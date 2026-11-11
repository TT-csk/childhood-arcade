"""One-command deployment of Childhood Arcade, using a selected ADB target."""
import argparse
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.request import ProxyHandler, build_opener
import uuid
import zipfile
from webview import page_health
from resources import read_pack, install_pack

PACKAGE = 'org.childhood.arcade'
MIN_SDK = 23
SLOT = re.compile(r'(?:prefs|slot:[a-f0-9]{64}:[0-3]|battery:[a-f0-9]{64})\.json')


class DeploymentError(Exception):
    def __init__(self, status, message, **details):
        super().__init__(message)
        self.status, self.details = status, details


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temporary.open('x', encoding='utf-8') as file:
            os.chmod(temporary, 0o600)
            json.dump(value, file, ensure_ascii=False, indent=2)
            file.flush()
            os.fsync(file.fileno())
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def read_config(home):
    path = home / 'deployment.json'
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'schema': 1}


def selected_serial(value):
    if not value:
        raise DeploymentError('needs_target', '首次安装需要电视当前IP地址，并在电视开启ADB调试；不会自动扫描或选择其他设备。')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,180}', value):
        raise DeploymentError('invalid_target', '设备地址格式无效。')
    if re.fullmatch(r'\d+\.\d+\.\d+\.\d+(?::\d+)?', value):
        host, _, port = value.partition(':')
        try:
            ipaddress.IPv4Address(host)
            if port and not 1 <= int(port) <= 65535:
                raise ValueError()
        except ValueError:
            raise DeploymentError('invalid_target', '电视IP地址或端口无效。')
        return host + ':' + (port or '5555')
    if ':' in value:
        host, _, port = value.rpartition(':')
        if ':' in host or not port.isdigit() or not 1 <= int(port) <= 65535:
            raise DeploymentError('invalid_target', '请提供IPv4地址、主机名:端口或明确的USB设备标识。')
    return value


def artifact(path, expected):
    path = Path(path).expanduser().resolve()
    if not path.is_file() or path.suffix.lower() != '.apk':
        raise DeploymentError('needs_apk', '本机没有找到已配置的APK，请先构建或提供安装包。', path=str(path))
    if not expected or not re.fullmatch(r'[a-f0-9]{64}', expected):
        raise DeploymentError('needs_apk_hash', '需要配置已核对安装包的SHA-256。')
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected:
        raise DeploymentError('apk_changed', '安装包内容与已配置的SHA-256不一致，已停止安装。', actual=digest, expected=expected)
    try:
        with zipfile.ZipFile(path) as archive:
            if not {'AndroidManifest.xml', 'classes.dex', 'assets/index.html'}.issubset(archive.namelist()):
                raise ValueError()
            expanded = sum(item.file_size for item in archive.infolist())
    except (zipfile.BadZipFile, ValueError):
        raise DeploymentError('invalid_apk', '安装包结构不完整。')
    return {'path': str(path), 'sha256': digest, 'bytes': path.stat().st_size,
            'required_free_bytes': max(32 * 1024 * 1024, expanded * 2 + path.stat().st_size * 3)}


def free_bytes(output, kilobytes=True):
    lines = [line.split() for line in output.strip().splitlines() if line.strip()]
    if len(lines) < 2:
        return None
    header = [word.lower() for word in lines[0]]
    column = next((i for i, word in enumerate(header) if word in ('available', 'avail', 'free')), None)
    row = lines[-1]
    if column is None or len(row) <= column:
        return None
    match = re.fullmatch(r'([\d.]+)([KMGT]?)(?:i?B)?', row[column], re.I)
    if not match:
        return None
    suffix = match.group(2).upper()
    multiplier = 1024 ** ('KMGT'.index(suffix) + 1) if suffix else (1024 if kilobytes else 1)
    return int(float(match.group(1)) * multiplier)


class Client:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def run(self, args, timeout=12, allow_failure=False, binary=False):
        try:
            result = subprocess.run([self.adb] + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise DeploymentError('connection_timeout', '设备操作超时；请检查电视是否在线、电脑到电视的网络，以及VPN的局域网访问设置。', command=args[:4])
        except OSError as error:
            raise DeploymentError('adb_unavailable', '无法运行ADB工具。', error=str(error))
        if result.returncode and not allow_failure:
            text = (result.stderr + result.stdout).decode('utf-8', 'replace')
            if 'unauthorized' in text.lower():
                raise DeploymentError('needs_authorization', '请在电视上选择“允许此电脑调试”，然后发“继续安装游戏厅”。')
            raise DeploymentError('adb_failed', '设备命令未完成。', output=text[-2000:])
        if binary:
            return result.stdout
        return result.stdout.decode('utf-8', 'replace').strip(), result.stderr.decode('utf-8', 'replace').strip(), result.returncode

    def shell(self, *args, **kwargs):
        return self.run(['-s', self.serial, 'shell'] + list(args), **kwargs)[0]

    def connect(self):
        details = ''
        if ':' in self.serial:
            out, err, _ = self.run(['connect', self.serial], timeout=8, allow_failure=True)
            details = out + '\n' + err
        out, err, _ = self.run(['-s', self.serial, 'get-state'], timeout=5, allow_failure=True)
        details += '\n' + out + '\n' + err
        if out == 'device':
            return
        if 'unauthorized' in details.lower() or 'failed to authenticate' in details.lower():
            raise DeploymentError('needs_authorization', '请在电视上允许此电脑的调试连接，然后发“继续安装游戏厅”。', serial=self.serial)
        if 'offline' in details.lower():
            raise DeploymentError('device_offline', '电视ADB当前离线，请唤醒电视并检查ADB开关后继续。', serial=self.serial)
        raise DeploymentError('network_unreachable', '无法连接这台电视。请核对当前IP、ADB开关及局域网互通；电脑开VPN时检查“允许访问局域网”。不同地点的普通VPN不保证互通。', serial=self.serial, output=details.strip()[-1500:])

    def probe(self):
        def prop(name):
            return self.shell('getprop', name)
        sdk = prop('ro.build.version.sdk')
        if not sdk.isdigit():
            raise DeploymentError('device_not_ready', '电视系统尚未就绪，无法读取Android版本。')
        model, device = prop('ro.product.model'), prop('ro.product.device')
        serialno = prop('ro.serialno') or prop('ro.boot.serialno')
        android_id = self.shell('settings', 'get', 'secure', 'android_id', allow_failure=True)
        if not re.fullmatch(r'[a-fA-F0-9]{8,64}', android_id):
            android_id = ''
        stable = [value for value in (serialno, android_id) if value and value.lower() not in ('unknown', 'null', '0')]
        if not stable:
            raise DeploymentError('identity_unavailable', '无法读取稳定的设备标识，尚不能绑定自动安装目标。')
        identity = hashlib.sha256(json.dumps([model, device] + stable).encode()).hexdigest()
        space = self.shell('df', '-k', '/data', allow_failure=True)
        available = free_bytes(space)
        if available is None:
            space = self.shell('df', '/data', allow_failure=True)
            available = free_bytes(space, False)
        return {'model': model, 'device': device, 'sdk': int(sdk), 'android': prop('ro.build.version.release'),
                'abi': prop('ro.product.cpu.abilist') or prop('ro.product.cpu.abi'), 'identity': identity,
                'free_bytes': available, 'space_output': space}

    def backup(self, path):
        if getattr(self, 'transport', None) == 'provider':
            from release_transport import backup
            return backup(self, path)
        out, err, code = self.run(['-s', self.serial, 'exec-out', 'run-as', PACKAGE, 'ls', 'files/store'], allow_failure=True)
        if code:
            if 'No such file or directory' in out + err and 'run-as:' not in out + err:
                out = ''
            else:
                raise DeploymentError('backup_unavailable', '已有应用的自动备份未完成，已停止覆盖安装。可先在游戏厅设置中导出备份。', output=(out + err)[-1200:])
        entries = []
        for name in out.splitlines():
            if SLOT.fullmatch(name):
                raw = self.run(['-s', self.serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'files/store/' + name])[0]
                entries.append({'key': name[:-5], 'value': json.loads(raw)})
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('x', encoding='utf-8') as file:
            os.chmod(path, 0o600)
            json.dump({'schema': 1, 'app': 'childhood-arcade', 'entries': entries}, file, ensure_ascii=False)
        return {'path': str(path), 'entries': len(entries), 'includes_roms': False}

    def page(self):
        if getattr(self, "transport", None) == "provider":
            from release_transport import call
            return call(self, "health")
        # Xiaomi Android 6 pidof returns every PID, ignoring the package argument.
        # Match the exact UI-process name instead of trusting its first result.
        rows = self.shell('ps', '-A', allow_failure=True).splitlines()
        pid = [row.split()[1] for row in rows if len(row.split()) > 1 and row.split()[-1] == PACKAGE]
        if not pid:
            rows = self.shell('ps', allow_failure=True).splitlines()
            pid = [row.split()[1] for row in rows if len(row.split()) > 1 and row.split()[-1] == PACKAGE]
        if not pid or not pid[0].isdigit():
            raise ValueError('未找到游戏厅进程')
        port = self.run(['-s', self.serial, 'forward', 'tcp:0', 'localabstract:webview_devtools_remote_' + pid[0]])[0]
        if not port.isdigit():
            raise ValueError('无法创建本机页面检查通道')
        try:
            opener = build_opener(ProxyHandler({}))
            with opener.open('http://127.0.0.1:' + port + '/json/list', timeout=3) as response:
                targets = json.load(response)
            target = next(item for item in targets if item.get('url') in ('file:///android_asset/index.html', 'https://appassets.androidplatform.net/assets/index.html'))
            return page_health(target['webSocketDebuggerUrl'], int(port))
        finally:
            self.run(['-s', self.serial, 'forward', '--remove', 'tcp:' + port], allow_failure=True)


def deploy(home, config, client, apk, report, announce):
    entries = []
    if config.get('game_pack_manifest'):
        try:
            entries = read_pack(config['game_pack_manifest'])
        except (OSError, ValueError, KeyError) as error:
            raise DeploymentError('invalid_game_pack', '游戏资源包未通过本地校验，已停止安装。', error=str(error))
    announce('连接电视并检查授权')
    client.connect()
    info = client.probe()
    report['device'] = info
    expected_model = config.get('expected_model')
    if expected_model and info['model'] != expected_model:
        raise DeploymentError('wrong_model', '连接到的设备型号与已选电视不一致，已停止安装。', expected=expected_model, actual=info['model'])
    previous = config.get('target')
    if previous and previous['identity'] != info['identity']:
        raise DeploymentError('target_changed', '该地址对应的设备与已绑定电视不同，已停止安装。请先确认是否更换了电视。', actual_model=info['model'])
    if info['sdk'] < MIN_SDK:
        raise DeploymentError('unsupported_android', '当前APK最低要求Android 6 / API23。', actual_sdk=info['sdk'])
    config['target'] = {'serial': client.serial, 'identity': info['identity'], 'model': info['model']}
    config.pop('pending_serial', None)
    atomic_json(home / 'deployment.json', config)
    announce('检查现有版本并备份进度')
    existing = client.shell('pm', 'path', PACKAGE, allow_failure=True)
    report['existing_install'] = existing.startswith('package:')
    if report['existing_install']:
        report['backup'] = client.backup(home / 'backups' / (report['run_id'] + '.json'))
    matched = False
    if report['existing_install']:
        paths = [line[len('package:'):] for line in existing.splitlines() if line.startswith('package:')]
        if len(paths) == 1 and re.fullmatch(r'/data/app/[A-Za-z0-9._-]+/base\.apk', paths[0]):
            checksum = client.shell('sha256sum', paths[0], allow_failure=True).split()
            matched = bool(checksum and checksum[0].lower() == apk['sha256'])
    if matched:
        announce('电视安装包哈希一致，跳过重复安装')
        report['install_skipped'] = True
        out, err, code = 'Success: installed APK already matches expected SHA-256', '', 0
    else:
        if info['free_bytes'] is not None and info['free_bytes'] < apk['required_free_bytes']:
            raise DeploymentError('insufficient_space', '电视可用空间不足，未执行安装；请先在电视上释放空间。', available=info['free_bytes'], required=apk['required_free_bytes'])
        announce('覆盖安装游戏厅，保留应用数据')
        out, err, code = client.run(['-s', client.serial, 'install', '--no-streaming', '--no-incremental', '-r', apk['path']], timeout=max(240, min(600, 60 + apk['bytes'] // (128 * 1024))), allow_failure=True)
    report['install_output'] = (out + '\n' + err).strip()
    if code or not re.search(r'\bSuccess\b', out):
        output = out + err
        if 'UPDATE_INCOMPATIBLE' in output or 'signatures do not match' in output:
            raise DeploymentError('signature_conflict', '已有应用签名与安装包不一致，已停止；没有卸载或清除存档。')
        if 'INSUFFICIENT_STORAGE' in output:
            raise DeploymentError('insufficient_space', '电视报告空间不足，未清除应用数据。')
        if 'USER_RESTRICTED' in output:
            raise DeploymentError('installation_restricted', '电视禁止此次安装，请查看电视安装提示或“允许安装未知来源应用”开关。')
        raise DeploymentError('installation_failed', 'APK未安装成功，已停止，不自动卸载重试。', output=output[-2000:])
    report['installed'] = True
    if not client.shell('pm', 'path', PACKAGE).startswith('package:'):
        raise DeploymentError('package_not_found', '安装命令返回成功，但未找到游戏厅包，尚未完成验证。')
    if entries:
        try:
            install_pack(client, PACKAGE, entries, announce, report)
        except (ValueError, OSError) as error:
            raise DeploymentError('game_pack_incomplete', 'APK已安装，游戏资源尚未全部就绪；再次更新会跳过已校验文件。', error=str(error))
    announce('启动并验证游戏厅页面')
    out, err, launch_code = client.run(['-s', client.serial, 'shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity'], allow_failure=True)
    launch = (out + '\n' + err).strip()
    report['launch_output'] = launch
    if launch_code or 'Error:' in launch or 'Exception' in launch:
        raise DeploymentError('launch_failed', 'APK已安装，启动未完成；请查看安装报告。')
    started, error = time.monotonic(), ''
    report['page_verified'] = False
    while time.monotonic() - started < 12:
        try:
            health = client.page()
            report['page'] = health
            if health and health.get('ready') and health.get('title') == '童年游戏厅' and health.get('state', {}).get('page') not in ('loading', None) and health.get('visible') == 'visible':
                report['page_verified'] = True
                break
        except Exception as exception:
            error = str(exception)
        time.sleep(0.3)
    image = client.run(['-s', client.serial, 'exec-out', 'screencap', '-p'], allow_failure=True, binary=True)
    # Some Xiaomi Android 6 builds print "argc: 3" before the raw PNG bytes.
    png = image.find(b'\x89PNG\r\n\x1a\n')
    if 0 < png < 256:
        image = image[png:]
    if image.startswith(b'\x89PNG\r\n\x1a\n'):
        path = home / 'reports' / (report['run_id'] + '.png')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(image)
        os.chmod(path, 0o600)
        report['screenshot'] = str(path)
    report['physical_gameplay_verified'] = False
    report['audio_listening_verified'] = False
    if not report['page_verified']:
        raise DeploymentError('installed_unverified', 'APK已安装，页面未能自动确认。请查看电视屏幕或截图；不要重复卸载安装。', page_check_error=error)
    config['last_install'] = {'sha256': apk['sha256'], 'run_id': report['run_id'], 'page_verified': True}
    atomic_json(home / 'deployment.json', config)
    return 'installed_verified', '游戏厅已安装并显示页面。声音、游戏流畅度与实体手柄仍需要实机体验确认。'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', default=os.environ.get('ARCADE_DEPLOY_HOME', str(Path.home() / '.config/childhood-arcade')))
    parser.add_argument('--adb')
    parser.add_argument('--serial', dest='global_serial')
    commands = parser.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('configure')
    setup.add_argument('--apk')
    setup.add_argument('--sha256')
    setup.add_argument('--game-pack-manifest', help='Path to a verified game pack manifest')
    setup.add_argument('--expected-model')
    setup.add_argument('--transport', choices=['legacy', 'provider'])
    setup.add_argument('--forget-target', action='store_true')
    for name in ('deploy', 'doctor', 'probe', 'install', 'backup', 'restore'):
        command = commands.add_parser(name)
        command.add_argument('--serial')
        if name == 'install':
            command.add_argument('--apk')
        if name == 'restore':
            command.add_argument('--input', required=True)
        if name == 'backup':
            command.add_argument('--output', required=True)
    commands.add_parser('status')
    args = parser.parse_args(argv)
    home = Path(args.home).expanduser().resolve()
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8]
    report = {'schema': 1, 'run_id': run_id, 'command': args.command, 'installed': False, 'page_verified': False}
    code = 0
    try:
        config = read_config(home)
        adb = args.adb or os.environ.get('ARCADE_ADB') or config.get('adb') or shutil.which('adb')
        if args.command == 'configure':
            if args.apk:
                value = artifact(args.apk, args.sha256)
                config['apk'] = {'path': value['path'], 'sha256': value['sha256']}
            if args.game_pack_manifest:
                manifest = Path(args.game_pack_manifest).expanduser().resolve()
                try:
                    read_pack(manifest)
                except (OSError, ValueError, KeyError) as error:
                    raise DeploymentError('invalid_game_pack', '游戏资源包未通过校验；配置没有更改。', error=str(error))
                config['game_pack_manifest'] = str(manifest)
            if args.transport:
                config['transport'] = args.transport
            if args.expected_model:
                config['expected_model'] = args.expected_model
            if args.forget_target:
                config.pop('target', None)
                config.pop('pending_serial', None)
            if adb:
                if not Path(adb).is_file():
                    raise DeploymentError('adb_unavailable', '配置的ADB文件不存在。')
                config['adb'] = str(Path(adb).resolve())
            atomic_json(home / 'deployment.json', config)
            report.update(status='configured', message='本机部署配置已保存；尚未连接或安装电视。', config_path=str(home / 'deployment.json'))
        elif args.command == 'status':
            report.update(status='configured' if config.get('apk') else 'needs_apk', configuration=config)
        else:
            apk = None
            if args.command in ('deploy', 'install'):
                configured = config.get('apk', {})
                apk_path = getattr(args, 'apk', None) or configured.get('path')
                if not apk_path:
                    raise DeploymentError('needs_apk', '请先配置已核对的本地APK。')
                apk = artifact(apk_path, configured.get('sha256'))
                report['apk'] = apk
            serial = selected_serial(getattr(args, 'serial', None) or args.global_serial or config.get('pending_serial') or config.get('target', {}).get('serial'))
            report['serial'] = serial
            if not adb:
                raise DeploymentError('adb_unavailable', '请安装官方Android Platform Tools或配置ADB路径。')
            client = Client(adb, serial)
            client.transport = config.get("transport", "legacy")
            if args.command in ('deploy', 'install'):
                config['pending_serial'] = serial
                atomic_json(home / 'deployment.json', config)
                report['status'], report['message'] = deploy(home, config, client, apk, report, lambda message: print(message, file=sys.stderr, flush=True))
            elif args.command == 'restore':
                client.connect()
                info = client.probe()
                if config.get('target') and config['target']['identity'] != info['identity']:
                    raise DeploymentError('target_changed', '目标电视改变，未恢复存档。')
                if client.transport != 'provider':
                    raise DeploymentError('unsupported_restore', '此恢复命令仅支持正式版传输协议。')
                from release_transport import restore
                report.update(status='restored', restore=restore(client, args.input))
            elif args.command == 'backup':
                client.connect()
                info = client.probe()
                if config.get('target') and config['target']['identity'] != info['identity']:
                    raise DeploymentError('target_changed', '当前设备与已绑定电视不同，没有导出数据。')
                report.update(status='backed_up', backup=client.backup(Path(args.output).expanduser().resolve()))
            else:
                client.connect()
                report.update(status='checked', device=client.probe())
    except DeploymentError as error:
        code = 3 if error.status == 'installed_unverified' else 2
        report.update(status=error.status, message=str(error), details=error.details)
    except Exception as error:
        code = 2
        report.update(status='operation_failed', message=str(error))
    report_path = home / 'reports' / (run_id + '.json')
    report['report_path'] = str(report_path)
    atomic_json(report_path, report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return code
