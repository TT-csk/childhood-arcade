"""Verified, sequential private game-data installation; never replaces a conflicting file."""
import hashlib
import json
from pathlib import Path
import re
import shlex


def read_pack(path):
    manifest = Path(path).expanduser().resolve()
    value = json.loads(manifest.read_text(encoding='utf-8'))
    if value.get('schema') != 1 or not isinstance(value.get('files'), list) or not value['files']:
        raise ValueError('Invalid game resource manifest')
    result, seen = [], set()
    for entry in value['files']:
        name, digest = entry.get('name', ''), entry.get('sha256', '')
        if not re.fullmatch(r'[a-z0-9-]{1,60}\.(?:zip|gba|md|sfc)', name) or name in seen or not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise ValueError('Invalid or duplicate resource identity')
        relative = Path(entry.get('path', ''))
        if relative.is_absolute() or '..' in relative.parts or not relative.parts:
            raise ValueError('Invalid resource path')
        source = (manifest.parent / relative).resolve()
        if not source.is_relative_to(manifest.parent) or not source.is_file():
            raise ValueError('Resource is missing or outside its pack')
        size = source.stat().st_size
        if size != entry.get('bytes') or not 0 < size <= 256 * 1024 * 1024:
            raise ValueError('Resource size mismatch')
        with source.open('rb') as file:
            hasher = hashlib.sha256()
            for chunk in iter(lambda: file.read(262144), b''):
                hasher.update(chunk)
            actual = hasher.hexdigest()
        if actual != digest:
            raise ValueError('Resource checksum mismatch: ' + name)
        seen.add(name)
        result.append(dict(entry, source=str(source)))
    return result


def resource_tool(client, package, apk_path, args):
    if getattr(client, 'transport', None) == 'provider':
        from release_transport import call
        return call(client, args[0], args[2] if args[0] == 'install' else args[1])
    command = 'CLASSPATH=' + shlex.quote(apk_path) + ' app_process /system/bin org.childhood.arcade.ResourceTool ' + ' '.join(shlex.quote(x) for x in args)
    raw = client.shell('run-as', package, '/system/bin/sh', '-c', shlex.quote(command), timeout=180)
    line = next((x[len('RESOURCE_JSON '):] for x in raw.splitlines() if x.startswith('RESOURCE_JSON ')), None)
    if line is None:
        raise ValueError('Resource verification returned no result')
    result = json.loads(line)
    if result.get('error'):
        raise ValueError(result['error'])
    return result


def install_pack(client, package, entries, announce, report):
    raw = client.shell('pm', 'path', package)
    paths = [x[len('package:'):] for x in raw.splitlines() if x.startswith('package:')]
    if len(paths) != 1 or not paths[0].startswith('/data/app/') or not paths[0].endswith('.apk'):
        raise ValueError('Installed package path could not be verified')
    apk_path = paths[0]
    checks = {entry['name']: resource_tool(client, package, apk_path, ['verify', entry['name'], entry['sha256']]) for entry in entries}
    pending = [entry for entry in entries if not checks[entry['name']].get('verified')]
    for entry in pending:
        if checks[entry['name']].get('bytes', 0):
            raise ValueError('A different installed resource was preserved: ' + entry['name'])
    required = sum(entry['bytes'] for entry in pending) + max((entry['bytes'] for entry in pending), default=0) + 16 * 1024 * 1024
    available = client.probe().get('free_bytes')
    if available is None or available < required:
        raise ValueError('Insufficient or unknown free space for remaining game resources')
    report['resources'] = []
    for index, entry in enumerate(entries):
        name, digest = entry['name'], entry['sha256']
        row = {'name': name, 'sha256': digest}
        report['resources'].append(row)
        checked = checks[name]
        if checked.get('verified'):
            row.update(verified=True, status='already_verified')
            continue
        if checked.get('bytes', 0):
            raise ValueError('A different installed resource was preserved: ' + name)
        announce('安装游戏资源 %d/%d：%s' % (index + 1, len(entries), name))
        stage = '/data/local/tmp/childhood-arcade-' + digest + '.zip'
        try:
            client.run(['-s', client.serial, 'push', entry['source'], stage], timeout=max(180, entry['bytes'] // 100000 + 120))
            value = resource_tool(client, package, apk_path, ['install', stage, name, digest])
            if not value.get('verified') or value.get('sha256') != digest or value.get('bytes') != entry['bytes']:
                raise ValueError('Installed resource did not match manifest: ' + name)
            row.update(value)
        finally:
            client.shell('rm', '-f', stage, allow_failure=True)
