"""Frame generation's choices the screen can show (PanelRates.java with android/app/src/jvmtest's PanelRatesJvmTest; Lsfg's rows): the fastest refresh is the main screen's
fastest display mode held to Android's peak refresh setting (the AYN Thor: 60 and 120 Hz modes, a 60 Hz setting), the multipliers offered are those whose output at 30 fps
fits it, the target rates those at most it that a mode shows evenly, and a saved choice the screen cannot show is used as the nearest offered one. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
J = REPO / 'android/app/src'
SRC = J / 'main/java/dev/nxnative/android/PanelRates.java'
TEST = J / 'jvmtest/java/dev/nxnative/android/PanelRatesJvmTest.java'
LSFG = J / 'main/java/dev/nxnative/android/Lsfg.java'


class Wiring(unittest.TestCase):
    def test_lsfg_offers_and_uses_what_the_screen_shows(self):
        t = LSFG.read_text(encoding='utf-8')
        self.assertIn('PanelRates.clamp(num(c, "lsfg_mult", titleId, 2), multipliers(c))', t)   # the env and the in-game push take the held value (the game's own, else the default)
        self.assertIn('PanelRates.clamp(num(c, "lsfg_target", titleId, 0), targets(c))', t)
        self.assertIn('choice(c, tv, target(c, titleId), tl', t)
        self.assertIn('choice(c, mv, multiplier(c, titleId), ml', t)
        self.assertIn('"peak_refresh_rate"', t)
        self.assertNotIn('TARGETS = {0, 60, 90, 120}', t)

    def test_follows_the_screen_while_the_game_runs(self):
        t = LSFG.read_text(encoding='utf-8')
        self.assertIn('Settings.System.getUriFor("peak_refresh_rate")', t)   # Android's refresh setting changed with the game running (the AYN Thor's 60 -> 120 Hz)
        self.assertIn('registerDisplayListener', t)
        self.assertIn('if (m == last[0] && t == last[1]) return;', t)   # the host gets the settings again only when what is in use changes
        g = (J / 'main/java/dev/nxnative/android/GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('lsfgFollow = Lsfg.follow(this, ui, titleId())', g)
        self.assertIn('lsfgFollow.run(); lsfgFollow = null;', g)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Rates(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='panelrates_') as d:
            subprocess.run(['javac', '-d', d, str(SRC), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.PanelRatesJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
