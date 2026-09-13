#!/usr/bin/env python3
"""Capture/compare built public signatures and versionpack metadata.

Run capture on the baseline, then compare after refactoring. The destination
must be outside build outputs overwritten by Gradle (e.g. /tmp/refactor-baseline).
JAVA_HOME selects javap; this script never builds or downloads dependencies.
"""
import argparse
import io
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def normalize_metadata(value):
    if isinstance(value, dict):
        result = {key: ('<build-version>' if key == 'version' else normalize_metadata(item))
                  for key, item in value.items()}
        if 'jars' in result:
            # The existing wrapper uses File.listFiles(), whose order is unspecified.
            result['jars'] = sorted(result['jars'], key=lambda entry: entry['file'])
        return result
    if isinstance(value, list):
        return [normalize_metadata(item) for item in value]
    if isinstance(value, str):
        value = re.sub(r'1\.3-beta\.4-\d+-development', '<build-version>', value)
        return re.sub(r'1\.3-beta\.4-[^/]+(?=\.jar$)', '<build-version>', value)
    return value


def capture():
    javap = str(Path(os.environ['JAVA_HOME']) / 'bin/javap') if 'JAVA_HOME' in os.environ else 'javap'
    signatures = {}
    for properties in sorted((ROOT / 'versions').glob('*/gradle.properties')):
        version = properties.parent.name
        classes = properties.parent / 'build/classes/java/main'
        names = sorted(str(path.relative_to(classes)).replace('/', '.')[:-6]
                       for path in classes.rglob('*.class'))
        if not names:
            raise RuntimeError(f'Missing compiled classes for {version}')
        output = subprocess.check_output([javap, '-protected', '-classpath', str(classes), *names], text=True)
        # javap emits one declaration per class. Keep public/protected members,
        # including generated Lombok accessors and nested compatibility types.
        for block in re.split(r'Compiled from "[^"\n]+"\n', output):
            block = block.strip()
            if not block:
                continue
            declaration = block.splitlines()[0]
            name = re.search(r'(?:class|interface|enum) ([\w.$]+)', declaration).group(1)
            signatures[f'{version}/{name}'] = block
    jars = sorted((ROOT / 'fabricWrapper/build/libs').glob('*.jar'), key=lambda path: path.stat().st_mtime)
    if not jars:
        raise RuntimeError('Missing versionpack JAR')
    with zipfile.ZipFile(jars[-1]) as archive:
        wrapper = json.loads(archive.read('fabric.mod.json'))
        nested = wrapper.get('jars', [])
        if len(nested) != 15:
            raise RuntimeError(f'Expected 15 nested JARs, found {len(nested)}')
        resources = {}
        for entry in nested:
            with zipfile.ZipFile(io.BytesIO(archive.read(entry['file']))) as child:
                metadata = json.loads(child.read('fabric.mod.json'))
                key = json.dumps(metadata['depends']['minecraft'], sort_keys=True)
                resources[key] = {
                    name: normalize_metadata(json.loads(child.read(name)))
                    for name in child.namelist()
                    if name == 'fabric.mod.json' or name.endswith('.mixins.json') or '/lang/' in name and name.endswith('.json')
                }
        return {'signatures': signatures, 'wrapper': normalize_metadata(wrapper), 'resources': resources}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['capture', 'compare'])
    parser.add_argument('baseline', type=Path)
    args = parser.parse_args()
    actual = capture()
    if args.mode == 'capture':
        if args.baseline.exists():
            raise SystemExit('Refusing to overwrite an existing baseline')
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + '\n')
        print(f'Captured {len(actual["signatures"])} class signatures and 15 nested JARs')
    else:
        expected = json.loads(args.baseline.read_text())
        errors = [name for name, signature in expected['signatures'].items()
                  if signature.startswith(('public ', 'protected '))
                  and actual['signatures'].get(name) != signature]
        for section in ['wrapper', 'resources']:
            if actual[section] != normalize_metadata(expected[section]):
                errors.append(section)
        if errors:
            raise SystemExit('Parity differences:\n' + '\n'.join(errors))
        count = sum(signature.startswith(('public ', 'protected '))
                    for signature in expected['signatures'].values())
        print(f'Unchanged {count} public/protected class signatures, metadata, mixins and translations')
