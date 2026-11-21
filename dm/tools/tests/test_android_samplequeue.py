"""The launcher's queue of sampled-keep checks (android/SHADERS.md "Sampled keep"; SampleQueue.java with android/app/src/jvmtest's SampleQueueJvmTest) and how Ondevice, LayerService and
MainActivity use it: one check at a time, a card for each, Play takes the lead, the phone is calm, no check starts in the app's first seconds. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
J = REPO / 'android/app/src'
SRC = J / 'main/java/dev/nxnative/android/SampleQueue.java'
TEST = J / 'jvmtest/java/dev/nxnative/android/SampleQueueJvmTest.java'


def text(rel): return (REPO / rel).read_text(encoding='utf-8')


class Wiring(unittest.TestCase):
    def test_the_queue_has_no_android_classes(self):
        self.assertNotRegex(SRC.read_text(encoding='utf-8'), r'import android\.')

    def test_ondevice_queues_instead_of_firing_every_card(self):
        o = text('android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java')
        self.assertNotIn('sampleTried', o)                 # the old per-process set that marked a pack tried when the lock was busy
        self.assertIn('SampleQueue', o)
        self.assertIn('SampleQueue.holdReason', o)         # one gate: game, packer, settle time, calm, cooling
        self.assertIn('LayerWatch.backgroundCalm', o)
        self.assertIn('static void playTakesLead', o)
        self.assertIn('static void sampleSkip', o)
        self.assertIn('EXTRA_STOP', o)

    def test_play_takes_the_lead(self):
        m = text('android/app/src/main/java/dev/nxnative/android/MainActivity.java')
        start = m[m.index('private void start(Game g)'):]
        self.assertIn('Ondevice.playTakesLead', start[:400])

    def test_the_service_stops_a_check_and_says_why_it_refused(self):
        s = text('android/app/src/ondevice/java/dev/nxnative/android/LayerService.java')
        self.assertIn('EXTRA_STOP', s)
        self.assertIn('e.interrupt()', s)
        self.assertIn('REFUSED', s)

    def test_the_check_runs_in_the_foreground(self):
        """A plain started service is stopped a minute after the app leaves the screen ("Stopping service due to app idle") and the :pack process is frozen mid-check,
        holding the packer's lock (2026-11-16 phone test): the check is a dataSync foreground service, and a refused start never ends the service under a running thread"""
        o = text('android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java')
        start = o[o.index('private static void startSample'):]
        self.assertIn('c.startForegroundService(i)', start[:1800])
        self.assertNotIn('c.startService(i)', start[:1800])
        m = text('android/app/src/ondevice/AndroidManifest.xml')
        svc = m[m.index('android:name=".LayerService"'):]
        self.assertIn('android:foregroundServiceType="dataSync"', svc[:200])
        s = text('android/app/src/ondevice/java/dev/nxnative/android/LayerService.java')
        self.assertIn('FOREGROUND_SERVICE_TYPE_DATA_SYNC', s)
        self.assertIn('public void onTimeout(int startId, int fgsType)', s)
        on = s[s.index('public int onStartCommand'):s.index('private void end(int startId)')]
        self.assertLess(on.index('foreground()'), on.index('LayerWatch.pauseReason'))   # answered before any refusal
        self.assertNotRegex(on, r'(?<![.\w])stopSelf\(startId\); return START_NOT_STICKY')   # refusals go through end(), which leaves a running thread's service alone
        self.assertIn('stopSelf();   // (not stopSelf(startId)', on)

    def test_the_card_words_name_no_old_library(self):
        for rel in ('android/app/src/main/java/dev/nxnative/android/SampleQueue.java', 'android/app/src/main/java/dev/nxnative/android/PrepareText.java',
                    'android/app/src/main/java/dev/nxnative/android/MainActivity.java'):
            for ln in text(rel).splitlines():
                for lit in re.findall(r'"(?:[^"\\]|\\.)*"', ln.split('//')[0]):   # string literals only, not comments
                    if re.search(r'(older|old) librar', lit): self.fail(f'{rel}: {lit}')

    def test_the_python_side_writes_progress_and_stops_without_a_verdict(self):
        p = text('dm/gfx/tools/shader_sample.py')
        self.assertIn("PROGRESS_FILE = 'progress.json'", p)
        self.assertIn('EXIT_STOPPED', p)

    def test_the_service_takes_the_lines_thermal_rule(self):
        """The launcher's line starts a check on a severe phone with no game (backgroundCalm); the service must not refuse it with the in-game rule (phone test 2026-11-16)"""
        s = text('android/app/src/ondevice/java/dev/nxnative/android/LayerService.java')
        self.assertIn('isSample ? LayerWatch.backgroundPause(this)', s)
        w = text('android/app/src/main/java/dev/nxnative/android/LayerWatch.java')
        self.assertIn('static boolean backgroundCalm(Context c) { return backgroundPause(c) == null; }', w)

    def test_known_transitions_need_no_check(self):
        """A candidate whose older library's VK chain is a row of vka_known_transitions.tsv for this chain is kept with no check and no card (Ondevice.knownKeep before the line);
        the APK carries the rows for its own chain (NX_KNOWN_FROM)"""
        g = text('android/app/build.gradle')
        self.assertIn("'NX_KNOWN_FROM'", g); self.assertIn('vka_known_transitions.tsv', g)
        o = text('android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java')
        keep = o[o.index('static boolean sampledKeep'):]
        self.assertLess(keep.index('knownKeep(c, packDir, old)'), keep.index('SAMPLES.enqueue(key)'))
        self.assertIn('ShaderLibs.knownKeep(packDir, old, BuildConfig.NX_DMSH_VK_CHAIN, BuildConfig.NX_DMSH_VK_ABI, BuildConfig.NX_DMSH_CHAIN, BuildConfig.NX_KNOWN_FROM', o)
        self.assertTrue((REPO / 'dm/gfx/vk_android/vka_known_transitions.tsv').is_file())

    def test_the_chain_is_untouched(self):
        import hashlib
        r = subprocess.run(['python3', str(REPO / 'dm/gfx/tools/vk_android_build.py'), '--digest'], capture_output=True, text=True)
        self.assertEqual(r.stdout.strip(), '9e7537573836')

    def test_skip_backoff_and_resume_outlive_the_process(self):
        """A Skip and a check given up are recorded on disk (not queued again for 7 days or until the APK or tools change); an interrupted check waits BACKOFF_MS before the
        line starts it again and resumes from its translations; a verdict removes the work folder"""
        o = text('android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java')
        skip = o[o.index('static void sampleSkip'):]
        self.assertIn('recordSkip(packDir, SampleQueue.SKIPPED)', skip[:300])
        keep = o[o.index('static boolean sampledKeep'):]
        self.assertIn('SampleQueue.skipped(packDir, BuildConfig.NX_DMSH_VK_CHAIN', keep[:2400])
        self.assertEqual(o.count('recordSkip(it.pack, SampleQueue.FAILED)'), 2, 'both ways a start fails')
        step = o[o.index('private static void sampleStep'):]
        self.assertIn('SAMPLES.startNext(k ->', step[:2600]); self.assertIn('SampleQueue.backoffLeft(SampleQueue.interruptedAt(', step[:2600])
        self.assertIn('SampleQueue.runningText(p, sampleResuming)', o)
        s = text('android/app/src/ondevice/java/dev/nxnative/android/LayerService.java')
        self.assertIn('deleteTree(work)', s)
        p = text('dm/gfx/tools/shader_sample.py')
        for name, val in (('RESUME_FILE', 'resume.json'), ('STOPPED_FILE', 'stopped.json'), ('PROGRESS_FILE', 'progress.json')):
            self.assertIn(f"{name} = '{val}'", p)
        q = SRC.read_text(encoding='utf-8')
        self.assertIn('RESUME = "resume.json", STOPPED = "stopped.json", PROGRESS = "progress.json"', q)
        marker = re.search(r'BUILDING = "([^"]+)"', text('android/app/src/main/java/dev/nxnative/android/LayerPending.java')).group(1)
        self.assertIn(f'MARKER = "{marker}"', q)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Queue(unittest.TestCase):
    def test_rules(self):
        tmp = Path(tempfile.mkdtemp(prefix='samplequeue_'))
        try:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(tmp), str(SRC), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', str(tmp), 'dev.nxnative.android.SampleQueueJvmTest'], capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')
        finally: shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
