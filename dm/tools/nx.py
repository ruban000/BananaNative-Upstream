#!/usr/bin/env python3
"""A reader of NX archives (`.nx`, github.com/fab918/nxformat docs/SPEC.md release 1.0.4, header versions (0,2) and (0,3)), the contract in android/NX.md.
The file is only ever read. An archive holds already-decrypted games: no keys are involved.

An archive is taken where a .nup is (dm/tools/nup.py opens a file whose magic is `NXAR` as an Nx, a subclass of nup.Nup), by the same names:
`<file>.nx:<selector>[/<path in the package>]`, split at the last `.nx:`, the selector being `base`, `update/<numeric_content_version>` or
`addon/<16 hex digits>`. A package is one snapshot of the application's main program (base 0, update 1, standalone update 3 as a base-less update) or an add-on
snapshot (DLC 2 with ADDON_TITLE_ID); its root is the snapshot's root directory, which holds `exefs/`, `romfs/`, `data/` and `program1`...`program15/` as a folder
dump's version folder does. Programs (android/NX.md "Programs"): every NxProgram of the game has its own packages, program n being the one whose ID's low
nibble is n and program 0 the one whose ID is the application's (else the lowest index); `<file>.nx:program<n>/<selector>[/<path>]` names program n's package
(program_packages(n)), a selector without it program 0's (packages, as nup.Nup's). Add-ons are the application's, the same under every program. romfs.py's Path / open / os / mmap take these paths, so pack_dump.py, pack_android.py, shader_split.py and romfs_image.py read them.

Open (Nx()) checks, before any value is used: both header slots (CRC-32C, versions, required feature bit 0 only, reserved bytes, section ranges; the valid slot with the
higher generation counts, an older one is tried when the newer one's metadata fails its hashes), then the SHA-256 of the Catalog, the Manifest and the block index with
the integrity table, then the structure the reader relies on: tables in range, names, sorted children, every tree walked without cycles or repeats, block references in
range, each file's block count and block sizes, the blocks' order and ranges. Refused with a message: snapshot type 4, an archive with more than one application
that has a game snapshot, unknown required features or Catalog extensions.

NCA snapshots (NXConvert's native import of a decrypted NSP or XCI: a root of `<content ID>.nca` and `<content ID>.cnmt.nca` files, android/NX.md "NCA
snapshots") give the same packages: the snapshot's own content meta record (application: base, patch: update/<version>, add-on: addon/<title ID>) and the NCAs it
names, each Program NCA (by its ID offset n) a package of program n with exefs/ (its PartitionFS), romfs/ (its RomFS, through the BKTR patch over the base's
Program NCA of the same index, and compressed storage) and data/ (the control NCA's RomFS); dm/tools/nca.py reads them. Their CNMTs and NCA headers are read at
open, the trees when first listed. A file of such a tree has no SHA-256 of its own: `verify` checks the NCA files.

Reads: a file is a list of blocks of 1 << chunk_size_log2 bytes. A RAW block is read with one positioned read of the bytes asked for, a ZSTD block (one frame, at most
1 MiB) is decoded whole and kept in a small least-recently-used cache. Slice CRC-32C checks on reads are off (CHECK_READS; pure-Python CRC-32C is slow); `verify`
checks every block's slices and every file's SHA-256. zstd is compression.zstd (Python 3.14), else backports.zstd, imported at the first ZSTD block.

A file used through a descriptor: DM_NUP_FD=<fd>:<path> as for a .nup (nup.fd_of / open_fd / stat).

Usage: nx.py <file>.nx [packages | ls [<name>] | cat <name> | verify [<name>]]   (exit 1 on a bad file; <name> is a selector with a path)
"""
import collections, errno, hashlib, io, os, re, stat as _stat, struct, sys, tempfile, threading
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nup
from nup import pread, open_fd, NONE, BASE, UPDATE, ADDON

MAGIC = b'NXAR'
PAGE = 4096
DATA_START = 8192
HEADER = struct.Struct('<4sHHIHHQQQQQQQQQQQQQ32s32s32sQI24sI')   # NxHeader, 256 bytes (SPEC §3)
CATALOG = struct.Struct('<4sHHQIIIIIIIIQQQQQQQQ16s')            # NxCatalogHeader, 128 (§4)
APP = struct.Struct('<QBBHIIIIIIIIIIIIIII8s')                   # NxApplication, 80
PROGRAM = struct.Struct('<QIBBH16sIIII16s')                     # NxProgram, 64
SNAPSHOT = struct.Struct('<IBBHIIQII16sII32s32s')               # NxSnapshot, 120
EXTENSION = struct.Struct('<IIHHIQQ')                           # NxExtension, 32
MANIFEST = struct.Struct('<4sHHQIIIIQQQQQQ16s')                 # NxManifestHeader, 96 (§6)
DIRECTORY = struct.Struct('<IIII')                              # NxDirectory, 16
ENTRY = struct.Struct('<QIIBBHI')                               # NxDirectoryEntry, 24
FILE = struct.Struct('<QIIBBH32s12s')                           # NxFile, 64
BLOCK = struct.Struct('<QIIBBHI')                               # NxBlock, 24 (§7)
assert (HEADER.size, CATALOG.size, APP.size, PROGRAM.size, SNAPSHOT.size, EXTENSION.size, MANIFEST.size, DIRECTORY.size, ENTRY.size, FILE.size, BLOCK.size) == \
       (256, 128, 80, 64, 120, 32, 96, 16, 24, 64, 24)
VERSIONS = ((0, 2), (0, 3))
CHECK_SIZE = 16384
MiB = 1 << 20
T_BASE, T_UPDATE, T_DLC, T_STANDALONE, T_UNKNOWN = range(5)
TYPES = {T_BASE: 'base', T_UPDATE: 'update', T_DLC: 'DLC', T_STANDALONE: 'standalone update', T_UNKNOWN: 'unknown'}
F_ADDON_TITLE_ID = 2
EXT_SOURCE_RESOURCES, EXT_EXTRA_FILES = 0x00010008, 0x00010009
KNOWN_EXTENSIONS = {0x00010001, 0x00010002, 0x00010003, 0x00010004, 0x00010005, 0x00010006, EXT_SOURCE_RESOURCES, EXT_EXTRA_FILES}
RAW, ZSTD = 0, 1
MAX_DEPTH = 256
CACHE_BLOCKS = 16       # decoded ZSTD blocks kept per archive (at most 16 MiB)
CHECK_READS = False     # check each block read against its slices' CRC-32C (verify always does)
PROGRAM_SELECTOR = re.compile(r'program([0-9]+)/(.*)', re.S)   # `program<n>/<selector>[/<path>]`: program n's package (android/NX.md "Programs")


class Error(nup.Error): pass
class Unsupported(Error): pass   # a header slot that is valid but asks for what this reader does not know: reported, never passed over for the other slot


# ---- CRC-32C (Castagnoli, reflected 0x82f63b78): a C module when one is installed, else a table ----
def _table():
    t = []
    for i in range(256):
        c = i
        for _ in range(8): c = (c >> 1) ^ 0x82F63B78 if c & 1 else c >> 1
        t.append(c)
    return t
_T = _table()


def _crc32c_py(data, crc=0):
    c, t = crc ^ 0xFFFFFFFF, _T
    for b in data: c = t[(c ^ b) & 0xFF] ^ (c >> 8)
    return c ^ 0xFFFFFFFF


try:
    from crc32c import crc32c as _fast   # pip install crc32c
    def crc32c(data, crc=0): return _fast(bytes(data), crc)
except ImportError:
    try:
        import google_crc32c as _g
        def crc32c(data, crc=0): return _g.extend(crc, bytes(data))
    except ImportError: crc32c = _crc32c_py
assert crc32c(b'123456789') == 0xE3069283


def align(n, a=16): return (n + a - 1) // a * a


class Header:
    """One valid header slot"""
    def __init__(self, raw, slot, file_size):
        self.raw, self.slot = raw, slot
        (magic, self.major, self.minor, hsize, req, opt, self.archive_size, self.catalog_offset, self.catalog_size, self.media_offset, self.media_size,
         self.data_offset, self.data_size, self.manifest_offset, self.manifest_size, self.block_index_offset, self.block_count, self.integrity_offset,
         self.integrity_size, self.catalog_sha, self.manifest_sha, self.index_sha, self.generation, check, reserved, crc) = HEADER.unpack(raw)
        if magic != MAGIC: raise Error('not an NX archive (no NXAR magic)')
        if crc32c(raw[:252]) != crc: raise Error('header CRC-32C does not match (damaged)')
        self.check(hsize, req, opt, check, reserved, file_size)

    def check(self, hsize, req, opt, check, reserved, file_size):
        if (self.major, self.minor) not in VERSIONS:
            raise Unsupported(f'NX header version ({self.major},{self.minor}); this reader knows ' + ' and '.join(f'({a},{b})' for a, b in VERSIONS))
        if hsize != 256: raise Error(f'header size {hsize}, not 256')
        if req != 1: raise Unsupported(f'required features {req:#x}: this reader knows bit 0 (separate header pages) only, and it must be set')
        if opt: raise Unsupported(f'optional features {opt:#x}: none is defined')
        if check != CHECK_SIZE: raise Error(f'check size {check}, not {CHECK_SIZE}')
        if any(reserved): raise Error('reserved header bytes are not zero')
        if self.archive_size > file_size: raise Error(f'archive size {self.archive_size}, the file has {file_size} bytes (a copy cut off)')
        if self.archive_size > (1 << 63) - 1: raise Error('archive size above INT64_MAX')
        if not 128 <= self.catalog_size <= 16 * MiB: raise Error(f'catalog size {self.catalog_size} out of range')
        if not 96 <= self.manifest_size <= 4 << 30: raise Error(f'manifest size {self.manifest_size} out of range')
        if (self.media_offset == 0) != (self.media_size == 0) or (self.media_size and self.media_size < 32): raise Error('bad media range')
        if self.block_count > 1 << 32: raise Error(f'{self.block_count} blocks, above 2^32')
        if self.block_count:
            if self.data_offset != DATA_START or not self.data_size: raise Error('bad data range')
            if not self.block_index_offset or not self.integrity_offset or not self.integrity_size or self.integrity_size % 4: raise Error('bad block index or integrity range')
        elif self.data_offset or self.data_size or self.block_index_offset or self.integrity_offset or self.integrity_size:
            raise Error('no blocks, but a data, block index or integrity range')
        # the tail: Media, Catalog, Manifest, block index, integrity table, packed at 16-byte boundaries after the data region (SPEC §1)
        regions = [(n, o, s) for n, o, s in (('media', self.media_offset, self.media_size), ('catalog', self.catalog_offset, self.catalog_size),
                                             ('manifest', self.manifest_offset, self.manifest_size),
                                             ('block index', self.block_index_offset, self.block_count * BLOCK.size),
                                             ('integrity table', self.integrity_offset, self.integrity_size)) if o or s]
        end = DATA_START + self.data_size if self.block_count else DATA_START
        first = True
        for n, o, s in regions:
            if o % 16: raise Error(f'{n} offset {o:#x} is not 16-byte aligned')
            if (first and o < align(end)) or (not first and o != align(end)): raise Error(f'{n} at {o:#x} is not where the tail puts it (after {end:#x})')
            end, first = o + s, False
        if end != self.archive_size: raise Error(f'archive size {self.archive_size}, the tail ends at {end}')


class Snapshot:
    def __init__(self, i, row):
        (self.program_index, self.type, self.flags, r0, self.version, self.root, self.logical_size, dvo, dvl, self.content_id, self.first_dependency,
         self.dependency_count, self.identity, self.build_id) = row
        self.index, self.display_version_ref, self.reserved = i, (dvo, dvl), r0

    @property
    def addon_title_id(self): return struct.unpack_from('<Q', self.content_id)[0] if self.flags & F_ADDON_TITLE_ID else None


class Package(nup.Package):
    """A game package of the archive (nup.Package's fields): kind BASE / UPDATE / ADDON, flags 1 for a base-less update; snapshot is the NxSnapshot"""
    def __init__(self, i, title_id, root, exefs, romfs, version, kind, flags, snapshot):
        super().__init__(i, title_id, root, exefs, romfs, version, kind, flags)
        self.snapshot = snapshot


class Nx(nup.Nup):
    """An NX archive as nup.Nup's tree: nodes are directory IDs (< nd), nd + a file ID, or the container's virtual folders ('', 'update', 'addon', ('pkg', i))"""
    synthetic = False
    uuid = bytes(16)
    programs_inside = True   # a snapshot holds the game's other programs as program<n>/ (pack_dump.program_dir)

    def __init__(self, path):
        self.path = os.path.abspath(path)
        self._cache, self._lock, self._v = collections.OrderedDict(), threading.Lock(), []
        fd = open_fd(self.path)
        try:
            st = os.fstat(fd); self.stat = st
            if not _stat.S_ISREG(st.st_mode): raise Error(f'{path}: not a file')
            self.size = st.st_size
            slots, errs = [], []
            for name, off in (('A', 0), ('B', PAGE)):
                raw = pread(fd, 256, off)
                if len(raw) < 256: errs.append(f'slot {name}: cut off'); continue
                try: slots.append(Header(raw, name, st.st_size))
                except Unsupported as e: raise Error(f'{path}: header slot {name}: {e}') from None   # valid, but not for this reader
                except Error as e: errs.append(f'slot {name}: {e}')
            if not slots:
                if all('no NXAR magic' in e for e in errs): raise Error(f'{path}: not an NX archive')
                raise Error(f'{path}: no valid header slot ({"; ".join(errs)})')
            if len(slots) == 2 and slots[0].generation == slots[1].generation:
                if slots[0].raw != slots[1].raw: raise Error(f'{path}: both header slots have generation {slots[0].generation} but differ')
                slots = slots[:1]
            slots.sort(key=lambda h: -h.generation)
            failed = []
            for h in slots:   # the newest generation whose metadata passes its hashes (SPEC §1: an older one when the newer one's is damaged)
                try: self._mount(fd, h); break
                except Error as e: failed.append(f'generation {h.generation} (slot {h.slot}): {e}')
            else: raise Error(f'{path}: {"; ".join(failed)}')
            self.header = h
        finally: os.close(fd)

    # ---- open: metadata ----
    def _mount(self, fd, h):
        cat = pread(fd, h.catalog_size, h.catalog_offset)
        man = pread(fd, h.manifest_size, h.manifest_offset)
        idx = pread(fd, h.block_count * BLOCK.size, h.block_index_offset) if h.block_count else b''
        integ = pread(fd, h.integrity_size, h.integrity_offset) if h.block_count else b''
        if len(cat) != h.catalog_size or len(man) != h.manifest_size or len(idx) != h.block_count * BLOCK.size or len(integ) != h.integrity_size:
            raise Error('the tail is cut off')
        if hashlib.sha256(cat).digest() != h.catalog_sha: raise Error('the Catalog does not match its SHA-256 (damaged)')
        if hashlib.sha256(man).digest() != h.manifest_sha: raise Error('the Manifest does not match its SHA-256 (damaged)')
        if hashlib.sha256(idx + integ).digest() != h.index_sha: raise Error('the block index does not match its SHA-256 (damaged)')
        if h.media_size:
            media = pread(fd, 32, h.media_offset)
            if len(media) < 32 or media[:4] != b'NXMD' or struct.unpack_from('<HHQ', media, 4) != (1, 32, h.media_size): raise Error('bad Media region header')
        self.h = h
        self._blocks(idx, integ, h)
        self._manifest(man, h)
        self._catalog(cat, h)
        self._trees()
        self._packages()

    def _blocks(self, idx, integ, h):
        self.blocks, self.checks = [], struct.unpack(f'<{len(integ) // 4}I', integ)
        end, data_end, need = DATA_START, DATA_START + h.data_size, 0
        for i, (off, stored, logical, codec, flags, r0, first) in enumerate(BLOCK.iter_unpack(idx)):
            if off % 16 or off < align(end) or off + stored > data_end: raise Error(f'block {i} at {off:#x} out of order or out of the data region')
            if not stored or stored > MiB + (64 << 10) or not 1 <= logical <= MiB: raise Error(f'block {i}: bad sizes ({stored} stored, {logical} logical)')
            if codec not in (RAW, ZSTD) or flags or r0: raise Error(f'block {i}: unknown codec {codec} or flags')
            if codec == RAW and stored != logical: raise Error(f'block {i}: RAW with {stored} stored and {logical} logical bytes')
            if first != need: raise Error(f'block {i}: first check {first}, expected {need}')
            need += (logical + CHECK_SIZE - 1) // CHECK_SIZE
            self.blocks.append((off, stored, logical, codec, first))
            end = off + stored
        if self.blocks and end != data_end: raise Error(f'the data region ends at {data_end:#x}, its last block at {end:#x}')
        if need * 4 != h.integrity_size: raise Error(f'integrity table of {h.integrity_size} bytes, the blocks need {need * 4}')

    @staticmethod
    def _table(name, data, off, count, size, a, lo, cat=''):
        """The bytes of a table of count records of size bytes at off of a section (data), checked: aligned to a, at or after lo, inside it"""
        if not count:
            if off: raise Error(f'{cat}{name}: no records, offset {off:#x} (must be zero)')
            return b'', lo
        if off % a or off < lo or off + count * size > len(data): raise Error(f'{cat}{name} table out of range')
        return data[off:off + count * size], off + count * size

    def _manifest(self, man, h):
        (magic, ver, hsize, total, nd, nf, ne, nr, dir_off, file_off, ent_off, ref_off, pool_off, pool_size, reserved) = MANIFEST.unpack_from(man)
        if magic != b'NXMF' or ver != 1 or hsize != 96 or total != h.manifest_size or any(reserved): raise Error('bad Manifest header')
        if not 1 <= nd <= 1 << 24 or nf > 1 << 25 or ne > 50331648: raise Error('Manifest counts out of range')
        if not dir_off: raise Error('Manifest has no directory table')
        dirs, lo = self._table('directory', man, dir_off, nd, 16, 8, 96, 'Manifest ')
        files, lo = self._table('file', man, file_off, nf, 64, 8, lo, 'Manifest ')
        ents, lo = self._table('entry', man, ent_off, ne, 24, 8, lo, 'Manifest ')
        refs, lo = self._table('block reference', man, ref_off, nr, 4, 4, lo, 'Manifest ')
        if pool_off % 8 or pool_off < lo or pool_off + pool_size > len(man) or pool_size > 2 << 30: raise Error('Manifest path pool out of range')
        self.nd, self.nf = nd, nf
        self.dirs = []
        cursor = 0
        for d, (first, count, flags, r) in enumerate(DIRECTORY.iter_unpack(dirs)):
            if flags or r: raise Error(f'directory {d}: unknown flags')
            if first != cursor: raise Error(f'directory {d}: entries do not partition the entry table')
            cursor += count; self.dirs.append((first, count))
        if cursor != ne: raise Error('directory entry ranges do not cover the entry table')
        self.entries = []   # (name bytes, node)
        for e, (no, nl, target, kind, flags, r0, r1) in enumerate(ENTRY.iter_unpack(ents)):
            if flags or r0 or r1: raise Error(f'entry {e}: unknown flags')
            if not 1 <= nl <= 4096 or no < pool_off or no + nl > pool_off + pool_size: raise Error(f'entry {e}: name out of the path pool')
            name = man[no:no + nl]
            if b'\0' in name or b'/' in name or b'\\' in name or name in (b'.', b'..'): raise Error(f'entry {e}: bad name {name!r}')
            try: name.decode('utf-8')
            except UnicodeDecodeError: raise Error(f'entry {e}: name {name!r} is not UTF-8') from None
            if kind == 1:
                if target >= nd: raise Error(f'entry {e}: directory {target} out of range')
                node = target
            elif kind == 2:
                if target >= nf: raise Error(f'entry {e}: file {target} out of range')
                node = nd + target
            else: raise Error(f'entry {e}: unknown kind {kind}')
            self.entries.append((name, node))
        for d, (first, count) in enumerate(self.dirs):
            for i in range(first + 1, first + count):
                if self.entries[i - 1][0] >= self.entries[i][0]: raise Error(f'directory {d}: names unsorted or not unique ({self.entries[i - 1][0]!r}, {self.entries[i][0]!r})')
        self.refs = struct.unpack(f'<{nr}I', refs)
        self.files = []   # (size, first reference, block count, sha256, chunk_size_log2)
        cursor, nb = 0, len(self.blocks)
        for f, (size, first, count, log2, flags, r0, sha, r1) in enumerate(FILE.iter_unpack(files)):
            if flags or r0 or any(r1): raise Error(f'file {f}: unknown flags')
            if not 16 <= log2 <= 20: raise Error(f'file {f}: chunk size 2^{log2}')
            if size > (1 << 63) - 1: raise Error(f'file {f}: size above INT64_MAX')
            chunk = 1 << log2
            if count != (size + chunk - 1) // chunk: raise Error(f'file {f}: {count} blocks for {size} bytes in chunks of {chunk}')
            if first != cursor: raise Error(f'file {f}: block references do not partition the reference table')
            cursor += count
            if cursor > nr: raise Error(f'file {f}: block references out of range')
            for j in range(count):
                b = self.refs[first + j]
                if b >= nb: raise Error(f'file {f}: block {b} out of range')
                want = chunk if j < count - 1 else size - chunk * (count - 1)
                if self.blocks[b][2] != want: raise Error(f'file {f}: block {b} holds {self.blocks[b][2]} bytes, its place in the file {want}')
            self.files.append((size, first, count, sha, log2))
        if cursor != nr: raise Error('file block references do not cover the reference table')

    def _string(self, cat, pool, off, ln, what):
        if off == NONE and ln == 0: return None
        if not ln or off + ln > len(pool): raise Error(f'{what}: string out of the pool')
        try: return pool[off:off + ln].decode('utf-8')
        except UnicodeDecodeError: raise Error(f'{what}: not UTF-8') from None

    def _catalog(self, cat, h):
        (magic, ver, hsize, total, flags, na, np_, ns, nl, ndep, nx, r0, app_off, prog_off, snap_off, loc_off, dep_off, ext_off, pool_off, pool_size,
         r1) = CATALOG.unpack_from(cat)
        if magic != b'NXCT' or ver != 1 or hsize != 128 or total != h.catalog_size or flags or r0 or any(r1): raise Error('bad Catalog header')
        if not 1 <= na <= 4096 or np_ > 16384 or not 1 <= ns <= 65536 or nl > 16384 or ndep > 32768 or nx > 4096: raise Error('Catalog counts out of range')
        apps, lo = self._table('application', cat, app_off, na, 80, 8, 128, 'Catalog ')
        progs, lo = self._table('program', cat, prog_off, np_, 64, 8, lo, 'Catalog ')
        snaps, lo = self._table('snapshot', cat, snap_off, ns, 120, 8, lo, 'Catalog ')
        _, lo = self._table('localized entry', cat, loc_off, nl, 24, 8, lo, 'Catalog ')
        _, lo = self._table('dependency', cat, dep_off, ndep, 56, 8, lo, 'Catalog ')
        exts, lo = self._table('extension', cat, ext_off, nx, 32, 8, lo, 'Catalog ')
        if pool_off % 8 or pool_off < lo or pool_off + pool_size > len(cat): raise Error('Catalog string pool out of range')
        pool = cat[pool_off:pool_off + pool_size]
        self.snapshots = [Snapshot(i, row) for i, row in enumerate(SNAPSHOT.iter_unpack(snaps))]
        for s in self.snapshots:
            if s.type > T_UNKNOWN: raise Error(f'snapshot {s.index}: type {s.type}')
            if s.flags & ~3 or s.reserved: raise Error(f'snapshot {s.index}: unknown flags {s.flags:#x}')
            if s.program_index != NONE and s.program_index >= np_: raise Error(f'snapshot {s.index}: program {s.program_index} out of range')
            if s.program_index == NONE and s.type != T_DLC: raise Error(f'snapshot {s.index}: a {TYPES[s.type]} without a program')
            if s.flags & F_ADDON_TITLE_ID and (s.type != T_DLC or s.program_index != NONE or not s.addon_title_id or any(s.content_id[8:])):
                raise Error(f'snapshot {s.index}: bad add-on title ID')
            if s.root >= self.nd: raise Error(f'snapshot {s.index}: root directory {s.root} out of range')
            if s.dependency_count and s.first_dependency + s.dependency_count > ndep: raise Error(f'snapshot {s.index}: dependencies out of range')
            self._string(cat, pool, *s.display_version_ref, f'snapshot {s.index} display version')
        self.programs = []
        for i, (pid, app, kind, pflags, r0, cid, first, count, npdm, r1, r2) in enumerate(PROGRAM.iter_unpack(progs)):
            if not pid or app >= na or kind > 2 or pflags & ~1 or r0 or r1 or any(r2): raise Error(f'program {i}: bad record')
            if not count or first + count > ns: raise Error(f'program {i}: snapshots out of range')
            if any(self.snapshots[j].program_index != i for j in range(first, first + count)): raise Error(f'program {i}: its snapshots name another program')
            self.programs.append((pid, app, kind, first, count))
        self.apps = []
        cursor = 0
        for i, row in enumerate(APP.iter_unpack(apps)):
            (aid, platform, aflags, r0, pfirst, pcount, sfirst, scount, to, tl, po, pl, icon, lfirst, lcount, nacp, ofirst, ocount, meta, r1) = row
            if not aid or aflags & ~3 or r0 or any(r1) or platform > 2: raise Error(f'application {i}: bad record')
            if sfirst != cursor or not scount or sfirst + scount > ns: raise Error(f'application {i}: snapshots do not partition the snapshot table')
            cursor += scount
            if pcount and (pfirst + pcount > np_ or any(self.programs[p][1] != i for p in range(pfirst, pfirst + pcount))): raise Error(f'application {i}: programs out of range')
            if meta != NONE and not sfirst <= meta < sfirst + scount: raise Error(f'application {i}: metadata snapshot out of range')
            title, publisher = self._string(cat, pool, to, tl, f'application {i} title'), self._string(cat, pool, po, pl, f'application {i} publisher')
            self.apps.append(dict(id=aid, platform=platform, programs=range(pfirst, pfirst + pcount) if pcount else range(0),
                                  snapshots=range(sfirst, sfirst + scount), title=title, publisher=publisher, icon=icon))
        if cursor != ns: raise Error('application snapshot ranges do not cover the snapshot table')
        self.resource_roots, self.extra_root, seen = [], None, set()
        for i, (tid, eflags, owner, r0, oidx, doff, dsize) in enumerate(EXTENSION.iter_unpack(exts)):
            if eflags & ~1 or r0 or owner > 3: raise Error(f'Catalog extension {i}: bad record')
            if doff < pool_off + pool_size and dsize or doff + dsize > len(cat): raise Error(f'Catalog extension {tid:#010x}: payload out of range')
            if (tid, owner, oidx) in seen: raise Error(f'Catalog extension {tid:#010x}: repeated')
            seen.add((tid, owner, oidx))
            if tid not in KNOWN_EXTENSIONS:
                if eflags & 1: raise Error(f'requires Catalog extension {tid:#010x}, which this reader does not know (a newer NXFormat writer)')
                continue
            data = cat[doff:doff + dsize]
            if tid == EXT_SOURCE_RESOURCES:
                if len(data) < 16 or data[:4] != b'NXSR': raise Error('bad Source Resources extension')
                n = struct.unpack_from('<I', data, 8)[0]
                if len(data) < 16 + 8 * n: raise Error('Source Resources extension cut off')
                self.resource_roots += [struct.unpack_from('<II', data, 16 + 8 * k)[1] for k in range(n)]
            elif tid == EXT_EXTRA_FILES:
                if len(data) != 16 or data[:4] != b'NXEX': raise Error('bad Extra Files extension')
                self.extra_root = struct.unpack_from('<I', data, 8)[0]
        has_resources = any(t == EXT_SOURCE_RESOURCES for t, _, _ in seen)
        if (h.minor == 3) != has_resources: raise Error(f'header version (0,{h.minor}) ' + ('without' if h.minor == 3 else 'with') + ' Source Resources')
        if any(r >= self.nd for r in self.resource_roots + ([self.extra_root] if self.extra_root is not None else [])): raise Error('extension root directory out of range')

    def _trees(self):
        """Walks every snapshot's tree (and the resource and extra-file trees): no cycle, no node twice in one tree, depth at most 256, the snapshot's logical size"""
        self._names = {}
        roots = [(s.root, s) for s in self.snapshots] + [(r, None) for r in self.resource_roots] + ([(self.extra_root, None)] if self.extra_root is not None else [])
        for root, s in roots:
            seen, total, stack = {root}, 0, [(root, 0)]
            while stack:
                d, depth = stack.pop()
                if depth > MAX_DEPTH: raise Error(f'directory {d}: deeper than {MAX_DEPTH}')
                first, count = self.dirs[d]
                for name, node in self.entries[first:first + count]:
                    if node in seen: raise Error(f'{"snapshot %d" % s.index if s else "a resource tree"}: {"directory" if node < self.nd else "file"} {name!r} reached twice (a cycle or a repeat)')
                    seen.add(node); self._names.setdefault(node, name)
                    if node < self.nd: stack.append((node, depth + 1))
                    else: total += self.files[node - self.nd][0]
            if s is not None and total != s.logical_size: raise Error(f'snapshot {s.index}: its files hold {total} bytes, its record says {s.logical_size}')

    # ---- packages ----
    def _root_kids(self, d):
        first, count = self.dirs[d]
        return dict((n.decode(), k) for n, k in self.entries[first:first + count])

    def _nca_tree(self, s):
        """Whether snapshot s is NCA files at its root (NXConvert's native import of a decrypted NSP or XCI: android/NX.md "NCA snapshots")"""
        kids = self._root_kids(s.root)
        return 'exefs' not in kids and 'romfs' not in kids and any(n.lower().endswith(('.nca', '.ncz')) for n in kids)

    # ---- NCA snapshots: their NCAs read through dm/tools/nca.py into the same package trees (exefs/, romfs/, data/) ----
    def _file_storage(self, k):
        nx_, node = self, k
        class FileStorage:   # a file of the archive as an nca.py storage; each read opens its own descriptor (none is held: Windows could not delete the file)
            size = self.record(k)[0]
            def read(_, off, n):
                fd = open_fd(nx_.path)
                try: return nx_.read_range(node, off, n, fd)
                finally: os.close(fd)
        return FileStorage()

    def _vdir(self, name, kids=None, load=None):
        self._v.append(('d', name, kids, load)); return ('v', len(self._v) - 1)

    def _vfile(self, name, st, off, size):
        self._v.append(('f', name, (st, off, size), None)); return ('v', len(self._v) - 1)

    def _vtree(self, name, tree):
        """A virtual directory of an nca.py tree {name: dict | (storage, offset, size)}"""
        kids = {n: self._vtree(n, v) if isinstance(v, dict) else self._vfile(n, *v) for n, v in tree.items()}
        return self._vdir(name, kids)

    def _vkids(self, k):
        kind, name, kids, load = self._v[k[1]]
        if kids is None:
            kids = load(); self._v[k[1]] = (kind, name, kids, None)
        return kids

    def _nca_packages(self, s):
        """[(program index or None for an add-on, Package)] of NCA snapshot s: its own content meta record (application for a base, patch for an update, add-on
        for a DLC) and the NCAs it names, each read when its tree is first listed"""
        import nca
        what = f'snapshot {s.index} ({TYPES[s.type]}' + (f' {s.version}' if s.type in (T_UPDATE, T_STANDALONE) else '') + ')'
        if s.type == T_UNKNOWN: raise Error(f'{what} has type 4 (unknown): this reader takes base, update, standalone update and DLC snapshots')
        kids = self._root_kids(s.root)
        if any(n.lower().endswith('.ncz') for n in kids): raise Error(f'{what}: an NCZ (compressed NCA container), which is not read; convert from the NSP')
        ncas = {n.lower(): k for n, k in kids.items() if n.lower().endswith('.nca') and k >= self.nd}
        def open_nca(name):
            return nca.Nca(self._file_storage(ncas[name]), f'{what}: {name}')
        recs = []
        try:
            for n in sorted(ncas):
                if n.endswith('.cnmt.nca'): recs.append((n, nca.record(open_nca(n))))
            want = {T_BASE: nca.APPLICATION, T_UPDATE: nca.PATCH, T_STANDALONE: nca.PATCH, T_DLC: nca.ADDON}[s.type]
            own = [r for _, r in recs if r.type == want]
            if not own: raise Error(f'{what}: no {({nca.APPLICATION: "application", nca.PATCH: "patch", nca.ADDON: "add-on"})[want]} content meta record (*.cnmt.nca)')
            def named(r, c):
                name = c[0] + '.nca'
                if name not in ncas: raise Error(f'{what}: {r.name} names {name}, which is not in the snapshot')
                return name
            for r in own:
                for c in r.contents:
                    if c[2] != nca.R_DELTA: named(r, c)   # every content the record needs is there (a delta fragment is not needed)
            out = []
            if s.type == T_DLC:
                for r in own:
                    data = [c for c in r.contents if c[2] == nca.R_DATA]
                    if len(data) != 1: raise Error(f'{what}: {r.name}: {len(data)} data contents, expected one')
                    out.append((None, self._nca_package(s, r.id, r.version, ADDON, 0, open_nca(named(r, data[0])), None, None)))
                return out
            if len(own) > 1: raise Error(f'{what}: {len(own)} content meta records of its kind ({", ".join(r.name for r in own)})')
            r = own[0]
            base = [b for _, b in recs if b.type == nca.APPLICATION] if s.type != T_BASE else []
            control = [c for c in r.contents if c[2] == nca.R_CONTROL]
            ctl = open_nca(named(r, control[0])) if control else None
            for c in sorted((c for c in r.contents if c[2] == nca.R_PROGRAM), key=lambda c: c[3]):
                prog = open_nca(named(r, c))
                if prog.content_type != nca.PROGRAM: raise Error(f'{prog.name}: content type {prog.content_type}, its record says Program')
                b = None
                for br in base:
                    hit = [x for x in br.contents if x[2] == nca.R_PROGRAM and x[3] == c[3]]
                    if hit: b = open_nca(named(br, hit[0])); break
                kind = BASE if s.type == T_BASE else UPDATE
                out.append((c[3], self._nca_package(s, prog.program_id, r.version, kind, 1 if s.type == T_STANDALONE else 0, prog, b, ctl)))
            if not out: raise Error(f'{what}: {r.name} names no Program NCA')
            return out
        except nca.Error as e: raise Error(str(e)) from None

    def _nca_package(self, s, title_id, version, kind, flags, main, base, control):
        """A Package whose tree is read from NCAs: main (a Program or data NCA), base (the base's Program NCA, for a patched RomFS), control"""
        import nca
        def romfs_of(n, b):
            i = n.first(0)
            if i is None: return None
            braw = None
            if n.patched(i):
                if b is None: raise Error(f'{n.name}: a patched RomFS with no base Program NCA of its program in the snapshot')
                bi = b.first(0)
                if bi is None: raise Error(f'{b.name}: no RomFS section for the patch to read')
                braw = b.raw(bi)
            return lambda: nca.romfs(n.data(i, braw)[1], f'{n.name}: RomFS')
        def load(fn):
            def go():
                try: return self._vtree('', fn())   # a virtual directory's children: the tree's top level
                except nca.Error as e: raise Error(str(e)) from None
            return lambda: self._vkids(go())
        kids = {}
        if kind != ADDON:
            pi = main.first(1)
            if pi is not None: kids['exefs'] = self._vdir('exefs', load=load(lambda: nca.pfs(main.data(pi)[1], f'{main.name}: ExeFS')))
            if control is not None and (r := romfs_of(control, None)): kids['data'] = self._vdir('data', load=load(r))
        r = romfs_of(main, base)
        if r: kids['romfs'] = self._vdir('romfs', load=load(r))
        root = self._vdir('', kids)
        p = Package(len(self.all_packages), title_id, root, kids.get('exefs', NONE), kids.get('romfs', NONE), version,
                    kind, flags, s)
        p.program = None   # set by _packages from the Program NCA's ID offset
        self.all_packages.append(p)
        return p

    def _packages(self):
        game = [a for a in self.apps if any(self.snapshots[j].type in (T_BASE, T_UPDATE, T_STANDALONE) for j in a['snapshots'])]
        if len(game) > 1:
            raise Error('a collection of ' + str(len(game)) + ' games (' + ', '.join(f'{a["id"]:016X}' + (f' {a["title"]}' if a['title'] else '') for a in game)
                        + '): this reader takes an archive of one game (its updates and add-ons)')
        self.app = game[0] if game else None
        # programs (android/NX.md "Programs"): index = program_id & 0xf; program 0 is the one whose ID is the application's, else the lowest index (not
        # program_kind: NXConvert marks a Switch 2 Edition's program 1 primary). Each has its own packages; the add-ons are the application's, under every program
        self.program_table, self.program_id = {}, None   # program index → NxProgram row index
        if self.app:
            progs = list(self.app['programs'])
            if not progs: raise Error(f'application {self.app["id"]:016X} has no program')
            for p in progs:
                n = self.programs[p][0] & 0xF
                if n in self.program_table:
                    raise Error(f'programs {self.programs[self.program_table[n]][0]:016X} and {self.programs[p][0]:016X} both have index {n}')
                self.program_table[n] = p
            main = next((p for p in progs if self.programs[p][0] == self.app['id']), None)
            if main is None: main = self.program_table[min(self.program_table)]
            self.program_table[0] = main
            self.program_id = self.programs[main][0]
            dlc = [self.snapshots[j] for j in self.app['snapshots'] if self.snapshots[j].type == T_DLC]
        else: dlc = [s for s in self.snapshots if s.type == T_DLC]   # an archive of add-ons only (one beside a game)
        self.all_packages, self._by_program, self._v = [], {}, []
        addons, by = [], {}
        for s in dlc:
            if self._nca_tree(s): addons += [p for _, p in self._nca_packages(s)]
            else: addons.append(self._package(s, None))
        seen = set()
        for n, p in sorted(self.program_table.items()):
            pid, _, _, first, count = self.programs[p]
            for j in range(first, first + count):
                s = self.snapshots[j]
                if not self._nca_tree(s): by.setdefault(n, []).append(self._package(s, pid)); continue
                if j in seen: continue   # (program 0 and its own index name one row when program 0 is not index 0)
                seen.add(j)
                for m, q in self._nca_packages(s): q.program = m; by.setdefault(m, []).append(q)   # an NCA snapshot: its Program NCAs' ID offsets name the programs
        for n, own in by.items():
            if sum(q.kind == BASE for q in own) > 1: raise Error(f'more than one base snapshot of program {n}')
            self._by_program[n] = own + addons
        self.packages = self._by_program.get(0, addons)   # program 0's packages and the add-ons: what nup-shaped callers see
        if not self.packages: raise Error('no snapshot of a game or an add-on')

    def _package(self, s, pid):
        """The Package of snapshot s (pid: its program's ID; None for an add-on), added to all_packages (its index is the node ('pkg', index))"""
        if True:   # (one block: the checks, then the package)
            what = f'snapshot {s.index} ({TYPES[s.type]}' + (f' {s.version}' if s.type in (T_UPDATE, T_STANDALONE) else '') + ')'
            if s.type == T_UNKNOWN: raise Error(f'{what} has type 4 (unknown): this reader takes base, update, standalone update and DLC snapshots')
            kids = self._root_kids(s.root)
            ex, ro = kids.get('exefs', NONE), kids.get('romfs', NONE)
            if ex != NONE and ex >= self.nd: ex = NONE   # a file named exefs is no ExeFS
            if ro != NONE and ro >= self.nd: ro = NONE
            i = len(self.all_packages)
            if s.type == T_DLC:
                if s.addon_title_id is None: raise Error(f'{what} has no add-on title ID (a native DLC import): this reader names add-ons by title ID')
                p = Package(i, s.addon_title_id, s.root, ex, ro, s.version, ADDON, 0, s)
            else:
                p = Package(i, pid, s.root, ex, ro, s.version, BASE if s.type == T_BASE else UPDATE, 1 if s.type == T_STANDALONE else 0, s)
            p.program = None if s.type == T_DLC else pid & 0xF if pid != self.program_id else 0
            self.all_packages.append(p)
            return p

    def program_indexes(self):
        """The program indexes of the game (0 first), [] for an archive of add-ons only"""
        return sorted(self._by_program)

    def program_packages(self, n):
        """Program n's packages and the application's add-ons (n = 0: the same as packages)"""
        if n not in self._by_program: raise Error(f'{self.path}: no program {n} (has: {", ".join(map(str, self.program_indexes())) or "none"})')
        return self._by_program[n]

    def select(self, selector, program=0):
        """nup.Nup.select over program n's packages"""
        pkgs = self.program_packages(program) if program or self._by_program else self.packages
        saved = self.packages
        try: self.packages = pkgs; return super().select(selector)
        finally: self.packages = saved

    def node(self, rest):
        """nup.Nup.node, and `program<n>/<selector>[/<path>]` for program n's package"""
        m = PROGRAM_SELECTOR.fullmatch(rest or '')
        if not m: return super().node(rest)
        s = nup.SELECTOR.fullmatch(m.group(2))
        if not s: raise FileNotFoundError(errno.ENOENT, 'not a package selector', f'{self.path}:{rest}')
        try: p = self.select(s.group(1), int(m.group(1)))
        except nup.Error as e: raise FileNotFoundError(errno.ENOENT, str(e), f'{self.path}:{rest}') from None
        return self.find(s.group(2) or '', ('pkg', p.index))

    @property
    def title(self): return self.app['title'] if self.app else None

    @property
    def publisher(self): return self.app['publisher'] if self.app else None

    # ---- the tree (nup.Nup's, over directory entries; ('v', i) nodes are the virtual trees of NCA snapshots) ----
    def _pkg(self, k):
        while isinstance(k, tuple) and k[0] == 'pkg': k = self.all_packages[k[1]].root
        return k

    @staticmethod
    def _is_v(k): return isinstance(k, tuple) and k[0] == 'v'

    def is_dir(self, k):
        return self._v[k[1]][0] == 'd' if self._is_v(k) else super().is_dir(k)

    def file_size(self, k):
        return self._v[k[1]][2][2] if self._is_v(k) and self._v[k[1]][0] == 'f' else 0 if self._is_v(k) else super().file_size(k)

    def name(self, k):
        if self._is_v(k): return os.fsencode(self._v[k[1]][1])
        return self._names.get(k, b'') if isinstance(k, int) else os.fsencode(k if isinstance(k, str) else self.all_packages[k[1]].selector)

    def record(self, k):
        """(size, first block reference, block count, sha256, chunk_size_log2) of a file node"""
        return self.files[k - self.nd]

    def _dir_kids(self, d):
        first, count = self.dirs[d]
        return [n for _, n in self.entries[first:first + count]]

    def children(self, k):
        k = self._pkg(k)
        if self._is_v(k):
            if not self.is_dir(k): raise NotADirectoryError(errno.ENOTDIR, 'not a directory', self.path)
            return sorted(self._vkids(k).items(), key=lambda c: c[0].encode())
        if isinstance(k, int):
            if k >= self.nd: raise NotADirectoryError(errno.ENOTDIR, 'not a directory', self.path)
            first, count = self.dirs[k]
            return [(os.fsdecode(n), c) for n, c in self.entries[first:first + count]]
        return super().children(k)

    def lookup(self, k, name):
        k = self._pkg(k)
        if self._is_v(k):
            if not self.is_dir(k): raise NotADirectoryError(errno.ENOTDIR, 'not a directory', self.path)
            return self._vkids(k).get(name)
        if not isinstance(k, int): return super().lookup(k, name)
        if k >= self.nd: raise NotADirectoryError(errno.ENOTDIR, 'not a directory', self.path)
        first, count = self.dirs[k]
        want, lo, hi = os.fsencode(name), first, first + count
        while lo < hi:
            mid = (lo + hi) // 2
            if self.entries[mid][0] < want: lo = mid + 1
            else: hi = mid
        return self.entries[lo][1] if lo < first + count and self.entries[lo][0] == want else None

    # ---- content ----
    def block(self, b, fd, check=None):
        """The logical bytes of block b (fd: a descriptor of the archive). A ZSTD block is decoded whole and cached; check: its slices' CRC-32C (default CHECK_READS)"""
        off, stored, logical, codec, _ = self.blocks[b]
        check = CHECK_READS if check is None else check
        if codec == RAW:
            data = pread(fd, stored, off)
            if len(data) != stored: raise Error(f'{self.path}: block {b} cut off')
        else:
            with self._lock:
                data = self._cache.get(b)
                if data is not None: self._cache.move_to_end(b)
            if data is not None:
                if check: self.check_block(b, data)
                return data
            data = self._decode(b, pread(fd, stored, off), logical)
            if check: self.check_block(b, data)
            with self._lock:
                self._cache[b] = data
                while len(self._cache) > CACHE_BLOCKS: self._cache.popitem(last=False)
            return data
        if check: self.check_block(b, data)
        return data

    def _decode(self, b, comp, logical):
        z = nup.zstd()
        bad = lambda why: Error(f'{self.path}: block {b} (ZSTD, {len(comp)} bytes at {self.blocks[b][0]:#x}): {why}; the archive is damaged')
        if comp[:4] != nup.ZSTD_MAGIC: raise bad('not a zstd frame')
        try:
            info = z.get_frame_info(comp)
            if info.dictionary_id: raise bad(f'dictionary {info.dictionary_id}')
            if info.decompressed_size != logical: raise bad(f'frame content size {info.decompressed_size}, the block holds {logical} bytes')
            d = z.ZstdDecompressor(options={z.DecompressionParameter.window_log_max: 20})
            data = d.decompress(comp)
        except z.ZstdError as e: raise bad(str(e)) from None
        if not d.eof or d.unused_data or len(data) != logical: raise bad('not exactly one frame of the block\'s size')
        return data

    def check_block(self, b, data):
        _, _, logical, _, first = self.blocks[b]
        for k in range(0, logical, CHECK_SIZE):
            if crc32c(data[k:k + CHECK_SIZE]) != self.checks[first + k // CHECK_SIZE]:
                raise Error(f'{self.path}: block {b}: slice {k // CHECK_SIZE} does not match its CRC-32C (damaged)')

    def file_blocks(self, k):
        size, first, count, _, log2 = self.record(k)
        return [self.refs[first + j] for j in range(count)]

    def open(self, k, regions=None):
        """A read-only, seekable raw stream of file node k. regions: a dict that gets {fd: (start, length)} for the stream's descriptor (romfs.py's mmap)"""
        if self.is_dir(k): raise IsADirectoryError(errno.EISDIR, 'is a directory', self.path)
        if self._is_v(k): return _VStream(*self._v[k[1]][2], regions)
        return _Stream(self, k, regions)

    def read_range(self, k, pos, n, fd):
        """Bytes [pos, pos + n) of file node k (fewer at its end), read with descriptor fd"""
        length, _, _, _, log2 = self.record(k)
        first = self.record(k)[1]
        out, end = [], min(length, pos + n)
        while pos < end:
            j, inner = pos >> log2, pos & ((1 << log2) - 1)
            b = self.refs[first + j]
            off, stored, logical, codec, _ = self.blocks[b]
            take = min(end - pos, logical - inner)
            if codec == RAW and not CHECK_READS:
                data = pread(fd, take, off + inner)
                if len(data) != take: raise Error(f'{self.path}: block {b} cut off')
            else: data = self.block(b, fd)[inner:inner + take]
            out.append(data); pos += take
        return b''.join(out)

    def verify(self, k, done=None):
        """True when file node k's blocks match their slice CRC-32C and its bytes its SHA-256 (done: a dict of block results shared across calls). A file of an
        NCA snapshot's tree has no hash of its own: the NCA files it is read from are checked whole (the CLI's verify of the archive)"""
        if self._is_v(k): return True
        done = {} if done is None else done
        h = hashlib.sha256()
        fd = open_fd(self.path)
        try:
            for b in self.file_blocks(k):
                if done.get(b) is False: return False
                try: data = self.block(b, fd, check=not done.get(b))
                except Error: done[b] = False; raise
                done[b] = True; h.update(data)
        finally: os.close(fd)
        return h.digest() == self.record(k)[3]

    def identity(self, s):
        """The canonical identity of snapshot s (SPEC §12), from its tree"""
        nodes = []
        stack = [(s.root, b'')]
        while stack:
            d, path = stack.pop()
            nodes.append((path, 1, None))
            first, count = self.dirs[d]
            for name, node in self.entries[first:first + count]:
                p = path + b'/' + name if path else name
                if node < self.nd: stack.append((node, p))
                else: nodes.append((p, 2, self.files[node - self.nd]))
        nodes.sort(key=lambda t: t[0])
        app = next(a for a in self.apps if s.index in a['snapshots'])
        pid = self.programs[s.program_index][0] if s.program_index != NONE else 0xFFFFFFFFFFFFFFFF
        h = hashlib.sha256(b'NX-SNAPSHOT-ID\0' + struct.pack('<HBQQBI', 3, app['platform'], app['id'], pid, s.type, s.version) + s.content_id + struct.pack('<I', len(nodes)))
        for path, kind, f in nodes:
            h.update(struct.pack('<BI', kind, len(path)) + path)
            if f: h.update(struct.pack('<Q', f[0]) + f[3])
        return h.digest()


class _Stream(io.RawIOBase):
    """A file of the archive: its blocks read where they are. fileno() is the archive's descriptor when the file is RAW blocks one after the other (its range in
    regions), else a temporary file ($DM_TEMP) with the file's bytes"""
    def __init__(self, nx, k, regions=None):
        self.nx, self.k, self.regions = nx, k, regions
        self.length, _, _, _, log2 = nx.record(k)
        self.log2, self.ids = log2, nx.file_blocks(k)
        self.pos, self.tmp = 0, None
        self.fd = open_fd(nx.path)
    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos
    def seek(self, offset, whence=0):
        pos = offset + (self.pos if whence == 1 else self.length if whence == 2 else 0)
        if whence not in (0, 1, 2) or pos < 0: raise ValueError('invalid seek')
        self.pos = pos; return pos
    def _read(self, pos, n): return self.nx.read_range(self.k, pos, n, self.fd)
    def readinto(self, buffer):
        want = min(len(buffer), max(0, self.length - self.pos))
        if not want: return 0
        data = pread(self.tmp.fileno(), want, self.pos) if self.tmp is not None else self._read(self.pos, want)
        buffer[:len(data)] = data; self.pos += len(data)
        return len(data)
    def readall(self):
        if self.pos >= self.length: return b''
        data = self._read(self.pos, self.length - self.pos) if self.tmp is None else pread(self.tmp.fileno(), self.length - self.pos, self.pos)
        self.pos += len(data); return data
    def contiguous(self):
        """The archive offset of the file's bytes when they are RAW blocks one after the other, else None"""
        bs = [self.nx.blocks[b] for b in self.ids]
        if not bs: return 0
        if any(c != RAW for _, _, _, c, _ in bs): return None
        if any(bs[i + 1][0] != bs[i][0] + bs[i][1] for i in range(len(bs) - 1)): return None
        return bs[0][0]
    def fileno(self):
        if self.tmp is not None: return self.tmp.fileno()
        start = self.contiguous()
        if start is not None:
            if self.regions is not None: self.regions[self.fd] = (start, self.length)
            return self.fd
        tmp = tempfile.TemporaryFile(dir=os.environ.get('DM_TEMP') or None)
        pos = 0
        while pos < self.length:
            b = self._read(pos, 8 << 20); tmp.write(b); pos += len(b)
        tmp.flush(); self.tmp = tmp
        if self.regions is not None: self.regions[tmp.fileno()] = (0, self.length)
        return tmp.fileno()
    def close(self):
        if not self.closed:
            if self.regions is not None:
                self.regions.pop(self.fd, None)
                if self.tmp is not None: self.regions.pop(self.tmp.fileno(), None)
            if self.tmp is not None: self.tmp.close()
            os.close(self.fd)
        super().close()


class _VStream(io.RawIOBase):
    """A file of an NCA snapshot's tree: size bytes at off of an nca.py storage (through the patch and compression layers). fileno() is a temporary file
    ($DM_TEMP) with its bytes"""
    def __init__(self, st, off, size, regions=None):
        self.st, self.off, self.length, self.regions, self.pos, self.tmp = st, off, size, regions, 0, None
    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos
    def seek(self, offset, whence=0):
        pos = offset + (self.pos if whence == 1 else self.length if whence == 2 else 0)
        if whence not in (0, 1, 2) or pos < 0: raise ValueError('invalid seek')
        self.pos = pos; return pos
    def _read(self, pos, n):
        n = max(0, min(n, self.length - pos))
        if not n: return b''
        try: return self.st.read(self.off + pos, n)
        except ValueError as e: raise Error(str(e)) from None   # nca.Error
    def readinto(self, buffer):
        want = min(len(buffer), max(0, self.length - self.pos))
        if not want: return 0
        data = pread(self.tmp.fileno(), want, self.pos) if self.tmp is not None else self._read(self.pos, want)
        buffer[:len(data)] = data; self.pos += len(data)
        return len(data)
    def readall(self):
        data = self._read(self.pos, self.length - self.pos) if self.tmp is None else pread(self.tmp.fileno(), max(0, self.length - self.pos), self.pos)
        self.pos += len(data); return data
    def fileno(self):
        if self.tmp is None:
            tmp = tempfile.TemporaryFile(dir=os.environ.get('DM_TEMP') or None)
            for pos in range(0, self.length, 8 << 20): tmp.write(self._read(pos, 8 << 20))
            tmp.flush(); self.tmp = tmp
            if self.regions is not None: self.regions[tmp.fileno()] = (0, self.length)
        return self.tmp.fileno()
    def close(self):
        if not self.closed and self.tmp is not None:
            if self.regions is not None: self.regions.pop(self.tmp.fileno(), None)
            self.tmp.close()
        super().close()


def is_nx(path):
    """Whether path is an NX archive (its magic, whatever its name; DM_NUP_FD honoured)"""
    try:
        fd = open_fd(path)
        try: return _stat.S_ISREG(os.fstat(fd).st_mode) and pread(fd, 4, 0) == MAGIC
        finally: os.close(fd)
    except (OSError, TypeError, ValueError): return False


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'): print(__doc__); return
    try:
        n = nup.open_nup(args[0]); cmd = args[1] if len(args) > 1 else 'packages'
        if not getattr(n, 'programs_inside', False): raise Error(f'{args[0]}: not an NX archive')   # nup.py's nx.Nx (this file run as __main__ is another module)
        if cmd == 'packages':
            if n.app: print(f'# application {n.app["id"]:016X}' + (f' {n.title}' if n.title else '') + (f' ({n.publisher})' if n.publisher else '')
                            + f', program {n.program_id:016X}, generation {n.header.generation}, version (0,{n.header.minor})')
            for p in n.packages + [q for i in n.program_indexes() if i for q in n.program_packages(i) if q.kind != ADDON]:
                print(f'{"program%d/" % p.program if p.program else ""}{p.selector}\t{p.title_id:016X}\tv{p.version}\texefs={"yes" if p.exefs != NONE else "no"}\tromfs={"yes" if p.romfs != NONE else "no"}'
                      + ('\tstandalone' if p.flags & 1 else ''))
        elif cmd == 'ls':
            k = n.node(args[2] if len(args) > 2 else '')
            for name, c in n.children(k): print(f'{name}/' if n.is_dir(c) else f'{name}\t{n.file_size(c)}')
        elif cmd == 'cat': sys.stdout.buffer.write(n.read(n.node(args[2])))
        elif cmd == 'verify':
            # the whole archive: every file record and every snapshot's identity; with a name, the files under it
            if len(args) > 2:
                top = n.node(args[2]); nodes = [] if n.is_dir(top) else [top]
                if n.is_dir(top):
                    stack = [top]
                    while stack:
                        for _, c in n.children(stack.pop()): (stack if n.is_dir(c) else nodes).append(c)
            else: nodes = [n.nd + f for f in range(n.nf)]
            done, bad = {}, 0
            for k in nodes:
                try: ok = n.verify(k, done)
                except nup.Error as e: ok = False; print(f'[RED] {n.name(k).decode(errors="replace")}: {e}')
                if not ok: bad += 1; print(f'[RED] {n.name(k).decode(errors="replace")} (file {k - n.nd}): content does not match its CRC-32C or SHA-256', flush=True)
            if len(args) <= 2:
                unused = set(range(len(n.blocks))) - set(done)
                for b in sorted(unused):   # blocks no file names: checked too (full verification)
                    fd = open_fd(n.path)
                    try: n.block(b, fd, check=True)
                    except nup.Error as e: bad += 1; print(f'[RED] {e}')   # nup.Error: run as __main__, this file's Error is another class than nx.Error
                    finally: os.close(fd)
                for s in n.snapshots:
                    if n.identity(s) != s.identity: bad += 1; print(f'[RED] snapshot {s.index} ({TYPES.get(s.type)} {s.version}): its identity does not match its tree')
            print(f'{len(nodes)} files, {bad} bad')
            if bad: sys.exit(1)
        else: sys.exit(f'unknown command {cmd}')
    except (nup.Error, OSError) as e: sys.exit(f'[RED] {e}')


if __name__ == '__main__':
    main()
