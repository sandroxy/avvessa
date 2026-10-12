#!/usr/bin/env python3
"""Compile the packaged UTS bridge for classic uni-app and uni-app x without launching a device."""
import argparse
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def run(command, **kwargs): subprocess.run([str(x) for x in command], check=True, **kwargs)

def check(args):
    hbuilder = args.hbuilder_contents.resolve()
    compiler = hbuilder/'plugins/uniapp-uts-v1/node_modules/@dcloudio/uni-uts-v1'
    node = hbuilder/'plugins/node/node'
    extension = hbuilder/'plugins/uniapp-runextension'
    classic_libs = extension/'lib'
    kotlin_plugin = compiler/'lib/kotlin/lib/uts-kotlin-compiler-plugin.jar'
    x_libs = args.x_android_sdk.resolve()/'SDK/libs'
    x_ios = args.x_ios_sdk.resolve()/'SDK/Libs'
    work = args.output.resolve()
    if work == ROOT or ROOT not in work.parents or subprocess.run(['git','check-ignore','-q',str(work)],cwd=ROOT).returncode:
        raise SystemExit('Output must be inside an ignored repository build directory')
    work.mkdir(parents=True,exist_ok=True)
    module = work/'input/uni_modules/Sandrox-Avvessa'
    if module.exists(): shutil.rmtree(module)
    module.mkdir(parents=True)
    with zipfile.ZipFile(args.archive) as archive:
        for name in archive.namelist():
            if Path(name).is_absolute() or '..' in Path(name).parts: raise SystemExit('Unsafe package entry')
        archive.extractall(module)
    js = """const compiler = require(process.argv[1]); compiler.compile(process.argv[2], {isX:process.argv[3]==='true',isPlugin:true,isSingleThread:true,sourceMap:true}).then(result=>{if(!result||result.errMsg)throw new Error(result?.errMsg||'No UTS result')}).catch(error=>{console.error(error);process.exit(1)})"""
    for runtime in ('classic','x'):
        for platform in ('android','ios'):
            out = work/f'{runtime}-{platform}'
            if out.exists(): shutil.rmtree(out)
            out.mkdir()
            env = os.environ | {'UNI_INPUT_DIR':str(work/'input'),'UNI_OUTPUT_DIR':str(out),'UNI_UTS_PLATFORM':'app-'+platform,
                                'UNI_APP_X':'true' if runtime=='x' else 'false','NODE_ENV':'production',
                                'UNI_APP_X_DOM2':'true' if runtime=='x' and args.x_renderer=='vapor' else 'false'}
            run([node,'-e',js,compiler,module,'true' if runtime=='x' else 'false'],env=env)
        android = work/f'{runtime}-android/uni_modules/Sandrox-Avvessa/utssdk/app-android'
        extracted = work/f'{runtime}-classes'
        extracted.mkdir(exist_ok=True)
        jars = list(classic_libs.glob('*.jar')) if runtime=='classic' else [classic_libs/name for name in
            ('android_36.jar','kotlin-stdlib-2.2.0.jar','kotlinx-coroutines-core-jvm-1.6.4.jar')]
        archives = list((android/'libs').glob('*.aar'))
        if runtime=='x':
            archives += [x_libs/name for name in ('app-common-release.aar','app-runtime-release.aar','uts-runtime-release.aar')]
            # The native renderer needs its framework archive; Vapor uses app-common/app-runtime.
            if args.x_renderer=='native': archives.append(x_libs/'framework-release.aar')
        for aar in archives:
            with zipfile.ZipFile(aar) as archive:
                if 'classes.jar' in archive.namelist():
                    dest=extracted/(aar.stem+'.jar'); dest.write_bytes(archive.read('classes.jar')); jars.append(dest)
        jars += list((android/'libs').glob('*.jar'))
        run([extension/'kotlinc/bin/kotlinc','-classpath',os.pathsep.join(str(p) for p in jars),'-d',work/f'{runtime}-kotlin',
             '-Xplugin='+str(kotlin_plugin),'-P','plugin:io.dcloud.uts.kotlin:tag=UTS','-P','plugin:io.dcloud.uts.kotlin:console=true',android/'src/index.kt'])
        ios = work/f'{runtime}-ios/uni_modules/Sandrox-Avvessa/utssdk/app-ios'
        sdk=subprocess.check_output(['xcrun','--sdk','iphoneos','--show-sdk-path'],text=True).strip()
        frameworks = [ios/'Frameworks']
        if runtime=='classic': frameworks += [args.classic_ios_sdk.resolve()/'SDK/Libs']
        else: frameworks += [x_ios/name/'ios-arm64' for name in ('DCloudUTSFoundation.xcframework','DCloudUniappRuntime.xcframework')]
        run(['xcrun','swiftc','-typecheck','-sdk',sdk,'-target','arm64-apple-ios'+('15.0' if runtime=='x' else '13.0'),
             *[arg for path in frameworks for arg in ('-F',path)],ios/'src/index.swift'])
    print('Classic UNI and uni-app x Android/iOS UTS compiler checks passed.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path)
    parser.add_argument('--hbuilder-contents',type=Path,required=True)
    parser.add_argument('--classic-ios-sdk',type=Path,required=True)
    parser.add_argument('--x-android-sdk',type=Path,required=True)
    parser.add_argument('--x-ios-sdk',type=Path,required=True)
    parser.add_argument('--x-renderer',choices=('native','vapor'),default='native',help='Match the supplied uni-app x SDK and consumer renderer')
    parser.add_argument('--output',type=Path,default=ROOT/'.build/uniapp/compiler')
    check(parser.parse_args())
