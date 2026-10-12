"""Consumer resources and host-level permission overrides, without a device or credentials."""
import importlib.util
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('prepare', Path(__file__).resolve().parents[1] / 'prepare-uniapp-android.py')
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)

def png(size):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\xff\xff\xff\0' * size) * size)) + chunk(b'IEND', b'')

class ConsumerResourcesTest(unittest.TestCase):
    def test_density_images_are_preserved_without_resampling(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for density, size in {'mdpi':24,'hdpi':36,'xhdpi':48,'xxhdpi':72,'xxxhdpi':96}.items():
                (root / (density + '.png')).write_bytes(png(size))
            result = prepare.notification_icons(root)
            self.assertEqual(len(result), 5)
            self.assertEqual(result['drawable-hdpi/avvessa_consumer_notification.png'], png(36))
            (root / 'hdpi.png').write_bytes(png(24))
            with self.assertRaises(SystemExit): prepare.notification_icons(root)

    def test_single_png_and_vector_use_their_own_resource_qualifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'icon.png').write_bytes(png(48))
            self.assertIn('drawable-nodpi/avvessa_consumer_notification.png', prepare.notification_icons(root / 'icon.png'))
            (root / 'icon.xml').write_text('<vector />')
            self.assertIn('drawable/avvessa_consumer_notification.xml', prepare.notification_icons(root / 'icon.xml'))
            (root / 'icon.png').write_bytes(b'not a png')
            with self.assertRaises(SystemExit): prepare.notification_icons(root / 'icon.png')

    def test_retaining_one_host_permission_does_not_restore_unrelated_permissions(self):
        root = ET.parse(prepare.ROOT / 'native/android/avvessa/src/main/AndroidManifest.xml').getroot()
        retained = 'android.permission.READ_EXTERNAL_STORAGE'
        original = ET.tostring(root)
        rules = {p.get(f'{{{prepare.ANDROID}}}name'):p.get(f'{{{prepare.TOOLS}}}node') for p in prepare.permission_overlay(root,[retained])}
        self.assertEqual(rules[retained], 'merge')
        self.assertEqual(rules['android.permission.ACCESS_FINE_LOCATION'], 'remove')
        self.assertEqual(ET.tostring(root),original)
        for invalid in [[retained,retained],['android.permission.INTERNET'],['unknown.permission'],None]:
            with self.assertRaises(SystemExit): prepare.permission_overlay(root,invalid)

if __name__ == '__main__': unittest.main()
