"""The launcher's global Settings page keeps the truly global rows and the input rows at top level and folds the defaults every game inherits into one closed
"Defaults for all games" group; each input row also has a per-game twin that resolves game value, else the main screen's, else the built-in one.
Source checks (the look and the pad focus are checked on the phone)."""
import re
import unittest
import sys as _sys
from pathlib import Path as _Path
_sys.path.insert(0, str(_Path(__file__).resolve().parent))   # the tests folder, for javatext (unittest by file name does not add it)
import javatext  # noqa: E402
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'


def settings_body():
    t = (SRC / 'MainActivity.java').read_text(encoding='utf-8')
    return t[t.index('private void buildSettings('):t.index('private LinearLayout defaultsGroup(')]


class Grouping(unittest.TestCase):
    def test_defaults_group(self):
        b = settings_body()
        self.assertIn('LinearLayout perf = defaultsGroup(content);', b)
        # rows added to the group: only the defaults a game's page can override (the in-game panel's settings among them: frame generation, Display, Picture size, the
        # on-screen controls' look, Motion sensitivity, Pause on return); the input rows that were per game before stay in Controls
        in_group = set(re.findall(r'perf\.addView\(new Ui\.Setting\(this, "[^"]+", "([^"]+)"', b))
        in_group |= set(re.findall(r'perf\.addView\((\w+)Row\.view', b))
        self.assertEqual(in_group, {'Use fast cores', 'res', 'cap', 'ups', 'sharp', 'drs', 'lsfg', 'disp', 'size', 'look', 'motion', 'Pause on return'})

    def test_top_level_rows(self):
        b = settings_body()
        for row in ('"Check for updates"', '"Player wiki"', '"Folders"', '"Storage"', '"Keep the screen on while preparing"', '"GPU driver"', '"Advanced"',
                    '"Readiness check"', '"Graphics self-test"', '"Share log"', '"Tester settings"', '"ADB control (debug)"', '"Time of day"', '"Frame generation"'):
            self.assertIn(row, b)
        for row in ('ctl.addView(new Ui.Setting(this, "📳", "Rumble"', 'ctl.addView(xbox.view)', 'ctl.addView(padMap.view)', 'ctl.addView(touch.view)', 'ctl.addView(padPanel.view)', 'play.addView(rot.view)'):
            self.assertIn(row, b)

    def test_group_is_closed_and_focusable(self):
        t = (SRC / 'MainActivity.java').read_text(encoding='utf-8')
        g = t[t.index('private LinearLayout defaultsGroup('):]
        g = g[:g.index('private LinearLayout settingsCard')]
        self.assertIn('Defaults for all games', g)
        self.assertIn("Each game's page can override these.", g)
        self.assertIn('head.setFocusable(true)', g)
        self.assertIn('card.setVisibility(defaultsOpen ? View.VISIBLE : View.GONE)', g)   # views exist while closed
        self.assertRegex(t, r'private boolean defaultsOpen;')


class PerGameInput(unittest.TestCase):
    def test_resolve_order(self):
        p = (SRC / 'Prefs.java').read_text(encoding='utf-8')
        self.assertIn('getBoolean(gameKey("rumble", titleId), rumble(c))', p)
        self.assertIn('getBoolean(gameKey("rotation_auto", titleId), rotationAuto(c))', p)
        self.assertIn('getInt(gameKey("panel_pad", titleId), panelPad(c))', p)
        self.assertIn('getBoolean("rotation_auto", false)', p)   # built-in
        tc = (SRC / 'TouchControls.java').read_text(encoding='utf-8')
        self.assertIn('getInt(Prefs.gameKey("mode", titleId), mode(c))', tc)
        self.assertIn('getInt("mode", MODE_AUTO)', tc)
        # a stored key of the main screen is untouched
        for key in ('"rumble"', '"rotation_auto"', '"panel_pad"', '"xbox_buttons"'): self.assertIn(key, p)

    def test_game_start_reads_game_value(self):
        a = (SRC / 'GameActivity.java').read_text(encoding='utf-8')
        for c in ('Prefs.panelPad(this, id)', 'TouchControls.mode(this, id)', 'Prefs.rotationAuto(this, titleId())', 'Prefs.rumble(', 'Prefs.xboxButtons(this, id)', 'Prefs.padMap(this, id)'):
            if c.startswith('Prefs.rumble'): continue
            self.assertIn(c, a)

    def test_game_page_rows(self):
        g = (SRC / 'GameSettings.java').read_text(encoding='utf-8')
        for title in ('Rumble', 'On-screen controls', 'Screen rotation', 'Controller panel'):
            self.assertRegex(g, r'controls\.add\(new Ui\.Setting\(a, "[^"]+", "%s"' % re.escape(title))
        self.assertIn('Default (Settings: %1$s)', g)
        self.assertIn('As in Settings: %1$s', g)


def setUpModule():
    javatext.install()   # the launcher text is in strings.xml: this module reads the Java source with its English


def tearDownModule():
    javatext.uninstall()


if __name__ == '__main__':
    unittest.main()
