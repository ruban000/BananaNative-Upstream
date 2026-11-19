#!/usr/bin/env python3
"""Plain (decrypted) NCAs read through storages, for NCA snapshots of an NX archive (android/NX.md "NCA snapshots"; dm/tools/nx.py). No keys: a header that is
not plain (`DNCA` or `NCA3` at 0x200, each FS header matching its SHA-256 at 0x280) or a section whose encryption type is not 1 (none) is refused, as are a
sparse section and an NCZ.

A section's data is read through the layers in Nintendo's order, checked against Atmosphère's
libraries/libstratosphere/source/fssystem/fssystem_nca_file_system_driver.cpp (OpenStorageImpl, CreateStorageByRawStorage; read 2026-11-19):
  1. the raw section (FS header's section table entry); a sparse layer is refused;
  2. the patch: with a BKTR indirect table (patch info at FS header 0x100) the section is an indirect storage whose storage 0 is the base's section and storage 1
     this section's bytes [0, indirect table offset) (CreateIndirectStorage: SetStorage(1, SubStorage(data, 0, indirect_offset))); its table is read from this
     section at the indirect offset; the AesCtrEx table only matters for decryption and is ignored;
  3. the hash layer: HierarchicalSha256 (a PartitionFS: its data region, FS header 0x40/0x48) or IVFC (a RomFS: level 6's offset and size); hashes not checked;
  4. compressed storage last, over the hash layer's data (CreateCompressedStorage(..., std::move(storage) /* the hash layer */, compression_info)): its table at
     the compression info's table offset in that data, the entries' physical offsets in that data too.
Bucket trees (indirect and compressed tables) as Atmosphère's fssystem_bucket_tree: an L1 node of 0x4000 bytes (and L2 nodes when the entry sets are more
than one node's offsets), then the entry sets of 0x4000 bytes, each a 0x10-byte header (index, count, end offset) and its entries.
"""
import collections, hashlib, struct, threading

NONE = 0xFFFFFFFF
NODE = 0x4000
PROGRAM, META, CONTROL, DATA = 0, 1, 2, 4       # NCA content types (0x205)
R_PROGRAM, R_DATA, R_CONTROL, R_DELTA = 1, 2, 3, 6   # CNMT content record types (ncm::ContentType)
APPLICATION, PATCH, ADDON = 0x80, 0x81, 0x82
LZ4_CACHE = 8


class Error(ValueError): pass


def need(ok, msg):
    if not ok: raise Error(msg)


# ---- storages: size, read(off, n) -> bytes (exactly n bytes inside the storage) ----
class Mem:
    def __init__(self, b): self.b, self.size = b, len(b)
    def read(self, off, n): return self.b[off:off + n]


class Sub:
    def __init__(self, base, off, size, what='a range'):
        need(0 <= off and 0 <= size and off + size <= base.size, f'{what} [{off:#x}, +{size:#x}) is outside its storage ({base.size:#x} bytes)')
        self.base, self.off, self.size = base, off, size
    def read(self, off, n):
        need(0 <= off and off + n <= self.size, 'a read outside its range')
        return self.base.read(self.off + off, n)


def bucket_entries(st, off, size, count, fmt, what):
    """The entries of a bucket tree table at off (size bytes) of storage st, count of them, each struct fmt: (entries, end offset)"""
    esize = struct.calcsize(fmt)
    per_set, per_node = (NODE - 0x10) // esize, (NODE - 0x10) // 8
    sets = -(-count // per_set) if count else 0
    if sets <= per_node: l2 = 0
    else:   # Atmosphère BucketTree::QueryNodeStorageSize / GetL2NodeCount
        n_l2 = -(-sets // per_node)
        need(n_l2 <= per_node, f'{what}: too many entries')
        l2 = -(-(sets - (per_node - (n_l2 - 1))) // per_node)
    nodes = (1 + l2) * NODE
    need(count >= 0 and size >= nodes + sets * NODE and off + size <= st.size, f'{what}: the table ({count} entries) is outside its storage or cut off')
    if not count: return [], 0
    idx, n, end = struct.unpack('<iiq', st.read(off, 0x10))
    need(idx == 0 and n >= 1, f'{what}: bad L1 node')
    out = []
    for s in range(sets):
        b = st.read(off + nodes + s * NODE, NODE)
        si, c, send = struct.unpack_from('<iiq', b)
        need(si == s and 0 < c <= per_set, f'{what}: bad entry set {s}')
        out += [struct.unpack_from(fmt, b, 0x10 + esize * j) for j in range(c)]
    need(len(out) == count, f'{what}: {len(out)} entries, its header says {count}')
    need(out[0][0] == 0 and all(out[i][0] < out[i + 1][0] for i in range(len(out) - 1)) and out[-1][0] < end, f'{what}: entries out of order')
    return out, end


class Indirect:
    """A BKTR indirect storage: entries (virtual offset, physical offset, storage index) over storages[0] (the base's section) and storages[1] (this one's)"""
    def __init__(self, entries, end, storages, what):
        self.v = [e[0] for e in entries] + [end]
        self.e, self.st, self.size, self.what = entries, storages, end, what
        for i, (v, p, s) in enumerate(entries):
            need(s in (0, 1) and p >= 0 and p + self.v[i + 1] - v <= storages[s].size, f'{what}: entry {i} is outside storage {s}')
    def read(self, off, n):
        import bisect
        out, end = [], off + n
        need(0 <= off and end <= self.size, f'{self.what}: a read outside the patched section')
        i = bisect.bisect_right(self.v, off) - 1
        while off < end:
            v, p, s = self.e[i]
            take = min(end, self.v[i + 1]) - off
            out.append(self.st[s].read(p + off - v, take)); off += take; i += 1
        return b''.join(out)


def lz4_block(src, size):
    """An LZ4 block (lz4 Block_format.md: sequences of a token, literals, a 2-byte offset and a match; the last sequence literals only) decoded to size bytes"""
    out, i, n = bytearray(), 0, len(src)
    while i < n:
        t = src[i]; i += 1
        lit = t >> 4
        if lit == 15:
            while True:
                need(i < n, 'LZ4: cut off'); b = src[i]; i += 1; lit += b
                if b != 255: break
        need(i + lit <= n, 'LZ4: literals cut off')
        out += src[i:i + lit]; i += lit
        if i >= n: break
        need(i + 2 <= n, 'LZ4: offset cut off')
        off = src[i] | src[i + 1] << 8; i += 2
        need(0 < off <= len(out), f'LZ4: offset {off} outside the {len(out)} bytes decoded')
        ml = t & 15
        if ml == 15:
            while True:
                need(i < n, 'LZ4: cut off'); b = src[i]; i += 1; ml += b
                if b != 255: break
        ml += 4
        start = len(out) - off
        if off >= ml: out += out[start:start + ml]
        else:
            pat = bytes(out[start:]); out += (pat * (ml // off + 1))[:ml]
        need(len(out) <= size, 'LZ4: more bytes than the block holds')
    need(len(out) == size, f'LZ4: {len(out)} bytes, the block holds {size}')
    return bytes(out)


class Compressed:
    """Compressed storage: entries (virtual offset, physical offset, type 0 none / 1 zeros / 3 LZ4, level, reserved, physical size) over base"""
    def __init__(self, entries, end, base, what):
        self.v = [e[0] for e in entries] + [end]
        self.e, self.base, self.size, self.what = entries, base, end, what
        self.cache, self.lock = collections.OrderedDict(), threading.Lock()
        for i, (v, p, typ, _, _, psize) in enumerate(entries):
            need(typ in (0, 1, 3), f'{what}: entry {i} has compression type {typ} (0 none, 1 zeros, 3 LZ4 are read)')
            if typ == 0: need(psize == self.v[i + 1] - v, f'{what}: entry {i} stored with {psize} bytes for {self.v[i + 1] - v}')
            if typ != 1: need(p >= 0 and p + psize <= base.size, f'{what}: entry {i} is outside its data')
    def _block(self, i):
        with self.lock:
            b = self.cache.get(i)
            if b is not None: self.cache.move_to_end(i); return b
        v, p, _, _, _, psize = self.e[i]
        try: b = lz4_block(self.base.read(p, psize), self.v[i + 1] - v)
        except Error as e: raise Error(f'{self.what}: block at {v:#x}: {e}') from None
        with self.lock:
            self.cache[i] = b
            while len(self.cache) > LZ4_CACHE: self.cache.popitem(last=False)
        return b
    def read(self, off, n):
        import bisect
        out, end = [], off + n
        need(0 <= off and end <= self.size, f'{self.what}: a read outside the section')
        i = bisect.bisect_right(self.v, off) - 1
        while off < end:
            v, p, typ, _, _, _ = self.e[i]
            take = min(end, self.v[i + 1]) - off
            if typ == 0: out.append(self.base.read(p + off - v, take))
            elif typ == 1: out.append(bytes(take))
            else: out.append(self._block(i)[off - v:off - v + take])
            off += take; i += 1
        return b''.join(out)


# ---- the NCA ----
class Section:
    def __init__(self, i, start, end, fh):
        self.index, self.start, self.end, self.fh = i, start, end, fh
        self.version, self.fs_type, self.hash_type, self.enc = struct.unpack_from('<HBBB', fh)


class Nca:
    """An NCA file (storage st, its name for messages): header fields and sections; data(i, base) the section's filesystem data"""
    def __init__(self, st, name):
        self.st, self.name = st, name
        need(st.size >= 0xC00, f'{name}: shorter than an NCA header')
        h = st.read(0, 0xC00)
        need(h[0x200:0x204] in (b'DNCA', b'NCA3'), f'{name}: no plain NCA header (DNCA or NCA3 at 0x200; {bytes(h[0x200:0x204])!r}): an encrypted NCA, which is not read')
        self.content_type, self.program_id, self.index = h[0x205], struct.unpack_from('<Q', h, 0x210)[0], struct.unpack_from('<I', h, 0x218)[0]
        self.sections = {}
        for i in range(4):
            start, end = struct.unpack_from('<II', h, 0x240 + 0x10 * i)
            if not start and not end: continue
            fh = h[0x400 + 0x200 * i:0x600 + 0x200 * i]
            need(hashlib.sha256(fh).digest() == h[0x280 + 0x20 * i:0x2A0 + 0x20 * i], f'{name}: section {i}: its FS header does not match its SHA-256 (not a plain NCA)')
            need(6 <= start < end and end * 0x200 <= st.size, f'{name}: section {i} is outside the NCA')
            s = Section(i, start * 0x200, end * 0x200, fh)
            need(s.version == 2, f'{name}: section {i}: FS header version {s.version}')
            need(s.enc == 1, f'{name}: section {i} is encrypted (type {s.enc}); only decrypted NCAs are read')
            need(not any(fh[0x148:0x178]), f'{name}: section {i} is sparse, which is not read')
            self.sections[i] = s

    def raw(self, i):
        s = self.sections[i]
        return Sub(self.st, s.start, s.end - s.start, f'{self.name}: section {i}')

    def patched(self, i):
        """Whether section i has a BKTR indirect table (an update's RomFS)"""
        off, size = struct.unpack_from('<QQ', self.sections[i].fh, 0x100)
        return bool(size) and self.sections[i].fh[0x110:0x114] == b'BKTR'

    def data(self, i, base=None):
        """Section i's filesystem data: (fs type 0 RomFS / 1 PartitionFS, storage). base: the base NCA's raw RomFS section, for a patched one"""
        s, what = self.sections[i], f'{self.name}: section {i}'
        st = self.raw(i)
        if self.patched(i):   # 2: the patch
            need(base is not None, f'{what}: a patch with no base Program NCA in the snapshot')
            ioff, isize = struct.unpack_from('<QQ', s.fh, 0x100)
            magic, ver, count, _ = struct.unpack_from('<4sIiI', s.fh, 0x110)
            need(ver == 1, f'{what}: indirect table version {ver}')
            entries, end = bucket_entries(st, ioff, isize, count, '<qqi', f'{what}: indirect table')
            st = Indirect(entries, end, [base, Sub(st, 0, ioff, f'{what}: patch data')], f'{what} (patched)')
        hd = s.fh[8:0x100]   # 3: the hash layer's data
        if s.fs_type == 1 and s.hash_type == 2: doff, dsize = struct.unpack_from('<QQ', s.fh, 0x40)
        elif s.fs_type == 0 and s.hash_type == 3:
            need(hd[:4] == b'IVFC', f'{what}: no IVFC header')
            levels = struct.unpack_from('<I', hd, 0xC)[0] - 1
            need(2 <= levels <= 6, f'{what}: {levels} IVFC levels')
            doff, dsize = struct.unpack_from('<QQ', hd, 0x10 + 0x18 * (levels - 1))
        else: raise Error(f'{what}: filesystem {s.fs_type} with hash type {s.hash_type} is not read')
        st = Sub(st, doff, dsize, f'{what}: its data')
        coff, csize = struct.unpack_from('<QQ', s.fh, 0x178)
        if csize:   # 4: compressed storage over the hash layer's data
            magic, ver, count, _ = struct.unpack_from('<4sIiI', s.fh, 0x188)
            need(magic == b'BKTR' and ver == 1, f'{what}: bad compression table header')
            entries, end = bucket_entries(st, coff, csize, count, '<qqBbHI', f'{what}: compression table')
            st = Compressed(entries, end, st, f'{what} (compressed)')
        return s.fs_type, st

    def first(self, fs_type):
        return next((i for i, s in sorted(self.sections.items()) if s.fs_type == fs_type), None)


# ---- filesystems: a tree {name: dict | (storage, offset, size)} ----
def pfs(st, what):
    need(st.size >= 0x10 and st.read(0, 4) == b'PFS0', f'{what}: not a PartitionFS')
    count, strsize = struct.unpack('<II', st.read(4, 8))
    head = 0x10 + 0x18 * count + strsize
    need(count <= 1 << 16 and head <= st.size, f'{what}: the PartitionFS header is cut off')
    table, strings, out = st.read(0x10, 0x18 * count), st.read(0x10 + 0x18 * count, strsize), {}
    for i in range(count):
        off, n, no, _ = struct.unpack_from('<QQII', table, 0x18 * i)
        need(no < strsize and head + off + n <= st.size, f'{what}: entry {i} is outside the PartitionFS')
        end = strings.find(b'\0', no)
        name = strings[no:end if end >= 0 else strsize].decode('utf-8')
        need(name and '/' not in name and name not in ('.', '..') and name not in out, f'{what}: a bad or repeated name {name!r}')
        out[name] = (st, head + off, n)
    return out


def romfs(st, what):
    need(st.size >= 0x50, f'{what}: RomFS too short')
    hdr = struct.unpack('<10Q', st.read(0, 0x50))
    need(hdr[0] == 0x50, f'{what}: bad RomFS header')
    dt, ds, ft, fs, data = hdr[3], hdr[4], hdr[7], hdr[8], hdr[9]
    need(dt + ds <= st.size and ft + fs <= st.size and data <= st.size, f'{what}: RomFS tables out of bounds')
    dirs, files = st.read(dt, ds), st.read(ft, fs)
    seen_d, seen_f, root = set(), set(), {}
    def name_of(b, at, n):
        label = b[at:at + n].decode('utf-8')
        need(label and '/' not in label and label not in ('.', '..') and '\0' not in label, f'{what}: unsafe RomFS name {label!r}')
        return label
    stack = [(0, root, 0)]
    while stack:
        d, node, depth = stack.pop()
        need(depth < 256 and d + 0x18 <= ds and d not in seen_d, f'{what}: bad or cyclic RomFS directory')
        seen_d.add(d)
        _, _, child, f, _, _ = struct.unpack_from('<6I', dirs, d)
        while f != NONE:
            need(f + 0x20 <= fs and f not in seen_f, f'{what}: bad or cyclic RomFS file')
            seen_f.add(f)
            _, nxt, fo, fsize, _, fn = struct.unpack_from('<IIQQII', files, f)
            need(fn <= fs - f - 0x20 and data + fo + fsize <= st.size, f'{what}: RomFS file out of bounds')
            label = name_of(files, f + 0x20, fn)
            need(label not in node, f'{what}: {label} twice')
            node[label] = (st, data + fo, fsize); f = nxt
        c = child
        while c != NONE:
            need(c + 0x18 <= ds, f'{what}: bad RomFS directory')
            _, sib, _, _, _, n = struct.unpack_from('<6I', dirs, c)
            need(n <= ds - c - 0x18, f'{what}: RomFS directory name out of bounds')
            label = name_of(dirs, c + 0x18, n)
            need(label not in node, f'{what}: {label} twice')
            node[label] = {}; stack.append((c, node[label], depth + 1))
            c = sib
    return root


# ---- the content meta ----
class Record:
    def __init__(self, b, name):
        need(len(b) >= 0x20, f'{name}: shorter than a content meta header')
        self.id, self.version, self.type, _, exs, cc, cmc = struct.unpack_from('<QIBBHHH', b, 0)
        need(len(b) >= 0x20 + exs + 0x38 * cc, f'{name}: cut off')
        ext = b[0x20:0x20 + exs]
        self.application_id = struct.unpack_from('<Q', ext)[0] if self.type in (PATCH, ADDON) and exs >= 8 else None
        self.contents = []   # (content ID hex, size, record type, id offset)
        for i in range(cc):
            o = 0x20 + exs + 0x38 * i
            self.contents.append((b[o + 0x20:o + 0x30].hex(), int.from_bytes(b[o + 0x30:o + 0x35], 'little'), b[o + 0x36], b[o + 0x37]))
        self.name = name


def record(meta):
    """The content meta Record of a meta NCA"""
    need(meta.content_type == META, f'{meta.name}: content type {meta.content_type}, not a meta NCA')
    i = meta.first(1)
    need(i is not None, f'{meta.name}: no PartitionFS section')
    files = pfs(meta.data(i)[1], meta.name)
    cn = [n for n in files if n.endswith('.cnmt')]
    need(len(cn) == 1, f'{meta.name}: {len(cn)} .cnmt files, expected one')
    st, off, n = files[cn[0]]
    return Record(st.read(off, n), cn[0])
