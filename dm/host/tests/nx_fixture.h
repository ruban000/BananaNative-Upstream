// A small NX archive writer for the host's unit tests (t_fs "NX archive"), written from the format's docs/SPEC.md (release 1.0.4, header version
// (0,2)) independently of dm/tools/nx_pack.py and of the reader under test (nx_image.h): only its CRC-32C is shared, and t_fs checks that against
// the standard check value first. Snapshots are given as lists of files by path ("romfs/data/a.bin"); each snapshot gets its own directories and
// file records, equal chunks share one block (compared byte by byte), a file asks for RAW or ZSTD blocks and its chunk size. A ZSTD block is one
// standard frame (single segment, content size, no dictionary, no checksum) of raw and RLE blocks, written here: it needs no compressor.
// Applications come from the snapshots' app IDs (grouped, in order); each NxProgram row covers the snapshots that name it (contiguous), so an application
// may have several programs with snapshots of their own (a Switch 2 Edition: program 0 with a base and an update, program 1 with an update only).
// No Media region, no strings, no metadata: the reader does not use them. Needs plat_sha256 and nx_crc32c (include it after nx_image.h).
#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct { const char *path; const uint8_t *data; size_t size; uint8_t log2, zstd; } nxw_file;
typedef struct { uint64_t app; int prog; uint8_t type; uint32_t ver; uint64_t addon_tid; const nxw_file *f; int nf; } nxw_snap;   // prog: index in the program list, -1 none
typedef struct { uint64_t id, app; uint8_t kind; } nxw_prog;
typedef struct { uint8_t *b; size_t n, cap; } nxw_buf;

static void nxw_put(nxw_buf *o, const void *p, size_t n) {
    if (o->n + n > o->cap) { o->cap = (o->n + n) * 2 + 4096; o->b = realloc(o->b, o->cap); }
    if (p) memcpy(o->b + o->n, p, n); else memset(o->b + o->n, 0, n);
    o->n += n;
}
static void nxw_pad(nxw_buf *o, size_t al) { while (o->n % al) nxw_put(o, NULL, 1); }
static void nxw_w16(uint8_t *p, uint16_t v) { memcpy(p, &v, 2); }
static void nxw_w32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }
static void nxw_w64(uint8_t *p, uint64_t v) { memcpy(p, &v, 8); }

// One zstd frame of raw and RLE blocks holding p[0..n) (1 <= n <= 1 MiB): runs of 32 or more equal bytes become RLE blocks
static void nxw_zstd(nxw_buf *o, const uint8_t *p, size_t n) {
    uint8_t h[9] = {0x28, 0xb5, 0x2f, 0xfd, 0xa0}; nxw_w32(h + 5, (uint32_t)n); nxw_put(o, h, 9);   // FHD 0xa0: single segment, a 4-byte content size
    size_t i = 0, maxb = n < 131072 ? n : 131072;
    while (i < n) {
        size_t r = 1; while (i + r < n && p[i + r] == p[i] && r < maxb) r++;
        uint8_t bh[3]; uint32_t v;
        if (r >= 32) { v = (uint32_t)(r << 3) | (1u << 1) | (i + r == n); bh[0] = (uint8_t)v; bh[1] = (uint8_t)(v >> 8); bh[2] = (uint8_t)(v >> 16); nxw_put(o, bh, 3); nxw_put(o, p + i, 1); i += r; continue; }
        size_t j = i;   // a raw stretch up to the next long run
        while (j < n && j - i < maxb) { size_t q = 1; while (j + q < n && p[j + q] == p[j] && q < 32) q++; if (q >= 32) break; j += q; }
        if (j - i > maxb) j = i + maxb;
        v = (uint32_t)((j - i) << 3) | (j == n); bh[0] = (uint8_t)v; bh[1] = (uint8_t)(v >> 8); bh[2] = (uint8_t)(v >> 16); nxw_put(o, bh, 3); nxw_put(o, p + i, j - i); i = j;
    }
}

typedef struct { const uint8_t *src; uint32_t lg; } nxw_blk;
// Writes the archive into *out (malloc'd); returns its size. req_ext: a nonzero type adds one required global Catalog extension of that type
static size_t nx_fixture_write(const nxw_snap *S, int ns, const nxw_prog *P, int np, uint32_t req_ext, uint8_t **out) {
    nxw_buf data = {0}, dirs = {0}, ents = {0}, files = {0}, refs = {0}, pool = {0}, idx = {0}, integ = {0};
    nxw_blk *blk = NULL; uint32_t nblk = 0, ndir = 0, nent = 0, nfile = 0, nref = 0, ncheck = 0;
    uint32_t *root = calloc((size_t)ns, 4); uint64_t *lsize = calloc((size_t)ns, 8);
    nxw_put(&data, NULL, 8192);   // the two header pages; blocks from 8192
    for (int s = 0; s < ns; s++) {
        // Breadth first: directory IDs are given as directories are queued, so the entry runs follow directory ID order
        char *q[256]; uint32_t qn = 0, qh = 0; q[qn++] = strdup(""); root[s] = ndir++;
        while (qh < qn) {
            char *pre = q[qh++]; size_t pl = strlen(pre);
            const char *nm[64]; size_t nl[64]; int isdir[64], fi[64], nk = 0;
            for (int i = 0; i < S[s].nf; i++) {
                const char *path = S[s].f[i].path, *rest;
                if (!pl) rest = path; else if (!strncmp(path, pre, pl) && path[pl] == '/') rest = path + pl + 1; else continue;
                const char *sl = strchr(rest, '/'); size_t L = sl ? (size_t)(sl - rest) : strlen(rest); int dup = 0;
                for (int k = 0; k < nk; k++) if (nl[k] == L && !memcmp(nm[k], rest, L)) dup = 1;
                if (!dup) { nm[nk] = rest; nl[nk] = L; isdir[nk] = sl != NULL; fi[nk] = i; nk++; }
            }
            for (int a = 1; a < nk; a++) for (int b = a; b > 0; b--) {   // sorted bytewise, a shorter prefix first
                int c = memcmp(nm[b - 1], nm[b], nl[b - 1] < nl[b] ? nl[b - 1] : nl[b]);
                if (c < 0 || (!c && nl[b - 1] < nl[b])) break;
                const char *tn = nm[b]; nm[b] = nm[b - 1]; nm[b - 1] = tn; size_t tl = nl[b]; nl[b] = nl[b - 1]; nl[b - 1] = tl;
                int t = isdir[b]; isdir[b] = isdir[b - 1]; isdir[b - 1] = t; t = fi[b]; fi[b] = fi[b - 1]; fi[b - 1] = t;
            }
            uint8_t d[16] = {0}; nxw_w32(d, nent); nxw_w32(d + 4, (uint32_t)nk); nxw_put(&dirs, d, 16);
            for (int k = 0; k < nk; k++) {
                uint8_t e[24] = {0}; nxw_w64(e, pool.n); nxw_w32(e + 8, (uint32_t)nl[k]); nxw_put(&pool, nm[k], nl[k]);   // (pool-relative: made Manifest-relative below)
                if (isdir[k]) {
                    char *c = malloc(pl + nl[k] + 2); sprintf(c, "%s%s%.*s", pre, pl ? "/" : "", (int)nl[k], nm[k]);
                    nxw_w32(e + 12, ndir++); e[16] = 1; q[qn++] = c;
                } else {
                    const nxw_file *f = &S[s].f[fi[k]]; uint64_t cs = 1ull << f->log2; uint32_t nb = (uint32_t)((f->size + cs - 1) / cs);
                    uint8_t r[64] = {0}; nxw_w64(r, f->size); nxw_w32(r + 8, nref); nxw_w32(r + 12, nb); r[16] = f->log2; plat_sha256(f->data, f->size, r + 20);
                    nxw_put(&files, r, 64); lsize[s] += f->size;
                    for (uint32_t b = 0; b < nb; b++) {
                        const uint8_t *src = f->data + b * cs; uint32_t lg = (uint32_t)(b + 1 < nb ? cs : f->size - b * cs), id = 0;
                        while (id < nblk && !(blk[id].lg == lg && !memcmp(blk[id].src, src, lg))) id++;
                        if (id == nblk) {   // a new block: its bytes, descriptor and slice CRCs
                            blk = realloc(blk, sizeof *blk * (nblk + 1)); blk[nblk].src = src; blk[nblk].lg = lg; nblk++;
                            nxw_pad(&data, 16); uint64_t at = data.n; size_t st;
                            if (f->zstd) { nxw_zstd(&data, src, lg); st = data.n - at; } else { nxw_put(&data, src, lg); st = lg; }
                            uint8_t x[24] = {0}; nxw_w64(x, at); nxw_w32(x + 8, (uint32_t)st); nxw_w32(x + 12, lg); x[16] = f->zstd ? 1 : 0; nxw_w32(x + 20, ncheck); nxw_put(&idx, x, 24);
                            for (uint32_t o = 0; o < lg; o += 16384) { uint8_t c4[4]; nxw_w32(c4, nx_crc32c(src + o, lg - o < 16384 ? lg - o : 16384)); nxw_put(&integ, c4, 4); ncheck++; }
                        }
                        uint8_t r4[4]; nxw_w32(r4, id); nxw_put(&refs, r4, 4); nref++;
                    }
                    nxw_w32(e + 12, nfile++); e[16] = 2;
                }
                nxw_put(&ents, e, 24); nent++;
            }
            free(pre);
        }
    }
    uint64_t data_end = data.n;
    // ---- The Manifest ----
    nxw_buf man = {0}; nxw_put(&man, NULL, 96);
    uint64_t o_dir = 0, o_file = 0, o_ent = 0, o_ref = 0, o_pool;
    nxw_pad(&man, 8); o_dir = man.n; nxw_put(&man, dirs.b, dirs.n);
    if (nfile) { nxw_pad(&man, 8); o_file = man.n; nxw_put(&man, files.b, files.n); }
    if (nent) { nxw_pad(&man, 8); o_ent = man.n; nxw_put(&man, ents.b, ents.n); }
    if (nref) { nxw_pad(&man, 4); o_ref = man.n; nxw_put(&man, refs.b, refs.n); }
    nxw_pad(&man, 8); o_pool = man.n; nxw_put(&man, pool.b, pool.n);
    for (uint32_t i = 0; i < nent; i++) { uint8_t *e = man.b + o_ent + 24 * i; uint64_t v; memcpy(&v, e, 8); nxw_w64(e, v + o_pool); }
    memcpy(man.b, "NXMF", 4); nxw_w16(man.b + 4, 1); nxw_w16(man.b + 6, 96); nxw_w64(man.b + 8, man.n);
    nxw_w32(man.b + 16, ndir); nxw_w32(man.b + 20, nfile); nxw_w32(man.b + 24, nent); nxw_w32(man.b + 28, nref);
    nxw_w64(man.b + 32, o_dir); nxw_w64(man.b + 40, o_file); nxw_w64(man.b + 48, o_ent); nxw_w64(man.b + 56, o_ref); nxw_w64(man.b + 64, o_pool); nxw_w64(man.b + 72, pool.n);
    // ---- The Catalog: applications by first appearance (the snapshots come grouped by application, programs in order) ----
    uint64_t apps[16]; int na = 0;
    for (int s = 0; s < ns; s++) if (!na || apps[na - 1] != S[s].app) apps[na++] = S[s].app;
    nxw_buf cat = {0}; nxw_put(&cat, NULL, 128);
    uint64_t o_app = cat.n; nxw_put(&cat, NULL, 80 * (size_t)na);
    uint64_t o_prog = 0; if (np) { nxw_pad(&cat, 8); o_prog = cat.n; nxw_put(&cat, NULL, 64 * (size_t)np); }
    nxw_pad(&cat, 8); uint64_t o_snap = cat.n; nxw_put(&cat, NULL, 120 * (size_t)ns);
    uint64_t o_ext = 0; if (req_ext) { nxw_pad(&cat, 8); o_ext = cat.n; nxw_put(&cat, NULL, 32); }
    nxw_pad(&cat, 8); uint64_t o_pool_c = cat.n;   // an empty string pool
    uint64_t o_payload = 0; if (req_ext) { nxw_pad(&cat, 8); o_payload = cat.n; nxw_put(&cat, NULL, 8); }
    for (int a = 0, s0 = 0, p0 = 0; a < na; a++) {
        uint8_t *r = cat.b + o_app + 80 * a; int s1 = s0, p1 = p0, base = 0, dlc = 0;
        while (s1 < ns && S[s1].app == apps[a]) { base |= S[s1].type == 0; dlc |= S[s1].type == 2; s1++; }
        while (p1 < np && P[p1].app == apps[a]) p1++;
        nxw_w64(r, apps[a]); r[8] = 2; r[9] = (uint8_t)(base | dlc << 1);
        nxw_w32(r + 12, p1 > p0 ? (uint32_t)p0 : 0xffffffffu); nxw_w32(r + 16, (uint32_t)(p1 - p0)); nxw_w32(r + 20, (uint32_t)s0); nxw_w32(r + 24, (uint32_t)(s1 - s0));
        nxw_w32(r + 28, 0xffffffffu); nxw_w32(r + 36, 0xffffffffu); nxw_w32(r + 44, 0xffffffffu); nxw_w32(r + 56, 0xffffffffu); nxw_w32(r + 68, 0xffffffffu);
        s0 = s1; p0 = p1;
    }
    for (int p = 0; p < np; p++) {
        uint8_t *r = cat.b + o_prog + 64 * p; int a = 0, s0 = -1, sc = 0; while (apps[a] != P[p].app) a++;
        for (int s = 0; s < ns; s++) if (S[s].prog == p) { if (s0 < 0) s0 = s; sc++; }
        nxw_w64(r, P[p].id); nxw_w32(r + 8, (uint32_t)a); r[12] = P[p].kind; nxw_w32(r + 32, (uint32_t)s0); nxw_w32(r + 36, (uint32_t)sc); nxw_w32(r + 40, 0xffffffffu);
    }
    for (int s = 0; s < ns; s++) {
        uint8_t *r = cat.b + o_snap + 120 * s;
        nxw_w32(r, S[s].prog < 0 ? 0xffffffffu : (uint32_t)S[s].prog); r[4] = S[s].type; r[5] = S[s].addon_tid ? 2 : 0;
        nxw_w32(r + 8, S[s].ver); nxw_w32(r + 12, root[s]); nxw_w64(r + 16, lsize[s]); nxw_w32(r + 24, 0xffffffffu);
        if (S[s].addon_tid) nxw_w64(r + 32, S[s].addon_tid);
        uint8_t idn[8]; nxw_w64(idn, 0x4e58534e41500000ull + (uint64_t)s); plat_sha256(idn, 8, r + 56);   // a distinct identity (not the canonical §12 one: the reader does not check it)
    }
    if (req_ext) { uint8_t *x = cat.b + o_ext; nxw_w32(x, req_ext); nxw_w32(x + 4, 1); nxw_w32(x + 12, 0xffffffffu); nxw_w64(x + 16, o_payload); nxw_w64(x + 24, 8); }
    memcpy(cat.b, "NXCT", 4); nxw_w16(cat.b + 4, 1); nxw_w16(cat.b + 6, 128); nxw_w64(cat.b + 8, cat.n);
    nxw_w32(cat.b + 20, (uint32_t)na); nxw_w32(cat.b + 24, (uint32_t)np); nxw_w32(cat.b + 28, (uint32_t)ns); nxw_w32(cat.b + 40, req_ext ? 1 : 0);
    nxw_w64(cat.b + 48, o_app); nxw_w64(cat.b + 56, o_prog); nxw_w64(cat.b + 64, o_snap); nxw_w64(cat.b + 88, o_ext); nxw_w64(cat.b + 96, o_pool_c); nxw_w64(cat.b + 104, 0);
    // ---- The tail and the header slots ----
    nxw_buf o = data; uint8_t h[256] = {0};
    nxw_pad(&o, 16); uint64_t cat_off = o.n; nxw_put(&o, cat.b, cat.n);
    nxw_pad(&o, 16); uint64_t man_off = o.n; nxw_put(&o, man.b, man.n);
    uint64_t idx_off = 0, int_off = 0;
    if (nblk) { nxw_pad(&o, 16); idx_off = o.n; nxw_put(&o, idx.b, idx.n); nxw_pad(&o, 16); int_off = o.n; nxw_put(&o, integ.b, integ.n); }
    memcpy(h, "NXAR", 4); nxw_w16(h + 6, 2); nxw_w32(h + 8, 256); nxw_w16(h + 12, 1); nxw_w64(h + 16, o.n);
    nxw_w64(h + 24, cat_off); nxw_w64(h + 32, cat.n);
    if (nblk) { nxw_w64(h + 56, 8192); nxw_w64(h + 64, data_end - 8192); }
    nxw_w64(h + 72, man_off); nxw_w64(h + 80, man.n); nxw_w64(h + 88, idx_off); nxw_w64(h + 96, nblk); nxw_w64(h + 104, int_off); nxw_w64(h + 112, integ.n);
    plat_sha256(cat.b, cat.n, h + 120); plat_sha256(man.b, man.n, h + 152);
    { plat_sha256_t c; plat_sha256_init(&c); plat_sha256_update(&c, idx.b, idx.n); plat_sha256_update(&c, integ.b, integ.n); plat_sha256_final(h + 184, &c); }
    nxw_w32(h + 224, 16384); nxw_w32(h + 252, nx_crc32c(h, 252));
    memcpy(o.b, h, 256); memcpy(o.b + 4096, h, 256);
    free(dirs.b); free(ents.b); free(files.b); free(refs.b); free(pool.b); free(idx.b); free(integ.b); free(man.b); free(cat.b); free(blk); free(root); free(lsize);
    *out = o.b; return o.n;
}
// Rewrites a header slot's CRC after a test changed it
static void nx_fixture_recrc(uint8_t *slot) { nxw_w32(slot + 252, nx_crc32c(slot, 252)); }
