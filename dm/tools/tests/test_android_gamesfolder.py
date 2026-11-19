"""The launcher's games folders (GamesFolder.java, MetaKeep.java; android/README.md "Games folders") on the JVM: android/app/src/jvmtest GamesFolderJvmTest with
GamesFolder, MetaKeep, PackFind, Nup, Nsp, Nx, Zstd, Pack, DumpLayout, BuildCheck, RomfsCheck, Profile, ShaderLibs, PickedPath, CopyPlan, Words and a stub
BuildConfig. The folder it looks through is made here: a .nup (dm/tools/nup_pack.py), an NSP game with its older version and DLC beside it (test_nsp.py's
writer), an .nx with an update-only .nx beside it (dm/tools/nx_pack.py), a dump folder, a .nup four levels down and one five levels down, hidden, work and pack
folders with .nup files in them, and a file with an upper-case extension. The test checks the rule, the depth limit, the kept facts (a look at unchanged files
reads none), the three triggers and what each adds, the kept card details, and stand-in paths read through an opener. Skipped without a JDK or a zstd module."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(REPO / 'dm/tools'), str(REPO / 'scripts')]
from dm.tools.tests import test_nsp as N  # noqa: E402
from dm.tools.tests.test_android_nx import have_zstd  # noqa: E402
import nsp_extract  # noqa: E402

SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/GamesFolderJvmTest.java'
CLASSES = ('GamesFolder', 'MetaKeep', 'PackFind', 'Nup', 'Nsp', 'Nx', 'Zstd', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Profile', 'ShaderLibs', 'PickedPath',
           'CopyPlan', 'Words')


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode: raise AssertionError(' '.join(map(str, cmd)) + '\n' + r.stdout[-2000:] + r.stderr[-2000:])
    return r


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
@unittest.skipUnless(have_zstd(), 'no zstd module for nx_pack.py')
class GamesFolderJvm(unittest.TestCase):
    def test_games_folder(self):
        t = Path(tempfile.mkdtemp(prefix='gamesfolder_'))
        try:
            (t / 'BuildConfig.java').write_text(N.Launcher.STUB, encoding='utf-8')
            run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(t / 'classes')] + [str(SRC / f'{c}.java') for c in CLASSES]
                + [str(TEST), str(t / 'BuildConfig.java')])
            g, w = t / 'games', t / 'work'
            (g / 'NSP Game').mkdir(parents=True)
            (g / 'NX').mkdir()
            w.mkdir()
            base, update, dlc = N.build('game', N.TID, 0, N.GAME0), N.build('game', N.TID, 131072, N.GAME1), N.build('dlc', N.DLC, 0, N.DLCT)
            for name, data in (('base.nsp', base), ('update.nsp', update), ('dlc.nsp', dlc)):
                (g / 'NSP Game' / name).write_bytes(data)
                (w / name).write_bytes(data)
            nsp_extract.extract(w / 'base.nsp', w / 'dump0', say=lambda *a: None)
            nsp_extract.extract(w / 'update.nsp', w / 'dumpu', say=lambda *a: None)
            run([sys.executable, '-I', str(REPO / 'dm/tools/nup_pack.py'), str(w / 'dump0'), str(g / 'Bananza.nup'), '--raw'])
            run([sys.executable, '-I', str(REPO / 'dm/tools/nx_pack.py'), str(w / 'dump0'), str(g / 'NX/game.nx')])
            run([sys.executable, '-I', str(REPO / 'dm/tools/nx_pack.py'), str(w / 'dumpu'), str(g / 'NX/zz update.nx')])
            shutil.copytree(w / 'dump0', g / 'Dumps/Dump0')
            for d in ('.hidden', '0400AAAA00002000-work', 'packdir', 'a/b/c/d', 'a/b/c/d/e'): (g / d).mkdir(parents=True, exist_ok=True)
            for f in ('.hidden/h.nup', '0400AAAA00002000-work/w.nup', 'packdir/p.nup', 'a/b/c/d/deep.nup', 'a/b/c/d/e/deeper.nup'): shutil.copyfile(g / 'Bananza.nup', g / f)
            (g / 'packdir/pack.tsv').write_text('format\t1\n')
            (g / 'Shout.NUP').write_bytes(b'wrong case')
            (g / 'readme.txt').write_text('not a game')
            (t / 'state').mkdir()
            r = run(['java', '-Dfile.encoding=UTF-8', '-cp', str(t / 'classes'), 'dev.nxnative.android.GamesFolderJvmTest', str(g), str(t / 'state')])
            self.assertRegex(r.stdout, r'^ok \d+', r.stdout)
        finally:
            shutil.rmtree(t, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
