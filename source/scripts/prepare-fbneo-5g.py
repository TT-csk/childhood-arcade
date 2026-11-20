"""为旧版 5 多功能 ROM 构建隔离的实验核心；校验整文件和完整驱动描述符。"""
import hashlib, struct, sys
from pathlib import Path
source,target=map(Path,sys.argv[1:3]);data=bytearray(source.read_bytes())
if hashlib.sha256(data).hexdigest()!='5a15ab447444632d2821c440d4e3af17f1794b6c9cfeee0fbec1f1c05e34aba5':raise SystemExit('输入核心不匹配')
old=[0x3be747ab,0x64952683,0xe8239365,0x89b21d4c,0x3cda13a0,0x9c00160d,0x38754256,0x59d33e9c,0xc9f8c357,0xfafc3eb9,0x346d4a30,0x7ff6ca47,0x696cce3b]
new=old[:2]+[0x969c0d62,0xc69ae867,0xd7beaeaf,0xe1b1131b,0x2fa1a5ad,0x6de89589,0x97bd0c0a,0xc0d5bc20,0x6fa01c9a,0x0ab43275,0x358b1419]
anchor=struct.pack('<I',old[0])
if data.count(anchor)!=1:raise SystemExit('描述符定位不唯一')
i=data.find(anchor)
for k,(a,b) in enumerate(zip(old,new)):
 pos=i+16*k
 if struct.unpack_from('<I',data,pos)[0]!=a:raise SystemExit('描述符字段不匹配')
 struct.pack_into('<I',data,pos,b)
target.write_bytes(data);print(hashlib.sha256(data).hexdigest())
