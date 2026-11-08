/* Desktop smoke-test launcher: same IPC server as the Android app, engine run on the main thread.
 * Usage: hp_hosttest <data dir> <user dir> <game.ipc> [width height]
 * Not shipped in the APK. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <SDL.h>

#include "../launcher/hp.h"

extern int hwengine_RunEngine(int argc, char **argv);

/* Test hook: HP_TEST_RESTORE=<seconds>[,<seconds>...] posts SDL_WINDOWEVENT_RESTORED to the engine
 * at those times. On the phone the same event arrives right after start-up and again after every
 * pause; the engine answers it with "fullscr", which reloads the video state, the chat lines and
 * all textures (the path that crashed v0.1.1 in PrettifySurfaceAlpha). */
static void *restore_thread(void *arg)
{
    char *list = (char *)arg, *save = NULL;
    int last = 0;
    for (char *t = strtok_r(list, ",", &save); t; t = strtok_r(NULL, ",", &save)) {
        int at = atoi(t);
        if (at > last) sleep((unsigned)(at - last));
        last = at;
        SDL_Event e;
        memset(&e, 0, sizeof e);
        e.type = SDL_WINDOWEVENT;
        e.window.event = SDL_WINDOWEVENT_RESTORED;
        e.window.windowID = 1;
        SDL_PushEvent(&e);
        fprintf(stderr, "[hosttest] posted SDL_WINDOWEVENT_RESTORED at %ds\n", at);
    }
    return NULL;
}

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
    const char *rs = getenv("HP_TEST_RESTORE");
    if (rs && *rs) {
        pthread_t th;
        if (pthread_create(&th, NULL, restore_thread, strdup(rs)) == 0) pthread_detach(th);
    }
    int rc = hwengine_RunEngine((int)(sizeof args / sizeof args[0]), args);
    fprintf(stderr, "engine returned %d\n", rc);
    hp_ipc_join();
    return rc;
}
