"""Reject accidental edits to the immutable historical release archives."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent / 'fixtures'
for release in ['0.0.7', '0.1.0']:
    folder = root / release
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    data = folder / 'data'
    actual = {str(path.relative_to(data)).replace('\\', '/'): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in data.rglob('*') if path.is_file()}
    assert actual == manifest['files'], f'Historical {release} archive changed'
    assert manifest['release'] == release and manifest['minecraft'] == '1.20.1'
    print(f'Historical {release} fixture checksums passed ({len(actual)} original files).')
