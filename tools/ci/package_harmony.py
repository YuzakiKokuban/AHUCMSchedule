"""Package the unsigned HarmonyOS trial separately from installable releases."""
from pathlib import Path
from zipfile import ZipFile
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[2]
source = ROOT / 'harmonyApp/entry/build/default/outputs/default/entry-default-unsigned.hap'
with ZipFile(source) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert 'ets/modules.abc' in names
    assert archive.read('ets/modules.abc')
    module = json.loads(archive.read('module.json'))
    assert module['app']['bundleName'] == 'com.yuzakikokuban.ahucm'
    assert [p['name'] for p in module['module']['requestPermissions']] == ['ohos.permission.INTERNET']
    assert not any(name.lower().endswith(('.xls', '.xlsx', '.p12', '.keystore', '.jks', '.p7b')) for name in names)
    vendor = archive.read('resources/rawfile/vendor/xlsx.full.min.js')
    assert hashlib.sha256(vendor).hexdigest() == 'cc015130aa8521e7f088f88898eba949ccdcbfb38df0bd129b44b7273c3a6f41'
    for name in ('school-sync.js', 'capture-account.js', 'schedule-core.js', 'parser.html'):
        assert archive.read('resources/rawfile/' + name) == (ROOT / 'harmonyApp/entry/src/main/resources/rawfile' / name).read_bytes()
    version = module['app']['versionName']

target = ROOT / 'outputs' / f'QihuangSchedule-HarmonyOS-{version}-preview-unsigned.hap'
target.parent.mkdir(exist_ok=True)
shutil.copy2(source, target)
digest = hashlib.sha256(target.read_bytes()).hexdigest()
(target.parent / 'SHA256SUMS-HarmonyOS.txt').write_text(f'{digest}  {target.name}\n', encoding='ascii')
print(f'{target.name}: {target.stat().st_size} bytes; sha256 {digest}')
