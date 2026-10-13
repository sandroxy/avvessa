#!/usr/bin/env python3
"""Install explicitly selected transports into a local UNI consumer. Never publish that configured copy."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def configure(module, vendors, kit):
    if module.is_symlink() or any(path.is_symlink() for path in module.rglob('*')):
        raise ValueError('Consumer package must not contain symbolic links')
    module = module.resolve()
    package = json.loads((module/'package.json').read_text())
    legacy = package.get('_dp_type') == 'nativeplugin'
    libs = module/('android' if legacy else 'utssdk/app-android/libs')
    config_path = module/'utssdk/app-android/config.json'
    config = package if legacy else json.loads(config_path.read_text())
    node = config['_dp_nativeplugin']['android'] if legacy else config
    provenance = json.loads((module/'avvessa-package.json').read_text())
    if provenance['vendorModules']:
        raise ValueError('Start from the base release ZIP, not a source-built vendor variant')
    expected_kit = provenance['files'].get('tools/android-vendors.zip')
    if not expected_kit or hashlib.sha256(kit.read_bytes()).hexdigest() != expected_kit:
        raise ValueError('Vendor kit differs from the package manifest')
    state_path = module/'avvessa-vendors.local.json'
    state = json.loads(state_path.read_text()) if state_path.exists() else {'files':{},'dependencies':[]}
    # Refuse to replace any consumer-owned changes. A new set removes only this tool's prior files.
    for relative, digest in state['files'].items():
        path = module/relative
        if not safe(relative) or path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Previously installed vendor files changed; start from a fresh package')
    with zipfile.ZipFile(kit) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not all(safe(n) for n in names):
            raise ValueError('Unsafe vendor archive')
        index = json.loads(archive.read('index.json'))
        if index['version'] != package['version'] or len(vendors) != len(set(vendors)) or set(vendors)-set(index['vendors']):
            raise ValueError('Unknown, duplicate or mismatched vendor selection')
        files = {}; dependencies = set()
        for vendor in vendors:
            record = index['vendors'][vendor]
            dependencies.update(record['dependencies'])
            for name, digest in record['files'].items():
                content = archive.read('libs/'+name)
                if not safe(name) or '/' in name or hashlib.sha256(content).hexdigest() != digest:
                    raise ValueError('Vendor file digest differs')
                # DCloud legacy JARs belong under android/libs; AARs are at android/.
                path = libs/('libs' if legacy and name.endswith('.jar') else '')/name
                relative = path.relative_to(module).as_posix()
                if path.exists() and relative not in state['files']:
                    raise ValueError('Refusing to overwrite existing consumer file: '+relative)
                files[relative] = content
        base = [entry['coordinate'] for entry in provenance['dependencies'] if not entry['bundled']]
        expected_dependencies = sorted(set(base+state['dependencies']))
        if sorted(node['dependencies']) != expected_dependencies:
            raise ValueError('Consumer dependencies changed; merge them explicitly in a fresh configured copy')
        for relative in state['files']: (module/relative).unlink()
        for relative, content in files.items():
            path = module/relative; path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(content)
        node['dependencies'] = sorted(set(base)|dependencies)
        (module/'package.json' if legacy else config_path).write_text(json.dumps(config,ensure_ascii=False,indent=2)+'\n')
        state_path.write_text(json.dumps({'vendors':vendors,'dependencies':sorted(dependencies),
            'files':{name:hashlib.sha256(value).hexdigest() for name,value in files.items()}},indent=2)+'\n')
    print('Configured transports: '+(', '.join(vendors) or 'base only'))
    print('Supply matching consumer resources and application Manifest before building. This local copy is not a marketplace upload.')


def safe(name):
    return bool(name) and '\\' not in name and not PurePosixPath(name).is_absolute() and all(p not in ('','.', '..') for p in name.split('/'))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module',type=Path,required=True)
    parser.add_argument('--vendors',default='',help='Comma-separated explicit selection; empty removes tool-installed transports')
    args=parser.parse_args()
    try: configure(args.module,args.vendors.split(',') if args.vendors else [],Path(__file__).with_name('android-vendors.zip'))
    except (ValueError,KeyError,FileNotFoundError,zipfile.BadZipFile) as error: raise SystemExit(str(error))
