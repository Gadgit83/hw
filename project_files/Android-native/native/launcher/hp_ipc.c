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
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <fcntl.h>
#include <sys/time.h>
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


/* ---------------------------------------------------------------------------------------------
 * Network mode (docs/tv-viewer-plan.md section 2.8). The engine still talks to this launcher over the local
 * IPC socket. In network mode the launcher also holds a TCP link to the relay (the host phone's service):
 *  - the IPC reader thread copies every engine message into a queue and never blocks on the network;
 *  - a sender thread writes the queue to the relay;
 *  - a downstream thread reads the relay's frames (the same one-byte length framing as the IPC) and
 *    writes them to the engine socket under a lock.
 * The relay filters and forwards; this side only taps. It also records, in a diagnostics file, every
 * turn checksum (N) and map digest (M) that crosses the link in either direction, so that two phones'
 * sequences can be compared.
 * ------------------------------------------------------------------------------------------- */
#define HP_NET_FLUSH_DEFAULT_MS 100   /* see net_flush note in CHANGELOG 0.3.0-tv5b; the env var HP_NET_FLUSH_MS overrides it (harness) */
static int g_net;
static char g_net_host[128], g_net_token[160], g_net_nick[64] = "Player", g_net_diag[1024];
static int g_net_port;
static int g_net_is_host;                 /* net.cfg says role=host: this phone's engine may ask for a pause (see net_pause_request) */
static int g_net_is_watch;                /* net.cfg says role=watch: this phone only watches; nothing from its engine goes to the relay (see net_tap) */
static unsigned long g_watch_blocked;     /* engine messages a watching phone's launcher did not send */
static int g_net_pending;                 /* config sent, connect to the relay after the engine's next '?' */
static int g_relay_fd = -1;
static volatile int g_net_up, g_net_ending, g_net_lost;
static pthread_mutex_t g_wr_lock = PTHREAD_MUTEX_INITIALIZER;   /* all writes to the engine socket */
static pthread_mutex_t g_relay_wr_lock = PTHREAD_MUTEX_INITIALIZER;   /* all writes to the relay link: sender thread and control frames */
static int g_engine_fd = -1;

struct qnode { struct qnode *next; unsigned char len; unsigned char data[255]; };
static struct qnode *g_q_head, *g_q_tail;
static unsigned long g_q_count;
static int g_q_closed;
static pthread_mutex_t g_q_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_q_cond = PTHREAD_COND_INITIALIZER;
static pthread_t g_send_thread, g_down_thread;
static int g_send_started, g_down_started;

static unsigned long g_tx_msgs, g_rx_msgs, g_tx_dropped_local;
static unsigned long g_tx_type[256], g_rx_type[256];
static uint64_t g_tx_fnv = 0xcbf29ce484222325ULL, g_rx_fnv = 0xcbf29ce484222325ULL;
static int g_turn_seq;
/* Timing of the engine's empty '+' time marks as they leave the phone (wall clock), and bytes sent: the figures for the flush interval. */
static unsigned long g_tx_bytes, g_plus_n, g_plus_bucket[8];   /* buckets: <=25 <=50 <=75 <=100 <=250 <=500 <=1000 >1000 ms */
static unsigned g_plus_max_ms;
static int g_net_trace;                   /* HP_NET_TRACE set: log every sent message's type and game-time stamp (harness measurements) */
static struct timespec g_plus_last;
static FILE *g_diag;
static struct timespec g_t0;
static pthread_mutex_t g_diag_lock = PTHREAD_MUTEX_INITIALIZER;

static void fnv_add(uint64_t *h, const unsigned char *b, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        *h ^= b[i];
        *h *= 0x100000001b3ULL;
    }
}

static void diag(const char *fmt, ...)
{
    if (!g_diag) return;
    struct timespec t;
    va_list ap;
    clock_gettime(CLOCK_MONOTONIC, &t);
    long ms = (long)((t.tv_sec - g_t0.tv_sec) * 1000 + (t.tv_nsec - g_t0.tv_nsec) / 1000000);
    pthread_mutex_lock(&g_diag_lock);
    fprintf(g_diag, "%7ld ", ms);
    va_start(ap, fmt);
    vfprintf(g_diag, fmt, ap);
    va_end(ap);
    fputc('\n', g_diag);
    fflush(g_diag);
    pthread_mutex_unlock(&g_diag_lock);
}

int hp_ipc_net_config(const char *path)
{
    FILE *f = fopen(path, "r");
    char line[400];
    if (!f) return -1;
    while (fgets(line, sizeof line, f)) {
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq++ = 0;
        if (!strcmp(line, "host")) snprintf(g_net_host, sizeof g_net_host, "%s", eq);
        else if (!strcmp(line, "port")) g_net_port = atoi(eq);
        else if (!strcmp(line, "token")) snprintf(g_net_token, sizeof g_net_token, "%s", eq);
        else if (!strcmp(line, "nick") && *eq) snprintf(g_net_nick, sizeof g_net_nick, "%s", eq);
        else if (!strcmp(line, "diag")) snprintf(g_net_diag, sizeof g_net_diag, "%s", eq);
        else if (!strcmp(line, "role")) { g_net_is_host = !strcmp(eq, "host"); g_net_is_watch = !strcmp(eq, "watch"); }
    }
    fclose(f);
    if (!g_net_host[0] || g_net_port <= 0 || !g_net_token[0]) return -1;
    g_net = 1;
    return 0;
}

const char *hp_ipc_net_nick(void)
{
    return g_net ? g_net_nick : NULL;
}

static void engine_write(const void *buf, size_t n)
{
    pthread_mutex_lock(&g_wr_lock);
    if (g_engine_fd >= 0) write_full(g_engine_fd, buf, n);
    pthread_mutex_unlock(&g_wr_lock);
}

/* The relay link is gone or the relay ended the game: tell the user and make the engine stop. */
static void net_lost(const char *reason)
{
    if (g_net_ending || __sync_lock_test_and_set(&g_net_lost, 1)) return;
    LOGE("relay: %s", reason);
    diag("LINK %s", reason);
    if (g_error_handler) g_error_handler(reason);
    pthread_mutex_lock(&g_fd_lock);
    if (g_client_fd >= 0) shutdown(g_client_fd, SHUT_RDWR);
    pthread_mutex_unlock(&g_fd_lock);
}

static void note_digest(const char *dir, const unsigned char *m, int len)
{
    if (m[0] == 'N') {
        if (len >= 7) {
            unsigned tick = ((unsigned)m[5] << 8) | m[6];
            int seq = __sync_add_and_fetch(&g_turn_seq, 1);
            diag("N %s turn %d checksum %02x%02x%02x%02x tick %u", dir, seq, m[1], m[2], m[3], m[4], tick);
        } else {
            diag("N %s final (no checksum, %d bytes)", dir, len);
        }
    } else if (m[0] == 'M') {
        char t[160];
        int k = len - 1 < 150 ? len - 1 : 150;
        memcpy(t, m + 1, (size_t)k);
        t[k] = 0;
        for (int i = 0; i < k; i++) if ((unsigned char)t[i] < 32 || (unsigned char)t[i] > 126) t[i] = '.';
        diag("M %s map digest %s", dir, t);
    } else if (m[0] == 'F' || m[0] == 'G') {
        char t[160];
        int k = len - 1 < 150 ? len - 1 : 150;
        memcpy(t, m + 1, (size_t)k);
        t[k] = 0;
        diag("%c %s team %s (unsynced)", m[0], dir, t);
    } else if (m[0] == 'f' || m[0] == 'g') {
        char t[160];
        int k = len - 3 < 150 ? len - 3 : 150;
        if (k < 0) k = 0;
        memcpy(t, m + 1, (size_t)k);
        t[k] = 0;
        diag("%c %s team %s (synced)", m[0], dir, t);
    }
}

static int g_tx_delay_done;

static void *send_thread(void *arg)
{
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&g_q_lock);
        while (!g_q_head && !g_q_closed) pthread_cond_wait(&g_q_cond, &g_q_lock);
        struct qnode *n = g_q_head;
        if (n) {
            g_q_head = n->next;
            if (!g_q_head) g_q_tail = NULL;
            g_q_count--;
        }
        pthread_mutex_unlock(&g_q_lock);
        if (!n) break;                                        /* closed and drained */
        unsigned char buf[256];
        buf[0] = n->len;
        memcpy(buf + 1, n->data, n->len);
        if (!g_tx_delay_done) {
            /* Test hook (HP_NET_TX_DELAY_MS, set only by the host tests): hold this phone's first message back, to
               imitate a phone that is slow to finish generating the map. */
            g_tx_delay_done = 1;
            const char *d = getenv("HP_NET_TX_DELAY_MS");
            if (d && atoi(d) > 0) usleep((useconds_t)atoi(d) * 1000u);
        }
        int bad = 0;
        pthread_mutex_lock(&g_relay_wr_lock);
        if (!g_net_lost) bad = write_full(g_relay_fd, buf, (size_t)n->len + 1);
        pthread_mutex_unlock(&g_relay_wr_lock);
        if (bad) net_lost("Lost the connection to the host (send failed).");
        free(n);
    }
    return NULL;
}

/* Called on the IPC reader thread for every engine message: only appends to the queue. */
static void net_tap(const unsigned char *msg, unsigned len)
{
    if (!g_net_up || g_net_lost) return;
    if (g_net_is_watch && !(len == 1 && msg[0] == 'q')) {
        /* A watching phone has no team: its engine has nothing to say to the game, so nothing is sent (the relay would ignore it
         * all anyway). The one exception is the engine's own "game over" (q), which only tells the relay this phone has seen the end. */
        if (++g_watch_blocked <= 5) diag("WATCH not sent to the relay: message '%c' (%u bytes)", msg[0] >= 32 && msg[0] < 127 ? msg[0] : '?', len);
        return;
    }
    g_tx_msgs++;
    g_tx_type[msg[0]]++;
    g_tx_bytes += len + 1;
    if (g_diag && g_net_trace && len >= 3) diag("TX %c %u", msg[0] >= 32 && msg[0] < 127 ? msg[0] : '?', (unsigned)(msg[len - 2] << 8 | msg[len - 1]));   /* measurement only */
    if (msg[0] == '+') {
        struct timespec t;
        clock_gettime(CLOCK_MONOTONIC, &t);
        if (g_plus_n++) {
            long ms = (t.tv_sec - g_plus_last.tv_sec) * 1000 + (t.tv_nsec - g_plus_last.tv_nsec) / 1000000;
            static const long lim[7] = { 25, 50, 75, 100, 250, 500, 1000 };
            int b = 0;
            while (b < 7 && ms > lim[b]) b++;
            g_plus_bucket[b]++;
            if (ms > (long)g_plus_max_ms) g_plus_max_ms = (unsigned)ms;
        }
        g_plus_last = t;
    }
    unsigned char f = (unsigned char)len;
    fnv_add(&g_tx_fnv, &f, 1);
    fnv_add(&g_tx_fnv, msg, len);
    note_digest("tx", msg, (int)len);
    struct qnode *n = malloc(sizeof *n);
    if (!n) return;
    n->next = NULL;
    n->len = (unsigned char)len;
    memcpy(n->data, msg, len);
    pthread_mutex_lock(&g_q_lock);
    if (g_q_count > 200000) {                                  /* the relay is not taking data: give up */
        pthread_mutex_unlock(&g_q_lock);
        free(n);
        g_tx_dropped_local++;
        net_lost("The host is not accepting data.");
        return;
    }
    if (g_q_tail) g_q_tail->next = n; else g_q_head = n;
    g_q_tail = n;
    g_q_count++;
    pthread_cond_signal(&g_q_cond);
    pthread_mutex_unlock(&g_q_lock);
}

/* A control frame is the length byte, a 0, a kind letter and text; sent at once because the process may be about to end. */
static void net_send_control(char kind, const char *text)
{
    if (!g_net_up || g_relay_fd < 0) return;
    unsigned char buf[256];
    size_t tl = strlen(text);
    if (tl > 250) tl = 250;
    buf[0] = (unsigned char)(tl + 2);
    buf[1] = 0;
    buf[2] = (unsigned char)kind;
    memcpy(buf + 3, text, tl);
    /* Behind the queue, in order: let the sender catch up briefly, then write. */
    for (int i = 0; i < 100; i++) {
        pthread_mutex_lock(&g_q_lock);
        int empty = g_q_head == NULL;
        pthread_mutex_unlock(&g_q_lock);
        if (empty) break;
        usleep(5000);
    }
    pthread_mutex_lock(&g_relay_wr_lock);              /* one writer at a time on the link: never in the middle of a frame the sender thread is writing */
    write_full(g_relay_fd, buf, tl + 3);
    pthread_mutex_unlock(&g_relay_wr_lock);
    diag("CONTROL %c %s", kind, text);
}

/* The relay says the host paused (P) or resumed (R): tell this phone's own engine through the trusted local IPC ('e' command;
 * the relay's engine whitelist never lets another phone's message become a command). If the engine is not connected yet the
 * pause is simply not applied: nothing moves before every engine is up, so nothing can desync. */
static void net_apply_pause(int pause)
{
    unsigned char frame[16];
    const char cmd[] = "enetpause 0";
    size_t n = sizeof cmd - 1;
    frame[0] = (unsigned char)n;
    memcpy(frame + 1, cmd, n);
    frame[n] = pause ? '1' : '0';
    /* Test hook (HP_NET_PAUSE_JITTER_MS, set only by the host tests): apply pause and resume this late, so that the engines of one
       game pause and resume at different wall-clock moments. */
    const char *j = getenv("HP_NET_PAUSE_JITTER_MS");
    if (j && atoi(j) > 0) usleep((useconds_t)atoi(j) * 1000u);
    diag("PAUSE %s", pause ? "on (the host paused)" : "off (resumed)");
    engine_write(frame, n + 1);
}

/* The engine (host phone only, touch on the pause button) asks for a pause or resume: message 'y' + '1' or '0'. Not an engine
 * game message: it is not tapped to the relay as one; it goes as the control frame P / R, which the relay accepts from the
 * host's link only. */
static void net_pause_request(int pause)
{
    if (!g_net_is_host) return;
    net_send_control(pause ? 'P' : 'R', "");
}

/* What the relay may send to this engine, besides its own control frames (first byte 0): the types the relay itself lets through
 * (Frames.java WHITELIST "M#+LlRrUuDdZzAaSjJ,NpPwtgfhbc12345" and 0x80..0x8A) and F, the relay's "team gone" for a dropped phone.
 * Anything else, above all 'e' (a local engine command: it would let a hostile or broken host or relay run console commands on this
 * phone's engine), is dropped before it reaches the engine. Local commands come only from this launcher and the app's own files. */
int hp_ipc_relay_frame_ok(unsigned char type)
{
    static const char allowed[] = "M#+LlRrUuDdZzAaSjJ,NpPwtgfhbc12345F";
    if (type >= 0x80 && type <= 0x8A) return 1;
    return type != 0 && type < 0x80 && strchr(allowed, type) != NULL;
}

static unsigned long g_rx_dropped;
static void *down_thread(void *arg)
{
    (void)arg;
    for (;;) {
        unsigned char len, msg[256];
        if (read_full(g_relay_fd, &len, 1)) break;
        if (len && read_full(g_relay_fd, msg, len)) break;
        if (!len) continue;
        if (msg[0] == 0) {
            if (len > 2 && msg[1] == 'X') {
                char t[260];
                memcpy(t, msg + 2, (size_t)len - 2);
                t[len - 2] = 0;
                char m[300];
                /* The relay's reason is shown to the player: "The host ended the game" is a sentence of its own, anything else
                 * (a desync, every engine gone) follows "The game ended:". */
                if (!strncmp(t, "The host ended the game", 23)) snprintf(m, sizeof m, "%s", t);
                else snprintf(m, sizeof m, "The game ended: %s", t);
                net_lost(m);
            }
            if (len == 2 && (msg[1] == 'P' || msg[1] == 'R')) net_apply_pause(msg[1] == 'P');
            /* The relay's link ping: answered here, on this thread, so it works while the engine generates a map or is paused. */
            if (len == 2 && msg[1] == 'K') {
                unsigned char k[3] = { 2, 0, 'K' };                    /* straight onto the link: no waiting behind the queue, no log line every 5 s */
                pthread_mutex_lock(&g_relay_wr_lock);
                write_full(g_relay_fd, k, sizeof k);
                pthread_mutex_unlock(&g_relay_wr_lock);
            }
            continue;
        }
        if (!hp_ipc_relay_frame_ok(msg[0])) {
            if (++g_rx_dropped <= 10) diag("RX DROPPED a relay message of type 0x%02x ('%c', %u bytes): not on the whitelist", msg[0], msg[0] >= 32 && msg[0] < 127 ? msg[0] : '?', (unsigned)len);
            continue;
        }
        unsigned char frame[257];
        frame[0] = len;
        memcpy(frame + 1, msg, len);
        g_rx_msgs++;
        g_rx_type[msg[0]]++;
        fnv_add(&g_rx_fnv, frame, (size_t)len + 1);
        note_digest("rx", msg, len);
        engine_write(frame, (size_t)len + 1);
    }
    if (!g_net_ending) net_lost("The connection to the host was lost.");
    return NULL;
}

static int net_connect(void)
{
    struct addrinfo hints, *res = NULL;
    char portstr[16], line[600];
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    snprintf(portstr, sizeof portstr, "%d", g_net_port);
    clock_gettime(CLOCK_MONOTONIC, &g_t0);
    if (g_net_diag[0]) g_diag = fopen(g_net_diag, "w");
    g_net_trace = getenv("HP_NET_TRACE") != NULL;
    diag("engine link to %s:%d as '%s'", g_net_host, g_net_port, g_net_nick);
    if (getaddrinfo(g_net_host, portstr, &hints, &res) || !res) {
        diag("LINK cannot resolve %s", g_net_host);
        return -1;
    }
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { freeaddrinfo(res); return -1; }
    fcntl(fd, F_SETFL, O_NONBLOCK);
    int rc = connect(fd, res->ai_addr, res->ai_addrlen);
    freeaddrinfo(res);
    if (rc < 0 && errno == EINPROGRESS) {
        struct pollfd p = { fd, POLLOUT, 0 };
        int err = 0;
        socklen_t el = sizeof err;
        if (poll(&p, 1, 8000) <= 0 || getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el) || err) {
            diag("LINK connect failed or timed out");
            close(fd);
            return -1;
        }
    } else if (rc < 0) {
        diag("LINK connect failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    fcntl(fd, F_SETFL, 0);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof one);
    struct timeval tv = { 10, 0 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    int n = snprintf(line, sizeof line, "{\"t\":\"hello\",\"role\":\"engine\",\"token\":\"%s\"}\n", g_net_token);
    if (write_full(fd, line, (size_t)n)) { close(fd); return -1; }
    /* reply line, byte by byte so no binary data is read */
    size_t used = 0;
    for (;;) {
        char c;
        ssize_t r = recv(fd, &c, 1, 0);
        if (r <= 0 || used >= sizeof line - 1) { close(fd); diag("LINK no reply from the host"); return -1; }
        if (c == '\n') break;
        line[used++] = c;
    }
    line[used] = 0;
    if (!strstr(line, "\"t\":\"ok\"")) {
        diag("LINK refused: %s", line);
        const char *rs = strstr(line, "\"reason\":\"");
        static char why[300];
        snprintf(why, sizeof why, "The host refused the game link: %s", rs ? rs + 10 : line);
        char *q = strchr(why, '"');
        if (q) *q = 0;
        if (g_error_handler) g_error_handler(why);
        close(fd);
        return -1;
    }
    /* The relay pings every 5 s once the game runs; a link that has said nothing for 60 s (the relay waits up to 45 s for the other
     * phones before it opens the gate, and does not ping until then) is half open: the host is gone without a close. recv then fails
     * and the down thread reports "The connection to the host was lost." */
    tv.tv_sec = 60;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    g_relay_fd = fd;
    g_net_up = 1;
    diag("engine link up: %s", line);
    if (pthread_create(&g_send_thread, NULL, send_thread, NULL) == 0) g_send_started = 1;
    if (pthread_create(&g_down_thread, NULL, down_thread, NULL) == 0) g_down_started = 1;
    return 0;
}

/* The engine has closed the IPC socket: let the sender drain, then close the link and write the summary. */
static void net_finish(void)
{
    if (!g_net) return;
    g_net_ending = 1;
    if (g_send_started) {
        pthread_mutex_lock(&g_q_lock);
        g_q_closed = 1;
        pthread_cond_broadcast(&g_q_cond);
        pthread_mutex_unlock(&g_q_lock);
        pthread_join(g_send_thread, NULL);
        g_send_started = 0;
    }
    if (g_relay_fd >= 0) shutdown(g_relay_fd, SHUT_RDWR);
    if (g_down_started) {
        pthread_join(g_down_thread, NULL);
        g_down_started = 0;
    }
    if (g_relay_fd >= 0) close(g_relay_fd);
    g_relay_fd = -1;
    g_net_up = 0;
    if (g_diag) {
        char types[400];
        size_t u = 0;
        types[0] = 0;
        for (int i = 0; i < 256 && u < sizeof types - 30; i++) {
            if (g_tx_type[i] || g_rx_type[i])
                u += (size_t)snprintf(types + u, sizeof types - u, " %c:%lu/%lu", i >= 32 && i < 127 ? i : '?', g_tx_type[i], g_rx_type[i]);
        }
        diag("PLUSGAPS %lu '+' messages, %lu bytes sent in all, gap ms buckets <=25:%lu <=50:%lu <=75:%lu <=100:%lu <=250:%lu <=500:%lu <=1000:%lu >1000:%lu, longest %u ms",
             g_plus_n, g_tx_bytes, g_plus_bucket[0], g_plus_bucket[1], g_plus_bucket[2], g_plus_bucket[3], g_plus_bucket[4],
             g_plus_bucket[5], g_plus_bucket[6], g_plus_bucket[7], g_plus_max_ms);
        if (g_net_is_watch) diag("WATCH engine messages not sent: %lu", g_watch_blocked);
        diag("RX DROPPED by the whitelist: %lu", g_rx_dropped);
        diag("SUMMARY tx %lu messages hash %016llx, rx %lu messages hash %016llx, turns %d, by type tx/rx:%s",
             g_tx_msgs, (unsigned long long)g_tx_fnv, g_rx_msgs, (unsigned long long)g_rx_fnv, g_turn_seq, types);
        fclose(g_diag);
        g_diag = NULL;
    }
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
            /* A skipped line would make this phone's game differ from the others'; in a network game stop instead. */
            LOGE("config line too long (%zu bytes)%s", len, g_net ? ", game stopped" : ", skipped");
            if (g_net) {
                fclose(f);
                if (g_error_handler) g_error_handler("A line of the game setup is longer than 255 bytes (see the log).");
                return -1;
            }
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
    if (g_net && g_net_is_watch) {
        /* Tells the engine this phone only watches: it hides the playing controls (touch widgets, weapon menu). Local IPC, from the app's own net.cfg only. */
        static const unsigned char watchmsg[] = { 9, 'e', 'n', 'e', 't', 'w', 'a', 't', 'c', 'h' };
        if (write_full(fd, watchmsg, sizeof watchmsg)) return -1;
    }
    if (g_net && g_net_is_host) {
        /* Tells the engine this phone is the host (shows the pause button). Written by the launcher from the app's own net.cfg only. */
        static const unsigned char hostmsg[] = { 8, 'e', 'n', 'e', 't', 'h', 'o', 's', 't' };
        if (write_full(fd, hostmsg, sizeof hostmsg)) return -1;
    }
    if (g_net) {
        /* Network games only: how often this engine sends its empty '+' time mark and flushes its buffered messages
         * (game ms). The engine default is 1000, which made every viewer wait up to a second for the next time mark. */
        int ms = HP_NET_FLUSH_DEFAULT_MS;
        const char *e = getenv("HP_NET_FLUSH_MS");
        if (e && *e) ms = atoi(e);
        if (ms >= 10 && ms < 1000) {
            unsigned char fm[24];
            int fl = snprintf((char *)fm + 1, sizeof fm - 1, "enetflush %d", ms);
            fm[0] = (unsigned char)fl;
            if (write_full(fd, fm, (size_t)fl + 1)) return -1;
            LOGI("network flush interval %d ms", ms);
        }
    }
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
    pthread_mutex_lock(&g_wr_lock);
    g_engine_fd = fd;
    pthread_mutex_unlock(&g_wr_lock);
    LOGI("engine connected%s", g_net ? " (network mode)" : "");
    for (;;) {
        unsigned char len;
        unsigned char msg[256];
        if (read_full(fd, &len, 1)) break;
        if (len && read_full(fd, msg, len)) break;
        /* Network mode: tap every engine message (right after the read) into the queue for the relay link. */
        if (g_net && len && msg[0] != 'C' && msg[0] != '?' && msg[0] != 'y') net_tap(msg, len);
        if (len && msg[0] == 'C') {
            /* The engine asks for the game configuration. */
            if (send_config(fd)) break;
            if (g_net) g_net_pending = 1;
        } else if (len && msg[0] == '?') {
            /* The engine asks a question; answer with '!' as the Qt frontend does. */
            static const unsigned char ok[2] = { 1, '!' };
            pthread_mutex_lock(&g_wr_lock);
            int bad = write_full(fd, ok, sizeof ok);
            pthread_mutex_unlock(&g_wr_lock);
            if (bad) break;
            if (g_net && g_net_pending) {
                /* The setup has been delivered: now join the game through the relay. */
                g_net_pending = 0;
                if (net_connect()) {
                    if (g_error_handler) g_error_handler("Could not connect the game to the host (see the log).");
                    break;
                }
            }
        } else if (len >= 2 && msg[0] == 'y') {
            if (g_net) net_pause_request(msg[1] == '1');
        } else if (len > 1 && msg[0] == 'E') {
            /* The engine reports a fatal error and is about to exit the process. */
            char text[256];
            memcpy(text, msg + 1, (size_t)len - 1);
            text[len - 1] = 0;
            LOGE("engine error: %s", text);
            if (g_net) net_send_control('E', text);          /* before anything else: the process is about to end */
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
    net_finish();
    pthread_mutex_lock(&g_wr_lock);
    g_engine_fd = -1;
    pthread_mutex_unlock(&g_wr_lock);
    pthread_mutex_lock(&g_fd_lock);
    g_client_fd = -1;
    pthread_mutex_unlock(&g_fd_lock);
    close(fd);
    return NULL;
}

/* The player confirmed "leave the game" in the app (GameActivity, through JNI). Independent of the engine's UI state, which an
 * open chat would otherwise block: tell the relay first (control frame Q, treated like the engine's own Q message), then ask the
 * engine to quit with its "forcequit" command over the local IPC socket. Only the app's own process can call this; nothing
 * that arrives from the relay is ever turned into such a command. Returns 0 when the engine was asked. */
int hp_ipc_user_quit(void)
{
    static const char cmd[] = "eforcequit";
    int rc = -1;
    if (g_net) net_send_control('Q', "user quit");
    pthread_mutex_lock(&g_wr_lock);
    if (g_engine_fd >= 0) {
        unsigned char len = (unsigned char)(sizeof cmd - 1);
        rc = (write_full(g_engine_fd, &len, 1) == 0 && write_full(g_engine_fd, cmd, sizeof cmd - 1) == 0) ? 0 : -1;
    }
    pthread_mutex_unlock(&g_wr_lock);
    return rc;
}

#ifdef HP_HOST_TEST
/* Test hook (host test script action "ctl:<letter>"): sends a control frame to the relay as a (buggy or hostile) launcher would,
 * e.g. "ctl:P" from a joining phone, which the relay must ignore. */
void hp_ipc_send_control(char kind)
{
    net_send_control(kind, "");
}

/* Test hook (host test script action "relay:<text>"): writes one message straight onto the relay link, past the launcher's own filter
 * (what a hostile or broken launcher on a watching phone would send). The relay must ignore it. Returns 0 when written. */
int hp_ipc_send_relay(const char *payload)
{
    size_t n = strlen(payload);
    unsigned char buf[256];
    int rc = -1;
    if (n == 0 || n > 255 || !g_net_up || g_relay_fd < 0) return -1;
    buf[0] = (unsigned char)n;
    memcpy(buf + 1, payload, n);
    pthread_mutex_lock(&g_relay_wr_lock);
    rc = write_full(g_relay_fd, buf, n + 1);
    pthread_mutex_unlock(&g_relay_wr_lock);
    return rc;
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
#endif /* HP_HOST_TEST */

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
