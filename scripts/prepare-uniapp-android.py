#!/usr/bin/env python3
"""Build private consumer resources and a host manifest overlay for selected push channels."""
import argparse
import copy
import json
import os
import shutil
import subprocess
import struct
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
ANDROID='http://schemas.android.com/apk/res/android'
TOOLS='http://schemas.android.com/tools'
ET.register_namespace('android',ANDROID); ET.register_namespace('tools',TOOLS)
PARAMETERS={'xiaomi':['XIAOMI_APPID','XIAOMI_APPKEY'],'oppo':['OPPO_APPID','OPPO_APPKEY','OPPO_APPSECRET'],
            'meizu':['MEIZU_APPID','MEIZU_APPKEY'],'vivo':['VIVO_APPID','VIVO_APPKEY'],'honor':['HONOR_APPID'],'huawei':[],'fcm':[]}
METADATA={'VIVO_APPID':'com.vivo.push.app_id','VIVO_APPKEY':'com.vivo.push.api_key','HONOR_APPID':'com.hihonor.push.app_id'}


def notification_icons(source):
    """Return validated consumer resources without modifying the supplied artwork."""
    source = Path(source)
    def png(path):
        data = path.read_bytes()
        if len(data) < 33 or data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
            raise SystemExit('Notification PNG is invalid')
        width, height = struct.unpack('>II', data[16:24])
        if not width or width != height or width > 1024:
            raise SystemExit('Notification PNG must be square and no larger than 1024 pixels')
        return data
    if source.is_dir():
        resources = {}
        for density, size in {'mdpi':24,'hdpi':36,'xhdpi':48,'xxhdpi':72,'xxxhdpi':96}.items():
            path = source / (density + '.png')
            data = png(path)
            if struct.unpack('>II',data[16:24]) != (size,size): raise SystemExit(f'{density}.png must be {size}x{size}')
            resources[f'drawable-{density}/avvessa_consumer_notification.png'] = data
        if {p.name for p in source.iterdir()} != {d+'.png' for d in ('mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi')}:
            raise SystemExit('Icon directory must contain exactly the five density PNG files')
        return resources
    if source.suffix == '.png': return {'drawable-nodpi/avvessa_consumer_notification.png':png(source)}
    if source.suffix != '.xml' or ET.parse(source).getroot().tag != 'vector': raise SystemExit('Notification icon must be a vector XML, PNG, or density directory')
    return {'drawable/avvessa_consumer_notification.xml':source.read_bytes()}


def permission_overlay(core, retained):
    removed = {item.get(f'{{{ANDROID}}}name') for item in core.findall('uses-permission') if item.get(f'{{{TOOLS}}}node') == 'remove'}
    if not isinstance(retained,list) or any(not isinstance(item,str) for item in retained) or len(retained) != len(set(retained)) or set(retained)-removed:
        raise SystemExit('retainedPermissions must contain unique permissions otherwise removed by Avvessa')
    output=[]
    for permission in core.findall('uses-permission'):
        item=copy.deepcopy(permission)
        if item.get(f'{{{ANDROID}}}name') in retained: item.set(f'{{{TOOLS}}}node','merge')
        output.append(item)
    return output

def prepare(args):
    data=json.loads(args.config.read_text())
    android=data['android']
    vendors=android['vendorModules']; properties=android['vendorProperties']; identity=android['applicationId']
    import re
    if not isinstance(identity,str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+',identity): raise SystemExit('Invalid application ID')
    if len(set(vendors))!=len(vendors) or set(vendors)-set(PARAMETERS): raise SystemExit('Unknown or duplicate vendor modules')
    expected={key for vendor in vendors for key in PARAMETERS[vendor]}
    if set(properties)!=expected or any(not isinstance(v,str) or not v.strip() for v in properties.values()): raise SystemExit('Vendor properties differ from selected modules')
    core_manifest=ET.parse(ROOT/'native/android/avvessa/src/main/AndroidManifest.xml').getroot()
    permissions=permission_overlay(core_manifest,android.get('retainedPermissions',[]))
    icon=args.small_icon.resolve() if args.small_icon else ROOT/'native/android/avvessa/src/main/res/drawable/avvessa_notification.xml'
    icons=notification_icons(icon)
    color=getattr(args,'notification_color',None)
    if color is not None and not re.fullmatch(r'#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?',color): raise SystemExit('notification color must be #RRGGBB or #AARRGGBB')
    work=args.output.resolve()
    if work==ROOT or ROOT not in work.parents: raise SystemExit('Output must remain inside the package directory')
    in_git = subprocess.run(['git','rev-parse','--is-inside-work-tree'],cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode == 0
    if in_git:
        if subprocess.run(['git','check-ignore','-q',str(work)],cwd=ROOT).returncode: raise SystemExit('Output must be ignored by Git')
    elif work != ROOT/'.build' and ROOT/'.build' not in work.parents:
        raise SystemExit('Extracted source packages write consumer resources only under .build/')
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
    if 'fcm' in vendors:
        ET.SubElement(app,'meta-data',{f'{{{ANDROID}}}name':'com.google.firebase.messaging.default_notification_icon',f'{{{ANDROID}}}resource':'@drawable/avvessa_consumer_notification',f'{{{TOOLS}}}replace':'android:resource'})
        if color:
            ET.SubElement(strings,'color',{'name':'avvessa_consumer_notification_color'}).text=color
            ET.SubElement(app,'meta-data',{f'{{{ANDROID}}}name':'com.google.firebase.messaging.default_notification_color',f'{{{ANDROID}}}resource':'@color/avvessa_consumer_notification_color'})
    write('configuration/src/main/AndroidManifest.xml',ET.tostring(manifest,encoding='unicode'))
    write('configuration/src/main/res/values/avvessa.xml',ET.tostring(strings,encoding='unicode'))
    # Local AARs have no reliable priority relative to the vendor AARs. Apply merge rules at
    # the consuming application's priority instead of modifying official SDK artifacts.
    host_manifest=ET.Element('manifest',{'package':identity})
    for permission in permissions: host_manifest.append(permission)
    host_app=ET.SubElement(host_manifest,'application')
    for entry in core_manifest.find('application'):
        if entry.tag=='meta-data' or entry.get(f'{{{TOOLS}}}node')=='remove':
            host_app.append(copy.deepcopy(entry))
    for entry in app:
        host_app.append(copy.deepcopy(entry))
    ET.indent(host_manifest)
    write('AndroidManifest.xml',ET.tostring(host_manifest,encoding='unicode')+'\n')
    resources_root=work/'configuration/src/main/res'
    for old in resources_root.glob('drawable*/avvessa_consumer_notification.*'): old.unlink()
    for relative,content in icons.items():
        target=resources_root/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(content);target.chmod(0o600)
    subprocess.run([str(ROOT/'native/android/gradlew'),'-p',str(work),':configuration:assembleRelease','--console=plain'],check=True)
    print(work/'configuration/build/outputs/aar/configuration-release.aar')
    print(work/'AndroidManifest.xml')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',type=Path,required=True,help='Local JSON with android.applicationId, vendorModules, vendorProperties and service file paths')
    parser.add_argument('--small-icon',type=Path,help='Consumer vector XML, square PNG, or directory with mdpi.png through xxxhdpi.png')
    parser.add_argument('--notification-color',help='Optional FCM default tint, #RRGGBB or #AARRGGBB')
    parser.add_argument('--output',type=Path,default=ROOT/'.build/uniapp-consumer')
    prepare(parser.parse_args())
