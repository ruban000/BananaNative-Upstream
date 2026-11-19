"""The launcher's .nx reading (android/NX.md) on the JVM: android/app/src/jvmtest NxJvmTest with Nx, Nup, Nsp, Zstd, Pack, DumpLayout, BuildCheck, RomfsCheck,
Profile, ShaderLibs, PickedPath, CopyPlan, PackFind, Words and a stub BuildConfig. Its fixtures come from dm/tools/nx_pack.py (run with this Python, which needs a
zstd module for the ZSTD archives) and from the test's own small writer. Skipped without a JDK."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/NxJvmTest.java'

# the profile's build row is the fixture's update main (build ID bytes 2*31+i: 3e3f4041...), and one romfs_check row on a RomFS text file
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=3e3f4041\\t\\t\\t\\t\\t\\tPack/Actor/b.txt|1|hello romfs"
        + "\\n040015100B514001\\tWonder Edition\\t1\\tabcdef012345\\t327680 main=7c7d7e7f";
    public static final String NX_DMSH_VK_CHAIN = "6607c8d19abc";
    public static final String NX_DMSH_VK_ABI = "01b52a48538b";
    public static final String NX_DMSH_VK_ABI_COMPAT = "";
    public static final String NX_DMSH_VK_ABI_SAME = "";
}
'''


def have_zstd():
    # as nx_pack.py runs (-I: no PYTHONPATH, no user site)
    probe = 'try:\n    import compression.zstd\nexcept ImportError:\n    import backports.zstd\n'
    return subprocess.run([sys.executable, '-I', '-c', probe], capture_output=True).returncode == 0


def compile_test(case, t):
    (t / 'BuildConfig.java').write_text(STUB, encoding='utf-8')
    r = subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(t / 'classes')]
                       + [str(SRC / f'{c}.java') for c in ('Nx', 'Nacp', 'Nup', 'Nsp', 'Zstd', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Profile', 'ShaderLibs',
                                                          'PickedPath', 'CopyPlan', 'PackFind', 'Words')]
                       + [str(TEST), str(t / 'BuildConfig.java')], capture_output=True, text=True, errors='replace')
    case.assertEqual(r.returncode, 0, r.stdout + r.stderr)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
@unittest.skipUnless(os.environ.get('NX_TEST_DKB_NX'), 'NX_TEST_DKB_NX names no real archive')
class RealArchive(unittest.TestCase):
    """NXConvert's own archive, read only (NX_TEST_DKB_NX=<Donkey Kong Bananza .nx from pack-nup>): packages, main.npdm, the card's details, RomFS files"""
    def test_real(self):
        t = Path(tempfile.mkdtemp(prefix='nxreal_'))
        try:
            compile_test(self, t)
            r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(t / 'classes'), 'dev.nxnative.android.NxJvmTest', 'real', os.environ['NX_TEST_DKB_NX']],
                               capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
            self.assertRegex(r.stdout, r'ok \d+')
        finally:
            shutil.rmtree(t, ignore_errors=True)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
@unittest.skipUnless(have_zstd(), 'no zstd module for nx_pack.py')
class Launcher(unittest.TestCase):
    def test_jvm(self):
        t = Path(tempfile.mkdtemp(prefix='nxjvm_'))
        try:
            compile_test(self, t)
            (t / 'run').mkdir()
            r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8', '-cp', str(t / 'classes'), 'dev.nxnative.android.NxJvmTest', str(REPO), str(t / 'run'),
                                sys.executable], capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
            self.assertRegex(r.stdout, r'^ok \d+')
        finally:
            shutil.rmtree(t, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
