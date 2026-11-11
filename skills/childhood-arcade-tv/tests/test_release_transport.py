import base64,io,json,tempfile,unittest,zipfile,sys
from pathlib import Path
from unittest.mock import Mock
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from release_transport import call,backup
class TransportTests(unittest.TestCase):
 def test_provider_result_and_failure(self):
  c=Mock();c.shell.return_value='Bundle[{result='+base64.b64encode(b'{"protocol":1}').decode()+'}]';self.assertEqual(call(c,'capabilities')['protocol'],1)
  c.shell.return_value='Bundle[{result='+base64.b64encode(b'{"error":"denied"}').decode()+'}]'
  with self.assertRaises(ValueError):call(c,'capabilities')
 def test_zip_backup_and_reject_foreign_paths(self):
  with tempfile.TemporaryDirectory() as d:
   for name in ('store/prefs.json','../outside'):
    b=io.BytesIO()
    with zipfile.ZipFile(b,'w') as z:z.writestr(name,'{}')
    c=Mock();c.serial='test';c.run.return_value=b.getvalue();p=Path(d)/'saved.json'
    if name.startswith('store'):
     result=backup(c,p);self.assertTrue(Path(result['path']).exists());self.assertEqual(result['entries'],1)
    else:
     with self.assertRaises(ValueError):backup(c,Path(d)/'bad.json')
if __name__=='__main__':unittest.main()
