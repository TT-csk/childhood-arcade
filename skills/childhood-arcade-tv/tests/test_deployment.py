import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import deployment as d


class Device:
    def __init__(self, sdk=30, free=1024**3, identity='device-a', signature_error=False, backup_error=False, fresh=False):
        self.serial = '192.168.50.9:5555'
        self.info = {'model': 'test-tv', 'identity': identity, 'sdk': sdk, 'free_bytes': free}
        self.signature_error, self.backup_error = signature_error, backup_error
        self.fresh, self.installed = fresh, False
        self.commands = []

    def connect(self):
        self.commands.append(('connect', self.serial))

    def probe(self):
        return self.info

    def shell(self, *args, **kwargs):
        self.commands.append(args)
        if args[:2] == ('pm', 'path'):
            if self.fresh and not self.installed:
                return ''
            return 'package:/data/app/arcade/base.apk'
        if args[0] == 'sha256sum':
            return 'b' * 64 + '  ' + args[1]
        raise AssertionError(args)

    def backup(self, path):
        self.commands.append(('backup', str(path)))
        if self.backup_error:
            raise d.DeploymentError('backup_unavailable', 'test backup failure')
        return {'path': str(path), 'entries': 1}

    def run(self, args, **kwargs):
        self.commands.append(tuple(args))
        if 'install' in args:
            if self.signature_error:
                return '', 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]', 1
            self.installed = True
            return 'Success', '', 0
        if 'am' in args:
            return 'Status: ok', '', 0
        if 'screencap' in args:
            return b''
        raise AssertionError(args)

    def page(self):
        return {'ready': True, 'title': '童年游戏厅', 'state': {'page': 'home'}, 'visible': 'visible', 'catalogCount': 8}


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.apk = {'path': '/test.apk', 'bytes': 8 * 1024 * 1024, 'sha256': 'a' * 64, 'required_free_bytes': 32 * 1024 * 1024}

    def invoke(self, client, config=None):
        report = {'run_id': 'test-run', 'installed': False}
        status = d.deploy(self.home, config or {'schema': 1}, client, self.apk, report, lambda _: None)
        return status, report

    def blocked(self, client, expected, config=None):
        with self.assertRaises(d.DeploymentError) as caught:
            self.invoke(client, config)
        self.assertEqual(caught.exception.status, expected)
        self.assertFalse(any('install' in command for command in client.commands))

    def test_same_address_different_device_cannot_be_updated(self):
        self.blocked(Device(identity='new-device'), 'target_changed', {'target': {'identity': 'old-device'}})

    def test_wrong_model_cannot_be_installed(self):
        self.blocked(Device(), 'wrong_model', {'expected_model': 'another-tv'})

    def test_sdk_below_23_cannot_be_installed(self):
        self.blocked(Device(sdk=22), 'unsupported_android')

    def test_low_space_stops_before_install(self):
        self.blocked(Device(free=4096), 'insufficient_space')

    def test_backup_failure_preserves_existing_install(self):
        self.blocked(Device(backup_error=True), 'backup_unavailable')

    def test_signature_conflict_does_not_uninstall_or_clear(self):
        client = Device(signature_error=True)
        with self.assertRaises(d.DeploymentError) as caught:
            self.invoke(client)
        self.assertEqual(caught.exception.status, 'signature_conflict')
        self.assertTrue(any(command[0] == 'backup' for command in client.commands))
        self.assertFalse(any('uninstall' in command or 'clear' in command for command in client.commands))

    def test_success_backs_up_before_selected_target_install(self):
        client = Device()
        status, report = self.invoke(client)
        self.assertEqual(status[0], 'installed_verified')
        backup_at = next(i for i, command in enumerate(client.commands) if command[0] == 'backup')
        install_at = next(i for i, command in enumerate(client.commands) if 'install' in command)
        self.assertLess(backup_at, install_at)
        self.assertEqual(client.commands[install_at][0:2], ('-s', client.serial))
        self.assertTrue(report['page_verified'])
        self.assertFalse(report['physical_gameplay_verified'])

    def test_first_install_does_not_require_nonexistent_save_directory(self):
        client = Device(fresh=True)
        status, report = self.invoke(client)
        self.assertEqual(status[0], 'installed_verified')
        self.assertFalse(report['existing_install'])
        self.assertFalse(any(command[0] == 'backup' for command in client.commands))

    def test_process_and_install_success_are_insufficient_without_page(self):
        client = Device()
        client.page = lambda: {'ready': False}
        with patch.object(d.time, 'monotonic', side_effect=[0, 0, 13]), patch.object(d.time, 'sleep'):
            with self.assertRaises(d.DeploymentError) as caught:
                self.invoke(client)
        self.assertEqual(caught.exception.status, 'installed_unverified')

    def test_authorization_failure_never_sends_install(self):
        client = d.Client('/fake-adb', '192.168.50.9:5555')
        calls = []
        def command(args, **kwargs):
            calls.append(args)
            return '', 'error: device unauthorized', 1
        client.run = command
        with self.assertRaises(d.DeploymentError) as caught:
            client.connect()
        self.assertEqual(caught.exception.status, 'needs_authorization')
        self.assertFalse(any('install' in args or 'shell' in args for args in calls))

    def test_network_error_has_its_own_actionable_state(self):
        client = d.Client('/fake-adb', '192.168.50.9:5555')
        client.run = lambda *a, **k: ('', 'connection refused', 1)
        with self.assertRaises(d.DeploymentError) as caught:
            client.connect()
        self.assertEqual(caught.exception.status, 'network_unreachable')
        self.assertIn('VPN', str(caught.exception))

    def test_configure_resource_pack_checks_contents_and_preserves_previous_config(self):
        pack = self.home / 'pack'
        pack.mkdir()
        rom = pack / 'sample.gba'
        rom.write_bytes(b'game data')
        manifest = pack / 'manifest.json'
        manifest.write_text(json.dumps({'schema': 1, 'files': [{'name': 'sample.gba', 'path': 'sample.gba', 'bytes': 9, 'sha256': hashlib.sha256(rom.read_bytes()).hexdigest()}]}))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(d.main(['--home', str(self.home / 'config'), 'configure', '--game-pack-manifest', str(manifest)]), 0)
        config_path = self.home / 'config' / 'deployment.json'
        before = config_path.read_bytes()
        self.assertEqual(json.loads(before)['game_pack_manifest'], str(manifest.resolve()))
        rom.write_bytes(b'bad')
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(d.main(['--home', str(self.home / 'config'), 'configure', '--game-pack-manifest', str(manifest)]), 2)
        self.assertEqual(json.loads(out.getvalue())['status'], 'invalid_game_pack')
        self.assertEqual(config_path.read_bytes(), before)

    def test_bad_hash_stops_before_any_adb_operation(self):
        apk = self.home / 'app.apk'
        apk.write_bytes(b'changed')
        d.atomic_json(self.home / 'deployment.json', {'apk': {'path': str(apk), 'sha256': '0' * 64}})
        with patch.object(d.subprocess, 'run') as command, contextlib.redirect_stdout(io.StringIO()) as out:
            code = d.main(['--home', str(self.home), 'deploy', '--serial', '192.168.50.9'])
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(out.getvalue())['status'], 'apk_changed')
        command.assert_not_called()

    def test_resume_uses_pending_new_address_before_old_bound_address(self):
        apk = self.home / 'app.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            for name in ('AndroidManifest.xml', 'classes.dex', 'assets/index.html'):
                archive.writestr(name, 'test')
        config = {'apk': {'path': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}, 'adb': '/fake-adb',
                  'target': {'serial': '192.168.50.8:5555', 'identity': 'device-a'}, 'pending_serial': '192.168.50.9:5555'}
        d.atomic_json(self.home / 'deployment.json', config)
        with patch.object(d, 'Client') as client, patch.object(d, 'deploy', return_value=('installed_verified', 'test')) as deploy, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(d.main(['--home', str(self.home), 'deploy']), 0)
        client.assert_called_once_with('/fake-adb', '192.168.50.9:5555')
        self.assertEqual(deploy.call_count, 1)

    def test_missing_target_does_not_select_another_connected_device(self):
        with self.assertRaises(d.DeploymentError) as caught:
            d.selected_serial(None)
        self.assertEqual(caught.exception.status, 'needs_target')
        self.assertEqual(d.selected_serial('192.168.50.9'), '192.168.50.9:5555')
        for value in ('-s', '192.168.1.999', 'host:0', 'host:65536', 'host;echo test'):
            with self.assertRaises(d.DeploymentError):
                d.selected_serial(value)

    def test_old_and_new_android_df_formats(self):
        self.assertEqual(d.free_bytes('Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/dm 100000 10 99990 1% /data'), 99990 * 1024)
        self.assertEqual(d.free_bytes('Filesystem Size Used Free Blksize\n/data 5.0G 3.9G 1.1G 4096', False), int(1.1 * 1024**3))
        self.assertIsNone(d.free_bytes('df: unknown option'))

    def test_backup_excludes_roms_and_never_overwrites_previous_backup(self):
        client = d.Client('/fake-adb', 'explicit-device')
        slot = 'slot:' + 'a' * 64 + ':0.json'
        def command(args, **kwargs):
            if args[-2:] == ['ls', 'files/store']:
                return 'prefs.json\n' + slot + '\nrom:private.json\ncustom.json', '', 0
            return '{"saved":true}', '', 0
        client.run = command
        target = self.home / 'backup.json'
        self.assertEqual(client.backup(target)['entries'], 2)
        before = target.read_bytes()
        self.assertTrue(all(not entry['key'].startswith('rom:') for entry in json.loads(before)['entries']))
        with self.assertRaises(FileExistsError):
            client.backup(target)
        self.assertEqual(target.read_bytes(), before)


    def test_page_selects_exact_ui_process_without_trusting_xiaomi_pidof(self):
        client = d.Client('/fake-adb', 'explicit-tv')
        table = 'USER PID NAME\nroot 1 init\nu0_a55 777 org.childhood.arcade:service\nu0_a55 123 org.childhood.arcade\n'
        target = {'url': 'https://appassets.androidplatform.net/assets/index.html', 'webSocketDebuggerUrl': 'ws://127.0.0.1:22222/devtools/page/test'}
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            return ('22222' if 'tcp:0' in args else '', '', 0)
        with patch.object(client, 'shell', return_value=table) as shell, patch.object(client, 'run', side_effect=run), patch.object(d, 'build_opener') as opener, patch.object(d, 'page_health', return_value={'ready': True}):
            opener.return_value.open.return_value = io.BytesIO(json.dumps([target]).encode())
            self.assertTrue(client.page()['ready'])
        self.assertTrue(any('localabstract:webview_devtools_remote_123' in command for command in calls))
        self.assertFalse(any('pidof' in call.args for call in shell.call_args_list))


if __name__ == '__main__':
    unittest.main()
