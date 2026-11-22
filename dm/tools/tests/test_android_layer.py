"""The shader layer's app side (android/SHADERS.md, Shaders the library lacks): LayerPending counts the requests the packer has not settled (the host's req/<key>.ctl files minus
built.tsv's keys; a failure settles for its own tools version only) and words the "Building shaders (N)" indicator; LayerGuard's safeguards (requests the prepared library has
are dropped, the quota, heat and battery, workers) (JVM part, skipped without a JDK); the host, the watcher, the service, the player's switch and the game page are wired."""
import os
import struct
import shutil
import subprocess
import tempfile
import unittest
import sys as _sys
from pathlib import Path as _Path
_sys.path.insert(0, str(_Path(__file__).resolve().parent))   # the tests folder, for javatext (unittest by file name does not add it)
import javatext  # noqa: E402
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
WORDS = REPO / 'android/app/src/main/java/dev/nxnative/android/Words.java'   # the pure classes' text (Words), compiled with them
J = REPO / 'android/app/src'
SRC = [J / f'main/java/dev/nxnative/android/{n}.java' for n in ('LayerPending', 'LayerDir', 'LayerGuard', 'LayerStatus', 'ShaderLibs', 'LayerKept')] + [J / 'ondevice/java/dev/nxnative/android/PackLock.java']
TEST = J / 'jvmtest/java/dev/nxnative/android/LayerPendingJvmTest.java'


class Wiring(unittest.TestCase):
    def test_wired(self):
        self.assertIn('DM_VK_LAYER\t', (REPO / 'docs/env_switches.tsv').read_text(encoding='utf-8'))
        sh = (REPO / 'dm/gfx/vk_android/vka_shader.c').read_text(encoding='utf-8')
        self.assertIn('vka_layer_poll', (REPO / 'dm/gfx/vk_android/vka_present.c').read_text(encoding='utf-8'))
        self.assertIn('vka_layer_gen', (REPO / 'dm/gfx/vk_android/vka_draw.c').read_text(encoding='utf-8'))
        self.assertIn('layer_request(p, LY)', sh)
        self.assertIn('vka_shlib_extract(own, LY', sh)   # a layer's shader keeps a copy of its entry, so a replaced layer can be unmapped
        self.assertIn('vka_ly_set(&g_ly', sh)
        self.assertIn('.unwritten = req_unwritten', sh)   # a request the writer could not write is unmarked and asked again
        self.assertIn('DM_VK_LAYER_DIR\t', (REPO / 'docs/env_switches.tsv').read_text(encoding='utf-8'))
        self.assertIn('vka_shlib_empty(&g_lib', sh)   # a pack with no library plays with an empty one when the layer is on
        self.assertIn('vka_layer_root_of(root, sizeof root, getenv("DM_VK_LAYER_DIR")', sh)
        self.assertIn('LayerService', (J / 'ondevice/AndroidManifest.xml').read_text(encoding='utf-8'))
        self.assertIn('shader_layer.py', (J / 'ondevice/java/dev/nxnative/android/LayerService.java').read_text(encoding='utf-8'))
        ga = (J / 'main/java/dev/nxnative/android/GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('new LayerWatch', ga)
        self.assertIn('"DM_VK_LAYER_DIR="', ga)   # the layer's folder in the pack (LayerDir), told to the host
        self.assertIn('LayerDir.resolve', ga)
        svc = (J / 'ondevice/java/dev/nxnative/android/LayerService.java').read_text(encoding='utf-8')
        self.assertIn('PackLock.tryLayer()', svc); self.assertIn('PackLock.endLayer()', svc); self.assertIn('PackLock.layerStopAsked()', svc)   # one lock over the packer's Python
        self.assertIn('LayerPending.BEAT_MS', svc); self.assertIn('LayerPending.lastTiming(log)', svc)   # the marker is touched while the build runs
        ps = (J / 'ondevice/java/dev/nxnative/android/PackService.java').read_text(encoding='utf-8')
        self.assertIn('PackLock.beginJob()', ps); self.assertIn('PackLock.endJob()', ps); self.assertIn('waitForLayer(j, Job.STEPS[i])', ps); self.assertIn('PackLock.WAITING', ps)
        gs = (J / 'main/java/dev/nxnative/android/GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('LayerGuard.full(layer', gs); self.assertIn('LayerStatus.storageText(', gs)   # a layer past its quota says so on the game's page
        self.assertIn('LayerGuard.noteFull(root', (J / 'main/java/dev/nxnative/android/LayerWatch.java').read_text(encoding='utf-8'))

    def test_safeguards_wired(self):
        main = J / 'main/java/dev/nxnative/android'
        ga, svc = (main / 'GameActivity.java').read_text(encoding='utf-8'), (J / 'ondevice/java/dev/nxnative/android/LayerService.java').read_text(encoding='utf-8')
        self.assertIn('"DM_VK_LAYER=0"', ga)   # Shaders while playing off
        self.assertIn('if (!runLayer) return;', ga)   # no watcher then: the switch as the host got it at the start (the in-game panel's switch is for the next start)
        self.assertIn('runLayer ? LayerGuard.dropCovered(layerRoot, hostLib, id) : 0', ga); self.assertIn('ShaderLibs.hostLibrary(', ga)
        sp = (main / 'SidePanel.java').read_text(encoding='utf-8')
        self.assertIn('Advanced › Shaders while playing', sp); self.assertNotIn('CheckBox', sp[sp.index('private void shaderRows'):sp.index('private void general')]); self.assertIn('layerShown()', sp)
        self.assertIn('new LayerWatch(this, titleId(), root,', ga)   # the layer folder itself (LayerWatch no longer adds shader_layer to it)
        self.assertIn('"--max-bytes"', svc); self.assertIn('LayerWatch.pauseReason(this, ', svc); self.assertIn('LayerPending.markerText(', svc); self.assertIn('LayerPending.BUILDING', svc)
        self.assertIn('Integer.toString(procs)', svc)
        for f in ('GameSettings', 'MainActivity', 'Folders', 'PackUi', 'FrontendLaunch'):   # the rules that decide Play read the switch
            t = (main / f'{f}.java').read_text(encoding='utf-8')
            self.assertNotRegex(t, r'(shadersOnDemand|layerCovers|libraryBlock|shaderUpdateOptional)\([^;]*Prefs\.gameEnv\(', f)
        gs = (main / 'GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('"Shaders while playing"', gs); self.assertIn('"Import a library"', gs); self.assertIn('R.plurals.game_n_built_while_you_play', gs); self.assertIn('Prefs.layerBuild(a, g.titleId, !on)', gs)   # the one small row and its dialog
        self.assertIn('Prefs.layerExplained', (main / 'MainActivity.java').read_text(encoding='utf-8'))
        lw = (main / 'LayerWatch.java').read_text(encoding='utf-8')
        self.assertIn('RETRY_MS = 5000', lw); self.assertIn('MAX_EMPTY_RUNS = 5', lw)
        adv = [l.split('\t') for l in (REPO / 'docs/advanced_settings.tsv').read_text(encoding='utf-8').splitlines() if l.startswith('DM_VK_LAYER\t')]
        self.assertEqual(adv[0][1], 'advanced'); self.assertEqual(adv[0][10], 'Game settings > Shaders while playing')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Pending(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='layerpending_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.tmp / 'classes'), str(WORDS), *map(str, SRC), str(TEST)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *a):
        r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.LayerPendingJvmTest', *map(str, a)], capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.rstrip('\n').split(' ', 1)[1]

    def test_pending(self):
        d = Path(tempfile.mkdtemp(prefix='layerroot_'))
        try:
            self.assertEqual(self.java('pending', d), '0 ')   # no request folder
            (d / 'req').mkdir()
            for k in ('1111111111111111', '2222222222222222', '3333333333333333'): (d / 'req' / f'{k}.ctl').write_bytes(b'x'); (d / 'req' / f'{k}.code').write_bytes(b'y')
            (d / 'req' / 'pairs.tsv').write_text('x')
            self.assertEqual(self.java('pending', d), '3 1111111111111111,2222222222222222,3333333333333333')
            (d / 'built.tsv').write_text('1111111111111111\tok\n3333333333333333\tfail\nbad line\n')
            self.assertEqual(self.java('pending', d), '1 2222222222222222')
            # each version's own list (lib/v-<V>/built.tsv): another version's rows settle nothing here, and the shared list counts only its rows of this version
            self.assertEqual(self.java('pending', d, 'aaaaaaaaaaaa'), '3 1111111111111111,2222222222222222,3333333333333333')
            (d / 'lib/v-aaaaaaaaaaaa').mkdir(parents=True); (d / 'lib/v-aaaaaaaaaaaa/built.tsv').write_text('2222222222222222\tok\taaaaaaaaaaaa\n')
            (d / 'lib/v-bbbbbbbbbbbb').mkdir(parents=True); (d / 'lib/v-bbbbbbbbbbbb/built.tsv').write_text('1111111111111111\tok\tbbbbbbbbbbbb\n3333333333333333\tok\tbbbbbbbbbbbb\n')
            self.assertEqual(self.java('pending', d, 'aaaaaaaaaaaa'), '2 1111111111111111,3333333333333333')
            self.assertEqual(self.java('pending', d, 'bbbbbbbbbbbb'), '1 2222222222222222')
            self.assertEqual(self.java('built', d, 'aaaaaaaaaaaa'), '1'); self.assertEqual(self.java('built', d, 'bbbbbbbbbbbb'), '2')
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_layer_folder_in_pack(self):
        d = Path(tempfile.mkdtemp(prefix='layerdir_'))
        try:
            pack, temp = d / 'pack', d / 'temp'
            pack.mkdir(); temp.mkdir()
            self.assertEqual(self.java('resolve', pack, temp), f'{pack}/shaders/layer in-pack')
            self.assertTrue((pack / 'shaders/layer').is_dir())
            # an older layer folder in the game's temporary folder moves into an empty pack one
            (temp / 'shader_layer/req').mkdir(parents=True); (temp / 'shader_layer/req/1111111111111111.ctl').write_bytes(b'x')
            self.assertEqual(self.java('resolve', pack, temp), f'{pack}/shaders/layer in-pack')
            self.assertTrue((pack / 'shaders/layer/req/1111111111111111.ctl').is_file())
            self.assertFalse((temp / 'shader_layer').exists())
            # one the pack already has wins: the older one is left alone
            (temp / 'shader_layer').mkdir()
            (temp / 'shader_layer/x').write_bytes(b'x')
            self.java('resolve', pack, temp)
            self.assertTrue((temp / 'shader_layer/x').is_file())
            self.assertTrue((pack / 'shaders/layer/req/1111111111111111.ctl').is_file())
            # a pack that cannot take it: the temporary folder, as before
            ro = d / 'ro'; (ro / 'shaders').mkdir(parents=True); (ro / 'shaders').chmod(0o500)
            try:
                if not os.access(ro / 'shaders', os.W_OK):
                    self.assertEqual(self.java('resolve', ro, temp), f'{temp}/shader_layer temp')
            finally: (ro / 'shaders').chmod(0o700)
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_tidy_versions(self):
        d = Path(tempfile.mkdtemp(prefix='layertidy_'))
        g = 1 << 30
        try:
            self.assertEqual(self.java('tidy', d, 'aaaaaaaaaaaa'), '')   # no libraries
            other = d / 'lib/v-bbbbbbbbbbbb'; (other / 'results').mkdir(parents=True); (other / 'dmsh_vk.lib').write_bytes(b'old'); (other / 'results/k.r').write_bytes(b'r')
            (other / 'built.tsv').write_text('1111111111111111\tok\tbbbbbbbbbbbb\n')
            mine = d / 'lib/v-aaaaaaaaaaaa'; mine.mkdir(); (mine / 'dmsh_vk.lib').write_bytes(b'new')
            # another version's layer (an older APK's, or the other app's sharing the pack folder) touched lately, the folder within the quota: kept whole
            self.assertEqual(self.java('tidy', d, 'aaaaaaaaaaaa', 100 * g, int(__import__('time').time() * 1000)), '')
            self.assertTrue((other / 'dmsh_vk.lib').is_file()); self.assertTrue((other / 'built.tsv').is_file()); self.assertTrue((other / 'results/k.r').is_file())
            # untouched for more than 30 days: removed whole
            later = int(__import__('time').time() * 1000) + 31 * 24 * 3600 * 1000
            self.assertIn("removed 1 other version's layer, unused for 30 days", self.java('tidy', d, 'aaaaaaaaaaaa', 100 * g, later))
            self.assertFalse(other.exists()); self.assertTrue((mine / 'dmsh_vk.lib').is_file())
            # the whole folder past this version's quota (no space left on the volume): other versions go, the oldest first; this version's never
            for v, t in (('v-cccccccccccc', 100), ('v-dddddddddddd', 200)):
                (d / 'lib' / v).mkdir(); (d / 'lib' / v / 'dmsh_vk.lib').write_bytes(b'x' * 10); os.utime(d / 'lib' / v / 'dmsh_vk.lib', (t, t)); os.utime(d / 'lib' / v, (t, t))
            now = 300 * 1000
            self.assertIn('past its quota', self.java('tidy', d, 'aaaaaaaaaaaa', 0, now))
            self.assertEqual(sorted(p.name for p in (d / 'lib').iterdir()), ['v-aaaaaaaaaaaa'])
            # a build runs (its marker is fresh): nothing is touched
            (d / 'lib/v-eeeeeeeeeeee').mkdir(); (d / '.building').write_bytes(b'')
            self.assertEqual(self.java('tidy', d, 'aaaaaaaaaaaa', 0, int((d / '.building').stat().st_mtime * 1000) + 1000), '')
            self.assertTrue((d / 'lib/v-eeeeeeeeeeee').is_dir())
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_text(self):
        self.assertEqual(self.java('text', 4), 'Building shaders (4)')

    def test_label(self):
        self.assertEqual(self.java('label', 0, '-', 'true'), '-')   # settled: the label clears, never "0"
        self.assertEqual(self.java('label', 0, 'battery low', 'true'), '-')
        self.assertEqual(self.java('label', 7, '-', 'false'), 'Starting shader build (7)')   # requests, no run asked for yet
        self.assertEqual(self.java('label', 7, '-', 'true'), 'Building shaders (7)')
        self.assertEqual(self.java('label', 7, 'battery low', 'true'), 'Shader build paused (battery low) \u00b7 7 waiting')
        self.assertEqual(self.java('label', 3, 'storage', 'false'), 'Shader build paused (storage) \u00b7 3 waiting')

    def test_label_choices(self):   # Off, Number only, Full text: the paused message is said in all three, the build is not the label's business
        self.assertEqual(self.java('shown', 0, 635, '-', 'true', 'true', '-'), '-')   # Off: hidden
        self.assertEqual(self.java('shown', 1, 635, '-', 'true', 'true', '-'), '635')   # Number only
        self.assertEqual(self.java('shown', 1, 635, '-', 'true', 'true', 'phone is warm: 1 worker'), '635')   # warm and building: the count alone
        self.assertEqual(self.java('shown', 2, 635, '-', 'true', 'true', '-'), 'Building shaders (635)')
        for mode in (0, 1, 2):   # a build that waits for the phone (hot, battery, storage) is always said
            self.assertEqual(self.java('shown', mode, 635, 'battery low', 'true', 'false', '-'), 'Shader build paused (battery low) \u00b7 635 waiting', mode)
            self.assertEqual(self.java('shown', mode, 3, 'storage', 'true', 'false', '-'), 'Shader build paused (storage) \u00b7 3 waiting', mode)
            self.assertEqual(self.java('shown', mode, 0, '-', 'true', 'true', '-'), '-', mode)   # nothing pending: nothing, never 0

    def test_label_off_still_builds(self):   # the label is what is drawn; whether shaders are built never reads it
        for mode in (0, 1, 2):
            shown = self.java('shown', mode, 40, '-', 'true', 'false', '-')
            self.assertEqual(shown, {0: '-', 1: '40', 2: 'Building shaders (40)'}[mode])
            self.assertEqual(self.java('due', 40, 'false', 'false', '-', 'false', 100000, 0, 5000), 'true', mode)   # the same decision under every label
        # what does stop a build: nothing waiting, given up, the layer full, a low battery, a run in progress, the wait since the last ask; heat is not on the list
        self.assertEqual(self.java('due', 0, 'false', 'false', '-', 'false', 100000, 0, 5000), 'false')
        self.assertEqual(self.java('due', 40, 'true', 'false', '-', 'false', 100000, 0, 5000), 'false')
        self.assertEqual(self.java('due', 40, 'false', 'true', '-', 'false', 100000, 0, 5000), 'false')
        self.assertEqual(self.java('due', 40, 'false', 'false', 'battery low', 'false', 100000, 0, 5000), 'false')
        self.assertEqual(self.java('due', 40, 'false', 'false', '-', 'true', 100000, 0, 5000), 'false')
        self.assertEqual(self.java('due', 40, 'false', 'false', '-', 'false', 3000, 0, 5000), 'false')
        for st in (2, 3, 4, 5): self.assertIn('- | ', self.java('pace', st, 1.2, 80, 'false', 40, 0))   # no pause under any heat

    def test_label_wired(self):   # Off only hides the label: the watcher still builds; the label is at the very top and see-through
        main = J / 'main/java/dev/nxnative/android'
        lw, ga = (main / 'LayerWatch.java').read_text(encoding='utf-8'), (main / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('String label = gaveUp ? null : LayerStatus.shown(Prefs.layerLabel(ctx, titleId), n,', lw); self.assertIn('shown.show(label)', lw)   # computed on the work thread, shown on the main thread
        self.assertIn('new HandlerThread("nxlayer-watch")', lw, 'the tick reads the request folder off the main thread (it delivers the controller input)')
        self.assertLess(lw.index('LayerStatus.shown('), lw.index('if (LayerStatus.buildDue('), 'the label is decided before, and apart from, the build condition')
        self.assertNotIn('LayerStatus.shown', ga.split('private void layerShow')[0])
        show = ga.split('private void layerShow(String text)')[1].split('private void skipShow')[0]
        self.assertIn('lp.topMargin = 0;', show); self.assertIn('bg.setColor(0x33000000)', show)
        self.assertNotIn('overlayItem', show)
        self.assertIn('Prefs.layerLabel(act, game(), w)', (main / 'SidePanel.java').read_text(encoding='utf-8'))   # the running game's own label
        self.assertIn('Prefs.layerLabel(this, w)', (main / 'MainActivity.java').read_text(encoding='utf-8'))

    def test_label_honest(self):
        # "Building shaders" only while the packer builds; between runs that settled nothing the label says it waits and when it tries again
        self.assertEqual(self.java('label2', 635, '-', 'true', 'true', 0, '-'), 'Building shaders (635)')
        self.assertEqual(self.java('label2', 635, '-', 'true', 'false', 0, '-'), 'Building shaders (635)')   # (the few seconds between two runs that settled some)
        self.assertEqual(self.java('label2', 635, '-', 'true', 'false', 24, '-'), 'Shader build waiting (635) \u00b7 next try in 24 s')
        self.assertEqual(self.java('label2', 635, '-', 'true', 'true', 24, '-'), 'Building shaders (635)')   # a run that builds is never "waiting"
        self.assertEqual(self.java('label2', 9, '-', 'false', 'false', 0, '-'), 'Starting shader build (9)')
        self.assertEqual(self.java('label2', 635, '-', 'true', 'true', 0, 'phone is warm: 1 worker'), 'Building shaders (635) \u00b7 phone is warm: 1 worker')
        self.assertEqual(self.java('label2', 635, 'battery low', 'true', 'false', 0, 'phone is warm: 1 worker'), 'Shader build paused (battery low) \u00b7 635 waiting')

    def test_pace(self):
        W, H = 'phone is warm', 'phone is hot: 1 worker'
        # heat never pauses: status 2 runs with one worker and the warm note (the hot note at a headroom at its limit), whatever the pending count or the game's age
        self.assertEqual(self.java('pace', 2, 'NaN', 80, 'false', 20, 5 * 60000), f'- | {W}')
        self.assertEqual(self.java('pace', 2, 'NaN', 80, 'false', 20, 30 * 60000), f'- | {W}')
        self.assertEqual(self.java('pace', 2, 0.8, 80, 'false', 1000, 30 * 60000), f'- | {W}')
        self.assertEqual(self.java('pace', 2, 'NaN', 80, 'false', 20, 10 ** 18), f'- | {W}')
        self.assertEqual(self.java('pace', 2, 1.0, 80, 'false', 1000, 0), f'- | {H}')
        # status 3 and above, a game in front or not: one worker and the hot note, never no worker (at critical Android itself may close the app: the next start goes on from built.tsv)
        for st in (3, 4, 5, 6):
            for up in (0, 30 * 60000, 9223372036854775807):
                self.assertEqual(self.java('pace', st, 'NaN', 80, 'false', 1000, up), f'- | {H}', (st, up))
        # a low battery that does not charge still waits (nothing to do with heat)
        self.assertEqual(self.java('pace', 2, 'NaN', 10, 'false', 1000, 0), 'Shader build paused (battery low) | null')
        self.assertEqual(self.java('pace', 2, 'NaN', 10, 'true', 1000, 0), f'- | {W}')
        self.assertEqual(self.java('pace', 1, 'NaN', 80, 'false', 0, 10 ** 9), '- | null')
        for st in (2, 3, 4, 5): self.assertEqual(self.java('procs', 8, st, 1000, 1.5 if st == 2 else 'NaN'), '1', st)   # hot: one worker, not zero
        self.assertEqual(self.java('procs', 8, 2, 1000), '3')   # (status 2 keeps every worker while many wait and the headroom is unknown or under 0.85)
        self.assertEqual(self.java('procs', 8, 2, 1000, 0.9), '1'); self.assertEqual(self.java('procs', 8, 2, 1000, 0.84), '3'); self.assertEqual(self.java('procs', 8, 3, 1000), '1'); self.assertEqual(self.java('procs', 8, 4, 1000), '1')

    def test_last_timing(self):
        d = Path(tempfile.mkdtemp(prefix='layertiming_'))
        try:
            self.assertEqual(self.java('timing', d / 'none.log'), '-')
            (d / 'layer.log').write_text('progress 1 2 translate\nshader_layer timing: 5 shaders in 2.0 s\nother\nshader_layer timing: 9 shaders in 3.0 s, setup 1.0 s\nexit\n')
            self.assertEqual(self.java('timing', d / 'layer.log'), 'shader_layer timing: 9 shaders in 3.0 s, setup 1.0 s')   # the last one: logcat gets one line per run
            (d / 'big.log').write_text('shader_layer timing: old\n' + 'x' * 70000 + '\n'); self.assertEqual(self.java('timing', d / 'big.log'), '-')
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_stale_marker(self):
        d = Path(tempfile.mkdtemp(prefix='layerstale_'))
        try:
            (d / '.building').write_text('4242\tdev.nxnative.android\n')
            now = int((d / '.building').stat().st_mtime * 1000) + 1000
            # this app's marker with a process that is gone (force-stop): not building at once, however fresh the marker is
            self.assertEqual(self.java('building-own', d, now, 'dev.nxnative.android', 'dead'), 'false')
            self.assertEqual(self.java('building-own', d, now, 'dev.nxnative.android', 'alive'), 'true')
            # an other app's marker (its pid is not ours to see): the time rule alone
            self.assertEqual(self.java('building-own', d, now, 'dev.nxnative.android.play', 'dead'), 'true')
            self.assertEqual(self.java('building-own', d, now + 4 * 60000, 'dev.nxnative.android', 'alive'), 'false')   # (a live pid with a stale marker is not a build)
            self.assertEqual(self.java('clear-stale', d, now, 'dev.nxnative.android', 'alive'), 'false'); self.assertTrue((d / '.building').exists())
            self.assertEqual(self.java('clear-stale', d, now, 'dev.nxnative.android', 'dead'), 'true'); self.assertFalse((d / '.building').exists())
            (d / '.building').write_bytes(b'')   # an older marker without a pid: the time rule
            self.assertEqual(self.java('building-own', d, int((d / '.building').stat().st_mtime * 1000) + 1000, 'dev.nxnative.android', 'dead'), 'true')
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_panel_line(self):
        self.assertEqual(self.java('line', 'true', 'true', 0, 0, '-', 'false'), 'All shaders ready')
        self.assertEqual(self.java('line', 'true', 'false', 1234, 0, '-', 'false'), 'Shaders: built while playing \u00b7 1,234 built \u00b7 none waiting')
        self.assertEqual(self.java('line', 'true', 'false', 12, 5, '-', 'false'), 'Shaders: built while playing \u00b7 12 built \u00b7 5 waiting')
        self.assertEqual(self.java('line', 'true', 'true', 0, 5, '-', 'false'), 'Shaders: built while playing \u00b7 5 waiting')
        self.assertEqual(self.java('line', 'true', 'false', 12, 5, 'battery low', 'false'), 'Shaders: built while playing \u00b7 12 built \u00b7 5 waiting \u00b7 paused (battery low)')
        self.assertEqual(self.java('line', 'true', 'false', 12, 5, 'battery low', 'true'), 'Shaders: built while playing \u00b7 12 built \u00b7 5 waiting \u00b7 stopped (the shader builder could not run)')
        self.assertIn('off for this run', self.java('line', 'false', 'false', 0, 0, '-', 'false'))

    def test_failures_once_per_version(self):
        d = Path(tempfile.mkdtemp(prefix='layerfail_'))
        try:
            (d / 'req').mkdir()
            for k in ('1111111111111111', '2222222222222222', '3333333333333333', '4444444444444444'): (d / 'req' / f'{k}.ctl').write_bytes(b'x')
            (d / 'built.tsv').write_text('1111111111111111\tok\tbbbbbbbbbbbb\n2222222222222222\tfail\taaaaaaaaaaaa\n3333333333333333\tfail\tbbbbbbbbbbbb\n4444444444444444\tfail\n')
            # the older shared list: only this version's rows count. Its failure settles; another version's row (ok or failed, or one without a version) does not:
            # that shader is not in this version's library, so it is built (a failure tried once more)
            self.assertEqual(self.java('pending', d, 'aaaaaaaaaaaa'), '3 1111111111111111,3333333333333333,4444444444444444')
            self.assertEqual(self.java('settled', d, 'aaaaaaaaaaaa'), '1')
            # the version's own list: every row of it is that version's
            (d / 'lib/v-aaaaaaaaaaaa').mkdir(parents=True); (d / 'lib/v-aaaaaaaaaaaa/built.tsv').write_text('1111111111111111\tok\taaaaaaaaaaaa\n2222222222222222\tfail\taaaaaaaaaaaa\n')
            self.assertEqual(self.java('pending', d, 'aaaaaaaaaaaa'), '2 3333333333333333,4444444444444444')
            self.assertEqual(self.java('settled', d, 'aaaaaaaaaaaa'), '2')
            self.assertEqual(self.java('pending', d), '0 ')   # no version: every row settles (the older rule)
            self.assertEqual(self.java('built', d), '1')
            self.assertEqual(self.java('building', d, 10 ** 13), 'false')
            (d / '.building').write_bytes(b'')
            now = int((d / '.building').stat().st_mtime * 1000)
            self.assertEqual(self.java('building', d, now + 1000), 'true')
            self.assertEqual(self.java('building', d, now + 2 * 60 * 1000), 'true')   # (the service touches it every 30 s while it builds)
            self.assertEqual(self.java('building', d, now + 4 * 60 * 1000), 'false')   # a packer killed mid-build leaves the marker: it stops counting within minutes
        finally: shutil.rmtree(d, ignore_errors=True)

    @staticmethod
    def library(path, game, keys):
        """A dmsh_vk.lib with only its header and its entries (sorted by key as a number; the key little-endian at each entry's start)"""
        ks = sorted(int(k, 16) for k in keys)
        h = bytearray(128); struct.pack_into('<II', h, 0, 0x4B56534D, 1); h[40:56] = game.encode().ljust(16, b'\0'); struct.pack_into('<II', h, 72, len(ks), 128)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(bytes(h) + b''.join(struct.pack('<Q', k) + bytes(56) for k in ks))

    def test_drop_covered(self):
        d = Path(tempfile.mkdtemp(prefix='layerdrop_'))
        try:
            game = '040074A01BF12000'
            r = d / 'layer/req'; r.mkdir(parents=True)
            keys = ['00000000000000a1', '00000000000000b2', 'f0000000000000c3', 'f0000000000000d4', '00000000000000e5']
            for k in keys: (r / f'{k}.ctl').write_bytes(b'x'); (r / f'{k}.code').write_bytes(b'y')
            # the library has a1, b2, c3, d4 (c3 and d4 above 2^63: unsigned order) and not e5; d4 is e5's program's vertex shader: it stays
            (r / 'pairs.tsv').write_text('00000000000000a1\t00000000000000b2\nf0000000000000d4\t00000000000000e5\nshort\n')
            lib = d / 'pack/shaders/v-x/dmsh_vk.lib'
            self.library(lib, game, ['00000000000000a1', '00000000000000b2', 'f0000000000000c3', 'f0000000000000d4', '0000000000000001', 'ffffffffffffffff'])
            self.assertEqual(self.java('drop', d / 'layer', lib, '0400C3F00006E000'), '0')   # another game's library: nothing
            self.assertEqual(self.java('drop', d / 'layer', lib, game), '3')
            self.assertEqual(sorted(p.name for p in r.glob('*.ctl')), ['00000000000000e5.ctl', 'f0000000000000d4.ctl'])
            self.assertFalse((r / '00000000000000a1.code').exists()); self.assertTrue((r / 'pairs.tsv').is_file())
            self.assertEqual(self.java('drop', d / 'layer', d / 'none.lib', game), '0')   # no library
            (d / 'cut.lib').write_bytes(lib.read_bytes()[:150])
            self.assertEqual(self.java('drop', d / 'layer', d / 'cut.lib', game), '0')   # a library cut short is not read
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_kept_library(self):
        """A library a sample kept (its folder holds sampled-keep.json): its requests are not dropped at the start, and the ones that only verify its entries are told from the missing
        ones: the library has the key, the layer has not built it, and no request paired with it is one the library lacks"""
        d = Path(tempfile.mkdtemp(prefix='layerkept_'))
        try:
            game = '040074A01BF12000'; a, b, c, m, p = '00000000000000a1', '00000000000000b2', 'f0000000000000c3', '00000000000000e5', '00000000000000f6'
            r = d / 'layer/req'; r.mkdir(parents=True)
            for k in (a, b, c, m, p): (r / f'{k}.ctl').write_bytes(b'x'); (r / f'{k}.code').write_bytes(b'y')
            lib = d / 'pack/shaders/v-x/dmsh_vk.lib'; self.library(lib, game, [a, b, c, p])   # m is missing from the library; p is its pixel mate's vertex shader
            (r / 'pairs.tsv').write_text(f'{p}\t{m}\n{a}\t{b}\n')
            self.assertEqual(self.java('kept', lib), 'false')
            self.assertEqual(self.java('verifyonly', d / 'layer', lib, game), 'true ' + ','.join(sorted([a, b, c])), 'p is paired with a missing shader: an ordinary request; m is missing')
            (d / 'layer/lib/v-v1').mkdir(parents=True); (d / 'layer/lib/v-v1/built.tsv').write_text(f'{a}\tok\tv1\n')
            self.assertEqual(self.java('verifyonly', d / 'layer', lib, game, 'v1'), 'true ' + ','.join(sorted([b, c])), 'a built one is the layer\'s: not verified again')
            self.assertEqual(self.java('verifyonly', d / 'layer', d / 'none.lib', game), 'false ', 'no library read: nothing is verify-only')
            (lib.parent / 'sampled-keep.json').write_text('{}')
            self.assertEqual(self.java('kept', lib), 'true')
            self.assertEqual(self.java('drop', d / 'layer', lib, game), '0', 'a kept library: the requests are the verification, none is dropped')
            self.assertEqual(len(list(r.glob('*.ctl'))), 5)
        finally: shutil.rmtree(d, ignore_errors=True)

    @staticmethod
    def whole_library(path, game, keys, abi=b'abcdef012345', cut=0):
        """A dmsh_vk.lib as the host checks it: header, entries (one module each), module table, blob; cut: bytes taken off its end"""
        ks = sorted(int(k, 16) for k in keys); n = len(ks)
        eo = 128; mo = eo + 64 * n; bo = mo + 16 * n; blob = 32 * n
        h = bytearray(128); struct.pack_into('<II', h, 0, 0x4B56534D, 1); h[24:36] = b'0123456789ab'; h[40:56] = game.encode().ljust(16, b'\0')
        struct.pack_into('<IIIIIIQQ', h, 72, n, eo, 0, bo, n, mo, bo, blob); h[116:128] = abi
        ents = b''.join(struct.pack('<QQBBHII', k, 0, 4, 1, 0, i, 0) + bytes(64 - 28) for i, k in enumerate(ks))
        mods = b''.join(struct.pack('<IIIBBH', 32 * i, 32, 32, 0, 0, 0) for i in range(n))
        data = bytes(h) + ents + mods + b'\x03\x02\x23\x07' * (blob // 4)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data[:len(data) - cut])

    def test_host_library_is_whole(self):
        """A library cut short is refused by the host (vka_shlib_check: blob out of the file): the launcher does not take it as the host's library, so dropCovered keeps the
        requests it lists, and the next library the host would play is taken instead"""
        d = Path(tempfile.mkdtemp(prefix='layerwhole_'))
        try:
            game, cur, abi = '040074A01BF12000', 'aaaaaaaaaaaa', 'abcdef012345'
            keys = ['00000000000000a1', '00000000000000b2']
            own = d / f'pack/shaders/v-{cur}/dmsh_vk.lib'
            self.whole_library(own, game, keys)
            self.assertEqual(self.java('structure', own), 'ok')
            self.assertEqual(self.java('host', d / 'pack', game, cur, abi), f'host {own}'.split(' ', 1)[1])
            self.whole_library(own, game, keys, cut=8)
            self.assertEqual(self.java('structure', own), 'blob out of the file')
            self.assertEqual(self.java('host', d / 'pack', game, cur, abi), '-')
            older = d / 'pack/shaders/v-bbbbbbbbbbbb/dmsh_vk.lib'; self.whole_library(older, game, keys)
            self.assertEqual(self.java('host', d / 'pack', game, cur, abi), str(older))   # the host goes on to the next library it plays
            r = d / 'layer/req'; r.mkdir(parents=True)
            for k in keys: (r / f'{k}.ctl').write_bytes(b'x')
            self.assertEqual(self.java('drop', d / 'layer', own, game), '0')   # dropCovered does not take a library cut short either
            b = bytearray(older.read_bytes()); struct.pack_into('<Q', b, 128 + 64, 0); older.write_bytes(bytes(b))   # (the second entry's key: 0, out of order)
            self.assertIn('out of order', self.java('structure', older))
            b = bytearray(own.read_bytes() + bytes(8)); struct.pack_into('<I', b, 128 + 128 + 16 + 8, 4000); own.write_bytes(bytes(b))   # the second module's stored bytes past the blob
            self.assertEqual(self.java('structure', own), 'module 1 past the blob')
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_pack_lock(self):
        self.assertEqual(self.java('lock'), 'ok')

    def test_quota_pause_procs(self):
        g, m = 1 << 30, 1 << 20
        self.assertEqual(self.java('quota', 0, 100 * g, 0), str(2 * g))   # at least 2 GB
        self.assertEqual(self.java('quota', 3 * g, 100 * g, 1 * g), str(9 * g // 2))   # room for a whole layer of its library: library, results and the next library (4.5 times it)
        self.assertEqual(self.java('quota', 500 * m, 1 * g, 0), str(500 * m + g - g))   # the volume keeps 1 GB free: only the free space stops it
        self.assertEqual(self.java('quota', 3 * g, 2 * g, 1 * g), str(5 * g - g))
        self.assertEqual(self.java('quota', 10 * g, 50 * g, 20 * g), str(60 * g - 3 * g))   # (a twentieth of what it can have kept free, when that is more than 1 GB)
        self.assertEqual(self.java('quota', 100, 0, 0), '0')
        d = Path(tempfile.mkdtemp(prefix='layerusage_'))
        try:
            (d / 'req').mkdir(); (d / 'req/a.ctl').write_bytes(b'x' * 100); (d / 'lib/v-x/results').mkdir(parents=True); (d / 'lib/v-x/dmsh_vk.lib').write_bytes(b'y' * 1000)
            (d / 'lib/v-x/results/k.r').write_bytes(b'r' * 10)
            (d / 'lib/v-old/results').mkdir(parents=True); (d / 'lib/v-old/dmsh_vk.lib').write_bytes(b'o' * 5000); (d / 'lib/v-old/results/k.r').write_bytes(b'r' * 5000)
            self.assertEqual(self.java('usage', d), '11110')
            self.assertEqual(self.java('usage', d, 'x'), '1110')   # another version's folder is not this version's quota: an APK update never blocks the game
            # the game page's note: written past the quota, read only while the phone still has no room for it
            self.assertEqual(self.java('full', d, 'x', 100 * g), '-')
            self.java('notefull', d, 3 * g // 2, g)
            self.assertEqual(self.java('full', d, 'x', 0), f'{3 * g // 2} {g // 2}')
            self.assertEqual(self.java('full', d, 'x', 100 * g), '-')   # freed space since: no note shown
            self.java('notefull', d, g, 2 * g); self.assertFalse((d / '.quota').exists())
        finally: shutil.rmtree(d, ignore_errors=True)
        t = self.java('storage', 3 * g, 2 * g, 'false', 'true')
        self.assertIn('3,072 MB', t); self.assertIn('Import a library', t); self.assertIn('Build all', t); self.assertIn('Shader cache built while playing', t); self.assertIn('may not show', t)
        self.assertNotIn('Build all', self.java('storage', 3 * g, 2 * g, 'true', 'false'))
        self.assertIn('storage: free space on the phone, or Import a library', self.java('line', 'true', 'false', 12, 5, 'storage', 'false'))
        self.assertEqual(self.java('pause', 0, 80, 'false'), '-')
        self.assertEqual(self.java('pause', 2, 80, 'true'), '-')   # moderate: warm, one worker
        self.assertEqual(self.java('pause', 3, 80, 'true'), '-')   # severe with no game: one worker
        self.assertEqual(self.java('pause', 4, 80, 'true'), '-')   # critical: one worker (no pause)
        self.assertEqual(self.java('pause', 1, 80, 'false'), '-')   # light: one worker, no pause
        self.assertEqual(self.java('pause', 0, 14, 'false'), 'Shader build paused (battery low)')
        self.assertEqual(self.java('pause', 0, 14, 'true'), '-')
        self.assertEqual(self.java('pause', 0, -2147483648, 'false'), '-')   # unknown battery
        self.assertEqual(self.java('procs', 8, 0, 10), '2'); self.assertEqual(self.java('procs', 8, 1, 10), '1'); self.assertEqual(self.java('procs', 4, 0, 10), '1')
        # many requests waiting (a game that plays blank): more workers until the thermal pause, never past 3, never on a phone with fewer than 6 cores
        self.assertEqual(self.java('procs', 8, 1, 1000), '3'); self.assertEqual(self.java('procs', 6, 1, 150), '3'); self.assertEqual(self.java('procs', 6, 0, 149), '2')
        self.assertEqual(self.java('procs', 12, 0, 1000), '3'); self.assertEqual(self.java('procs', 8, 2, 1000), '3'); self.assertEqual(self.java('procs', 4, 1, 1000), '1')

    def test_tidy_removes_unfinished_library(self):
        d = Path(tempfile.mkdtemp(prefix='layertmp_'))
        try:
            (d / 'lib/v-aaaaaaaaaaaa').mkdir(parents=True); (d / 'lib/v-aaaaaaaaaaaa/dmsh_vk.lib.tmp').write_bytes(b'half')
            (d / '.building').write_bytes(b'')
            self.java('tidy', d, 'aaaaaaaaaaaa')
            self.assertTrue((d / 'lib/v-aaaaaaaaaaaa/dmsh_vk.lib.tmp').exists())   # a build is running: its file
            (d / '.building').unlink()
            self.java('tidy', d, 'aaaaaaaaaaaa')
            self.assertFalse((d / 'lib/v-aaaaaaaaaaaa/dmsh_vk.lib.tmp').exists())
        finally: shutil.rmtree(d, ignore_errors=True)


def setUpModule():
    javatext.install()   # the launcher text is in strings.xml: this module reads the Java source with its English


def tearDownModule():
    javatext.uninstall()


if __name__ == '__main__':
    unittest.main()
