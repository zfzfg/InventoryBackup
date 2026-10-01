"""Generate immutable archives by invoking an unmodified historical release on Purpur 1.20.1."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
from run_servers import ROOT, download, fetch_json

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', choices=['0.0.7', '0.1.0'], required=True)
    parser.add_argument('--plugin-jar', type=Path, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    plugin = args.plugin_jar.resolve()
    java = str(Path(args.java).resolve()) if Path(args.java).is_file() else args.java
    work = ROOT / 'target/historical-export' / f'{args.release}-{int(time.time())}'
    work.mkdir(parents=True)
    metadata = fetch_json('https://api.purpurmc.org/v2/purpur/1.20.1/latest')
    build = metadata['build']
    server = ROOT / '.test-cache/servers' / f'purpur-1.20.1-{build}.jar'
    download(f'https://api.purpurmc.org/v2/purpur/1.20.1/{build}/download', server)
    if metadata.get('md5') and hashlib.md5(server.read_bytes()).hexdigest() != metadata['md5']:
        raise RuntimeError('Server checksum mismatch')
    data = work / 'plugins/InventoryBackup'
    data.mkdir(parents=True)
    shutil.copy2(plugin, work / 'plugins/InventoryBackup.jar')
    shutil.copy2(ROOT / 'tests/fixture-plugin/target/InventoryBackup-HistoricalFixtures-0.2.0.jar', work / 'plugins/Fixtures.jar')
    (data / 'config.yml').write_text('language: en\nauto-delete-days: 0\ncleanup-interval-hours: 24\nupdate-check:\n  enabled: false\n')
    (work / 'eula.txt').write_text('eula=true\n')
    (work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25799\nonline-mode=false\nlevel-type=minecraft:flat\nview-distance=2\nsimulation-distance=2\n')
    log = work / 'console.log'
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen([java, '-Xms256M', '-Xmx1G', '-jar', str(server), '--nogui'], cwd=work,
                                   stdout=output, stderr=subprocess.STDOUT)
        try: process.wait(timeout=300)
        except subprocess.TimeoutExpired:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
    content = log.read_text(encoding='utf-8', errors='replace')
    if 'INVENTORYBACKUP_HISTORICAL_EXPORT_PASS' not in content or 'INVENTORYBACKUP_HISTORICAL_EXPORT_FAIL' in content:
        raise RuntimeError(f'Historical export failed; inspect {log}')
    destination = ROOT / 'tests/fixtures' / args.release
    if destination.exists(): raise RuntimeError(f'Refusing to replace immutable fixtures: {destination}')
    shutil.copytree(data, destination / 'data')
    files = {str(path.relative_to(destination / 'data')).replace('\\', '/'): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in (destination / 'data').rglob('*') if path.is_file()}
    manifest = dict(release=args.release, exported_at_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                    plugin_filename=plugin.name, plugin_sha256=hashlib.sha256(plugin.read_bytes()).hexdigest(),
                    server='Purpur', minecraft='1.20.1', build=build, server_sha256=hashlib.sha256(server.read_bytes()).hexdigest(),
                    generator='InventoryBackup-HistoricalFixtures',
                    generator_sha256=hashlib.sha256((work / 'plugins/Fixtures.jar').read_bytes()).hexdigest(), files=files)
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    shutil.copy2(log, destination / 'export-console.log')
    print(f'Exported original {args.release} archive: {destination}')

if __name__ == '__main__': main()
