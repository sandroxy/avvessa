import hashlib
import importlib.util
import json
import io
from pathlib import Path
import sys
import tempfile
import tarfile
import unittest
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from release_artifacts import read_zip, zip_tree, read_npm, verify_react_native
spec=importlib.util.spec_from_file_location('vendors',Path(__file__).resolve().parents[1]/'configure-uniapp-vendors.py')
vendors=importlib.util.module_from_spec(spec);spec.loader.exec_module(vendors)


class DistributionTests(unittest.TestCase):
    def test_npm_requires_complete_canonical_inputs_and_rejects_modified_or_private_files(self):
        package = {'name': '@sandrox/avvessa', 'version': '1.1.0', 'publishConfig': {'access': 'public'},
                   'files': ['index.js', 'shared', 'avvessa-native.json']}
        metadata = {'version': '1.1.0', 'adapters': {'reactNative': {'package': '@sandrox/avvessa'}}}
        source = {'plugin.json': json.dumps(metadata).encode(),
                  'adapters/react-native/package.json': json.dumps(package).encode(),
                  'adapters/react-native/index.js': b'public facade',
                  'adapters/react-native/inputs.json': json.dumps({'core/bridge.js': 'shared/bridge.js', 'plugin.json': 'avvessa-native.json'}).encode(),
                  'core/bridge.js': b'canonical bridge'}
        contents = {'package.json': source['adapters/react-native/package.json'], 'index.js': b'public facade',
                    'shared/bridge.js': b'canonical bridge', 'avvessa-native.json': source['plugin.json']}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'package.tgz'
            def pack(files):
                with tarfile.open(path, 'w:gz') as archive:
                    for name, data in files.items():
                        item = tarfile.TarInfo('package/'+name);item.size=len(data)
                        archive.addfile(item, io.BytesIO(data))
            pack(contents)
            self.assertEqual(verify_react_native(path, '1.1.0', source), contents)
            for files in [contents | {'shared/bridge.js': b'changed'},
                          {k:v for k,v in contents.items() if k != 'shared/bridge.js'},
                          contents | {'agconnect-services.json': b'private'},
                          contents | {'../escape': b'bad'}]:
                pack(files)
                with self.assertRaises(ValueError): verify_react_native(path, '1.1.0', source)
            with tarfile.open(path, 'w:gz') as archive:
                item=tarfile.TarInfo('package/linked');item.type=tarfile.SYMTYPE;item.linkname='/tmp/other'
                archive.addfile(item)
            with self.assertRaises(ValueError): read_npm(path)

    def test_duplicate_and_traversing_archive_entries_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'bad.zip'
            for name in ['../escape','/absolute','a/../escape','a\\b']:
                with zipfile.ZipFile(path,'w') as archive:archive.writestr(name,'bad')
                with self.assertRaises(ValueError):read_zip(path)

    def test_zip_preserves_executable_tools_and_rejects_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/'input';root.mkdir();tool=root/'gradlew';tool.write_text('fixture');tool.chmod(0o755)
            target=Path(temporary)/'a.zip';zip_tree(root,target)
            with read_zip(target) as archive:self.assertEqual(archive.getinfo('gradlew').external_attr>>16 & 0o777,0o755)
            (root/'alias').symlink_to(tool)
            with self.assertRaises(ValueError):zip_tree(root,target)

    def test_explicit_vendor_install_remove_and_consumer_edit_protection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'tools').mkdir();(root/'utssdk/app-android/libs').mkdir(parents=True)
            (root/'package.json').write_text(json.dumps({'version':'1.0.0'}))
            config=root/'utssdk/app-android/config.json';config.write_text(json.dumps({'dependencies':['base:push:1']}))
            content=b'vendor fixture';digest=hashlib.sha256(content).hexdigest()
            kit=root/'tools/android-vendors.zip'
            with zipfile.ZipFile(kit,'w') as archive:
                archive.writestr('index.json',json.dumps({'version':'1.0.0','vendors':{'xiaomi':{'dependencies':['vendor:push:1'],'files':{'avvessa-xiaomi.aar':digest}}}}))
                archive.writestr('libs/avvessa-xiaomi.aar',content)
            (root/'avvessa-package.json').write_text(json.dumps({'vendorModules':[],'files':{'tools/android-vendors.zip':hashlib.sha256(kit.read_bytes()).hexdigest()},'dependencies':[{'coordinate':'base:push:1','bundled':False}]}))
            vendors.configure(root,['xiaomi'],kit)
            aar=root/'utssdk/app-android/libs/avvessa-xiaomi.aar'
            self.assertEqual(aar.read_bytes(),content)
            vendors.configure(root,[],kit);self.assertFalse(aar.exists())
            self.assertEqual(json.loads(config.read_text())['dependencies'],['base:push:1'])
            vendors.configure(root,['xiaomi'],kit);aar.write_bytes(b'user change')
            with self.assertRaises(ValueError):vendors.configure(root,[],kit)
            self.assertEqual(aar.read_bytes(),b'user change')
            with self.assertRaises(ValueError):vendors.configure(root,['missing'],kit)
