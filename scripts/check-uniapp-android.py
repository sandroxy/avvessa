#!/usr/bin/env python3
"""Link a packaged UNI plugin and optional consumer resources into an unsigned Android test artifact."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
ET.register_namespace('tools', 'http://schemas.android.com/tools')

def check(args):
    output = args.output.resolve()
    if output == ROOT or ROOT not in output.parents or subprocess.run(['git','check-ignore','-q',str(output)],cwd=ROOT).returncode:
        raise SystemExit('Output must be inside an ignored repository build directory')
    package = output/'package'
    if package.exists(): shutil.rmtree(package)
    package.mkdir(parents=True)
    with zipfile.ZipFile(args.archive) as archive:
        if any(Path(n).is_absolute() or '..' in Path(n).parts for n in archive.namelist()): raise SystemExit('Unsafe package entry')
        archive.extractall(package)
    metadata = json.loads((package/'package.json').read_text())
    legacy = metadata.get('_dp_type') == 'nativeplugin'
    android = package/('android' if legacy else 'utssdk/app-android/libs')
    dependencies = metadata['_dp_nativeplugin']['android']['dependencies'] if legacy else json.loads((android.parent/'config.json').read_text())['dependencies']
    app = output/'app'; libs = app/'libs'
    if libs.exists(): shutil.rmtree(libs)
    libs.mkdir(parents=True)
    for artifact in android.rglob('*'):
        if artifact.suffix in ('.aar','.jar'): shutil.copy2(artifact,libs/artifact.name)
    if args.configuration_aar: shutil.copy2(args.configuration_aar,libs/'avvessa-consumer.aar')
    if args.configuration_aar and not args.consumer_manifest:
        raise SystemExit('Consumer resources require --consumer-manifest from prepare-uniapp-android.py')
    if legacy:
        if not args.dcloud_android_aar: raise SystemExit('Legacy linkage requires --dcloud-android-aar')
        shutil.copy2(args.dcloud_android_aar,libs/'dcloud-uniapp.aar')
    def write(relative,text):
        path=output/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);path.chmod(0o600)
    write('settings.gradle',"pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name='AvvessaUniLinkCheck'\ninclude(':app')\n")
    write('build.gradle',"plugins { id 'com.android.application' version '8.12.3' apply false }\n")
    write('gradle.properties','android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2048m\n')
    lines=["plugins { id 'com.android.application' }", "android { namespace 'com.sandrox.avvessa.unicheck'; compileSdk 36; defaultConfig { applicationId 'com.sandrox.avvessa.unicheck'; minSdk 23; targetSdk 35 }; buildTypes { release { minifyEnabled false } } }",
           "dependencies { implementation fileTree(dir: 'libs', include: ['*.aar','*.jar'])"]
    lines += ['implementation '+json.dumps(coordinate) for coordinate in dependencies]
    lines += ['}']
    write('app/build.gradle','\n'.join(lines)+'\n')
    expected_metadata = {}
    retained_permissions = set()
    if args.consumer_manifest:
        manifest = ET.parse(args.consumer_manifest).getroot()
        manifest.attrib.pop('package', None)
        retained_permissions = {p.get(f'{{{ANDROID}}}name') for p in manifest.findall('uses-permission') if p.get('{http://schemas.android.com/tools}node') == 'merge'}
        expected_metadata = {entry.get(f'{{{ANDROID}}}name'): entry.get(f'{{{ANDROID}}}value',entry.get(f'{{{ANDROID}}}resource'))
                             for entry in manifest.findall('application/meta-data')}
        write('app/src/main/AndroidManifest.xml',ET.tostring(manifest,encoding='unicode'))
    else:
        write('app/src/main/AndroidManifest.xml','<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="Avvessa link check" /></manifest>\n')
    log=output/'build.log'
    with log.open('w') as stream:
        result=subprocess.run([str(ROOT/'native/android/gradlew'),'-p',str(output),':app:assembleRelease','--console=plain'],cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
    if result.returncode: raise SystemExit(f'Android package linkage failed; inspect {log}')
    merged = ET.parse(app/'build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml').getroot()
    actual_metadata = {entry.get(f'{{{ANDROID}}}name'): entry.get(f'{{{ANDROID}}}value',entry.get(f'{{{ANDROID}}}resource'))
                       for entry in merged.findall('application/meta-data')}
    if any(actual_metadata.get(key) != value for key,value in expected_metadata.items()):
        raise SystemExit('The packaged application did not preserve consumer metadata overrides')
    if any(entry.get(f'{{{ANDROID}}}name') == 'cn.jpush.android.service.InitProvider' for entry in merged.findall('application/provider')):
        raise SystemExit('The packaged application retained implicit JPush initialization')
    removed = {entry.get(f'{{{ANDROID}}}name') for entry in ET.parse(ROOT/'native/android/avvessa/src/main/AndroidManifest.xml').getroot().findall('uses-permission')
               if entry.get('{http://schemas.android.com/tools}node') == 'remove'}
    final_permissions = {entry.get(f'{{{ANDROID}}}name') for entry in merged.findall('uses-permission')}
    if not retained_permissions <= final_permissions: raise SystemExit('A permission explicitly retained by the host was removed')
    if (removed-retained_permissions).intersection(final_permissions):
        raise SystemExit('The packaged application retained permissions removed by Avvessa')
    print('Android package resources, manifest, privacy merge rules and bytecode linked. This artifact is not a runnable delivery test.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path)
    parser.add_argument('--configuration-aar',type=Path)
    parser.add_argument('--consumer-manifest',type=Path,help='Generated application-priority AndroidManifest.xml')
    parser.add_argument('--dcloud-android-aar',type=Path)
    parser.add_argument('--output',type=Path,default=ROOT/'.build/uniapp/android-link')
    check(parser.parse_args())
