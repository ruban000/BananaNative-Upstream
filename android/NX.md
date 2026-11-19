# NX archive support (`.nx`)

Play and prepare a game straight from one `.nx` file (github.com/fab918/nxformat, `docs/SPEC.md` release 1.0.4, header versions
`(0,2)` and `(0,3)`), with no unpacking, as a `.nup` is ([NUP.md](NUP.md)). The `.nx` is the user's dump: the app and the pack tools only
read it, and nothing here writes one except the test fixture writer (`dm/tools/nx_pack.py`). An `.nx` is an archive of games that are already
decrypted: it holds no keys and needs none.

## The format in one paragraph

Two 256-byte header slots (`NXAR`, at 0 and 4096; the valid one with the higher `generation` counts), the Data region of blocks from 8192, then the
tail: Media (icons, NACP, NPDM), the Catalog (`NXCT`: applications, programs, snapshots, extensions, string pool), the Manifest (`NXMF`:
directories, files, entries, block references, path pool), the hot block index (24 bytes a block) and the integrity table (one CRC-32C per 16 KiB
slice of each block's logical bytes). A **snapshot** is one complete version of a game: base, update, standalone update or DLC, each with the root
directory of its whole tree; an update's tree is the game as that update makes it (no layering), as a NUP update package is. A **file** is a list of
blocks of `1 << chunk_size_log2` bytes (64 KiB to 1 MiB, the last one shorter); a block is RAW or one Zstandard frame (no dictionary, window at most
1 MiB) and may be shared by any number of files and snapshots (deduplication). Header, Catalog, Manifest and index carry their own CRC-32C or SHA-256.

## What is read

Snapshots made from an extracted folder or a `.nup` (NXConvert `pack-tree`, `pack-nup`, or a folder dropped on the app): their root holds
`exefs/`, `romfs/`, `data/` (control data) and `program1` … `program15/` (the other programs of a multi-program game, each with its own
`exefs/` and `romfs/`). These are the layouts of a folder dump's version folder, so everything after a package's selector is the same path as inside
that folder.

Snapshots made from a decrypted NSP or XCI (NXConvert's native import, Switch 1 games only): their root holds the content's NCAs as
`<content ID>.nca` and `<content ID>.cnmt.nca`. They are read as described in [NCA snapshots](#nca-snapshots) below and give the same packages,
with the same `exefs/` and `romfs/` paths inside, as an extracted snapshot does.

Not read (the reader says which and why):

- Snapshot type 4 (unknown).
- In an NCA snapshot: an NCA whose header is not plain (`DNCA` or `NCA3` at 0x200, checked by its FS header hashes), a section whose encryption
  type is not 1 (none), a sparse section (FS header 0x148..0x178 not zero; NXConvert does not store one either), an NCZ.
- An archive with more than one application that has a base or update snapshot (a collection): the reader names the applications. DLC snapshots of
  other applications are ignored. An archive of DLC only (no base or update) has only `addon/` and `aoc/` packages, so a DLC `.nx` beside a game
  is found as a `.nup` of DLC is; with no selector it is refused ("no game").
- A selector that matches more than one snapshot (an update and a standalone update of one version, or rows that differ only in content ID or
  build ID): refused as ambiguous.
- Header versions other than `(0,2)` and `(0,3)`, unknown required features or required Catalog extensions (as the spec requires).

## Packages and names

A snapshot is a package, named as in a `.nup` (`<file>.nx:<selector>`, split at the last `.nx:`):

| snapshot (`NxSnapshot.snapshot_type`) | selector |
|---|---|
| 0 base | `base` |
| 1 update | `update/<numeric_content_version>` |
| 3 standalone update (no base in the archive) | `update/<numeric_content_version>`, a base-less update (as a NUP update package with flag 1) |
| 2 DLC with `ADDON_TITLE_ID` (flag bit 1) | `addon/<16 hex digits>`, the title ID from the first 8 bytes of `content_id` |
| 2 DLC of an NCA snapshot (no `ADDON_TITLE_ID`) | `addon/<16 hex digits>`, the title ID from its CNMT |

**Programs.** Each `NxProgram` of the game's application has its own packages. A program's index is its ID's low nibble (`program_id & 0xf`:
`040015100B514001` is program 1 of `010015100B514000`), the index of `pack_android.py --program`, `DM_ROMFS_PROGRAM_<n>` and a folder dump's
`program/<n>/`. A selector without a program names program 0: the program whose ID equals the application ID, else the lowest index (not
`program_kind`: NXConvert marks a Switch 2 Edition's program 1 as primary). A selector may start with `program<n>/` to name program n:
`game.nx:program1/update/327680/romfs` is program 1's update 327680. `program0/` is accepted and means program 0 (by the same rule, so it is
the lowest index when no program has index 0); n is 0 to 15 with no leading zeros, and a program the game does not have is refused. DLC packages belong to the
application and are the same under any program. Two programs with the same index are refused as ambiguous. The launcher has no `aoc/<index>`
selectors (only the host's add-on mounts use them).

Why: NXConvert gives every program its own `NxProgram` row and snapshots (`import/dump.rs` for a folder in the console's layout,
`import/manifest.rs` for a nested `program<n>/` view, `import/nup.rs` for a `.nup` whose program 1 is an update package of its own or a base of its
own), and keeps the application ID of the original game (`0100…`). A Switch 2 Edition from a console-layout folder therefore has program 1 only as a
program of its own; from a flattened folder or a `.nup` with nested `program<n>/`, also as a `program<n>/` folder in program 0's tree. Read from
NXConvert's source (release 1.0.4), not yet from an archive it wrote.

The package's root is the snapshot's root directory, so `game.nx:update/458752/romfs`, `game.nx:update/458752/exefs/main` and, where program 0's
tree has it, `game.nx:base/program1/romfs` name what they name in a folder dump. A Switch 2 Edition runs as from a folder: `DM_ROMFS` is
program 1's own RomFS (`game.nx:program1/update/<v>/romfs`) and `DM_ROMFS_PROGRAM_0` program 0's (`game.nx:update/<v>/romfs`); `pack_dump.program_dir`
and the launcher's `DumpLayout.programDir` find program n as `program<n>/` packages first, else as a `program<n>/` folder. Without a selector the RomFS is the highest-version update's, else the base's, as for a `.nup`. A program path always follows a package
selector: `:program1` alone is not a selector (`:program1/base`, `:program1/update/<v>` are), and `:base/program1/romfs` is the folder in program
0's base. A `.nup` has neither form.

Everywhere a `.nup` path is taken, an `.nx` path is taken: `DM_ROMFS`, `DM_ROMFS_PROGRAM_<n>`, `DM_AOC_DIR/<index>.nx` (a file or a link to the
`.nx`, the add-on whose title ID's low 12 bits are `<index>`), `pack.tsv`'s `romfs` and `aoc` rows, the tools' `romfs.py` paths, `DM_NUP_FD`
and `fd:<n>/<name>` for a file used where it is. The extension is lower case, as `.nup`'s.

## NCA snapshots

A snapshot from NXConvert's native import is the content of one CNMT, decrypted, with its NCAs as files at the snapshot's root (no folders). An
update's snapshot also holds the NCAs of the base it needs (the spec's "complete view": NXConvert stores the whole required content set, so a
patch never needs a file from another snapshot). Each reader turns such a snapshot into the same package tree an extracted one has: `exefs/`
(the files of the Program NCA's PartitionFS section) and `romfs/` (the RomFS of its RomFS section), for each program. The plaintext-NSP readers
([NUP.md, "A plaintext NSP"](NUP.md#a-plaintext-nsp-read-where-it-is): `dm/tools/nsp.py`, `dm/host/nsp_toc.h`, `Nsp.java`) already read the
header, CNMT, PartitionFS and RomFS of such NCAs and are the model; what is new is reading them through the `.nx` file reader, and two storage
layers they refuse: a patch (an update's RomFS over its base) and compressed storage.

- **Header.** 0xC00 bytes, plain: `DNCA` (NXConvert's decrypter's marker) or `NCA3` at 0x200. Content type at 0x205 (0 program, 1 meta, 2 control,
  4 data), program ID at 0x210, content index at 0x218 (the program index of a Program NCA), section table at 0x240 (four entries of start and end
  in 0x200-byte units), each FS header (0x200 bytes at 0x400 + 0x200·i) checked against its SHA-256 at 0x280 + 0x20·i. FS header: version 2 at 0,
  type at 2 (0 RomFS, 1 PartitionFS), hash type at 3, encryption type at 4 (must be 1), hash data at 8 (PartitionFS: the PFS0 region at
  0x38/0x40 of the hash data, i.e. FS header 0x40/0x48; RomFS: IVFC, level 6's offset and size are the RomFS), patch info at 0x100, sparse
  info at 0x148, compression info at 0x178. A Program NCA's first PartitionFS section is its ExeFS and its first RomFS section its RomFS (as
  `Nsp.java` reads them); a control NCA's RomFS is the package's `data/` in the tools.
- **CNMT.** The `.cnmt.nca` (content type 1) holds a PartitionFS with one `*.cnmt`: title ID, version, type (0x80 application, 0x81 patch,
  0x82 add-on), its application or patch ID, and the content records (content ID → the file `<32 hex>.nca` in the snapshot, record type (ncm):
  1 program, 2 data, 3 control, 6 delta fragment, which is not read; ID offset: the program index). The snapshot's package comes from the CNMT:
  application → `base`, patch → `update/<version>`, add-on → `addon/<title ID>`; the Program NCA with ID offset n gives `program<n>/` packages
  (n = 0: the plain selectors), exactly as the extracted rules above. The NCAs a record names must be in the snapshot; one that is not is a
  refusal naming it (a delta fragment is not needed). The base NCAs in an update's snapshot are named by the base's own CNMT, also there.
- **Patch (an update's RomFS).** The update's Program NCA has a RomFS section whose patch info (FS header 0x100) has a `BKTR` indirect table
  (offset, size, header: `BKTR`, version 1, entry count) and an AesCtrEx table, which is ignored (the NCA is decrypted). The patched section is an
  indirect storage whose virtual size is the bucket tree's end offset (the L1 node header); storage 1 is the update's section up to the indirect
  table's offset. Entries of 0x14 bytes (virtual offset s64, physical offset s64, storage
  index s32) in buckets of 0x4000 bytes (a 0x10-byte header: index, entry count, end offset), after the node area (one 0x4000-byte L1 node, plus
  L2 nodes when there are more buckets than one node holds). Storage 0 is the base's RomFS section (the base Program NCA of the same program
  index, named by the base CNMT in the snapshot), storage 1 the update's own section. The RomFS is level 6 of the update's IVFC, in the patched
  storage. The ExeFS of an update is the update Program NCA's own PartitionFS.
- **Compressed storage.** When the compression info (FS header 0x178: table offset s64, size s64, `BKTR` header with entry count) is set, the
  data the hash layer gives (the PartitionFS region, or IVFC level 6) is read through it: the table offset and the entries' physical offsets are
  relative to that data. Entries of 0x18 bytes (virtual offset s64, physical offset s64, compression type u8: 0 none, 1 zeros, 3 LZ4 block;
  compression level s8; reserved u16; physical size u32), in the same bucket layout. An LZ4 block decoder is written into each reader (no library).
- **Layer order** (Atmosphère `libraries/libstratosphere/source/fssystem/fssystem_nca_file_system_driver.cpp`, `OpenStorageImpl` /
  `CreateStorageByRawStorage`, read 2026-11-19): raw section (sparse; flattened here) → decryption (none here) → indirect patch → hash layer
  (HierarchicalSha256 or IVFC: its data region) → compressed storage. Each reader cites it where it builds the chain.
- **Hashes.** The NCA's own hash levels (HierarchicalSha256, IVFC) are not checked on reads: the `.nx` blocks are already checked by their CRC-32C
  and the files by `verify`'s SHA-256, which covers the NCA files whole.
- **Catalog and CNMT agree.** The Catalog row's type and version choose the snapshot; its CNMT must agree (a base an application CNMT, an update a
  patch CNMT of that version, an add-on an add-on CNMT of that title ID), else it is refused. An NCA snapshot has no `program<n>/` folders:
  its programs are packages (`:program1/base`), and the host refuses `:base/program1/romfs` there with that hint.
- **DLC.** An add-on's snapshot: its CNMT (type 0x82) and a data NCA (content type 4) with a RomFS section: `addon/<title ID>`, `romfs/` only.
- **What the host reads.** Only RomFS trees (as for a `.nup`); the tools and the launcher also read `exefs/` and the CNMT. Each RomFS file of an
  NCA snapshot maps to a byte range of the RomFS, which maps to ranges of the NCA file (through the patch and compression tables when there are
  any), which map to `.nx` blocks.

## Reading

- **Open.** Read both header pages, keep the valid slot with the higher generation (identical when equal), check the header CRC-32C, versions,
  features, reserved bytes and section ranges, read the tail, check `catalog_sha256`, `manifest_sha256` and `index_sha256` before any value of those
  sections is used, then the Catalog and Manifest structure the reader relies on (ranges, counts, names, sorted children, no cycles, block references
  in range, each file's block count and sizes). A damaged or cut archive is refused at open with what failed, not at a read. When the newer
  generation's metadata fails its hashes, the older generation is used (with a `[RED]` line in the host), as the spec allows. Snapshot identities
  (spec §12) are not recomputed at open (only `verify` does), and the Media region is only range-checked.
- **Reads.** A read of `[off, off + len)` of a file maps to blocks arithmetically (`off >> chunk_size_log2`), reads each RAW block's bytes with one
  positioned read, or decodes a ZSTD block whole (at most 1 MiB) and copies the requested part. Decoded blocks go to a small least-recently-used cache
  (the host: `DM_NUP_CACHE_MB`, as for a `.nup`), since games read files in pieces. No file is ever decompressed whole, so an `.nx` needs neither the
  `.nup` reader's whole-file buffers nor its temporary files for large files.
- **Integrity.** Each block read is checked against its 16 KiB slices' CRC-32C (the host: the ARMv8 CRC instructions when the CPU has them, else a
  table), and a mismatch is a read error with a `[RED]` line naming the file and block, not wrong bytes handed to the game. The launcher checks
  the slices of what it reads. The Python tools do not check reads (`nx.CHECK_READS`: Python has only a slow table CRC-32C), as they do not for a
  `.nup`; their `verify` checks every slice and every file's SHA-256.
- **Metadata.** The title, publisher and icon on the game cards come from the Catalog (the application's `default_title`, `publisher`,
  `default_icon_media_index`, the localized entries), the NPDM and build IDs from the files themselves (`exefs/main.npdm`, the NSO headers).

## Readers

- Host: `dm/host/nx_image.h` (an image whose file entries are block lists, `IMG_ENT_NX`), opened by `hle_fs.c` wherever a `.nup` is.
- Tools: `dm/tools/nx.py` (reader and CLI: `packages`, `ls`, `cat`, `verify`), used by `nup.py` and `romfs.py` for paths with `.nx:`, so
  `pack_android.py`, `pack_dump.py` and `shader_split.py` take `.nx` paths with no code of their own; `dm/tools/nx_pack.py`, a small clean-room writer
  for test fixtures (a folder dump to `.nx`), not for users.
- Launcher: `Nx.java` (the archive, through `Nup.Source`), used wherever `Nup` and `Nsp` are: Add game, `PackFind`, `DumpLayout`, `DumpCheck`,
  readiness, `GameActivity`'s `DM_ROMFS` and add-on links, `PackService`.

## Tests

- Host: `make -C dm/host/tests GAME=<id> run` (t_fs "NX archive": an archive written in C from the spec with RAW and ZSTD blocks, shared blocks, an
  update, an add-on, a damaged block).
- Tools: `python -m unittest dm/tools/tests/test_nx.py` (`nx_pack.py` fixtures; the same reads as from the folder; `pack_dump.scan`).
- Launcher: `NxJvmTest` through `dm/tools/tests/test_android_nx.py`.
