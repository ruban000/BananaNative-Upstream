// Save write and save transaction unit test (takes seconds): includes hle_fs.c directly and calls its static implementation; pwrite is replaced by a scriptable fake.
// All expected values are hand-written from the semantics, not computed by calling the implementation under test:
//   (1) pwrite returns 0 (can happen when the disk is full) -> WriteFile returns failure after one call (it used to loop forever here);
//   (2) pwrite writes in pieces (3 bytes first, then the rest) -> success and the file content is complete;
//   (3) transaction: the failure in (1) causes this transaction's Commit to be rejected and the working directory to return to the committed version; the next transaction writes in the working directory, the committed directory is still the old version before Commit and the new version after Commit;
//   (4) DM_TEST_FS_FAIL=write:N:errno makes only the Nth save write fail, with writes before and after proceeding normally; that transaction's Commit is rejected, the committed version is unchanged, and the front end receives one notification;
//      the next transaction commits normally, and the final save = the content of the next transaction;
//   (5) remounting discards uncommitted content;
//   (8) a save folder that cannot clone (ENOTSUP: HFS+, exFAT) mounts read-only and tells the front end; recursive deletes unlink symlinks and never empty their targets.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <sys/clonefile.h>                // declared before the define, so only hle_fs.c's calls go to the fake
static ssize_t fake_pwrite(int fd, const void *buf, size_t n, off_t off);
static int fake_clonefile(const char *src, const char *dst, uint32_t flags);
#define pwrite fake_pwrite
#define clonefile fake_clonefile
#include "../hle_fs.c"
#undef pwrite
#undef clonefile
ssize_t pwrite(int, const void *, size_t, off_t);
static int g_clone_enotsup;                   // 1: clonefile fails as on HFS+ and exFAT (ENOTSUP), checked on disk images of both
static int fake_clonefile(const char *src, const char *dst, uint32_t flags) {
    if (g_clone_enotsup) { errno = ENOTSUP; return -1; }
    return clonefile(src, dst, flags);
}

struct dm_flags g_flag; uint64_t g_t0_ns; uint64_t now_ns(void) { return 0; }   // stubs for log timestamps and the read path (not used by the write path)
void dm_guest_write_prepare(void *a, size_t n) { (void)a; (void)n; }
extern int g_nfail, g_lasterr;                                        // front-end notification count (the strong implementation in t_fs_stub.c replaces the weak one in hle_fs.c)
static int g_mode, g_calls;                   // 0 write normally; 1 always return 0; 2 write only 3 bytes the first time
static ssize_t fake_pwrite(int fd, const void *buf, size_t n, off_t off) {
    g_calls++;
    if (g_mode == 1) { if (g_calls > 1000) { printf("  ✗ pwrite retried over 1000 times after returning 0 (endless loop)\n"); exit(1); } return 0; }
    if (g_mode == 2 && g_calls == 1 && n > 3) n = 3;
    return pwrite(fd, buf, n, off);
}
static int fails, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { fails++; printf("  ✗ "); } else printf("  ✓ "); printf(__VA_ARGS__); printf("\n"); } while (0)
static void put(const char *p, const char *s) { FILE *f = fopen(p, "w"); fputs(s, f); fclose(f); }
static int same(const char *p, const char *s) {
    char b[64] = {0}; FILE *f = fopen(p, "r"); if (!f) return 0; size_t k = fread(b, 1, sizeof b - 1, f); fclose(f);
    return k == strlen(s) && !memcmp(b, s, k);
}
static Result write_save(const char *path, const char *s) {           // open -> set size -> write -> close
    uint64_t h; Result r = h_fs_OpenFile(&h, path, MODE_WRITE); if (r) return r;
    h_fs_SetFileSize(h, (int64_t)strlen(s));
    r = h_fs_WriteFile(h, 0, s, strlen(s), NULL); h_fs_CloseFile(h); return r;
}
static int mnt_find(const char *name) { for (int i = 0; i < g_nmnt; i++) if (!strcmp(g_mnt[i].name, name)) return 1; return 0; }
static int read_fs(const char *path, char *out, size_t n) { uint64_t h; size_t got = 0; if (h_fs_OpenFile(&h, path, MODE_READ)) return 0; Result r = h_fs_ReadFile(&got, h, 0, out, n - 1); h_fs_CloseFile(h); out[got] = 0; return !r; }

// ---------- NUP container (nup_image.h, android/NUP.md) ----------
// A .nup written here from SPEC.md's layout, independently of dm/tools/nup_pack.py: four packages (base, two updates, an add-on), raw, empty and
// zstd files, and one extent shared by two files (dedup)
#include "nup_fixture_zst.h"
static void nup_fixture_text(char *out) { for (int i = 0; i < 3000; i++) out += sprintf(out, "line %05d of the compressed file\n", i); }
enum { NUP_ND = 10, NUP_NF = 8 };
static size_t nup_fixture(uint8_t *o) {   // returns the file's size; o has room for 8 KiB
    static const struct { const char *name; int kids[3], nk; } D[NUP_ND] = {
        {"", {1}, 1}, {"romfs", {2, 10 + 0}, 2}, {"data", {10 + 1, 10 + 2, 10 + 3}, 3},          // base: romfs/{data/{a,e,z}, top.txt}
        {"", {4}, 1}, {"romfs", {5}, 1}, {"data", {10 + 4, 10 + 5}, 2},                          // update 65536: romfs/data/{a,z}
        {"", {7}, 1}, {"romfs", {10 + 6}, 1},                                                     // update 131072: romfs/v2.txt
        {"", {9}, 1}, {"romfs", {10 + 7}, 1}};                                                    // add-on 040074a01bf13001: romfs/d.txt
    static const struct { const char *name, *data; int zstd, share; } F[NUP_NF] = {
        {"top.txt", "base-top"}, {"a", "hello"}, {"e", ""}, {"z", NULL, 1}, {"a", "HELLO"}, {"z", NULL, 1, 3}, {"v2.txt", "two"}, {"d.txt", "dlc"}};
    static const struct { uint64_t tid; uint32_t root, romfs, ver; uint8_t kind; } P[4] = {
        {0x040074A01BF12000ull, 0, 1, 0, 0}, {0x040074A01BF12000ull, 3, 4, 65536, 1}, {0x040074A01BF12000ull, 6, 7, 131072, 1}, {0x040074a01bf13001ull, 8, 9, 0, 2}};
    uint8_t toc[4096] = {0}; char pool[256]; size_t pl = 0, no[NUP_ND + NUP_NF];
    for (int i = 0; i < NUP_ND + NUP_NF; i++) { const char *n = i < NUP_ND ? D[i].name : F[i - NUP_ND].name; no[i] = pl; memcpy(pool + pl, n, strlen(n)); pl += strlen(n); }
    size_t o_pk = 0x30, o_dir = o_pk + 4 * 0x40, o_file = o_dir + NUP_ND * 0x18, o_kids = o_file + NUP_NF * 0x50, nkids = 0;
    for (int i = 0; i < NUP_ND; i++) nkids += D[i].nk;
    size_t o_pool = o_kids + 4 * nkids, toc_size = o_pool + pl, blob = (0x80 + toc_size + 15) & ~(size_t)15, at = blob;
    #define W32(b, off, v) do { uint32_t _v = (uint32_t)(v); memcpy((b) + (off), &_v, 4); } while (0)
    #define W64(b, off, v) do { uint64_t _v = (uint64_t)(v); memcpy((b) + (off), &_v, 8); } while (0)
    memcpy(toc, "TOC\x01", 4); W32(toc, 4, 4); W64(toc, 8, NUP_ND); W64(toc, 0x10, NUP_NF); W64(toc, 0x18, o_pool); W64(toc, 0x20, pl);
    for (int i = 0; i < 4; i++) { uint8_t *p = toc + o_pk + 0x40 * i; W64(p, 0, P[i].tid); W32(p, 8, P[i].root); W32(p, 0xC, NUP_NONE); W32(p, 0x10, P[i].romfs); W32(p, 0x14, P[i].ver); p[0x18] = P[i].kind; }
    size_t slot = 0;
    for (int i = 0; i < NUP_ND; i++) { uint8_t *d = toc + o_dir + 0x18 * i; W32(d, 0, no[i]); W32(d, 4, strlen(D[i].name)); W32(d, 8, slot); W32(d, 12, D[i].nk);
        for (int j = 0; j < D[i].nk; j++) W32(toc, o_kids + 4 * slot++, D[i].kids[j]); }
    uint64_t xo[NUP_NF] = {0};
    for (int i = 0; i < NUP_NF; i++) {
        uint8_t *r = toc + o_file + 0x50 * i; size_t size = F[i].zstd ? 102000 : strlen(F[i].data), stored = F[i].zstd ? nup_zst_len : size;
        if (F[i].share) xo[i] = xo[F[i].share];
        else if (stored) { xo[i] = at; memcpy(o + at, F[i].zstd ? (const char *)nup_zst : F[i].data, stored); at = (at + stored + 15) & ~(size_t)15; }
        W32(r, 0, no[NUP_ND + i]); W32(r, 4, strlen(F[i].name)); W64(r, 8, size); W64(r, 0x10, stored ? xo[i] : 0); W64(r, 0x18, stored); r[0x40] = (uint8_t)F[i].zstd;
    }
    memcpy(toc + o_pool, pool, pl);
    size_t fsize = at;   // the last extent's padding is part of the file here (SPEC: gaps are zero padding)
    uint8_t *s = o; memset(s, 0, 0x80); memcpy(s, "NUP\x01", 4); s[4] = 0x80; s[6] = 1; W64(s, 8, 0x80); W64(s, 0x10, toc_size);
    plat_sha256(toc, toc_size, s + 0x18); W64(s, 0x38, blob); W64(s, 0x40, fsize); W32(s, 0x48, 4);
    memcpy(o + 0x80, toc, toc_size);
    #undef W32
    #undef W64
    return fsize;
}
static void nup_write(const char *p, const uint8_t *b, size_t n) { FILE *f = fopen(p, "wb"); fwrite(b, 1, n, f); fclose(f); }
static void nup_tests(const char *root) {
    printf("NUP container\n");
    static uint8_t b[8192]; static char want[102001], got[102001];
    size_t n = nup_fixture(b); nup_fixture_text(want);
    char np[1100], spec[1200]; snprintf(np, sizeof np, "%s/game.nup", root); nup_write(np, b, n);
    unsetenv("DM_ROMFS_OVERLAY"); unsetenv("DM_NUP_CACHE_MB"); g_nmnt = 0;
    setenv("DM_ROMFS", np, 1);
    CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && g_mnt[0].img, "a .nup with no selector mounts");
    CHECK(read_fs("rom:/v2.txt", got, sizeof got) && !strcmp(got, "two"), "with no selector the RomFS is the newest update's (131072)");
    { uint64_t h; CHECK(h_fs_OpenFile(&h, "rom:/data/a", MODE_READ) == RES_PATH_NOT_FOUND, "the older update's files are not in it"); }
    g_nmnt = 0;
    snprintf(spec, sizeof spec, "%s:update/65536/romfs", np); setenv("DM_ROMFS", spec, 1);
    CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/data/a", got, sizeof got) && !strcmp(got, "HELLO"), "update/65536/romfs selects that update");
    {   uint64_t h; int64_t sz = 0; size_t r1 = 0, r2 = 0, r3 = 0;
        CHECK(h_fs_OpenFile(&h, "rom:/data/z", MODE_READ) == RES_OK && h_fs_GetFileSize(&sz, h) == RES_OK && sz == 102000, "a zstd file opens with its uncompressed size (%lld)", (long long)sz);
        CHECK(h_fs_ReadFile(&r1, h, 0, got, 40000) == RES_OK && h_fs_ReadFile(&r2, h, 40000, got + 40000, 70000) == RES_OK && r1 == 40000 && r2 == 62000 && !memcmp(got, want, 102000),
              "a zstd file reads in pieces, the last one cut at its end (%zu + %zu)", r1, r2);
        CHECK(h_fs_ReadFile(&r3, h, 102000, got, 10) == RES_OK && r3 == 0, "a read at the end gives 0 bytes");
        nup_aux *a = g_mnt[0].img->aux;
        CHECK(a->nz == 1 && a->z[0].refs == 1 && a->held == 102000, "the open holds the decompressed bytes (refs %u, held %llu)", a->z[0].refs, (unsigned long long)a->held);
        h_fs_CloseFile(h);
        CHECK(a->z[0].refs == 0 && a->z[0].buf, "closed, the bytes stay cached within the budget");
    }
    // Forgejo #291, #342: a copy of the .nup with zeros where a file's extent is (a cut or sparse copy) is read as it is: the zstd file does not decode and the
    // red line says its extent is all zeros (the decoder's own error strings are stripped); a raw file reads as zeros
    {   nup_aux *a = g_mnt[0].img->aux; uint64_t zo = a->z[0].off, zs = a->z[0].stored; static uint8_t zb[8192]; char zp[1100];
        memcpy(zb, b, n); CHECK(zo + zs <= n, "the zstd extent is inside the fixture"); memset(zb + zo, 0, (size_t)zs);
        snprintf(zp, sizeof zp, "%s/zeroed.nup", root); nup_write(zp, zb, n);
        g_nmnt = 0; snprintf(spec, sizeof spec, "%s:update/65536/romfs", zp); setenv("DM_ROMFS", spec, 1);
        CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && g_mnt[0].img, "a .nup whose zstd extent is zeros still mounts (the TOC is intact)");
        char why[400] = ""; const uint8_t *r = nup_z_get(g_mnt[0].img, 0, why, sizeof why);
        CHECK(!r && strstr(why, "all zeros") && strstr(why, "begins 00 00 00 00") && !strstr(why, "stripped"), "a zeroed zstd extent fails and says it is all zeros: %s", why);
        uint64_t h; CHECK(h_fs_OpenFile(&h, "rom:/data/z", MODE_READ) != RES_OK, "the game's open of it fails");
    }
    g_nmnt = 0; setenv("DM_NUP_CACHE_MB", "0", 1);
    snprintf(spec, sizeof spec, "%s:base", np); setenv("DM_ROMFS", spec, 1);
    CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/top.txt", got, sizeof got) && !strcmp(got, "base-top"), ":base selects the base");
    CHECK(read_fs("rom:/data/e", got, sizeof got) && !got[0], "an empty file reads as empty");
    CHECK(read_fs("rom:/data/z", got, sizeof got) && !memcmp(got, want, 102000), "the file sharing the zstd extent reads the same bytes");
    { nup_aux *a = g_mnt[0].img->aux; CHECK(!a->z[0].buf && !a->held, "with DM_NUP_CACHE_MB=0 nothing stays cached after close"); }
    { int t = -1; CHECK(h_fs_GetEntryType(&t, "rom:/data") == RES_OK && t == ENT_DIR, "a directory is a directory"); }
    unsetenv("DM_NUP_CACHE_MB");
    // a large compressed file (here: DM_NUP_DISK_MB of 0.05 MiB, the 102,000-byte file is "large") is decompressed into a temporary file and mapped: the same bytes,
    // not in the cache's budget, kept after close
    g_nmnt = 0; setenv("DM_NUP_DISK_MB", "0.05", 1); setenv("TMPDIR", root, 1);
    snprintf(spec, sizeof spec, "%s:update/65536/romfs", np); setenv("DM_ROMFS", spec, 1);
    {   uint64_t h; size_t r1 = 0, r2 = 0; memset(got, 0, sizeof got);
        CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && h_fs_OpenFile(&h, "rom:/data/z", MODE_READ) == RES_OK, "a large zstd file opens");
        CHECK(h_fs_ReadFile(&r1, h, 0, got, 50000) == RES_OK && h_fs_ReadFile(&r2, h, 50000, got + 50000, 60000) == RES_OK && r1 == 50000 && r2 == 52000 && !memcmp(got, want, 102000),
              "a large zstd file reads its bytes from the temporary file (%zu + %zu)", r1, r2);
        nup_aux *a = g_mnt[0].img->aux;
        CHECK(a->z[0].mapped && a->held == 0, "it is mapped from a temporary file, outside the cache's budget (mapped %d, held %llu)", a->z[0].mapped, (unsigned long long)a->held);
        h_fs_CloseFile(h);
        CHECK(a->z[0].buf && a->ndec == 1, "closed, the map stays: no second decompression at the next open");
        char tl[1200]; snprintf(tl, sizeof tl, "%s/nup-", root); DIR *d = opendir(root); int left = 0; struct dirent *de;
        while (d && (de = readdir(d))) if (!strncmp(de->d_name, "nup-", 4)) left++;
        if (d) closedir(d);
        CHECK(!left, "the temporary file is unlinked (%d left in %s)", left, root); (void)tl;
    }
    g_nmnt = 0; unsetenv("DM_NUP_DISK_MB"); unsetenv("TMPDIR");
    {   char aocd[1100], ln[1200]; size_t cache = 0; snprintf(aocd, sizeof aocd, "%s/aocnup", root); mkdir(aocd, 0755);
        snprintf(ln, sizeof ln, "%s/1.nup", aocd); unlink(ln); CHECK(!symlink(np, ln), "link 1.nup to the .nup"); setenv("DM_AOC_DIR", aocd, 1);
        CHECK(h_fs_QueryAocCache(&cache, 1) == RES_OK && cache > 0x30, "the add-on reports a table size (%zu)", cache);
        CHECK(h_fs_MountAoc("aocn", 1, NULL, 0) == RES_OK && read_fs("aocn:/d.txt", got, sizeof got) && !strcmp(got, "dlc"), "add-on 1 mounts from the addon package whose title ID ends in 001");
        CHECK(h_fs_MountAoc("aocm", 2, NULL, 0) == RES_TARGET_NOT_FOUND, "an index with no link is not found");
        // Forgejo #30: a link to a .nup that has no add-on of that index (the launcher linked the game's own .nup for a pack's aoc row), a link to nothing and a cut-off
        // image are not usable (aoc_load leaves them out with a [RED] line), and mounting one anyway answers "target not found" instead of a FATAL
        CHECK(dm_aoc_usable(1), "add-on 1 in the .nup is usable");
        snprintf(ln, sizeof ln, "%s/2.nup", aocd); unlink(ln); CHECK(!symlink(np, ln), "link 2.nup to the .nup, which has no add-on 2");
        CHECK(!dm_aoc_usable(2), "a .nup without that add-on is not usable");
        CHECK(h_fs_MountAoc("aocx", 2, NULL, 0) == RES_TARGET_NOT_FOUND && !mnt_find("aocx"), "mounting it answers target not found, no FATAL, no mount");
        snprintf(ln, sizeof ln, "%s/3.nup", aocd); unlink(ln); CHECK(!symlink("/nonexistent/Game.nup", ln), "link 3.nup to nothing");
        CHECK(!dm_aoc_usable(3) && h_fs_MountAoc("aocy", 3, NULL, 0) == RES_TARGET_NOT_FOUND, "a link to nothing is not usable, and not found");
        snprintf(ln, sizeof ln, "%s/4.img", aocd); { FILE *f = fopen(ln, "wb"); fwrite("DMROMFS1", 1, 8, f); fclose(f); }
        CHECK(!dm_aoc_usable(4) && h_fs_MountAoc("aocz", 4, NULL, 0) == RES_TARGET_NOT_FOUND, "a cut-off image is not usable, and its mount is not found");
        CHECK(h_fs_MountAoc("aocn", 1, NULL, 0) == RES_OK && read_fs("aocn:/d.txt", got, sizeof got) && !strcmp(got, "dlc"), "the readable add-on still reads");
        for (int i = 2; i <= 4; i++) { snprintf(ln, sizeof ln, "%s/%d.%s", aocd, i, i == 4 ? "img" : "nup"); unlink(ln); }
        unsetenv("DM_AOC_DIR");
    }
    // fd:<n>/<name> in place of the path (a .nup picked with Android's file picker: the launcher's descriptor, nup_image.h nup_fd_spec)
    {   int fd = open(np, O_RDONLY); CHECK(fd >= 0, "open the .nup for its descriptor");
        snprintf(spec, sizeof spec, "fd:%d/Game Name.nup:update/65536/romfs", fd); setenv("DM_ROMFS", spec, 1); g_nmnt = 0;
        CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/data/a", got, sizeof got) && !strcmp(got, "HELLO"), "fd:<n>/<name>.nup:update/65536/romfs mounts that update");
        CHECK(read_fs("rom:/data/z", got, sizeof got) && !memcmp(got, want, 102000), "a zstd file reads through the descriptor");
        CHECK(lseek(fd, 0, SEEK_CUR) == 0, "the descriptor's own position is not moved (pread of a duplicate)");
        g_nmnt = 0;
        {   char aocd[1100], ln[1200], tgt[64]; snprintf(aocd, sizeof aocd, "%s/aocfd", root); mkdir(aocd, 0755);
            snprintf(ln, sizeof ln, "%s/1.nup", aocd); unlink(ln); snprintf(tgt, sizeof tgt, "fd:%d/Game Name.nup", fd);
            CHECK(!symlink(tgt, ln), "link 1.nup to fd:<n>/<name>"); setenv("DM_AOC_DIR", aocd, 1);
            CHECK(h_fs_MountAoc("aocf", 1, NULL, 0) == RES_OK && read_fs("aocf:/d.txt", got, sizeof got) && !strcmp(got, "dlc"), "add-on 1 mounts through a link to the descriptor");
            unsetenv("DM_AOC_DIR");
        }
        close(fd);
        snprintf(spec, sizeof spec, "fd:%d/Game Name.nup:base", fd); CHECK(!img_open(spec), "a descriptor that is not open is refused");
        g_nmnt = 0;
    }
    snprintf(spec, sizeof spec, "%s:addon/040074a01bf13001", np); { romfs_img *m = img_open(spec); CHECK(m && m->n == 2, "addon/<title ID> selects the add-on"); romfs_image_close(m); }
    snprintf(spec, sizeof spec, "%s:update/1", np); CHECK(!img_open(spec), "an update that is not there is refused");
    snprintf(spec, sizeof spec, "%s:patch", np); CHECK(!img_open(spec), "an unknown selector is refused");
    {   char bp[1100]; snprintf(bp, sizeof bp, "%s/bad.nup", root);
        uint8_t c[8192]; memcpy(c, b, n); c[0x80 + 0x40] ^= 1; nup_write(bp, c, n); CHECK(!img_open(bp), "a changed TOC byte fails its SHA-256");
        nup_write(bp, b, n - 16); CHECK(!img_open(bp), "a cut-off .nup is refused (file size)");
        memcpy(c, b, n); c[0x4C] = 1; nup_write(bp, c, n); CHECK(!img_open(bp), "unknown superblock flags are refused");
    }
    g_nmnt = 0; unsetenv("DM_ROMFS");
}
// ---------- NX archive (nx_image.h, android/NX.md) ----------
// An .nx written here from the format's SPEC.md (nx_fixture.h): a base with a second program, an update, an add-on; RAW and ZSTD blocks, chunk sizes of 64 and 128 KiB,
// a block shared by two files and by both snapshots, files whose last block is short, an empty file, nested folders
#include "nx_fixture.h"
#include <pthread.h>
static uint32_t nxt_seed;
static uint32_t nxt_rnd(void) { nxt_seed = nxt_seed * 1664525u + 1013904223u; return nxt_seed >> 8; }
static uint8_t *nxt_noise(size_t n, uint32_t seed) { uint8_t *p = malloc(n); nxt_seed = seed; for (size_t i = 0; i < n; i++) p[i] = (uint8_t)(nxt_rnd() >> 15); return p; }   // (the generator's low bits repeat every 64 KiB)
static uint8_t *nxt_runs(size_t n, uint32_t seed) {   // compressible: runs of one byte between short noise
    uint8_t *p = malloc(n); nxt_seed = seed;
    for (size_t i = 0; i < n;) { size_t r = 40 + nxt_rnd() % 3000; uint8_t v = (uint8_t)(nxt_rnd() >> 15); for (size_t k = 0; k < r && i < n; k++) p[i++] = v; for (size_t k = 0; k < 30 && i < n; k++) p[i++] = (uint8_t)(nxt_rnd() >> 15); }
    return p;
}
static int nxt_same_reads(uint64_t h, const uint8_t *want, size_t n, int count) {   // count reads at random offsets and lengths equal the source bytes
    static uint8_t got[400000];
    for (int i = 0; i < count; i++) {
        uint64_t off = nxt_rnd() % (n + 1); size_t len = i % 7 == 0 ? nxt_rnd() % 300000 : nxt_rnd() % 70000, r = 0;
        if (h_fs_ReadFile(&r, h, (int64_t)off, got, len) != RES_OK) return 0;
        size_t exp = off >= n ? 0 : n - off < len ? n - off : len;
        if (r != exp || memcmp(got, want + off, r)) return 0;
    }
    return 1;
}
typedef struct { romfs_img *m; int64_t k; const uint8_t *want; size_t n; int ok; uint32_t seed; } nxt_thread;
static void *nxt_reader(void *p) {
    nxt_thread *t = p; uint8_t *buf = malloc(200000); uint32_t s = t->seed; t->ok = 1;
    for (int i = 0; i < 300 && t->ok; i++) {
        s = s * 1664525u + 1013904223u; uint64_t off = (s >> 8) % t->n; s = s * 1664525u + 1013904223u; size_t len = (s >> 8) % 200000;
        int64_t r = nx_read(t->m, (uint64_t)t->k, off, buf, len); size_t exp = t->n - off < len ? t->n - off : len;
        if (r != (int64_t)exp || memcmp(buf, t->want + off, exp)) t->ok = 0;
    }
    free(buf); return NULL;
}
static uint64_t nxt_block_off(romfs_img *m, const char *path, uint32_t b) {   // the archive offset of a file's block b (for damaging it)
    nx_aux *a = m->aux; int64_t k = img_find(m, path); const nx_file *f = &a->f[m->e[k].a];
    return nup_u64(a->idx + 24ull * a->refs[f->ref + b]);
}
// NCA snapshots (android/NX.md "NCA snapshots"; nx_nca.h): a game of two programs as NXConvert keeps a decrypted NSP, written in C (nx_nca_fixture.h): the base
// (program 0 plain, program 1 compressed: LZ4, zero and stored entries), an update patched over it (BKTR indirect tables of several entry sets; program 1
// patched, then compressed), an add-on (CNMT title ID, no ADDON_TITLE_ID). Every file read through hle_fs.c equals its source; the refusals NX.md lists
#include "nx_nca_fixture.h"
static void nx_nca_tests(const char *root) {
    printf("NX archive: NCA snapshots\n");
    const uint64_t app = 0x0100000000010000ull; char np[1100], spec[1200], got[64];
    uint8_t *raw = nxt_noise(200000, 21), *z = nxt_runs(150000, 22), *zz = malloc(150000), *rr = nxt_runs(120000, 23);
    memcpy(zz, z, 150000); for (size_t i = 0; i < 150000; i += 9973) zz[i] ^= 0x5a;
    memset(rr + 0x8000, 0, 0x8000);   // a run of zeros: a compressed entry of type 1
    const nxn_src r0[] = {{"top.txt", (const uint8_t *)"base-top", 8}, {"data/raw.bin", raw, 200000}, {"data/z.bin", z, 150000}, {"a/b/deep.txt", (const uint8_t *)"deep", 4}};
    const nxn_src r1[] = {{"p1.txt", (const uint8_t *)"program one", 11}, {"data/runs.bin", rr, 120000}};
    const nxn_src u0[] = {{"aa.txt", (const uint8_t *)"shifts the rest", 15}, {"top.txt", (const uint8_t *)"update-top", 10}, {"data/raw.bin", raw, 200000}, {"data/z.bin", zz, 150000}, {"v1.txt", (const uint8_t *)"one", 3}};
    const nxn_src u1[] = {{"p1.txt", (const uint8_t *)"program one v1", 14}, {"data/runs.bin", rr, 120000}};
    const nxn_src dl[] = {{"d.txt", (const uint8_t *)"dlc", 3}};
    const nxw_prog P[] = {{app, app, 0}};
    snprintf(np, sizeof np, "%s/nca2.nx", root);
    for (int opt = 0; opt < 4; opt++) {
        nxn_set x; nxn_build(&x, app, r0, 4, r1, 2, u0, 5, u1, 2, dl, 1, opt);
        const nxw_snap S[] = {{app, 0, 0, 0, 0, x.base, 3}, {app, 0, 1, 65536, 0, x.upd, 6}, {app, -1, 2, 0, 0, x.aoc, 2}};
        uint8_t *b = NULL; size_t n = nx_fixture_write(S, 3, P, 1, 0, &b); nup_write(np, b, n); free(b); nxn_free(&x);
        if (opt) {
            const char *want = opt == 1 ? "SHA-256" : opt == 2 ? "encrypted" : "sparse"; char why[400] = "";
            snprintf(spec, sizeof spec, "%s:base", np); romfs_img *m = nup_image_open(spec, why, sizeof why);
            CHECK(!m && strstr(why, want), "%s is refused: %s", opt == 1 ? "a damaged FS header" : opt == 2 ? "an encrypted section" : "a sparse section", why); romfs_image_close(m);
            continue;
        }
        struct { const char *sel; const nxn_src *src; int n; } c[] = {{"", u0, 5}, {":base/romfs", r0, 4}, {":program1/base", r1, 2}, {":program1/update/65536/romfs", u1, 2}};
        nxt_seed = 5;
        for (size_t i = 0; i < sizeof c / sizeof *c; i++) {
            g_nmnt = 0; snprintf(spec, sizeof spec, "%s%s", np, c[i].sel); setenv("DM_ROMFS", spec, 1); int ok = h_fs_MountRom("rom", NULL, 0) == RES_OK;
            for (int f = 0; ok && f < c[i].n; f++) {
                char p[200]; uint64_t h; int64_t sz = -1; snprintf(p, sizeof p, "rom:/%s", c[i].src[f].path);
                ok = h_fs_OpenFile(&h, p, MODE_READ) == RES_OK && h_fs_GetFileSize(&sz, h) == RES_OK && sz == (int64_t)c[i].src[f].size && nxt_same_reads(h, c[i].src[f].data, c[i].src[f].size, c[i].src[f].size > 1000 ? 200 : 3);
                if (ok) h_fs_CloseFile(h); else printf("    %s differs\n", p);
            }
            CHECK(ok, "\"%s\": every file reads as its source, at random offsets (%s)", c[i].sel[0] ? c[i].sel : "(no selector)", i == 0 ? "patched" : i == 1 ? "plain" : i == 2 ? "compressed" : "patched, then compressed");
        }
        {   char aocd[1100], ln[1200]; snprintf(aocd, sizeof aocd, "%s/aocnca", root); mkdir(aocd, 0755); snprintf(ln, sizeof ln, "%s/1.nx", aocd); unlink(ln);
            CHECK(!symlink(np, ln), "link 1.nx to the .nx"); setenv("DM_AOC_DIR", aocd, 1);
            CHECK(dm_aoc_usable(1) && h_fs_MountAoc("aocnca", 1, NULL, 0) == RES_OK && read_fs("aocnca:/d.txt", got, sizeof got) && !strcmp(got, "dlc"), "DM_AOC_DIR/1.nx: the add-on found by its CNMT's title ID");
            unlink(ln); unsetenv("DM_AOC_DIR");
        }
        {   char why[400] = ""; snprintf(spec, sizeof spec, "%s:program2/base", np); romfs_img *m = nup_image_open(spec, why, sizeof why);
            CHECK(!m && strstr(why, "no program 2"), "program 2, which the CNMT lacks, is refused: %s", why); romfs_image_close(m); }
        // a damaged block of U0 (stored in ZSTD blocks): the mount works (blocks are checked when read), a read through the patch fails with a [RED] line
        {   snprintf(spec, sizeof spec, "%s:update/65536", np); romfs_img *m = img_open(spec); nx_aux *a = m->aux; uint64_t bo = 0;
            for (uint32_t i = 0; i < a->nst; i++) if (a->st[i]->kind == NXS_FILE && a->f[a->st[i]->file].nblk > 2) {   // U0: the NCA the patch reads storage 1 from
                const nx_file *f = &a->f[a->st[i]->file]; const uint8_t *bk = a->idx + 24ull * a->refs[f->ref + 1]; if (bk[16]) { bo = nup_u64(bk); break; } }
            romfs_image_close(m);
            uint8_t *bb; FILE *ff = fopen(np, "rb"); fseek(ff, 0, SEEK_END); long fl = ftell(ff); fseek(ff, 0, SEEK_SET); bb = malloc(fl); fread(bb, 1, fl, ff); fclose(ff);
            CHECK(bo > 8192, "found a ZSTD block of the update's NCA");
            bb[bo + 100] ^= 0x40; char bp[1100]; snprintf(bp, sizeof bp, "%s/nca2bad.nx", root); nup_write(bp, bb, fl); free(bb);
            g_nmnt = 0; snprintf(spec, sizeof spec, "%s:update/65536", bp); setenv("DM_ROMFS", spec, 1);
            int bad = 0; static uint8_t buf[200000];
            if (h_fs_MountRom("rom", NULL, 0) == RES_OK) for (int f = 0; f < 5; f++) { char p[200]; uint64_t h; size_t r = 0; snprintf(p, sizeof p, "rom:/%s", u0[f].path);
                if (h_fs_OpenFile(&h, p, MODE_READ) == RES_OK) { bad += h_fs_ReadFile(&r, h, 0, buf, sizeof buf) != RES_OK; h_fs_CloseFile(h); } }
            CHECK(bad > 0, "a damaged block of the update's NCA fails the reads that pass through it (%d files)", bad);
        }
    }
    free(raw); free(z); free(zz); free(rr); g_nmnt = 0; unsetenv("DM_ROMFS");
}
// A Switch 2 Edition as NXConvert writes it (android/NX.md "Programs"): program 0 (ID = the application's) with a base and an update, program 1
// (040015100B514001, marked primary) with only an update of its own, and an add-on of the application
static void nx_s2e_tests(const char *root) {
    printf("NX archive: two programs (a Switch 2 Edition)\n");
    const uint64_t app = 0x010015100B514000ull, p1 = 0x040015100B514001ull;
    const nxw_file b0[] = {{"romfs/top.txt", (const uint8_t *)"p0-base", 7, 16, 0}}, u0[] = {{"romfs/top.txt", (const uint8_t *)"p0-update", 9, 16, 0}},
                   u1[] = {{"exefs/main", (const uint8_t *)"MAIN1", 5, 16, 0}, {"romfs/top.txt", (const uint8_t *)"p1-update", 9, 16, 0}},
                   dl[] = {{"romfs/d.txt", (const uint8_t *)"dlc", 3, 16, 0}};
    const nxw_prog P[] = {{app, app, 1}, {p1, app, 0}};
    const nxw_snap S[] = {{app, 0, 0, 0, 0, b0, 1}, {app, 0, 1, 327680, 0, u0, 1}, {app, 1, 1, 327680, 0, u1, 2}, {app, -1, 2, 0, 0x010015100B515001ull, dl, 1}};
    uint8_t *b = NULL; size_t n = nx_fixture_write(S, 4, P, 2, 0, &b); char np[1100], spec[1200], got[64];
    snprintf(np, sizeof np, "%s/s2e.nx", root); nup_write(np, b, n); free(b);
    struct { const char *sel, *want; } ok[] = {{"", "p0-update"}, {":base", "p0-base"}, {":update/327680/romfs", "p0-update"}, {":program0/update/327680/romfs", "p0-update"},
        {":program0/base", "p0-base"}, {":program1/update/327680/romfs", "p1-update"}, {":program1/update/327680", "p1-update"}};
    for (size_t i = 0; i < sizeof ok / sizeof *ok; i++) {
        g_nmnt = 0; snprintf(spec, sizeof spec, "%s%s", np, ok[i].sel); setenv("DM_ROMFS", spec, 1);
        CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/top.txt", got, sizeof got) && !strcmp(got, ok[i].want), "\"%s\" is %s", ok[i].sel[0] ? ok[i].sel : "(no selector)", ok[i].want);
    }
    const char *bad[] = {":program1/base", ":program2/update/327680", ":program1", ":program1/", ":program16/base", ":program01/base"};
    for (size_t i = 0; i < sizeof bad / sizeof *bad; i++) { snprintf(spec, sizeof spec, "%s%s", np, bad[i]); char why[400] = ""; romfs_img *m = nup_image_open(spec, why, sizeof why); CHECK(!m, "\"%s\" is refused: %s", bad[i], why); romfs_image_close(m); }
    const char *dlc[] = {":addon/010015100b515001", ":aoc/1", ":program1/addon/010015100b515001/romfs", ":program1/aoc/1", ":program0/aoc/1"};
    for (size_t i = 0; i < sizeof dlc / sizeof *dlc; i++) { snprintf(spec, sizeof spec, "%s%s", np, dlc[i]); romfs_img *m = img_open(spec); CHECK(m && m->n == 2, "\"%s\" is the application's add-on", dlc[i]); romfs_image_close(m); }
    // The run's shape: DM_ROMFS = program 1's own RomFS, DM_ROMFS_PROGRAM_0 = program 0's, the add-on by index
    {   char aocd[1100], ln[1200]; snprintf(aocd, sizeof aocd, "%s/aocs2e", root); mkdir(aocd, 0755); snprintf(ln, sizeof ln, "%s/1.nx", aocd); unlink(ln);
        CHECK(!symlink(np, ln), "link 1.nx to the .nx");
        g_nmnt = 0; snprintf(spec, sizeof spec, "%s:program1/update/327680/romfs", np); setenv("DM_ROMFS", spec, 1);
        snprintf(spec, sizeof spec, "%s:update/327680/romfs", np); setenv("DM_ROMFS_PROGRAM_0", spec, 1); setenv("DM_AOC_DIR", aocd, 1);
        CHECK(h_fs_MountRom("content", NULL, 0) == RES_OK && read_fs("content:/top.txt", got, sizeof got) && !strcmp(got, "p1-update"), "DM_ROMFS=<f>.nx:program1/update/327680/romfs: program 1's RomFS");
        CHECK(h_fs_MountRomIdx("conorig", 0, NULL, 0) == RES_OK && read_fs("conorig:/top.txt", got, sizeof got) && !strcmp(got, "p0-update"), "DM_ROMFS_PROGRAM_0=<f>.nx:update/327680/romfs: program 0's");
        CHECK(dm_aoc_usable(1) && h_fs_MountAoc("aocs", 1, NULL, 0) == RES_OK && read_fs("aocs:/d.txt", got, sizeof got) && !strcmp(got, "dlc"), "DM_AOC_DIR/1.nx: the add-on");
        unlink(ln); unsetenv("DM_AOC_DIR"); unsetenv("DM_ROMFS_PROGRAM_0");
    }
    {   // program 0 is the lowest index, not the program marked primary: here no program has the application's ID
        const nxw_prog P2[] = {{0x010015100B514800ull, app, 1}, {p1, app, 0}};
        uint8_t *b2 = NULL; size_t n2 = nx_fixture_write(S, 4, P2, 2, 0, &b2); nup_write(np, b2, n2); free(b2);
        g_nmnt = 0; setenv("DM_ROMFS", np, 1);
        CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/top.txt", got, sizeof got) && !strcmp(got, "p0-update"), "with no program of the application's ID, program 0 is the lowest index (not the primary one)");
    }
    g_nmnt = 0; unsetenv("DM_ROMFS");
}
static void nx_tests(const char *root) {
    printf("NX archive\n");
    CHECK(nx_crc32c("123456789", 9) == 0xe3069283u, "CRC-32C gives the standard check value (%08x)", nx_crc32c("123456789", 9));
    {   static uint8_t big[100000]; for (size_t i = 0; i < sizeof big; i++) big[i] = (uint8_t)(i * 7 + (i >> 9));
        uint32_t c = ~0u; for (size_t i = 0; i < sizeof big; i++) { c ^= big[i]; for (int k = 0; k < 8; k++) c = c & 1 ? (c >> 1) ^ 0x82f63b78u : c >> 1; }
        CHECK(nx_crc32c(big + 3, sizeof big - 3) != 0 && nx_crc32c(big, sizeof big) == ~c, "CRC-32C over 100,000 bytes equals the bitwise definition");
    }
    uint8_t *raw = nxt_noise(200000, 1), *z = nxt_runs(300000, 2), *big = nxt_runs(150000, 3), *copy = malloc(66536);
    memcpy(copy, raw, 65536); { uint8_t *t = nxt_noise(1000, 4); memcpy(copy + 65536, t, 1000); free(t); }
    const uint64_t app = 0x040074A01BF12000ull;
    const nxw_file base[] = {
        {"exefs/main", (const uint8_t *)"MAIN0", 5, 16, 0}, {"program1/romfs/p1.txt", (const uint8_t *)"program one", 11, 16, 0},
        {"romfs/a/b/c/deep.txt", (const uint8_t *)"deep", 4, 16, 0}, {"romfs/data/big.bin", big, 150000, 17, 1}, {"romfs/data/copy.bin", copy, 66536, 16, 0},
        {"romfs/data/e", (const uint8_t *)"", 0, 16, 0}, {"romfs/data/raw.bin", raw, 200000, 16, 0}, {"romfs/data/z.bin", z, 300000, 16, 1},
        {"romfs/top.txt", (const uint8_t *)"base-top", 8, 16, 0}};
    const nxw_file upd[] = {{"exefs/main", (const uint8_t *)"MAIN1", 5, 16, 0}, {"romfs/data/raw.bin", raw, 200000, 16, 0},
                            {"romfs/top.txt", (const uint8_t *)"update-top", 10, 16, 0}, {"romfs/v1.txt", (const uint8_t *)"one", 3, 16, 0}};
    const nxw_file dlc[] = {{"romfs/d.txt", (const uint8_t *)"dlc", 3, 16, 0}};
    const nxw_prog P[] = {{app, app, 0}};
    const nxw_snap S[] = {{app, 0, 0, 0, 0, base, 9}, {app, 0, 1, 65536, 0, upd, 4}, {app, -1, 2, 0, 0x040074a01bf13001ull, dlc, 1}};
    uint8_t *b = NULL; size_t n = nx_fixture_write(S, 3, P, 1, 0, &b);
    char np[1100], spec[1200], got[400]; snprintf(np, sizeof np, "%s/game.nx", root); nup_write(np, b, n);
    unsetenv("DM_ROMFS_OVERLAY"); unsetenv("DM_NUP_CACHE_MB"); g_nmnt = 0;
    CHECK(nup_is_spec(np) && nup_is_spec("x.nx:base/romfs") && nup_is_spec("fd:3/G.nx:update/1/program1/romfs") && !nup_is_spec("x.nxa") && !nup_is_spec("x.NX"), "an .nx path is a package spec");
    {   char f[64], s[64]; CHECK(nup_split("a.nup.nx:base/program1/romfs", f, sizeof f, s, sizeof s) && !strcmp(f, "a.nup.nx") && !strcmp(s, "base/program1"), "split at the last \".nx:\", /romfs dropped (%s | %s)", f, s); }
    setenv("DM_ROMFS", np, 1);
    CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && g_mnt[0].img && nx_image_is(g_mnt[0].img), "an .nx with no selector mounts");
    CHECK(read_fs("rom:/v1.txt", got, sizeof got) && !strcmp(got, "one") && read_fs("rom:/top.txt", got, sizeof got) && !strcmp(got, "update-top"), "with no selector the RomFS is the newest update's");
    { uint64_t h; CHECK(h_fs_OpenFile(&h, "rom:/data/z.bin", MODE_READ) == RES_PATH_NOT_FOUND, "the base's files are not in the update's tree (no layering)"); }
    g_nmnt = 0; snprintf(spec, sizeof spec, "%s:base/romfs", np); setenv("DM_ROMFS", spec, 1);
    CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/top.txt", got, sizeof got) && !strcmp(got, "base-top"), ":base/romfs selects the base");
    CHECK(read_fs("rom:/a/b/c/deep.txt", got, sizeof got) && !strcmp(got, "deep") && read_fs("rom:/data/e", got, sizeof got) && !got[0], "a file in nested folders and an empty file read");
    {   uint64_t dh; int64_t cnt = 0; static DirectoryEntry de[8]; char names[200] = "";
        CHECK(h_fs_OpenDirectory(&dh, "rom:/data", 3) == RES_OK && h_fs_ReadDirectory(&cnt, de, dh, 8) == RES_OK, "rom:/data lists");
        for (int64_t i = 0; i < cnt; i++) snprintf(names + strlen(names), sizeof names - strlen(names), "%s%s:%lld", i ? " " : "", de[i].name, (long long)de[i].size);
        CHECK(cnt == 5 && !strcmp(names, "big.bin:150000 copy.bin:66536 e:0 raw.bin:200000 z.bin:300000"), "its listing is sorted, with sizes: %s", names);
        h_fs_CloseDirectory(dh);
        int t = -1; CHECK(h_fs_GetEntryType(&t, "rom:/a/b") == RES_OK && t == ENT_DIR && h_fs_GetEntryType(&t, "rom:/a/b/c/deep.txt") == RES_OK && t == ENT_FILE, "entry types");
    }
    {   const char *paths[4] = {"rom:/data/raw.bin", "rom:/data/z.bin", "rom:/data/big.bin", "rom:/data/copy.bin"}; const uint8_t *src[4] = {raw, z, big, copy}; size_t len[4] = {200000, 300000, 150000, 66536};
        nxt_seed = 99;
        for (int i = 0; i < 4; i++) {
            uint64_t h; int64_t sz = 0;
            CHECK(h_fs_OpenFile(&h, paths[i], MODE_READ) == RES_OK && h_fs_GetFileSize(&sz, h) == RES_OK && sz == (int64_t)len[i], "%s opens with its size (%lld)", paths[i], (long long)sz);
            CHECK(nxt_same_reads(h, src[i], len[i], 300), "%s: 300 reads at random offsets equal the source bytes", paths[i]);
            static uint8_t all[300001]; size_t r = 0;
            CHECK(h_fs_ReadFile(&r, h, 0, all, sizeof all) == RES_OK && r == len[i] && !memcmp(all, src[i], len[i]), "%s reads whole in one call, cut at its end", paths[i]);
            h_fs_CloseFile(h);
        }
        nx_aux *a = g_mnt[0].img->aux;
        CHECK(a->held > 0 && a->held <= a->budget && a->ndec > 0, "decoded ZSTD blocks stay cached within the budget (held %llu, %llu decodes)", (unsigned long long)a->held, (unsigned long long)a->ndec);
        int64_t kr = img_find(g_mnt[0].img, "/data/raw.bin"), kc = img_find(g_mnt[0].img, "/data/copy.bin");
        CHECK(a->refs[a->f[g_mnt[0].img->e[kr].a].ref] == a->refs[a->f[g_mnt[0].img->e[kc].a].ref], "raw.bin and copy.bin share their first block");
    }
    {   // threads reading the same blocks at once, with a cache too small to hold them: no block decoded twice at once, every byte right
        g_nmnt = 0; setenv("DM_NUP_CACHE_MB", "1", 1); CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK, "remount with a 1 MiB cache");
        romfs_img *m = g_mnt[0].img; nxt_thread t[6]; pthread_t th[6];
        for (int i = 0; i < 6; i++) { t[i].m = m; t[i].k = img_find(m, i & 1 ? "/data/raw.bin" : "/data/z.bin"); t[i].want = i & 1 ? raw : z; t[i].n = i & 1 ? 200000 : 300000; t[i].seed = 7u + (uint32_t)i; pthread_create(&th[i], NULL, nxt_reader, &t[i]); }
        int ok = 1; for (int i = 0; i < 6; i++) { pthread_join(th[i], NULL); ok &= t[i].ok; }
        nx_aux *a = m->aux; CHECK(ok && a->held <= a->budget, "6 threads x 300 random reads are right, the cache stays within 1 MiB (held %llu)", (unsigned long long)a->held);
        g_nmnt = 0; setenv("DM_NUP_CACHE_MB", "0", 1); CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/top.txt", got, sizeof got), "remount with no cache");
        uint64_t h; size_t r = 0; static uint8_t zb[300000];
        CHECK(h_fs_OpenFile(&h, "rom:/data/z.bin", MODE_READ) == RES_OK && h_fs_ReadFile(&r, h, 0, zb, sizeof zb) == RES_OK && r == 300000 && !memcmp(zb, z, r), "with DM_NUP_CACHE_MB=0 ZSTD blocks still read");
        h_fs_CloseFile(h); CHECK(!((nx_aux *)g_mnt[0].img->aux)->held, "and nothing stays cached");
        unsetenv("DM_NUP_CACHE_MB");
    }
    // Selectors
    g_nmnt = 0; snprintf(spec, sizeof spec, "%s:update/65536/romfs", np); setenv("DM_ROMFS", spec, 1);
    CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/top.txt", got, sizeof got) && !strcmp(got, "update-top"), "update/65536/romfs selects the update");
    snprintf(spec, sizeof spec, "%s:base/program1/romfs", np); setenv("DM_ROMFS_PROGRAM_1", spec, 1);
    CHECK(h_fs_MountRomIdx("conorig", 1, NULL, 0) == RES_OK && read_fs("conorig:/p1.txt", got, sizeof got) && !strcmp(got, "program one"), "DM_ROMFS_PROGRAM_1=<file>.nx:base/program1/romfs mounts the second program's RomFS");
    unsetenv("DM_ROMFS_PROGRAM_1");
    snprintf(spec, sizeof spec, "%s:update/65536/program1/romfs", np); CHECK(!img_open(spec), "a program folder the snapshot lacks is refused");
    snprintf(spec, sizeof spec, "%s:addon/040074a01bf13001/romfs", np); { romfs_img *m = img_open(spec); CHECK(m && m->n == 2, "addon/<title ID> selects the add-on"); romfs_image_close(m); }
    snprintf(spec, sizeof spec, "%s:update/1", np); CHECK(!img_open(spec), "an update that is not there is refused");
    snprintf(spec, sizeof spec, "%s:patch", np); CHECK(!img_open(spec), "an unknown selector is refused");
    {   char aocd[1100], ln[1200]; size_t cache = 0; snprintf(aocd, sizeof aocd, "%s/aocnx", root); mkdir(aocd, 0755);
        snprintf(ln, sizeof ln, "%s/1.nx", aocd); unlink(ln); CHECK(!symlink(np, ln), "link 1.nx to the .nx"); setenv("DM_AOC_DIR", aocd, 1);
        CHECK(dm_aoc_usable(1) && h_fs_QueryAocCache(&cache, 1) == RES_OK && cache > 0x30, "add-on 1 is usable and reports a table size (%zu)", cache);
        CHECK(h_fs_MountAoc("aocx1", 1, NULL, 0) == RES_OK && read_fs("aocx1:/d.txt", got, sizeof got) && !strcmp(got, "dlc"), "add-on 1 mounts from the DLC snapshot whose title ID ends in 001");
        snprintf(ln, sizeof ln, "%s/2.nx", aocd); unlink(ln); CHECK(!symlink(np, ln), "link 2.nx to the .nx, which has no add-on 2");
        CHECK(!dm_aoc_usable(2) && h_fs_MountAoc("aocx2", 2, NULL, 0) == RES_TARGET_NOT_FOUND, "a .nx without that add-on is not usable, and its mount is not found");
        unlink(ln); unsetenv("DM_AOC_DIR");
    }
    {   int fd = open(np, O_RDONLY); g_nmnt = 0;
        snprintf(spec, sizeof spec, "fd:%d/Game Name.nx:update/65536/romfs", fd); setenv("DM_ROMFS", spec, 1);
        CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && read_fs("rom:/top.txt", got, sizeof got) && !strcmp(got, "update-top"), "fd:<n>/<name>.nx:update/65536/romfs mounts that update");
        CHECK(lseek(fd, 0, SEEK_CUR) == 0, "the descriptor's own position is not moved");
        close(fd); g_nmnt = 0;
    }
    // Refusals and damage
    {   char bp[1100]; snprintf(bp, sizeof bp, "%s/bad.nx", root); uint8_t *c = malloc(n); uint64_t cat_off = nup_u64(b + 24);
        memcpy(c, b, n); c[20] ^= 1; c[4096 + 20] ^= 1; nup_write(bp, c, n); CHECK(!img_open(bp), "a bad header CRC in both slots is refused");
        memcpy(c, b, n); c[20] ^= 1; nup_write(bp, c, n); { romfs_img *m = img_open(bp); CHECK(m != NULL, "a damaged slot A with a valid slot B opens"); romfs_image_close(m); }
        memcpy(c, b, n); c[cat_off + 130] ^= 1; nup_write(bp, c, n); CHECK(!img_open(bp), "a changed Catalog byte fails its SHA-256");
        memcpy(c, b, n); c[4096 + 216] = 1; c[4096 + 120] ^= 1; nx_fixture_recrc(c + 4096); nup_write(bp, c, n);
        { romfs_img *m = img_open(bp); CHECK(m && ((nx_aux *)m->aux)->generation == 0, "a newer generation whose Catalog is damaged falls back to the older valid one"); romfs_image_close(m); }
        memcpy(c, b, n); c[6] = 4; c[4096 + 6] = 4; nx_fixture_recrc(c); nx_fixture_recrc(c + 4096); nup_write(bp, c, n); CHECK(!img_open(bp), "header version (0,4) is refused");
        nup_write(bp, b, n - 100); CHECK(!img_open(bp), "a cut-off .nx is refused");
        // a damaged RAW block (raw.bin's second) and a damaged ZSTD block (z.bin's third): those reads fail, the rest still read
        snprintf(spec, sizeof spec, "%s:base", np); romfs_img *m = img_open(spec);
        uint64_t ro = nxt_block_off(m, "/data/raw.bin", 1), zo = nxt_block_off(m, "/data/z.bin", 2); romfs_image_close(m);
        memcpy(c, b, n); c[ro + 5000] ^= 0x40; c[zo + 200] ^= 0x40; nup_write(bp, c, n);
        g_nmnt = 0; snprintf(spec, sizeof spec, "%s:base", bp); setenv("DM_ROMFS", spec, 1);
        CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK, "an .nx with damaged block payloads still mounts (blocks are checked when read)");
        uint64_t h; size_t r = 0; static uint8_t buf[70000];
        CHECK(h_fs_OpenFile(&h, "rom:/data/raw.bin", MODE_READ) == RES_OK && h_fs_ReadFile(&r, h, 0, buf, 60000) == RES_OK && r == 60000 && !memcmp(buf, raw, 60000), "the undamaged block reads");
        CHECK(h_fs_ReadFile(&r, h, 65536 + 4000, buf, 3000) == RES_FAIL, "a read of the damaged RAW slice fails");
        CHECK(h_fs_ReadFile(&r, h, 65536 + 16384 + 10, buf, 3000) == RES_OK && !memcmp(buf, raw + 65536 + 16384 + 10, 3000), "another slice of that block reads (RAW reads check only their slices)");
        h_fs_CloseFile(h);
        CHECK(h_fs_OpenFile(&h, "rom:/data/z.bin", MODE_READ) == RES_OK && h_fs_ReadFile(&r, h, 2 * 65536 + 100, buf, 10) == RES_FAIL, "a read in the damaged ZSTD block fails");
        CHECK(h_fs_ReadFile(&r, h, 65536, buf, 65536) == RES_OK && !memcmp(buf, z + 65536, 65536), "the ZSTD block before it reads");
        h_fs_CloseFile(h);
        free(c);
    }
    {   // a snapshot of NCA files that are not NCAs (zeros), a collection of two games, an unknown required Catalog extension
        static uint8_t nca[1000]; char p2[1100]; uint8_t *b2 = NULL; size_t n2;
        const nxw_file ncas[] = {{"0123456789abcdef0123456789abcdef.nca", nca, 1000, 16, 0}, {"fedcba9876543210fedcba9876543210.cnmt.nca", nca, 500, 16, 0}};
        const nxw_snap S2[] = {{app, 0, 0, 0, 0, base, 9}, {app, 0, 1, 131072, 0, ncas, 2}};
        n2 = nx_fixture_write(S2, 2, P, 1, 0, &b2); snprintf(p2, sizeof p2, "%s/nca.nx", root); nup_write(p2, b2, n2); free(b2);
        char why[400] = ""; romfs_img *m = nup_image_open(p2, why, sizeof why);
        CHECK(!m && strstr(why, "NCA header"), "a snapshot whose NCAs are not NCAs is refused, and says so: %s", why);
        snprintf(spec, sizeof spec, "%s:base", p2); m = img_open(spec); CHECK(m != NULL, "the base beside it still opens"); romfs_image_close(m);
        const nxw_prog P3[] = {{app, app, 0}, {0x0100000000010000ull, 0x0100000000010000ull, 0}};
        const nxw_snap S3[] = {{app, 0, 0, 0, 0, base, 9}, {0x0100000000010000ull, 1, 0, 0, 0, dlc, 1}};
        n2 = nx_fixture_write(S3, 2, P3, 2, 0, &b2); nup_write(p2, b2, n2); free(b2);
        why[0] = 0; m = nup_image_open(p2, why, sizeof why); CHECK(!m && strstr(why, "2 games"), "an archive of two games is refused: %s", why);
        n2 = nx_fixture_write(S, 3, P, 1, 0x00020001, &b2); nup_write(p2, b2, n2); free(b2);
        why[0] = 0; m = nup_image_open(p2, why, sizeof why); CHECK(!m && strstr(why, "required Catalog extension"), "an unknown required Catalog extension is refused: %s", why);
    }
    free(raw); free(z); free(big); free(copy); free(b);
    g_nmnt = 0; unsetenv("DM_ROMFS");
    nx_s2e_tests(root);
    nx_nca_tests(root);
}
// A stand-in for the SDK's own mount table: one RAM disk, and what the host asks of it (Unmount of an SDK-owned mount, GitHub issue #1012)
extern void *(*t_guest_sym)(const char *);
static int g_sdk_cache_mounted, g_sdk_asked, g_sdk_unmounts, g_sdk_no_is_mounted;
static int sdk_is_mounted(const char *name) { g_sdk_asked++; return g_sdk_cache_mounted && !strcmp(name, "cache"); }
static void sdk_unmount(const char *name) { g_sdk_unmounts++; if (!g_sdk_cache_mounted || strcmp(name, "cache")) abort(); g_sdk_cache_mounted = 0; }   // as the SDK's: not mounted aborts
static void *sdk_sym(const char *name) {
    if (!strcmp(name, "_ZN2nn2fs9IsMountedEPKc")) return g_sdk_no_is_mounted ? NULL : (void *)sdk_is_mounted;
    if (!strcmp(name, "_ZN2nn2fs7UnmountEPKc")) return (void *)sdk_unmount;
    return NULL;
}

int main(void) {
    char root[1024], save[1100], work[1200], p[1200];
    const char *sp = getenv("TMPDIR"); snprintf(root, sizeof root, "%s/t_fs.XXXXXX", sp ? sp : "/private/tmp");
    if (!mkdtemp(root)) { perror("mkdtemp"); return 1; }
    char rom[1100], overlay[1100], rdata[1200], odata[1200], fn[1300], got[64];
    snprintf(rom, sizeof rom, "%s/rom", root); snprintf(overlay, sizeof overlay, "%s/overlay", root); snprintf(rdata, sizeof rdata, "%s/data", rom); snprintf(odata, sizeof odata, "%s/data", overlay);
    mkdir(rom, 0755); mkdir(overlay, 0755); mkdir(rdata, 0755); mkdir(odata, 0755);
    snprintf(fn, sizeof fn, "%s/same", rdata); put(fn, "base"); snprintf(fn, sizeof fn, "%s/same", odata); put(fn, "overlay"); snprintf(fn, sizeof fn, "%s/base", rdata); put(fn, "only-base"); snprintf(fn, sizeof fn, "%s/over", odata); put(fn, "only-overlay");
    {   // *.zs game files for the zstd head check below (written before the mount, which lists the folders once)
        FILE *f; snprintf(fn, sizeof fn, "%s/good.zs", rdata); f = fopen(fn, "wb"); fwrite("\x28\xb5\x2f\xfd" "rest", 1, 8, f); fclose(f);
        snprintf(fn, sizeof fn, "%s/skip.zs", rdata); f = fopen(fn, "wb"); fwrite("\x53\x2a\x4d\x18" "rest", 1, 8, f); fclose(f);
        snprintf(fn, sizeof fn, "%s/zero.zs", rdata); f = fopen(fn, "wb"); fwrite("\0\0\0\0\0\0\0\0", 1, 8, f); fclose(f);
        snprintf(fn, sizeof fn, "%s/sub.zs", rdata); f = fopen(fn, "wb"); fwrite("\0\0\0\0\0\0\0\0", 1, 8, f); fclose(f);
        snprintf(fn, sizeof fn, "%s/tiny.zs", rdata); put(fn, "ab");
    }
    setenv("DM_ROMFS", rom, 1); setenv("DM_ROMFS_OVERLAY", overlay, 1); CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK, "mount RomFS with two layers (base + overlay)");
    CHECK(read_fs("rom:/data/same", got, sizeof got) && !strcmp(got, "overlay"), "the overlay file wins"); CHECK(read_fs("rom:/data/base", got, sizeof got) && !strcmp(got, "only-base"), "falls back to the base when the overlay lacks the file");
    uint64_t rh; CHECK(h_fs_OpenFile(&rh, "rom:/data/same", MODE_WRITE) == RES_FAIL, "the two-layer mount refuses writes");
    CHECK(read_fs("rom:\\data\\over", got, sizeof got) && !strcmp(got, "only-overlay") && read_fs("rom:\\data\\base", got, sizeof got) && !strcmp(got, "only-base"), "backslashes separate as slashes do, in both layers");
    CHECK(h_fs_OpenFile(&rh, "rom:/data/base", MODE_READ) == RES_OK && rh && !(rh & 15), "a folder file's handle is 16-byte aligned, as a Horizon FileAccessor pointer is (%#llx)", (unsigned long long)rh); h_fs_CloseFile(rh);
    {   // a damaged *.zs in a RomFS folder (its first bytes zeros: a copy that stopped or was cut) is answered as a damaged image entry is: "not found", or the substitute
        // romfs_substitute names; a good one (zstd magic 28 b5 2f fd, or a skippable frame) and a short one open as they are
        CHECK(read_fs("rom:/data/good.zs", got, sizeof got) && !memcmp(got, "\x28\xb5\x2f\xfd" "rest", 8), "a zstd file in a folder opens as it is");
        CHECK(read_fs("rom:/data/skip.zs", got, sizeof got) && !memcmp(got + 4, "rest", 4), "so does one that begins with a skippable frame");
        CHECK(read_fs("rom:/data/tiny.zs", got, sizeof got) && !strcmp(got, "ab"), "a file shorter than a magic is not judged");
        CHECK(h_fs_OpenFile(&rh, "rom:/data/zero.zs", MODE_READ) == RES_PATH_NOT_FOUND, "a *.zs in a folder that begins with zeros is answered \"not found\"");
        setenv("DM_ROMFS_SUBST", "data/sub.zs=data/good.zs", 1);
        CHECK(read_fs("rom:/data/sub.zs", got, sizeof got) && !memcmp(got, "\x28\xb5\x2f\xfd" "rest", 8), "and served as its substitute when romfs_substitute names one");
        CHECK(h_fs_OpenFile(&rh, "rom:/data/zero.zs", MODE_READ) == RES_PATH_NOT_FOUND, "a damaged file with no substitute named stays \"not found\"");
        unsetenv("DM_ROMFS_SUBST");
    }
    g_nmnt = 0;
    unsetenv("DM_ROMFS_OVERLAY");
    {   // game files that are neither a folder nor a file (a stale path, a fifo) stop the rom mount with a FATAL line, in a child so the abort does not end this test
        char gone[1200], fifo[1200], out[1200]; snprintf(gone, sizeof gone, "%s/RomFS", root); snprintf(fifo, sizeof fifo, "%s/fifo", root); snprintf(out, sizeof out, "%s/fatal.txt", root);
        mkfifo(fifo, 0644);
        const char *paths[2] = {gone, fifo};
        for (int i = 0; i < 2; i++) {
            fflush(NULL); pid_t pid = fork();
            if (!pid) { freopen(out, "w", stderr); setenv("DM_ROMFS", paths[i], 1); h_fs_MountRom("rom", NULL, 0); _exit(0); }
            int st = 0; waitpid(pid, &st, 0);
            char line[512] = {0}; FILE *f = fopen(out, "r"); if (f) { fread(line, 1, sizeof line - 1, f); fclose(f); }
            CHECK(WIFSIGNALED(st) && WTERMSIG(st) == SIGABRT && strstr(line, "FATAL the game files") && strstr(line, paths[i]), "%s game files stop the rom mount", i ? "a fifo as the" : "missing");
        }
    }

    printf("RomFS image\n");   // written by hand in romfs_image.py's layout: /data/a = "hello", /data/b empty directory, /data/c = "xy"
    {   uint8_t img[32 + 7 + 5 * 24 + 7] = {0}; uint64_t hd[3] = {39, 5 * 24 + 7, 5};
        img_ent e[5] = {{1, 1, 0, 0, 0, 0}, {2, 3, 0, 4, 0, 0}, {32, 5, 4, 1, 1, 0}, {5, 0, 5, 1, 0, 0}, {37, 2, 6, 1, 1, 0}};
        memcpy(img, "DMROMFS1", 8); memcpy(img + 8, hd, sizeof hd); memcpy(img + 32, "helloxy", 7); memcpy(img + 39, e, sizeof e); memcpy(img + 39 + sizeof e, "dataabc", 7);
        char ip[1100]; snprintf(ip, sizeof ip, "%s/rom.img", root); FILE *f = fopen(ip, "wb"); fwrite(img, 1, sizeof img, f); fclose(f);
        setenv("DM_ROMFS", ip, 1); unsetenv("DM_ROMFS_OVERLAY"); CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK, "mount the image");
        CHECK(read_fs("rom:/data/a", got, sizeof got) && !strcmp(got, "hello"), "reads a file"); CHECK(read_fs("rom:/data/../data/c", got, sizeof got) && !strcmp(got, "xy"), "reads the next file, through ..");
        uint64_t h; size_t n = 0; int64_t sz = 0; char b[16] = {0};
        CHECK(h_fs_OpenFile(&h, "rom:/data/a", MODE_READ) == RES_OK && h_fs_ReadFile(&n, h, 3, b, 10) == RES_OK && n == 2 && !memcmp(b, "lo", 2), "a read stops at the end of the file (got %zu)", n);
        CHECK(h_fs_GetFileSize(&sz, h) == RES_OK && sz == 5 && h_fs_GetFileOpenMode(h) == MODE_READ, "size 5, open for reading");
        // the overloads without a bytes-read output must read in full: a short read is a failure, with or without read options
        { const uint32_t opt = 0; memset(b, 0, sizeof b);
          CHECK(h_fs_ReadFileExact(h, 1, b, 4) == RES_OK && !memcmp(b, "ello", 4) && h_fs_ReadFileExactOpt(h, 0, b, 5, &opt) == RES_OK && !memcmp(b, "hello", 5), "full reads without a byte count succeed");
          CHECK(h_fs_ReadFileExact(h, 3, b, 10) != RES_OK && h_fs_ReadFileExactOpt(h, 3, b, 10, &opt) != RES_OK, "short reads without a byte count fail"); }
        h_fs_CloseFile(h);
        CHECK(h_fs_GetFileSize(&sz, 0) == RES_FAIL, "a null handle fails (the log names it)");
        // entry 4: its index has low bits set, which Metroid Prime 4's file device masks off (handle & ~7) as it may of a Horizon pointer
        CHECK(h_fs_OpenFile(&h, "rom:/data/c", MODE_READ) == RES_OK && !(h & 15) && h_fs_ReadFile(&n, h & ~7ull, 0, b, 10) == RES_OK && n == 2 && !memcmp(b, "xy", 2), "entry 4 still reads after its handle's low bits are masked off (%#llx)", (unsigned long long)h);
        CHECK(h_fs_GetFileSize(&sz, h & ~7ull) == RES_OK && sz == 2, "and is 2 bytes"); h_fs_CloseFile(h & ~7ull);
        CHECK(read_fs("rom:\\data\\c", got, sizeof got) && !strcmp(got, "xy") && read_fs("rom:\\data\\..\\data\\a", got, sizeof got) && !strcmp(got, "hello"), "backslash paths, also through ..");
        CHECK(h_fs_OpenFile(&h, "rom:/data/x", MODE_READ) == RES_PATH_NOT_FOUND, "a missing file is not found");
        CHECK(h_fs_OpenFile(&h, "rom:/data/A", MODE_READ) == RES_PATH_NOT_FOUND, "names are case-sensitive");
        CHECK(h_fs_OpenFile(&h, "rom:/data/b", MODE_READ) == RES_PATH_NOT_FOUND, "a directory does not open as a file");
        CHECK(h_fs_OpenFile(&h, "rom:/data/a", MODE_WRITE) == RES_FAIL && h_fs_CreateFile("rom:/data/a", 1) == RES_PATH_EXISTS, "refuses writes; create answers \"exists\"");
        int32_t t = -1; CHECK(h_fs_GetEntryType(&t, "rom:/data/b") == RES_OK && t == ENT_DIR && h_fs_GetEntryType(&t, "rom:/data/c") == RES_OK && t == ENT_FILE, "entry types");
        DirectoryEntry de[4]; int64_t cnt = 0;
        CHECK(h_fs_OpenDirectory(&h, "rom:/data", DIRMODE_DIR | DIRMODE_FILE) == RES_OK && h_fs_DirCount(&cnt, h) == RES_OK && cnt == 3, "a directory of 3 entries (%lld)", (long long)cnt);
        CHECK(h_fs_ReadDirectory(&cnt, de, h, 4) == RES_OK && cnt == 3 && !strcmp(de[0].name, "a") && de[0].size == 5 && de[1].type == ENT_DIR && !strcmp(de[2].name, "c"), "lists a, b, c"); h_fs_CloseDirectory(h);
        CHECK(h_fs_OpenDirectory(&h, "rom:/", DIRMODE_FILE) == RES_OK && h_fs_ReadDirectory(&cnt, de, h, 4) == RES_OK && cnt == 0, "the file filter hides directories"); h_fs_CloseDirectory(h);
        CHECK(h_fs_OpenDirectory(&h, "rom:/data/b", DIRMODE_DIR | DIRMODE_FILE) == RES_OK && h_fs_DirCount(&cnt, h) == RES_OK && cnt == 0, "an empty directory"); h_fs_CloseDirectory(h);
        g_nmnt = 0;
        char ov[1100], ovd[1200]; snprintf(ov, sizeof ov, "%s/imgov", root); snprintf(ovd, sizeof ovd, "%s/data", ov); mkdir(ov, 0755); mkdir(ovd, 0755);
        snprintf(fn, sizeof fn, "%s/a", ovd); put(fn, "mod"); snprintf(fn, sizeof fn, "%s/new", ovd); put(fn, "added");
        setenv("DM_ROMFS_OVERLAY", ov, 1); CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK, "mount the image with an overlay folder");
        CHECK(read_fs("rom:/data/a", got, sizeof got) && !strcmp(got, "mod"), "the overlay file wins over the image");
        CHECK(read_fs("rom:/data/c", got, sizeof got) && !strcmp(got, "xy") && read_fs("rom:/data/new", got, sizeof got) && !strcmp(got, "added"), "image files and overlay-only files both read");
        CHECK(h_fs_OpenDirectory(&h, "rom:/data", DIRMODE_DIR | DIRMODE_FILE) == RES_OK && h_fs_ReadDirectory(&cnt, de, h, 4) == RES_OK && cnt == 4, "the listing merges both, once each (%lld)", (long long)cnt);
        int seen = 0; for (int i = 0; i < cnt; i++) { if (!strcmp(de[i].name, "a")) seen += de[i].size == 3; if (!strcmp(de[i].name, "b")) seen += de[i].type == ENT_DIR; if (!strcmp(de[i].name, "c")) seen += de[i].size == 2; if (!strcmp(de[i].name, "new")) seen += de[i].size == 5; }
        CHECK(seen == 4, "with the right types and sizes"); h_fs_CloseDirectory(h);
        CHECK(read_fs("rom:\\data\\a", got, sizeof got) && !strcmp(got, "mod") && read_fs("rom:\\data\\c", got, sizeof got) && !strcmp(got, "xy"), "backslash paths reach the overlay folder and the image");
        h = 0; cnt = -1; CHECK(h_fs_OpenDirectory(&h, "rom:\\data", DIRMODE_DIR | DIRMODE_FILE) == RES_OK && h_fs_DirCount(&cnt, h) == RES_OK && cnt == 4, "and list the merged directory (%lld)", (long long)cnt); h_fs_CloseDirectory(h);
        unsetenv("DM_ROMFS_OVERLAY"); g_nmnt = 0;
        snprintf(fn, sizeof fn, "%s/2.img", root); CHECK(!link(ip, fn), "stage a DLC image"); setenv("DM_AOC_DIR", root, 1);
        size_t cache = 0;
        CHECK(h_fs_QueryAocCache(&cache, 2) == RES_OK && cache == 176, "DLC image reports Horizon table size (176, got %zu)", cache);
        CHECK(h_fs_MountAoc("aoc", 2, NULL, 0) == RES_OK && read_fs("aoc:/data/a", got, sizeof got) && !strcmp(got, "hello"), "DLC mounts and reads directly from its image");
        {   // one mount name for each add-on in turn, unmounted in between (Pokémon Friends' AocVersionCheker: "AVCtitle")
            memcpy(img + 32, "HELLO", 5); snprintf(fn, sizeof fn, "%s/3.img", root); f = fopen(fn, "wb"); fwrite(img, 1, sizeof img, f); fclose(f); memcpy(img + 32, "hello", 5);
            int before = g_nmnt;
            CHECK(h_fs_MountAoc("AVC", 2, NULL, 0) == RES_OK && read_fs("AVC:/data/a", got, sizeof got) && !strcmp(got, "hello"), "the first add-on under a name");
            h_fs_Unmount("AVC"); CHECK(!h_fs_IsMounted("AVC"), "Unmount releases an add-on's name");
            CHECK(!h_fs_IsMounted("") && !read_fs(":/data/a", got, sizeof got), "a released entry answers to no name, the empty one included");
            CHECK(h_fs_MountAoc("AVC", 3, NULL, 0) == RES_OK && read_fs("AVC:/data/a", got, sizeof got) && !strcmp(got, "HELLO"), "the next add-on under that name shows its own files (got %s)", got);
            h_fs_Unmount("AVC");
            CHECK(h_fs_MountAoc("AVC", 2, NULL, 0) == RES_OK && read_fs("AVC:/data/a", got, sizeof got) && !strcmp(got, "hello") && g_nmnt == before + 2,
                  "the first add-on again takes its entry back (%d entries for two add-ons)", g_nmnt - before);
            CHECK(h_fs_MountAoc("AVC", 3, NULL, 0) == RES_OK && read_fs("AVC:/data/a", got, sizeof got) && !strcmp(got, "hello"), "a name still in use keeps its target, as before");
            CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK, "mount the RomFS"); h_fs_Unmount("rom");
            CHECK(h_fs_IsMounted("rom") && read_fs("rom:/data/c", got, sizeof got) && !strcmp(got, "xy"), "Unmount leaves the RomFS mounted, as every mount but an add-on's");
        }
        unsetenv("DM_AOC_DIR"); g_nmnt = 0;
        {   // a file the image marks damaged (IMG_ENT_DAMAGED) still reads, and the mount counts it and the first open names it once
            romfs_img *clean = img_open(ip); CHECK(clean && !clean->damaged, "a clean image has no damaged files"); romfs_image_close(clean);
            e[2].flags = IMG_ENT_DAMAGED; memcpy(img + 39, e, sizeof e);
            f = fopen(ip, "wb"); fwrite(img, 1, sizeof img, f); fclose(f);
            CHECK(h_fs_MountRom("rom", NULL, 0) == RES_OK && g_mnt[0].img && g_mnt[0].img->damaged == 1, "a damaged file mounts, and is counted");
            CHECK(g_mnt[0].img->e[2].flags == IMG_ENT_DAMAGED, "not named before its first open");
            CHECK(read_fs("rom:/data/c", got, sizeof got) && !strcmp(got, "xy") && g_mnt[0].img->e[4].flags == 0, "opening a clean file names nothing");
            CHECK(read_fs("rom:/data/a", got, sizeof got) && !strcmp(got, "hello") && g_mnt[0].img->e[2].flags == (IMG_ENT_DAMAGED | IMG_ENT_REPORTED), "the damaged file reads as written, and is named");
            CHECK(read_fs("rom:/data/a", got, sizeof got) && !strcmp(got, "hello"), "and reads again");
            g_nmnt = 0;
            e[2].flags = 2; memcpy(img + 39, e, sizeof e); f = fopen(ip, "wb"); fwrite(img, 1, sizeof img, f); fclose(f);
            CHECK(!img_open(ip), "an unknown flag on a file is refused");
            e[2].flags = 0; e[1].flags = IMG_ENT_DAMAGED; memcpy(img + 39, e, sizeof e); f = fopen(ip, "wb"); fwrite(img, 1, sizeof img, f); fclose(f);
            CHECK(!img_open(ip), "a damaged flag on a directory is refused");
            e[1].flags = 0; memcpy(img + 39, e, sizeof e);
        }
        f = fopen(ip, "wb"); fwrite(img, 1, 39 + 50, f); fclose(f);   // cut off inside the index, as a copy stopped partway
        CHECK(!img_open(ip), "a cut-off image is refused");
    }
    nup_tests(root);
    nx_tests(root);
    snprintf(save, sizeof save, "%s/save", root); snprintf(work, sizeof work, "%s.work", save);
    mkdir(save, 0755); snprintf(p, sizeof p, "%s/a", save); put(p, "old");
    setenv("DM_SAVE", save, 1); setenv("DM_TEST_FS_FAIL", "write:5:28", 1);   // the 5th save write fails, errno 28 = ENOSPC
    CHECK(h_fs_MountSave("save", NULL) == RES_OK, "mount the save");

    // Save write count: (1)=1st (2)=2nd (3)=3rd (4)=4th/5th/6th/7th (5)=8th
    printf("① pwrite returns 0\n");
    g_mode = 1; g_calls = 0;
    CHECK(write_save("save:/a", "xyz") == RES_FAIL, "WriteFile returns failure 0x2A2");
    CHECK(g_calls == 1, "pwrite called only once (actually %d times; it used to retry forever)", g_calls);

    printf("② pwrite writes in pieces\n");
    g_mode = 2; g_calls = 0;
    CHECK(write_save("save:/a", "abcdefg") == RES_OK, "WriteFile succeeds");
    CHECK(g_calls == 2, "pwrite called twice (3 bytes + 4 bytes; actually %d)", g_calls);
    snprintf(p, sizeof p, "%s/a", work); CHECK(same(p, "abcdefg"), "the work directory holds the complete abcdefg");
    g_mode = 0;

    printf("③ transaction\n");
    CHECK(h_fs_Commit("save") == RES_FAIL && g_nfail == 1 && g_lasterr == ENOSPC, "the Commit of the transaction holding ①② is refused, front end notified once errno=ENOSPC (actually %d times errno=%d)", g_nfail, g_lasterr);
    snprintf(p, sizeof p, "%s/a", work); CHECK(same(p, "old"), "work directory discarded, back to the committed version old");
    CHECK(write_save("save:/a", "new") == RES_OK, "next transaction: write new");
    snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "old"), "before Commit the committed directory is still old");
    CHECK(h_fs_Commit("save") == RES_OK, "Commit");
    CHECK(same(p, "new"), "after Commit the committed directory is new");
    snprintf(p, sizeof p, "%s.new", save); CHECK(access(p, F_OK) != 0, "the swapped-out old version is deleted (no .new left)");

    printf("④ DM_TEST_FS_FAIL=write:5:28\n");
    CHECK(write_save("save:/b", "4") == RES_PATH_NOT_FOUND, "(b does not exist: open fails, not counted as a write)");
    h_fs_CreateFile("save:/b", 0);
    CHECK(write_save("save:/b", "4") == RES_OK, "4th write succeeds");
    errno = 0; CHECK(write_save("save:/b", "5") == RES_FAIL && errno == 28, "5th write fails errno=28 (actually errno=%d)", errno);
    CHECK(write_save("save:/b", "6") == RES_OK, "6th write succeeds");
    CHECK(h_fs_Commit("save") == RES_FAIL && g_nfail == 2 && g_lasterr == 28, "that transaction's Commit is refused, front end notified errno=28 (%d times in total)", g_nfail);
    snprintf(p, sizeof p, "%s/b", save); CHECK(access(p, F_OK) != 0, "committed directory unchanged (no b)");
    snprintf(p, sizeof p, "%s/b", work); CHECK(access(p, F_OK) != 0, "work directory discarded (no b)");
    snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "new"), "committed directory a is still new");
    h_fs_CreateFile("save:/b", 0);
    CHECK(write_save("save:/b", "7") == RES_OK && h_fs_Commit("save") == RES_OK, "next transaction: write b=7, Commit succeeds");
    snprintf(p, sizeof p, "%s/b", save); CHECK(same(p, "7"), "final committed b = 7 (the next transaction's content)");
    CHECK(g_nfail == 2, "a successful transaction sends no notification (still %d times in total)", g_nfail);

    printf("⑤ remounting discards uncommitted content\n");
    CHECK(write_save("save:/a", "v9") == RES_OK, "write a=v9 without committing");
    g_nmnt = 0;                                                        // simulate a process restart: clear the mount table
    CHECK(h_fs_MountSave("save", NULL) == RES_OK, "remount");
    snprintf(p, sizeof p, "%s/a", work); CHECK(same(p, "new"), "work directory a is back to the committed version new");
    snprintf(p, sizeof p, "%s/b", work); CHECK(same(p, "7"), "work directory b = the committed 7");

    printf("⑥ failed writes are handled without exiting (DM_TEST_FS_FAILAT)\n");
    unsetenv("DM_TEST_FS_FAIL");
    // 6a Recloning after a refused commit fails: do not exit, the work directory is untouched and the committed version unchanged; the next commit is refused again and the reclone succeeds; the transaction after that commits normally
    setenv("DM_TEST_FS_FAILAT", "reclone:1", 1);
    CHECK(write_save("save:/a", "bad") == RES_OK, "write a=bad");
    save_poison(ENOSPC); int nf0 = g_nfail;   // a save write of this transaction failed
    CHECK(h_fs_Commit("save") == RES_FAIL && g_nfail == nf0 + 1, "this transaction is refused (injected reclone failure), the process is still alive, front end notified once");
    snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "new"), "committed directory a is still new");
    snprintf(p, sizeof p, "%s/a", work); CHECK(same(p, "bad"), "work directory not deleted (a is still bad)");
    CHECK(h_fs_Commit("save") == RES_FAIL && g_nfail == nf0 + 2, "still tainted: the next commit is refused too");
    snprintf(p, sizeof p, "%s/a", work); CHECK(same(p, "new"), "this time the reclone works: work directory a is back to new");
    snprintf(p, sizeof p, "%s.re", work); CHECK(access(p, F_OK) != 0, "no .re left");
    CHECK(write_save("save:/a", "good") == RES_OK && h_fs_Commit("save") == RES_OK, "next transaction writes a=good, commit succeeds");
    snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "good"), "committed a = good");
    // 6b Writing the guest name after a rename fails and the rollback fails too: do not exit; the file is in a transactional save, so this transaction's commit is refused and the committed version is unchanged; after recloning everything works
    setenv("DM_TEST_FS_FAILAT", "guestname:1,renameback:1", 1);
    h_fs_CreateFile("save:/c", 0); CHECK(write_save("save:/c", "c") == RES_OK, "write c");
    CHECK(h_fs_RenameFile("save:/c", "save:/d") != RES_OK, "rename c → d returns failure (both steps injected to fail), the process is still alive");
    nf0 = g_nfail; CHECK(h_fs_Commit("save") == RES_FAIL && g_nfail == nf0 + 1, "this transaction's commit is refused");
    snprintf(p, sizeof p, "%s/c", save); CHECK(access(p, F_OK) != 0, "committed directory has no c");
    snprintf(p, sizeof p, "%s/d", save); CHECK(access(p, F_OK) != 0, "committed directory has no d");
    snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "good"), "committed a is still good");
    CHECK(write_save("save:/a", "ok2") == RES_OK && h_fs_Commit("save") == RES_OK, "the next transaction commits as usual");
    // 6c Cloning the work directory at mount fails: do not exit; mount the committed directory read-only instead. Reads work, writes fail, commits are no-ops, the committed version is unchanged, and the front end is notified once; the next normal mount works as before
    setenv("DM_TEST_FS_FAILAT", "mountclone:1", 1); g_nmnt = 0; nf0 = g_nfail;
    snprintf(p, sizeof p, "rm -rf '%s'", work); system(p);
    CHECK(h_fs_MountSave("save", NULL) == RES_OK && g_nfail == nf0 + 1, "mount returns success, front end notified once (actually %d times)", g_nfail - nf0);
    CHECK(read_fs("save:/a", got, sizeof got) && !strcmp(got, "ok2"), "the read-only mount reads the committed a=ok2 (read %s)", got);
    CHECK(write_save("save:/a", "zzz") != RES_OK, "writes fail");
    CHECK(h_fs_Commit("save") == RES_OK, "commit is a no-op");
    snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "ok2"), "committed a is still ok2");
    unsetenv("DM_TEST_FS_FAILAT"); g_nmnt = 0;
    CHECK(h_fs_MountSave("save", NULL) == RES_OK && write_save("save:/a", "ok3") == RES_OK && h_fs_Commit("save") == RES_OK, "remount without injection: transactional as usual, writing a=ok3 and committing succeed");
    snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "ok3"), "committed a = ok3");

    printf("⑦ backslash paths on a save\n");
    {   uint64_t h; DirectoryEntry de[4]; int64_t cnt = 0; int32_t t = -1;
        CHECK(h_fs_CreateDirectory("save:\\dir") == RES_OK && h_fs_CreateFile("save:\\dir\\f", 0) == RES_OK && write_save("save:\\dir\\f", "bs") == RES_OK, "create and write save:\\dir\\f");
        CHECK(read_fs("save:/dir/f", got, sizeof got) && !strcmp(got, "bs"), "it is save:/dir/f");
        CHECK(h_fs_RenameFile("save:\\dir\\f", "save:\\dir\\g") == RES_OK && h_fs_GetEntryType(&t, "save:/dir/g") == RES_OK && t == ENT_FILE, "rename by backslash path");
        h = 0; CHECK(h_fs_OpenDirectory(&h, "save:\\dir", DIRMODE_FILE) == RES_OK && h_fs_ReadDirectory(&cnt, de, h, 4) == RES_OK && cnt == 1 && !strcmp(de[0].name, "g"), "the directory lists g, not a name with backslashes (%s)", cnt ? de[0].name : "-"); h_fs_CloseDirectory(h);
    }

    printf("⑧ a save folder that cannot clone, and symlinks in recursive deletes\n");
    {   g_nmnt = 0; g_clone_enotsup = 1; int nf1 = g_nfail;
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && g_mnt[0].ro && g_nfail == nf1 + 1, "clonefile ENOTSUP: the committed save mounts read-only and the front end is told once");
        CHECK(read_fs("save:/a", got, sizeof got) && !strcmp(got, "ok3"), "the committed save still reads (a = %s)", got);
        CHECK(write_save("save:/a", "lost") != RES_OK, "a write is refused");
        snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "ok3"), "committed a is still ok3");
        g_clone_enotsup = 0; g_nmnt = 0;
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && !g_mnt[0].ro, "with cloning back, the save mounts writable");
        char victim[1100], vf[1200], lnk[1200];
        snprintf(victim, sizeof victim, "%s/victim", root); mkdir(victim, 0755); snprintf(vf, sizeof vf, "%s/keep", victim); put(vf, "keep");
        CHECK(h_fs_CreateDirectory("save:/d") == RES_OK, "create save:/d");
        snprintf(lnk, sizeof lnk, "%s/d/lnk", work); CHECK(symlink(victim, lnk) == 0, "a symlink to a directory outside the save inside save:/d");
        CHECK(h_fs_DeleteDirRec("save:/d") == RES_OK && access(lnk, F_OK) && same(vf, "keep"), "DeleteDirectoryRecursively removes the symlink, not its target's files");
        CHECK(h_fs_CreateDirectory("save:/d") == RES_OK && symlink(victim, lnk) == 0, "the same again in a new save:/d");
        CHECK(h_fs_CleanDirRec("save:/d") == RES_OK && access(lnk, F_OK) && same(vf, "keep"), "CleanDirectoryRecursively removes the symlink, not its target's files");
        snprintf(lnk, sizeof lnk, "%s/lnk2", root); symlink(victim, lnk); errno = 0;
        CHECK(rm_tree(lnk) == -1 && errno == ENOTDIR && same(vf, "keep"), "rm_tree of a symlink to a directory refuses it (ENOTDIR) and leaves the target alone");
    }

    printf("⑨ Unmount: a no-op with one user; with more, a save's entry is freed and the next save mount takes it\n");
    {   extern int g_test_users; g_nmnt = 0;
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && write_save("save:/a", "keep") == RES_OK, "one user: mount, write a=keep without committing");
        h_fs_Unmount("save");
        CHECK(g_nmnt == 1 && !strcmp(g_mnt[0].name, "save") && read_fs("save:/a", got, sizeof got) && !strcmp(got, "keep"), "one user: Unmount leaves the mount and its uncommitted write (a = %s)", got);
        CHECK(h_fs_Commit("save") == RES_OK, "commit a=keep");
        g_test_users = 2; int i = 0;
        for (; i < MAX_MOUNT + 8; i++) { h_fs_Unmount("save"); if (h_fs_MountSave("save", NULL) != RES_OK) break; }
        CHECK(i == MAX_MOUNT + 8 && g_nmnt == 1 && g_mnt[0].txn_root == 0, "two users: %d Unmount + mount cycles keep one entry (table holds %d)", MAX_MOUNT + 8, g_nmnt);
        CHECK(write_save("save:/a", "drop") == RES_OK, "write a=drop without committing"); h_fs_Unmount("save");
        CHECK(!g_mnt[0].name[0], "Unmount frees the save's name");
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && g_nmnt == 1 && read_fs("save:/a", got, sizeof got) && !strcmp(got, "keep"), "the mount after Unmount starts from the committed save, in the same entry (a = %s)", got);
        // an entry whose transaction another mounted name still shares is not taken: that name's pending writes live in it
        CHECK(h_fs_MountSave("alias", NULL) == RES_OK && g_nmnt == 2 && g_mnt[1].txn_root == 0, "an alias shares entry 0's transaction");
        CHECK(write_save("alias:/a", "pending") == RES_OK, "write a=pending through the alias"); h_fs_Unmount("save");
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && g_nmnt == 3 && g_mnt[2].txn_root == 0, "a new mount of the save appends (entry 0 is the alias's transaction) and shares it (table holds %d)", g_nmnt);
        CHECK(read_fs("save:/a", got, sizeof got) && !strcmp(got, "pending") && h_fs_Commit("alias") == RES_OK, "and sees the alias's pending write (a = %s), which commits", got);
        snprintf(p, sizeof p, "%s/a", save); CHECK(same(p, "pending"), "committed a = pending");
        g_test_users = 1;
    }
    {   // Unmount follows who holds the mount: the host's table is permanent, the SDK's entry is released in the SDK, so the name can be mounted again
        CHECK(h_fs_IsMounted("save"), "the host holds save");
        h_fs_Unmount("cache"); CHECK(1, "Unmount with no SDK in the process is a no-op");
        t_guest_sym = sdk_sym; g_sdk_cache_mounted = 1;
        h_fs_Unmount("save");
        CHECK(!g_sdk_asked && !g_sdk_unmounts && h_fs_IsMounted("save"), "a host mount stays and the SDK is not asked");
        h_fs_Unmount("nowhere");
        CHECK(g_sdk_asked == 1 && !g_sdk_unmounts, "a name in neither table is a no-op (the SDK's Unmount would abort on it)");
        h_fs_Unmount(NULL);
        CHECK(g_sdk_asked == 1 && !g_sdk_unmounts, "a null name reaches no SDK call");
        h_fs_Unmount("cache");
        CHECK(g_sdk_unmounts == 1 && !g_sdk_cache_mounted, "an SDK-owned mount is released in the SDK");
        h_fs_Unmount("cache");
        CHECK(g_sdk_unmounts == 1, "unmounting it again does not reach the SDK's Unmount");
        g_sdk_cache_mounted = 1; g_sdk_no_is_mounted = 1; h_fs_Unmount("cache");
        CHECK(g_sdk_unmounts == 1 && g_sdk_cache_mounted, "an SDK without an IsMounted symbol: no blind Unmount");
        t_guest_sym = NULL;
    }

    printf("⑩ repeated save commit failures are deduplicated per path with a count\n");
    {   char neu[1100], neuc[1200], out[1100];
        snprintf(neu, sizeof neu, "%s.new", save);
        snprintf(neuc, sizeof neuc, "%s/stuck", neu);
        snprintf(out, sizeof out, "%s/commit_dedup.txt", root);
        mkdir(neu, 0755); put(neuc, "x"); chmod(neu, 0555);
        fflush(NULL); pid_t pid = fork();
        if (!pid) {
            freopen(out, "w", stderr);
            for (int k = 0; k < 25; k++) {
                if (h_fs_Commit("save") != RES_FAIL) _exit(2);
            }
            chmod(neu, 0755);
            if (h_fs_Commit("save") != RES_OK) _exit(3);
            if (h_fs_Commit("save") != RES_OK) _exit(4);
            fflush(stderr);
            _exit(0);
        }
        int st = 0; waitpid(pid, &st, 0);
        chmod(neu, 0755); rm_tree(neu);
        char buf[8192] = {0}; FILE *f = fopen(out, "r");
        if (f) { fread(buf, 1, sizeof buf - 1, f); fclose(f); }
        int n_cannot = 0, n_still = 0, n_cleared = 0;
        for (const char *q = buf; (q = strstr(q, "cannot clear the")); q++) n_cannot++;
        for (const char *q = buf; (q = strstr(q, "still failing")); q++) n_still++;
        for (const char *q = buf; (q = strstr(q, "cleared after 25 failed attempts")); q++) n_cleared++;
        CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0, "25 failed commits then recovery after chmod");
        CHECK(n_cannot == 1 && n_still == 1 && n_cleared == 1,
              "deduped log: cannot_clear=%d still_failing=%d cleared=%d (expected 1, 1, 1)", n_cannot, n_still, n_cleared);
    }

    printf("⑪ commit durability, and a save swap journal left part way\n");
    {   char neu[1200], m1[1200], nf[1300], cmd2[1300]; g_nmnt = 0;
        snprintf(neu, sizeof neu, "%s.new", save); snprintf(m1, sizeof m1, "%s.pend1", save); snprintf(nf, sizeof nf, "%s/a", neu); snprintf(p, sizeof p, "%s/a", save);
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && !g_mnt[0].ro, "mount the save");
        setenv("DM_TEST_FS_FAILAT", "commitsync:1", 1);
        CHECK(write_save("save:/a", "synced") == RES_OK && h_fs_Commit("save") == RES_FAIL, "a commit whose new version cannot be synced to the storage is refused");
        CHECK(same(p, "pending") && access(neu, F_OK) != 0, "the committed a is unchanged and the unsynced .new is gone");
        unsetenv("DM_TEST_FS_FAILAT");
        CHECK(h_fs_Commit("save") == RES_OK && same(p, "synced"), "the next commit (the work directory still holds a=synced) goes through");
        // a journal (the Android fallback swap's marker) beside the save that recovery could not finish: its .new is the only whole copy of the new version
        mkdir(neu, 0755); put(nf, "journal"); put(m1, neu);
        CHECK(write_save("save:/a", "blocked") == RES_OK && h_fs_Commit("save") == RES_FAIL, "a commit with a journal left beside the save is refused");
        CHECK(same(nf, "journal") && !access(m1, F_OK) && same(p, "synced"), "the journal's .new, its marker and the save are kept as they are");
        g_nmnt = 0;
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && g_mnt[0].ro, "a mount with the journal left mounts the save read-only");
        CHECK(same(nf, "journal") && !access(m1, F_OK), "and keeps the .new and the marker (they were cleared before)");
        CHECK(read_fs("save:/a", got, sizeof got) && !strcmp(got, "synced"), "the save still reads (a = %s)", got);
        unlink(m1); snprintf(cmd2, sizeof cmd2, "rm -rf '%s'", neu); system(cmd2); g_nmnt = 0;
        CHECK(h_fs_MountSave("save", NULL) == RES_OK && !g_mnt[0].ro && write_save("save:/a", "after") == RES_OK && h_fs_Commit("save") == RES_OK && same(p, "after"), "with the journal gone the save is writable and commits again");
    }

    char cmd[1200]; snprintf(cmd, sizeof cmd, "rm -rf '%s'", root); system(cmd);
    printf("== %d checks, %d failed ==\n", checks, fails);
    return fails != 0;
}
