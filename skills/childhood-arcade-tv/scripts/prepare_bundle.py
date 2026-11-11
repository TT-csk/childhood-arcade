"""Prepare a hash-pinned local APK and resource ZIP without contacting a device."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import zipfile

from deployment import DeploymentError, artifact, atomic_json, read_config
from resources import read_pack


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def prepare(home, apk, apk_hash, archive, archive_hash):
    home = Path(home).expanduser().resolve()
    archive = Path(archive).expanduser().resolve()
    artifact(apk, apk_hash)
    if not archive.is_file() or sha256(archive) != archive_hash:
        raise ValueError('资源包不存在或 SHA-256 不匹配')
    bundles = home / 'bundles'
    bundles.mkdir(parents=True, exist_ok=True)
    destination = bundles / archive_hash
    if not destination.exists():
        with tempfile.TemporaryDirectory(prefix='.extract-', dir=str(bundles)) as tmp:
            with zipfile.ZipFile(archive) as z:
                names = set()
                total = 0
                for info in z.infolist():
                    path = PurePosixPath(info.filename)
                    if '\\' in info.filename or ':' in info.filename or path.is_absolute() or '..' in path.parts or not path.parts or info.filename in names or (info.external_attr >> 16) & 0o170000 == 0o120000:
                        raise ValueError('资源包包含不安全路径或重复文件')
                    names.add(info.filename)
                    total += info.file_size
                    if total > 2 * 1024**3 or len(names) > 5000:
                        raise ValueError('资源包超过解压限制')
                z.extractall(tmp)
            manifest = Path(tmp) / 'game-pack' / 'manifest.json'
            read_pack(manifest)
            os.rename(tmp, destination)
    manifest = destination / 'game-pack' / 'manifest.json'
    read_pack(manifest)
    config = read_config(home)
    config['apk'] = {'path': str(Path(apk).expanduser().resolve()), 'sha256': apk_hash}
    config['game_pack_manifest'] = str(manifest)
    atomic_json(home / 'deployment.json', config)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', required=True)
    parser.add_argument('--apk', required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--game-pack', required=True)
    parser.add_argument('--game-pack-sha256', required=True)
    args = parser.parse_args()
    try:
        print('资源配置完成：', prepare(args.home, args.apk, args.apk_sha256, args.game_pack, args.game_pack_sha256))
    except (OSError, ValueError, zipfile.BadZipFile, DeploymentError) as error:
        parser.exit(2, '准备失败：%s\n' % error)

if __name__ == '__main__':
    main()
