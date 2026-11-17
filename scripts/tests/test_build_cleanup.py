"""Exercise scoped compiler cleanup with files only; no native tools or installs."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('build_cleanup', Path(__file__).resolve().parents[1]/'clean-build-output.py')
cleanup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cleanup)


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.patch_root = patch.object(cleanup, 'ROOT', self.root)
        self.patch_root.start()
        self.git = patch.object(cleanup.subprocess, 'check_output', side_effect=self.git_output)
        self.git.start()
        self.put('plugin.json', '{"id":"avvessa","version":"1.2.0"}')

    def tearDown(self):
        self.git.stop()
        self.patch_root.stop()
        self.temp.cleanup()

    def git_output(self, args, **kwargs):
        return 'a'*40+'\n' if kwargs.get('text') else b''

    def put(self, name, content='fixture', mode=0o644):
        p = self.root/name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        p.chmod(mode)
        return p

    def test_cleanup_preserves_inputs_dependencies_reports_and_credentials(self):
        output = self.put('.build/ios-device/Build/Products/Fixture.framework/Fixture')
        output.parent.chmod(0o555)
        private = self.put('.build/ios-device/Build/config.local.json', 'private', 0o600)
        report = self.put('.build/ios-device/Build/result.xcresult/Data/record', 'evidence')
        log = self.put('native/android/avvessa/build/build.log', 'original log')
        native = self.put('native/android/avvessa/build/classes/Object.class')
        keeps = [self.put('dist/release/accepted.zip'), self.put('doc/dev/release/receipt.json'),
                 self.put('.build/ios-device/SourcePackages/checkouts/fixture/build/input'),
                 self.put('.build/uniapp/project/Pods/Fixture/build/input'),
                 self.put('.build/uniapp/project/node_modules/package/android/build/fixture'),
                 self.put('.build/uniapp/project/consumer.json'),
                 self.put('.build/unreviewed-scratch/build/object.o')]
        before = {p: p.read_bytes() for p in keeps}
        paths, token = cleanup.plan()
        cleanup.clean()
        self.assertTrue(output.exists())
        with patch.object(cleanup, 'require_idle'):
            cleanup.clean(token, apply=True)
        self.assertFalse(output.exists())
        self.assertFalse(native.exists())
        self.assertEqual(private.read_text(), 'private')
        self.assertEqual(private.stat().st_mode & 0o777, 0o600)
        self.assertEqual(report.read_text(), 'evidence')
        self.assertEqual(log.read_text(), 'original log')
        for p, data in before.items(): self.assertEqual(p.read_bytes(), data)
        self.assertEqual(cleanup.outputs(), [])

    def test_missing_completion_changed_output_tracked_input_and_busy_process_fail_closed(self):
        output = self.put('.build/ios-device/Build/object.o')
        _, token = cleanup.plan()
        with self.assertRaisesRegex(ValueError, 'completed-plan'):
            cleanup.clean(apply=True)
        output.write_text('different output')
        with self.assertRaisesRegex(ValueError, 'changed since completion'):
            cleanup.clean(token, apply=True)
        _, token = cleanup.plan()
        with patch.object(cleanup, 'require_idle', side_effect=ValueError('busy')):
            with self.assertRaisesRegex(ValueError, 'busy'):
                cleanup.clean(token, apply=True)
        with patch.object(cleanup.subprocess, 'check_output', return_value=b'.build/ios-device/Build/object.o\0'):
            with self.assertRaisesRegex(ValueError, 'tracked inputs'):
                cleanup.plan()
        self.assertTrue(output.exists())

    def test_symlinked_output_is_rejected_and_external_target_is_untouched(self):
        outside = self.put('other-project/private.json')
        scope = self.root/'.build/ios-device'
        scope.mkdir(parents=True)
        (scope/'Build').symlink_to(outside.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symbolic link'):
            cleanup.plan()
        self.assertTrue(outside.exists())

    def test_interrupted_removal_restores_original_evidence(self):
        self.put('.build/ios-device/Build/object.o')
        log = self.put('.build/ios-device/Build/original.log', 'evidence', 0o600)
        _, token = cleanup.plan()
        def interrupted(_):
            log.unlink()
            raise OSError('interrupted')
        with patch.object(cleanup, 'require_idle'), patch.object(cleanup.shutil, 'rmtree', side_effect=interrupted):
            with self.assertRaisesRegex(OSError, 'interrupted'):
                cleanup.clean(token, apply=True)
        self.assertEqual(log.read_text(), 'evidence')
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)

    def test_open_output_files_block_cleanup(self):
        path = self.root/'.build/ios-device/Build'
        result = subprocess.CompletedProcess([], 0, 'p123\nn'+str(path/'object.o')+'\n', '')
        with patch.object(cleanup.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(ValueError, 'in use'):
                cleanup.require_idle([path])


if __name__ == '__main__':
    unittest.main()
