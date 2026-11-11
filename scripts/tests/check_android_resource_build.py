"""Build consumer resources with synthetic service configuration; never initialize a push SDK."""
import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.build/android-resource-tests'
PROJECT = WORK / 'project'
ARCHIVE = PROJECT / 'configuration/build/outputs/aar/configuration-release.aar'
ANDROID = 'http://schemas.android.com/apk/res/android'
TOOLS = 'http://schemas.android.com/tools'


def service_configuration():
    return {
        'project_info': {'project_number': '1234567890', 'project_id': 'avvessa-resource-test',
                         'firebase_url': 'https://example.invalid'},
        'client': [
            {'client_info': {'mobilesdk_app_id': '1:1234567890:android:' + suffix,
                             'android_client_info': {'package_name': 'com.example.avvessa.' + name}},
             'api_key': [{'current_key': 'test-only-' + name}]}
            for name, suffix in [('other', '1111111111111111'), ('resources', '2222222222222222')]
        ],
        'configuration_version': '1',
    }


def archive_strings():
    with zipfile.ZipFile(ARCHIVE) as archive:
        return {element.attrib['name']: element.text
                for name in archive.namelist() if name.startswith('res/values/') and name.endswith('.xml')
                for element in ET.fromstring(archive.read(name)).findall('string')}


class AndroidResourceBuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        WORK.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.service = service_configuration()
        self.android = {
            'applicationId': 'com.example.avvessa.resources', 'vendorModules': ['fcm'],
            'vendorProperties': {}, 'googleServicesFile': str(WORK / 'google-services.json'),
            'agconnectServicesFile': None, 'retainedPermissions': ['android.permission.READ_EXTERNAL_STORAGE'],
        }
        self.calls = 0

    def run_command(self, command, succeeds=True):
        self.calls += 1
        result = subprocess.run([str(arg) for arg in command], cwd=ROOT, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        log = WORK / f'{self._testMethodName}-{self.calls}.log'
        log.write_text(result.stdout)
        if succeeds:
            self.assertEqual(result.returncode, 0, f'Build failed; inspect {log}\n{result.stdout[-5000:]}')
        else:
            self.assertNotEqual(result.returncode, 0, f'Invalid resources were accepted; inspect {log}')
        return result.stdout

    def generate(self, succeeds=True):
        (WORK / 'google-services.json').write_text(json.dumps(self.service))
        config = WORK / 'consumer.json'
        config.write_text(json.dumps({'android': self.android}))
        return self.run_command([sys.executable, '-B', ROOT / 'scripts/prepare-uniapp-android.py',
                                 '--config', config, '--output', PROJECT], succeeds)

    def gradle(self, *arguments, succeeds=True):
        return self.run_command([ROOT / 'native/android/gradlew', '-p', PROJECT,
                                 *arguments, '--console=plain'], succeeds)

    def assert_selected_client(self, client_index=1):
        values = archive_strings()
        client = self.service['client'][client_index]
        self.assertEqual(values['google_app_id'], client['client_info']['mobilesdk_app_id'])
        self.assertEqual(values['google_api_key'], client['api_key'][0]['current_key'])
        self.assertEqual(values['gcm_defaultSenderId'], self.service['project_info']['project_number'])
        self.assertEqual(values['project_id'], self.service['project_info']['project_id'])
        self.assertNotIn('Configuration \'firebaseResources\' was resolved during configuration time.',
                         (WORK / f'{self._testMethodName}-{self.calls}.log').read_text())

    def test_clean_incremental_and_vendor_switches(self):
        self.generate()
        self.gradle(':services:clean', ':configuration:clean')
        self.generate()
        self.assert_selected_client()
        self.assertIn('firebase_database_url', archive_strings())
        self.assertIn(':services:processReleaseGoogleServices UP-TO-DATE', self.generate())
        self.assert_selected_client()

        self.android['applicationId'] = 'com.example.avvessa.other'
        self.service['project_info'] = {'project_number': '9876543210', 'project_id': 'avvessa-updated-test'}
        self.service['client'][0]['client_info']['mobilesdk_app_id'] = '1:9876543210:android:3333333333333333'
        self.service['client'][0]['api_key'][0]['current_key'] = 'test-only-updated'
        self.generate()
        self.assert_selected_client(0)
        self.assertNotIn('firebase_database_url', archive_strings())

        self.android.update(vendorModules=['honor'], vendorProperties={'HONOR_APPID': '123456'}, googleServicesFile=None)
        self.generate()
        values = archive_strings()
        self.assertIn('avvessa_honor_appid', values)
        self.assertFalse({'google_app_id', 'google_api_key', 'gcm_defaultSenderId', 'project_id'} & values.keys())
        overlay = ET.parse(PROJECT / 'AndroidManifest.xml').getroot()
        rules = {item.get(f'{{{ANDROID}}}name'): item.get(f'{{{TOOLS}}}node')
                 for item in overlay.findall('uses-permission')}
        self.assertEqual(rules['android.permission.READ_EXTERNAL_STORAGE'], 'merge')
        self.assertEqual(rules['android.permission.ACCESS_FINE_LOCATION'], 'remove')
        with zipfile.ZipFile(ARCHIVE) as archive:
            self.assertIn('res/drawable/avvessa_consumer_notification.xml', archive.namelist())

        self.android.update(vendorModules=['fcm'], vendorProperties={},
                            googleServicesFile=str(WORK / 'google-services.json'))
        self.generate()
        self.assert_selected_client(0)
        self.assertNotIn('avvessa_honor_appid', archive_strings())

    def test_output_directory_is_owned_by_google_services(self):
        self.generate()
        init = WORK / 'relocate-firebase.gradle'
        init.write_text("""allprojects {
    tasks.configureEach {
        if (name == 'processReleaseGoogleServices') {
            outputDirectory.set(project.layout.buildDirectory.dir('relocated-firebase'))
        }
    }
}
""")
        self.gradle(':configuration:assembleRelease', ':configuration:verifyFirebaseResources', '--init-script', init)
        self.assert_selected_client()
        self.assertTrue((PROJECT / 'services/build/relocated-firebase/values/values.xml').is_file())

    def test_packaged_resource_loss_or_change_fails_verification(self):
        self.generate()
        original = ARCHIVE.read_bytes()
        try:
            for remove in (True, False):
                with self.subTest(remove=remove):
                    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(ARCHIVE, 'w') as target:
                        for entry in source.infolist():
                            content = source.read(entry)
                            if entry.filename.startswith('res/values/') and entry.filename.endswith('.xml'):
                                root = ET.fromstring(content)
                                for element in root.findall('string'):
                                    if element.get('name') == 'google_app_id':
                                        if remove:
                                            root.remove(element)
                                        else:
                                            element.text = 'another-client'
                                content = ET.tostring(root)
                            target.writestr(copy.copy(entry), content)
                    output = self.gradle(':configuration:verifyFirebaseResources', '-x', ':configuration:bundleReleaseAar', succeeds=False)
                    self.assertIn('Firebase resource missing or changed in consumer AAR: google_app_id', output)
        finally:
            ARCHIVE.write_bytes(original)

    def test_unmatched_application_id_is_rejected(self):
        self.android['applicationId'] = 'com.example.avvessa.unregistered'
        self.assertIn('Firebase service package differs', self.generate(succeeds=False))

    def test_fcm_keeps_inject_as_a_maven_dependency(self):
        report = WORK / 'fcm-dependencies.json'
        self.run_command([ROOT / 'native/android/gradlew', '-p', ROOT / 'native/android',
                          '-I', ROOT / 'scripts/uniapp-dependencies.gradle', '-PavvessaVendors=fcm',
                          '-PavvessaDependencyReport=' + str(report), 'exportUniAppDependencies', '--console=plain'])
        dependencies = json.loads(report.read_text())
        inject = [item for item in dependencies if item['coordinate'].startswith('javax.inject:javax.inject:')]
        self.assertEqual(len(inject), 1)
        self.assertFalse(inject[0]['bundled'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
