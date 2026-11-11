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
if __name__=='__main__':unittest.main()
