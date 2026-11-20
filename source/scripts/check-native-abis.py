"""Fail closed if either ABI is incomplete, changed, or incorrectly identified."""
from pathlib import Path
import hashlib,json,struct
root=Path(__file__).resolve().parents[1];libs=root/'app/src/main/jniLibs';lock=json.loads((root/'native-abi-lock.json').read_text())['abiLibraries'];ids=(root/'app/src/main/java/org/childhood/arcade/NativeCoreIdentity.java').read_text()
if set(lock)!={'armeabi-v7a','arm64-v8a'} or set(lock['armeabi-v7a'])!=set(lock['arm64-v8a']):raise SystemExit('原生库双架构集合不一致')
for abi,machine in [('armeabi-v7a',40),('arm64-v8a',183)]:
 actual={p.name for p in (libs/abi).glob('*.so')}
 if actual!=set(lock[abi]):raise SystemExit('原生库集合不完整：'+abi)
 for name,want in lock[abi].items():
  data=(libs/abi/name).read_bytes();sha=hashlib.sha256(data).hexdigest()
  if sha!=want or data[:4]!=b'\x7fELF' or struct.unpack_from('<H',data,18)[0]!=machine:raise SystemExit('原生库哈希或架构不匹配：'+abi+'/'+name)
  if abi=='arm64-v8a' and name!='libjnidispatch.so' and sha[:12] not in ids:raise SystemExit('64位核心身份未更新：'+name)
print('双架构原生库完整性校验通过：各11个')
