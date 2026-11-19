// An NX archive (github.com/fab918/nxformat, docs/SPEC.md release 1.0.4, header versions (0,2) and (0,3); android/NX.md) as a RomFS image: one
// snapshot's romfs/ subtree becomes the in-memory index a DMROMFS1 image has (romfs_image.h), as nup_image.h does for a NUP package, so hle_fs.c's
// lookups, listings and sizes are the same code.
//
// Naming (android/NX.md "Packages and names"): "<file>.nx", "<file>.nx:<selector>" or "<file>.nx:<selector>/romfs", the selector being base,
// update/<numeric_content_version> (an update or a standalone update), addon/<16 hex digits> (a DLC snapshot with ADDON_TITLE_ID) or the host's own
// aoc/<index> (the add-on whose title ID's low 12 bits are the index); with none, the newest update's RomFS, else the base's. Each NxProgram of the
// game has its own packages: "program<n>/<selector>" names program n's (n = program_id & 0xf: "<file>.nx:program1/update/327680/romfs", a Switch 2
// Edition's own program); with no prefix, program 0 (ID = the application's, else the lowest index; not program_kind). Add-ons are the application's,
// with or without a prefix. "<selector>/program<n>" is a folder inside the package's tree (a nested view: "<file>.nx:base/program1/romfs").
// The file part may be "fd:<n>/<name>" (nup_image.h nup_fd_spec).
// nup_split takes these forms; nup_image_open hands a file that begins with "NXAR" to nx_image_open_fd, whatever its name.
//
// Open (NX.md "Reading"): both header pages, the valid slot with the higher generation (an older valid one when the newer one's metadata is
// damaged), its CRC-32C, versions, features, reserved bytes and section ranges; then the tail is read once and the Catalog, Manifest and index
// SHA-256 are checked before any of their values is used; then the structure the reader relies on: the Catalog's tables and the rows it reads, the
// Manifest's tables and partitions, the selected tree (names, sorted children, no directory or file reached twice), every block descriptor, and
// each file's block count, block references and block sizes. A damaged or cut archive is refused at open with what failed in why.
//
// Files: an image entry with IMG_ENT_NX, a = its index in the image's nx_file table (first block reference, block count, chunk size), b = its
// size. nx_read copies [off, off + len) block by block: a RAW block by one pread of the 16 KiB slices that cover the request, a ZSTD block
// decoded whole (one frame, at most 1 MiB) into a small least-recently-used cache of decoded blocks (DM_NUP_CACHE_MB, default 128, as a .nup's),
// since games read files in pieces and blocks are shared between files. Every slice read is checked against the integrity table's CRC-32C (the
// ARMv8 CRC instructions on Linux / Android arm64 when the CPU has them, a table elsewhere); a mismatch fails the read with one [RED] line naming
// the archive, the file and the block. No file is decompressed whole, so there are no whole-file buffers or temporary files as for a .nup.
#pragma once
#include "nup_image.h"   // nup_split, nup_open_file, nup_u32 / nup_u64, the zstd declarations; it includes this file at its end

#ifdef DM_NUP_ZSTD
unsigned long long ZSTD_getFrameContentSize(const void *src, size_t src_size);
size_t ZSTD_findFrameCompressedSize(const void *src, size_t src_size);
unsigned ZSTD_getDictID_fromFrame(const void *src, size_t src_size);
#endif

#define IMG_ENT_NX 8   // in memory only (an NX image): a = index in nx_aux.f
#define NX_NONE 0xFFFFFFFFu
#define NX_SLICE 16384u   // check_size: logical bytes covered by one integrity entry
#define NX_MIB (1ull << 20)

// ---- CRC-32C (Castagnoli, reflected 0x82f63b78; the state starts at ~0 and is inverted at the end) ----
static uint32_t nx_crc_tab[8][256];
static int nx_crc_hw_ok __attribute__((unused));   // (set on Linux / Android arm64 only)
static pthread_once_t nx_crc_once = PTHREAD_ONCE_INIT;
#if defined(__aarch64__) && defined(__linux__)
#include <arm_acle.h>
#include <sys/auxv.h>
#ifndef HWCAP_CRC32
#define HWCAP_CRC32 (1 << 7)
#endif
__attribute__((target("crc"))) static uint32_t nx_crc_hw(uint32_t c, const uint8_t *p, size_t n) {
    while (n && ((uintptr_t)p & 7)) { c = __crc32cb(c, *p++); n--; }
    for (; n >= 8; p += 8, n -= 8) { uint64_t v; memcpy(&v, p, 8); c = __crc32cd(c, v); }
    while (n--) c = __crc32cb(c, *p++);
    return c;
}
#endif
static void nx_crc_setup(void) {
    for (uint32_t i = 0; i < 256; i++) { uint32_t c = i; for (int k = 0; k < 8; k++) c = c & 1 ? (c >> 1) ^ 0x82f63b78u : c >> 1; nx_crc_tab[0][i] = c; }
    for (uint32_t i = 0; i < 256; i++) for (int k = 1; k < 8; k++) nx_crc_tab[k][i] = (nx_crc_tab[k - 1][i] >> 8) ^ nx_crc_tab[0][nx_crc_tab[k - 1][i] & 0xff];
#if defined(__aarch64__) && defined(__linux__)
    nx_crc_hw_ok = (getauxval(AT_HWCAP) & HWCAP_CRC32) != 0;
#endif
}
static uint32_t nx_crc_sw(uint32_t c, const uint8_t *p, size_t n) {   // slicing by 8 (a little-endian host)
    while (n && ((uintptr_t)p & 7)) { c = nx_crc_tab[0][(c ^ *p++) & 0xff] ^ (c >> 8); n--; }
    for (; n >= 8; p += 8, n -= 8) {
        uint64_t v; memcpy(&v, p, 8); v ^= c;
        c = nx_crc_tab[7][v & 0xff] ^ nx_crc_tab[6][(v >> 8) & 0xff] ^ nx_crc_tab[5][(v >> 16) & 0xff] ^ nx_crc_tab[4][(v >> 24) & 0xff] ^
            nx_crc_tab[3][(v >> 32) & 0xff] ^ nx_crc_tab[2][(v >> 40) & 0xff] ^ nx_crc_tab[1][(v >> 48) & 0xff] ^ nx_crc_tab[0][v >> 56];
    }
    while (n--) c = nx_crc_tab[0][(c ^ *p++) & 0xff] ^ (c >> 8);
    return c;
}
static uint32_t nx_crc32c(const void *p, size_t n) {
    pthread_once(&nx_crc_once, nx_crc_setup);
#if defined(__aarch64__) && defined(__linux__)
    if (nx_crc_hw_ok) return ~nx_crc_hw(~0u, p, n);
#endif
    return ~nx_crc_sw(~0u, p, n);
}

static uint16_t nx_u16(const uint8_t *p) { uint16_t v; memcpy(&v, p, 2); return v; }
static int nx_zero(const uint8_t *p, size_t n) { for (size_t i = 0; i < n; i++) if (p[i]) return 0; return 1; }
static uint64_t nx_align16(uint64_t v) { return (v + 15) & ~15ull; }
static uint64_t nx_align8(uint64_t v) { return (v + 7) & ~7ull; }

// ---- The image's state ----
typedef struct { uint64_t size; uint32_t ref, nblk, file_id; uint8_t log2; } nx_file;   // ref: first block reference in nx_aux.refs
typedef struct nx_cb { struct nx_cb *hnext, *prev, *next; uint8_t *buf; uint64_t key; uint32_t size, refs; uint8_t loading; } nx_cb;   // a decoded block
// A storage of an NCA snapshot (nx_nca.h): an NX file, a range of another storage, an indirect (patch) or a compressed storage over others
typedef struct nxs {
    uint8_t kind; uint32_t serial, file, nent;
    uint64_t start, size, off;   // readable [start, size); SUB: the offset in a
    struct nxs *a, *b;           // SUB: the parent; IND: storages 0 and 1; CMP: the data
    uint8_t *ent;                // IND / CMP: the bucket table's entries as stored (0x14 / 0x18 bytes each)
    char name[48];               // FILE: the NCA's name, for messages
} nxs;
typedef struct {
    pthread_mutex_t lock; pthread_cond_t cv;
    uint8_t *meta;              // the hot block index (nblk x 24 bytes) followed by the integrity table (ncheck x 4), checked against index_sha256
    const uint8_t *idx, *crc;
    uint64_t nblk, ncheck, nzblk;   // nzblk: ZSTD blocks in the archive (for the log)
    uint32_t *refs; uint64_t nrefs, crefs;   // the image's files' block references, in nx_file order
    nx_file *f; uint64_t nf, cf;
    nxs **st; uint32_t nst, cst; nxs *romfs;   // an NCA snapshot's storages; romfs: the RomFS (file entries' a is their offset in it), NULL for an extracted tree
    nx_cb **hash; uint32_t hmask; nx_cb lru;   // lru.next is the most recently used; blocks being decoded are in the hash only
    uint64_t held, budget, ndec, nred;
    uint64_t generation;
    char what[200];             // "<file> <selector>", for messages
} nx_aux;

static void nx_aux_free(void *p) {
    nx_aux *a = p; if (!a) return;
    if (a->hash) for (uint32_t i = 0; i <= a->hmask; i++) for (nx_cb *c = a->hash[i], *nx; c; c = nx) { nx = c->hnext; free(c->buf); free(c); }
    for (uint32_t i = 0; i < a->nst; i++) { free(a->st[i]->ent); free(a->st[i]); }
    free(a->st); free(a->hash); free(a->meta); free(a->refs); free(a->f);
    pthread_mutex_destroy(&a->lock); pthread_cond_destroy(&a->cv); free(a);
}
static int nx_image_is(const romfs_img *m) { return m && m->aux_free == nx_aux_free; }

// ---- The decoded-block cache: ZSTD blocks (key: the block ID) and LZ4 entries of compressed storages (key: bit 63, the storage, the entry) ----
static uint32_t nx_hkey(const nx_aux *a, uint64_t k) { return (uint32_t)(k ^ k >> 29 ^ k >> 43) & a->hmask; }
static void nx_lru_unlink(nx_cb *c) { c->prev->next = c->next; c->next->prev = c->prev; c->prev = c->next = NULL; }
static void nx_lru_front(nx_aux *a, nx_cb *c) { c->next = a->lru.next; c->prev = &a->lru; a->lru.next->prev = c; a->lru.next = c; }
static void nx_hash_del(nx_aux *a, nx_cb *c) { nx_cb **pp = &a->hash[nx_hkey(a, c->key)]; while (*pp != c) pp = &(*pp)->hnext; *pp = c->hnext; }
// Frees decoded blocks no read holds, least recently used first, until the cache fits its budget. Called with a->lock held
static void nx_trim(nx_aux *a) {
    for (nx_cb *c = a->lru.prev; a->held > a->budget && c != &a->lru;) {
        nx_cb *p = c->prev;
        if (!c->refs) { nx_lru_unlink(c); nx_hash_del(a, c); a->held -= c->size; free(c->buf); free(c); }
        c = p;
    }
}
static void nx_cache_put(nx_aux *a, nx_cb *c) { pthread_mutex_lock(&a->lock); c->refs--; nx_trim(a); pthread_mutex_unlock(&a->lock); }
// Fills *out (malloc'd) and *size with a decoded block: 0, or an errno with the reason in why
typedef int (*nx_load_fn)(romfs_img *m, void *ctx, uint8_t **out, uint32_t *size, char *why, size_t wn);
// The decoded block of key, held for the caller (nx_cache_put gives it back), decoded by load when it is not cached; NULL with errno set and the reason in
// why. A block another thread is decoding is waited for, not decoded twice
static nx_cb *nx_cache_get(romfs_img *m, uint64_t key, nx_load_fn load, void *ctx, char *why, size_t wn) {
    nx_aux *a = m->aux; nx_cb *c;
    pthread_mutex_lock(&a->lock);
    for (;;) {
        for (c = a->hash[nx_hkey(a, key)]; c && c->key != key; c = c->hnext) {}
        if (!c) break;
        if (c->loading) { pthread_cond_wait(&a->cv, &a->lock); continue; }
        c->refs++; nx_lru_unlink(c); nx_lru_front(a, c); pthread_mutex_unlock(&a->lock); return c;
    }
    if (!(c = calloc(1, sizeof *c))) { pthread_mutex_unlock(&a->lock); snprintf(why, wn, "out of memory"); errno = ENOMEM; return NULL; }
    c->key = key; c->loading = 1; c->hnext = a->hash[nx_hkey(a, key)]; a->hash[nx_hkey(a, key)] = c;
    pthread_mutex_unlock(&a->lock);
    uint8_t *out = NULL; uint32_t size = 0; int e = load(m, ctx, &out, &size, why, wn);
    pthread_mutex_lock(&a->lock);
    c->loading = 0;
    if (e) { nx_hash_del(a, c); free(c); free(out); c = NULL; }
    else { c->buf = out; c->size = size; c->refs = 1; nx_lru_front(a, c); a->held += size; a->ndec++; nx_trim(a); }
    pthread_cond_broadcast(&a->cv); pthread_mutex_unlock(&a->lock);
    if (e) errno = e;
    return c;
}

// ---- The block reader ----
// Checks slices [s0, s1) of block id, whose bytes from slice s0 on are at p (logical size lg): the slice that fails, or -1
static int64_t nx_slices_bad(const nx_aux *a, uint32_t id, const uint8_t *p, uint32_t lg, uint64_t s0, uint64_t s1) {
    uint32_t fc = nup_u32(a->idx + 24ull * id + 20);
    for (uint64_t s = s0; s < s1; s++) {
        uint64_t lo = s * NX_SLICE, len = lg - lo < NX_SLICE ? lg - lo : NX_SLICE;
        if (nx_crc32c(p + (lo - s0 * NX_SLICE), (size_t)len) != nup_u32(a->crc + 4 * ((uint64_t)fc + s))) return (int64_t)s;
    }
    return -1;
}
// A ZSTD block decoded whole and checked (the cache's loader; ctx: the block ID)
static int nx_zstd_load(romfs_img *m, void *ctx, uint8_t **outp, uint32_t *size, char *why, size_t wn) {
    nx_aux *a = m->aux; uint32_t id = *(const uint32_t *)ctx;
    const uint8_t *b = a->idx + 24ull * id; uint64_t off = nup_u64(b); uint32_t st = nup_u32(b + 8), lg = nup_u32(b + 12);
    uint8_t *src = NULL, *out = NULL; int e = 0;
#ifndef DM_NUP_ZSTD
    (void)off; (void)st; (void)lg; snprintf(why, wn, "a ZSTD block, and this host is built without a zstd decompressor (DM_NUP_ZSTD)"); e = ENOTSUP;
#else
    if (!(src = malloc(st)) || !(out = malloc(lg))) { snprintf(why, wn, "out of memory"); e = ENOMEM; }
    else if (!romfs_image_read(m->fd, src, st, (off_t)off)) { e = errno ? errno : EIO; snprintf(why, wn, "cannot read its %u stored bytes at %#llx (errno %d)", st, (unsigned long long)off, e); }
    else {
        // One standard frame, no dictionary, content size = the block's, window at most 1 MiB, exactly the stored bytes (SPEC §7)
        uint32_t mg = nup_u32(src); uint8_t fhd = st > 4 ? src[4] : 0; uint64_t win = 0;
        if (st >= 6 && !(fhd & 0x20)) { uint8_t wd = src[5]; uint64_t wb = 1ull << (10 + (wd >> 3)); win = wb + (wb >> 3) * (wd & 7); }
        if (st < 6 || mg != 0xFD2FB528u) e = EIO, snprintf(why, wn, "the stored bytes are not a zstd frame (begin %02x %02x %02x %02x)", src[0], st > 1 ? src[1] : 0, st > 2 ? src[2] : 0, st > 3 ? src[3] : 0);
        else if (ZSTD_getFrameContentSize(src, st) != lg) e = EIO, snprintf(why, wn, "the zstd frame does not declare the block's size %u", lg);
        else if (ZSTD_getDictID_fromFrame(src, st)) e = EIO, snprintf(why, wn, "the zstd frame needs a dictionary");
        else if (win > NX_MIB) e = EIO, snprintf(why, wn, "the zstd frame's window is over 1 MiB");
        else if (ZSTD_findFrameCompressedSize(src, st) != st) e = EIO, snprintf(why, wn, "the zstd frame is not exactly the block's %u stored bytes", st);
        else { size_t r = ZSTD_decompress(out, lg, src, st); if (ZSTD_isError(r) || r != lg) e = EIO, snprintf(why, wn, "the zstd frame does not decode (%s)", ZSTD_isError(r) ? "corrupt" : "short"); }
        int64_t sb;
        if (!e && (sb = nx_slices_bad(a, id, out, lg, 0, (lg + NX_SLICE - 1) / NX_SLICE)) >= 0) e = EIO, snprintf(why, wn, "CRC-32C mismatch in its decoded slice %lld (a damaged file)", (long long)sb);
    }
#endif
    free(src);
    if (e) { free(out); return e; }
    *outp = out; *size = lg; return 0;
}
// Copies [off, off + len) of NX file fi (inside it) into out block by block: a RAW block by one pread of the slices that cover the request, a ZSTD block
// decoded whole through the cache; every slice checked. 0, or an errno with why naming the block ("block 3 of 9 (block ID 12): ...")
static int nx_fread(romfs_img *m, uint32_t fi, uint64_t off, void *out, size_t len, char *why, size_t wn) {
    nx_aux *a = m->aux; const nx_file *f = &a->f[fi];
    if (off > f->size || len > f->size - off) { snprintf(why, wn, "a read of %#llx+%#zx is beyond the file's %llu bytes", (unsigned long long)off, len, (unsigned long long)f->size); return EIO; }
    uint8_t *scratch = NULL; size_t scap = 0, done = 0; char w[240] = ""; uint64_t bi = 0; uint32_t id = 0; int e = 0;
    while (done < len) {
        uint64_t pos = off + done, in = pos & ((1ull << f->log2) - 1); bi = pos >> f->log2; id = a->refs[f->ref + bi];
        const uint8_t *b = a->idx + 24ull * id; uint32_t lg = nup_u32(b + 12);
        size_t n = lg - in < len - done ? (size_t)(lg - in) : len - done;
        if (!b[16]) {   // RAW: the slices that cover the request, one pread
            uint64_t s0 = in / NX_SLICE, s1 = (in + n + NX_SLICE - 1) / NX_SLICE, lo = s0 * NX_SLICE, hi = s1 * NX_SLICE < lg ? s1 * NX_SLICE : lg;
            if (hi - lo > scap) { free(scratch); scap = (size_t)(hi - lo); if (!(scratch = malloc(scap))) { e = ENOMEM; snprintf(w, sizeof w, "out of memory"); break; } }
            if (!romfs_image_read(m->fd, scratch, (size_t)(hi - lo), (off_t)(nup_u64(b) + lo))) { e = errno ? errno : EIO; snprintf(w, sizeof w, "cannot read it (errno %d)", e); break; }
            int64_t sb = nx_slices_bad(a, id, scratch, lg, s0, s1);
            if (sb >= 0) { e = EIO; snprintf(w, sizeof w, "CRC-32C mismatch in its slice %lld (a damaged file)", (long long)sb); break; }
            memcpy((uint8_t *)out + done, scratch + (in - lo), n);
        } else {        // ZSTD: decoded whole, through the cache
            nx_cb *c = nx_cache_get(m, id, nx_zstd_load, &id, w, sizeof w);
            if (!c) { e = errno ? errno : EIO; break; }
            memcpy((uint8_t *)out + done, c->buf + in, n);
            nx_cache_put(a, c);
        }
        done += n;
    }
    free(scratch);
    if (e) snprintf(why, wn, "file %u, block %llu of %u (block ID %u): %s", f->file_id, (unsigned long long)bi, f->nblk, id, w);
    return e;
}

// ---- Open ----
// A header slot's own checks (SPEC §3): NULL when valid, else what failed. The page past the 256-byte header is zero (SPEC §1)
static const char *nx_slot_bad(const uint8_t *p) {
    if (memcmp(p, "NXAR", 4)) return "no NXAR magic";
    if (nx_crc32c(p, 252) != nup_u32(p + 252)) return "header CRC-32C mismatch";
    if (nx_u16(p + 4) != 0 || (nx_u16(p + 6) != 2 && nx_u16(p + 6) != 3)) return "unsupported header version (this reader takes (0,2) and (0,3))";
    if (nup_u32(p + 8) != 256) return "header_size is not 256";
    if (nx_u16(p + 12) != 1) return "unknown required features";
    if (nx_u16(p + 14)) return "unknown optional features";
    if (nup_u32(p + 224) != NX_SLICE) return "check_size is not 16384";
    if (!nx_zero(p + 228, 24)) return "reserved header bytes are not zero";
    if (!nx_zero(p + 256, 4096 - 256)) return "the header page's padding is not zero";
    return NULL;
}
typedef struct {
    uint16_t minor;
    uint64_t asize, cat_off, cat_size, media_off, media_size, data_off, data_size, man_off, man_size, idx_off, nblk, int_off, int_size, gen;
    const uint8_t *h;
} nx_hdr;
static void nx_hdr_parse(nx_hdr *x, const uint8_t *p) {
    x->h = p; x->minor = nx_u16(p + 6);
    x->asize = nup_u64(p + 16); x->cat_off = nup_u64(p + 24); x->cat_size = nup_u64(p + 32); x->media_off = nup_u64(p + 40); x->media_size = nup_u64(p + 48);
    x->data_off = nup_u64(p + 56); x->data_size = nup_u64(p + 64); x->man_off = nup_u64(p + 72); x->man_size = nup_u64(p + 80);
    x->idx_off = nup_u64(p + 88); x->nblk = nup_u64(p + 96); x->int_off = nup_u64(p + 104); x->int_size = nup_u64(p + 112); x->gen = nup_u64(p + 216);
}
// The selected generation's section ranges (SPEC §1, §3): NULL when they hold, else what failed
static const char *nx_ranges_bad(const nx_hdr *x, uint64_t fsize) {
    uint64_t e;
    if (x->asize > fsize) return "archive_size is beyond the end of the file (a cut or incomplete copy)";
    if (x->asize > (uint64_t)INT64_MAX) return "archive_size is too large";
    if (x->cat_size < 128 || x->cat_size > 16 * NX_MIB || x->cat_off % 16) return "bad Catalog range";
    if ((x->media_off == 0) != (x->media_size == 0) || (x->media_off && (x->media_off % 16 || x->media_size < 32))) return "bad Media range";
    if (x->nblk > (1ull << 32)) return "too many blocks";
    if (x->nblk ? (x->data_off != 8192 || !x->data_size) : (x->data_off || x->data_size)) return "bad Data range";
    if (x->man_size < 96 || x->man_size > 4096 * NX_MIB || x->man_off % 16) return "bad Manifest range";
    if (x->nblk ? (!x->idx_off || x->idx_off % 16 || !x->int_off || x->int_off % 16 || !x->int_size || x->int_size % 4) : (x->idx_off || x->int_off || x->int_size))
        return "bad block index or integrity table range";
    uint64_t end = 8192;
    if (x->nblk && __builtin_add_overflow(x->data_off, x->data_size, &end)) return "bad Data range";
    uint64_t first = x->media_off ? x->media_off : x->cat_off;
    if (first < nx_align16(end)) return "the tail overlaps the Data region";
    if (x->media_off) { if (__builtin_add_overflow(x->media_off, x->media_size, &e) || x->cat_off != nx_align16(e)) return "the Catalog does not follow the Media region"; }
    if (__builtin_add_overflow(x->cat_off, x->cat_size, &e) || x->man_off != nx_align16(e)) return "the Manifest does not follow the Catalog";
    if (__builtin_add_overflow(x->man_off, x->man_size, &e)) return "bad Manifest range";
    if (x->nblk) {
        if (x->idx_off != nx_align16(e)) return "the block index does not follow the Manifest";
        if (__builtin_add_overflow(x->idx_off, x->nblk * 24, &e) || x->int_off != nx_align16(e)) return "the integrity table does not follow the block index";
        if (__builtin_add_overflow(x->int_off, x->int_size, &e)) return "bad integrity table range";
    }
    if (x->asize != e) return "archive_size is not the end of the tail";
    return NULL;
}
// Reads the generation's tail (Catalog through the integrity table) and checks its zero gaps and its three SHA-256 digests. *tail holds it from
// the Catalog's first byte on; NULL when good, else what failed (*e: errno)
static const char *nx_tail_load(int fd, const nx_hdr *x, uint64_t fsize, uint8_t **tail, int *e) {
    const char *r = nx_ranges_bad(x, fsize); *tail = NULL; *e = EINVAL;
    if (r) return r;
    uint64_t len = x->asize - x->cat_off;
    if (len > SIZE_MAX || !(*tail = malloc(len ? (size_t)len : 1))) { *e = ENOMEM; return "out of memory for the archive's metadata"; }
    if (!romfs_image_read(fd, *tail, (size_t)len, (off_t)x->cat_off)) { *e = errno ? errno : EIO; return "cannot read the archive's metadata (a cut copy?)"; }
    const uint8_t *t = *tail; uint64_t ce = x->cat_size, ms = x->man_off - x->cat_off, me = ms + x->man_size;
    if (!nx_zero(t + ce, (size_t)(ms - ce))) return "nonzero bytes between the Catalog and the Manifest";
    uint8_t h[32];
    plat_sha256(t, (size_t)x->cat_size, h); if (memcmp(h, x->h + 120, 32)) return "the Catalog's SHA-256 does not match (a damaged file)";
    plat_sha256(t + ms, (size_t)x->man_size, h); if (memcmp(h, x->h + 152, 32)) return "the Manifest's SHA-256 does not match (a damaged file)";
    plat_sha256_t c; plat_sha256_init(&c);
    if (x->nblk) {
        uint64_t is = x->idx_off - x->cat_off, ie = is + x->nblk * 24, ts = x->int_off - x->cat_off;
        if (!nx_zero(t + me, (size_t)(is - me)) || !nx_zero(t + ie, (size_t)(ts - ie))) return "nonzero bytes between tail sections";
        plat_sha256_update(&c, t + is, (size_t)(x->nblk * 24)); plat_sha256_update(&c, t + ts, (size_t)x->int_size);
    }
    plat_sha256_final(h, &c); if (memcmp(h, x->h + 184, 32)) return "the block index's SHA-256 does not match (a damaged file)";
    return NULL;
}

// The Manifest while the archive is opened (its tables checked: partitions, ranges)
typedef struct { const uint8_t *man, *idx; uint64_t o_dir, o_ent, o_file, o_ref, o_path, path_size, ndir, nfile, nref, nblk; } nx_man;
// A directory's child named nm; 1 with the kind and target (checked against the tables), 0 when none (an entry whose name is out of the path pool matches nothing)
static int nx_child(const nx_man *M, uint32_t dir, const char *nm, uint8_t *kind, uint32_t *target) {
    const uint8_t *d = M->man + M->o_dir + 16ull * dir; size_t L = strlen(nm);
    for (uint64_t j = 0, first = nup_u32(d), cnt = nup_u32(d + 4); j < cnt; j++) {
        const uint8_t *r = M->man + M->o_ent + 24 * (first + j); uint64_t no = nup_u64(r);
        if (nup_u32(r + 8) != L || no < M->o_path || L > M->path_size || no - M->o_path > M->path_size - L) continue;
        if (!memcmp(M->man + no, nm, L)) {
            *kind = r[16]; *target = nup_u32(r + 12);
            return (*kind == 1 && *target < M->ndir) || (*kind == 2 && *target < M->nfile);
        }
    }
    return 0;
}
static int nx_name_lt(const uint8_t *a, uint32_t al, const uint8_t *b, uint32_t bl) {   // a < b, bytewise, a shorter prefix first
    int c = memcmp(a, b, al < bl ? al : bl);
    return c < 0 || (!c && al < bl);
}
// Manifest file id as the image's next nx_file, checked (its record, its block count, references and block sizes): its index, -1 with why
static int64_t nx_add_file(nx_aux *a, const nx_man *M, uint32_t id, char *why, size_t wn) {
    if (id >= M->nfile) { snprintf(why, wn, "a file entry's target is out of range"); return -1; }
    const uint8_t *f = M->man + M->o_file + 64ull * id; uint64_t size = nup_u64(f); uint32_t fr = nup_u32(f + 8), fc = nup_u32(f + 12); uint8_t lg = f[16];
    if (size > (uint64_t)INT64_MAX || lg < 16 || lg > 20 || f[17] || nx_u16(f + 18) || !nx_zero(f + 52, 12)) { snprintf(why, wn, "file %u: bad record", id); return -1; }
    uint64_t cs = 1ull << lg;
    if (fc != (size + cs - 1) >> lg) { snprintf(why, wn, "file %u: its block count does not match its size", id); return -1; }
    if ((uint64_t)fr + fc > M->nref) { snprintf(why, wn, "file %u: its block references are out of range", id); return -1; }
    if (a->nrefs + fc > UINT32_MAX) { snprintf(why, wn, "too many block references"); return -1; }
    if (a->nf == a->cf) { uint64_t c = a->cf ? a->cf * 2 : 256; nx_file *p = realloc(a->f, sizeof *p * (size_t)c); if (!p) { snprintf(why, wn, "out of memory"); return -1; } a->f = p; a->cf = c; }
    if (a->nrefs + fc > a->crefs) { uint64_t c = a->crefs ? a->crefs : 1024; while (c < a->nrefs + fc) c *= 2; uint32_t *p = realloc(a->refs, sizeof *p * (size_t)c); if (!p) { snprintf(why, wn, "out of memory"); return -1; } a->refs = p; a->crefs = c; }
    for (uint32_t b = 0; b < fc; b++) {
        uint32_t bid = nup_u32(M->man + M->o_ref + 4ull * (fr + b));
        if (bid >= M->nblk) { snprintf(why, wn, "file %u: a block reference is out of range", id); return -1; }
        uint64_t want_l = b + 1 < fc ? cs : size - (uint64_t)b * cs;
        if (nup_u32(M->idx + 24ull * bid + 12) != want_l) { snprintf(why, wn, "file %u: block %u's size does not match the file's chunks", id, b); return -1; }
        a->refs[a->nrefs + b] = bid;
    }
    nx_file *x = &a->f[a->nf]; x->size = size; x->ref = (uint32_t)a->nrefs; x->nblk = fc; x->file_id = id; x->log2 = lg;
    a->nrefs += fc; return (int64_t)a->nf++;
}
// A snapshot made from an NSP or XCI: a <content ID>.cnmt.nca file at its root (NX.md "NCA snapshots")
static int nx_snap_nca(const nx_man *M, uint32_t root) {
    const uint8_t *d = M->man + M->o_dir + 16ull * root;
    for (uint64_t j = 0; j < nup_u32(d + 4); j++) {
        const uint8_t *r = M->man + M->o_ent + 24 * (nup_u32(d) + j); uint64_t no = nup_u64(r); uint32_t nl = nup_u32(r + 8);
        if (no >= M->o_path && nl <= M->path_size && no - M->o_path <= M->path_size - nl && r[16] == 2 && nl > 9 && !memcmp(M->man + no + nl - 9, ".cnmt.nca", 9)) return 1;
    }
    return 0;
}
#include "nx_nca.h"

// Opens the archive at fd (taken: closed on failure, the image's otherwise) as the image of sel's RomFS. NULL with errno set and a reason in why
static romfs_img *nx_image_open_fd(int fd, uint64_t fsize, const char *file, const char *sel_in, char *why, size_t wn) {
    uint8_t *pages = NULL, *tail = NULL, *seen_d = NULL, *seen_f = NULL; uint32_t *queue = NULL; uint64_t *ent_of = NULL;
    romfs_img *m = NULL; nx_aux *aux = NULL; int e = EINVAL; nx_hdr H; const char *base = strrchr(file, '/'); base = base ? base + 1 : file;
#define NX_BAD(...) do { snprintf(why, wn, __VA_ARGS__); goto bad; } while (0)
    char sel[128]; strlcpy(sel, sel_in, sizeof sel);
    // "[program<n>/]<package>[/program<n>]": the leading one names program n's own packages (its NxProgram row, n = program_id & 0xf), the trailing one a
    // folder inside the package's tree (NX.md "Packages and names", Programs). "program<n>" alone is not a selector
    int pidx = 0; char pfx[24] = ""; {
        if (!strncmp(sel, "program", 7) && sel[7] >= '0' && sel[7] <= '9') {
            char *end; long v = strtol(sel + 7, &end, 10);
            if (*end != '/' || !end[1] || v > 15 || (sel[7] == '0' && end != sel + 8)) { snprintf(why, wn, "unknown selector \"%s\" (program<n>/ goes before base, update/<version>, addon/<title ID> or aoc/<index>)", sel_in); close(fd); errno = EINVAL; return NULL; }
            pidx = (int)v; snprintf(pfx, sizeof pfx, "program%d/", pidx); memmove(sel, end + 1, strlen(end + 1) + 1);
        }
    }
    char sub[32] = "romfs", prog[24] = ""; {
        char *p = strstr(sel, "/program");
        if (p && p[8] >= '1' && p[8] <= '9' && strspn(p + 8, "0123456789") == strlen(p + 8) && strlen(p + 1) < sizeof prog) { strlcpy(prog, p + 1, sizeof prog); *p = 0; }
    }
    if (fsize < 8192) NX_BAD("the file is shorter than its two header pages (a cut copy?)");
    if (!(pages = malloc(8192))) { e = ENOMEM; NX_BAD("out of memory"); }
    const char *bad_s[2] = {NULL, NULL};
    for (int s = 0; s < 2; s++) bad_s[s] = romfs_image_read(fd, pages + 4096 * s, 4096, 4096 * s) ? nx_slot_bad(pages + 4096 * s) : "cannot read the header page";
    // Candidates, newest first: the valid slot with the higher generation; an older valid generation is tried when the newer one's metadata is damaged
    const uint8_t *cand[2] = {NULL, NULL}; int nc = 0;
    if (!bad_s[0] && !bad_s[1]) {
        uint64_t g0 = nup_u64(pages + 216), g1 = nup_u64(pages + 4096 + 216);
        if (g0 == g1) { if (memcmp(pages, pages + 4096, 256)) NX_BAD("both header slots are valid with the same generation but differ"); cand[nc++] = pages; }
        else if (g0 > g1) { cand[nc++] = pages; cand[nc++] = pages + 4096; }
        else { cand[nc++] = pages + 4096; cand[nc++] = pages; }
    } else if (!bad_s[0]) cand[nc++] = pages;
    else if (!bad_s[1]) cand[nc++] = pages + 4096;
    else NX_BAD("no valid header slot (slot A: %s; slot B: %s)", bad_s[0], bad_s[1]);
    {   const char *r = NULL, *first_r = NULL; uint64_t first_g = 0;
        for (int i = 0; i < nc; i++) {
            nx_hdr_parse(&H, cand[i]); free(tail);
            if (!(r = nx_tail_load(fd, &H, fsize, &tail, &e))) break;
            if (!i) { first_r = r; first_g = H.gen; }
            if (e == ENOMEM) break;
        }
        if (r) { if (first_r && first_r != r) NX_BAD("generation %llu: %s; the older generation: %s", (unsigned long long)first_g, first_r, r); NX_BAD("%s", r); }
        if (first_r) LOG("[RED] fs NX %s: generation %llu is damaged (%s): generation %llu, the older one, is read", base, (unsigned long long)first_g, first_r, (unsigned long long)H.gen);
    }
    e = EINVAL;
    const uint8_t *cat = tail, *man = tail + (H.man_off - H.cat_off), *idx = H.nblk ? tail + (H.idx_off - H.cat_off) : NULL, *crcs = H.nblk ? tail + (H.int_off - H.cat_off) : NULL;

    // ---- The block index (SPEC §7) ----
    uint64_t ncheck = 0, nzblk = 0, data_end = H.data_off + H.data_size, prev_end = H.data_off;
    for (uint64_t i = 0; i < H.nblk; i++) {
        const uint8_t *b = idx + 24 * i; uint64_t off = nup_u64(b); uint32_t st = nup_u32(b + 8), lg = nup_u32(b + 12);
        if (off % 16 || off < (i ? nx_align16(prev_end) : H.data_off) || !st || st > NX_MIB + 65536 || off + st > data_end) NX_BAD("block %llu: bad stored range", (unsigned long long)i);
        if (!lg || lg > NX_MIB || b[16] > 1 || b[17] || nx_u16(b + 18) || (!b[16] && st != lg)) NX_BAD("block %llu: bad descriptor", (unsigned long long)i);
        if (nup_u32(b + 20) != ncheck) NX_BAD("block %llu: first_check is not the sum of the earlier blocks' checks", (unsigned long long)i);
        ncheck += (lg + NX_SLICE - 1) / NX_SLICE; nzblk += b[16]; prev_end = off + st;
    }
    if (H.nblk && prev_end != data_end) NX_BAD("data_size does not end at the last block");
    if (ncheck * 4 != H.int_size) NX_BAD("the integrity table's size does not match the blocks");

    // ---- The Catalog (SPEC §4) ----
    if (memcmp(cat, "NXCT", 4) || nx_u16(cat + 4) != 1 || nx_u16(cat + 6) != 128 || nup_u64(cat + 8) != H.cat_size || nup_u32(cat + 16)) NX_BAD("bad Catalog header");
    uint32_t napp = nup_u32(cat + 20), nprog = nup_u32(cat + 24), nsnap = nup_u32(cat + 28), nloc = nup_u32(cat + 32), ndep = nup_u32(cat + 36), next = nup_u32(cat + 40);
    if (!napp || napp > 4096 || nprog > 16384 || !nsnap || nsnap > 65536 || nloc > 16384 || ndep > 32768 || next > 4096 || nup_u32(cat + 44) || !nx_zero(cat + 112, 16)) NX_BAD("bad Catalog counts");
    uint64_t o_app = nup_u64(cat + 48), o_prog = nup_u64(cat + 56), o_snap = nup_u64(cat + 64), o_ext = nup_u64(cat + 88), o_pool = nup_u64(cat + 96), pool_size = nup_u64(cat + 104);
    {   const uint64_t cnt[6] = {napp, nprog, nsnap, nloc, ndep, next}, sz[6] = {80, 64, 120, 24, 56, 32};
        uint64_t at = 128;
        for (int t = 0; t < 6; t++) {
            uint64_t o = nup_u64(cat + 48 + 8 * t);
            if (!cnt[t]) { if (o) NX_BAD("bad Catalog table offsets"); continue; }
            if (o != nx_align8(at)) NX_BAD("bad Catalog table offsets");
            at = o + cnt[t] * sz[t];
        }
        if (o_pool != nx_align8(at) || pool_size > H.cat_size || o_pool > H.cat_size - pool_size) NX_BAD("bad Catalog string pool range");
    }
    // Extensions: unknown required ones refuse the archive (SPEC §11); Source Resources only in (0,3), and at least one there
    {   uint32_t nsr = 0;
        for (uint32_t i = 0; i < next; i++) {
            const uint8_t *x = cat + o_ext + 32ull * i; uint32_t type = nup_u32(x), fl = nup_u32(x + 4); uint16_t ok = nx_u16(x + 8);
            uint64_t dof = nup_u64(x + 16), dsz = nup_u64(x + 24);
            if (fl & ~1u || ok > 3 || nx_u16(x + 10) || dof < o_pool + pool_size || dsz > H.cat_size || dof > H.cat_size - dsz) NX_BAD("Catalog extension %u: bad record", i);
            if (ok == 0 ? nup_u32(x + 12) != NX_NONE : nup_u32(x + 12) >= (ok == 1 ? napp : ok == 2 ? nprog : nsnap)) NX_BAD("Catalog extension %u: bad owner", i);
            int known = type >= 0x00010001 && type <= 0x00010009 && type != 0x00010007;
            if ((fl & 1) && !(known && type != 0x00010006 && type != 0x00010009)) NX_BAD("an unknown required Catalog extension %#x (a newer NX writer?)", type);
            nsr += type == 0x00010008;
        }
        if (H.minor == 3 ? !nsr : nsr != 0) NX_BAD("Source Resources do not match header version (0,%u)", H.minor);
    }
    // Applications, programs, snapshots: the rows this reader uses, their ranges and partitions
    {   uint64_t pc = 0, sc = 0;
        for (uint32_t i = 0; i < napp; i++) {
            const uint8_t *a = cat + o_app + 80ull * i; uint32_t fp = nup_u32(a + 12), np = nup_u32(a + 16), fs = nup_u32(a + 20), ns = nup_u32(a + 24);
            if (!nup_u64(a) || a[8] > 2 || a[9] & ~3 || nx_u16(a + 10) || !nx_zero(a + 72, 8)) NX_BAD("application %u: bad record", i);
            if (np ? fp != pc || (uint64_t)fp + np > nprog : fp != NX_NONE) NX_BAD("application %u: bad program range", i);
            if (!ns || fs != sc || (uint64_t)fs + ns > nsnap) NX_BAD("application %u: bad snapshot range", i);
            for (uint32_t k = 0; k < np; k++) {
                const uint8_t *p = cat + o_prog + 64ull * (fp + k);
                if (!nup_u64(p) || nup_u32(p + 8) != i || p[12] > 2 || p[13] & ~1 || nx_u16(p + 14) || nup_u32(p + 44) || !nx_zero(p + 48, 16)) NX_BAD("program %u: bad record", fp + k);
                if (!nup_u32(p + 36) || nup_u32(p + 32) < fs || (uint64_t)nup_u32(p + 32) + nup_u32(p + 36) > (uint64_t)fs + ns) NX_BAD("program %u: bad snapshot range", fp + k);
            }
            pc += np; sc += ns;
        }
        if (pc != nprog || sc != nsnap) NX_BAD("the applications do not cover the program and snapshot tables");
    }
    uint64_t ndir = 0, nfile = 0;   // (read below; the snapshots' root directories are checked against ndir)
    // ---- The Manifest's header and tables (SPEC §6) ----
    if (memcmp(man, "NXMF", 4) || nx_u16(man + 4) != 1 || nx_u16(man + 6) != 96 || nup_u64(man + 8) != H.man_size || !nx_zero(man + 80, 16)) NX_BAD("bad Manifest header");
    ndir = nup_u32(man + 16); nfile = nup_u32(man + 20);
    uint64_t nent = nup_u32(man + 24), nref = nup_u32(man + 28);
    uint64_t o_dir = nup_u64(man + 32), o_file = nup_u64(man + 40), o_ent = nup_u64(man + 48), o_ref = nup_u64(man + 56), o_path = nup_u64(man + 64), path_size = nup_u64(man + 72);
    if (!ndir || ndir > 16777216 || nfile > 33554432 || nent > 50331648) NX_BAD("bad Manifest counts");
    {   const uint64_t cnt[4] = {ndir, nfile, nent, nref}, sz[4] = {16, 64, 24, 4}; uint64_t at = 96;
        for (int t = 0; t < 4; t++) {
            uint64_t o = nup_u64(man + 32 + 8 * t);
            if (!cnt[t]) { if (o) NX_BAD("bad Manifest table offsets"); continue; }
            if (o != (t == 3 ? (at + 3) & ~3ull : nx_align8(at))) NX_BAD("bad Manifest table offsets");
            at = o + cnt[t] * sz[t];
        }
        if (o_path != nx_align8(at) || path_size > 2048 * NX_MIB || path_size > H.man_size || o_path > H.man_size - path_size) NX_BAD("bad Manifest path pool range");
    }
    {   uint64_t c = 0;   // entry ranges partition the entry table in directory order, block reference ranges the reference table in file order
        for (uint64_t i = 0; i < ndir; i++) { const uint8_t *d = man + o_dir + 16 * i; if (nup_u32(d) != c || nup_u32(d + 8) || nup_u32(d + 12)) NX_BAD("directory %llu: bad record", (unsigned long long)i); c += nup_u32(d + 4); }
        if (c != nent) NX_BAD("the directories do not partition the entry table");
        c = 0;
        for (uint64_t i = 0; i < nfile; i++) { const uint8_t *f = man + o_file + 64 * i; if (nup_u32(f + 8) != c) NX_BAD("file %llu: bad block reference range", (unsigned long long)i); c += nup_u32(f + 12); }
        if (c != nref) NX_BAD("the files do not partition the block reference table");
    }
    // Snapshot rows
    for (uint32_t i = 0; i < nsnap; i++) {
        const uint8_t *s = cat + o_snap + 120ull * i; uint32_t pi = nup_u32(s); uint8_t ty = s[4], fl = s[5];
        if (ty > 4 || fl & ~3 || nx_u16(s + 6) || (pi == NX_NONE ? ty != 2 : pi >= nprog) || nup_u32(s + 12) >= ndir) NX_BAD("snapshot %u: bad record", i);
        if (((fl & 1) != 0) == nx_zero(s + 88, 32)) NX_BAD("snapshot %u: BUILD_ID_PRESENT does not match its build ID", i);
        if ((fl & 2) && (ty != 2 || pi != NX_NONE || !nup_u64(s + 32) || !nx_zero(s + 40, 8))) NX_BAD("snapshot %u: bad ADDON_TITLE_ID", i);
    }

    // ---- The image's state: the block index, the file table, the cache (NCA snapshots read their CNMTs through it while the package is chosen) ----
    if (!(m = calloc(1, sizeof *m))) { e = ENOMEM; NX_BAD("out of memory"); }
    m->fd = fd; fd = -1;
    if (!(aux = calloc(1, sizeof *aux))) { e = ENOMEM; NX_BAD("out of memory"); }
    pthread_mutex_init(&aux->lock, NULL); pthread_cond_init(&aux->cv, NULL);
    aux->lru.next = aux->lru.prev = &aux->lru;
    m->aux = aux; m->aux_free = nx_aux_free;
    if (H.nblk && !(aux->meta = malloc((size_t)(H.nblk * 24 + H.int_size)))) { e = ENOMEM; NX_BAD("out of memory for the block index"); }
    if (H.nblk) { memcpy(aux->meta, idx, (size_t)(H.nblk * 24)); memcpy(aux->meta + H.nblk * 24, crcs, (size_t)H.int_size); }
    aux->idx = aux->meta; aux->crc = aux->meta ? aux->meta + H.nblk * 24 : NULL; aux->nblk = H.nblk; aux->ncheck = ncheck; aux->nzblk = nzblk; aux->generation = H.gen;
    { const char *mb = getenv("DM_NUP_CACHE_MB"); aux->budget = (uint64_t)(mb && *mb ? atoll(mb) : 128) << 20; }
    { uint32_t hs = 64; while (hs < (1u << 20) && (uint64_t)hs * 32768 < aux->budget) hs <<= 1; aux->hmask = hs - 1; if (!(aux->hash = calloc(hs, sizeof *aux->hash))) { e = ENOMEM; NX_BAD("out of memory"); } }
    snprintf(aux->what, sizeof aux->what, "%s %s%s%s%s", base, pfx, sel[0] ? sel : "(newest)", prog[0] ? "/" : "", prog);
    const nx_man M = {man, aux->idx, o_dir, o_ent, o_file, o_ref, o_path, path_size, ndir, nfile, nref, H.nblk};

    // ---- The package (android/NX.md "Packages and names") ----
    int want = -1; uint32_t want_ver = 0; uint64_t want_tid = 0; int aoc = -1;   // want: -2 newest, 0 base, 1 update, 2 add-on
    if (!sel[0]) want = -2;
    else if (!strcmp(sel, "base")) want = 0;
    else if (!strncmp(sel, "update/", 7) && sel[7] && strlen(sel + 7) <= 10 && strspn(sel + 7, "0123456789") == strlen(sel + 7) && strtoull(sel + 7, NULL, 10) <= UINT32_MAX) { want = 1; want_ver = (uint32_t)strtoul(sel + 7, NULL, 10); }
    else if (!strncmp(sel, "addon/", 6) && strlen(sel + 6) == 16 && strspn(sel + 6, "0123456789abcdefABCDEF") == 16) { want = 2; want_tid = strtoull(sel + 6, NULL, 16); }
    else if (!strncmp(sel, "aoc/", 4) && sel[4] && strlen(sel + 4) < 6 && strspn(sel + 4, "0123456789") == strlen(sel + 4)) { want = 2; aoc = atoi(sel + 4); }
    else NX_BAD("unknown selector \"%s\" (base, update/<version>, addon/<title ID>, each optionally after program<n>/ or followed by /program<n>)", sel_in);
    if (prog[0] && want == 2) NX_BAD("an add-on has no programs (\"%s/%s\")", sel, prog);
    // The game: the one application with a base or update snapshot (DLC of other applications is ignored); none in a DLC-only archive
    int64_t gapp = -1; char apps[200] = ""; int ngame = 0;
    for (uint32_t i = 0; i < napp; i++) {
        const uint8_t *a = cat + o_app + 80ull * i; int g = 0;
        for (uint32_t k = 0; k < nup_u32(a + 24); k++) { uint8_t ty = cat[o_snap + 120ull * (nup_u32(a + 20) + k) + 4]; g |= ty == 0 || ty == 1 || ty == 3; }
        if (g) { if (!ngame++) gapp = i; size_t L = strlen(apps); if (L < sizeof apps - 20) snprintf(apps + L, sizeof apps - L, "%s%016llx", L ? ", " : "", (unsigned long long)nup_u64(a)); }
    }
    if (ngame > 1) NX_BAD("an archive of %d games (applications %s): one game per .nx is read", ngame, apps);
    // The program: program 0 is the one whose ID is the application's, else the lowest index (program_id & 0xf; not program_kind: NXConvert marks a Switch 2
    // Edition's program 1 as primary); program n > 0 the one whose index is n. Two programs of the index chosen are ambiguous. An NCA snapshot's programs are
    // its CNMT's (the Program records' ID offsets), whatever program row it is under
    uint32_t mainp = NX_NONE;
    if (gapp >= 0) {
        const uint8_t *a = cat + o_app + 80ull * gapp; uint32_t fp = nup_u32(a + 12), np = nup_u32(a + 16); int widx = pidx;
        if (!pidx) for (uint32_t k = 0; k < np && mainp == NX_NONE; k++) if (nup_u64(cat + o_prog + 64ull * (fp + k)) == nup_u64(a)) mainp = fp + k;
        if (mainp == NX_NONE && np) {
            if (!pidx) { widx = 16; for (uint32_t k = 0; k < np; k++) if ((int)(nup_u64(cat + o_prog + 64ull * (fp + k)) & 0xf) < widx) widx = (int)(nup_u64(cat + o_prog + 64ull * (fp + k)) & 0xf); }
            int cnt = 0; for (uint32_t k = 0; k < np; k++) if ((int)(nup_u64(cat + o_prog + 64ull * (fp + k)) & 0xf) == widx) { if (!cnt++) mainp = fp + k; }
            if (cnt > 1) NX_BAD("the game has %d programs of index %d: ambiguous", cnt, widx);
        }
    }
    int64_t pick = -1, upick = -1, bpick = -1; uint32_t best = 0; int nmatch = 0, nupd = 0, nbase = 0, unknown = 0;
    for (uint32_t i = 0; i < nsnap; i++) {
        const uint8_t *s = cat + o_snap + 120ull * i; uint8_t ty = s[4]; uint32_t ver = nup_u32(s + 8);
        if (gapp >= 0) { const uint8_t *a = cat + o_app + 80ull * gapp; if (i < nup_u32(a + 20) || i >= nup_u32(a + 20) + nup_u32(a + 24)) continue; }
        int nca = nx_snap_nca(&M, nup_u32(s + 12));
        if (want == 2) {
            if (ty != 2) continue;
            uint64_t tid = nup_u64(s + 32);
            if (!(s[5] & 2)) {   // an add-on of an NCA snapshot: its title ID is its CNMT's
                if (!nca) continue;
                nx_ncx x; memset(&x, 0, sizeof x); x.m = m; x.a = aux; x.M = &M; x.root = nup_u32(s + 12); tid = 0;
                if (nx_ncx_cnmts(&x, why, wn)) { nx_ncx_free(&x); goto bad; }
                for (int c = 0; c < x.nc; c++) if (x.c[c].type == 0x82) tid = x.c[c].id;
                nx_ncx_free(&x);
                if (!tid) continue;
            }
            if (aoc >= 0 ? (int)(tid & 0xfff) == aoc : tid == want_tid) { pick = i; nmatch++; }
            continue;
        }
        if (!nca && (mainp == NX_NONE || nup_u32(s) != mainp)) continue;
        if (ty == 4) { unknown++; continue; }
        if (ty == 1 || ty == 3) {   // an update or a standalone update: update/<version>
            if (upick < 0 || ver > best) { upick = i; best = ver; nupd = 1; } else if (ver == best) nupd++;
            if (want == 1 && ver == want_ver) { pick = i; nmatch++; }
        } else if (ty == 0) { bpick = i; nbase++; if (!want) { pick = i; nmatch++; } }
    }
    if (want == -2) { pick = upick >= 0 ? upick : bpick; nmatch = upick >= 0 ? nupd : nbase; }
    if (pick < 0) {
        if (want != 2 && gapp < 0) NX_BAD("no game in the archive (add-on content only)");
        if (want != 2 && pidx && mainp == NX_NONE) NX_BAD("the game has no program %d (%s)", pidx, sel_in);
        NX_BAD("no snapshot %s%s%s", pfx, sel[0] ? sel : "(update or base)", unknown ? " (snapshots of an unknown type are not read)" : "");
    }
    if (nmatch > 1) NX_BAD("more than one snapshot is %s%s (they differ only in content ID or build ID)", pfx, sel[0] ? sel : "the newest");
    uint32_t root = nup_u32(cat + o_snap + 120ull * pick + 12), rdir = root; uint8_t kind = 0; uint32_t tgt = 0;

    if (nx_snap_nca(&M, root)) {   // ---- An NCA snapshot: its RomFS through the NCA's storages (nx_nca.h) ----
        if (prog[0]) NX_BAD("snapshot %s%s holds NCAs, not a %s folder (its programs are %sprogram<n>/ selectors)", pfx, sel[0] ? sel : "(newest)", prog, "");
        const uint8_t *s = cat + o_snap + 120ull * pick; uint8_t ty = s[4];
        nx_ncx x; memset(&x, 0, sizeof x); x.m = m; x.a = aux; x.M = &M; x.root = root;
        char w2[300] = ""; nxs *rs = NULL;
        if (!nx_ncx_cnmts(&x, w2, sizeof w2)) rs = nx_nca_romfs(&x, ty == 2 ? 2 : ty == 0 ? 0 : 1, nup_u32(s + 8), want == 2 && aoc < 0 ? want_tid : 0, want == 2 ? aoc : -1, pidx, w2, sizeof w2);
        if (rs && ty == 2 && want != 2) rs = NULL;
        nx_ncx_free(&x);
        if (!rs) NX_BAD("snapshot %s%s (from an NSP or XCI): %s", pfx, sel[0] ? sel : "(newest)", w2);
        aux->romfs = rs;
        if ((e = nx_nca_entries(m, rs, w2, sizeof w2))) NX_BAD("snapshot %s%s: its RomFS: %s", pfx, sel[0] ? sel : "(newest)", w2);
        free(tail); free(pages);
        return m;
    }

    // The snapshot's romfs/ (or program<n>/romfs/)
    if (prog[0]) {
        if (!nx_child(&M, rdir, prog, &kind, &tgt) || kind != 1) NX_BAD("snapshot %s has no %s folder", sel[0] ? sel : "(newest)", prog);
        rdir = tgt;
    }
    if (!nx_child(&M, rdir, sub, &kind, &tgt) || kind != 1) NX_BAD("snapshot %s has no %s%sromfs folder", sel[0] ? sel : "(newest)", prog, prog[0] ? "/" : "");
    rdir = tgt;

    // ---- The tree: breadth first from the RomFS root (romfs_image_open's shape), checked in pass 1, written in pass 2 ----
    uint64_t n = 1, names = 0;
    if (!(queue = malloc(sizeof *queue * (size_t)(ndir + 1))) || !(seen_d = calloc((size_t)ndir, 1)) || !(seen_f = calloc(nfile ? (size_t)nfile : 1, 1))) { e = ENOMEM; NX_BAD("out of memory"); }
    {   uint64_t qh = 0, qt = 0; queue[qt++] = rdir; seen_d[rdir] = 1;
        while (qh < qt) {
            const uint8_t *d = man + o_dir + 16ull * queue[qh++]; uint64_t first = nup_u32(d), cnt = nup_u32(d + 4);
            const uint8_t *prev = NULL; uint32_t plen = 0;
            for (uint64_t j = 0; j < cnt; j++) {
                const uint8_t *r = man + o_ent + 24 * (first + j); uint64_t no = nup_u64(r); uint32_t nl = nup_u32(r + 8), id = nup_u32(r + 12);
                if (r[16] < 1 || r[16] > 2 || r[17] || nx_u16(r + 18) || nup_u32(r + 20)) NX_BAD("a directory entry has a bad kind or nonzero reserved bytes");
                if (!nl || nl > 4096 || no < o_path || nl > path_size || no - o_path > path_size - nl) NX_BAD("a name is out of the path pool");
                const uint8_t *nm = man + no;
                if (memchr(nm, 0, nl) || memchr(nm, '/', nl) || memchr(nm, '\\', nl) || (nl == 1 && nm[0] == '.') || (nl == 2 && !memcmp(nm, "..", 2))) NX_BAD("a name is not a plain file name");
                if (prev && !nx_name_lt(prev, plen, nm, nl)) NX_BAD("a directory's children are not sorted by name (or repeat a name)");
                prev = nm; plen = nl;
                if (r[16] == 1) {
                    if (id >= ndir) NX_BAD("a directory entry's target is out of range");
                    if (seen_d[id]) NX_BAD("a directory is reached twice (a cycle)");
                    seen_d[id] = 1; queue[qt++] = id;
                } else {
                    if (id >= nfile) NX_BAD("a file entry's target is out of range");
                    if (seen_f[id]) NX_BAD("a file record is reached twice in one snapshot");
                    seen_f[id] = 1;
                }
                n++; names += nl;
            }
        }
    }
    if (n > UINT32_MAX) NX_BAD("too many entries");
    size_t isz = (size_t)n * sizeof(img_ent) + (size_t)names;
    if (!(m->e = malloc(isz ? isz : 1))) { e = ENOMEM; NX_BAD("out of memory"); }
    m->n = n; m->names = (const char *)m->e + n * sizeof(img_ent);
    if (!(ent_of = malloc(sizeof *ent_of * (size_t)(ndir + 1)))) { e = ENOMEM; NX_BAD("out of memory"); }
    {   char *np = (char *)m->names; uint64_t at = 0, k = 1, qh = 0, qt = 0;
        memset(&m->e[0], 0, sizeof m->e[0]); queue[qt] = rdir; ent_of[qt++] = 0;
        while (qh < qt) {   // directory queue[qh] became image entry ent_of[qh]; its children are the next run of entries
            uint64_t me = ent_of[qh]; const uint8_t *d = man + o_dir + 16ull * queue[qh++]; uint64_t first = nup_u32(d), cnt = nup_u32(d + 4);
            m->e[me].a = k; m->e[me].b = cnt;
            for (uint64_t j = 0; j < cnt; j++, k++) {
                const uint8_t *r = man + o_ent + 24 * (first + j); uint32_t nl = nup_u32(r + 8), id = nup_u32(r + 12);
                img_ent *x = &m->e[k]; memset(x, 0, sizeof *x);
                x->name = (uint32_t)at; x->nlen = (uint16_t)nl; memcpy(np + at, man + nup_u64(r), nl); at += nl;
                if (r[16] == 1) { queue[qt] = id; ent_of[qt++] = k; }
                else {
                    int64_t fi = nx_add_file(aux, &M, id, why, wn); if (fi < 0) goto bad;
                    x->type = 1; x->flags = IMG_ENT_NX; x->a = (uint64_t)fi; x->b = aux->f[fi].size;
                }
            }
        }
    }
    free(ent_of); free(queue); free(seen_d); free(seen_f); free(tail); free(pages);
    return m;
bad:
    if (m) romfs_image_close(m); else if (fd >= 0) close(fd);   // (aux exists only as m->aux, and is freed with the image)
    free(ent_of); free(queue); free(seen_d); free(seen_f); free(tail); free(pages);
    errno = e; return NULL;
#undef NX_BAD
}

// ---- Reads ----
// The image path of entry k ("/data/a.bin"), for messages: each step up finds the directory whose child run holds the entry
static void nx_ent_path(const romfs_img *m, uint64_t k, char *out, size_t on) {
    uint64_t chain[64]; int d = 0;
    while (k && d < 64) {
        chain[d++] = k; uint64_t p = 0;
        for (uint64_t i = 0; i < m->n; i++) if (!m->e[i].type && k >= m->e[i].a && k < m->e[i].a + m->e[i].b) { p = i; break; }
        k = p;
    }
    size_t at = 0; out[0] = 0;
    while (d-- && at + 2 < on) { const img_ent *x = &m->e[chain[d]]; at += (size_t)snprintf(out + at, on - at, "/%.*s", (int)x->nlen, m->names + x->name); if (at >= on) at = on - 1; }
}
// Copies [off, off + len) of image entry k (a file) into out: the bytes copied (len cut at the file's end), or -1 with errno set after one [RED] line
// naming the archive, the file and the block (and for an NCA snapshot, the NCA and the storage layer)
static int64_t nx_read(romfs_img *m, uint64_t k, uint64_t off, void *out, size_t len) {
    nx_aux *a = m->aux; const img_ent *x = &m->e[k]; char why[400] = ""; int e;
    if (off >= x->b) return 0;
    if (len > x->b - off) len = (size_t)(x->b - off);
    if (a->romfs) e = nxs_read(m, a->romfs, x->a + off, out, len, why, sizeof why);
    else e = nx_fread(m, (uint32_t)x->a, off, out, len, why, sizeof why);
    if (!e) return (int64_t)len;
    pthread_mutex_lock(&a->lock); uint64_t nred = a->nred++; pthread_mutex_unlock(&a->lock);
    if (nred < 32) {
        char path[512]; nx_ent_path(m, k, path, sizeof path);
        LOG("[RED] fs NX %s: %s: %s: the read fails%s", a->what, path, why, nred == 31 ? " (no more of these lines for this archive)" : "");
    }
    errno = e; return -1;
}
