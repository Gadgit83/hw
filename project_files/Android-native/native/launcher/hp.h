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

/* The player confirmed leaving the game: tells the relay (control frame Q) and makes the engine quit whatever its UI state.
 * Returns 0 when the engine was asked to quit. */
int hp_ipc_user_quit(void);

#ifdef HP_HOST_TEST
/* Test hook for the host test: send one message to the engine, e.g. "Fteamname". Returns 0 when sent. */
int hp_ipc_send(const char *payload);
#endif
/* Network mode. Reads the relay link (host, port, token, nick, diag) from a small key=value file. Call before
 * hp_ipc_start; returns 0 when the file is valid, and the launcher then joins the game through the relay
 * (see hp_ipc.c). Returns -1 when there is no such file: a local game. */
int hp_ipc_net_config(const char *path);
/* The player name from that file (for --nick), or NULL in a local game. */
const char *hp_ipc_net_nick(void);
