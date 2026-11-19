#!/usr/bin/env python3
"""A small clean-room NX archive writer (github.com/fab918/nxformat docs/SPEC.md 1.0.4, header version (0,2)), for test fixtures only: folder dumps in the
layout scripts/pack_dump.py reads (base/, updates/<version>/, dlc/<title id>/<version>/, the console's layout or the flattened one) become one `.nx`. Not a tool
for users: they make their archives with NXConvert (the format's reference packer). Written from the spec alone, so dm/tools/nx.py, the host's reader and the
launcher's are checked against a second implementation, not against themselves.

  nx_pack.py <dump folder> [<dump folder> ...] <out.nx> [--codec auto|raw|zstd] [--chunk-log2 16..20] [--no-dedup] [--second-generation]
             [--nca [--nca-magic DNCA|NCA3] [--nca-compress]] [--nca-tree] [--app-id HEX] [--extension TYPE[:required]] [--level N]

Each dump folder is one application (several folders: a collection, which the readers refuse). Each version folder's package (its exefs/ and romfs/, and data/
and program<n>/ when they are there) is a snapshot's root: base/ → base (type 0), updates/<v>/ → update (1; standalone update, 3, when there is no base/),
dlc/<title id>/... → DLC (2) with the add-on title ID (flag ADDON_TITLE_ID). The application ID is program 0's main.npdm program ID (--app-id for a dump
without one). In the console's layout each program/<n>/ of a version folder is a program of its own (an NxProgram with its main.npdm's ID, its own snapshots;
updates of a program without a base are standalone updates), as NXConvert's folder import makes them; a flattened program<n>/ stays a folder in program 0's tree.
  --codec        auto (default): each block one zstd frame when that shrinks it, else RAW; raw: every block RAW (no zstd module needed); zstd: every block a frame
  --chunk-log2   the block size, 2^N bytes (default 16: 64 KiB, so small fixtures still have files of several blocks)
  --no-dedup     no shared blocks and no shared file records (by default equal chunks share one block, and an unchanged file at the same path one file record)
  --second-generation  write generation 0 without the last snapshot, then append that snapshot's blocks and a second tail as generation 1 in header slot B
                 (the append protocol, SPEC §8); slot A keeps generation 0
  --nca          NCA snapshots (android/NX.md "NCA snapshots"), as NXConvert's native import keeps a decrypted NSP: each snapshot's root holds plain NCAs
                 (`<content ID>.nca`, `<content ID>.cnmt.nca`; dm/tools/nca_pack.py). The base: an application CNMT (0x80), a Program NCA per program (content
                 index and CNMT ID offset n; an ExeFS PartitionFS section under HierarchicalSha256 and a RomFS section under IVFC, the RomFS built from the
                 folder's romfs/), a control NCA (data/, else a blank control.nacp). Each update: a patch CNMT (0x81, ID app + 0x800, its version) with its
                 Program NCAs, whose RomFS section is a BKTR indirect storage over the base's RomFS section (storage 0: each file whose bytes the base has;
                 storage 1: the rest) when the base has that program, else a whole section; a control NCA; and the base's NCAs and CNMT as files beside them.
                 Each add-on: an add-on CNMT (0x82, its application app) and a data NCA (RomFS), its DLC row native (content ID, no ADDON_TITLE_ID).
                 Program n is the console layout's program/<n>/ (or a flattened program<n>/): its Program NCA has the program's main.npdm ID (else
                 app | n), content index n and CNMT ID offset n. CNMT content
                 record types are ncm's: 1 program, 2 data, 3 control.
    --nca-magic  the header magic at 0x200: DNCA (NXConvert's decrypter, the default) or NCA3
    --nca-compress   write the RomFS sections of the add-ons, and of the base when the dump has no update, through compressed storage: 64 KiB blocks of
                 LZ4 (the lz4 module's blocks; without it nca_pack.lz4_compress's), zeros (type 1) or none (type 0)
  --nca-tree     take each version folder's files as they are as the snapshot's root (fake `.nca` files, which the readers refuse as not plain NCAs)
  --extension    add a Catalog extension of that type ID (hex) with an 8-byte zero payload, owned by application 0; `:required` sets its required flag
Needs compression.zstd (Python 3.14) or backports.zstd unless --codec raw. Exit 1 on error.
"""
import argparse, hashlib, os, re, struct, sys
from pathlib import Path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'scripts'))
import nx            # the record layouts and CRC-32C (one definition of the bytes; the writer's choices are its own)
import pack_dump     # the dump layout (package_root, dlc_romfs)

NONE = 0xFFFFFFFF
EMPTY_SHA = hashlib.sha256().digest()


def align(n, a=16): return (n + a - 1) // a * a


class Dir:
    def __init__(self): self.kids = {}   # name bytes → Dir or File


class File:
    def __init__(self, path, size): self.path, self.size = path, size


class MemFile:   # a file written from memory (an NCA of --nca)
    def __init__(self, data): self.data, self.size = data, len(data)


def file_bytes(f):
    if isinstance(f, MemFile): return f.data
    with open(f.path, 'rb') as h: return h.read()


def as_dict(d):
    """A Dir as nca_pack's tree: {name: dict | bytes}"""
    return {n.decode(): as_dict(k) if isinstance(k, Dir) else file_bytes(k) for n, k in d.kids.items()}


def to_nca(app, snaps, magic=b'DNCA', compress=False):
    """The snapshots of a dump as NXConvert's native import keeps a decrypted NSP (android/NX.md "NCA snapshots"): each snapshot's root holds its NCAs as
    <content ID>.nca and <content ID>.cnmt.nca. The base: an application record with a Program NCA per program (ExeFS PartitionFS section, RomFS section; content
    index and ID offset n), a control NCA. Each update: a patch record (ID app + 0x800) with its Program NCAs, each RomFS a BKTR patch over the base's section
    of the same program (a program the base does not have: a whole section), a control NCA, and the base's NCAs and record beside them (the complete view).
    Each add-on: an add-on record and a data NCA. compress: the RomFS sections of the base (when there is no update to patch it) and of the add-ons are
    written through compressed storage (LZ4 blocks, zeros, none)"""
    import nca_pack as P
    lz = None
    if compress:
        try:
            import lz4.block
            lz = lambda b: lz4.block.compress(b, store_size=False)
        except ImportError: lz = P.lz4_compress   # (real LZ4 blocks from nca_pack's own compressor)
    def parts(s):   # {program index: (exefs, romfs, data)} of a snapshot: its own program and the flattened program<n>/ folders in it
        def of(d):
            k = d.kids
            return (as_dict(k[b'exefs']) if b'exefs' in k else {}, as_dict(k[b'romfs']) if b'romfs' in k else {}, as_dict(k[b'data']) if b'data' in k else None)
        out = {s.program: of(s.root)}
        for name, k in s.root.kids.items():
            m = re.fullmatch(rb'program([1-9]|1[0-5])', name)
            if m and isinstance(k, Dir): out.setdefault(int(m.group(1)), of(k))
        return out
    def snap(typ, version, files, addon=None):
        d = Dir()
        for n, b in files: d.kids[n.encode()] = MemFile(b)
        s = Snap(typ, version, d, addon); s.pid = app if addon is None else None
        return s
    def control(data):
        tree = data if data else {'control.nacp': bytes(0x4000)}
        fh, sec, _ = P.romfs_section(P.romfs_image(tree)[0])
        return P.nca(P.CONTROL, app, [(fh, sec)], magic=magic)
    def exefs(ex): return P.pfs_section(P.pfs0(sorted((n, b) for n, b in ex.items() if not isinstance(b, dict))))
    bases, ups, pids = {}, {}, {}
    for s in snaps:
        if s.addon is not None: continue
        (bases if s.type == 0 else ups.setdefault(s.version, {})).update(parts(s))
        if s.pid: pids.setdefault(s.program, s.pid)
    def pid_of(n, ex):   # program n's ID: its snapshot's (main.npdm's), a flattened program<n>/'s own main.npdm's, else app | n
        if n in pids: return pids[n]
        npdm = ex.get('main.npdm', b'')
        at = npdm.find(b'ACI0') if isinstance(npdm, bytes) else -1
        return struct.unpack_from('<Q', npdm, at + 0x10)[0] if 0 <= at and at + 0x18 <= len(npdm) else app | n
    has_update = bool(ups)
    out, base_files, base_prog = [], [], {}
    if bases:
        contents = []
        for n, (ex, ro, data) in sorted(bases.items()):
            img, fmap = P.romfs_image(ro)
            if compress and not has_update: fh, sec, _ = P.compressed_romfs_section(img, lz4_compress=lz)
            else: fh, sec, l6 = P.romfs_section(img); base_prog[n] = (sec, l6, fmap)
            contents.append((P.nca(P.PROGRAM, pid_of(n, ex), [exefs(ex), (fh, sec)], index=n, magic=magic), P.R_PROGRAM, n))
        contents.append((control(bases.get(0, ({}, {}, None))[2]), P.R_CONTROL, 0))
        meta = P.meta_nca(P.APPLICATION, app, 0, contents, app + 0x800, magic)
        base_files = [(P.nca_name(meta, True), meta)] + [(P.nca_name(b), b) for b, _, _ in contents]
        out.append(snap(0, 0, base_files))
    for version, prog in sorted(ups.items()):
        contents = []
        for n, (ex, ro, data) in sorted(prog.items()):
            img, fmap = P.romfs_image(ro)
            if n in base_prog: fh, sec, _, _ = P.patched_romfs_section(img, *base_prog[n], fmap)
            else: fh, sec, _ = P.romfs_section(img)
            contents.append((P.nca(P.PROGRAM, pid_of(n, ex), [exefs(ex), (fh, sec)], index=n, magic=magic), P.R_PROGRAM, n))
        contents.append((control(prog.get(0, ({}, {}, None))[2]), P.R_CONTROL, 0))
        meta = P.meta_nca(P.PATCH, app + 0x800, version, contents, app, magic)
        out.append(snap(1 if bases else 3, version, [(P.nca_name(meta, True), meta)] + [(P.nca_name(b), b) for b, _, _ in contents] + base_files))
    for s in snaps:
        if s.addon is None: continue
        img, _ = P.romfs_image(parts(s)[s.program][1])
        fh, sec, _ = P.compressed_romfs_section(img, lz4_compress=lz) if compress else P.romfs_section(img)
        data = P.nca(P.DATA, s.addon, [(fh, sec)], magic=magic)
        meta = P.meta_nca(P.ADDON, s.addon, s.version, [(data, P.R_DATA, 0)], app, magic)
        d = snap(2, s.version, [(P.nca_name(meta, True), meta), (P.nca_name(data), data)], s.addon)
        d.nca_cid = P.content_id(meta)   # a native DLC row: its content ID, no ADDON_TITLE_ID
        out.append(d)
    return out


def tree(path, skip=('.listing',)):
    d = Dir()
    with os.scandir(path) as it:
        for e in it:
            if e.name in skip: continue
            if e.is_dir(): d.kids[e.name.encode()] = tree(e.path)
            elif e.is_file(): d.kids[e.name.encode()] = File(e.path, e.stat().st_size)
    return d


def npdm_program_id(path):
    try: data = open(path, 'rb').read()
    except OSError: return 0
    off = data.find(b'ACI0')
    return struct.unpack_from('<Q', data, off + 0x10)[0] if 0 <= off and off + 0x18 <= len(data) else 0


class Snap:
    def __init__(self, typ, version, root, addon=None, program=0):
        self.type, self.version, self.root, self.addon, self.program, self.pid = typ, version, root, addon, program, None


def programs_of(v):
    """[(program index, its package folder)] of a version folder: in the console's layout each program/<n>/ (program/<n>/program/ when a data content shares
    the index), else the folder as pack_dump.package_root finds it, as program 0"""
    found = []
    if (v / 'program').is_dir():
        for c in sorted((c for c in (v / 'program').iterdir() if c.is_dir() and c.name.isdigit()), key=lambda c: int(c.name)):
            r = next((r for r in (c, c / 'program') if (r / 'exefs').is_dir() or (r / 'romfs').is_dir()), None)
            if r is not None: found.append((int(c.name), r))
    return found or [(0, pack_dump.package_root(v))]


def discover(src, nca_tree=False, app_id=None):
    """(application ID, [Snap]) of a dump folder. Each program of the console's layout (program/<n>/) is a program of its own (NxProgram); its ID is its
    main.npdm's, else the application's with n in the low byte"""
    src = Path(src)
    snaps = discover_programs(src, nca_tree)
    pids = {}
    for s in snaps:
        if s.addon is None and s.program not in pids and (pid := npdm_program_id(s.path / 'exefs/main.npdm')): pids[s.program] = pid
    app = app_id or pids.get(0)
    if not app: raise SystemExit(f'{src}: no exefs/main.npdm of program 0 to take the application ID from (--app-id)')
    for s in snaps:
        if s.addon is None: s.pid = pids.get(s.program, app | s.program)
    return app, snaps


def discover_programs(src, nca_tree):
    def content(p):
        p = Path(p)
        if nca_tree: return tree(p)
        root = Dir()
        for sub in ('exefs', 'romfs', 'data'):
            if (p / sub).is_dir(): root.kids[sub.encode()] = tree(p / sub)
        for c in sorted(p.iterdir()) if p.is_dir() else []:
            if c.is_dir() and re.fullmatch(r'program([1-9]|1[0-5])', c.name): root.kids[c.name.encode()] = tree(c)
        return root
    vers = [(True, 0, src / 'base')] if (src / 'base').is_dir() else []   # (is the base, version, folder)
    if (src / 'updates').is_dir(): vers += [(False, int(v), src / 'updates' / v) for v in sorted((v for v in os.listdir(src / 'updates') if v.isdigit()), key=int)]
    found = [(is_base, ver, n, r) for is_base, ver, v in vers for n, r in ([(0, v)] if nca_tree else programs_of(v))]
    based = {n for is_base, _, n, _ in found if is_base}   # programs with a base: their updates are type 1, the others' standalone updates (type 3)
    snaps = []
    for is_base, ver, n, r in found:
        s = Snap(0 if is_base else 1 if n in based else 3, ver, content(r), program=n); s.path = r
        snaps.append(s)
    if (src / 'dlc').is_dir():
        for t in sorted(t for t in os.listdir(src / 'dlc') if re.fullmatch(r'[0-9a-fA-F]{16}', t)):
            rom = pack_dump.dlc_romfs(src / 'dlc' / t)
            snaps.append(Snap(2, 0, content(rom.parent), int(t, 16)))
    if not snaps: raise SystemExit(f'{src}: no base/, updates/<version>/ or dlc/<title id>/ to pack')
    return snaps


class Writer:
    def __init__(self, out, codec='auto', chunk_log2=16, dedup=True, level=3):
        self.f, self.codec, self.log2, self.dedup, self.level = out, codec, chunk_log2, dedup, level
        self.blocks = []        # (offset, stored, logical, codec, crcs)
        self.by_hash = {}       # sha256 of a chunk → [(block, bytes)]
        self.files = {}         # (application, relative path, size, sha) → file key (shared file records)
        self.pos = nx.DATA_START
        self.z = None
        if codec != 'raw':
            try: from compression import zstd
            except ImportError: from backports import zstd
            self.z = zstd

    def compress(self, data):
        z = self.z
        c = z.ZstdCompressor(options={z.CompressionParameter.compression_level: self.level, z.CompressionParameter.window_log: max(10, min(20, self.log2))})
        c.set_pledged_input_size(len(data))
        return c.compress(data, z.ZstdCompressor.FLUSH_FRAME)

    def block(self, data):
        """The block of chunk data, written when no equal one is there"""
        h = hashlib.sha256(data).digest()
        if self.dedup:
            for b, d in self.by_hash.get(h, []):
                if d == data: return b   # the bytes compared, not only the hash (SPEC §7)
        stored, codec = data, 0
        if self.codec != 'raw':
            comp = self.compress(data)
            if self.codec == 'zstd' or len(comp) < len(data): stored, codec = comp, 1
        off = align(self.pos)
        self.f.seek(off); self.f.write(stored); self.pos = off + len(stored)
        crcs = [nx.crc32c(data[k:k + nx.CHECK_SIZE]) for k in range(0, len(data), nx.CHECK_SIZE)]
        self.blocks.append((off, len(stored), len(data), codec, crcs))
        b = len(self.blocks) - 1
        self.by_hash.setdefault(h, []).append((b, data))
        return b

    def file(self, f):
        """(size, sha256, [blocks]) of a source file"""
        if hasattr(f, 'nx'): return f.nx
        chunk, ids, h = 1 << self.log2, [], hashlib.sha256()
        if isinstance(f, MemFile):
            for i in range(0, len(f.data), chunk): data = f.data[i:i + chunk]; h.update(data); ids.append(self.block(data))
        else:
            with open(f.path, 'rb') as src:
                while data := src.read(chunk): h.update(data); ids.append(self.block(data))
        f.nx = (f.size, h.digest(), ids)
        return f.nx

    def write_blocks(self, d):
        for k in d.kids.values():
            if isinstance(k, Dir): self.write_blocks(k)
            else: self.file(k)


def cid_of(s):   # a DLC row's content_id: the add-on title ID (ADDON_TITLE_ID), or a native DLC's content ID (--nca)
    return getattr(s, 'nca_cid', None) or struct.pack('<Q', s.addon) + bytes(8)


def build_tail(w, apps, gen_snaps, extensions):
    """The Catalog, Manifest, block index and integrity table of one generation: apps [(application ID, [Snap])], gen_snaps the Snaps it holds"""
    # blocks referenced by this generation, in offset order (block IDs are ranks by offset)
    used = set()
    def mark(d):
        for k in d.kids.values():
            if isinstance(k, Dir): mark(k)
            else: used.update(k.nx[2])
    for _, snaps in apps:
        for s in snaps:
            if s in gen_snaps: mark(s.root)
    order = sorted(used, key=lambda b: w.blocks[b][0])
    bid = {b: i for i, b in enumerate(order)}

    # Manifest: directories breadth first (each directory's entries one contiguous run), files in order of first use; equal files at the same path of one
    # application share one record (SPEC §6), unless --no-dedup
    dirs, entries, files, file_key, refs, pool, pool_at = [], [], [], {}, [], bytearray(), {}
    def name_ref(n):
        if n not in pool_at: pool_at[n] = len(pool); pool.extend(n)
        return pool_at[n]
    def file_id(app, rel, f):
        size, sha, ids = f.nx
        key = (app, rel, size, sha) if w.dedup else id(f)
        if key not in file_key:
            file_key[key] = len(files); files.append((size, len(refs), len(ids), sha)); refs.extend(bid[b] for b in ids)
        return file_key[key]
    roots = {}
    for ai, (app, snaps) in enumerate(apps):
        for s in snaps:
            if s not in gen_snaps: continue
            roots[id(s)] = len(dirs)
            dirs.append(None)
            pending = [(len(dirs) - 1, s.root, b'')]
            while pending:
                di, d, rel = pending.pop(0)
                first = len(entries)
                kids = sorted(d.kids.items())
                slots = []
                for n, k in kids:
                    p = rel + b'/' + n if rel else n
                    if isinstance(k, Dir):
                        dirs.append(None); slots.append((n, 1, len(dirs) - 1)); pending.append((len(dirs) - 1, k, p))
                    else: slots.append((n, 2, file_id((app, s.pid), p, k)))
                entries.extend(slots)
                dirs[di] = (first, len(kids))
    # entry ranges partition the entry table in directory order: breadth first, a directory's entries are written in the order of the IDs given
    nd, nf, ne, nr = len(dirs), len(files), len(entries), len(refs)
    o_dir = 96; o_file = align(o_dir + 16 * nd, 8) if nf else 0
    o_ent = align((o_file + 64 * nf) if nf else (o_dir + 16 * nd), 8) if ne else 0
    last = (o_ent + 24 * ne) if ne else ((o_file + 64 * nf) if nf else (o_dir + 16 * nd))
    o_ref = align(last, 4) if nr else 0
    last = (o_ref + 4 * nr) if nr else last
    o_pool = align(last, 8)
    man = bytearray(o_pool + len(pool))
    for i, (first, count) in enumerate(dirs): nx.DIRECTORY.pack_into(man, o_dir + 16 * i, first, count, 0, 0)
    for i, (size, first, count, sha) in enumerate(files): nx.FILE.pack_into(man, o_file + 64 * i, size, first, count, w.log2, 0, 0, sha, b'')
    for i, (n, kind, target) in enumerate(entries): nx.ENTRY.pack_into(man, o_ent + 24 * i, o_pool + name_ref(n), len(n), target, kind, 0, 0, 0)
    for i, b in enumerate(refs): struct.pack_into('<I', man, o_ref + 4 * i, b)
    man[o_pool:] = pool
    nx.MANIFEST.pack_into(man, 0, b'NXMF', 1, 96, len(man), nd, nf, ne, nr, o_dir, o_file, o_ent, o_ref, o_pool, len(pool), b'')

    # Catalog: one program per program ID with game snapshots (ascending by ID, all primary as NXConvert writes them), each with its snapshots by type and
    # version; DLC rows without a program after them
    app_rows, prog_rows, snap_rows, ext_rows = [], [], [], []
    for ai, (app, snaps) in enumerate(apps):
        game = [s for s in snaps if s in gen_snaps and s.type != 2]
        dlc = sorted((s for s in snaps if s in gen_snaps and s.type == 2), key=lambda s: (s.version, cid_of(s)))
        first, first_prog = len(snap_rows), len(prog_rows)
        rows = []
        for pid in sorted({s.pid for s in game}):
            mine = sorted((s for s in game if s.pid == pid), key=lambda s: (s.type, s.version))
            prog_rows.append((pid, ai, 0, 0, 0, bytes(16), first + len(rows), len(mine), NONE, 0, b''))
            rows += [(len(prog_rows) - 1, s) for s in mine]
        rows += [(NONE, s) for s in dlc]
        for pi, s in rows:
            cid = cid_of(s) if s.type == 2 else bytes(16)
            snap_rows.append((pi, s.type, 2 if s.type == 2 and not hasattr(s, 'nca_cid') else 0, 0, s.version, roots[id(s)], logical(s.root), NONE, 0, cid, 0, 0,
                              identity(app, s.pid if s.type != 2 else None, s, cid), bytes(32)))
        flags = (1 if any(s.type == 0 for s in game) else 0) | (2 if dlc else 0)
        nprog = len(prog_rows) - first_prog
        app_rows.append((app, 2, flags, 0, first_prog if nprog else NONE, nprog, first, len(rows), NONE, 0, NONE, 0, NONE,
                         0, 0, NONE, 0, 0, NONE, b''))
    for tid, req in extensions: ext_rows.append((tid, 1 if req else 0, 1, 0, 0))
    o_app = 128; o_prog = align(o_app + 80 * len(app_rows), 8) if prog_rows else 0
    last = (o_prog + 64 * len(prog_rows)) if prog_rows else o_app + 80 * len(app_rows)
    o_snap = align(last, 8); last = o_snap + 120 * len(snap_rows)
    o_ext = align(last, 8) if ext_rows else 0
    last = (o_ext + 32 * len(ext_rows)) if ext_rows else last
    o_pool = align(last, 8); last = o_pool   # an empty string pool
    payloads = []
    for _ in ext_rows: payloads.append(align(last, 8)); last = payloads[-1] + 8
    cat = bytearray(last if last >= 128 else 128)
    nx.CATALOG.pack_into(cat, 0, b'NXCT', 1, 128, len(cat), 0, len(app_rows), len(prog_rows), len(snap_rows), 0, 0, len(ext_rows), 0,
                         o_app, o_prog, o_snap, 0, 0, o_ext, o_pool, 0, b'')
    for i, r in enumerate(app_rows): nx.APP.pack_into(cat, o_app + 80 * i, *r)
    for i, r in enumerate(prog_rows): nx.PROGRAM.pack_into(cat, o_prog + 64 * i, *r)
    for i, r in enumerate(snap_rows): nx.SNAPSHOT.pack_into(cat, o_snap + 120 * i, *r)
    for i, (tid, flags, owner, oidx, _) in enumerate(ext_rows): nx.EXTENSION.pack_into(cat, o_ext + 32 * i, tid, flags, owner, 0, oidx, payloads[i], 8)

    index = b''.join(nx.BLOCK.pack(w.blocks[b][0], w.blocks[b][1], w.blocks[b][2], w.blocks[b][3], 0, 0, 0) for b in order)
    first, idx, integ = 0, bytearray(index), bytearray()
    for i, b in enumerate(order):
        struct.pack_into('<I', idx, 24 * i + 20, first)
        crcs = w.blocks[b][4]; integ += struct.pack(f'<{len(crcs)}I', *crcs); first += len(crcs)
    data_end = (w.blocks[order[-1]][0] + w.blocks[order[-1]][1]) if order else 0
    return bytes(cat), bytes(man), bytes(idx), bytes(integ), len(order), data_end


def logical(d):
    return sum(logical(k) if isinstance(k, Dir) else k.size for k in d.kids.values())


def identity(app, program, s, cid):
    nodes = []
    def walk(d, path):
        nodes.append((path, 1, None))
        for n, k in d.kids.items():
            p = path + b'/' + n if path else n
            if isinstance(k, Dir): walk(k, p)
            else: nodes.append((p, 2, k.nx))
    walk(s.root, b'')
    nodes.sort(key=lambda t: t[0])
    h = hashlib.sha256(b'NX-SNAPSHOT-ID\0' + struct.pack('<HBQQBI', 3, 2, app, program if program is not None else 0xFFFFFFFFFFFFFFFF, s.type, s.version)
                       + cid + struct.pack('<I', len(nodes)))
    for path, kind, f in nodes:
        h.update(struct.pack('<BI', kind, len(path)) + path)
        if f: h.update(struct.pack('<Q', f[0]) + f[1])
    return h.digest()


def write_tail(f, w, tail, generation):
    """Writes one generation's tail at the next 16-byte boundary after the data written so far; returns its 256-byte header"""
    cat, man, idx, integ, nblocks, last_block_end = tail
    off = align(max(w.pos, nx.DATA_START))
    o_cat = off; o_man = align(o_cat + len(cat)); end = o_man + len(man)
    o_idx = align(end) if nblocks else 0
    o_int = align(o_idx + len(idx)) if nblocks else 0
    end = o_int + len(integ) if nblocks else end
    f.seek(o_cat); f.write(cat)
    f.seek(o_man); f.write(man)
    if nblocks: f.seek(o_idx); f.write(idx); f.seek(o_int); f.write(integ)   # the gaps between are zeros (written past the end)
    w.pos = end
    h = bytearray(nx.HEADER.pack(b'NXAR', 0, 2, 256, 1, 0, end, o_cat, len(cat), 0, 0, nx.DATA_START if nblocks else 0,
                                 (last_block_end - nx.DATA_START) if nblocks else 0, o_man, len(man), o_idx, nblocks, o_int, len(integ),
                                 hashlib.sha256(cat).digest(), hashlib.sha256(man).digest(), hashlib.sha256(idx + integ).digest(), generation, nx.CHECK_SIZE,
                                 bytes(24), 0))
    struct.pack_into('<I', h, 252, nx.crc32c(bytes(h[:252])))
    return bytes(h)


def pack(srcs, out, codec='auto', chunk_log2=16, dedup=True, second_generation=False, nca_tree=False, app_id=None, extensions=(), level=3,
         nca=False, nca_magic='DNCA', nca_compress=False):
    """Writes the .nx of dump folders srcs to out (see the module's text); returns out"""
    if not 16 <= chunk_log2 <= 20: raise SystemExit('--chunk-log2 must be 16 to 20')
    apps = sorted((discover(s, nca_tree, app_id) for s in srcs), key=lambda a: a[0])
    if len({a for a, _ in apps}) != len(apps): raise SystemExit('two dump folders of one application')
    if nca: apps = [(a, to_nca(a, snaps, nca_magic.encode(), nca_compress)) for a, snaps in apps]
    every = [s for _, snaps in apps for s in snaps]
    phases = [every[:-1], every] if second_generation else [every]
    if second_generation and len(every) < 2: raise SystemExit('--second-generation needs at least two snapshots')
    with open(out, 'w+b') as f:
        f.write(bytes(nx.DATA_START))
        w = Writer(f, codec, chunk_log2, dedup, level)
        headers = []
        for g, snaps in enumerate(phases):
            for s in snaps: w.write_blocks(s.root)
            tail = build_tail(w, apps, set(snaps), extensions)
            headers.append(write_tail(f, w, tail, g))
        a = headers[0]; b = headers[-1]
        f.seek(0); f.write(a); f.seek(nx.PAGE); f.write(b)
        f.truncate(w.pos)
    return out


def main():
    ap = argparse.ArgumentParser(description='A clean-room NX archive writer for test fixtures (not for users): dump folders to one .nx. See the module text.',
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument('paths', nargs='+', help='one or more dump folders, then the .nx to write')
    ap.add_argument('--codec', choices=('auto', 'raw', 'zstd'), default='auto')
    ap.add_argument('--chunk-log2', type=int, default=16)
    ap.add_argument('--no-dedup', action='store_true')
    ap.add_argument('--second-generation', action='store_true')
    ap.add_argument('--nca-tree', action='store_true')
    ap.add_argument('--nca', action='store_true', help="NCA snapshots, as NXConvert's native import of a decrypted NSP (see the module text)")
    ap.add_argument('--nca-magic', choices=('DNCA', 'NCA3'), default='DNCA')
    ap.add_argument('--nca-compress', action='store_true')
    ap.add_argument('--app-id', type=lambda s: int(s, 16))
    ap.add_argument('--extension', action='append', default=[], help='TYPE[:required], TYPE in hex')
    ap.add_argument('--level', type=int, default=3)
    a = ap.parse_args()
    if len(a.paths) < 2: ap.error('give at least one dump folder and the .nx to write')
    *srcs, out = a.paths
    if os.path.exists(out): sys.exit(f'{out} exists')
    exts = [(int(e.split(':')[0], 16), e.endswith(':required')) for e in a.extension]
    try: pack(srcs, out, a.codec, a.chunk_log2, not a.no_dedup, a.second_generation, a.nca_tree, a.app_id, exts, a.level, a.nca, a.nca_magic, a.nca_compress)
    except BaseException:
        try: os.unlink(out)
        except OSError: pass
        raise
    print(f'{out}: {os.path.getsize(out)} bytes')


if __name__ == '__main__':
    main()
