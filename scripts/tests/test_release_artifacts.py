import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from release_artifacts import read_zip, zip_tree
spec=importlib.util.spec_from_file_location('vendors',Path(__file__).resolve().parents[1]/'configure-uniapp-vendors.py')
vendors=importlib.util.module_from_spec(spec);spec.loader.exec_module(vendors)


class DistributionTests(unittest.TestCase):
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
