"""仅为同系列替代画面资源构建 5 新战役实验核心，验证输入和描述符。"""
import hashlib,struct,sys
from pathlib import Path
src,dst=map(Path,sys.argv[1:3]);data=bytearray(src.read_bytes())
if hashlib.sha256(data).hexdigest()!='5a15ab447444632d2821c440d4e3af17f1794b6c9cfeee0fbec1f1c05e34aba5':raise SystemExit('输入核心不匹配')
old=[0x4df30bcb,0xf06c589a,0x64952683,0xe8239365,0x89b21d4c,0x3cda13a0,0x9c00160d,0x483a986c,0xd918f796,0xbdb9a887,0x6f8ac6fb,0x9577a692,0xc0cafa36,0xcc81efa3]
new=[0xb0c126da,0xf06c589a,0x64952683,0x969c0d62,0xc69ae867,0xd7beaeaf,0xe1b1131b,0x2fa1a5ad,0x6de89589,0x97bd0c0a,0xc0d5bc20,0x9577a692,0xc0cafa36,0xcc81efa3]
needle=struct.pack('<I',old[0])
if data.count(needle)!=1:raise SystemExit('描述符定位不唯一')
i=data.find(needle)
for k,(a,b) in enumerate(zip(old,new)):
 pos=i+16*k
 if struct.unpack_from('<I',data,pos)[0]!=a:raise SystemExit('描述符字段不匹配')
 struct.pack_into('<I',data,pos,b)
dst.write_bytes(data);print(hashlib.sha256(data).hexdigest())
