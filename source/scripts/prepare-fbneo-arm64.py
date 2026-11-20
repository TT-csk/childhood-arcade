"""Reproduce validated ROM descriptor revisions for the pinned 64-bit FBNeo core."""
from pathlib import Path
import hashlib, json, struct
p=Path(__file__).resolve().parents[1]/'app/src/main/jniLibs/arm64-v8a';data=(p/'libfbneo.so').read_bytes()
expected='82637a59dbe07f54';assert hashlib.sha256(data).hexdigest().startswith(expected)
def modify(name,patches):
 result=bytearray(data)
 for anchor,old,new,stride in patches:
  marker=struct.pack('<I',anchor);assert data.count(marker)==1,(name,hex(anchor),'anchor not unique');pos=data.index(marker)
  for i,(a,b) in enumerate(zip(old,new)):
   loc=pos+i*stride;assert struct.unpack_from('<I',result,loc)[0]==a,(name,i,hex(struct.unpack_from('<I',result,loc)[0]),hex(a));struct.pack_into('<I',result,loc,b)
 (p/name).write_bytes(result);return {'file':name,'sha256':hashlib.sha256(result).hexdigest(),'patches':sum(a!=b for _,o,n,_ in patches for a,b in zip(o,n))}
a=0x3be747ab;old=[a,0x64952683,0xe8239365,0x89b21d4c,0x3cda13a0,0x9c00160d,0x38754256,0x59d33e9c,0xc9f8c357,0xfafc3eb9,0x346d4a30,0x7ff6ca47,0x696cce3b];new=old[:2]+[0x969c0d62,0xc69ae867,0xd7beaeaf,0xe1b1131b,0x2fa1a5ad,0x6de89589,0x97bd0c0a,0xc0d5bc20,0x6fa01c9a,0x0ab43275,0x358b1419]
results=[modify('libfbneo3g.so',[(0xaf90d166,[0xaf90d166],[0x8458fff9],24)]),modify('libfbneo5g.so',[(a,old,new,24)]),modify('libfbneo5w.so',[(0x94527837,[0x94527837,0x08ee1fe3],[0xf804b927,0x0af11b49],24),(0x4df30bcb,[0x4df30bcb],[0xb0c126da],24)])]
(p/'origin-variants.json').write_text(json.dumps({'sourceSha256':hashlib.sha256(data).hexdigest(),'source':'official libretro nightly arm64 FBNeo; CRC descriptor differences mirrored from verified 32-bit variants','entryStrideBytes':24,'results':results},indent=2)+'\n');print(json.dumps(results))
