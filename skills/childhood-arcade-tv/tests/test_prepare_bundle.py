import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from prepare_bundle import prepare, sha256

class BundleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.apk = self.root/'app.apk'; self.zip = self.root/'pack.zip'
        with zipfile.ZipFile(self.apk, 'w') as z:
            for name in ('AndroidManifest.xml','classes.dex','assets/index.html'): z.writestr(name, 'x')
        self.data = b'hello'; self.manifest = {'schema':1,'files':[{'name':'test.gba','path':'files/test.gba','bytes':len(self.data),'sha256':hashlib.sha256(self.data).hexdigest()}]}
    def make_zip(self, extra=None):
        with zipfile.ZipFile(self.zip,'w') as z:
            z.writestr('game-pack/manifest.json',json.dumps(self.manifest)); z.writestr('game-pack/files/test.gba',self.data)
            if extra: z.writestr(extra,'evil')
    def run_prepare(self, expected=None):
        return prepare(self.root/'home',self.apk,sha256(self.apk),self.zip,expected or sha256(self.zip))
    def test_success_and_reuse(self):
        self.make_zip(); manifest=self.run_prepare(); self.assertTrue(manifest.is_file()); self.assertEqual(self.run_prepare(),manifest)
        self.assertEqual(json.loads((self.root/'home/deployment.json').read_text())['game_pack_manifest'],str(manifest))
    def test_hash_failure_does_not_change_configuration(self):
        self.make_zip(); self.run_prepare(); config=self.root/'home/deployment.json'; old=config.read_bytes()
        with self.assertRaises(ValueError): self.run_prepare('0'*64)
        self.assertEqual(config.read_bytes(),old)
    def test_rejects_traversal_before_writing(self):
        self.make_zip('../outside');
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertFalse((self.root/'outside').exists()); self.assertFalse((self.root/'home/deployment.json').exists())
    def test_rejects_windows_path_traversal(self):
        self.make_zip('game-pack/..\\outside')
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertFalse((self.root/'home/deployment.json').exists())
    def test_resource_tampering_rejected(self):
        self.data=b'wrong'; self.make_zip()
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertFalse((self.root/'home/deployment.json').exists())
if __name__ == '__main__': unittest.main()
