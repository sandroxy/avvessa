#!/usr/bin/env python3
"""Build private consumer resources and a host manifest overlay for selected push channels."""
import argparse
import copy
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
ANDROID='http://schemas.android.com/apk/res/android'
TOOLS='http://schemas.android.com/tools'
ET.register_namespace('android',ANDROID); ET.register_namespace('tools',TOOLS)
PARAMETERS={'xiaomi':['XIAOMI_APPID','XIAOMI_APPKEY'],'oppo':['OPPO_APPID','OPPO_APPKEY','OPPO_APPSECRET'],
            'meizu':['MEIZU_APPID','MEIZU_APPKEY'],'vivo':['VIVO_APPID','VIVO_APPKEY'],'honor':['HONOR_APPID'],'huawei':[],'fcm':[]}
METADATA={'VIVO_APPID':'com.vivo.push.app_id','VIVO_APPKEY':'com.vivo.push.api_key','HONOR_APPID':'com.hihonor.push.app_id'}

def prepare(args):
    data=json.loads(args.config.read_text())
    android=data['android']
    vendors=android['vendorModules']; properties=android['vendorProperties']; identity=android['applicationId']
    import re
    if not isinstance(identity,str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+',identity): raise SystemExit('Invalid application ID')
    if len(set(vendors))!=len(vendors) or set(vendors)-set(PARAMETERS): raise SystemExit('Unknown or duplicate vendor modules')
    expected={key for vendor in vendors for key in PARAMETERS[vendor]}
    if set(properties)!=expected or any(not isinstance(v,str) or not v.strip() for v in properties.values()): raise SystemExit('Vendor properties differ from selected modules')
    work=args.output.resolve()
    if work==ROOT or ROOT not in work.parents or subprocess.run(['git','check-ignore','-q',str(work)],cwd=ROOT).returncode: raise SystemExit('Output must be inside an ignored product build directory')
    work.mkdir(parents=True,exist_ok=True)
    def write(path,text):
        p=work/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);p.chmod(0o600)
    write('settings.gradle', "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral(); maven { url 'https://developer.huawei.com/repo/' } } }\nrootProject.name='AvvessaUniConfiguration'\ninclude(':services',':configuration')\n")
    write('gradle.properties','android.useAndroidX=true\n')
    write('build.gradle', "buildscript { repositories { google(); mavenCentral(); maven { url 'https://developer.huawei.com/repo/' } }; dependencies { classpath 'com.huawei.agconnect:agcp:1.9.6.300' } }\nplugins { id 'com.android.application' version '8.12.3' apply false; id 'com.android.library' version '8.12.3' apply false; id 'com.google.gms.google-services' version '4.5.0' apply false }\n")
    write('gradle/libs.versions.toml','[plugins]\nandroid-application = { id = "com.android.application", version = "8.12.3" }\n')
    service="plugins { id 'com.android.application' }\nandroid { namespace 'com.sandrox.avvessa.uniservices'; compileSdk 36; defaultConfig { applicationId "+json.dumps(identity)+"; minSdk 21; targetSdk 36 } }\n"
    resources=[];tasks=[]
    if 'huawei' in vendors:
        source=Path(android['agconnectServicesFile']);config=json.loads(source.read_text())
        if config.get('client',{}).get('package_name')!=identity: raise SystemExit('Huawei service package differs')
        write('services/agconnect-services.json',source.read_text());service+="apply plugin: 'com.huawei.agconnect'\n"
        resources.append('../services/build/generated/res/agc/release');tasks.append(':services:processReleaseAGCPlugin')
    if 'fcm' in vendors:
        source=Path(android['googleServicesFile']);config=json.loads(source.read_text())
        if not any(c.get('client_info',{}).get('android_client_info',{}).get('package_name')==identity for c in config.get('client',[])): raise SystemExit('Firebase service package differs')
        write('services/google-services.json',source.read_text());service+="apply plugin: 'com.google.gms.google-services'\n"
        resources.append('../services/build/generated/res/google-services/release');tasks.append(':services:processReleaseGoogleServices')
    write('services/build.gradle',service);write('services/src/main/AndroidManifest.xml','<manifest />\n')
    library="plugins { id 'com.android.library' }\nandroid { namespace 'com.sandrox.avvessa.uniconfiguration'; compileSdk 36; defaultConfig { minSdk 21 }; sourceSets.main.res.srcDirs += "+json.dumps(resources)+" }\n"
    if tasks: library+="tasks.configureEach { if (name == 'preReleaseBuild') { dependsOn("+','.join(json.dumps(t) for t in tasks)+") } }\n"
    write('configuration/build.gradle',library)
    manifest=ET.Element('manifest'); app=ET.SubElement(manifest,'application'); strings=ET.Element('resources')
    def resource_string(value):
        # Android's string parser interprets backslashes, quotes and leading @ independently of XML.
        return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r').replace('\t','\\t')+'"'
    for key,value in properties.items():
        name='avvessa_'+key.lower(); ET.SubElement(strings,'string',{'name':name,'translatable':'false'}).text=resource_string(value)
        ET.SubElement(app,'meta-data',{f'{{{ANDROID}}}name':METADATA.get(key,key),f'{{{ANDROID}}}value':'@string/'+name,f'{{{TOOLS}}}replace':'android:value'})
    if 'huawei' in vendors:
        config=json.loads(Path(android['agconnectServicesFile']).read_text())
        ET.SubElement(strings,'string',{'name':'avvessa_huawei_app_id','translatable':'false'}).text=resource_string('appid='+str(config['client']['app_id']))
        ET.SubElement(app,'meta-data',{f'{{{ANDROID}}}name':'com.huawei.hms.client.appid',f'{{{ANDROID}}}value':'@string/avvessa_huawei_app_id',f'{{{TOOLS}}}replace':'android:value'})
    write('configuration/src/main/AndroidManifest.xml',ET.tostring(manifest,encoding='unicode'))
    write('configuration/src/main/res/values/avvessa.xml',ET.tostring(strings,encoding='unicode'))
    # Local AARs have no reliable priority relative to the vendor AARs. Apply merge rules at
    # the consuming application's priority instead of modifying official SDK artifacts.
    host_manifest=ET.Element('manifest',{'package':identity})
    core_manifest=ET.parse(ROOT/'native/android/avvessa/src/main/AndroidManifest.xml').getroot()
    for permission in core_manifest.findall('uses-permission'):
        host_manifest.append(copy.deepcopy(permission))
    host_app=ET.SubElement(host_manifest,'application')
    for entry in core_manifest.find('application'):
        if entry.tag=='meta-data' or entry.get(f'{{{TOOLS}}}node')=='remove':
            host_app.append(copy.deepcopy(entry))
    for entry in app:
        host_app.append(copy.deepcopy(entry))
    ET.indent(host_manifest)
    write('AndroidManifest.xml',ET.tostring(host_manifest,encoding='unicode')+'\n')
    icon=args.small_icon.resolve() if args.small_icon else ROOT/'native/android/avvessa/src/main/res/drawable/avvessa_notification.xml'
    write('configuration/src/main/res/drawable/avvessa_consumer_notification.xml',icon.read_text())
    subprocess.run([str(ROOT/'native/android/gradlew'),'-p',str(work),':configuration:assembleRelease','--console=plain'],check=True)
    print(work/'configuration/build/outputs/aar/configuration-release.aar')
    print(work/'AndroidManifest.xml')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',type=Path,required=True,help='Local JSON with android.applicationId, vendorModules, vendorProperties and service file paths')
    parser.add_argument('--small-icon',type=Path,help='Optional consumer Android vector drawable XML')
    parser.add_argument('--output',type=Path,default=ROOT/'.build/uniapp-consumer')
    prepare(parser.parse_args())
