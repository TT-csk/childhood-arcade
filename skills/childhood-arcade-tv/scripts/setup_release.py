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


def setup(descriptor, home, repository=None):
    data = json.loads(Path(descriptor).read_text(encoding='utf-8'))
    repository = repository or data.get('repository')
    if repository:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository): raise ValueError('Invalid GitHub repository')
        tag = data.get('tag', '')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', tag): raise ValueError('Invalid release tag')
        for key in ('apk', 'game_pack'):
            name=data[key].get('filename', '')
            if not re.fullmatch(r'[A-Za-z0-9_.-]+', name): raise ValueError('Invalid release filename')
            data[key]['url']='https://github.com/'+repository+'/releases/download/'+tag+'/'+name
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
    prepare(home, paths[0][0], paths[0][1], paths[1][0], paths[1][1])
    config = deployment.read_config(home)
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
    args = p.parse_args(argv)
    try:
        home = setup(args.release_json, args.home, args.repo)
    except Exception as exc:
        print('准备失败：' + str(exc), file=sys.stderr)
        return 2
    if args.serial:
        return deployment.main(['--home', str(home), 'deploy', '--serial', args.serial])
    print('资源已准备；提供电视 IP 后执行部署。')
    return 0

if __name__ == '__main__':
    sys.exit(main())
