"""Build the split-resource Android release; provide explicit SDK/JDK and signing configuration."""
import argparse,hashlib,json,os,shutil,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--tools',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--signing-config',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];out=root/'build-production';out.mkdir(exist_ok=True);jdk=Path(a.jdk);tools=Path(a.tools);platform=Path(a.android_jar);sign=json.loads(Path(a.signing_config).read_text());env=dict(os.environ,JAVA_HOME=str(jdk),ARCADE_SIGNING_PASSWORD=sign['password'])
def run(args):subprocess.run([str(x) for x in args],check=True,env=env)
assets=out/'assets';shutil.rmtree(assets,ignore_errors=True);shutil.copytree(root/'app/src/main/assets',assets)
for rel in ['arcade','gba','md','sfc','vendor/fbneo','vendor/mgba','vendor/picodrive','vendor/snes9x']:shutil.rmtree(assets/rel,ignore_errors=True)
(assets/'catalog.js').write_text('window.GAME_CATALOG='+json.dumps(json.loads((root/'release-catalog.json').read_text()),ensure_ascii=False)+';\n')
for name in ['classes','dex']:
 shutil.rmtree(out/name,ignore_errors=True);(out/name).mkdir()
run([tools/'aapt2','compile','--dir',root/'app/src/main/res','-o',out/'resources.zip']);run([tools/'aapt2','link','-o',out/'base.apk','-I',platform,'--manifest',root/'app/src/main/AndroidManifest.xml','-A',assets,out/'resources.zip','--auto-add-overlay'])
run([jdk/'bin/javac','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',platform,'-cp',root/'app/libs/jna-5.13.0.jar','-d',out/'classes',*sorted((root/'app/src/main/java/org/childhood/arcade').glob('*.java'))]);run([jdk/'bin/jar','cf',out/'classes.jar','-C',out/'classes','.']);run([tools/'d8','--lib',platform,'--min-api','23','--output',out/'dex',out/'classes.jar',root/'app/libs/jna-5.13.0.jar'])
shutil.copy2(out/'base.apk',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 z.write(out/'dex/classes.dex','classes.dex')
 for lib in (root/'app/src/main/jniLibs').rglob('*.so'):z.write(lib,'lib/'+lib.relative_to(root/'app/src/main/jniLibs').as_posix())
run([tools/'zipalign','-f','-p','4',out/'unsigned.apk',out/'aligned.apk']);apk=out/'childhood-arcade-1.0.0.apk';run([jdk/'bin/java','-jar',tools/'lib/apksigner.jar','sign','--ks',sign['keystore'],'--ks-key-alias',sign['alias'],'--ks-pass','env:ARCADE_SIGNING_PASSWORD','--key-pass','env:ARCADE_SIGNING_PASSWORD','--out',apk,out/'aligned.apk']);run([jdk/'bin/java','-jar',tools/'lib/apksigner.jar','verify','--verbose',apk]);print(json.dumps({'apk':str(apk),'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()}))
