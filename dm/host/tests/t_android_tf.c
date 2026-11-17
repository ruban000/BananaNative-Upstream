// the Android replay's transform feedback, the part with no Vulkan in it (dm/gfx/vk_android/vka_tf_spv.c): the capture layout read from a control section, and
// the rewrite that makes a vertex module capture it, on the graphics selftest's vertex shader (one float vec4 output at Location 0), checked by structure and, when
// spirv-val is on the PATH, by validation. DM_VK_SPV_DIR=<folder of .spv vertex modules> also rewrites each of those to capture its lowest vec4 output and validates it
#include "../../gfx/vk_android/vka_tf.h"
#include "../../gfx/vk_android/vka_selftest_spv.h"
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
static int have_val(void) { return system("spirv-val --version >/dev/null 2>&1") == 0; }
static int validate(const uint32_t *w, size_t n, const char *tag) {
    char path[256]; snprintf(path, sizeof path, "/tmp/t_tf_%d.spv", (int)getpid());
    FILE *f = fopen(path, "wb"); if (!f) return 0; fwrite(w, 4, n, f); fclose(f);
    char cmd[400]; snprintf(cmd, sizeof cmd, "spirv-val --target-env vulkan1.3 %s", path);
    int r = system(cmd); remove(path);
    if (r) printf("  spirv-val failed on %s\n", tag);
    return r == 0;
}
static int count_op(const uint32_t *w, size_t n, uint32_t op) { int k = 0; for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; k += (w[i] & 0xffff) == op; } return k; }
// The value of decoration dec on any id, counted (an OpDecorate of 4 words), and the last value seen
static int count_dec(const uint32_t *w, size_t n, uint32_t dec, uint32_t value) {
    int k = 0; for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; k += w[i] == (4u << 16 | 71) && w[i + 2] == dec && w[i + 3] == value; } return k; }

// A control section as GLSLC writes the fields read here: the strides at +0x6dc, the output counts at +0x6e8, each buffer's locations at +0xdc and components at +0x4dc
static void ctl_buf(uint8_t *c, int b, uint16_t stride, int n, const uint8_t *loc, const uint8_t *comp) {
    memcpy(c + 0x6dc + 2 * b, &stride, 2); c[0x6e8 + b] = (uint8_t)n;
    for (int i = 0; i < n; i++) { c[0xdc + 0x80 * b + i] = (uint8_t)(0x20 + loc[i]); c[0x4dc + 0x80 * b + i] = comp[i]; }
}
static void t_layout(void) {
    static uint8_t c[0x880]; vka_tf_layout L; uint32_t magic = 0x98761234u;
    memset(c, 0, sizeof c); memcpy(c, &magic, 4);
    CHECK(vka_tf_layout_read(c, sizeof c, &L) == 0, "no strides: no capture");
    CHECK(vka_tf_layout_read(c, 0x100, &L) == 0 && vka_tf_layout_read(NULL, 0, &L) == 0, "a short or missing section: no capture");
    ctl_buf(c, 0, 12, 1, (uint8_t[]){3}, (uint8_t[]){3});                       // one output a stream (Pokemon Legends: Z-A)
    ctl_buf(c, 2, 28, 3, (uint8_t[]){0, 7, 31}, (uint8_t[]){4, 1, 2});          // three interleaved
    CHECK(vka_tf_layout_read(c, sizeof c, &L) == 1, "a layout of two buffers is read");
    CHECK(L.stride[0] == 12 && L.n[0] == 1 && L.loc[0][0] == 3 && L.comp[0][0] == 3, "buffer 0: location 3, three components");
    CHECK(!L.stride[1] && !L.n[1] && !L.stride[3], "buffers 1 and 3 unused");
    CHECK(L.stride[2] == 28 && L.n[2] == 3 && L.loc[2][1] == 7 && L.comp[2][1] == 1 && L.loc[2][2] == 31 && L.comp[2][2] == 2, "buffer 2: three outputs in order");
    uint16_t bad = 16; memcpy(c + 0x6dc, &bad, 2);
    CHECK(vka_tf_layout_read(c, sizeof c, &L) == -1, "a stride that is not its components' bytes: unknown form");
    ctl_buf(c, 0, 12, 1, (uint8_t[]){3}, (uint8_t[]){3}); c[0xdc] = 0x07;
    CHECK(vka_tf_layout_read(c, sizeof c, &L) == -1, "an output that is no generic: unknown form");
    c[0xdc] = 0x23; c[0x4dc] = 5;
    CHECK(vka_tf_layout_read(c, sizeof c, &L) == -1, "five components: unknown form");
    c[0x4dc] = 3; c[0x6e8] = 0;
    CHECK(vka_tf_layout_read(c, sizeof c, &L) == -1, "a stride with no outputs: unknown form");
    c[0x6e8] = 1; uint32_t v2 = 0xc9; memcpy(c + 4, &v2, 4);
    CHECK(vka_tf_layout_read(c, sizeof c, &L) == 0, "an NVN2 section: no capture");
}
static void t_rewrite(int val) {
    size_t n = sizeof SPV_TRI_VERT / 4, on = 0; char err[256];
    // location 0 (the module's vec4) as xyz into buffer 0; all of it, its x, and two components of location 5 (which the module lacks) into buffer 1
    vka_tf_layout L = {.stride = {12, 28}, .n = {1, 3}, .loc = {{0}, {0, 0, 5}}, .comp = {{3}, {4, 1, 2}}};
    uint32_t *o = vka_spv_xfb(SPV_TRI_VERT, n, &L, 16, &on, err, sizeof err);
    CHECK(o, "selftest vertex shader not rewritten: %s", err);
    if (o) {
        CHECK(o[3] > SPV_TRI_VERT[3] && on > n, "bound and size grow (%u → %u)", SPV_TRI_VERT[3], o[3]);
        CHECK(o[5] == (2u << 16 | 17) && o[6] == 53, "the TransformFeedback capability comes first");
        CHECK(count_op(o, on, 16) == count_op(SPV_TRI_VERT, n, 16) + 1, "one more execution mode (Xfb)");
        CHECK(count_op(o, on, 59) == count_op(SPV_TRI_VERT, n, 59) + 4, "four new variables");
        CHECK(count_op(o, on, 62) == count_op(SPV_TRI_VERT, n, 62) + 4 * count_op(SPV_TRI_VERT, n, 253), "a store of each before each return");
        CHECK(count_op(o, on, 46) == count_op(SPV_TRI_VERT, n, 46) + 1, "zeros for the output the module lacks");
        CHECK(count_dec(o, on, 36, 0) == 1 && count_dec(o, on, 36, 1) == 3, "XfbBuffer 0 once, 1 three times");
        CHECK(count_dec(o, on, 37, 12) == 1 && count_dec(o, on, 37, 28) == 3, "XfbStride 12 and 28");
        CHECK(count_dec(o, on, 35, 0) == 2 && count_dec(o, on, 35, 16) == 1 && count_dec(o, on, 35, 20) == 1, "Offsets 0, 0, 16, 20");
        CHECK(count_dec(o, on, 30, 1) == count_dec(SPV_TRI_VERT, n, 30, 1) + 1 && count_dec(o, on, 30, 4) == 1, "the new outputs take the free locations 1 to 4");
        if (val) CHECK(validate(o, on, "selftest vertex with capture"), "valid SPIR-V");
        size_t on2 = 0; CHECK(!vka_spv_xfb(o, on, &L, 16, &on2, err, sizeof err), "a module that already captures is refused");
        free(o);
    }
    CHECK(!vka_spv_xfb(SPV_TRI_VERT, n, &L, 3, &on, err, sizeof err) && err[0], "no free location below the limit: refused (%s)", err);
    CHECK(!vka_spv_xfb(SPV_TRI_FRAG, sizeof SPV_TRI_FRAG / 4, &L, 16, &on, err, sizeof err), "a module with no vertex entry point is refused");
    vka_tf_layout none = {0};
    CHECK(!vka_spv_xfb(SPV_TRI_VERT, n, &none, 16, &on, err, sizeof err), "nothing to capture: refused");
    uint32_t *cut = malloc(n * 4); memcpy(cut, SPV_TRI_VERT, n * 4);
    CHECK(!vka_spv_xfb(cut, n - 3, &L, 16, &on, err, sizeof err), "a cut module is refused (the sanitizers check the reads)");
    cut[3] = 4; o = vka_spv_xfb(cut, n, &L, 16, &on, err, sizeof err); free(o);   // ids past the bound: not read out of range
    free(cut);
}
// Real modules: each vertex module of the folder captures its lowest float vec4 output as xyz, and all its vec4 outputs interleaved
static void t_dir(const char *dir, int val) {
    DIR *d = opendir(dir); if (!d) { printf("DM_VK_SPV_DIR %s: not a folder\n", dir); g_fail++; return; }
    int made = 0, refused = 0; struct dirent *e;
    while ((e = readdir(d))) {
        size_t l = strlen(e->d_name); if (l < 5 || strcmp(e->d_name + l - 4, ".spv")) continue;
        char p[1024]; snprintf(p, sizeof p, "%s/%s", dir, e->d_name);
        FILE *f = fopen(p, "rb"); if (!f) continue;
        fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
        uint32_t *w = malloc((size_t)sz + 4); size_t n = fread(w, 4, (size_t)sz / 4, f); fclose(f);
        // (the module's outputs, found as the rewrite finds them)
        uint8_t locd[4096] = {0}; uint32_t fl = 0, v4 = 0, pv4[8] = {0}, np = 0, outs = 0;
        for (size_t i = 5; i < n && (w[i] >> 16); i += w[i] >> 16) {
            uint32_t op = w[i] & 0xffff;
            if (op == 71 && w[i + 2] == 30 && w[i + 1] < 4096 && w[i + 3] < 32) locd[w[i + 1]] = (uint8_t)(w[i + 3] + 1);
            if (op == 22 && w[i + 2] == 32) fl = w[i + 1];
            if (op == 23 && w[i + 2] == fl && w[i + 3] == 4) v4 = w[i + 1];
            if (op == 32 && w[i + 2] == 3 && w[i + 3] == v4 && np < 8) pv4[np++] = w[i + 1];
            if (op == 59 && w[i + 3] == 3 && w[i + 2] < 4096 && locd[w[i + 2]]) for (uint32_t k = 0; k < np; k++) if (pv4[k] == w[i + 1]) outs |= 1u << (locd[w[i + 2]] - 1);
            if (op == 54) break;
        }
        if (outs) {
            vka_tf_layout L = {0}; int first = __builtin_ctz(outs);
            L.stride[0] = 12; L.n[0] = 1; L.loc[0][0] = (uint8_t)first; L.comp[0][0] = 3;
            for (int b = 0; b < 32 && L.n[1] < 6; b++) if (outs >> b & 1) { L.loc[1][L.n[1]] = (uint8_t)b; L.comp[1][L.n[1]++] = 4; }
            L.stride[1] = (uint16_t)(16 * L.n[1]);
            size_t on = 0; char err[256]; uint32_t *o = vka_spv_xfb(w, n, &L, 32, &on, err, sizeof err);
            if (!o) { refused++; printf("  %s: refused: %s\n", e->d_name, err); }
            else { made++; if (val) CHECK(validate(o, on, e->d_name), "%s: valid SPIR-V", e->d_name); free(o); }
        }
        free(w);
    }
    closedir(d);
    printf("DM_VK_SPV_DIR: %d modules rewritten, %d refused\n", made, refused);
    CHECK(made > 0, "no module of the folder was rewritten");
}
int main(void) {
    int val = have_val();
    if (!val) printf("spirv-val not on the PATH: structure checks only\n");
    t_layout(); t_rewrite(val);
    const char *dir = getenv("DM_VK_SPV_DIR"); if (dir && *dir) t_dir(dir, val);
    if (g_fail) { printf("t_android_tf: %d FAILED\n", g_fail); return 1; }
    printf("t_android_tf: ok\n");
    return 0;
}
