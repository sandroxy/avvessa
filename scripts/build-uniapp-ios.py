#!/usr/bin/env python3
"""Build device or simulator static frameworks for UNI, using the pinned official SDK checkouts."""
import argparse
import json
import plistlib
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def run(*args):
    subprocess.run([str(x) for x in args], cwd=ROOT, check=True)

def build(args):
    metadata = json.loads((ROOT / 'plugin.json').read_text())
    native = metadata['native']['ios']
    pins = json.loads((ROOT / 'Package.resolved').read_text())['pins']
    packages = args.source_packages.resolve()
    sdks = []
    for name in ('jpush', 'jcore'):
        checkout = packages / 'checkouts' / (name + '-sdk')
        expected = next(pin['state']['revision'] for pin in pins if pin['identity'] == name + '-sdk')
        actual = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip()
        run('git','-C',checkout,'diff','--quiet','HEAD','--')
        if actual != expected:
            raise SystemExit(f'{name}: SDK checkout does not match Package.resolved')
        slice_name = 'ios-arm64' if args.sdk == 'iphoneos' else 'ios-arm64_x86_64-simulator'
        sdks.append(checkout / f'{name}-ios-{native[name]}.xcframework' / slice_name)
    run('python3', ROOT / 'scripts/ios-privacy.py', '--jpush-sdk', packages / 'checkouts/jpush-sdk', '--jcore-sdk', packages / 'checkouts/jcore-sdk', '--check')
    output = args.output.resolve()
    if output == ROOT or ROOT not in output.parents or subprocess.run(['git','check-ignore','-q',str(output)],cwd=ROOT).returncode:
        raise SystemExit('Output must be inside an ignored repository build directory')
    output.mkdir(parents=True, exist_ok=True)
    sdk = subprocess.check_output(['xcrun', '--sdk', args.sdk, '--show-sdk-path'], text=True).strip()
    if args.arch != 'arm64' and args.sdk != 'iphonesimulator':
        raise SystemExit('Only arm64 is supported on physical iOS devices')
    target = f'{args.arch}-apple-ios{native["minimumVersion"]}' + ('-simulator' if args.sdk == 'iphonesimulator' else '')
    flags = ['-isysroot', sdk, '-target', target, '-fobjc-arc', '-fblocks', '-fmodules', '-O2', '-fvisibility=hidden',
             '-ffile-prefix-map=' + str(ROOT) + '=.', '-Werror=implicit-function-declaration', '-F', str(output)]
    for path in sdks: flags += ['-I', str(path / 'Headers')]

    def framework(name, sources, public, includes=(), libraries=()):
        destination = output / (name + '.framework')
        if destination.exists(): shutil.rmtree(destination)
        (destination / 'Headers').mkdir(parents=True)
        (destination / 'Modules').mkdir()
        for source in public: shutil.copy2(source, destination / 'Headers' / source.name)
        (destination / 'Modules/module.modulemap').write_text(f'framework module {name} {{ umbrella header "{name}.h" export * module * {{ export * }} }}\n')
        info = {'CFBundleExecutable':name, 'CFBundleIdentifier':'com.sandrox.avvessa.' + name.lower(),
                'CFBundleName':name, 'CFBundlePackageType':'FMWK', 'CFBundleShortVersionString':metadata['version'],
                'CFBundleVersion':'1', 'MinimumOSVersion':native['minimumVersion'],
                'CFBundleSupportedPlatforms':['iPhoneOS' if args.sdk == 'iphoneos' else 'iPhoneSimulator']}
        (destination / 'Info.plist').write_bytes(plistlib.dumps(info))
        objects = output / 'objects' / name
        objects.mkdir(parents=True, exist_ok=True)
        compiled = []
        for source in sources:
            obj = objects / (source.stem + '.o')
            run('xcrun', 'clang', *flags, *[arg for p in includes for arg in ('-I', str(p))], '-c', source, '-o', obj)
            compiled.append(obj)
        selected_libraries = []
        for library in libraries:
            architectures = subprocess.check_output(['xcrun','lipo','-archs',str(library)],text=True).split()
            if args.arch not in architectures: raise SystemExit('SDK archive lacks '+args.arch)
            if len(architectures) > 1:
                selected = objects / library.name
                run('xcrun','lipo',library,'-thin',args.arch,'-output',selected)
                selected_libraries.append(selected)
            else: selected_libraries.append(library)
        run('xcrun', 'libtool', '-static', '-o', destination / name, *compiled, *selected_libraries)
        return destination

    core = ROOT / 'native/ios/Sources/Avvessa'
    framework('Avvessa', sorted(core.glob('*.m')), [core / 'include/Avvessa.h'], [core / 'include'],
              [sdks[0] / 'libJPush.a', sdks[1] / 'libJCore.a'])
    bridge = ROOT / 'adapters/shared/ios'
    framework('AvvessaBridge', sorted(bridge.glob('*.m')), [bridge / 'include/AvvessaBridge.h'])
    if args.dcloud_ios_sdk:
        headers = args.dcloud_ios_sdk.resolve() / 'SDK/inc'
        if not (headers / 'DCUni/DCUniModule.h').is_file(): raise SystemExit('DCloud DCUniModule.h is missing')
        umbrella = output / 'objects/AvvessaUni/AvvessaUni.h'
        umbrella.parent.mkdir(parents=True, exist_ok=True)
        umbrella.write_text('#import <Foundation/Foundation.h>\n')
        framework('AvvessaUni', sorted((ROOT / 'adapters/uniapp/ios').glob('*.m')), [umbrella],
                  [headers / 'DCUni', headers / 'weexHeader', headers])
    privacy = output / 'AvvessaPrivacy.bundle'
    privacy.mkdir(exist_ok=True)
    shutil.copy2(core / 'Resources/PrivacyInfo.xcprivacy', privacy / 'PrivacyInfo.xcprivacy')
    (privacy / 'Info.plist').write_bytes(plistlib.dumps({'CFBundleIdentifier':'com.sandrox.avvessa.privacy', 'CFBundlePackageType':'BNDL', 'CFBundleVersion':'1'}))
    # Archiving alone cannot reveal unresolved SDK symbols. Link without signing or running;
    # Swift drives this probe because the official JPush archive also contains Swift objects.
    probe = output / 'objects/link-check.swift'
    probe.write_text('import AvvessaBridge\nlet session = AVBridgeSession()\n')
    system_frameworks = ('UIKit','UserNotifications','Network','Security','CFNetwork','SystemConfiguration',
                         'Foundation','CoreFoundation','CoreTelephony','CoreGraphics','WebKit')
    run('xcrun','--sdk',args.sdk,'swiftc','-sdk',sdk,'-target',target,'-F',output,
        probe,'-o',output/'objects/link-check','-framework','Avvessa','-framework','AvvessaBridge',
        *[arg for name in system_frameworks for arg in ('-framework', name)],'-lz','-lresolv','-Xlinker','-ObjC')
    print(output)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-packages', type=Path, required=True, help='Xcode SourcePackages directory containing pinned checkouts')
    parser.add_argument('--dcloud-ios-sdk', type=Path, help='Official classic SDK root; also builds the legacy transport')
    parser.add_argument('--sdk', choices=['iphoneos','iphonesimulator'], default='iphoneos')
    parser.add_argument('--arch', choices=['arm64','x86_64'], default='arm64')
    parser.add_argument('--output', type=Path, required=True)
    build(parser.parse_args())
