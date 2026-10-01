"""Run disposable Purpur servers; never touches an existing server directory."""
import argparse, hashlib, json, shutil, subprocess, time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--versions', nargs='+', default=['1.21.8','1.21.9','1.21.10','1.21.11','26.1.2','26.2','26.3'])
parser.add_argument('--java21', default='java')
parser.add_argument('--java25', default='java')
parser.add_argument('--upgrade-from', type=Path, help='NBT backup file exported on the baseline server')
args = parser.parse_args()
args.versions = list(dict.fromkeys(args.versions))
upgrade_fixture = args.upgrade_from.resolve() if args.upgrade_from else None
root = Path(__file__).resolve().parents[1]
work = root / 'target' / 'server-tests' / str(int(time.time()))
work.mkdir(parents=True)
results = []
for index, version in enumerate(args.versions):
    directory = work / version
    directory.mkdir()
    build = {'1.21.8': '2497', '1.21.9': '2505', '1.21.10': '2535', '1.21.11': '2568', '26.1.2': '2592', '26.2': '2633', '26.3': '2642'}[version]
    url = f'https://api.purpurmc.org/v2/purpur/{version}/{build}'
    metadata = subprocess.check_output(['curl', '-fsSL', '-A', 'InventoryBackup-Compatibility-Test/0.2.0', url], text=True)
    info = json.loads(metadata)
    build = str(info['build'])
    jar = directory / 'purpur.jar'
    subprocess.run(['curl','-fsSL','-A','InventoryBackup-Compatibility-Test/0.2.0',f'https://api.purpurmc.org/v2/purpur/{version}/{build}/download','-o',str(jar)], check=True)
    if info.get('md5') and hashlib.md5(jar.read_bytes()).hexdigest() != info['md5']:
        raise RuntimeError('Server checksum mismatch')
    (directory / 'plugins' / 'InventoryBackup').mkdir(parents=True)
    shutil.copy2(root/'plugin/target/InventoryBackup-0.2.0.jar', directory/'plugins/InventoryBackup.jar')
    shutil.copy2(root/'tests/server-plugin/target/InventoryBackup-ServerTests-0.2.0.jar', directory/'plugins/ServerTests.jar')
    config = (root/'plugin/src/main/resources/config.yml').read_text(encoding='utf-8').replace('enabled: true', 'enabled: false')
    (directory/'plugins/InventoryBackup/config.yml').write_text(config, encoding='utf-8')
    (directory/'eula.txt').write_text('eula=true\n')
    (directory/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={25800+index}\nonline-mode=false\nlevel-type=minecraft:flat\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=1\n')
    if upgrade_fixture:
        destination = directory/'plugins/InventoryBackup/inventories'/upgrade_fixture.parent.name
        destination.mkdir(parents=True,exist_ok=True)
        shutil.copy2(upgrade_fixture, destination/'upgrade.yml')
    java = args.java25 if version.startswith('26.') else args.java21
    if Path(java).is_file(): java = str(Path(java).resolve())
    log = directory/'console.log'
    print(f'Testing Purpur {version} build {build}', flush=True)
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen([java,'-Xms256M','-Xmx1G','-jar',str(jar),'--nogui'],cwd=directory,stdout=output,stderr=subprocess.STDOUT,stdin=subprocess.PIPE)
        try: process.wait(timeout=240)
        except subprocess.TimeoutExpired:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
    content = log.read_text(encoding='utf-8', errors='replace')
    passed = 'INVENTORYBACKUP_SERVER_TEST_PASS' in content and 'INVENTORYBACKUP_SERVER_TEST_FAIL' not in content
    results.append({'version':version,'build':build,'java':java,'passed':passed,'upgrade_passed': 'INVENTORYBACKUP_UPGRADE_TEST_PASS' in content,'log':str(log),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'plugin_sha256':hashlib.sha256((directory/'plugins/InventoryBackup.jar').read_bytes()).hexdigest()})
    (work/'results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    if version == '1.21.8' and passed:
        upgrade_fixture = next((directory/'plugins/InventoryBackup/inventories').glob('*/*_integration.yml'))
    print('PASS' if passed else content[content.find('INVENTORYBACKUP_SERVER_TEST_FAIL'):] if 'INVENTORYBACKUP_SERVER_TEST_FAIL' in content else content[-4000:], flush=True)
print(f'Results: {work / "results.json"}')
raise SystemExit(0 if all(result['passed'] for result in results) else 1)
