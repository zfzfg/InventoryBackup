import struct, zipfile
from pathlib import Path
jar = Path(__file__).resolve().parents[1]/'plugin/target/InventoryBackup-0.2.0.jar'
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert 'com/zfzfg/inventorybackup/api/InventoryBackupAPI.class' in names
    assert "api-version: '1.21.8'" in archive.read('plugin.yml').decode()
    assert '${project.version}' not in archive.read('plugin.yml').decode()
    assert not any(name.startswith(('org/bukkit/', 'org/mockbukkit/')) for name in names)
    for name in names:
        if name.endswith('.class'): assert struct.unpack('>H',archive.read(name)[6:8])[0] == 65, name
print('Package checks passed: API embedded, Java 21, no server or test dependencies.')
