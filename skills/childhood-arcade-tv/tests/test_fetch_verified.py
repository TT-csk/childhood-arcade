import hashlib, io, sys, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from fetch_verified import download
class Response(io.BytesIO):
 def geturl(self): return 'https://example.com/file'
class FetchTests(unittest.TestCase):
 def test_download_and_hash_failure_preserves_existing_file(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'file'; p.write_bytes(b'old'); good=hashlib.sha256(b'new').hexdigest()
   with patch('fetch_verified.urlopen', side_effect=lambda *a,**k:Response(b'new')):
    with self.assertRaises(ValueError): download('https://example.com/file',p,'0'*64)
    self.assertEqual(p.read_bytes(),b'old'); download('https://example.com/file',p,good); self.assertEqual(p.read_bytes(),b'new')
 def test_http_is_rejected(self):
  with self.assertRaises(ValueError): download('http://example.com/file','/tmp/unused','0'*64)
if __name__ == '__main__': unittest.main()
