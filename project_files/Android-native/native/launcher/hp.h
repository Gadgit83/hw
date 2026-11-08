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
