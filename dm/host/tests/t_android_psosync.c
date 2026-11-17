// Which draws are not skipped while their pipeline is made in the background (dm/gfx/vk_android/vka_psosync.h vkps_why; vka_draw.c draw_body): a draw into a small
// target, a draw that writes memory, the size limit's edges, and that every other draw stays skipped
#include <stdio.h>
#include <string.h>
#include "../../gfx/vk_android/vka_psosync.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static vkps_draw draw(uint32_t w, uint32_t h, int writes) { vkps_draw d = {0}; d.w = w; d.h = h; d.writes = writes; return d; }

int main(void) {
    vkps_draw d;
    // Draws into larger targets that write no memory stay skipped: the scene, a full-screen pass, a shadow map, a cube face
    d = draw(1280, 720, 0); CHECK(vkps_why(&d) == 0, "the scene's target");
    d = draw(1920, 1080, 0); CHECK(vkps_why(&d) == 0, "1920x1080");
    d = draw(2048, 2048, 0); CHECK(vkps_why(&d) == 0, "a shadow map");
    d = draw(128, 128, 0); CHECK(vkps_why(&d) == 0, "a 128x128 target");
    // Small targets: both sides within the limit, and a size that is known
    d = draw(64, 64, 0); CHECK(vkps_why(&d) == VKPS_SMALL, "64x64");
    d = draw(1, 1, 0); CHECK(vkps_why(&d) == VKPS_SMALL, "1x1");
    d = draw(64, 1, 0); CHECK(vkps_why(&d) == VKPS_SMALL, "64x1");
    d = draw(256, 16, 0); CHECK(vkps_why(&d) == 0, "256x16: one side over");
    d = draw(16, 256, 0); CHECK(vkps_why(&d) == 0, "16x256: the other side over");
    d = draw(65, 64, 0); CHECK(vkps_why(&d) == 0, "65x64");
    d = draw(64, 65, 0); CHECK(vkps_why(&d) == 0, "64x65");
    d = draw(0, 0, 0); CHECK(vkps_why(&d) == 0, "size not known");
    d = draw(0, 32, 0); CHECK(vkps_why(&d) == 0, "one side not known");
    // Draws that write memory, whatever the target
    d = draw(1280, 720, 1); CHECK(vkps_why(&d) == VKPS_WRITES, "writes memory");
    d = draw(0, 0, 1); CHECK(vkps_why(&d) == VKPS_WRITES, "writes memory, no target (the rasterizer off)");
    d = draw(65, 64, 1); CHECK(vkps_why(&d) == VKPS_WRITES, "writes memory, just over the small size");
    d = draw(16, 16, 1); CHECK(vkps_why(&d) == VKPS_SMALL, "both apply: the small target is the reason");
    CHECK(VKPS_SMALL != 0 && VKPS_WRITES != 0 && VKPS_SMALL != VKPS_WRITES, "the reasons are apart and not 0");
    CHECK(strcmp(vkps_name(VKPS_SMALL), vkps_name(VKPS_WRITES)) && strcmp(vkps_name(0), vkps_name(VKPS_SMALL)) && strcmp(vkps_name(0), vkps_name(VKPS_WRITES)), "the reasons' names differ");
    printf(fails ? "t_android_psosync: %d FAILED\n" : "t_android_psosync: ok\n", fails);
    return fails != 0;
}
