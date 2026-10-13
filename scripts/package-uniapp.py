#!/usr/bin/env python3
"""Assemble credential-free legacy and UTS UNI packages from locally built native components."""
import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VENDORS = ('xiaomi','huawei','oppo','vivo','honor','meizu','fcm')

def run(*args): subprocess.run([str(x) for x in args], cwd=ROOT, check=True)
def write_json(path, value): path.parent.mkdir(parents=True,exist_ok=True); path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def copy(source,destination):
    destination.parent.mkdir(parents=True,exist_ok=True)
    if source.is_dir(): shutil.copytree(source,destination,dirs_exist_ok=True)
    else: shutil.copy2(source,destination)

def copy_source(source, destination):
    # Public source inputs include uncommitted work, but never ignored local configuration,
    # IDE state or generated artifacts that happen to live beside a module.
    files = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z','--',
                                     str(source.relative_to(ROOT))],cwd=ROOT).decode().split('\0')
    for relative in sorted(set(files)-{''}):
        path = ROOT/relative
        if path.is_symlink(): raise SystemExit('UNI source must not contain symbolic links')
        if path.is_file(): copy(path,destination/path.relative_to(source))

def package(args):
    selected = args.vendors.split(',') if args.vendors else []
    if len(set(selected)) != len(selected) or set(selected) - set(VENDORS): raise SystemExit('Unknown or duplicate vendor module')
    native = ROOT / 'native/android'; output = args.output.resolve()
    if output == ROOT or ROOT not in output.parents: raise SystemExit('Output must be inside this repository (use an ignored build or dist directory)')
    check = subprocess.run(['git','check-ignore','-q',str(output)],cwd=ROOT)
    if check.returncode != 0: raise SystemExit('UNI output must be ignored by Git')
    output.mkdir(parents=True,exist_ok=True)
    report = output / 'android-dependencies.json'
    modules = ['avvessa'] + ['avvessa-'+name for name in selected]
    run(native/'gradlew','-p',native,'-I',ROOT/'scripts/uniapp-dependencies.gradle',
        '-PavvessaVendors='+','.join(selected),'-PavvessaDependencyReport='+str(report),
        *[':'+name+':assembleRelease' for name in modules], 'exportUniAppDependencies', '--console=plain')
    run(native/'gradlew','-p',ROOT/'adapters/android',
        '-PavvessaCoreAar='+str(native/'avvessa/build/outputs/aar/avvessa-release.aar'),
        '-PdcloudUniappAar='+str(args.dcloud_android_aar.resolve()),':bridge:assembleRelease',':uniapp:assembleRelease','--console=plain')
    ios = output/'ios'
    if args.ios_products:
        ios = args.ios_products.resolve()
        for name in ('Avvessa.framework', 'AvvessaBridge.framework', 'AvvessaUni.framework', 'AvvessaPrivacy.bundle'):
            if not (ios/name).is_dir(): raise SystemExit('Missing prebuilt iOS product: '+name)
    else:
        run('python3',ROOT/'scripts/build-uniapp-ios.py','--source-packages',args.source_packages.resolve(),
            '--dcloud-ios-sdk',args.dcloud_ios_sdk.resolve(),'--output',ios)
    dependencies = json.loads(report.read_text())
    metadata = json.loads((ROOT/'plugin.json').read_text())
    common_dependencies = [entry['coordinate'] for entry in dependencies if not entry['bundled']]
    for kind in ('uts','legacy'):
        root = output/kind/'Sandrox-Avvessa'
        if root.exists(): shutil.rmtree(root)
        root.mkdir(parents=True)
        if kind == 'uts':
            copy_source(ROOT/'uni_modules/Sandrox-Avvessa',root)
            android = root/'utssdk/app-android'; libs=android/'libs'; frameworks=root/'utssdk/app-ios/Frameworks'
            config=json.loads((android/'config.json').read_text());config['dependencies']=common_dependencies
            write_json(android/'config.json',config)
            copy(ios/'AvvessaPrivacy.bundle',root/'utssdk/app-ios/Resources/AvvessaPrivacy.bundle')
            # Canonical JS implementation and declarations are supplied by the product, never the consumer.
            copy(ROOT/'adapters/uniapp/js_sdk/bridge.js',root/'js_sdk/bridge.js')
            copy(ROOT/'adapters/uniapp/js_sdk/index.d.ts',root/'js_sdk/index.d.ts')
        else:
            package=json.loads((ROOT/'adapters/uniapp/packaging/package.template.json').read_text().replace('@VERSION@',metadata['version']))
            package['_dp_nativeplugin']['android']['dependencies']=common_dependencies
            write_json(root/'package.json',package)
            android=root/'android';libs=android;frameworks=root/'ios'
            copy_source(ROOT/'adapters/uniapp/js_sdk',root/'js_sdk')
            copy(ios/'AvvessaPrivacy.bundle',frameworks/'AvvessaPrivacy.bundle')
        for module in modules: copy(native/module/'build/outputs/aar'/f'{module}-release.aar',libs/(module+'.aar'))
        copy(ROOT/'adapters/shared/android/build/outputs/aar/bridge-release.aar',libs/'avvessa-bridge.aar')
        for entry in dependencies:
            if entry['bundled']:
                source=Path(entry['file']); name=entry['coordinate'].replace(':','-')+source.suffix
                copy(source,(android/'libs' if kind == 'legacy' and source.suffix == '.jar' else libs)/name)
        for name in ['Avvessa','AvvessaBridge']+(['AvvessaUni'] if kind=='legacy' else []): copy(ios/(name+'.framework'),frameworks/(name+'.framework'))
        if kind=='legacy': copy(ROOT/'adapters/uniapp/android/build/outputs/aar/uniapp-release.aar',libs/'avvessa-uniapp.aar')
        for name in ('LICENSE','THIRD_PARTY_NOTICES.md'): copy(ROOT/name,root/name)
        readme=ROOT/'adapters/uniapp/README.md'
        if readme.is_file():
            # The same maintained guide is readable both in Git and as a standalone market package.
            base = 'https://github.com/sandroxy/avvessa/blob/'+metadata['version']+'/'
            def link(match):
                label, target = match.groups()
                if '://' in target or target.startswith('#'): return match.group(0)
                path, sep, fragment = target.partition('#')
                resolved = (readme.parent/path).resolve().relative_to(ROOT)
                return '['+label+']('+base+str(resolved)+(sep+fragment if sep else '')+')'
            (root/'readme.md').write_text(re.sub(r'\[([^\]]+)\]\(([^)]+)\)',link,readme.read_text()))
        copy(ROOT/'CHANGELOG.md',root/'changelog.md')
        if args.vendor_kit:
            if selected: raise SystemExit('A release vendor kit requires an unmodified base package')
            copy(args.vendor_kit,root/'tools/android-vendors.zip')
            copy(ROOT/'scripts/configure-uniapp-vendors.py',root/'tools/configure-android-vendors.py')
        provenance={'kind':'avvessa-uniapp-package','version':metadata['version'],'mode':kind,'vendorModules':selected,
                    'dependencies':[{k:v for k,v in record.items() if k!='file'} for record in dependencies],
                    'files':{str(path.relative_to(root)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(root.rglob('*')) if path.is_file()}}
        write_json(root/'avvessa-package.json',provenance)
        archive=output/f'avvessa-uniapp-{"legacy-" if kind == "legacy" else ""}{metadata["version"]}.zip'
        with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as zipped:
            for path in sorted(root.rglob('*')):
                if path.is_file(): zipped.write(path,str(path.relative_to(root)))
        (Path(str(archive)+'.sha256')).write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
        print(archive)

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vendors',default='',help='Comma-separated optional native transports; omitted means base only')
    parser.add_argument('--source-packages',type=Path,required=True)
    parser.add_argument('--dcloud-android-aar',type=Path,required=True)
    parser.add_argument('--dcloud-ios-sdk',type=Path,required=True)
    parser.add_argument('--ios-products',type=Path,help='Reuse frameworks built by this release preparation; never rebuild accepted bytes')
    parser.add_argument('--vendor-kit',type=Path,help='Include optional Android transports as inactive consumer-selected files')
    parser.add_argument('--output',type=Path,default=ROOT/'dist/uniapp')
    package(parser.parse_args())
