"""Checks shared by local release preparation and public artifact verification."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import zipfile

VENDORS = ('fcm','honor','huawei','meizu','oppo','vivo','xiaomi')
MODULES = ('avvessa',)+tuple('avvessa-'+v for v in VENDORS)


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')


def zip_tree(root, destination):
    """Stable entry ordering/timestamps, explicit Unix permissions, no links or hidden machine state."""
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(root.rglob('*')):
            if path.is_symlink(): raise ValueError('Symbolic links are not release inputs')
            if not path.is_file(): continue
            name=path.relative_to(root).as_posix()
            if any(p.startswith('.') for p in Path(name).parts): continue
            entry=zipfile.ZipInfo(name,(2020,1,1,0,0,0))
            entry.external_attr=(stat.S_IFREG | (0o755 if path.stat().st_mode & 0o111 else 0o644))<<16
            entry.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(entry,path.read_bytes())


def read_zip(path):
    archive=zipfile.ZipFile(path)
    names=archive.namelist()
    for info in archive.infolist():
        name=info.filename.rstrip('/')
        if not name or '\\' in name or PurePosixPath(name).is_absolute() or any(p in ('','.', '..') for p in name.split('/')) or stat.S_ISLNK(info.external_attr>>16):
            archive.close(); raise ValueError('Unsafe ZIP entry')
    if len(names)!=len(set(names)):
        archive.close(); raise ValueError('Duplicate ZIP entry')
    return archive


def verify_packages(artifacts, version):
    manifest=json.loads(artifacts['native-build-manifest'].read_text())
    if manifest['version'] != version or manifest['kind'] != 'avvessa-native-release': raise ValueError('Native manifest mismatch')
    for role,digest in manifest['artifacts'].items():
        if sha(artifacts[role]) != digest: raise ValueError('Native artifact mismatch: '+role)
    with read_zip(artifacts['native-android-maven-repository']) as maven:
        expected={f'io/github/sandroxy/{module}/{version}/{module}-{version}{suffix}' for module in MODULES
                  for suffix in ('.aar','.pom','.module','-sources.jar','-javadoc.jar')}
        if set(manifest['mavenPayloads'])!=expected: raise ValueError('Maven payload inventory mismatch')
        for name,digest in manifest['mavenPayloads'].items():
            if hashlib.sha256(maven.read(name)).hexdigest()!=digest: raise ValueError('Maven payload mismatch: '+name)
        for module in MODULES:
            role='native-android-'+('core' if module=='avvessa' else module[8:])+'-aar'
            entry=f'io/github/sandroxy/{module}/{version}/{module}-{version}.aar'
            if hashlib.sha256(maven.read(entry)).hexdigest()!=sha(artifacts[role]): raise ValueError('Maven AAR mismatch')
    ios_hash=manifest['iosDeviceBinarySha256']
    with read_zip(artifacts['native-ios-xcframework']) as archive:
        binary='Avvessa.xcframework/ios-arm64/Avvessa.framework/Avvessa'
        if hashlib.sha256(archive.read(binary)).hexdigest()!=ios_hash: raise ValueError('iOS device slice mismatch')
        if 'AvvessaPrivacy.bundle/PrivacyInfo.xcprivacy' not in archive.namelist(): raise ValueError('Missing iOS privacy bundle')
    kits=[]
    for kind in ('uts','legacy'):
        with read_zip(artifacts[f'uniapp-{kind}-package']) as archive:
            meta=json.loads(archive.read('avvessa-package.json'))
            package=json.loads(archive.read('package.json'))
            if package['id']!='Sandrox-Avvessa' or package['version']!=version or meta['version']!=version or meta['mode']!=kind or meta['vendorModules']!=[]:
                raise ValueError('UNI identity/version/base mismatch')
            files={n for n in archive.namelist() if not n.endswith('/')} - {'avvessa-package.json'}
            if files != set(meta['files']): raise ValueError('UNI file inventory mismatch')
            for name,digest in meta['files'].items():
                if hashlib.sha256(archive.read(name)).hexdigest()!=digest: raise ValueError('UNI embedded file mismatch: '+name)
            android='android' if kind=='legacy' else 'utssdk/app-android/libs'
            ios='ios' if kind=='legacy' else 'utssdk/app-ios/Frameworks'
            if hashlib.sha256(archive.read(android+'/avvessa.aar')).hexdigest()!=sha(artifacts['native-android-core-aar']): raise ValueError('UNI core AAR differs from Maven')
            if hashlib.sha256(archive.read(ios+'/Avvessa.framework/Avvessa')).hexdigest()!=ios_hash: raise ValueError('UNI core differs from XCFramework')
            kits.append(hashlib.sha256(archive.read('tools/android-vendors.zip')).hexdigest())
            for name in files:
                if name.endswith(('.local.json','.p8','.p12','.keystore','.jks')) or Path(name).name in ('agconnect-services.json','google-services.json','development.json'):
                    raise ValueError('Private configuration in public package')
    if len(set(kits))!=1 or kits[0]!=manifest['vendorKitSha256']: raise ValueError('UNI vendor kit differs')
    return manifest
