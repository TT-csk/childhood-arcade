"""Download a release file by explicit HTTPS URL and expected SHA-256."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlparse
from urllib.request import urlopen


def download(url, destination, expected):
    if urlparse(url).scheme != 'https' or not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('需要 HTTPS 地址和可信来源的 SHA-256')
    dest = Path(destination).expanduser().resolve()
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.download-', dir=str(dest.parent))
    try:
        with os.fdopen(fd, 'wb') as out, urlopen(url, timeout=60) as response:
            if urlparse(response.geturl()).scheme != 'https':
                raise ValueError('重定向必须使用 HTTPS')
            h = hashlib.sha256(); total = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk: break
                total += len(chunk)
                if total > 2 * 1024**3: raise ValueError('下载文件超过大小上限')
                h.update(chunk); out.write(chunk)
            if h.hexdigest() != expected: raise ValueError('下载文件 SHA-256 不匹配')
        os.replace(temporary, dest)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    return dest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True); parser.add_argument('--sha256', required=True); parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try: print(download(args.url, args.output, args.sha256))
    except Exception as error: parser.exit(2, '下载失败：%s\n' % error)

if __name__ == '__main__': main()
