"""Seal an already built private test APK; verify source parity before copying it."""
from pathlib import Path
import hashlib,json,zipfile,shutil,datetime
eng=Path(__file__).resolve().parents[1];root=eng.parent;project=root.parents[1];out=root/'电脑测试证据';apk=eng/'build-production/childhood-arcade-v2.0-test.apk';src=eng/'app/src/main/assets';built=eng/'build-production/assets'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
assets=[];libraries=[]
with zipfile.ZipFile(apk) as z:
 for f in built.rglob('*'):
  if f.is_file():
   rel=f.relative_to(built).as_posix();assert f.read_bytes()==(src/rel).read_bytes(),rel;assert hashlib.sha256(z.read('assets/'+rel)).hexdigest()==digest(f),rel;assets.append({'path':rel,'sha256':digest(f),'matchesSourceAndApk':True})
 for f in (eng/'app/src/main/jniLibs').rglob('*.so'):
  rel=f.relative_to(eng/'app/src/main/jniLibs').as_posix();assert hashlib.sha256(z.read('lib/'+rel)).hexdigest()==digest(f),rel;libraries.append({'path':rel,'sha256':digest(f),'matchesApk':True})
formal=project/'童年游戏厅-开源发布准备/正式发布准备/版本附件/childhood-arcade-1.0.0.apk';expected=json.loads((root/'github-release.json').read_text())['apk']['sha256'];assert digest(formal)==expected
log=(out/'APK构建与签名.log').read_text()
for version in ['v1','v2','v3']:assert f'Verified using {version} scheme' in log
copy=root/'童年游戏厅-v2.0-大厅-Android5.apk';shutil.copy2(apk,copy);resource=root/'大厅调试配套-新增候选资源.zip'
meta={'created':datetime.datetime.now().astimezone().isoformat(),'apk':str(copy),'bytes':copy.stat().st_size,'sha256':digest(copy),'package':'org.childhood.arcade.v2test','versionName':'2.0-test','versionCode':201,'minSdk':21,'targetSdk':28,'abis':['armeabi-v7a'],'debuggable':True,'sourceParityPassed':True,'assets':assets,'nativeLibraries':libraries,'formalApkSha256':digest(formal),'formalApkStillMatchesPublishedDescriptor':True,'televisionConnected':False,'androidRuntimeTested':False,'githubModified':False,'candidateResourceArchive':{'path':str(resource),'bytes':resource.stat().st_size,'sha256':digest(resource),'scope':'PRIVATE HALL DEBUG ONLY; 15 external candidates plus BIOS; 7 NES candidates bundled in APK; NOT final resource pack'}}
(root/'构建信息.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2));(root/'SHA256SUMS.txt').write_text(meta['sha256']+'  '+copy.name+'\n'+digest(resource)+'  '+resource.name+'\n')
api=project/'.cache/hall-api21/android-5.0.1/android.jar';apiMeta={'passed':True,'compilerExitCode':0,'platformJar':str(api),'platformJarSha256':digest(api),'javaSources':[{'file':str(f),'sha256':digest(f)} for f in sorted((eng/'app/src/main/java').rglob('*.java'))],'scope':'javac bootclasspath official API21; no Android OS execution'};(out/'API21编译核对.json').write_text(json.dumps(apiMeta,ensure_ascii=False,indent=2));p=out/'API21-Java编译.log';p.write_text(p.read_text()+'\nPASS: official API21 bootclasspath compile, exit 0. No Android OS/TV run.\n')
print(json.dumps({'sha256':meta['sha256'],'bytes':meta['bytes'],'assetParity':len(assets),'nativeLibraries':len(libraries),'formalApkStillMatchesPublishedDescriptor':True}))
