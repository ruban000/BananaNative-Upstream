"""dm/tools/nx.py, the NX archive reader (android/NX.md), against archives written by the clean-room writer dm/tools/nx_pack.py: RAW and ZSTD blocks, shared
blocks and file records, files of several blocks with a short last one, reads at random offsets, header slots and generations, damaged and refused archives
(SPEC §1, §3, §4, §12), the package selectors, romfs.py's paths, and the tools that take a game folder (pack_dump.py, romfs_image.py, pack_android.py) giving the same
result from the folder and from the .nx of it. No game files needed."""
import hashlib
import io
import os
from pathlib import Path
import random
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(REPO / 'dm/tools'), str(REPO / 'scripts'), str(Path(__file__).resolve().parent)]
import nup
import nx
import nx_pack
import romfs
import romfs_image
import pack_dump
import nso
from test_nup import TID, VER, DLC, BIG, npdm, module, dump, have_zstd

RND = random.Random(7).randbytes(3 * 65536 + 777)   # incompressible: RAW blocks, four of them, the last one short
REPEAT = (b'\x5a' * 65536) * 3 + b'end'             # three equal chunks: one block referenced three times (not contiguous: read through a copy for mmap)


def nx_dump(root):
    """test_nup's dump, with files of several blocks (RAW and ZSTD), a file of equal chunks, the same bytes at another path, and a program1/ in the update"""
    dump(root)
    for sub in ('base', f'updates/{VER}'):
        (root / sub / 'romfs/rnd.bin').write_bytes(RND)
        (root / sub / 'romfs/repeat.bin').write_bytes(REPEAT)
    (root / f'updates/{VER}/romfs/Shader/big_copy.bin').write_bytes(BIG)   # another path: its own file record, the same blocks
    p1 = root / f'updates/{VER}/program1'
    (p1 / 'exefs').mkdir(parents=True); (p1 / 'romfs').mkdir()
    (p1 / 'exefs/main').write_bytes(module(b'program 1')); (p1 / 'exefs/main.npdm').write_bytes(npdm(TID + 1))
    (p1 / 'romfs/p1.txt').write_text('program 1')


def write_nx(srcs, out, *args):
    srcs = [srcs] if isinstance(srcs, (str, Path)) else srcs
    r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx_pack.py'), *map(str, srcs), str(out), *args], capture_output=True, text=True)
    if r.returncode: raise AssertionError(r.stdout + r.stderr)
    return out


def posix(p):   # a container path with '/' after the colon whatever the system (romfs.Path joins with os.sep)
    f, rest = nup.split(str(p))
    return f'{f}:{rest}'


def tree(top):   # {relative path: bytes or None for a directory} under a romfs.Path or a folder
    return {p.relative_to(top).as_posix(): None if p.is_dir() else p.read_bytes() for p in sorted(top.rglob('*'))}


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not have_zstd(): raise unittest.SkipTest('no zstd module (Python 3.14 or backports.zstd)')
        cls.td = tempfile.TemporaryDirectory()
        cls.root = Path(cls.td.name).resolve()
        cls.dump = cls.root / 'Game'; nx_dump(cls.dump)
        cls.nx = write_nx(cls.dump, cls.root / 'Game.nx')
        cls.variants = {'auto': cls.nx}
        for tag, args in (('raw', ['--codec', 'raw']), ('zstd', ['--codec', 'zstd']), ('nodedup', ['--no-dedup']), ('chunk17', ['--chunk-log2', '17']),
                          ('gen2', ['--second-generation'])):
            (cls.root / tag).mkdir()
            cls.variants[tag] = write_nx(cls.dump, cls.root / tag / 'Game.nx', *args)

    @classmethod
    def tearDownClass(cls): cls.td.cleanup()

    def bad(self, data, name):
        p = self.root / name; p.write_bytes(data); self.addCleanup(p.unlink)
        return p


class Read(Fixture):
    def test_packages_and_selectors(self):
        n = nup.open_nup(self.nx)
        self.assertIsInstance(n, nx.Nx)
        self.assertEqual([(p.selector, p.title_id, p.version, p.flags) for p in n.packages],
                         [('base', TID, 0, 0), (f'update/{VER}', TID, VER, 0), (f'addon/{DLC:016x}', DLC, 0, 0)])
        self.assertEqual(n.select('').version, VER); self.assertEqual(n.select('base').kind, nup.BASE)
        self.assertEqual(n.select(f'addon/{DLC:016X}').title_id, DLC)
        with self.assertRaisesRegex(nup.Error, 'no package'): n.select('update/1')
        self.assertEqual(nup.split(f'/a/b.nx:update/{VER}/romfs'), ('/a/b.nx', f'update/{VER}/romfs'))
        self.assertEqual(nup.split(f'/a/b.nup:c/x.nx:base'), ('/a/b.nup:c/x.nx', 'base'))   # at the last container
        self.assertEqual(nup.split(f'/a/b.nx:c/x.nup:base'), ('/a/b.nx:c/x.nup', 'base'))
        self.assertTrue(nup.is_nup(self.nx)); self.assertTrue(nx.is_nx(self.nx)); self.assertFalse(nx.is_nx(self.dump / 'base/exefs/main'))
        self.assertEqual(nup.default_romfs(self.nx), f'{self.nx}:update/{VER}/romfs')
        self.assertEqual(nup.default_romfs(f'{self.nx}:base'), f'{self.nx}:base/romfs')
        root = romfs.Path(f'{self.nx}:')
        self.assertEqual(sorted(p.name for p in root.iterdir()), ['addon', 'base', 'update'])
        self.assertEqual(romfs.os.listdir(f'{self.nx}:update/{VER}'), ['exefs', 'program1', 'romfs'])
        self.assertEqual(romfs.os.listdir(f'{self.nx}:addon/{DLC:016x}'), ['romfs'])
        self.assertFalse(romfs.Path(f'{self.nx}:update/1/romfs').exists())
        self.assertFalse(romfs.Path(f'{self.nx}:base/romfs/only_in_update').exists())
        self.assertTrue(romfs.Path(f'{self.nx}:update/{VER}/program1/romfs/p1.txt').is_file())

    def test_same_tree_as_the_folders(self):
        for tag, path in self.variants.items():
            for sel, folder in (('base', 'base'), (f'update/{VER}', f'updates/{VER}'), (f'addon/{DLC:016x}', 'dlc/040074a01bf13001/0')):
                for sub in ('exefs', 'romfs'):
                    if not (self.dump / folder / sub).is_dir(): continue
                    want = {k: v for k, v in tree(self.dump / folder / sub).items() if not k.endswith('.listing')}
                    with self.subTest(tag=tag, sel=sel, sub=sub): self.assertEqual(tree(romfs.Path(f'{path}:{sel}/{sub}')), want)

    def test_codecs_and_sharing(self):
        def codecs(p): return {b[3] for b in nup.open_nup(p).blocks}
        self.assertEqual(codecs(self.variants['raw']), {nx.RAW}); self.assertEqual(codecs(self.variants['zstd']), {nx.ZSTD})
        self.assertEqual(codecs(self.nx), {nx.RAW, nx.ZSTD})
        n = nup.open_nup(self.nx)
        big, copy = n.node(f'update/{VER}/romfs/Shader/big.bin'), n.node(f'update/{VER}/romfs/Shader/big_copy.bin')
        self.assertEqual(big, n.node('base/romfs/Shader/big.bin'))   # an unchanged file at the same path: one file record in both snapshots
        self.assertNotEqual(big, copy); self.assertEqual(n.file_blocks(big), n.file_blocks(copy))   # another path: its own record, the same blocks
        self.assertEqual(len(n.file_blocks(big)), 17); self.assertEqual(n.blocks[n.file_blocks(big)[-1]][2], 4)   # 16 chunks of 64 KiB and the last 4 bytes
        rep = n.file_blocks(n.node('base/romfs/repeat.bin')); self.assertEqual(rep[:3], [rep[0]] * 3)
        m = nup.open_nup(self.variants['nodedup'])
        self.assertEqual(len({b for f in range(m.nf) for b in m.file_blocks(m.nd + f)}), sum(len(m.file_blocks(m.nd + f)) for f in range(m.nf)))
        self.assertEqual(len(nup.open_nup(self.variants['chunk17']).file_blocks(nup.open_nup(self.variants['chunk17']).node('base/romfs/Shader/big.bin'))), 9)

    def test_random_offsets(self):
        rnd = random.Random(1)
        for tag, path in self.variants.items():
            for rel, data in (('Shader/big.bin', BIG), ('rnd.bin', RND), ('repeat.bin', REPEAT)):
                with self.subTest(tag=tag, rel=rel), romfs.open(f'{path}:update/{VER}/romfs/{rel}', 'rb', buffering=0) as f:
                    self.assertEqual(romfs.os.path.getsize(f'{path}:update/{VER}/romfs/{rel}'), len(data))
                    for _ in range(40):
                        off = rnd.randrange(len(data) + 10); n = rnd.choice((1, 3, 4096, 65535, 65536, 65537, 200000))
                        f.seek(off); self.assertEqual(f.read(n), data[off:off + n])
                    f.seek(-4, 2); self.assertEqual(f.read(), data[-4:])
                    f.seek(0); self.assertEqual(f.read(), data)
        with romfs.open(f'{self.nx}:update/{VER}/romfs/a.txt') as f: self.assertEqual(f.readline(), f'version {VER}\n')
        self.assertEqual(romfs.Path(f'{self.nx}:base/romfs/zero').read_bytes(), b'')

    def test_mmap(self):
        n = nup.open_nup(self.variants['raw'])
        with romfs.open(f'{self.variants["raw"]}:base/romfs/rnd.bin', 'rb') as f:   # RAW blocks one after the other: the archive's own pages
            self.assertEqual(f.raw.contiguous(), n.blocks[n.file_blocks(n.node('base/romfs/rnd.bin'))[0]][0])
            with romfs.mmap.mmap(f.fileno(), 0, access=romfs.mmap.ACCESS_READ) as m: self.assertEqual(m[:], RND)
        for rel, data in (('Shader/big.bin', BIG), ('repeat.bin', REPEAT)):   # ZSTD, and shared blocks: through a temporary copy
            with romfs.open(f'{self.nx}:base/romfs/{rel}', 'rb') as f:
                with romfs.mmap.mmap(f.fileno(), 0, access=romfs.mmap.ACCESS_READ) as m: self.assertEqual(m[:], data)
                f.seek(5); self.assertEqual(f.read(3), data[5:8])
        with self.assertRaises(OSError): romfs.open(f'{self.nx}:base/romfs/a.txt', 'wb')
        with self.assertRaises(IsADirectoryError): romfs.open(f'{self.nx}:base/romfs', 'rb')
        with self.assertRaises(FileNotFoundError): romfs.open(f'{self.nx}:base/romfs/missing', 'rb')

    def test_cache_is_bounded(self):
        n = nup.open_nup(self.variants['zstd'])
        with mock.patch.object(nx, 'CACHE_BLOCKS', 3):
            self.assertEqual(n.read(n.node('base/romfs/Shader/big.bin')), BIG)
            self.assertLessEqual(len(n._cache), 3)

    def test_verify_and_cli(self):
        for path in self.variants.values():
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx.py'), str(path), 'verify'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr); self.assertRegex(r.stdout, r'\d+ files, 0 bad')
        run = lambda *a: subprocess.run([sys.executable, str(REPO / 'dm/tools/nx.py'), str(self.nx), *a], capture_output=True)
        r = run('packages'); self.assertEqual(r.returncode, 0)
        self.assertIn(f'update/{VER}\t{TID:016X}\tv{VER}\texefs=yes\tromfs=yes', r.stdout.decode())
        self.assertIn(b'rnd.bin\t', run('ls', f'update/{VER}/romfs').stdout)
        self.assertEqual(run('cat', f'update/{VER}/romfs/rnd.bin').stdout, RND)
        r = run('verify', f'update/{VER}/romfs'); self.assertEqual(r.returncode, 0, r.stdout)
        self.assertNotEqual(run('cat', 'update/1/romfs/x').returncode, 0)
        r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx_pack.py'), '--help'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0); self.assertIn('test fixtures', r.stdout)

    def test_no_zstd_module_for_raw(self):   # an archive of RAW blocks reads with no zstd module; a ZSTD block then names the missing module
        code = ('import sys; sys.modules["compression.zstd"] = None; sys.modules["backports"] = None; sys.path.insert(0, sys.argv[1]); import romfs, nup\n'
                'p = romfs.Path(sys.argv[2] + ":update/458752/romfs"); print(sum(len(f.read_bytes()) for f in p.rglob("*") if f.is_file()))\n')
        r = subprocess.run([sys.executable, '-I', '-c', code, str(REPO / 'dm/tools'), str(self.variants['raw'])], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(int(r.stdout), sum(f.stat().st_size for f in (self.dump / f'updates/{VER}/romfs').rglob('*') if f.is_file()))
        r = subprocess.run([sys.executable, '-I', '-c', code, str(REPO / 'dm/tools'), str(self.variants['zstd'])], capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0); self.assertIn('zstd', r.stderr)


def fix_headers(d, hdr=None):
    """Both header slots of d (a bytearray) with their CRC-32C again, after hdr(slot bytearray) changed them"""
    for off in (0, nx.PAGE):
        h = bytearray(d[off:off + 256])
        if hdr: hdr(h)
        struct.pack_into('<I', h, 252, nx.crc32c(bytes(h[:252]))); d[off:off + 256] = h
    return d


def catalog_patch(d, fn):
    """d with fn(catalog bytearray) applied and both headers' catalog SHA-256 and CRC-32C written again (the checks after the hash are the ones that refuse)"""
    h = nx.HEADER.unpack_from(d, 0); off, size = h[7], h[8]
    cat = bytearray(d[off:off + size]); fn(cat); d[off:off + size] = cat
    return fix_headers(d, lambda s: s.__setitem__(slice(120, 152), hashlib.sha256(bytes(cat)).digest()))


class Damaged(Fixture):
    def test_generations(self):
        g = self.variants['gen2']; good = g.read_bytes()
        n = nx.Nx(g)
        self.assertEqual(n.header.generation, 1); self.assertEqual(n.header.slot, 'B')
        self.assertEqual([p.selector for p in n.packages][-1], f'addon/{DLC:016x}')   # generation 1 added the add-on
        a = nx.HEADER.unpack_from(good, 0); b = nx.HEADER.unpack_from(good, nx.PAGE)
        self.assertEqual((a[-4], b[-4]), (0, 1))   # generation
        self.assertLess(a[6], b[6])   # generation 0's tail ends before generation 1's blocks
        d = bytearray(good); d[nx.PAGE + 40] ^= 1   # slot B damaged: generation 0, without the add-on
        m = nx.Nx(self.bad(bytes(d), 'slotB.nx'))
        self.assertEqual((m.header.generation, [p.selector for p in m.packages]), (0, ['base', f'update/{VER}']))
        self.assertEqual(tree(romfs.Path(f'{self.root / "slotB.nx"}:update/{VER}/romfs')), tree(self.dump / f'updates/{VER}/romfs'))
        d = bytearray(good); d[10] ^= 1   # slot A damaged: generation 1 as before
        self.assertEqual(nx.Nx(self.bad(bytes(d), 'slotA.nx')).header.generation, 1)
        d = bytearray(good); d[b[7] + 200] ^= 1   # generation 1's Catalog damaged: generation 0 is taken (SPEC §1)
        self.assertEqual(nx.Nx(self.bad(bytes(d), 'cat1.nx')).header.generation, 0)
        d = bytearray(good); d[:256] = bytes(256); d[nx.PAGE + 40] ^= 1
        with self.assertRaisesRegex(nx.Error, 'no valid header slot'): nx.Nx(self.bad(bytes(d), 'both.nx'))
        one = bytearray(self.nx.read_bytes()); one[3] ^= 1   # one generation, slot A damaged: slot B's copy
        self.assertEqual(nx.Nx(self.bad(bytes(one), 'oneA.nx')).header.slot, 'B')
        same = bytearray(self.nx.read_bytes()); same[nx.PAGE + 120] ^= 1   # slot B valid (its CRC written again) and of the same generation, but not the same
        struct.pack_into('<I', same, nx.PAGE + 252, nx.crc32c(bytes(same[nx.PAGE:nx.PAGE + 252])))
        with self.assertRaisesRegex(nx.Error, 'both header slots have generation 0 but differ'): nx.Nx(self.bad(bytes(same), 'differ.nx'))

    def test_refused(self):
        good = self.nx.read_bytes(); h = nx.HEADER.unpack_from(good, 0)
        cat_off, man_off, idx_off = h[7], h[13], h[15]
        cases = [('magic', b'NXAQ' + good[4:4096] + b'NXAQ' + good[4100:], 'not an NX archive'),
                 ('cut', good[:-100], 'cut off'), ('short', good[:100], 'no valid header slot')]
        d = bytearray(good); d[cat_off + 130] ^= 1; cases.append(('catalog hash', d, 'Catalog does not match its SHA-256'))
        d = bytearray(good); d[man_off + 100] ^= 1; cases.append(('manifest hash', d, 'Manifest does not match its SHA-256'))
        d = bytearray(good); d[idx_off + 3] ^= 1; cases.append(('index hash', d, 'block index does not match its SHA-256'))
        cases.append(('version', fix_headers(bytearray(good), lambda s: struct.pack_into('<H', s, 6, 4)), r'header version \(0,4\)'))
        cases.append(('major', fix_headers(bytearray(good), lambda s: struct.pack_into('<H', s, 4, 1)), r'header version \(1,2\)'))
        cases.append(('feature', fix_headers(bytearray(good), lambda s: struct.pack_into('<H', s, 12, 3)), 'required features 0x3'))
        cases.append(('optional', fix_headers(bytearray(good), lambda s: struct.pack_into('<H', s, 14, 1)), 'optional features'))
        cases.append(('reserved', fix_headers(bytearray(good), lambda s: s.__setitem__(230, 1)), 'reserved header bytes'))
        cases.append(('range', fix_headers(bytearray(good), lambda s: struct.pack_into('<Q', s, 24, struct.unpack_from('<Q', s, 24)[0] + 16)), 'not where the tail puts it'))
        cases.append(('minor 3', fix_headers(bytearray(good), lambda s: struct.pack_into('<H', s, 6, 3)), r'\(0,3\) without Source Resources'))
        n = nx.Nx(self.nx)
        snap0 = nx.CATALOG.unpack_from(good, cat_off)[14]   # the snapshot table's offset in the Catalog
        cases.append(('type 4', catalog_patch(bytearray(good), lambda c: c.__setitem__(snap0 + 4, 4)), 'type 4'))
        cases.append(('root', catalog_patch(bytearray(good), lambda c: struct.pack_into('<I', c, snap0 + 12, n.nd + 3)), 'root directory'))
        cases.append(('size', catalog_patch(bytearray(good), lambda c: struct.pack_into('<Q', c, snap0 + 16, 1)), 'its record says 1'))
        for name, data, msg in cases:
            with self.subTest(name), self.assertRaisesRegex(nx.Error, msg): nx.Nx(self.bad(bytes(data), f'{name}.nx'))

    def test_extensions(self):
        src = self.root / 'Small'; self.addCleanup(shutil.rmtree, src, True)
        (src / 'base/exefs').mkdir(parents=True); (src / 'base/romfs').mkdir()
        (src / 'base/exefs/main.npdm').write_bytes(npdm(TID)); (src / 'base/romfs/a').write_text('a')
        opt = write_nx(src, self.root / 'opt.nx', '--extension', '00019999'); self.addCleanup(opt.unlink)
        self.assertEqual([p.selector for p in nx.Nx(opt).packages], ['base'])   # an unknown optional extension is skipped
        req = write_nx(src, self.root / 'req.nx', '--extension', '00019999:required'); self.addCleanup(req.unlink)
        with self.assertRaisesRegex(nx.Error, 'requires Catalog extension 0x00019999'): nx.Nx(req)
        with self.assertRaisesRegex(pack_dump.Error, 'requires Catalog extension'): pack_dump.scan(req)

    def test_nca_tree_and_collection(self):
        src = self.root / 'Native'; self.addCleanup(shutil.rmtree, src, True)
        (src / 'base').mkdir(parents=True); (src / 'base/0123456789abcdef0123456789abcdef.nca').write_bytes(b'NCA' * 100)
        p = write_nx(src, self.root / 'native.nx', '--nca-tree', '--app-id', f'{TID:016x}'); self.addCleanup(p.unlink)
        with self.assertRaisesRegex(nx.Error, r'no application content meta record \(\*\.cnmt\.nca\)'): nx.Nx(p)   # NCA files, but no record
        other = self.root / 'Other'; self.addCleanup(shutil.rmtree, other, True)
        (other / 'base/exefs').mkdir(parents=True); (other / 'base/romfs').mkdir()
        (other / 'base/exefs/main.npdm').write_bytes(npdm(0x0400AAAA00002000)); (other / 'base/romfs/x').write_text('x')
        c = write_nx([self.dump, other], self.root / 'collection.nx'); self.addCleanup(c.unlink)
        with self.assertRaisesRegex(nx.Error, f'a collection of 2 games \\({TID:016X}, 0400AAAA00002000\\)'): nx.Nx(c)

    def test_damaged_blocks(self):
        good = self.nx.read_bytes(); n = nx.Nx(self.nx)
        k = n.node(f'update/{VER}/romfs/Shader/big.bin'); b = n.file_blocks(k)[0]
        off, stored, logical, codec, _ = n.blocks[b]; self.assertEqual(codec, nx.ZSTD)
        d = bytearray(good); d[off + stored // 2] ^= 0xFF
        p = self.bad(bytes(d), 'zblock.nx'); m = nx.Nx(p)   # the index is unchanged: it opens, the block fails when read
        try: same = m.read(k) == BIG
        except nup.Error: same = False
        self.assertFalse(same)
        r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx.py'), str(p), 'verify'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1); self.assertIn('[RED]', r.stdout)
        kr = n.node(f'update/{VER}/romfs/rnd.bin'); br = n.file_blocks(kr)[1]
        off, stored, _, codec, _ = n.blocks[br]; self.assertEqual(codec, nx.RAW)
        d = bytearray(good); d[off + 100] ^= 0xFF
        p = self.bad(bytes(d), 'rblock.nx'); m = nx.Nx(p)
        self.assertNotEqual(m.read(m.node(f'update/{VER}/romfs/rnd.bin')), RND)   # RAW reads are not checked by default (CHECK_READS)
        with mock.patch.object(nx, 'CHECK_READS', True), self.assertRaisesRegex(nx.Error, 'does not match its CRC-32C'):
            m.read(m.node(f'update/{VER}/romfs/rnd.bin'))
        self.assertTrue(m.verify(m.node(f'update/{VER}/romfs/a.txt')))   # the other files are whole
        with self.assertRaisesRegex(nx.Error, 'CRC-32C'): m.verify(m.node(f'update/{VER}/romfs/rnd.bin'))
        r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx.py'), str(p), 'verify', f'update/{VER}/romfs'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1); self.assertIn('rnd.bin', r.stdout); self.assertRegex(r.stdout, r'\d+ files, [1-9]\d* bad')


class Tools(Fixture):
    def test_pack_dump_scan(self):
        app, vers, dlc = pack_dump.scan(self.nx)
        self.assertEqual((app, {v: posix(p) for v, p in vers.items()}, {t: posix(p) for t, p in dlc.items()}),
                         (TID, {VER: f'{self.nx}:update/{VER}'}, {DLC: f'{self.nx}:addon/{DLC:016x}/romfs'}))
        self.assertEqual(posix(pack_dump.version_dir(self.nx, vers, 0)), f'{self.nx}:base')
        ver, p1 = pack_dump.program_dir(self.nx, 1)
        self.assertEqual((ver, posix(p1)), (VER, f'{self.nx}:update/{VER}/program1'))
        self.assertIsNone(pack_dump.program_dir(self.nx, 2))

    def test_companions_beside(self):   # an add-on in its own .nx beside the game (a split archive), found by content
        d = self.root / 'split'; d.mkdir(); self.addCleanup(shutil.rmtree, d, True)
        game = d / 'GameOnly'; shutil.copytree(self.dump, game, ignore=shutil.ignore_patterns('dlc'))
        add = d / 'DlcOnly'; shutil.copytree(self.dump / 'dlc', add / 'dlc')
        write_nx(game, d / 'game.nx'); write_nx(add, d / 'extra.nx', '--app-id', f'{TID:016x}')
        self.assertEqual([p.selector for p in nx.Nx(d / 'extra.nx').packages], [f'addon/{DLC:016x}'])
        app, vers, dlc = pack_dump.scan(d / 'game.nx')
        self.assertEqual((app, {t: posix(p) for t, p in dlc.items()}), (TID, {DLC: f'{d / "extra.nx"}:addon/{DLC:016x}/romfs'}))

    def test_pack_version_and_dlc_images_match_the_folder(self):
        out = self.root / 'packed'; self.addCleanup(shutil.rmtree, out, True)
        quiet = lambda s: None
        for tag, src in (('folder', self.dump), ('nx', self.nx)):
            app, vers, dlc = pack_dump.scan(src)
            pack_dump.pack_version(pack_dump.version_dir(src, vers, VER), app, out / tag / 'v', quiet)
            pack_dump.pack_dlc(app, dlc, out / tag / 'aoc', quiet)
        for f in ('v/ExeFS/main', 'v/ExeFS/sdk', 'v/ExeFS/main.npdm', 'v/RomFS.img', 'aoc/1.img'):
            self.assertEqual((out / 'nx' / f).read_bytes(), (out / 'folder' / f).read_bytes(), f)
        before = os.stat(out / 'nx/v/RomFS.img').st_mtime_ns   # a reuse keeps the image from the .nx as it does one from a folder
        romfs_image.pack(f'{self.nx}:update/{VER}/romfs', out / 'nx/v/RomFS.img', reuse=True)
        self.assertEqual(os.stat(out / 'nx/v/RomFS.img').st_mtime_ns, before)

    def test_rewriter_reads_a_plain_copy(self):
        import pack_android
        out = self.root / 'rsrc'; self.addCleanup(shutil.rmtree, out, True)
        src = romfs.Path(f'{self.nx}:update/{VER}/exefs/main'); plain = self.dump / f'updates/{VER}/exefs/main'
        f = pack_android.rewriter_src(src, out, 'main')
        self.assertTrue(f.is_file() and f.name == 'main' and f.read_bytes() == plain.read_bytes())
        self.assertEqual(nso.read(f).image, nso.read(plain).image)
        self.assertEqual(pack_android.rewriter_src(plain, out, 'main'), plain)

    def test_pack_android_same_pack(self):
        try: import capstone, lz4.block   # noqa: F401  (pack_android.py checks them before any work)
        except ImportError: self.skipTest('no capstone / lz4 (android/environment.yml)')
        import pack_android
        def fake_rewrite(py, srcs, out, jobs, g, in_process=False):   # the rewriter's input as it reads it (nso.py), as its output
            for m, src in srcs.items():
                (out / f'{m}.patched.bin').write_bytes(bytes(nso.read(str(pack_android.rewriter_src(src, out, m))).image)); (out / f'{m}.android.key').write_text(pack_android.rewrite_key(src, g) + '\n')
        packs = {}
        for tag, src in (('folder', self.dump), ('nx', self.nx)):
            out = self.root / f'pack-{tag}'; self.addCleanup(shutil.rmtree, out, True)
            argv = ['pack_android.py', str(src), '--out', str(out), '--no-shaders', '--in-process']
            stdout = io.TextIOWrapper(io.BytesIO(), encoding='utf-8')   # reconfigure() as a console's (pack_android.py on Windows)
            with mock.patch.object(sys, 'argv', argv), mock.patch.object(pack_android, 'rewrite', fake_rewrite), mock.patch('sys.stdout', stdout):
                pack_android.main()
            packs[tag] = out
        rows = {t: (p / 'pack.tsv').read_text().splitlines() for t, p in packs.items()}
        self.assertIn(f'romfs\tGame.nx:update/{VER}/romfs', rows['nx']); self.assertIn(f'aoc\t1\tGame.nx:addon/{DLC:016x}/romfs', rows['nx'])
        strip = lambda rs: [r for r in rs if not r.startswith(('romfs\t', 'aoc\t'))]
        self.assertEqual(strip(rows['nx']), strip(rows['folder']))
        for m in ('main', 'sdk'):
            self.assertEqual((packs['nx'] / f'{m}.patched.bin').read_bytes(), (packs['folder'] / f'{m}.patched.bin').read_bytes())
            self.assertEqual((packs['nx'] / f'{m}.android.key').read_text(), (packs['folder'] / f'{m}.android.key').read_text())


APP0, P1, VER1 = 0x010015100B514000, 0x040015100B514001, 327680   # a Switch 2 Edition: Super Mario Bros. Wonder's IDs (profile 040015100B514001: main subsdk0 sdk)
DLC1 = APP0 + 0x1001   # its upgrade add-on


def edition_dump(root):
    """A Switch 2 Edition in the console's layout: program 0 (base and update), program 1 in the update only (updates/<v>/program/1/), an add-on"""
    for sub in ('base/program/0', f'updates/{VER1}/program/0'):
        d = root / sub; (d / 'exefs').mkdir(parents=True); (d / 'romfs').mkdir()
        (d / 'exefs/main').write_bytes(module(b'p0 ' + sub.encode())); (d / 'exefs/main.npdm').write_bytes(npdm(APP0))
        (d / 'romfs/a.txt').write_text(f'program 0 {sub}'); (d / 'romfs/rnd.bin').write_bytes(RND)
    d = root / f'updates/{VER1}/program/1'; (d / 'exefs').mkdir(parents=True); (d / 'romfs').mkdir()
    for m in ('main', 'subsdk0', 'sdk'): (d / 'exefs' / m).write_bytes(module(b'p1 ' + m.encode()))
    (d / 'exefs/main.npdm').write_bytes(npdm(P1)); (d / 'romfs/p1.txt').write_text('program 1')
    (root / f'dlc/{DLC1:016x}/0/romfs').mkdir(parents=True); (root / f'dlc/{DLC1:016x}/0/romfs/dlcfile').write_text('upgrade')


class Programs(unittest.TestCase):
    """android/NX.md "Programs": each NxProgram has its packages (`<file>.nx:program<n>/<selector>`); program 0 is the application's own; add-ons are the application's"""
    @classmethod
    def setUpClass(cls):
        cls.td = tempfile.TemporaryDirectory()
        cls.root = Path(cls.td.name).resolve()
        cls.dump = cls.root / 'Edition'; edition_dump(cls.dump)
        cls.nx = write_nx(cls.dump, cls.root / 'Edition.nx', '--codec', 'raw')

    @classmethod
    def tearDownClass(cls): cls.td.cleanup()

    def test_selectors(self):
        n = nx.Nx(self.nx)
        self.assertEqual([r[0] for r in n.programs], [APP0, P1]); self.assertEqual([r[2] for r in n.programs], [0, 0])   # both primary, as NXConvert marks them
        self.assertEqual(n.program_indexes(), [0, 1])
        self.assertEqual([(p.selector, p.title_id) for p in n.packages], [('base', APP0), (f'update/{VER1}', APP0), (f'addon/{DLC1:016x}', DLC1)])
        self.assertEqual([(p.selector, p.title_id, p.flags) for p in n.program_packages(1)], [(f'update/{VER1}', P1, 1), (f'addon/{DLC1:016x}', DLC1, 0)])
        self.assertIs(n.program_packages(1)[-1], n.packages[-1])   # one add-on under every program
        self.assertEqual(romfs.Path(f'{self.nx}:program1/update/{VER1}/romfs/p1.txt').read_text(), 'program 1')
        self.assertEqual(tree(romfs.Path(f'{self.nx}:program1/update/{VER1}/romfs')), tree(self.dump / f'updates/{VER1}/program/1/romfs'))
        self.assertEqual(tree(romfs.Path(f'{self.nx}:program1/update/{VER1}/exefs')), tree(self.dump / f'updates/{VER1}/program/1/exefs'))
        self.assertEqual(tree(romfs.Path(f'{self.nx}:program0/update/{VER1}/romfs')), tree(romfs.Path(f'{self.nx}:update/{VER1}/romfs')))
        self.assertEqual(romfs.Path(f'{self.nx}:program1/addon/{DLC1:016x}/romfs/dlcfile').read_text(), 'upgrade')
        for missing in ('program1', 'program1/base', 'program2/update/327680', 'program1/updates/327680', 'base/program1'):
            with self.subTest(missing): self.assertFalse(romfs.Path(f'{self.nx}:{missing}').exists())
        self.assertEqual(nup.default_romfs(f'{self.nx}:program1/update/{VER1}'), f'{self.nx}:program1/update/{VER1}/romfs')
        self.assertEqual(nup.default_romfs(self.nx), f'{self.nx}:update/{VER1}/romfs')
        r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx.py'), str(self.nx), 'packages'], capture_output=True, text=True)
        self.assertIn(f'program1/update/{VER1}\t{P1:016X}\tv{VER1}', r.stdout)
        r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx.py'), str(self.nx), 'verify'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_program_dir_and_scan(self):
        ver, d = pack_dump.program_dir(self.nx, 1)
        self.assertEqual((ver, posix(d)), (VER1, f'{self.nx}:program1/update/{VER1}'))
        self.assertEqual(pack_dump.program_dir(self.dump, 1), (VER1, self.dump / f'updates/{VER1}/program/1'))
        self.assertIsNone(pack_dump.program_dir(self.nx, 2))
        a, b = pack_dump.scan(self.dump), pack_dump.scan(self.nx)
        self.assertEqual((a[0], sorted(a[1]), sorted(a[2])), (b[0], sorted(b[1]), sorted(b[2])))
        self.assertEqual((b[0], sorted(b[1]), sorted(b[2])), (APP0, [VER1], [DLC1]))
        self.assertEqual(pack_dump.split_addons(P1, b[2]), list(b[2].values()))

    def test_pack_android_program_1(self):
        try: import capstone, lz4.block   # noqa: F401
        except ImportError: self.skipTest('no capstone / lz4 (android/environment.yml)')
        import pack_android
        def fake_rewrite(py, srcs, out, jobs, g, in_process=False):
            for m, src in srcs.items():
                (out / f'{m}.patched.bin').write_bytes(bytes(nso.read(str(pack_android.rewriter_src(src, out, m))).image)); (out / f'{m}.android.key').write_text(pack_android.rewrite_key(src, g) + '\n')
        packs = {}
        for tag, src in (('folder', self.dump), ('nx', self.nx)):
            out = self.root / f'pack-{tag}'; self.addCleanup(shutil.rmtree, out, True)
            argv = ['pack_android.py', str(src), '--program', '1', '--out', str(out), '--no-shaders', '--in-process']
            stdout = io.TextIOWrapper(io.BytesIO(), encoding='utf-8')   # reconfigure() as a console's (pack_android.py on Windows)
            with mock.patch.object(sys, 'argv', argv), mock.patch.object(pack_android, 'rewrite', fake_rewrite), mock.patch('sys.stdout', stdout):
                pack_android.main()
            packs[tag] = out
        rows = {t: (p / 'pack.tsv').read_text().splitlines() for t, p in packs.items()}
        self.assertIn(f'romfs\tupdates/{VER1}/program/1/romfs', rows['folder'])
        self.assertIn(f'romfs_program\t0\tupdates/{VER1}/program/0/romfs', rows['folder'])
        self.assertIn(f'romfs\tEdition.nx:program1/update/{VER1}/romfs', rows['nx'])
        self.assertIn(f'romfs_program\t0\tEdition.nx:update/{VER1}/romfs', rows['nx'])
        self.assertIn(f'romfs_program\t1\tEdition.nx:program1/update/{VER1}/romfs', rows['nx'])
        self.assertIn(f'aoc\t1\tEdition.nx:addon/{DLC1:016x}/romfs', rows['nx'])
        strip = lambda rs: [r for r in rs if not r.startswith(('romfs\t', 'aoc\t', 'romfs_program\t', 'packer\t'))]
        self.assertEqual(strip(rows['nx']), strip(rows['folder']))
        self.assertIn('program\t1', rows['nx']); self.assertIn(f'application\t{APP0:016X}', rows['nx'])
        for m in ('main', 'subsdk0', 'sdk'):
            self.assertEqual((packs['nx'] / f'{m}.patched.bin').read_bytes(), (packs['folder'] / f'{m}.patched.bin').read_bytes(), m)
            self.assertEqual((packs['nx'] / f'{m}.android.key').read_text(), (packs['folder'] / f'{m}.android.key').read_text(), m)


def nca_files(n, sel, program=0):
    """The nca.Nca of each NCA (not a record) in the snapshot of package sel"""
    import nca
    s = n.select(sel, program).snapshot
    return [nca.Nca(n._file_storage(k), name) for name, k in sorted(n._root_kids(s.root).items()) if name.endswith('.nca') and not name.endswith('.cnmt.nca')]


def section_data(n, sel, content_type, patched=None):
    """(the NCA, its RomFS section's data storage) of the first NCA of content_type in sel's snapshot (patched: only a patched / unpatched one)"""
    ncas = nca_files(n, sel)
    for c in ncas:
        i = c.first(0)
        if c.content_type != content_type or i is None or (patched is not None and c.patched(i) != patched): continue
        base = None
        if c.patched(i): base = next(b for b in ncas if b is not c and b.content_type == content_type and b.index == c.index and not b.patched(b.first(0)))
        return c, c.data(i, base.raw(base.first(0)) if base else None)[1]
    raise AssertionError(f'no such NCA in {sel}')


class NcaSnapshots(unittest.TestCase):
    """android/NX.md "NCA snapshots": archives whose snapshots are plain NCAs (nx_pack.py --nca) read into the same package trees as the folder's"""
    @classmethod
    def setUpClass(cls):
        cls.td = tempfile.TemporaryDirectory()
        cls.root = Path(cls.td.name).resolve()
        cls.dump = cls.root / 'Game'; nx_dump(cls.dump)
        (cls.root / 'nca').mkdir(); (cls.root / 'nca3').mkdir(); (cls.root / 'comp').mkdir()
        cls.nx = write_nx(cls.dump, cls.root / 'nca' / 'Game.nx', '--nca')
        cls.nca3 = write_nx(cls.dump, cls.root / 'nca3' / 'Game.nx', '--nca', '--nca-magic', 'NCA3', '--codec', 'raw')
        cls.base_only = cls.root / 'BaseOnly'; shutil.copytree(cls.dump, cls.base_only, ignore=shutil.ignore_patterns('updates'))
        (cls.base_only / 'base/romfs/zeros.bin').write_bytes(bytes(3 * 65536))   # whole blocks of zeros: compression type 1
        cls.comp = write_nx(cls.base_only, cls.root / 'comp' / 'Game.nx', '--nca', '--nca-compress')

    @classmethod
    def tearDownClass(cls): cls.td.cleanup()

    def test_packages_and_trees(self):
        for path in (self.nx, self.nca3):
            n = nx.Nx(path)
            self.assertEqual([(p.selector, p.title_id, p.version) for p in n.packages], [('base', TID, 0), (f'update/{VER}', TID, VER), (f'addon/{DLC:016x}', DLC, 0)])
            self.assertEqual(n.program_indexes(), [0, 1])   # the update's program1/ folder: a Program NCA of ID offset 1
            for sel, folder in (('base', 'base'), (f'update/{VER}', f'updates/{VER}'), (f'addon/{DLC:016x}', 'dlc/040074a01bf13001/0'),
                                (f'program1/update/{VER}', f'updates/{VER}/program1')):
                for sub in ('exefs', 'romfs'):
                    if not (self.dump / folder / sub).is_dir(): continue
                    want = {k: v for k, v in tree(self.dump / folder / sub).items() if not k.endswith('.listing')}
                    with self.subTest(path=path.parent.name, sel=sel, sub=sub): self.assertEqual(tree(romfs.Path(f'{path}:{sel}/{sub}')), want)
            self.assertFalse(romfs.Path(f'{path}:addon/{DLC:016x}/exefs').exists())
            self.assertTrue(romfs.Path(f'{path}:base/data/control.nacp').is_file())   # the control NCA's RomFS
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nx.py'), str(path), 'verify'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_patched_romfs(self):
        import nca
        n = nx.Nx(self.nx)
        c, st = section_data(n, f'update/{VER}', nca.PROGRAM, patched=True)
        ind = st.base
        self.assertIsInstance(ind, nca.Indirect)
        self.assertEqual({e[2] for e in ind.e}, {0, 1})   # entries of the base's section and of the update's own data
        rnd = random.Random(3)
        for rel in ('Shader/big.bin', 'rnd.bin', 'repeat.bin', 'a.txt', 'only_in_update', 'Shader/big_copy.bin'):
            data = (self.dump / f'updates/{VER}/romfs' / rel).read_bytes()
            with self.subTest(rel), romfs.open(f'{self.nx}:update/{VER}/romfs/{rel}', 'rb', buffering=0) as f:
                for _ in range(30):
                    off = rnd.randrange(len(data) + 5); k = rnd.choice((1, 7, 4096, 65536, 100000))
                    f.seek(off); self.assertEqual(f.read(k), data[off:off + k])
                f.seek(0); self.assertEqual(f.read(), data)
                with romfs.mmap.mmap(f.fileno(), 0, access=romfs.mmap.ACCESS_READ) as m: self.assertEqual(m[:], data)
        self.assertEqual(romfs.Path(f'{self.nx}:base/romfs/a.txt').read_text(), 'version 0\n' * 100)   # the base's own, unpatched

    def test_compressed(self):
        import nca
        n = nx.Nx(self.comp)
        _, st = section_data(n, 'base', nca.PROGRAM)
        self.assertIsInstance(st, nca.Compressed); self.assertEqual({e[2] for e in st.e}, {0, 1, 3})   # none, zeros and LZ4 blocks
        _, st = section_data(n, f'addon/{DLC:016x}', nca.DATA); self.assertIsInstance(st, nca.Compressed)
        for sel, folder in (('base', 'base'), (f'addon/{DLC:016x}', 'dlc/040074a01bf13001/0')):
            self.assertEqual(tree(romfs.Path(f'{self.comp}:{sel}/romfs')), tree(self.base_only / folder / 'romfs'))
        data = (self.base_only / 'base/romfs/Shader/big.bin').read_bytes()
        rnd = random.Random(5)
        with romfs.open(f'{self.comp}:base/romfs/Shader/big.bin', 'rb', buffering=0) as f:
            for _ in range(40):
                off = rnd.randrange(len(data)); k = rnd.choice((3, 70000, 131072))
                f.seek(off); self.assertEqual(f.read(k), data[off:off + k])

    def test_lz4_and_storages(self):
        import nca, nca_pack
        try: import lz4.block
        except ImportError: lz4 = None   # (the module's own blocks are checked too when it is there)
        rnd = random.Random(9)
        for b in (b'', b'a', bytes(70000), b'abc' * 5000, rnd.randbytes(5000) + b'xyz' * 3000 + rnd.randbytes(100), bytes(range(256)) * 300, b'q' * 300 + rnd.randbytes(9)):
            c = nca_pack.lz4_compress(b)
            self.assertEqual(nca.lz4_block(c, len(b)), b)
            if len(b) > 1000 and b.count(b[:3]) > 50: self.assertLess(len(c), len(b) // 2)   # it really compresses repeats
            if lz4:
                self.assertEqual(lz4.block.decompress(c, uncompressed_size=len(b)), b)   # the writer's blocks are LZ4 to the module
                self.assertEqual(nca.lz4_block(lz4.block.compress(b, store_size=False), len(b)), b)
        self.assertEqual(nca.lz4_block(nca_pack.lz4_literals(b'hello' * 20), 100), b'hello' * 20)
        with self.assertRaisesRegex(nca.Error, 'LZ4'): nca.lz4_block(b'\x1f\x00\x05\x00', 100)   # an offset before the start
        base, own = nca.Mem(bytes(range(256)) * 4), nca.Mem(b'X' * 64)
        ind = nca.Indirect([(0, 0, 0), (16, 0, 1), (48, 100, 0)], 300, [base, own], 'test')
        want = bytes(range(16)) + b'X' * 32 + (bytes(range(256)) * 4)[100:352]
        self.assertEqual(ind.read(0, 300), want); self.assertEqual(ind.read(10, 50), want[10:60])

    def test_refused_ncas(self):
        import nca, nca_pack
        fh, sec = nca_pack.pfs_section(nca_pack.pfs0([('a', b'x')]))
        good = bytearray(nca_pack.nca(nca_pack.PROGRAM, TID, [(fh, sec)]))
        nca.Nca(nca.Mem(bytes(good)), 'good')
        def fixed(d, at, v):   # an FS header byte changed and its SHA-256 written again: the check after the hash refuses
            d = bytearray(d); d[0x400 + at] = v; d[0x280:0x2A0] = hashlib.sha256(bytes(d[0x400:0x600])).digest(); return bytes(d)
        cases = [('magic', bytes(good[:0x200]) + b'NCA2' + bytes(good[0x204:]), 'no plain NCA header'),
                 ('hash', bytes(good[:0x404]) + b'\x03' + bytes(good[0x405:]), 'does not match its SHA-256'),
                 ('encrypted', fixed(good, 4, 3), 'encrypted'), ('sparse', fixed(good, 0x150, 1), 'sparse'), ('short', bytes(good[:0x800]), 'shorter')]
        for name, data, msg in cases:
            with self.subTest(name), self.assertRaisesRegex(nca.Error, msg): nca.Nca(nca.Mem(data), name)

    def test_tools(self):
        app, vers, dlc = pack_dump.scan(self.nx)
        self.assertEqual((app, {v: posix(p) for v, p in vers.items()}, {t: posix(p) for t, p in dlc.items()}),
                         (TID, {VER: f'{self.nx}:update/{VER}'}, {DLC: f'{self.nx}:addon/{DLC:016x}/romfs'}))
        self.assertEqual(posix(pack_dump.program_dir(self.nx, 1)[1]), f'{self.nx}:program1/update/{VER}')
        out = self.root / 'packed'; self.addCleanup(shutil.rmtree, out, True)
        for tag, src in (('folder', self.dump), ('nx', self.nx)):
            a, v, d = pack_dump.scan(src)
            pack_dump.pack_version(pack_dump.version_dir(src, v, VER), a, out / tag / 'v', lambda s: None)
            pack_dump.pack_dlc(a, d, out / tag / 'aoc', lambda s: None)
        for f in ('v/ExeFS/main', 'v/ExeFS/sdk', 'v/ExeFS/main.npdm', 'v/RomFS.img', 'aoc/1.img'):
            self.assertEqual((out / 'nx' / f).read_bytes(), (out / 'folder' / f).read_bytes(), f)

    def test_pack_android_same_pack(self):
        try: import capstone, lz4.block   # noqa: F401
        except ImportError: self.skipTest('no capstone / lz4 (android/environment.yml)')
        import pack_android
        def fake_rewrite(py, srcs, out, jobs, g, in_process=False):
            for m, src in srcs.items():
                (out / f'{m}.patched.bin').write_bytes(bytes(nso.read(str(pack_android.rewriter_src(src, out, m))).image)); (out / f'{m}.android.key').write_text(pack_android.rewrite_key(src, g) + '\n')
        packs = {}
        for tag, src in (('folder', self.dump), ('nx', self.nx)):
            out = self.root / f'pack-{tag}'; self.addCleanup(shutil.rmtree, out, True)
            argv = ['pack_android.py', str(src), '--out', str(out), '--no-shaders', '--in-process']
            with mock.patch.object(sys, 'argv', argv), mock.patch.object(pack_android, 'rewrite', fake_rewrite), mock.patch('sys.stdout', io.TextIOWrapper(io.BytesIO(), encoding='utf-8')):
                pack_android.main()
            packs[tag] = out
        rows = {t: (p / 'pack.tsv').read_text().splitlines() for t, p in packs.items()}
        self.assertIn(f'romfs\tGame.nx:update/{VER}/romfs', rows['nx']); self.assertIn(f'aoc\t1\tGame.nx:addon/{DLC:016x}/romfs', rows['nx'])
        strip = lambda rs: [r for r in rs if not r.startswith(('romfs\t', 'aoc\t', 'packer\t'))]
        self.assertEqual(strip(rows['nx']), strip(rows['folder']))
        for m in ('main', 'sdk'):
            self.assertEqual((packs['nx'] / f'{m}.patched.bin').read_bytes(), (packs['folder'] / f'{m}.patched.bin').read_bytes())
            self.assertEqual((packs['nx'] / f'{m}.android.key').read_text(), (packs['folder'] / f'{m}.android.key').read_text())

    def test_edition_program_1(self):   # a console-layout Switch 2 Edition as NCAs: program 1 is the patch record's Program NCA of ID offset 1
        try: import capstone, lz4.block   # noqa: F401
        except ImportError: self.skipTest('no capstone / lz4 (android/environment.yml)')
        import pack_android
        src = self.root / 'Edition'; edition_dump(src)
        p = write_nx(src, self.root / 'Edition.nx', '--nca')
        n = nx.Nx(p)
        self.assertEqual([(q.selector, q.title_id) for q in n.program_packages(1)], [(f'update/{VER1}', P1), (f'addon/{DLC1:016x}', DLC1)])
        self.assertEqual(tree(romfs.Path(f'{p}:program1/update/{VER1}/romfs')), tree(src / f'updates/{VER1}/program/1/romfs'))
        self.assertEqual(posix(pack_dump.program_dir(p, 1)[1]), f'{p}:program1/update/{VER1}')
        def fake_rewrite(py, srcs, out, jobs, g, in_process=False):
            for m, s in srcs.items():
                (out / f'{m}.patched.bin').write_bytes(bytes(nso.read(str(pack_android.rewriter_src(s, out, m))).image)); (out / f'{m}.android.key').write_text(pack_android.rewrite_key(s, g) + '\n')
        packs = {}
        for tag, s in (('folder', src), ('nx', p)):
            out = self.root / f'ed-{tag}'; self.addCleanup(shutil.rmtree, out, True)
            argv = ['pack_android.py', str(s), '--program', '1', '--out', str(out), '--no-shaders', '--in-process']
            with mock.patch.object(sys, 'argv', argv), mock.patch.object(pack_android, 'rewrite', fake_rewrite), mock.patch('sys.stdout', io.TextIOWrapper(io.BytesIO(), encoding='utf-8')):
                pack_android.main()
            packs[tag] = out
        rows = {t: (q / 'pack.tsv').read_text().splitlines() for t, q in packs.items()}
        self.assertIn(f'romfs\tEdition.nx:program1/update/{VER1}/romfs', rows['nx']); self.assertIn(f'romfs_program\t0\tEdition.nx:update/{VER1}/romfs', rows['nx'])
        strip = lambda rs: [r for r in rs if not r.startswith(('romfs\t', 'aoc\t', 'romfs_program\t', 'packer\t'))]
        self.assertEqual(strip(rows['nx']), strip(rows['folder']))
        for m in ('main', 'subsdk0', 'sdk'): self.assertEqual((packs['nx'] / f'{m}.patched.bin').read_bytes(), (packs['folder'] / f'{m}.patched.bin').read_bytes(), m)


class Descriptor(Fixture):
    """An .nx read through a descriptor (DM_NUP_FD=<fd>:<path>), named by a stand-in path where nothing is, as a .nup"""
    def setUp(self):
        self.stand = self.root / 'picked' / '0123456789abcdef' / 'Game.nx'
        self.stand.parent.mkdir(parents=True, exist_ok=True)
        self.fd = os.open(self.nx, nup.O_RDONLY)
        self.addCleanup(os.close, self.fd)
        env = mock.patch.dict(os.environ, {'DM_NUP_FD': f'{self.fd}:{self.stand}'})
        env.start(); self.addCleanup(env.stop)

    def test_reads_through_the_descriptor(self):
        self.assertFalse(self.stand.exists())
        self.assertTrue(nup.is_nup(self.stand)); self.assertTrue(nx.is_nx(self.stand))
        n = nup.open_nup(self.stand)
        self.assertEqual(n.path, str(self.stand))
        for sel in ('base/romfs', f'update/{VER}/romfs', f'addon/{DLC:016x}/romfs'):
            self.assertEqual(tree(romfs.Path(f'{self.stand}:{sel}')), tree(romfs.Path(f'{self.nx}:{sel}')), sel)
        app, vers, dlc = pack_dump.scan(self.stand)
        self.assertEqual({t: posix(p) for t, p in dlc.items()}, {DLC: f'{self.stand}:addon/{DLC:016x}/romfs'})
        if hasattr(os, 'pread'): self.assertEqual(os.lseek(self.fd, 0, os.SEEK_CUR), 0)   # the descriptor's own position never moves (pread of duplicates; Windows has no pread)


class Writer(unittest.TestCase):
    def test_crc32c(self):
        self.assertEqual(nx._crc32c_py(b'123456789'), 0xE3069283)
        self.assertEqual(nx.crc32c(b''), 0)

    def test_raw_writer_needs_no_zstd(self):
        with tempfile.TemporaryDirectory() as t:
            src = Path(t) / 'g'; (src / 'base/exefs').mkdir(parents=True); (src / 'base/romfs').mkdir()
            (src / 'base/exefs/main.npdm').write_bytes(npdm(TID)); (src / 'base/romfs/a').write_bytes(b'x' * 70000)
            code = 'import sys, runpy; sys.modules["compression.zstd"] = None; sys.modules["backports"] = None; sys.argv = sys.argv[1:]; runpy.run_path(sys.argv[0], run_name="__main__")'
            r = subprocess.run([sys.executable, '-c', code, str(REPO / 'dm/tools/nx_pack.py'), str(src), str(Path(t) / 'g.nx'), '--codec', 'raw'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            n = nx.Nx(Path(t) / 'g.nx')
            self.assertEqual(n.read(n.node('base/romfs/a')), b'x' * 70000)


if __name__ == '__main__':
    unittest.main()
