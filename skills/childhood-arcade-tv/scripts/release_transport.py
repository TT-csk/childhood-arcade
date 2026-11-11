"""Shell-only ContentProvider transport for non-debug release APKs."""
import base64,json,re,zipfile,io,os
from pathlib import Path
AUTH='content://org.childhood.arcade.deployment'
def call(client,method,arg=None):
    args=['content','call','--uri',AUTH,'--method',method]
    if arg is not None:args += ['--arg',arg]
    out=client.shell(*args,timeout=180)
    match=re.search(r'result=([A-Za-z0-9+/=]+)',out)
    if not match:raise ValueError('Release transfer did not return a result')
    value=json.loads(base64.b64decode(match.group(1)))
    if value.get('error'):raise ValueError(value['error'])
    return value

def backup(client,path):
    client.shell('am', 'force-stop', 'org.childhood.arcade')
    data=client.run(['-s',client.serial,'exec-out','content','read','--uri',AUTH+'/backup'],binary=True,timeout=120)
    if not data.startswith(b'PK'):raise ValueError('Release backup is not a ZIP')
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        total=0
        for e in z.infolist():
            if not re.fullmatch(r'(?:store/(?:prefs|slot:[a-f0-9]{64}:[0-3]|battery:[a-f0-9]{64})\.json|nes/[a-f0-9]{64}\.srm)',e.filename):raise ValueError('Invalid backup entry')
            total+=e.file_size
            if total>256*1024**2:raise ValueError('Backup exceeds limit')
        if z.testzip():raise ValueError('Corrupt backup')
        count=len(z.namelist())
    path=Path(path).with_suffix('.zip');path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as f:f.write(data)
    os.chmod(path,0o600)
    return {'path':str(path),'entries':count,'includes_roms':False,'format':'release-zip'}

def restore(client,path):
    import hashlib
    path=Path(path).expanduser().resolve()
    # Validate using the same allowlist as exported backups before touching the device.
    with zipfile.ZipFile(path) as z:
        total=0;seen=set()
        for e in z.infolist():
            if e.filename in seen or not re.fullmatch(r'(?:store/(?:prefs|slot:[a-f0-9]{64}:[0-3]|battery:[a-f0-9]{64})\.json|nes/[a-f0-9]{64}\.srm)',e.filename):raise ValueError('Invalid backup entry')
            seen.add(e.filename);total+=e.file_size
            if total>256*1024**2 or len(seen)>1000:raise ValueError('Backup exceeds limits')
        if z.testzip():raise ValueError('Corrupt backup')
    digest=hashlib.sha256(path.read_bytes()).hexdigest();stage='/data/local/tmp/childhood-arcade-backup-'+digest+'.zip'
    client.shell('am','force-stop','org.childhood.arcade')
    try:
        client.run(['-s',client.serial,'push',str(path),stage],timeout=180)
        return call(client,'restore',digest)
    finally:client.shell('rm','-f',stage,allow_failure=True)
