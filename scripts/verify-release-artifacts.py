#!/usr/bin/env python3
"""Verify frozen artifacts locally or downloaded GitHub assets; never modifies or publishes them."""
import argparse
import json
from pathlib import Path
from release_artifacts import MODULES, sha, read_zip, verify_packages, verify_react_native


def verify(args):
    if args.candidate:
        candidate=json.loads(args.candidate.read_text());version=candidate['version']
        artifacts={}
        for entry in candidate['artifacts']:
            relative=Path(entry['file'])
            if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe candidate path')
            path=args.candidate.parent/relative
            if path.is_symlink() or path.stat().st_size!=entry['bytes'] or sha(path)!=entry['sha256']:raise ValueError('Candidate bytes changed')
            artifacts[entry['role']]=path
        verify_packages(artifacts,version)
    else:
        version=args.version;directory=args.artifacts
        manifest=json.loads((directory/f'avvessa-native-{version}.json').read_text())
        if manifest['version']!=version:raise ValueError('Wrong native manifest')
        if args.source:
            import subprocess
            commit=subprocess.check_output(['git','-C',str(args.source),'rev-parse','HEAD'],text=True).strip()
            if manifest['source']['commit']!=commit or manifest['source']['dirty']:raise ValueError('Manifest does not refer to release tag')
        for module in MODULES:
            role='native-android-'+('core' if module=='avvessa' else module[8:])+'-aar'
            if sha(directory/f'{module}-{version}.aar')!=manifest['artifacts'][role]:raise ValueError('Maven mirror differs from accepted AAR')
        if args.channel == 'npm':
            if not args.source:
                raise ValueError('npm verification requires the release tag checkout')
            import hashlib
            tag_type = subprocess.check_output(['git', '-C', str(args.source), 'cat-file', '-t', 'refs/tags/'+version], text=True).strip()
            tag_commit = subprocess.check_output(['git', '-C', str(args.source), 'rev-list', '-n', '1', 'refs/tags/'+version], text=True).strip()
            if tag_type != 'tag' or tag_commit != commit:
                raise ValueError('npm source must be the annotated release tag')
            names = subprocess.check_output(['git', '-C', str(args.source), 'ls-files', '-z'], text=True).split('\0')
            source = {name: (args.source/name).read_bytes() for name in sorted(filter(None, names))}
            digest = hashlib.sha256(''.join(name+'\t'+hashlib.sha256(content).hexdigest()+'\n' for name, content in source.items()).encode()).hexdigest()
            if digest != manifest['source']['inputSha256']:
                raise ValueError('Release source content differs from accepted inputs')
            package = directory/f'avvessa-react-native-{version}.tgz'
            expected = manifest['artifacts']['react-native-package']
            if sha(package) != expected or Path(str(package)+'.sha256').read_text().strip() != expected+'  '+package.name:
                raise ValueError('npm tarball/checksum differs from accepted bytes')
            verify_react_native(package, version, source)
        if args.channel=='uniapp':
            native_ios=directory/f'avvessa-{version}.xcframework.zip'
            if sha(native_ios)!=manifest['artifacts']['native-ios-xcframework']:raise ValueError('Public XCFramework differs from accepted bytes')
            with read_zip(native_ios) as archive:
                import hashlib
                binary=archive.read('Avvessa.xcframework/ios-arm64/Avvessa.framework/Avvessa')
                if hashlib.sha256(binary).hexdigest()!=manifest['iosDeviceBinarySha256']:raise ValueError('Public iOS device slice mismatch')
            for mode,expected in [('uts',args.uts_sha256),('legacy',args.legacy_sha256)]:
                archive=directory/f'avvessa-uniapp-{"legacy-" if mode=="legacy" else ""}{version}.zip'
                if not expected or sha(archive)!=expected or expected!=manifest['artifacts'][f'uniapp-{mode}-package']:raise ValueError('UNI ZIP differs from accepted bytes')
                if Path(str(archive)+'.sha256').read_text().strip()!=expected+'  '+archive.name:raise ValueError('UNI checksum sidecar differs')
                with read_zip(archive) as zipped:
                    meta=json.loads(zipped.read('avvessa-package.json'))
                    import hashlib,io,zipfile
                    if meta['version']!=version or set(meta['files'])!={n for n in zipped.namelist() if not n.endswith('/')} - {'avvessa-package.json'}:raise ValueError('UNI inventory mismatch')
                    for name,digest in meta['files'].items():
                        if hashlib.sha256(zipped.read(name)).hexdigest()!=digest:raise ValueError('UNI embedded bytes differ')
                    android='android' if mode=='legacy' else 'utssdk/app-android/libs'
                    ios='ios' if mode=='legacy' else 'utssdk/app-ios/Frameworks'
                    if hashlib.sha256(zipped.read(android+'/avvessa.aar')).hexdigest()!=manifest['artifacts']['native-android-core-aar']:raise ValueError('UNI/Maven core mismatch')
                    if hashlib.sha256(zipped.read(ios+'/Avvessa.framework/Avvessa')).hexdigest()!=manifest['iosDeviceBinarySha256']:raise ValueError('UNI/native iOS mismatch')
                    kit=zipped.read('tools/android-vendors.zip')
                    if hashlib.sha256(kit).hexdigest()!=manifest['vendorKitSha256']:raise ValueError('Vendor kit mismatch')
                    with zipfile.ZipFile(io.BytesIO(kit)) as vendors:
                        for vendor in MODULES[1:]:
                            if hashlib.sha256(vendors.read('libs/'+vendor+'.aar')).hexdigest()!=manifest['artifacts']['native-android-'+vendor[8:]+'-aar']:raise ValueError('Vendor differs from Maven')
    print('Release artifact identity and embedded native bytes verified.')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--candidate',type=Path);p.add_argument('--version');p.add_argument('--artifacts',type=Path);p.add_argument('--source',type=Path);p.add_argument('--channel',choices=['android','npm','uniapp']);p.add_argument('--uts-sha256');p.add_argument('--legacy-sha256')
    args=p.parse_args()
    if not args.candidate and not all((args.version,args.artifacts,args.channel)):p.error('Choose --candidate, or --version/--artifacts/--channel')
    try:verify(args)
    except (ValueError,KeyError,FileNotFoundError) as error:raise SystemExit(str(error))
