/* Desktop smoke-test launcher: same IPC server as the Android app, engine run on the main thread.
 * Usage: hp_hosttest <data dir> <user dir> <game.ipc> [width height]
 * Not shipped in the APK. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../launcher/hp.h"

extern int hwengine_RunEngine(int argc, char **argv);

int main(int argc, char **argv)
{
    if (argc < 4) {
        fprintf(stderr, "usage: %s data user game.ipc [w h]\n", argv[0]);
        return 1;
    }
    const char *w = argc > 4 ? argv[4] : "1280", *h = argc > 5 ? argv[5] : "720";
    char port[16];
    int p = hp_ipc_start(argv[3]);
    if (p < 0) return 2;
    snprintf(port, sizeof port, "%d", p);
    char *args[] = {
        "--no-holiday-silliness", "--internal", "--port", port,
        "--prefix", argv[1], "--user-prefix", argv[2],
        "--width", (char *)w, "--height", (char *)h,
        "--locale", "en.txt", "--nodampen", "--nosound", "--nomusic",
    };
    int rc = hwengine_RunEngine((int)(sizeof args / sizeof args[0]), args);
    fprintf(stderr, "engine returned %d\n", rc);
    hp_ipc_join();
    return rc;
}
