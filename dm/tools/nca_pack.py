#!/usr/bin/env python3
"""Plain (decrypted) NCAs for test fixtures, written from the public layouts (switchbrew NCA / CNMT / RomFS / PFS0 pages, Atmosphère's fssystem bucket tree,
indirect and compressed storages): what NXConvert's native import keeps of a decrypted NSP, for dm/tools/nx_pack.py --nca. Not a tool for users, and nothing
here asks for or uses keys: every NCA is written plain (`DNCA` or `NCA3` at 0x200, encryption type 1). See android/NX.md "NCA snapshots".

  romfs_image(tree)          a RomFS (header 0x50, file data from 0x200, directory and file hash tables and entries) of a nested {name: dict | bytes}
  pfs0(files)                a PartitionFS of [(name, bytes)]
  pfs_section / romfs_section    (FS header, section bytes): HierarchicalSha256 over a PFS0; IVFC (six levels, the RomFS level 6) over a RomFS
  patched_romfs_section      the update's RomFS section as a BKTR indirect storage over the base's section (storage 0) and its own data (storage 1)
  compressed_romfs_section   a RomFS section read through compressed storage (blocks of LZ4, zeros or none; the table after them in the hashed data)
  nca(...)                   the NCA (0xC00-byte header with each FS header's SHA-256, sections from 0xC00), its content ID = SHA-256[:16]
  cnmt(...)                  a content meta record (application 0x80, patch 0x81, add-on 0x82) with its digest
The hash levels are written as they should be (IVFC and HierarchicalSha256 over zero-padded blocks); the readers do not check them (android/NX.md).
"""
import hashlib, struct

NONE = 0xFFFFFFFF
NODE = 0x4000   # bucket tree node and entry set size (Atmosphère fssystem_bucket_tree)
IVFC_BLOCK = 0x4000
PROGRAM, META, CONTROL, DATA = 0, 1, 2, 4                       # NCA content types (0x205)
R_PROGRAM, R_DATA, R_CONTROL = 1, 2, 3                           # CNMT content record types (ncm::ContentType)
APPLICATION, PATCH, ADDON = 0x80, 0x81, 0x82


def align(n, a): return (n + a - 1) // a * a


def pad(b, a): return b + bytes(align(len(b), a) - len(b))


def sha(b): return hashlib.sha256(b).digest()


# ---- RomFS ----
def _hash(parent, name):
    h = parent ^ 123456789
    for c in name: h = (((h >> 5) | (h << 27)) & 0xFFFFFFFF) ^ c
    return h


def _buckets(n):
    if n < 3: return 3
    if n < 19: return n | 1
    c = n
    while any(c % p == 0 for p in (2, 3, 5, 7, 11, 13, 17)): c += 1
    return c


def romfs_image(tree):
    """(RomFS bytes, {path: (offset of the file's data in the RomFS, size)}) of tree: {name: dict (a directory) | bytes (a file)}"""
    dirs = [('', None, tree)]   # (name, parent index, dict) breadth first
    i = 0
    while i < len(dirs):
        for n, v in sorted(dirs[i][2].items(), key=lambda kv: kv[0].encode()):
            if isinstance(v, dict): dirs.append((n, i, v))
        i += 1
    doff, o = [], 0
    for n, _, _ in dirs: doff.append(o); o += 0x18 + align(len(n.encode()), 4)
    dmeta_size = o
    files = []   # (name, dir index, bytes)
    for di, (_, _, d) in enumerate(dirs):
        for n, v in sorted(d.items(), key=lambda kv: kv[0].encode()):
            if not isinstance(v, dict): files.append((n, di, v))
    foff, o = [], 0
    for n, _, _ in files: foff.append(o); o += 0x20 + align(len(n.encode()), 4)
    fmeta_size = o
    data, pos, where, paths = bytearray(), 0, [], {}
    def path_of(di):
        parts = []
        while di: parts.append(dirs[di][0]); di = dirs[di][1]
        return '/'.join(reversed(parts))
    for n, di, b in files:
        pos = align(len(data), 0x10); data += bytes(pos - len(data)) + b; where.append(pos)
        p = path_of(di); paths[f'{p}/{n}' if p else n] = (pos, len(b))
    data_off = 0x200
    end = align(data_off + len(data), 4)
    nd_b, nf_b = _buckets(len(dirs)), _buckets(len(files))
    dhash_off = end; fhash_off = dhash_off + 4 * nd_b
    dmeta_off = fhash_off + 4 * nf_b
    fmeta_off = dmeta_off + dmeta_size
    # tables: hash buckets chain entries by their hash's bucket
    dh, fh = [NONE] * nd_b, [NONE] * nf_b
    dnext, fnext = [NONE] * len(dirs), [NONE] * len(files)
    for i, (n, p, _) in enumerate(dirs):
        b = _hash(doff[p] if p is not None else 0, n.encode()) % nd_b; dnext[i] = dh[b]; dh[b] = doff[i]
    for i, (n, di, _) in enumerate(files):
        b = _hash(doff[di], n.encode()) % nf_b; fnext[i] = fh[b]; fh[b] = foff[i]
    kids = {i: [j for j, d in enumerate(dirs) if d[1] == i] for i in range(len(dirs))}
    fkids = {i: [j for j, f in enumerate(files) if f[1] == i] for i in range(len(dirs))}
    dmeta = bytearray()
    for i, (n, p, _) in enumerate(dirs):
        sib = NONE
        if p is not None:
            s = kids[p]; k = s.index(i); sib = doff[s[k + 1]] if k + 1 < len(s) else NONE
        nb = n.encode()
        dmeta += struct.pack('<6I', doff[p] if p is not None else 0, sib, doff[kids[i][0]] if kids[i] else NONE,
                             foff[fkids[i][0]] if fkids[i] else NONE, dnext[i], len(nb)) + pad(nb, 4)
    fmeta = bytearray()
    for i, (n, di, b) in enumerate(files):
        s = fkids[di]; k = s.index(i); sib = foff[s[k + 1]] if k + 1 < len(s) else NONE
        nb = n.encode()
        fmeta += struct.pack('<IIQQII', doff[di], sib, where[i], len(b), fnext[i], len(nb)) + pad(nb, 4)
    head = struct.pack('<10Q', 0x50, dhash_off, 4 * nd_b, dmeta_off, dmeta_size, fhash_off, 4 * nf_b, fmeta_off, fmeta_size, data_off)
    img = bytearray(head) + bytes(data_off - len(head)) + data
    img += bytes(dhash_off - len(img))
    img += struct.pack(f'<{nd_b}I', *dh) + struct.pack(f'<{nf_b}I', *fh) + dmeta + fmeta
    return bytes(img), {p: (data_off + o, n) for p, (o, n) in paths.items()}


# ---- PartitionFS ----
def pfs0(files):
    names = b''.join(n.encode() + b'\0' for n, _ in files)
    head = 0x10 + 0x18 * len(files)
    names += bytes(align(head + len(names), 0x20) - head - len(names))   # the data starts on a 0x20 boundary
    table, off, no = b'', 0, 0
    for n, b in files:
        table += struct.pack('<QQII', off, len(b), no, 0); off += len(b); no += len(n.encode()) + 1
    return struct.pack('<4sII4s', b'PFS0', len(files), len(names), bytes(4)) + table + names + b''.join(b for _, b in files)


# ---- FS headers and sections ----
def _fs_header(fs_type, hash_type, hash_data, patch=b'', compression=b''):
    h = bytearray(0x200)
    struct.pack_into('<HBBB', h, 0, 2, fs_type, hash_type, 1)   # version 2, encryption type 1 (none)
    h[8:8 + len(hash_data)] = hash_data
    h[0x100:0x100 + len(patch)] = patch
    h[0x178:0x178 + len(compression)] = compression
    return h


def pfs_section(pfs):
    """(FS header, section bytes) of a PFS0 under HierarchicalSha256 (block 0x1000: the hash table, then the PFS0)"""
    block = 0x1000
    table = b''.join(sha(pfs[i:i + block]) for i in range(0, max(len(pfs), 1), block))
    poff = align(len(table), 0x200)
    hd = sha(table) + struct.pack('<II', block, 2) + struct.pack('<QQQQ', 0, len(table), poff, len(pfs))
    return _fs_header(1, 2, hd), pad(table, 0x200) + pfs


def ivfc(data):
    """(IVFC hash data (0xF8 bytes), the section's logical bytes, level 6's offset) for data as level 6: levels 1..5 of SHA-256 over zero-padded 0x4000 blocks"""
    levels = [data]
    for _ in range(5):
        below = levels[0]
        levels.insert(0, b''.join(sha(pad(below[i:i + IVFC_BLOCK], IVFC_BLOCK)) for i in range(0, max(len(below), 1), IVFC_BLOCK)))
    master = b''.join(sha(pad(levels[0][i:i + IVFC_BLOCK], IVFC_BLOCK)) for i in range(0, max(len(levels[0]), 1), IVFC_BLOCK))[:0x20]
    out, offs = bytearray(), []
    for lv in levels:
        o = align(len(out), IVFC_BLOCK); out += bytes(o - len(out)); offs.append(o); out += lv
    hd = bytearray(0xF8)
    struct.pack_into('<4sIII', hd, 0, b'IVFC', 0x20000, len(master), 7)
    for i, lv in enumerate(levels): struct.pack_into('<QQII', hd, 0x10 + 0x18 * i, offs[i], len(lv), 14, 0)
    hd[0xC0:0xE0] = master
    return bytes(hd), bytes(out), offs[5]


def romfs_section(romfs):
    """(FS header, section bytes, level 6's offset in the section) of a RomFS under IVFC"""
    hd, logical, l6 = ivfc(romfs)
    return _fs_header(0, 3, hd), logical, l6


def bucket_tree(entries, entry_fmt, end):
    """(BKTR header 0x10, table bytes: one L1 node, then the entry sets) of entries [(virtual offset, ...)], sorted, for a storage of end bytes"""
    esize = struct.calcsize(entry_fmt)
    per_set = (NODE - 0x10) // esize
    sets = [entries[i:i + per_set] for i in range(0, len(entries), per_set)]
    assert len(sets) <= (NODE - 0x10) // 8, 'a fixture this large needs L2 nodes'
    l1 = bytearray(NODE)
    struct.pack_into('<iiq', l1, 0, 0, len(sets), end)
    for i, s in enumerate(sets): struct.pack_into('<q', l1, 0x10 + 8 * i, s[0][0])
    body = bytearray(l1)
    for i, s in enumerate(sets):
        node = bytearray(NODE)
        struct.pack_into('<iiq', node, 0, i, len(s), sets[i + 1][0][0] if i + 1 < len(sets) else end)
        for j, e in enumerate(s): struct.pack_into(entry_fmt, node, 0x10 + esize * j, *e)
        body += node
    return struct.pack('<4sIiI', b'BKTR', 1, len(entries), 0), bytes(body)


def patched_romfs_section(romfs, base_section, base_l6, base_files, files, min_run=0x200):
    """The update's RomFS section as android/NX.md "Patch" reads it: (FS header with patch info, physical section bytes, the logical section bytes).
    The logical section is the IVFC of romfs; each file whose bytes are the same as a file of the base's RomFS (base_files / files: {path: (offset, size)} from
    romfs_image) is an entry of storage 0 (the base's section, base_section, its RomFS at base_l6), every other range one of storage 1 (this section's own data,
    packed from offset 0). The indirect table follows the data, then an empty AesCtrEx table"""
    hd, logical, l6 = ivfc(romfs)
    by_bytes = {}
    for p, (o, n) in base_files.items():
        if n >= min_run: by_bytes.setdefault(sha(base_section[base_l6 + o:base_l6 + o + n]), base_l6 + o)
    shared = []   # (virtual offset, size, physical offset in the base section)
    for p, (o, n) in sorted(files.items(), key=lambda kv: kv[1][0]):
        if n < min_run: continue
        b = romfs[o:o + n]; at = by_bytes.get(sha(b))
        if at is not None and base_section[at:at + n] == b: shared.append((l6 + o, n, at))
    entries, own, pos = [], bytearray(), 0
    for v, n, at in shared + [(len(logical), 0, None)]:
        if v > pos:   # this section's own bytes
            entries.append((pos, len(own), 1)); own += logical[pos:v]; own += bytes(align(len(own), 0x10) - len(own))
        if n: entries.append((v, at, 0))
        pos = v + n
    toff = align(len(own), 0x10)
    head, table = bucket_tree(entries, '<qqi', len(logical))
    aes_off = align(toff + len(table), 0x10)
    aes_head = struct.pack('<4sIiI', b'BKTR', 1, 0, 0)
    patch = struct.pack('<QQ', toff, len(table)) + head + struct.pack('<QQ', aes_off, 0) + aes_head
    phys = bytes(own) + bytes(toff - len(own)) + table + bytes(aes_off - toff - len(table))
    return _fs_header(0, 3, hd, patch=patch), phys, logical, entries


def compressed_romfs_section(romfs, block=0x10000, lz4_compress=None):
    """(FS header with compression info, section bytes, [compression types per entry]) of a RomFS read through compressed storage: blocks of block bytes,
    each zeros (type 1, no stored bytes), LZ4 (type 3) when lz4_compress shrinks it, else none (type 0); the stored blocks, then the table, are IVFC level 6"""
    stored, entries, kinds = bytearray(), [], []
    for v in range(0, len(romfs), block):
        b = romfs[v:v + block]
        if not b.strip(b'\0'): entries.append((v, len(stored), 1, 0, 0, 0)); kinds.append(1); continue
        c = lz4_compress(b) if lz4_compress else None
        if c is not None and len(c) < len(b): entries.append((v, len(stored), 3, 0, 0, len(c))); stored += c; kinds.append(3)
        else: entries.append((v, len(stored), 0, 0, 0, len(b))); stored += b; kinds.append(0)
        stored += bytes(align(len(stored), 0x10) - len(stored))
    toff = align(len(stored), 0x10)
    head, table = bucket_tree(entries, '<qqBbHI', len(romfs))
    level6 = bytes(stored) + bytes(toff - len(stored)) + table
    hd, logical, l6 = ivfc(level6)
    comp = struct.pack('<QQ', toff, len(table)) + head
    return _fs_header(0, 3, hd, compression=comp), logical, kinds


def lz4_compress(b):
    """An LZ4 block of b (a small greedy compressor: a 4-byte hash table, matches at most 0xFFFF back), so fixtures have real LZ4 blocks without the
    lz4 module. Keeps the format's end rules: the last match starts at least 12 bytes before the end, and the last 5 bytes are literals"""
    n = len(b); out = bytearray(); anchor = i = 0; table = {}
    def length(v):   # the 255-run of a length past 15 in its nibble
        while v >= 255: out.append(255); v -= 255
        out.append(v)
    limit = n - 12
    while i < limit:
        key = b[i:i + 4]; j = table.get(key); table[key] = i
        if j is None or i - j > 0xFFFF: i += 1; continue
        m = 4
        while i + m < n - 5 and b[j + m] == b[i + m]: m += 1
        lit = i - anchor
        out.append((min(lit, 15) << 4) | min(m - 4, 15))
        if lit >= 15: length(lit - 15)
        out += b[anchor:i]; out += struct.pack('<H', i - j)
        if m - 4 >= 15: length(m - 4 - 15)
        i += m; anchor = i
    lit = n - anchor
    out.append(min(lit, 15) << 4)
    if lit >= 15: length(lit - 15)
    out += b[anchor:]
    return bytes(out)


def lz4_literals(b):
    """An LZ4 block of b as one sequence of literals (valid, never shorter: for a writer without the lz4 module)"""
    n = len(b); tok = min(n, 15) << 4; out = bytearray([tok])
    if n >= 15:
        r = n - 15
        while r >= 255: out.append(255); r -= 255
        out.append(r)
    return bytes(out) + b


# ---- NCA and CNMT ----
def nca(content_type, program_id, sections, index=0, magic=b'DNCA'):
    """An NCA: header 0xC00 (signatures zero; the magic at 0x200; content type 0x205; size 0x208; program ID 0x210; content index 0x218; the section table at
    0x240; each FS header's SHA-256 at 0x280; the FS headers at 0x400), then each section (FS header, bytes) from 0xC00 at 0x200-byte boundaries"""
    h = bytearray(0xC00)
    body, pos = bytearray(), 0xC00
    for i, (fh, data) in enumerate(sections):
        start = align(pos, 0x200); body += bytes(start - pos); data = pad(data, 0x200)
        struct.pack_into('<II', h, 0x240 + 0x10 * i, start // 0x200, (start + len(data)) // 0x200)
        h[0x400 + 0x200 * i:0x600 + 0x200 * i] = fh
        h[0x280 + 0x20 * i:0x2A0 + 0x20 * i] = sha(bytes(fh))
        body += data; pos = start + len(data)
    h[0x200:0x204] = magic; h[0x204] = 0; h[0x205] = content_type
    struct.pack_into('<QQI', h, 0x208, 0xC00 + len(body), program_id, index)
    b = bytes(h) + bytes(body)
    return b


def content_id(b): return sha(b)[:16]


def cnmt(kind, title_id, version, contents, link_id, required=0):
    """A content meta record: contents [(nca bytes, record type, id offset)]; link_id: an application's patch ID, a patch's or an add-on's application ID"""
    if kind == APPLICATION: ext = struct.pack('<QII', link_id, 0, required)
    elif kind == PATCH: ext = struct.pack('<QII8s', link_id, 0, 0, b'')
    else: ext = struct.pack('<QII Q', link_id, required, 0, 0)
    head = struct.pack('<QIBBHHHBBBBII', title_id, version, kind, 0, len(ext), len(contents), 0, 0, 0, 0, 0, 0, 0)
    recs = b''.join(sha(b) + content_id(b) + len(b).to_bytes(5, 'little') + bytes([0, rtype, off]) for b, rtype, off in contents)
    body = head + ext + recs
    return body + sha(body)


def cnmt_name(kind, title_id): return f'{ {APPLICATION: "Application", PATCH: "Patch", ADDON: "AddOnContent"}[kind]}_{title_id:016x}.cnmt'


def meta_nca(kind, title_id, version, contents, link_id, magic=b'DNCA', required=0):
    """The meta NCA (content type 1) of a record: one PFS0 section with the .cnmt"""
    rec = cnmt(kind, title_id, version, contents, link_id, required)
    return nca(META, title_id, [pfs_section(pfs0([(cnmt_name(kind, title_id), rec)]))], magic=magic)


def nca_name(b, meta=False): return content_id(b).hex() + ('.cnmt.nca' if meta else '.nca')
