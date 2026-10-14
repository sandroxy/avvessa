#!/usr/bin/env python3
"""Build and freeze local release inputs. Never uploads, commits, tags, starts devices or sends push."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from release_artifacts import MODULES, VENDORS, sha, write_json, zip_tree, verify_packages

ROOT=Path(__file__).resolve().parent.parent


def run(*args, **kwargs): subprocess.run([str(x) for x in args],cwd=ROOT,check=True,**kwargs)
def capture(*args): return subprocess.check_output([str(x) for x in args],cwd=ROOT,text=True).strip()


def source_files():
    names=capture('git','ls-files','--cached','--others','--exclude-standard','-z').split('\0')
    return sorted({name for name in names if name and (ROOT/name).is_file()})


def source_digest(files):
    return hashlib.sha256(''.join(name+'\t'+sha(ROOT/name)+'\n' for name in files).encode()).hexdigest()


def prepare(args):
    meta=json.loads((ROOT/'plugin.json').read_text());version=meta['version']
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)',version): raise ValueError('Stable SemVer required')
    if not args.rehearsal and not args.signing_fingerprint: raise ValueError('A full public GPG signing fingerprint is required; use --rehearsal for unsigned checks')
    if args.signing_fingerprint and not re.fullmatch(r'(?:[0-9A-F]{40}|[0-9A-F]{64})',args.signing_fingerprint): raise ValueError('Use a full uppercase signing fingerprint')
    commit=capture('git','rev-parse','HEAD');dirty=bool(capture('git','status','--porcelain'))
    if dirty and not args.rehearsal: raise ValueError('Formal preparation requires committed, clean source')
    files=source_files();digest=source_digest(files)
    work=ROOT/'.build/release'; out=ROOT/('dist/rehearsal' if args.rehearsal else 'dist/release')/version
    for directory in (work,out):
        if directory.is_symlink() or ROOT not in directory.resolve().parents: raise ValueError('Release output escapes repository')
        directory.mkdir(parents=True,exist_ok=True)
    current=out/'candidate.json'
    if current.exists():
        raise ValueError('A frozen candidate already exists. Inspect and preserve it explicitly before another preparation; never replace accepted files')
    run('python3',ROOT/'scripts/check-metadata.py')
    policy=json.loads((ROOT/'release-policy.json').read_text())
    repository=work/'maven'
    # This is disposable compiler output, not a candidate or published registry.
    if repository.exists(): shutil.rmtree(repository)
    native=ROOT/'native/android'; gradle=native/'gradlew'
    run(gradle,'-p',native,'-PavvessaCandidateRepository='+str(repository),
        *[':'+name+':publishReleasePublicationToCandidateRepository' for name in MODULES],'--console=plain')
    artifacts={}
    bundle=work/'central'
    if bundle.exists(): shutil.rmtree(bundle)
    payloads=[]
    for name in MODULES:
        path=Path('io/github/sandroxy')/name/version
        for suffix in ('.aar','.pom','.module','-sources.jar','-javadoc.jar'):
            filename=name+'-'+version+suffix
            source=repository/path/filename
            if not source.is_file(): raise ValueError('Maven output missing: '+filename)
            dest=bundle/path/filename;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest);payloads.append(dest)
        role='native-android-'+('core' if name=='avvessa' else name[8:])+'-aar'
        artifacts[role]=out/f'{name}-{version}.aar';shutil.copy2(repository/path/f'{name}-{version}.aar',artifacts[role])
    for path in payloads:
        contents=[path]
        if args.signing_fingerprint:
            run('gpg','--batch','--armor','--local-user',args.signing_fingerprint,'--detach-sign','--output',str(path)+'.asc',path)
            contents.append(Path(str(path)+'.asc'))
        for content in contents:
            for algorithm in ('md5','sha1','sha256','sha512'):
                Path(str(content)+'.'+algorithm).write_text(hashlib.new(algorithm,content.read_bytes()).hexdigest()+'\n')
    artifacts['native-android-maven-repository']=out/f'avvessa-{version}-maven.zip'
    zip_tree(bundle,artifacts['native-android-maven-repository'])
    if args.signing_fingerprint:
        # Verify every signature against the explicitly selected identity, not just .asc presence.
        expression="require ARGV.shift; a=JSON.parse(ARGV.shift); MavenSignatures.verify!(repository:ARGV[0],aars:a,version:ARGV[1],signer:ARGV[2])"
        aars={m:str(artifacts['native-android-'+('core' if m=='avvessa' else m[8:])+'-aar']) for m in MODULES}
        run('ruby','-rjson','-e',expression,ROOT/'scripts/verify-maven-signatures.rb',json.dumps(aars),artifacts['native-android-maven-repository'],version,args.signing_fingerprint)
    # Resolve each optional graph independently. Nothing under tools/ is loaded by a UNI runtime.
    kit=work/'vendors'
    if kit.exists(): shutil.rmtree(kit)
    (kit/'libs').mkdir(parents=True)
    index={'kind':'avvessa-android-vendors','version':version,'vendors':{}}
    for vendor in VENDORS:
        report=work/f'{vendor}-dependencies.json'
        run(gradle,'-p',native,'-I',ROOT/'scripts/uniapp-dependencies.gradle','-PavvessaVendors='+vendor,
            '-PavvessaDependencyReport='+str(report),'exportUniAppDependencies','--console=plain')
        entries=json.loads(report.read_text());record={'dependencies':[],'files':{}}
        paths=[artifacts['native-android-'+vendor+'-aar']]
        for entry in entries:
            if entry['bundled']:
                source=Path(entry['file']);name=entry['coordinate'].replace(':','-')+source.suffix
                dest=kit/'libs'/name;shutil.copy2(source,dest);record['files'][name]=sha(dest)
            else: record['dependencies'].append(entry['coordinate'])
        name='avvessa-'+vendor+'.aar';dest=kit/'libs'/name;shutil.copy2(paths[0],dest);record['files'][name]=sha(dest)
        index['vendors'][vendor]=record
    write_json(kit/'index.json',index)
    kit_archive=work/'android-vendors.zip';zip_tree(kit,kit_archive)
    # One device build feeds both UNI archives and the native XCFramework.
    for sdk,arch in [('iphoneos','arm64'),('iphonesimulator','arm64'),('iphonesimulator','x86_64')]:
        command=['python3',ROOT/'scripts/build-uniapp-ios.py','--source-packages',args.source_packages.resolve(),
            '--sdk',sdk,'--arch',arch,'--output',work/f'{sdk}-{arch}']
        if sdk=='iphoneos': command += ['--dcloud-ios-sdk',args.dcloud_ios_sdk.resolve()]
        run(*command)
    device=work/'iphoneos-arm64'; simulator=work/'iphonesimulator-arm64/Avvessa.framework'
    run('xcrun','lipo','-create',simulator/'Avvessa',work/'iphonesimulator-x86_64/Avvessa.framework/Avvessa','-output',work/'simulator-universal')
    shutil.copy2(work/'simulator-universal',simulator/'Avvessa')
    ios=work/'native-ios'
    if ios.exists(): shutil.rmtree(ios)
    ios.mkdir()
    run('xcodebuild','-create-xcframework','-framework',device/'Avvessa.framework','-framework',simulator,'-output',ios/'Avvessa.xcframework')
    shutil.copytree(device/'AvvessaPrivacy.bundle',ios/'AvvessaPrivacy.bundle')
    for name in ('LICENSE','THIRD_PARTY_NOTICES.md'):shutil.copy2(ROOT/name,ios/name)
    artifacts['native-ios-xcframework']=out/f'avvessa-{version}.xcframework.zip';zip_tree(ios,artifacts['native-ios-xcframework'])
    # Source SPM distribution uses the same fixed source and pinned official packages as the Git tag.
    source=work/'source'
    if source.exists(): shutil.rmtree(source)
    for name in files:
        path=ROOT/name
        if path.is_symlink(): raise ValueError('Source package must not contain symbolic links')
        dest=source/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dest)
    artifacts['native-ios-spm-source']=out/f'avvessa-{version}-source.zip';zip_tree(source,artifacts['native-ios-spm-source'])
    uni=out/'uniapp'
    run('python3',ROOT/'scripts/package-uniapp.py','--source-packages',args.source_packages.resolve(),
        '--dcloud-android-aar',args.dcloud_android_aar.resolve(),'--dcloud-ios-sdk',args.dcloud_ios_sdk.resolve(),
        '--ios-products',device,'--vendor-kit',kit_archive,'--output',uni)
    for mode in ('uts','legacy'):
        artifacts[f'uniapp-{mode}-package']=uni/f'avvessa-uniapp-{"legacy-" if mode=="legacy" else ""}{version}.zip'
    npm = Path(capture('python3', ROOT/'scripts/prepare-react-native.py', '--output', work/'react-native', '--pack'))
    artifacts['react-native-package'] = out/npm.name
    shutil.copy2(npm, artifacts['react-native-package'])
    if source_digest(source_files())!=digest or capture('git','rev-parse','HEAD')!=commit:
        raise ValueError('Source changed during preparation; outputs are not qualified')
    manifest={'schemaVersion':1,'kind':'avvessa-native-release','version':version,
        'source':{'repository':meta['repository'],'commit':commit,'dirty':dirty,'inputSha256':digest},
        'signingFingerprint':args.signing_fingerprint,'vendorKitSha256':sha(kit_archive),
        'mavenPayloads':{path.relative_to(bundle).as_posix():sha(path) for path in payloads},
        'iosDeviceBinarySha256':sha(device/'Avvessa.framework/Avvessa'),
        'artifacts':{role:sha(path) for role,path in artifacts.items()},'sdkVersions':meta['native']}
    artifacts['native-build-manifest']=out/f'avvessa-native-{version}.json';write_json(artifacts['native-build-manifest'],manifest)
    verify_packages(artifacts,version)
    for role,path in list(artifacts.items()):
        if role=='native-build-manifest':continue
        checksum=Path(str(path)+'.sha256');checksum.write_text(sha(path)+'  '+path.name+'\n');artifacts['checksum-'+role]=checksum
    command=['ruby',ROOT/'scripts/snapshot-release-candidate.rb','--plugin','avvessa','--policy',ROOT/'release-policy.json',
        '--version',version,'--repository',meta['repository'],'--commit',commit,'--dirty',str(dirty).lower(),
        '--root',ROOT,'--output-dir',out,'--state','rehearsal' if args.rehearsal else 'candidate',
        '--qualification','androidMavenSigned='+str(bool(args.signing_fingerprint)).lower(),
        '--qualification','artifactProvenanceVerified=true']
    for kind,option in [('automatedTargets','--automated-target'),('manualTargets','--manual-target')]:
        for target in policy['acceptance'][kind]: command += [option,target]
    for role,path in artifacts.items(): command += ['--artifact',role+'='+str(path)]
    run(*command)
    print('Local preparation complete. No publication performed.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-packages',type=Path,required=True)
    parser.add_argument('--dcloud-android-aar',type=Path,required=True)
    parser.add_argument('--dcloud-ios-sdk',type=Path,required=True)
    parser.add_argument('--signing-fingerprint',help='Public fingerprint only; GPG manages private keys locally')
    parser.add_argument('--rehearsal',action='store_true',help='Allow uncommitted source and unsigned artifacts; never eligible for formal acceptance')
    try: prepare(parser.parse_args())
    except (ValueError,KeyError,FileNotFoundError,subprocess.CalledProcessError) as error: raise SystemExit(str(error))
