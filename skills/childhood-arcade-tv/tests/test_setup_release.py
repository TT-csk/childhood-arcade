import hashlib, json, sys, tempfile, unittest, zipfile
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import setup_release
class SetupTests(unittest.TestCase):
 def test_release_workflow_and_missing_url(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);apk=root/'source.apk';pack=root/'source.zip';game=b'example'
   with zipfile.ZipFile(apk,'w') as z:
    for n in ('AndroidManifest.xml','classes.dex','assets/index.html'):z.writestr(n,'x')
   with zipfile.ZipFile(pack,'w') as z:
    z.writestr('game-pack/manifest.json',json.dumps({'schema':1,'files':[{'name':'test.gba','path':'files/test.gba','bytes':len(game),'sha256':hashlib.sha256(game).hexdigest()}]}));z.writestr('game-pack/files/test.gba',game)
   data={'schema':1,'apk':{'url':'https://example.test/a','sha256':hashlib.sha256(apk.read_bytes()).hexdigest()},'game_pack':{'url':'https://example.test/b','sha256':hashlib.sha256(pack.read_bytes()).hexdigest()}}
   release=root/'release.json';release.write_text(json.dumps(data))
   def fake(url,dest,digest):
    import shutil
    source=apk if url.endswith('/a') else pack
    if hashlib.sha256(source.read_bytes()).hexdigest()!=digest:raise ValueError('bad hash')
    shutil.copyfile(source,dest)
   with patch.object(setup_release,'download',side_effect=fake):
    home=setup_release.setup(release,root/'home');config=json.loads((home/'deployment.json').read_text());self.assertTrue(Path(config['game_pack_manifest']).exists());self.assertTrue(Path(config['apk']['path']).exists())
    data['game_pack']['url']='';release.write_text(json.dumps(data));before=(home/'deployment.json').read_bytes()
    with self.assertRaises(ValueError):setup_release.setup(release,root/'home')
    self.assertEqual((home/'deployment.json').read_bytes(),before)
 def test_v2_pack_is_downloaded_separately_and_pinned_to_own_tag(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d); release=root/'release.json'; digest='a'*64
   release.write_text(json.dumps({'schema':1,'repository':'owner/repo','tag':'v2.0.0','apk':{'filename':'app.apk','sha256':digest},'game_pack':{'filename':'old.zip','sha256':digest,'tag':'v1.0.0'},'game_pack_v2':{'filename':'new.zip','sha256':digest}}))
   urls=[]
   def fake_download(url,dest,sha):
    urls.append(url);dest.write_bytes(b'candidate')
   def fake_prepare(home,*args):
    home.mkdir(exist_ok=True);(home/'deployment.json').write_text('{}')
   with patch.object(setup_release,'download',side_effect=fake_download),patch.object(setup_release,'prepare',side_effect=fake_prepare):
    home=setup_release.setup(release,root/'home');config=json.loads((home/'deployment.json').read_text())
   self.assertEqual(urls,['https://github.com/owner/repo/releases/download/v2.0.0/app.apk','https://github.com/owner/repo/releases/download/v1.0.0/old.zip','https://github.com/owner/repo/releases/download/v2.0.0/new.zip'])
   self.assertEqual(config['v2_resource_pack']['import'],'manual-in-hall');self.assertTrue(Path(config['v2_resource_pack']['path']).is_file())
 def test_explicit_64_bit_and_safe_32_bit_default(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);r=root/'r.json';sha='a'*64
   r.write_text(json.dumps({'schema':1,'repository':'owner/repo','tag':'v2','apk':{'filename':'old.apk','sha256':sha},'apk32':{'filename':'32.apk','sha256':sha},'apk64':{'filename':'64.apk','sha256':sha},'game_pack':{'filename':'pack.zip','sha256':sha}}))
   seen=[]
   def fetch(url,dest,digest):seen.append(url);dest.write_bytes(b'x')
   def prep(home,*args):home.mkdir(exist_ok=True);(home/'deployment.json').write_text('{}')
   with patch.object(setup_release,'download',side_effect=fetch),patch.object(setup_release,'prepare',side_effect=prep):
    setup_release.setup(r,root/'h32');setup_release.setup(r,root/'h64',abi='arm64-v8a')
   self.assertTrue(seen[0].endswith('/32.apk'));self.assertTrue(seen[2].endswith('/64.apk'))
if __name__=='__main__':unittest.main()
