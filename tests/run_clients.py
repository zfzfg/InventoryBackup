"""Actual protocol clients on disposable baseline servers; never attaches to existing servers."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from run_servers import ROOT, server_jar

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--platforms', nargs='+', default=['spigot', 'paper', 'purpur'])
    parser.add_argument('--java21', default='java')
    parser.add_argument('--java25', default='java')
    parser.add_argument('--spigot-root', type=Path, default=ROOT / 'target/spigot-build')
    args = parser.parse_args()
    args.spigot_root = args.spigot_root.resolve()
    args.java21 = str(Path(args.java21).resolve()) if Path(args.java21).is_file() else args.java21
    evidence = ROOT / 'target/client-tests' / str(time.time_ns())
    evidence.mkdir(parents=True)
    work = Path(tempfile.mkdtemp(prefix='ib-clients-'))
    results = []
    for platform in args.platforms:
        directory = work / platform
        data = directory / 'plugins/InventoryBackup'
        data.mkdir(parents=True)
        jar, metadata = server_jar(platform, '1.21.8', args)
        plugin = ROOT / 'plugin/target/InventoryBackup-0.2.0.jar'
        shutil.copy2(plugin, directory / 'plugins/InventoryBackup.jar')
        shutil.copy2(ROOT / 'tests/client-plugin/target/InventoryBackup-ClientTests-0.2.0.jar', directory / 'plugins/ClientTests.jar')
        config = (ROOT / 'plugin/src/main/resources/config.yml').read_text(encoding='utf-8').replace('enabled: true', 'enabled: false')
        (data / 'config.yml').write_text(config)
        (directory / 'eula.txt').write_text('eula=true\n')
        (directory / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=26200\nonline-mode=false\nenforce-secure-profile=false\nlevel-type=minecraft:flat\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=2\n')
        log = evidence / f'{platform}.log'
        clients_log = evidence / f'{platform}-clients.log'
        print(f'Testing real {platform} 1.21.8 client sessions', flush=True)
        with log.open('w', encoding='utf-8') as output:
            server = subprocess.Popen([args.java21, '-Xms256M', '-Xmx1G', '-jar', str(jar.resolve()), '--nogui'],
                                      cwd=directory, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.time() + 180
                while time.time() < deadline and server.poll() is None:
                    if 'Done (' in log.read_text(encoding='utf-8', errors='replace'): break
                    time.sleep(.25)
                else: raise RuntimeError('Server did not start')
                with clients_log.open('w', encoding='utf-8') as client_output:
                    subprocess.run(['node', str(ROOT / 'tests/clients/run.js'), '26200', '1.21.8'],
                                   stdout=client_output, stderr=subprocess.STDOUT, timeout=240)
                server.wait(timeout=20)
            except Exception as error:
                print(error, flush=True)
            finally:
                if server.poll() is None:
                    server.terminate()
                    try: server.wait(timeout=10)
                    except subprocess.TimeoutExpired: server.kill(); server.wait()
        content = log.read_text(encoding='utf-8', errors='replace')
        passed = 'INVENTORYBACKUP_CLIENT_TEST_PASS' in content and 'INVENTORYBACKUP_CLIENT_TEST_FAIL' not in content
        result = dict(platform=platform, version='1.21.8', passed=passed, metadata=metadata, work=str(directory),
                      log=str(log), clients_log=str(clients_log), plugin_sha256=hashlib.sha256(plugin.read_bytes()).hexdigest())
        results.append(result)
        (evidence / 'results.json').write_text(json.dumps(results, indent=2))
        print('PASS' if passed else f'FAIL; see {log} and {clients_log}', flush=True)
    print(f'Results: {evidence / "results.json"}')
    return 0 if all(result['passed'] for result in results) else 1

if __name__ == '__main__': raise SystemExit(main())
