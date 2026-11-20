"""Prepare a pinned release and optionally deploy to one explicit television."""
import argparse
import json
import re
from pathlib import Path
import sys
from urllib.parse import urlparse
import deployment
from fetch_verified import download
from prepare_bundle import prepare


def setup(descriptor, home, repository=None, abi="armeabi-v7a"):
    data = json.loads(Path(descriptor).read_text(encoding='utf-8'))
    if abi not in ('armeabi-v7a', 'arm64-v8a'): raise ValueError('不支持的安装包架构')
    if 'apk32' in data or 'apk64' in data:
        selected = 'apk32' if abi == 'armeabi-v7a' else 'apk64'
        if selected not in data: raise ValueError('所选架构安装包未提供')
        data['apk'] = dict(data[selected])
    elif abi != 'armeabi-v7a':
        raise ValueError('版本清单未提供 64 位安装包')
    repository = repository or data.get('repository')
    if repository:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository): raise ValueError('Invalid GitHub repository')
        tag = data.get('tag', '')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', tag): raise ValueError('Invalid release tag')
        for key in ('apk', 'game_pack', 'game_pack_v2'):
            if key not in data: continue
            name=data[key].get('filename', '')
            entry_tag=data[key].get('tag',tag)
            if not re.fullmatch(r'[A-Za-z0-9_.-]+', name): raise ValueError('Invalid release filename')
            if not re.fullmatch(r'[A-Za-z0-9_.-]+', entry_tag): raise ValueError('Invalid release tag')
            data[key]['url']='https://github.com/'+repository+'/releases/download/'+entry_tag+'/'+name
    if data.get('schema') != 1:
        raise ValueError('版本清单格式无效')
    home = Path(home).expanduser().resolve()
    downloads = home / 'downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    paths = []
    for key, filename in (('apk', 'arcade.apk'), ('game_pack', 'game-pack.zip')):
        entry = data.get(key, {})
        url, digest = entry.get('url', ''), entry.get('sha256', '')
        if urlparse(url).scheme != 'https' or not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ValueError('版本清单必须填写实际 HTTPS 下载链接')
        path = downloads / (digest + "-" + filename)
        download(url, path, digest)
        paths.append((path, digest))
    v2_path = None
    if 'game_pack_v2' in data:
        entry=data['game_pack_v2']
        url,digest=entry.get('url',''),entry.get('sha256','')
        if urlparse(url).scheme != 'https' or not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ValueError('v2.0 资源清单必须填写实际 HTTPS 下载链接')
        v2_path=downloads / (digest + '-game-pack-v2.zip')
        download(url,v2_path,digest)
    prepare(home, paths[0][0], paths[0][1], paths[1][0], paths[1][1])
    config = deployment.read_config(home)
    if v2_path is not None:
        config['v2_resource_pack'] = {'path':str(v2_path),'sha256':data['game_pack_v2']['sha256'],'import':'manual-in-hall'}
    config['transport'] = data.get('transport', 'legacy')
    if config['transport'] not in ('legacy', 'provider'): raise ValueError('Unknown release transport')
    deployment.atomic_json(home / 'deployment.json', config)
    return home


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--release-json', default=str(Path(__file__).resolve().parents[1] / 'assets' / 'release.json'))
    p.add_argument('--repo', help='GitHub owner/repository supplied by user')
    p.add_argument('--home', default=str(Path.home() / '.config' / 'childhood-arcade'))
    p.add_argument('--serial', help='Explicit television IP or ADB serial')
    p.add_argument('--abi', choices=['armeabi-v7a','arm64-v8a'], default='armeabi-v7a', help='旧用户默认32位；64位新用户明确选择')
    p.add_argument('--confirm-update', action='store_true', help='用户确认覆盖旧版本后使用')
    args = p.parse_args(argv)
    try:
        home = setup(args.release_json, args.home, args.repo, args.abi)
    except Exception as exc:
        print('准备失败：' + str(exc), file=sys.stderr)
        return 2
    if args.serial:
        command=['--home', str(home), 'deploy', '--serial', args.serial]
        if args.confirm_update: command.append('--confirm-update')
        return deployment.main(command)
    print('资源已准备；提供电视 IP 后执行部署。若包含 v2.0 资源包，请在游戏厅中单独导入。')
    return 0

if __name__ == '__main__':
    sys.exit(main())
