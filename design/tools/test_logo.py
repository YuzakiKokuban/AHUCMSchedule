"""Checks the output contract that themed launchers depend on."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
import logo_spec as spec

ANDROID = '{http://schemas.android.com/apk/res/android}'

class LogoContractTest(unittest.TestCase):
    def test_themed_icon_uses_transparent_white_mask_inside_safe_circle(self):
        image = Image.open(spec.RENDER_DIR / 'rendered/monochrome.png').convert('RGBA')
        radius = image.width * 66 / 108 / 2
        center = image.width / 2
        occupied = 0
        for y in range(image.height):
            for x in range(image.width):
                red, green, blue, alpha = image.getpixel((x, y))
                if alpha > 16:
                    occupied += 1
                    self.assertEqual((255, 255, 255), (red, green, blue))
                    self.assertLessEqual((x + .5 - center)**2 + (y + .5 - center)**2, radius**2)
        self.assertGreater(occupied, image.width * image.height * .04)
        self.assertEqual(0, image.getpixel((0, 0))[3])

    def test_colored_and_monochrome_drawables_share_contours(self):
        foreground = ET.fromstring(spec.android_glyph_drawable())
        monochrome = ET.fromstring(spec.android_glyph_drawable(color='#FFFFFFFF'))
        self.assertEqual([p.attrib[ANDROID+'pathData'] for p in foreground], [p.attrib[ANDROID+'pathData'] for p in monochrome])
        self.assertTrue(all(p.attrib[ANDROID+'fillColor']=='@color/ic_launcher_glyph' for p in foreground))

    def test_all_supported_launcher_shapes_keep_themed_layer(self):
        for name in ['ic_launcher.xml', 'ic_launcher_round.xml']:
            icon = ET.parse(spec.ANDROID_RES / 'mipmap-anydpi-v33' / name).getroot()
            self.assertEqual('@drawable/ic_launcher_monochrome', icon.find('monochrome').attrib[ANDROID+'drawable'])
        for qualifier in ['values-v31', 'values-night-v31']:
            colors = ET.parse(spec.ANDROID_RES / qualifier / 'ic_launcher_colors.xml').getroot()
            self.assertTrue(all(c.text.startswith('@android:color/system_') for c in colors))

if __name__ == '__main__':
    unittest.main()
