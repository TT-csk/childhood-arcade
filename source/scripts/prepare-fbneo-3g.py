"""实验性驱动目录校验调整：只接受已核对 SHA256 的 Android FBNeo。"""
import hashlib, struct, sys
from pathlib import Path
source, target = map(Path, sys.argv[1:3])
data = source.read_bytes()
expected = '5a15ab447444632d2821c440d4e3af17f1794b6c9cfeee0fbec1f1c05e34aba5'
old, replacement = struct.pack('<I', 0xaf90d166), struct.pack('<I', 0x8458fff9)
if hashlib.sha256(data).hexdigest() != expected or data.count(old) != 1:
    raise SystemExit('输入核心或目标校验字段不匹配，拒绝修改')
output = data.replace(old, replacement)
target.write_bytes(output)
print(hashlib.sha256(output).hexdigest())
