"""The in-game panel's settings as defaults with a game's own value (OwnSetting.java with android/app/src/jvmtest's OwnSettingJvmTest): a game without its own value
takes Settings' default key by key, its own value wins, "Use Settings' default" removes only its keys; PictureFit's size choices. And the wiring: the in-game panel
writes the running game's own frame generation, overlay, shaders label and on-screen controls, the launcher passes the game's frame generation, and Settings and
Game settings have each panel setting. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
JAVA = REPO / 'android/app/src/main/java/dev/nxnative/android'
SRC = [JAVA / 'OwnSetting.java', JAVA / 'PictureFit.java']
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/OwnSettingJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class OwnSettingJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, SRC), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.OwnSettingJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


class Wiring(unittest.TestCase):
    def test_panel_writes_the_game(self):
        t = (JAVA / 'SidePanel.java').read_text(encoding='utf-8')
        self.assertIn('Lsfg.push(act, game()); refresh(); }, game());', t)      # frame generation: the running game's own
        self.assertIn('Overlay.rows(act, box, null, game());', t)               # the overlay
        self.assertIn('Prefs.layerLabel(act, game(), w);', t)                   # the shaders label
        c = (JAVA / 'TouchControls.java').read_text(encoding='utf-8')
        self.assertIn('if (game != null) mode(ctx, game, id - 4000);', c)      # the on-screen controls' mode
        self.assertIn('private void save() { look(getContext(), game, opacity, scale, haptics); }', c)

    def test_launch_reads_the_game(self):
        a = (JAVA / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('Lsfg.env(this, env, titleId());', a)
        self.assertIn('Prefs.overlay(this, titleId())', a)
        self.assertIn('TouchControls.showLog(this, titleId())', a)
        self.assertNotIn('Lsfg.on(this)', a)

    def test_settings_and_game_settings(self):
        m = (JAVA / 'MainActivity.java').read_text(encoding='utf-8')
        for s in ('perf.addView(lsfgRow.view);', 'Prefs.stretchDefault(', 'Prefs.pictureSizeDefault(', 'TouchControls.lookRows(this, box, null);',
                  'Prefs.motionSensDefault(', 'Prefs.pauseOnReturnDefault(', 'private void panelLook()', 'private void playerOne()'):
            self.assertIn(s, m)
        g = (JAVA / 'GameSettings.java').read_text(encoding='utf-8')
        for s in ('frameGen(a, g, again)', 'Prefs.pictureSizeOwn(', 'overlay(a, g, again)', 'Prefs.layerLabel(a, id, w < 0 ? null : w)', 'touchLook(a, g, again)',
                  'Prefs.pauseOnReturnOwn(', 'Prefs.stretchOwn(', 'Prefs.MOTION_NAMES[Prefs.motionSens(a)]'):
            self.assertIn(s, g)


if __name__ == '__main__':
    unittest.main()
