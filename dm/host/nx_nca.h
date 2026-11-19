// NCA snapshots of an NX archive (android/NX.md "NCA snapshots"): a snapshot made from a decrypted NSP or XCI holds the content's NCAs as files at its root
// (<content ID>.nca, <content ID>.cnmt.nca). This file turns one into a RomFS image: the package's CNMT (application → base, patch → update/<version>,
// add-on → addon/<title ID>), the Program NCA whose content record has ID offset n (program<n>/), or the add-on's Data NCA, and the RomFS of its RomFS
// section. Each RomFS file's bytes are a range of a stack of storages, read per call:
//   NCA file (.nx blocks, nx_fread, CRC-checked) → section (its 0x200-byte units) → [indirect storage: the patch, BKTR] → IVFC data level → [compressed
//   storage: LZ4 blocks] → RomFS
// The order and the tables' places are Nintendo's, checked against Atmosphère's libstratosphere fssystem/fssystem_nca_file_system_driver.cpp
// (OpenStorageImpl, CreateIndirectStorage, CreateStorageByRawStorage, CreateCompressedStorage; the bucket tree in fssystem_bucket_tree.cpp):
//   - the indirect storage's table is at patch_info.indirect_offset of this section's own (decrypted) body; storage 0 is the original: the base NCA's
//     section of the same program, opened raw (no hash or compression layer); storage 1 is this section's body up to indirect_offset;
//   - the integrity layer comes next: its data storage is the last IVFC level (level 6), a range of the patched (or plain) section;
//   - the compressed storage comes after the integrity layer: its table is at compression_info.bucket.offset of that data level, its data is the data
//     level's [0, table offset), and the RomFS is the compressed storage's virtual bytes.
// The NCA's own hash levels are not checked (NX.md: the .nx blocks' CRC-32C and verify's SHA-256 cover the bytes). Refused, with the reason: a header that is
// not plain (DNCA or NCA3), an FS header whose SHA-256 does not match the NCA header's, an encrypted section (encryption type other than 1), a sparse
// section (FS header 0x148..0x178 not zero), an NCZ, a content record whose NCA is not in the snapshot (a delta fragment is not needed).
#pragma once
// Included only by nx_image.h, after its block reader (nx_fread), the cache, the storage type (nxs) and the Manifest helpers (nx_man, nx_child, nx_add_file)

#define NXS_FILE 0
#define NXS_SUB 1
#define NXS_IND 2
#define NXS_CMP 3
#define NX_BKTR_NODE 0x4000u
#define NX_LZ4_MAX (16u << 20)   // the most one compressed entry may decode to

static nxs *nxs_new(nx_aux *a, uint8_t kind, uint64_t size) {
    if (a->nst == a->cst) { uint32_t c = a->cst ? a->cst * 2 : 16; nxs **p = realloc(a->st, sizeof *p * c); if (!p) return NULL; a->st = p; a->cst = c; }
    nxs *s = calloc(1, sizeof *s); if (!s) return NULL;
    s->kind = kind; s->size = size; s->serial = a->nst; a->st[a->nst++] = s; return s;
}
static nxs *nxs_sub(nx_aux *a, nxs *parent, uint64_t off, uint64_t size) {
    if (off > parent->size || size > parent->size - off) return NULL;
    nxs *s = nxs_new(a, NXS_SUB, size); if (s) { s->a = parent; s->off = off; } return s;
}

// One LZ4 block (no frame): exactly dn bytes out of exactly sn bytes in; 0, or -1 for a block that is malformed or does not fill dn
static int nx_lz4(const uint8_t *src, size_t sn, uint8_t *dst, size_t dn) {
    size_t i = 0, o = 0;
    for (;;) {
        if (i >= sn) return -1;
        uint8_t t = src[i++]; size_t ll = t >> 4;
        if (ll == 15) { uint8_t b; do { if (i >= sn) return -1; b = src[i++]; ll += b; } while (b == 255); }
        if (ll > sn - i || ll > dn - o) return -1;
        memcpy(dst + o, src + i, ll); i += ll; o += ll;
        if (i == sn) return o == dn ? 0 : -1;   // the last sequence has literals only
        if (sn - i < 2) return -1;
        size_t d = (size_t)src[i] | (size_t)src[i + 1] << 8; i += 2;
        if (!d || d > o) return -1;
        size_t ml = t & 15;
        if (ml == 15) { uint8_t b; do { if (i >= sn) return -1; b = src[i++]; ml += b; } while (b == 255); }
        ml += 4;
        if (ml > dn - o) return -1;
        if (d >= ml) memcpy(dst + o, dst + o - d, ml); else for (size_t k = 0; k < ml; k++) dst[o + k] = dst[o - d + k];
        o += ml;
    }
}

static int nxs_read(romfs_img *m, const nxs *s, uint64_t off, void *out, size_t len, char *why, size_t wn);
// The entry of a bucket table that holds virtual offset pos (entries sorted by their first s64): its index
static uint32_t nx_bk_find(const nxs *s, uint32_t esz, uint64_t pos) {
    uint32_t lo = 0, hi = s->nent;
    while (hi - lo > 1) { uint32_t mid = lo + (hi - lo) / 2; if ((uint64_t)nup_u64(s->ent + (uint64_t)esz * mid) <= pos) lo = mid; else hi = mid; }
    return lo;
}
static uint64_t nx_bk_end(const nxs *s, uint32_t esz, uint32_t i) { return i + 1 < s->nent ? nup_u64(s->ent + (uint64_t)esz * (i + 1)) : s->size; }
typedef struct { const nxs *s; uint32_t i; } nx_lz4_job;
static int nx_lz4_load(romfs_img *m, void *ctx, uint8_t **out, uint32_t *size, char *why, size_t wn) {
    const nx_lz4_job *j = ctx; const uint8_t *e = j->s->ent + 24ull * j->i;
    uint64_t v = nup_u64(e), vn = nx_bk_end(j->s, 24, j->i) - v, p = nup_u64(e + 8); uint32_t pn = nup_u32(e + 20);
    uint8_t *src = malloc(pn ? pn : 1), *dst = malloc(vn ? (size_t)vn : 1); int r;
    if (!src || !dst) { free(src); free(dst); snprintf(why, wn, "out of memory"); return ENOMEM; }
    if ((r = nxs_read(m, j->s->a, p, src, pn, why, wn))) { free(src); free(dst); return r; }
    if (nx_lz4(src, pn, dst, (size_t)vn)) { free(src); free(dst); snprintf(why, wn, "compressed entry %u (virtual %#llx): its LZ4 block does not decode to %llu bytes", j->i, (unsigned long long)v, (unsigned long long)vn); return EIO; }
    free(src); *out = dst; *size = (uint32_t)vn; return 0;
}
// [off, off + len) of storage s into out: 0, or an errno with the reason in why
static int nxs_read(romfs_img *m, const nxs *s, uint64_t off, void *out, size_t len, char *why, size_t wn) {
    nx_aux *a = m->aux; int r;
    if (off < s->start || off > s->size || len > s->size - off) { snprintf(why, wn, "a read of %#llx+%#zx is outside a storage of %#llx bytes", (unsigned long long)off, len, (unsigned long long)s->size); return EIO; }
    switch (s->kind) {
    case NXS_FILE: {
        char w2[300] = ""; if (!(r = nx_fread(m, s->file, off, out, len, w2, sizeof w2))) return 0;
        snprintf(why, wn, "%s: %s", s->name, w2); return r; }
    case NXS_SUB: return nxs_read(m, s->a, s->off + off, out, len, why, wn);
    case NXS_IND:
    case NXS_CMP: {
        uint32_t esz = s->kind == NXS_IND ? 20 : 24; size_t done = 0;
        while (done < len) {
            uint64_t pos = off + done; uint32_t i = nx_bk_find(s, esz, pos); const uint8_t *e = s->ent + (uint64_t)esz * i;
            uint64_t v = nup_u64(e), ve = nx_bk_end(s, esz, i), p = nup_u64(e + 8);
            size_t n = ve - pos < len - done ? (size_t)(ve - pos) : len - done;
            if (s->kind == NXS_IND) {   // storage 0: the base's section, 1: this section's own data
                if ((r = nxs_read(m, nup_u32(e + 16) ? s->b : s->a, p + (pos - v), (uint8_t *)out + done, n, why, wn))) return r;
            } else if (e[16] == 0) { if ((r = nxs_read(m, s->a, p + (pos - v), (uint8_t *)out + done, n, why, wn))) return r; }
            else if (e[16] == 1) memset((uint8_t *)out + done, 0, n);
            else {   // LZ4: the entry decoded whole, through the block cache
                nx_lz4_job j = {s, i};
                nx_cb *c = nx_cache_get(m, (1ull << 63) | (uint64_t)s->serial << 32 | i, nx_lz4_load, &j, why, wn);
                if (!c) return errno ? errno : EIO;
                memcpy((uint8_t *)out + done, c->buf + (pos - v), n); nx_cache_put(a, c);
            }
            done += n;
        }
        return 0; }
    }
    snprintf(why, wn, "an unknown storage"); return EIO;
}

// A bucket tree (Atmosphère fssystem_bucket_tree.cpp): header (BKTR, version at most 1, entry count), then in the table: the node storage (one 0x4000-byte
// L1 node, plus L2 nodes when there are more entry sets than one node indexes), then entry sets of 0x4000 bytes (a 0x10-byte header: index, count, end
// offset; then entries of esz bytes). The entries, sorted by virtual offset, go to s->ent; the tree's end (the L1 node's offset field) to s->size
static int nx_bktr(romfs_img *m, nxs *s, const nxs *table, const uint8_t *hdr, uint32_t esz, char *why, size_t wn) {
    if (memcmp(hdr, "BKTR", 4) || nup_u32(hdr + 4) > 1) { snprintf(why, wn, "a bucket table header is not BKTR version 0 or 1"); return EINVAL; }
    int32_t cnt; memcpy(&cnt, hdr + 8, 4);
    if (cnt <= 0 || cnt > (1 << 24)) { snprintf(why, wn, "a bucket table has %d entries", cnt); return EINVAL; }
    uint32_t per = (NX_BKTR_NODE - 16) / esz, nsets = ((uint32_t)cnt + per - 1) / per, offs = (NX_BKTR_NODE - 16) / 8, l2 = 0;
    if (nsets > offs) { uint32_t nl2 = (nsets + offs - 1) / offs; l2 = (nsets - (offs - (nl2 - 1)) + offs - 1) / offs; }
    uint64_t nodes = (1ull + l2) * NX_BKTR_NODE, need = nodes + (uint64_t)nsets * NX_BKTR_NODE;
    if (need > table->size) { snprintf(why, wn, "a bucket table of %d entries needs %#llx bytes, its range has %#llx", cnt, (unsigned long long)need, (unsigned long long)table->size); return EINVAL; }
    uint8_t *node = malloc(NX_BKTR_NODE); int r;
    if (!node || !(s->ent = malloc((size_t)cnt * esz))) { free(node); snprintf(why, wn, "out of memory"); return ENOMEM; }
    if ((r = nxs_read(m, table, 0, node, 16, why, wn))) { free(node); return r; }
    int64_t end; memcpy(&end, node + 8, 8);
    if (nup_u32(node) != 0 || !nup_u32(node + 4) || end <= 0) { free(node); snprintf(why, wn, "a bucket table's L1 node header is bad"); return EINVAL; }
    uint32_t got = 0; int64_t prev = -1;
    for (uint32_t k = 0; k < nsets; k++) {
        if ((r = nxs_read(m, table, nodes + (uint64_t)k * NX_BKTR_NODE, node, NX_BKTR_NODE, why, wn))) { free(node); return r; }
        uint32_t n = nup_u32(node + 4);
        if (nup_u32(node) != k || !n || n > per || n > (uint32_t)cnt - got) { free(node); snprintf(why, wn, "bucket table entry set %u: bad header", k); return EINVAL; }
        for (uint32_t i = 0; i < n; i++) {
            const uint8_t *e = node + 16 + (uint64_t)esz * i; int64_t v; memcpy(&v, e, 8);
            if (v <= prev || v >= end) { free(node); snprintf(why, wn, "bucket table entry %u: its offset is out of order or past the end", got + i); return EINVAL; }
            prev = v; memcpy(s->ent + (uint64_t)esz * (got + i), e, esz);
        }
        got += n;
    }
    free(node);
    if (got != (uint32_t)cnt) { snprintf(why, wn, "a bucket table holds %u entries, its header says %d", got, cnt); return EINVAL; }
    s->nent = got; s->start = nup_u64(s->ent); s->size = (uint64_t)end;
    return 0;
}
// Checks an indirect (patch) or compressed table's entries against their storages' sizes
static int nx_bk_check(const nxs *s, char *why, size_t wn) {
    for (uint32_t i = 0; i < s->nent; i++) {
        const uint8_t *e = s->ent + (s->kind == NXS_IND ? 20ull : 24ull) * i; uint64_t vn = nx_bk_end(s, s->kind == NXS_IND ? 20 : 24, i) - nup_u64(e), p = nup_u64(e + 8);
        if (s->kind == NXS_IND) {
            uint32_t si = nup_u32(e + 16); const nxs *t = si ? s->b : s->a;
            if (si > 1 || p > t->size || vn > t->size - p) { snprintf(why, wn, "patch entry %u: storage %u range %#llx+%#llx is outside it", i, si, (unsigned long long)p, (unsigned long long)vn); return EINVAL; }
        } else {
            uint8_t ty = e[16]; uint32_t pn = nup_u32(e + 20);
            if (ty == 0 && (p > s->a->size || vn > s->a->size - p)) { snprintf(why, wn, "compressed entry %u is outside the data", i); return EINVAL; }
            if (ty == 3 && (!pn || p > s->a->size || pn > s->a->size - p || vn > NX_LZ4_MAX)) { snprintf(why, wn, "compressed entry %u: a bad LZ4 block range", i); return EINVAL; }
            if (ty != 0 && ty != 1 && ty != 3) { snprintf(why, wn, "compressed entry %u: compression type %u is not read", i, ty); return EINVAL; }
        }
    }
    return 0;
}

// ---- NCA files, CNMTs ----
typedef struct { uint64_t id; uint32_t ver; uint8_t type; uint32_t nrec; uint8_t *rec; char name[48]; } nx_cnmt;   // rec: nrec content records of 0x38 bytes
typedef struct {
    romfs_img *m; nx_aux *a; const nx_man *M; uint32_t root;
    struct { char name[64]; nxs *s; } f[64]; int nf;
    nx_cnmt c[16]; int nc;
} nx_ncx;
static void nx_ncx_free(nx_ncx *x) { for (int i = 0; i < x->nc; i++) free(x->c[i].rec); }
// A file at the snapshot's root as a storage (registered once)
static nxs *nx_ncx_file(nx_ncx *x, const char *name, char *why, size_t wn) {
    for (int i = 0; i < x->nf; i++) if (!strcmp(x->f[i].name, name)) return x->f[i].s;
    uint8_t kind; uint32_t id;
    if (!nx_child(x->M, x->root, name, &kind, &id) || kind != 2) { snprintf(why, wn, "%s is not in the snapshot", name); return NULL; }
    if (x->nf == 64) { snprintf(why, wn, "too many NCAs"); return NULL; }
    int64_t fi = nx_add_file(x->a, x->M, id, why, wn); if (fi < 0) return NULL;
    nxs *s = nxs_new(x->a, NXS_FILE, x->a->f[fi].size); if (!s) { snprintf(why, wn, "out of memory"); return NULL; }
    s->file = (uint32_t)fi; strlcpy(s->name, name, sizeof s->name);
    strlcpy(x->f[x->nf].name, name, sizeof x->f[0].name); x->f[x->nf++].s = s; return s;
}
// The NCA header (0xC00 bytes, plain) of storage f: magic, the FS headers' SHA-256, the sections inside the file. 0 or an errno with why
static int nx_nca_hdr(nx_ncx *x, const nxs *f, uint8_t *h, char *why, size_t wn) {
    int r;
    if (f->size < 0xC00) { snprintf(why, wn, "%s: shorter than an NCA header", f->name); return EINVAL; }
    if ((r = nxs_read(x->m, f, 0, h, 0xC00, why, wn))) return r;
    if (memcmp(h + 0x200, "NCA3", 4) && memcmp(h + 0x200, "DNCA", 4))
        { snprintf(why, wn, "%s: its header is not plain (no NCA3 or DNCA at 0x200: an encrypted NCA, which is not read)", f->name); return EINVAL; }
    for (int i = 0; i < 4; i++) {
        uint32_t st = nup_u32(h + 0x240 + 16 * i), en = nup_u32(h + 0x244 + 16 * i);
        if (!st && !en) continue;
        if (st < 6 || en <= st || (uint64_t)en * 512 > f->size) { snprintf(why, wn, "%s: section %d is outside the NCA", f->name, i); return EINVAL; }
        uint8_t d[32]; plat_sha256(h + 0x400 + 0x200 * i, 0x200, d);
        if (memcmp(d, h + 0x280 + 0x20 * i, 32)) { snprintf(why, wn, "%s: FS header %d does not match its SHA-256 in the NCA header (a damaged header)", f->name, i); return EINVAL; }
    }
    return 0;
}
// Section i of an NCA, checked as one this reader reads: its body as a storage, NULL with why
static nxs *nx_nca_section(nx_ncx *x, nxs *f, const uint8_t *h, int i, char *why, size_t wn) {
    uint32_t st = nup_u32(h + 0x240 + 16 * i), en = nup_u32(h + 0x244 + 16 * i); const uint8_t *fh = h + 0x400 + 0x200 * i;
    if (!st && !en) { snprintf(why, wn, "%s: section %d is empty", f->name, i); return NULL; }
    if (nx_u16(fh) != 2) { snprintf(why, wn, "%s: section %d: FS header version %u", f->name, i, nx_u16(fh)); return NULL; }
    if (fh[4] != 1) { snprintf(why, wn, "%s: section %d is encrypted (encryption type %u): not read", f->name, i, fh[4]); return NULL; }
    if (!nx_zero(fh + 0x148, 0x30)) { snprintf(why, wn, "%s: section %d is sparse: not read", f->name, i); return NULL; }
    nxs *s = nxs_sub(x->a, f, (uint64_t)st * 512, (uint64_t)(en - st) * 512);
    if (!s) snprintf(why, wn, "out of memory");
    return s;
}
// The files of the PartitionFS of a PFS0 section (fs type 1, HierarchicalSha256): its region as a storage and the entries; 0 or an errno with why
static int nx_pfs(nx_ncx *x, nxs *sec, const uint8_t *fh, nxs **pfs, uint8_t **tab, uint32_t *n, uint32_t *ss, char *why, size_t wn) {
    int r; uint8_t hh[16]; *tab = NULL;
    if (fh[2] != 1 || fh[3] != 2) { snprintf(why, wn, "not a PartitionFS section (fs %u, hash %u)", fh[2], fh[3]); return EINVAL; }
    if (!(*pfs = nxs_sub(x->a, sec, nup_u64(fh + 0x40), nup_u64(fh + 0x48)))) { snprintf(why, wn, "the PartitionFS is outside its section"); return EINVAL; }
    if ((*pfs)->size < 16 || (r = nxs_read(x->m, *pfs, 0, hh, 16, why, wn))) { if (!why[0]) snprintf(why, wn, "the PartitionFS is too short"); return EINVAL; }
    if (memcmp(hh, "PFS0", 4)) { snprintf(why, wn, "no PFS0 header"); return EINVAL; }
    *n = nup_u32(hh + 4); *ss = nup_u32(hh + 8);
    if (*n > 4096 || 16 + 0x18ull * *n + *ss > (*pfs)->size) { snprintf(why, wn, "the PFS0 header is cut off"); return EINVAL; }
    if (!(*tab = malloc(0x18ull * *n + *ss + 1))) { snprintf(why, wn, "out of memory"); return ENOMEM; }
    if ((r = nxs_read(x->m, *pfs, 16, *tab, 0x18ull * *n + *ss, why, wn))) { free(*tab); *tab = NULL; return r; }
    (*tab)[0x18ull * *n + *ss] = 0; return 0;
}
// Every *.cnmt.nca at the snapshot's root, parsed into x->c. 0 or an errno with why
static int nx_ncx_cnmts(nx_ncx *x, char *why, size_t wn) {
    const nx_man *M = x->M; const uint8_t *d = M->man + M->o_dir + 16ull * x->root;
    for (uint64_t j = 0; j < nup_u32(d + 4); j++) {
        const uint8_t *e = M->man + M->o_ent + 24 * (nup_u32(d) + j); uint64_t no = nup_u64(e); uint32_t nl = nup_u32(e + 8);
        if (no < M->o_path || nl > M->path_size || no - M->o_path > M->path_size - nl) { snprintf(why, wn, "a name is out of the path pool"); return EINVAL; }
        const char *nm = (const char *)M->man + no;
        if (nl > 4 && !memcmp(nm + nl - 4, ".ncz", 4)) { snprintf(why, wn, "%.*s is an NCZ (compressed NCA): not read", (int)nl, nm); return EINVAL; }
        if (e[16] != 2 || nl < 10 || nl >= 64 || memcmp(nm + nl - 9, ".cnmt.nca", 9)) continue;
        if (x->nc == 16) { snprintf(why, wn, "too many content metas"); return EINVAL; }
        char name[64]; memcpy(name, nm, nl); name[nl] = 0;
        uint8_t *h = malloc(0xC00), *tab = NULL; nxs *f, *sec, *pfs; uint32_t n = 0, ss = 0; int r = EINVAL;
        if (!h) { snprintf(why, wn, "out of memory"); return ENOMEM; }
        if (!(f = nx_ncx_file(x, name, why, wn)) || (r = nx_nca_hdr(x, f, h, why, wn))) { free(h); return r ? r : EINVAL; }
        if (h[0x205] != 1) { snprintf(why, wn, "%s: content type %u, not a meta NCA", name, h[0x205]); free(h); return EINVAL; }
        if (!(sec = nx_nca_section(x, f, h, 0, why, wn))) { free(h); return EINVAL; }
        char w2[200] = "";
        if ((r = nx_pfs(x, sec, h + 0x400, &pfs, &tab, &n, &ss, w2, sizeof w2))) { free(h); snprintf(why, wn, "%s: %s", name, w2); return r; }
        free(h);
        int k = -1; for (uint32_t i = 0; i < n; i++) { uint32_t so = nup_u32(tab + 0x18ull * i + 16); size_t L = so < ss ? strnlen((char *)tab + 0x18ull * n + so, ss - so) : 0;
            if (L > 5 && !memcmp(tab + 0x18ull * n + so + L - 5, ".cnmt", 5)) { if (k >= 0) k = -2; else if (k == -1) k = (int)i; } }
        if (k < 0) { free(tab); snprintf(why, wn, "%s: expected one .cnmt in its PartitionFS", name); return EINVAL; }
        uint64_t co = nup_u64(tab + 0x18ull * k), cs = nup_u64(tab + 0x18ull * k + 8), data = 16 + 0x18ull * n + ss; free(tab);
        if (cs < 0x20 || cs > (1u << 20) || co > pfs->size - data || cs > pfs->size - data - co) { snprintf(why, wn, "%s: the .cnmt is outside its PartitionFS", name); return EINVAL; }
        uint8_t *c = malloc((size_t)cs); if (!c) { snprintf(why, wn, "out of memory"); return ENOMEM; }
        if ((r = nxs_read(x->m, pfs, data + co, c, (size_t)cs, why, wn))) { free(c); return r; }
        uint16_t exs = nx_u16(c + 14), cnt = nx_u16(c + 16);
        if (0x20 + (uint64_t)exs + 0x38ull * cnt > cs) { free(c); snprintf(why, wn, "%s: the .cnmt is cut off", name); return EINVAL; }
        nx_cnmt *m = &x->c[x->nc++]; m->id = nup_u64(c); m->ver = nup_u32(c + 8); m->type = c[12]; m->nrec = cnt; strlcpy(m->name, name, sizeof m->name);
        if (!(m->rec = malloc(0x38ull * cnt + 1))) { free(c); snprintf(why, wn, "out of memory"); return ENOMEM; }
        memcpy(m->rec, c + 0x20 + exs, 0x38ull * cnt); free(c);
    }
    return 0;
}
static void nx_rec_name(const uint8_t *r, char *out) { for (int q = 0; q < 16; q++) snprintf(out + 2 * q, 3, "%02x", r[0x20 + q]); strcpy(out + 32, ".nca"); }
// The NCA of the content record of type rt (1 program, 2 data; ncm's ContentType) with ID offset ido (-1: any) in CNMT c; every record's NCA but a delta
// fragment's must be in the snapshot. NULL with why
static nxs *nx_cnmt_nca(nx_ncx *x, const nx_cnmt *c, uint8_t rt, int ido, char *why, size_t wn) {
    nxs *got = NULL; char nm[40];
    for (uint32_t i = 0; i < c->nrec; i++) {
        const uint8_t *r = c->rec + 0x38ull * i; if (r[0x36] == 6) continue;   // a delta fragment is not needed
        nx_rec_name(r, nm);
        nxs *f = nx_ncx_file(x, nm, why, wn);
        if (!f) { snprintf(why, wn, "content %s of %s (record type %u) is not in the snapshot", nm, c->name, r[0x36]); return NULL; }
        if (r[0x36] == rt && (ido < 0 || r[0x37] == ido) && !got) got = f;
    }
    if (!got) snprintf(why, wn, rt == 1 ? "%s has no program %d" : "%s has no data content", c->name, ido);
    return got;
}
// The RomFS section (fs type 0) of an NCA: its index, -1 with why
static int nx_romfs_section(const nxs *f, const uint8_t *h, char *why, size_t wn) {
    for (int i = 0; i < 4; i++) { const uint8_t *fh = h + 0x400 + 0x200 * i; if ((nup_u32(h + 0x240 + 16 * i) || nup_u32(h + 0x244 + 16 * i)) && fh[2] == 0) return i; }
    snprintf(why, wn, "%s has no RomFS section", f->name); return -1;
}

// The RomFS of the package (type 0 base, 1 update, 2 add-on) of an NCA snapshot, as a storage. tid/aoc pick an add-on; pidx the program. NULL with why
static nxs *nx_nca_romfs(nx_ncx *x, int type, uint32_t ver, uint64_t tid, int aoc, int pidx, char *why, size_t wn) {
    const nx_cnmt *pk = NULL, *bc = NULL; int npk = 0;
    for (int i = 0; i < x->nc; i++) {
        const nx_cnmt *c = &x->c[i];
        if (c->type == 0x80) bc = c;
        int ok = type == 0 ? c->type == 0x80 : type == 1 ? c->type == 0x81 && c->ver == ver : c->type == 0x82 && (aoc >= 0 ? (int)(c->id & 0xfff) == aoc : c->id == tid);
        if (ok) { pk = c; npk++; }
    }
    if (!npk) { snprintf(why, wn, "the snapshot has no content meta for this package (%s)", type == 0 ? "an application" : type == 1 ? "a patch of this version" : "this add-on"); return NULL; }
    if (npk > 1) { snprintf(why, wn, "the snapshot has %d content metas for this package", npk); return NULL; }
    uint8_t *h = malloc(0xC00), *bh = malloc(0xC00); nxs *f, *sec, *cur, *res = NULL; int r, si;
    if (!h || !bh) { snprintf(why, wn, "out of memory"); goto out; }
    if (!(f = nx_cnmt_nca(x, pk, type == 2 ? 2 : 1, type == 2 ? -1 : pidx, why, wn)) || nx_nca_hdr(x, f, h, why, wn)) goto out;
    if (h[0x205] != (type == 2 ? 4 : 0)) { snprintf(why, wn, "%s: content type %u, not a %s NCA", f->name, h[0x205], type == 2 ? "data" : "program"); goto out; }
    if ((si = nx_romfs_section(f, h, why, wn)) < 0 || !(sec = nx_nca_section(x, f, h, si, why, wn))) goto out;
    const uint8_t *fh = h + 0x400 + 0x200 * si; cur = sec;
    if (fh[3] != 3 || memcmp(fh + 8, "IVFC", 4)) { snprintf(why, wn, "%s: its RomFS section has no IVFC hash levels (hash type %u)", f->name, fh[3]); goto out; }
    // The patch: an indirect storage over the base's section (storage 0) and this section's body up to the table (storage 1)
    if (nup_u64(fh + 0x108)) {
        uint64_t io = nup_u64(fh + 0x100), isz = nup_u64(fh + 0x108); const nxs *tbl;
        if (!bc) { snprintf(why, wn, "%s is a patch, and the snapshot has no base content meta (the application's CNMT) for its base", f->name); goto out; }
        nxs *bf = nx_cnmt_nca(x, bc, 1, pidx, why, wn); int bsi;
        if (!bf || nx_nca_hdr(x, bf, bh, why, wn)) goto out;
        if ((bsi = nx_romfs_section(bf, bh, why, wn)) < 0) goto out;
        nxs *bsec = nx_nca_section(x, bf, bh, bsi, why, wn); if (!bsec) goto out;
        nxs *s1 = nxs_sub(x->a, sec, 0, io), *ind = nxs_new(x->a, NXS_IND, 0); tbl = nxs_sub(x->a, sec, io, isz);
        if (!s1 || !ind || !tbl) { snprintf(why, wn, "%s: the patch table is outside the section", f->name); goto out; }
        ind->a = bsec; ind->b = s1;
        char w2[200] = "";
        if ((r = nx_bktr(x->m, ind, tbl, fh + 0x110, 20, w2, sizeof w2)) || (r = nx_bk_check(ind, w2, sizeof w2))) { snprintf(why, wn, "%s: patch: %s", f->name, w2); goto out; }
        cur = ind;
    }
    // The integrity layer's data storage: the last IVFC level
    {   uint32_t lv = nup_u32(fh + 0x14);
        if (lv < 2 || lv > 7) { snprintf(why, wn, "%s: %u IVFC levels", f->name, lv); goto out; }
        uint64_t o = nup_u64(fh + 0x18 + 24 * (lv - 2)), z = nup_u64(fh + 0x20 + 24 * (lv - 2));
        if (!(cur = nxs_sub(x->a, cur, o, z))) { snprintf(why, wn, "%s: the RomFS level is outside its section", f->name); goto out; }
    }
    // Compressed storage, over the data level
    if (nup_u64(fh + 0x180)) {
        uint64_t to = nup_u64(fh + 0x178), tz = nup_u64(fh + 0x180); nxs *data = nxs_sub(x->a, cur, 0, to), *tbl = nxs_sub(x->a, cur, to, tz), *cmp = nxs_new(x->a, NXS_CMP, 0);
        if (!data || !tbl || !cmp) { snprintf(why, wn, "%s: the compression table is outside the RomFS level", f->name); goto out; }
        cmp->a = data; char w2[200] = "";
        if ((r = nx_bktr(x->m, cmp, tbl, fh + 0x188, 24, w2, sizeof w2)) || (r = nx_bk_check(cmp, w2, sizeof w2))) { snprintf(why, wn, "%s: compression: %s", f->name, w2); goto out; }
        if (cmp->start) { snprintf(why, wn, "%s: the compressed storage does not start at 0", f->name); goto out; }
        cur = cmp;
    }
    if (cur->kind == NXS_IND && cur->start) { snprintf(why, wn, "%s: the patched storage does not start at 0", f->name); goto out; }
    res = cur;
out:
    free(h); free(bh); return res;
}

// The RomFS at storage rs as image entries (the shape romfs_image_open checks): its tables read whole, walked by nsp_toc.h's nsp_walk (each file's
// offset relative to the RomFS), then laid out breadth first. 0 or an errno with why
static int nx_nca_entries(romfs_img *m, nxs *rs, char *why, size_t wn) {
    uint8_t h[0x50]; int r; nsp_b b; memset(&b, 0, sizeof b); uint8_t *tab = NULL; uint32_t *q = NULL, rootd = 0; uint64_t *eo = NULL; int ok = EINVAL;
    if (rs->size < 0x50) { snprintf(why, wn, "the RomFS is shorter than its header"); return EINVAL; }
    if ((r = nxs_read(m, rs, 0, h, 0x50, why, wn))) return r;
    uint64_t dt = nup_u64(h + 0x18), ds = nup_u64(h + 0x20), ft = nup_u64(h + 0x38), fs = nup_u64(h + 0x40), data = nup_u64(h + 0x48), size = rs->size;
    if (nup_u64(h) != 0x50 || dt > size || ds > size - dt || ft > size || fs > size - ft || data > size || ds < 0x18 || ds + fs > (256u << 20)) { snprintf(why, wn, "bad RomFS header"); return EINVAL; }
    if (!(tab = malloc((size_t)(ds + fs + 1)))) { snprintf(why, wn, "out of memory"); return ENOMEM; }
    if ((r = nxs_read(m, rs, dt, tab, (size_t)ds, why, wn)) || (r = nxs_read(m, rs, ft, tab + ds, (size_t)fs, why, wn))) { free(tab); return r; }
    nsp_rfs rf = {-1, 0, 0, tab, tab + ds, ds, fs, data, size, ds / 0x18 + 1, fs / 0x20 + 1, &b, why, wn};
    if (!nsp_walk(&rf, 0, 0, tab, 0, &rootd)) goto done;
    uint64_t n = 1 + b.nk, names = b.np;
    for (uint64_t i = 0; i < b.nk; i++) {   // names: plain file names, children strictly sorted (img_find's binary search)
        uint32_t k = b.k[i], no = k & 0x80000000u ? b.f[k & 0x7FFFFFFFu].no : b.d[k].no, nl = k & 0x80000000u ? b.f[k & 0x7FFFFFFFu].nl : b.d[k].nl;
        const uint8_t *nm = b.pool + no;
        if (!nl || nl > 0x300 || memchr(nm, 0, nl) || memchr(nm, '/', nl) || (nl == 1 && nm[0] == '.') || (nl == 2 && !memcmp(nm, "..", 2))) { snprintf(why, wn, "a RomFS name is not a plain file name"); goto done; }
    }
    for (uint64_t di = 0; di < b.nd; di++) for (uint32_t j = 1; j < b.d[di].cnt; j++) {
        uint32_t k0 = b.k[b.d[di].first + j - 1], k1 = b.k[b.d[di].first + j];
        uint32_t a0 = k0 & 0x80000000u ? b.f[k0 & 0x7FFFFFFFu].no : b.d[k0].no, l0 = k0 & 0x80000000u ? b.f[k0 & 0x7FFFFFFFu].nl : b.d[k0].nl;
        uint32_t a1 = k1 & 0x80000000u ? b.f[k1 & 0x7FFFFFFFu].no : b.d[k1].no, l1 = k1 & 0x80000000u ? b.f[k1 & 0x7FFFFFFFu].nl : b.d[k1].nl;
        if (!nx_name_lt(b.pool + a0, l0, b.pool + a1, l1)) { snprintf(why, wn, "a RomFS directory repeats a name"); goto done; }
    }
    if (n > UINT32_MAX) { snprintf(why, wn, "too many RomFS entries"); goto done; }
    if (!(m->e = malloc((size_t)n * sizeof(img_ent) + (size_t)names + 1)) || !(q = malloc(sizeof *q * (size_t)(b.nd + 1))) || !(eo = malloc(sizeof *eo * (size_t)(b.nd + 1)))) { snprintf(why, wn, "out of memory"); ok = ENOMEM; goto done; }
    m->n = n; m->names = (const char *)m->e + n * sizeof(img_ent);
    {   char *np = (char *)m->names; uint64_t at = 0, k = 1, qh = 0, qt = 0;
        memset(&m->e[0], 0, sizeof m->e[0]); q[qt] = rootd; eo[qt++] = 0;
        while (qh < qt) {
            uint64_t me = eo[qh]; const nsp_bdir *d = &b.d[q[qh++]];
            m->e[me].a = k; m->e[me].b = d->cnt;
            for (uint32_t j = 0; j < d->cnt; j++, k++) {
                uint32_t kid = b.k[d->first + j]; img_ent *x = &m->e[k]; memset(x, 0, sizeof *x);
                if (kid & 0x80000000u) {
                    const nsp_bfile *f = &b.f[kid & 0x7FFFFFFFu];
                    x->name = (uint32_t)at; x->nlen = (uint16_t)f->nl; memcpy(np + at, b.pool + f->no, f->nl); at += f->nl;
                    x->type = 1; x->flags = IMG_ENT_NX; x->a = f->off; x->b = f->size;
                } else {
                    const nsp_bdir *c = &b.d[kid];
                    x->name = (uint32_t)at; x->nlen = (uint16_t)c->nl; memcpy(np + at, b.pool + c->no, c->nl); at += c->nl;
                    q[qt] = kid; eo[qt++] = k;
                }
            }
        }
    }
    ok = 0;
done:
    free(tab); free(q); free(eo); free(b.d); free(b.f); free(b.k); free(b.pool);
    return ok;
}
