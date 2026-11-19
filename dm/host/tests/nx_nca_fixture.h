// NCA snapshots for the host's NX tests (t_fs "NX archive: NCA snapshots"), written in C from the layouts in android/NX.md "NCA snapshots" and the public
// descriptions of the formats (switchbrew's NCA, CNMT, PFS0, RomFS pages; Atmosphère's bucket tree, indirect and compressed storages), independently of
// the reader (nx_nca.h) and of dm/tools/nx_pack.py. A game of two programs as a decrypted NSP would give it:
//   base snapshot:   <app>.cnmt.nca (application meta: program 0 and 1), P0.nca (ExeFS PFS0 + plain RomFS), P1.nca (ExeFS + a compressed RomFS: LZ4,
//                    zero and stored entries)
//   update snapshot: <patch>.cnmt.nca (patch meta: program 0 and 1, and a delta fragment that is not there), U0.nca (a RomFS patched over P0's: an
//                    indirect table of several entry sets), U1.nca (patched over P1's, then compressed), and the base's CNMT, P0 and P1 (the complete view)
//   add-on snapshot: <aoc>.cnmt.nca (add-on meta), D.nca (a data NCA with a plain RomFS)
// The NCAs' own hash levels are left empty (the reader does not check them). Needs nx_fixture.h (nxw_buf, nxw_put, nxw_w*), plat_sha256.
#pragma once
#include "nx_fixture.h"

typedef struct { const char *path; const uint8_t *data; size_t size; } nxn_src;   // a RomFS file: its path in the RomFS, its bytes

static void nxn_pad(nxw_buf *o, size_t al) { while (o->n % al) nxw_put(o, NULL, 1); }
// A RomFS image of files (paths with folders), tables after the file data
static void nxn_romfs(nxw_buf *o, const nxn_src *F, int nf) {
    char dp[64][128]; int dpar[64], nd = 1, fdir[64]; dp[0][0] = 0; dpar[0] = -1;
    for (int i = 0; i < nf; i++) {   // the folders of each path, parents first
        const char *p = F[i].path; int cur = 0;
        for (const char *s = strchr(p, '/'); s; s = strchr(s + 1, '/')) {
            char d[128]; snprintf(d, sizeof d, "%.*s", (int)(s - p), p); int k = 0;
            while (k < nd && strcmp(dp[k], d)) k++;
            if (k == nd) { strcpy(dp[nd], d); dpar[nd] = cur; nd++; }
            cur = k;
        }
        fdir[i] = cur;
    }
    const char *dname[64], *fname[64]; uint32_t doff[64], foff[64], dsz = 0, fsz = 0;
    for (int i = 0; i < nd; i++) { const char *s = strrchr(dp[i], '/'); dname[i] = i ? (s ? s + 1 : dp[i]) : ""; doff[i] = dsz; dsz += 0x18 + ((strlen(dname[i]) + 3) & ~3u); }
    for (int i = 0; i < nf; i++) { const char *s = strrchr(F[i].path, '/'); fname[i] = s ? s + 1 : F[i].path; foff[i] = fsz; fsz += 0x20 + ((strlen(fname[i]) + 3) & ~3u); }
    size_t base = o->n; nxw_put(o, NULL, 0x200); uint64_t dat[64];
    for (int i = 0; i < nf; i++) { nxn_pad(o, 16); dat[i] = o->n - base - 0x200; nxw_put(o, F[i].data, F[i].size); }
    nxn_pad(o, 4); uint64_t dh = o->n - base; nxw_put(o, "\xff\xff\xff\xff", 4);
    uint64_t dt = o->n - base;
    for (int i = 0; i < nd; i++) {
        uint8_t e[0x18]; uint32_t cd = 0xFFFFFFFFu, cf = 0xFFFFFFFFu, sib = 0xFFFFFFFFu, L = (uint32_t)strlen(dname[i]);
        for (int j = 1; j < nd && cd == 0xFFFFFFFFu; j++) if (dpar[j] == i) cd = doff[j];
        for (int j = i + 1; j < nd && i && sib == 0xFFFFFFFFu; j++) if (dpar[j] == dpar[i]) sib = doff[j];
        for (int j = 0; j < nf && cf == 0xFFFFFFFFu; j++) if (fdir[j] == i) cf = foff[j];
        nxw_w32(e, i ? doff[dpar[i]] : 0); nxw_w32(e + 4, sib); nxw_w32(e + 8, cd); nxw_w32(e + 12, cf); nxw_w32(e + 16, 0xFFFFFFFFu); nxw_w32(e + 20, L);
        nxw_put(o, e, 0x18); nxw_put(o, dname[i], L); while ((o->n - base - dt) % 4) nxw_put(o, NULL, 1);
    }
    uint64_t fh = o->n - base; nxw_put(o, "\xff\xff\xff\xff", 4);
    uint64_t ft = o->n - base;
    for (int i = 0; i < nf; i++) {
        uint8_t e[0x20]; uint32_t sib = 0xFFFFFFFFu, L = (uint32_t)strlen(fname[i]);
        for (int j = i + 1; j < nf && sib == 0xFFFFFFFFu; j++) if (fdir[j] == fdir[i]) sib = foff[j];
        nxw_w32(e, doff[fdir[i]]); nxw_w32(e + 4, sib); nxw_w64(e + 8, dat[i]); nxw_w64(e + 16, F[i].size); nxw_w32(e + 24, 0xFFFFFFFFu); nxw_w32(e + 28, L);
        nxw_put(o, e, 0x20); nxw_put(o, fname[i], L); while ((o->n - base - ft) % 4) nxw_put(o, NULL, 1);
    }
    uint8_t *h = o->b + base; nxw_w64(h, 0x50); nxw_w64(h + 8, dh); nxw_w64(h + 0x10, 4); nxw_w64(h + 0x18, dt); nxw_w64(h + 0x20, dsz);
    nxw_w64(h + 0x28, fh); nxw_w64(h + 0x30, 4); nxw_w64(h + 0x38, ft); nxw_w64(h + 0x40, fsz); nxw_w64(h + 0x48, 0x200);
}
// A PFS0 of named files
static void nxn_pfs0(nxw_buf *o, const char **names, const uint8_t **data, const size_t *size, int n) {
    size_t st = 0; for (int i = 0; i < n; i++) st += strlen(names[i]) + 1; st = (st + 0x1F) & ~(size_t)0x1F;
    uint8_t h[16] = "PFS0"; nxw_w32(h + 4, (uint32_t)n); nxw_w32(h + 8, (uint32_t)st); nxw_put(o, h, 16);
    uint64_t at = 0; uint32_t so = 0;
    for (int i = 0; i < n; i++) { uint8_t e[0x18] = {0}; nxw_w64(e, at); nxw_w64(e + 8, size[i]); nxw_w32(e + 16, so); nxw_put(o, e, 0x18); at += size[i]; so += (uint32_t)strlen(names[i]) + 1; }
    size_t s0 = o->n; for (int i = 0; i < n; i++) nxw_put(o, names[i], strlen(names[i]) + 1);
    while (o->n - s0 < st) nxw_put(o, NULL, 1);
    for (int i = 0; i < n; i++) nxw_put(o, data[i], size[i]);
}
// A greedy LZ4 block compressor (the block format: sequences of literals and matches of 4 or more bytes; the last 5 bytes are literals and no match starts
// in the last 12)
static void nxn_lz4_len(nxw_buf *o, size_t v) { while (v >= 255) { nxw_put(o, "\xff", 1); v -= 255; } uint8_t b = (uint8_t)v; nxw_put(o, &b, 1); }
static void nxn_lz4_seq(nxw_buf *o, const uint8_t *lit, size_t ll, size_t off, size_t ml) {
    uint8_t t = (uint8_t)((ll >= 15 ? 15 : ll) << 4 | (off ? (ml - 4 >= 15 ? 15 : ml - 4) : 0)); nxw_put(o, &t, 1);
    if (ll >= 15) nxn_lz4_len(o, ll - 15);
    nxw_put(o, lit, ll);
    if (off) { uint8_t d[2] = {(uint8_t)off, (uint8_t)(off >> 8)}; nxw_put(o, d, 2); if (ml - 4 >= 15) nxn_lz4_len(o, ml - 4 - 15); }
}
static void nxn_lz4(nxw_buf *o, const uint8_t *p, size_t n) {
    static int32_t ht[4096]; for (int i = 0; i < 4096; i++) ht[i] = -1;
    size_t i = 0, anchor = 0;
    while (n >= 13 && i + 12 < n) {
        uint32_t v; memcpy(&v, p + i, 4); uint32_t hk = (v * 2654435761u) >> 20; int32_t c = ht[hk]; ht[hk] = (int32_t)i;
        if (c >= 0 && i - (size_t)c <= 65535 && !memcmp(p + c, p + i, 4)) {
            size_t ml = 4; while (i + ml + 5 < n && p[c + ml] == p[i + ml]) ml++;
            nxn_lz4_seq(o, p + anchor, i - anchor, i - (size_t)c, ml); i += ml; anchor = i;
        } else i++;
    }
    nxn_lz4_seq(o, p + anchor, n - anchor, 0, 0);
}
// A bucket tree's table (node storage, then entry sets of 0x4000 bytes): count entries of esz bytes, their virtual end
static void nxn_bktr(nxw_buf *o, const uint8_t *ent, uint32_t count, uint32_t esz, uint64_t end) {
    uint32_t per = (0x4000 - 16) / esz, nsets = (count + per - 1) / per;
    size_t base = o->n; nxw_put(o, NULL, 0x4000 * (1 + (size_t)nsets));
    uint8_t *l1 = o->b + base; nxw_w32(l1, 0); nxw_w32(l1 + 4, nsets); nxw_w64(l1 + 8, end);
    for (uint32_t k = 0; k < nsets; k++) {
        uint32_t c = count - k * per < per ? count - k * per : per; uint8_t *s = o->b + base + 0x4000 * (1 + (size_t)k);
        memcpy(l1 + 16 + 8 * k, ent + (size_t)esz * k * per, 8);
        uint64_t send = k + 1 < nsets ? 0 : end; if (k + 1 < nsets) memcpy(&send, ent + (size_t)esz * (k + 1) * per, 8);
        nxw_w32(s, k); nxw_w32(s + 4, c); nxw_w64(s + 8, send); memcpy(s + 16, ent + (size_t)esz * k * per, (size_t)esz * c);
    }
}
// A compressed data level of D: entries of 0x4000 virtual bytes, LZ4 when that is smaller, zeros when all zero, else stored; the table after the data.
// *to, *tz: the table's offset and size in the level, hdr: its BKTR header
static void nxn_compress(nxw_buf *o, const uint8_t *D, size_t n, uint64_t *to, uint64_t *tz, uint8_t hdr[16]) {
    uint32_t ne = (uint32_t)((n + 0x3FFF) / 0x4000); uint8_t *ent = calloc(ne, 24); size_t base = o->n;
    for (uint32_t i = 0; i < ne; i++) {
        size_t v = (size_t)i * 0x4000, vn = n - v < 0x4000 ? n - v : 0x4000; uint8_t *e = ent + 24 * i; nxw_w64(e, v);
        int zero = 1; for (size_t k = 0; k < vn && zero; k++) zero = !D[v + k];
        if (zero) { e[16] = 1; continue; }
        nxw_buf z = {0}; nxn_lz4(&z, D + v, vn);
        nxn_pad(o, 16); nxw_w64(e + 8, o->n - base);
        if (z.n < vn) { e[16] = 3; nxw_w32(e + 20, (uint32_t)z.n); nxw_put(o, z.b, z.n); }
        else { e[16] = 0; nxw_w32(e + 20, (uint32_t)vn); nxw_put(o, D + v, vn); }
        free(z.b);
    }
    nxn_pad(o, 16); *to = o->n - base; nxn_bktr(o, ent, ne, 24, n); *tz = o->n - base - *to;
    memcpy(hdr, "BKTR", 4); nxw_w32(hdr + 4, 1); nxw_w32(hdr + 8, ne); nxw_w32(hdr + 12, 0); free(ent);
}
// One NCA section: fs 0 RomFS (IVFC; levels empty but the last, at 0) or 1 PFS0; its FS header's extras set by the caller through fh
typedef struct { uint8_t fs; nxw_buf body; uint8_t fh[0x200]; } nxn_sec;
static void nxn_sec_romfs(nxn_sec *s, uint64_t lvl_size) {
    memset(s->fh, 0, 0x200); nxw_w16(s->fh, 2); s->fh[2] = 0; s->fh[3] = 3; s->fh[4] = 1;
    memcpy(s->fh + 8, "IVFC", 4); nxw_w32(s->fh + 0xC, 0x20000); nxw_w32(s->fh + 0x10, 0x20); nxw_w32(s->fh + 0x14, 7);
    nxw_w64(s->fh + 0x18 + 24 * 5, 0); nxw_w64(s->fh + 0x20 + 24 * 5, lvl_size);
}
static void nxn_sec_pfs(nxn_sec *s) { memset(s->fh, 0, 0x200); nxw_w16(s->fh, 2); s->fh[2] = 1; s->fh[3] = 2; s->fh[4] = 1; nxw_w64(s->fh + 0x40, 0); nxw_w64(s->fh + 0x48, s->body.n); }
// The NCA bytes; *cid: its content ID (the first 16 bytes of its SHA-256). magic: "NCA3" or "DNCA"
static void nxn_nca(nxw_buf *o, const char *magic, uint8_t ctype, uint64_t pid, uint32_t cidx, nxn_sec *S, int ns, uint8_t cid[16]) {
    nxw_put(o, NULL, 0xC00); uint8_t *h;
    for (int i = 0; i < ns; i++) {
        uint32_t st = (uint32_t)(o->n / 512); nxw_put(o, S[i].body.b, S[i].body.n); nxn_pad(o, 512); if (o->n / 512 == st) nxw_put(o, NULL, 512);
        h = o->b; nxw_w32(h + 0x240 + 16 * i, st); nxw_w32(h + 0x244 + 16 * i, (uint32_t)(o->n / 512));
        memcpy(h + 0x400 + 0x200 * i, S[i].fh, 0x200); plat_sha256(S[i].fh, 0x200, h + 0x280 + 0x20 * i);
    }
    h = o->b; memcpy(h + 0x200, magic, 4); h[0x205] = ctype; nxw_w64(h + 0x208, o->n); nxw_w64(h + 0x210, pid); nxw_w32(h + 0x218, cidx);
    uint8_t d[32]; plat_sha256(o->b, o->n, d); memcpy(cid, d, 16);
}
static void nxn_name(const uint8_t cid[16], const char *ext, char *out) { for (int i = 0; i < 16; i++) sprintf(out + 2 * i, "%02x", cid[i]); strcpy(out + 32, ext); }
// A meta NCA: a PFS0 with one .cnmt of the records (content IDs, record types, ID offsets); *name its file name
static void nxn_meta(nxw_buf *o, uint64_t id, uint32_t ver, uint8_t type, int nrec, uint8_t (*cids)[16], const uint8_t *rtype, const uint8_t *idoff, char *name) {
    nxw_buf c = {0}; uint8_t h[0x20] = {0}; nxw_w64(h, id); nxw_w32(h + 8, ver); h[12] = type; nxw_w16(h + 14, 0x10); nxw_w16(h + 16, (uint16_t)nrec);
    nxw_put(&c, h, 0x20); nxw_put(&c, NULL, 0x10);
    for (int i = 0; i < nrec; i++) { uint8_t r[0x38] = {0}; memcpy(r + 0x20, cids[i], 16); nxw_w32(r + 0x30, 0x1000); r[0x36] = rtype[i]; r[0x37] = idoff[i]; nxw_put(&c, r, 0x38); }
    nxw_put(&c, NULL, 0x20);
    char cn[64]; snprintf(cn, sizeof cn, "%s_%016llx.cnmt", type == 0x80 ? "Application" : type == 0x81 ? "Patch" : "AddOnContent", (unsigned long long)id);
    const char *nm[1] = {cn}; const uint8_t *dt[1] = {c.b}; size_t sz[1] = {c.n};
    nxn_sec s; memset(&s, 0, sizeof s); nxn_pfs0(&s.body, nm, dt, sz, 1); nxn_sec_pfs(&s);
    uint8_t cid[16]; nxn_nca(o, "NCA3", 1, id, 0, &s, 1, cid); nxn_name(cid, ".cnmt.nca", name);
    free(c.b); free(s.body.b);
}
// A Program NCA: ExeFS {main}, and a RomFS section from the caller (body and FS header)
static void nxn_program(nxw_buf *o, const char *magic, uint64_t pid, uint32_t idx, const char *main_text, nxn_sec *rom, uint8_t cid[16]) {
    nxn_sec s[2]; memset(&s[0], 0, sizeof s[0]); const char *nm[1] = {"main"}; const uint8_t *dt[1] = {(const uint8_t *)main_text}; size_t sz[1] = {strlen(main_text)};
    nxn_pfs0(&s[0].body, nm, dt, sz, 1); nxn_sec_pfs(&s[0]); s[1] = *rom;
    nxn_nca(o, magic, 0, pid, idx, s, 2, cid); free(s[0].body.b);
}
// An update section: T (the patched section's bytes) as an indirect storage over B (the base section's bytes): chunks of 0x100 equal to B at the same
// offset come from storage 0 (runs merged), the others from storage 1 (one entry each, so the table has several entry sets)
static void nxn_patch(nxn_sec *s, const uint8_t *B, size_t bn, const uint8_t *T, size_t tn) {
    uint32_t cap = (uint32_t)(tn / 0x100 + 2), ne = 0; uint8_t *ent = calloc(cap, 20); nxw_buf *o = &s->body;
    for (size_t v = 0; v < tn; v += 0x100) {
        size_t c = tn - v < 0x100 ? tn - v : 0x100; int same = v + c <= bn && !memcmp(B + v, T + v, c);
        if (same && ne && nup_u32(ent + 20 * (ne - 1) + 16) == 0) continue;   // the run goes on
        uint8_t *e = ent + 20 * ne++; nxw_w64(e, v);
        if (same) { nxw_w64(e + 8, v); nxw_w32(e + 16, 0); }
        else { nxw_w64(e + 8, o->n); nxw_w32(e + 16, 1); nxw_put(o, T + v, c); }
    }
    nxn_pad(o, 16); uint64_t io = o->n; nxn_bktr(o, ent, ne, 20, tn); uint64_t isz = o->n - io;
    nxw_w64(s->fh + 0x100, io); nxw_w64(s->fh + 0x108, isz); memcpy(s->fh + 0x110, "BKTR", 4); nxw_w32(s->fh + 0x114, 1); nxw_w32(s->fh + 0x118, ne);
    free(ent);
}

typedef struct { uint8_t *b; size_t n; char name[48]; } nxn_file;
// The whole fixture: the three snapshots' root files (nxw_file lists in *S, *U, *A, with their counts), built from the RomFS sources given. opt: 1 a damaged
// FS header in P0 (its SHA-256 in the NCA header does not match), 2 an encrypted RomFS section in P0, 3 a sparse one. The caller frees with nxn_free
typedef struct { nxn_file f[12]; int nf; nxw_file base[3], upd[6], aoc[2]; } nxn_set;
static nxn_file *nxn_add(nxn_set *x, nxw_buf *b, const char *name) { nxn_file *f = &x->f[x->nf++]; f->b = b->b; f->n = b->n; strcpy(f->name, name); return f; }
static void nxn_build(nxn_set *x, uint64_t app, const nxn_src *r0, int n0, const nxn_src *r1, int n1, const nxn_src *u0, int m0, const nxn_src *u1, int m1, const nxn_src *dl, int nd, int opt) {
    memset(x, 0, sizeof *x); char nm[48]; uint8_t cP0[16], cP1[16], cU0[16], cU1[16], cD[16];
    // P0: a plain RomFS (program 0)
    nxw_buf R0 = {0}; nxn_romfs(&R0, r0, n0); nxn_sec s0; memset(&s0, 0, sizeof s0); nxw_put(&s0.body, R0.b, R0.n); nxn_sec_romfs(&s0, R0.n);
    nxn_sec s0h = s0; if (opt == 2) s0h.fh[4] = 2; if (opt == 3) s0h.fh[0x148] = 1;
    nxw_buf P0 = {0}; nxn_program(&P0, "DNCA", app, 0, "MAIN0", &s0h, cP0);
    if (opt == 1) P0.b[0x400 + 0x200 + 0x10] ^= 1;   // FS header 1 changed after its hash was taken
    nxn_name(cP0, ".nca", nm); nxn_file *fP0 = nxn_add(x, &P0, nm);
    // P1: a compressed RomFS (program 1)
    nxw_buf R1 = {0}; nxn_romfs(&R1, r1, n1); nxn_sec s1; memset(&s1, 0, sizeof s1); uint64_t to, tz; uint8_t ch[16];
    nxn_compress(&s1.body, R1.b, R1.n, &to, &tz, ch); nxn_sec_romfs(&s1, s1.body.n);
    nxw_w64(s1.fh + 0x178, to); nxw_w64(s1.fh + 0x180, tz); memcpy(s1.fh + 0x188, ch, 16);
    nxw_buf P1 = {0}; nxn_program(&P1, "NCA3", app + 1, 1, "MAIN0-1", &s1, cP1); nxn_name(cP1, ".nca", nm); nxn_file *fP1 = nxn_add(x, &P1, nm);
    // the base's meta
    nxw_buf BM = {0}; { uint8_t c[2][16]; memcpy(c[0], cP0, 16); memcpy(c[1], cP1, 16); const uint8_t rt[2] = {1, 1}, io[2] = {0, 1}; nxn_meta(&BM, app, 0, 0x80, 2, c, rt, io, nm); }
    nxn_file *fBM = nxn_add(x, &BM, nm);
    // U0: program 0's update RomFS, patched over P0's section (whose body is the RomFS at 0)
    nxw_buf T0 = {0}; nxn_romfs(&T0, u0, m0); nxn_sec t0; memset(&t0, 0, sizeof t0); nxn_sec_romfs(&t0, T0.n);
    nxn_patch(&t0, s0.body.b, s0.body.n, T0.b, T0.n);
    nxw_buf U0 = {0}; nxn_program(&U0, "DNCA", app, 0, "MAIN1", &t0, cU0); nxn_name(cU0, ".nca", nm); nxn_file *fU0 = nxn_add(x, &U0, nm);
    // U1: program 1's update: its level compressed, the section patched over P1's
    nxw_buf R1u = {0}; nxn_romfs(&R1u, u1, m1); nxw_buf L1 = {0}; uint64_t to1, tz1; uint8_t ch1[16]; nxn_compress(&L1, R1u.b, R1u.n, &to1, &tz1, ch1);
    nxn_sec t1; memset(&t1, 0, sizeof t1); nxn_sec_romfs(&t1, L1.n); nxw_w64(t1.fh + 0x178, to1); nxw_w64(t1.fh + 0x180, tz1); memcpy(t1.fh + 0x188, ch1, 16);
    nxn_patch(&t1, s1.body.b, s1.body.n, L1.b, L1.n);
    nxw_buf U1 = {0}; nxn_program(&U1, "NCA3", app + 1, 1, "MAIN1-1", &t1, cU1); nxn_name(cU1, ".nca", nm); nxn_file *fU1 = nxn_add(x, &U1, nm);
    // the patch's meta, with a delta fragment that is not in the snapshot (not needed)
    nxw_buf PM = {0}; { uint8_t c[3][16]; memcpy(c[0], cU0, 16); memcpy(c[1], cU1, 16); memset(c[2], 0xdd, 16); const uint8_t rt[3] = {1, 1, 6}, io[3] = {0, 1, 0};
                        nxn_meta(&PM, app + 0x800, 65536, 0x81, 3, c, rt, io, nm); }
    nxn_file *fPM = nxn_add(x, &PM, nm);
    // the add-on: a data NCA with a plain RomFS, its meta
    nxw_buf RD = {0}; nxn_romfs(&RD, dl, nd); nxn_sec sd; memset(&sd, 0, sizeof sd); nxw_put(&sd.body, RD.b, RD.n); nxn_sec_romfs(&sd, RD.n);
    nxw_buf DN = {0}; nxn_nca(&DN, "NCA3", 4, app + 0x1001, 0, &sd, 1, cD); nxn_name(cD, ".nca", nm); nxn_file *fD = nxn_add(x, &DN, nm);
    nxw_buf AM = {0}; { uint8_t c[1][16]; memcpy(c[0], cD, 16); const uint8_t rt[1] = {2}, io[1] = {0}; nxn_meta(&AM, app + 0x1001, 0, 0x82, 1, c, rt, io, nm); }
    nxn_file *fAM = nxn_add(x, &AM, nm);
    nxn_file *bs[3] = {fBM, fP0, fP1}, *us[6] = {fPM, fU0, fU1, fBM, fP0, fP1}, *as[2] = {fAM, fD};
    for (int i = 0; i < 3; i++) x->base[i] = (nxw_file){bs[i]->name, bs[i]->b, bs[i]->n, 16, 0};
    for (int i = 0; i < 6; i++) x->upd[i] = (nxw_file){us[i]->name, us[i]->b, us[i]->n, 16, (uint8_t)(i == 1)};   // U0 in ZSTD blocks, the rest RAW
    for (int i = 0; i < 2; i++) x->aoc[i] = (nxw_file){as[i]->name, as[i]->b, as[i]->n, 16, 0};
    free(R0.b); free(s0.body.b); free(R1.b); free(s1.body.b); free(T0.b); free(t0.body.b); free(R1u.b); free(L1.b); free(t1.body.b); free(RD.b); free(sd.body.b);
}
static void nxn_free(nxn_set *x) { for (int i = 0; i < x->nf; i++) free(x->f[i].b); }
