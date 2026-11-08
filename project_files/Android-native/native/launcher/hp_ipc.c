#include "hp.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGI(...) (fprintf(stderr, "[HedgeParty] " __VA_ARGS__), fputc('\n', stderr))
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

#define TAG "HedgeParty"

static int g_listen_fd = -1;
static char g_config_path[1024];
static pthread_t g_thread;
static int g_thread_started;

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
        LOGE("accept failed: %s", strerror(errno));
        return NULL;
    }
    LOGI("engine connected");
    for (;;) {
        unsigned char len;
        unsigned char msg[256];
        if (read_full(fd, &len, 1)) break;
        if (len && read_full(fd, msg, len)) break;
        if (len && msg[0] == 'C') {
            /* The engine asks for the game configuration. */
            if (send_config(fd)) break;
        }
        /* Everything else (ticks, stats, resize notices) is only drained. */
    }
    LOGI("engine closed the IPC socket");
    close(fd);
    return NULL;
}

int hp_ipc_start(const char *config_path)
{
    struct sockaddr_in addr;
    socklen_t alen = sizeof addr;
    snprintf(g_config_path, sizeof g_config_path, "%s", config_path);
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
        /* Unblock accept() if the engine never connected. */
        shutdown(g_listen_fd, SHUT_RDWR);
        pthread_join(g_thread, NULL);
        g_thread_started = 0;
    }
    if (g_listen_fd >= 0) close(g_listen_fd);
    g_listen_fd = -1;
}
