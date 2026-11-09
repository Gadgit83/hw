/* Desktop smoke-test launcher: same IPC server as the Android app, engine run on the main thread.
 * Usage: hp_hosttest <data dir> <user dir> <game.ipc> [width height]
 * Not shipped in the APK. */
#include <stdint.h>
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

/* Test hook: HP_TEST_SCRIPT is a ';' separated list of "<ms>:<action>[:args]", run in order from a
 * launcher thread (times are milliseconds since the engine started). Actions:
 *   down:<finger>:<x>:<y>   move:<finger>:<x>:<y>   up:<finger>:<x>:<y>   (x, y normalised 0..1, as SDL2 gives them)
 *   focuslost  focusgained  minimized  restored      (window events, as the phone sends them)
 *   ipc:<text>                                         (a message to the engine as the frontend sends it, e.g. ipc:FGood Night: that team left)
 *   ctl:<letter>                                       (a control frame to the relay as the launcher would send it: ctl:P pause, ctl:R resume)
 *   waitfile:<path>                                    (blocks the script until the file exists: the shell or the net-test harness decides when)
 *   key:esc  key:y                                     (a key press and release, as GameActivity sends for Back and the confirm in a network game)
 *   key:enter  text:<chars>                            (opens the chat, types into it)
 *   userquit                                           (what GameActivity does when the player confirms leaving: hp_ipc_user_quit)
 *   log:<text>                                         (marks the engine log, for the shell driver) */
static void push_key(SDL_Keycode sym, SDL_Scancode sc)
{
    for (int down = 1; down >= 0; down--) {
        SDL_Event e;
        memset(&e, 0, sizeof e);
        e.type = down ? SDL_KEYDOWN : SDL_KEYUP;
        e.key.state = down ? SDL_PRESSED : SDL_RELEASED;
        e.key.keysym.sym = sym;
        e.key.keysym.scancode = sc;
        e.key.windowID = 1;
        SDL_PushEvent(&e);
        usleep(30000);
    }
}

static void push_finger(Uint32 type, int id, float x, float y, float *px, float *py)
{
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = type;
    e.tfinger.touchId = 1;
    e.tfinger.fingerId = id;
    e.tfinger.x = x;
    e.tfinger.y = y;
    e.tfinger.dx = type == SDL_FINGERMOTION ? x - *px : 0;
    e.tfinger.dy = type == SDL_FINGERMOTION ? y - *py : 0;
    e.tfinger.pressure = 1.0f;
    *px = x;
    *py = y;
    SDL_PushEvent(&e);
}

static void push_window(int ev)
{
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = SDL_WINDOWEVENT;
    e.window.event = (Uint8)ev;
    e.window.windowID = 1;
    SDL_PushEvent(&e);
}

static void *script_thread(void *arg)
{
    char *list = (char *)arg, *save = NULL;
    long last = 0;
    float px[16] = {0}, py[16] = {0};
    for (char *item = strtok_r(list, ";", &save); item; item = strtok_r(NULL, ";", &save)) {
        long at = atol(item);
        char *act = strchr(item, ':');
        if (!act) continue;
        act++;
        if (at > last) usleep((useconds_t)((at - last) * 1000));
        last = at;
        int id = 0;
        float x = 0, y = 0;
        if (!strncmp(act, "down:", 5) && sscanf(act + 5, "%d:%f:%f", &id, &x, &y) == 3) push_finger(SDL_FINGERDOWN, id, x, y, &px[id & 15], &py[id & 15]);
        else if (!strncmp(act, "move:", 5) && sscanf(act + 5, "%d:%f:%f", &id, &x, &y) == 3) push_finger(SDL_FINGERMOTION, id, x, y, &px[id & 15], &py[id & 15]);
        else if (!strncmp(act, "up:", 3) && sscanf(act + 3, "%d:%f:%f", &id, &x, &y) == 3) push_finger(SDL_FINGERUP, id, x, y, &px[id & 15], &py[id & 15]);
        else if (!strncmp(act, "ipc:", 4)) fprintf(stderr, "[hosttest] ipc send %s -> %d\n", act + 4, hp_ipc_send(act + 4));
        else if (!strncmp(act, "ctl:", 4) && act[4]) { hp_ipc_send_control(act[4]); fprintf(stderr, "[hosttest] control frame %c sent to the relay\n", act[4]); }
        else if (!strncmp(act, "waitfile:", 9)) { for (int i = 0; i < 20000 && access(act + 9, F_OK) != 0; i++) usleep(50000); }
        else if (!strcmp(act, "key:esc")) push_key(SDLK_ESCAPE, SDL_SCANCODE_ESCAPE);
        else if (!strcmp(act, "key:y")) push_key(SDLK_y, SDL_SCANCODE_Y);
        else if (!strcmp(act, "key:enter")) push_key(SDLK_RETURN, SDL_SCANCODE_RETURN);
        else if (!strncmp(act, "text:", 5)) { SDL_Event e; memset(&e, 0, sizeof e); e.type = SDL_TEXTINPUT; e.text.windowID = 1; snprintf(e.text.text, sizeof e.text.text, "%s", act + 5); SDL_PushEvent(&e); }
        else if (!strcmp(act, "userquit")) fprintf(stderr, "[hosttest] user quit -> %d\n", hp_ipc_user_quit());
        else if (!strcmp(act, "focuslost")) push_window(SDL_WINDOWEVENT_FOCUS_LOST);
        else if (!strcmp(act, "focusgained")) push_window(SDL_WINDOWEVENT_FOCUS_GAINED);
        else if (!strcmp(act, "minimized")) push_window(SDL_WINDOWEVENT_MINIMIZED);
        else if (!strcmp(act, "restored")) push_window(SDL_WINDOWEVENT_RESTORED);
        fprintf(stderr, "[hosttest] %ldms %s\n", at, act);
    }
    return NULL;
}

/* The engine calls this on a big explosion (uUtils.pas DeviceRumble); the app vibrates, the test logs it. */
void hw_device_rumble(int32_t ms)
{
    fprintf(stderr, "[hosttest] rumble %d ms\n", (int)ms);
}

int main(int argc, char **argv)
{
    if (argc < 4) {
        fprintf(stderr, "usage: %s data user game.ipc [w h]\n", argv[0]);
        return 1;
    }
    const char *w = argc > 4 ? argv[4] : "1280", *h = argc > 5 ? argv[5] : "720";
    char port[16];
    /* HP_NET_CFG=<file>: network game through the relay, same key=value file the app writes (net.cfg). */
    if (getenv("HP_NET_CFG") && hp_ipc_net_config(getenv("HP_NET_CFG")) != 0) {
        fprintf(stderr, "cannot read the network config %s\n", getenv("HP_NET_CFG"));
        return 1;
    }
    int p = hp_ipc_start(argv[3]);
    if (p < 0) return 2;
    snprintf(port, sizeof port, "%d", p);
    char *args[64] = {
        "--no-holiday-silliness", "--internal", "--port", port,
        "--prefix", argv[1], "--user-prefix", argv[2],
        "--width", (char *)w, "--height", (char *)h,
        "--locale", "en.txt", "--nodampen", "--nosound", "--nomusic",
    };
    int nargs = 16;
    if (hp_ipc_net_nick()) { args[nargs++] = "--nick"; args[nargs++] = (char *)hp_ipc_net_nick(); }
    /* HP_TEST_SOUND=1 runs with the SDL dummy audio driver and sound on (the default test has it off). */
    if (getenv("HP_TEST_SOUND")) { for (int i = 15; i + 1 < nargs; i++) args[i] = args[i + 1]; nargs--; }
    /* HP_TEST_ARGS: extra engine options, e.g. "--ui-scale 240 --inset-left 80". */
    char *extra = getenv("HP_TEST_ARGS") ? strdup(getenv("HP_TEST_ARGS")) : NULL, *sv = NULL;
    if (extra)
        for (char *t = strtok_r(extra, " ", &sv); t && nargs < 63; t = strtok_r(NULL, " ", &sv)) args[nargs++] = t;
    const char *script = getenv("HP_TEST_SCRIPT");
    if (script && *script) {
        pthread_t th2;
        if (pthread_create(&th2, NULL, script_thread, strdup(script)) == 0) pthread_detach(th2);
    }
    const char *rs = getenv("HP_TEST_RESTORE");
    if (rs && *rs) {
        pthread_t th;
        if (pthread_create(&th, NULL, restore_thread, strdup(rs)) == 0) pthread_detach(th);
    }
    int rc = hwengine_RunEngine(nargs, args);
    fprintf(stderr, "engine returned %d\n", rc);
    hp_ipc_join();
    return rc;
}
