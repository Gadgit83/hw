#include "hp.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGI(...) (fprintf(stderr, "[HWNative] " __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) LOGI(__VA_ARGS__)
#endif
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define TAG "HWNative"

static int g_listen_fd = -1;
static char g_config_path[1024];
static pthread_t g_thread;
static int g_thread_started;
/* The accepted engine socket, or -1. Guarded by g_fd_lock so hp_ipc_join can shut it down safely. */
static int g_client_fd = -1;
/* Set by hp_ipc_join so a connection accepted during shutdown is closed rather than served. */
static int g_stopping;
static pthread_mutex_t g_fd_lock = PTHREAD_MUTEX_INITIALIZER;
static void (*g_error_handler)(const char *message);
/* Game commands the engine reports to the frontend (it records them for demos): counted per command
 * letter, and the notable ones are logged, which shows that a computer player took a turn. */
static unsigned long g_cmd_count[256];
static int g_cmd_logged;

void hp_ipc_set_error_handler(void (*handler)(const char *message))
{
    g_error_handler = handler;
}

static int read_full(int fd, void *buf, size_t n)
{
    unsigned char *p = buf;
    while (n > 0) {
        ssize_t r = recv(fd, p, n, 0);
        if (r == 0) return -1;
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

static int write_full(int fd, const void *buf, size_t n)
{
    const unsigned char *p = buf;
    while (n > 0) {
        ssize_t r = send(fd, p, n, MSG_NOSIGNAL);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

/* Send every non-empty line of the config file as one length-prefixed message. */
static int send_config(int fd)
{
    FILE *f = fopen(g_config_path, "r");
    char line[512];
    int count = 0;
    if (!f) {
        LOGE("cannot open %s: %s", g_config_path, strerror(errno));
        return -1;
    }
    while (fgets(line, sizeof line, f)) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = 0;
        if (len == 0 || line[0] == '#') continue;
        if (len > 255) {
            LOGE("config line too long (%zu bytes), skipped", len);
            continue;
        }
        unsigned char l = (unsigned char)len;
        if (write_full(fd, &l, 1) || write_full(fd, line, len)) {
            fclose(f);
            return -1;
        }
        count++;
    }
    fclose(f);
    LOGI("sent %d config messages", count);
    return 0;
}

static void *server_thread(void *arg)
{
    (void)arg;
    int fd = accept(g_listen_fd, NULL, NULL);
    if (fd < 0) {
        pthread_mutex_lock(&g_fd_lock);
        int stopping = g_stopping;
        pthread_mutex_unlock(&g_fd_lock);
        if (!stopping) LOGE("accept failed: %s", strerror(errno));
        return NULL;
    }
    pthread_mutex_lock(&g_fd_lock);
    if (g_stopping) {
        pthread_mutex_unlock(&g_fd_lock);
        close(fd);
        return NULL;
    }
    g_client_fd = fd;
    pthread_mutex_unlock(&g_fd_lock);
    LOGI("engine connected");
    for (;;) {
        unsigned char len;
        unsigned char msg[256];
        if (read_full(fd, &len, 1)) break;
        if (len && read_full(fd, msg, len)) break;
        if (len && msg[0] == 'C') {
            /* The engine asks for the game configuration. */
            if (send_config(fd)) break;
        } else if (len && msg[0] == '?') {
            /* The engine asks a question; answer with '!' as the Qt frontend does. */
            static const unsigned char ok[2] = { 1, '!' };
            if (write_full(fd, ok, sizeof ok)) break;
        } else if (len > 1 && msg[0] == 'E') {
            /* The engine reports a fatal error and is about to exit the process. */
            char text[256];
            memcpy(text, msg + 1, (size_t)len - 1);
            text[len - 1] = 0;
            LOGE("engine error: %s", text);
            if (g_error_handler) g_error_handler(text);
        }
        else if (len >= 3) {
            unsigned char c = msg[0];
            g_cmd_count[c]++;
            /* The last two bytes of a game command are the low half of the game tick. */
            unsigned tick = ((unsigned)msg[len - 2] << 8) | msg[len - 1];
            if (g_cmd_logged < 300 && (c == 'A' || c == 'a' || c == 'N' || c == 'S' || c == 'j' || c == 'J' || c >= 128)) {
                g_cmd_logged++;
                if (c >= 128) LOGI("engine command weapon slot %d tick %u", c - 128, tick);
                else LOGI("engine command '%c' (%s) tick %u", c,
                          c == 'A' ? "attack start" : c == 'a' ? "attack release" : c == 'N' ? "turn ends" :
                          c == 'S' ? "switch hedgehog" : c == 'j' ? "jump" : "high jump", tick);
            }
        }
        /* Everything else (ticks, stats, resize notices) is only drained. */
    }
    {
        char sum[600];
        size_t used = 0;
        sum[0] = 0;
        for (int i = 0; i < 256 && used < sizeof sum - 24; i++) {
            if (!g_cmd_count[i] || i == '+') continue;
            used += (size_t)snprintf(sum + used, sizeof sum - used, i >= 128 ? " slot%d=%lu" : " %c=%lu",
                                     i >= 128 ? i - 128 : i, g_cmd_count[i]);
        }
        LOGI("engine commands seen:%s", used ? sum : " none");
    }
    LOGI("engine closed the IPC socket");
    pthread_mutex_lock(&g_fd_lock);
    g_client_fd = -1;
    pthread_mutex_unlock(&g_fd_lock);
    close(fd);
    return NULL;
}

/* Test hook (host test script action "ipc:<text>"): sends one message to the engine as the frontend would, e.g. "Fteamname"
 * (a team left, the engine runs "teamgone u..."). Returns 0 when sent. */
int hp_ipc_send(const char *payload)
{
    size_t n = strlen(payload);
    int rc = -1;
    if (n == 0 || n > 255) return -1;
    pthread_mutex_lock(&g_fd_lock);
    if (g_client_fd >= 0) {
        unsigned char len = (unsigned char)n;
        rc = (write_full(g_client_fd, &len, 1) == 0 && write_full(g_client_fd, payload, n) == 0) ? 0 : -1;
    }
    pthread_mutex_unlock(&g_fd_lock);
    return rc;
}

int hp_ipc_start(const char *config_path)
{
    struct sockaddr_in addr;
    socklen_t alen = sizeof addr;
    snprintf(g_config_path, sizeof g_config_path, "%s", config_path);
    pthread_mutex_lock(&g_fd_lock);
    g_stopping = 0;
    pthread_mutex_unlock(&g_fd_lock);
    g_listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (g_listen_fd < 0) return -1;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (bind(g_listen_fd, (struct sockaddr *)&addr, sizeof addr) || listen(g_listen_fd, 1) ||
        getsockname(g_listen_fd, (struct sockaddr *)&addr, &alen)) {
        LOGE("ipc socket setup failed: %s", strerror(errno));
        close(g_listen_fd);
        return -1;
    }
    if (pthread_create(&g_thread, NULL, server_thread, NULL)) return -1;
    g_thread_started = 1;
    return ntohs(addr.sin_port);
}

void hp_ipc_join(void)
{
    if (g_thread_started) {
        /* Mark stopping first, so a connection accepted from now on is closed, not served. */
        pthread_mutex_lock(&g_fd_lock);
        g_stopping = 1;
        /* Unblock recv() if the engine is still connected and keeps its socket open. */
        if (g_client_fd >= 0) shutdown(g_client_fd, SHUT_RDWR);
        pthread_mutex_unlock(&g_fd_lock);
        /* Unblock accept() if the engine never connected. */
        shutdown(g_listen_fd, SHUT_RDWR);
        pthread_join(g_thread, NULL);
        g_thread_started = 0;
    }
    if (g_listen_fd >= 0) close(g_listen_fd);
    g_listen_fd = -1;
}
