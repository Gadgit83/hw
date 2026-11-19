/* Android launcher: in-process stand-in for the Qt frontend.
 *
 * The engine is a client of a local IPC socket. It connects, asks for the game
 * configuration with a 'C' message and then plays. hp_ipc serves that socket from a
 * thread of the same process and answers with the lines of a plain text file, one
 * engine message per line (the same messages the Qt frontend sends, see
 * project_files/Android-native/README.md). Anything that can write that file, such as the Java setup screen
 * today or a network hub later, can start a game. */
#pragma once

/* Start the server on 127.0.0.1 (ephemeral port). Returns the port or -1. */
int hp_ipc_start(const char *config_path);
/* Wait for the server thread to finish (the engine has closed the socket). */
void hp_ipc_join(void);

/* Called from the server thread when the engine reports a fatal error ('E' message). The text
 * is NUL terminated and valid only during the call. Optional; the desktop test leaves it unset. */
void hp_ipc_set_error_handler(void (*handler)(const char *message));
/* Nonzero when the relay ended the game because the host ended it: a normal end that is not an engine error. */
int hp_ipc_relay_ended(void);

/* The player confirmed leaving the game: tells the relay (control frame Q) and makes the engine quit whatever its UI state.
 * Returns 0 when the engine was asked to quit. */
int hp_ipc_user_quit(void);
/* The host phone only: ends the game for everyone (control frame Z), then quits the engine. */
int hp_ipc_end_for_everyone(void);
/* A network game: the app is in the background (hidden != 0, control frame H) or in front again (0, control frame V). */
int hp_ipc_hidden(int hidden);

/* The pause screen's buttons (--pause-menu): called from the server thread with 'q' (Quit game) or 'o' (Options). Optional. */
void hp_ipc_set_pause_menu_handler(void (*handler)(int action));
/* The pause of a network game, from the server thread: HP_PAUSE_ASKED_* when this (host) phone asks for it, HP_PAUSE_APPLIED_* when the
 * relay's pause or resume reaches this phone's engine. Optional. */
#define HP_PAUSE_ASKED_ON 1
#define HP_PAUSE_ASKED_OFF 2
#define HP_PAUSE_APPLIED_ON 3
#define HP_PAUSE_APPLIED_OFF 4
void hp_ipc_set_pause_status_handler(void (*handler)(int state));
/* The controls editor (--touch-layout): called from the server thread for each control's place the engine reports (IPC 'T', e.g.
 * "fire:0.920,0.850,1.000,0.120,0.210"). entry is not NUL-terminated; len is its length. Optional. */
void hp_ipc_set_touch_layout_handler(void (*handler)(const char *entry, int len));
/* Sends one console command to the engine, e.g. "livevolume 128 100 50 0". Returns 0 when it was written. */
int hp_ipc_engine_command(const char *cmd);

#ifdef HP_HOST_TEST
/* Test hook for the host test: send one message to the engine, e.g. "Fteamname". Returns 0 when sent. */
int hp_ipc_send(const char *payload);
void hp_ipc_send_control(char kind);
int hp_ipc_send_relay(const char *payload);
#endif
/* Network mode. Reads the relay link (host, port, token, nick, diag) from a small key=value file. Call before
 * hp_ipc_start; returns 0 when the file is valid, and the launcher then joins the game through the relay
 * (see hp_ipc.c). Returns -1 when there is no such file: a local game. */
int hp_ipc_net_config(const char *path);
/* The player name from that file (for --nick), or NULL in a local game. */
const char *hp_ipc_net_nick(void);
/* The same name in base64, which is how the engine started with --internal wants --nick; NULL in a local game. */
const char *hp_ipc_net_nick_arg(void);
