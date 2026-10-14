#!/usr/bin/env python3
"""Materialize the complete RN package and optionally npm-pack it. Never publishes."""
import argparse
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def prepare(output, pack=False):
    if output.is_symlink() or any(parent.is_symlink() for parent in output.absolute().parents):
        raise ValueError('Output must not traverse symbolic links')
    output = output.resolve()
    if ROOT not in output.parents:
        raise ValueError('Output must be an ignored directory inside this repository')
    if subprocess.run(['git', 'check-ignore', '-q', str(output)], cwd=ROOT).returncode:
        raise ValueError('Output must be ignored by Git')
    marker = output / '.avvessa-rn-source'
    if output.exists():
        if not marker.is_file() or marker.read_text() != 'avvessa-react-native-source\n':
            raise ValueError('Refusing to replace an unmanaged package directory')
        shutil.rmtree(output)
    def copy_sources(scope, destination):
        paths = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', str(scope)], cwd=ROOT).decode().split('\0')
        for name in sorted(set(filter(None, paths))):
            origin = ROOT / name
            if not origin.exists():
                continue
            if origin.is_symlink() or not origin.is_file():
                raise ValueError('Package input must be a regular source file')
            target = destination / origin.relative_to(ROOT / scope)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(origin, target)
    copy_sources(Path('adapters/react-native'), output)
    inputs = json.loads((ROOT / 'adapters/react-native/inputs.json').read_text())
    for source, destination in inputs.items():
        if any(Path(value).is_absolute() or '..' in Path(value).parts for value in (source, destination)):
            raise ValueError('Package inputs must be relative paths without parent traversal')
        origin, target = ROOT / source, output / destination
        if origin.is_symlink():
            raise ValueError('Package input must not be a symbolic link')
        target.parent.mkdir(parents=True, exist_ok=True)
        if origin.is_dir():
            copy_sources(Path(source), target)
        else:
            shutil.copy2(origin, target)
    package = json.loads((output / 'package.json').read_text())
    package['version'] = json.loads((ROOT / 'plugin.json').read_text())['version']
    (output / 'package.json').write_text(json.dumps(package, indent=2) + '\n')
    marker.write_text('avvessa-react-native-source\n')
    if pack:
        result = subprocess.check_output(['npm', 'pack', '--ignore-scripts', '--json'], cwd=output, text=True)
        packed = json.loads(result)
        if len(packed) != 1:
            raise ValueError('npm pack must produce exactly one tarball')
        print(output / packed[0]['filename'])
    else:
        print(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pack', action='store_true', help='Create the local npm tarball without running package scripts')
    args = parser.parse_args()
    prepare(args.output, args.pack)
