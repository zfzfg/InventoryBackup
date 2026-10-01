"""Disposable localhost servers, platform transfers and baseline upgrade checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import tempfile
import urllib.request
import zipfile
import socket

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = ['1.21.8', '1.21.9', '1.21.10', '1.21.11', '26.1.2', '26.2', '26.3']
PURPUR = dict(zip(VERSIONS, ['2497', '2505', '2535', '2568', '2592', '2633', '2642']))
AGENT = 'InventoryBackup-Compatibility-Test/0.2.0 (zfzfg@sterra.online)'

class ServerUnavailable(Exception):
    pass

def fetch_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': AGENT}), timeout=60) as response:
        return json.load(response)

def download(url, destination, checksum=None):
    if destination.exists() and (not checksum or hashlib.sha256(destination.read_bytes()).hexdigest() == checksum):
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.download')
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': AGENT}), timeout=60) as response:
        with temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
    if checksum and hashlib.sha256(temporary.read_bytes()).hexdigest() != checksum:
        temporary.unlink()
        raise RuntimeError('Server SHA-256 mismatch')
    temporary.replace(destination)

def server_jar(platform, version, args):
    cache = ROOT / '.test-cache' / 'servers'
    if platform == 'spigot':
        directory = args.spigot_root / version
        jar = directory / f'spigot-{version}.jar'
        info_file = directory / 'BuildData/info.json'
        if info_file.exists():
            actual = json.loads(info_file.read_text()).get('minecraftVersion', '').removesuffix('_unobfuscated')
            if actual and actual != version:
                raise ServerUnavailable(f'BuildTools resolves {version} to {actual}; no exact server artifact')
        if not jar.exists():
            tools = args.spigot_root / 'BuildTools.jar'
            download('https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar', tools)
            directory.mkdir(parents=True, exist_ok=True)
            java = args.java25 if version.startswith('26.') else args.java21
            env = dict(os.environ, GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='core.longpaths', GIT_CONFIG_VALUE_0='true')
            if Path(java).is_file(): env['JAVA_HOME'] = str(Path(java).resolve().parent.parent)
            with (directory / 'builder.log').open('w', encoding='utf-8') as output:
                subprocess.run([java, '-jar', str(tools.resolve()), '--rev', version, '--compile', 'SPIGOT'],
                               cwd=directory, env=env, stdout=output, stderr=subprocess.STDOUT, check=True)
        info_file = directory / 'BuildData/info.json'
        info = json.loads(info_file.read_text()) if info_file.exists() else {}
        if info.get('minecraftVersion', '').removesuffix('_unobfuscated') != version:
            raise ServerUnavailable(f'BuildTools resolves {version} to {info.get("minecraftVersion")}; no exact server artifact')
        if (directory / 'Spigot/.git').exists():
            info['spigot_commit'] = subprocess.check_output(['git', '-C', str(directory / 'Spigot'), 'rev-parse', 'HEAD'], text=True).strip()
        return jar, info
    if platform == 'paper':
        manifest = cache / f'paper-{version}.json'
        info = json.loads(manifest.read_text()) if manifest.exists() else fetch_json(f'https://fill.papermc.io/v3/projects/paper/versions/{version}/builds')[0]
        artifact = info['downloads']['server:default']
        jar = cache / artifact['name']
        download(artifact['url'], jar, artifact['checksums']['sha256'])
        manifest.write_text(json.dumps(info, indent=2), encoding='utf-8')
        return jar, info
    info = fetch_json(f'https://api.purpurmc.org/v2/purpur/{version}/{PURPUR[version]}')
    jar = cache / f'purpur-{version}-{info["build"]}.jar'
    download(f'https://api.purpurmc.org/v2/purpur/{version}/{info["build"]}/download', jar)
    if info.get('md5') and hashlib.md5(jar.read_bytes()).hexdigest() != info['md5']:
        raise RuntimeError('Purpur checksum mismatch')
    return jar, info

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--versions', nargs='+', choices=VERSIONS, default=VERSIONS)
    parser.add_argument('--platforms', nargs='+', choices=['spigot', 'paper', 'purpur'], default=['spigot', 'paper', 'purpur'])
    parser.add_argument('--java21', default='java')
    parser.add_argument('--java25', default='java')
    parser.add_argument('--spigot-root', type=Path, default=ROOT / 'target/spigot-build')
    parser.add_argument('--upgrade-from', type=Path, nargs='+')
    parser.add_argument('--historical-data', type=Path, help='Source plugin data directory copied before startup')
    parser.add_argument('--port-base', type=int, default=0, help='Zero selects a free localhost port for each disposable server')
    parser.add_argument('--no-transfers', action='store_true', help='Only verify writes and historical reads; omit duplicate platform-transfer runs')
    args = parser.parse_args()
    args.spigot_root = args.spigot_root.resolve()
    for field in ['java21', 'java25']:
        if Path(getattr(args, field)).is_file(): setattr(args, field, str(Path(getattr(args, field)).resolve()))
    evidence = ROOT / 'target/server-tests' / str(time.time_ns())
    evidence.mkdir(parents=True)
    # Short physical paths are needed by Windows server code and legacy Java file APIs.
    work = Path(tempfile.mkdtemp(prefix='ib-servers-')) if os.name == 'nt' else evidence
    work.mkdir(parents=True, exist_ok=True)
    results, exports = [], {}
    plugin = ROOT / 'plugin/target/InventoryBackup-0.2.0.jar'
    harness = ROOT / 'tests/server-plugin/target/InventoryBackup-ServerTests-0.2.0.jar'
    def run(platform, version, phase, seeds):
        directory = work / f'{platform}-{version}-{phase}'
        directory.mkdir()
        print(f'Testing {platform} {version} ({phase})', flush=True)
        try:
            jar, metadata = server_jar(platform, version, args)
            data = directory / 'plugins/InventoryBackup'
            if args.historical_data: shutil.copytree(args.historical_data, data)
            historical = {str(path.relative_to(data)).replace('\\', '/'): path.read_bytes()
                          for path in data.rglob('*') if path.is_file()} if args.historical_data else {}
            data.mkdir(parents=True, exist_ok=True)
            shutil.copy2(plugin, directory / 'plugins/InventoryBackup.jar')
            shutil.copy2(harness, directory / 'plugins/ServerTests.jar')
            if not (data / 'config.yml').exists():
                config = (ROOT / 'plugin/src/main/resources/config.yml').read_text(encoding='utf-8').replace('enabled: true', 'enabled: false')
                (data / 'config.yml').write_text(config, encoding='utf-8')
            seed_names = []
            for index, fixture in enumerate(seeds):
                destination = data / 'inventories' / fixture.parent.name
                destination.mkdir(parents=True, exist_ok=True)
                name = f'transfer-{index}.yml'
                shutil.copy2(fixture, destination / name)
                seed_names.append(name)
            (directory / 'eula.txt').write_text('eula=true\n')
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = args.port_base + len(results) if args.port_base else reservation.getsockname()[1]
            (directory / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-type=minecraft:flat\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=1\n')
            java = args.java25 if version.startswith('26.') else args.java21
            log = directory / 'console.log'
            with log.open('w', encoding='utf-8') as output:
                process = subprocess.Popen([java, '-Xms256M', '-Xmx1G', '-jar', str(jar.resolve()), '--nogui'],
                                           cwd=directory, stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
                try: process.wait(timeout=300)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
            content = log.read_text(encoding='utf-8', errors='replace')
            evidence_log = evidence / f'{platform}-{version}-{phase}.log'
            if evidence_log != log: shutil.copy2(log, evidence_log)
            passed = 'INVENTORYBACKUP_SERVER_TEST_PASS' in content and 'INVENTORYBACKUP_SERVER_TEST_FAIL' not in content
            transfer_passed = all(f'INVENTORYBACKUP_TRANSFER_PASS {name}' in content for name in seed_names)
            historical_passed = True
            if historical:
                historical_files = [name for name in historical if name.startswith('inventories/') and name.endswith('.yml')]
                historical_passed = content.count('INVENTORYBACKUP_HISTORICAL_LOAD_PASS ') == len(historical_files)
                archives = list(data.glob('pre-nbt-upgrade-*.zip'))
                if len(archives) != 1: historical_passed = False
                else:
                    with zipfile.ZipFile(archives[0]) as archive:
                        historical_passed &= all(archive.read(name) == value for name, value in historical.items())
                remaining = [path.read_bytes() for path in (data / 'inventories').rglob('*.yml')]
                historical_passed &= all(historical[name] in remaining for name in historical_files)
            result = dict(platform=platform, version=version, phase=phase, passed=passed and transfer_passed and historical_passed,
                          historical_passed=historical_passed if historical else None,
                          java=java, metadata=metadata, transfers=seed_names, transfer_sources=[str(seed) for seed in seeds], log=str(evidence_log), work=str(directory),
                          sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), plugin_sha256=hashlib.sha256(plugin.read_bytes()).hexdigest())
            if not result['passed']: print(content[-7000:], flush=True)
            fixture = next((data / 'inventories').glob('*/*_integration.yml'), None) if result['passed'] else None
        except ServerUnavailable as error:
            result = dict(platform=platform, version=version, phase=phase, passed=False, unavailable=True, error=str(error))
            fixture = None
            print(f'UNAVAILABLE: {error}', flush=True)
        except Exception as error:
            result = dict(platform=platform, version=version, phase=phase, passed=False, error=str(error))
            fixture = None
            print(f'FAIL: {error}', flush=True)
        results.append(result)
        (evidence / 'results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
        print('PASS' if result['passed'] else 'UNAVAILABLE' if result.get('unavailable') else 'FAIL', flush=True)
        return fixture
    for version in dict.fromkeys(args.versions):
        for platform in dict.fromkeys(args.platforms):
            baseline = [fixture for (p, v), fixture in exports.items() if v == '1.21.8' and version != v]
            if args.upgrade_from: baseline.extend(fixture.resolve() for fixture in args.upgrade_from)
            fixture = run(platform, version, 'write', baseline)
            if fixture: exports[platform, version] = fixture
        if not args.no_transfers:
            for platform in dict.fromkeys(args.platforms):
                seeds = [fixture for (p, v), fixture in exports.items() if v == version and p != platform]
                if seeds: run(platform, version, 'transfer', seeds)
    print(f'Results: {evidence / "results.json"}', flush=True)
    return 0 if all(result['passed'] or result.get('unavailable') for result in results) else 1

if __name__ == '__main__':
    raise SystemExit(main())
