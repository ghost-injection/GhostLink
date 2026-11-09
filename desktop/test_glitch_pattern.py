import random
import tempfile
import unittest
from pathlib import Path
from glitch_pattern import GlitchPattern
import life_theme

class GlitchTests(unittest.TestCase):
    def test_bounds_lifetime_and_effects_off(self):
        p = GlitchPattern(random.Random(19))
        for _ in range(20):
            p.burst(100)
        self.assertEqual(len(p.blocks), 32)
        for b in p.blocks:
            self.assertGreaterEqual(b['x'], 0)
            self.assertLessEqual(b['x'] + b['w'], 1)
            self.assertLessEqual(b['y'] + b['h'], 1)
        p.step(2, ambient=False)
        self.assertEqual(p.blocks, [])
        p.burst()
        p.step(.1, enabled=False)
        self.assertEqual(p.blocks, [])

    def test_fifth_theme_persists(self):
        original = life_theme.PATH
        try:
            with tempfile.TemporaryDirectory() as folder:
                life_theme.PATH = Path(folder) / 'appearance.json'
                life_theme.save(4, False)
                self.assertEqual(life_theme.read(), (4, False))
                self.assertEqual(len(life_theme.NAMES), 5)
                self.assertNotEqual(life_theme.color(4), life_theme.color(0))
        finally:
            life_theme.PATH = original
