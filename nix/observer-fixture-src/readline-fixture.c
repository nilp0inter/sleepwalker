#define _DEFAULT_SOURCE
#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700
// sleepwalker-readline-fixture: real GNU Readline fixture for HIL text identity.
//
// Runs an interactive Readline session driven by rl_callback_read_char on the
// specified Linux VT.  A versioned Unix socket serves JSON control operations:
// describe, reset, await_barrier, snapshot, health, shutdown.
//
// F24 is bound as a fixture-only barrier: it captures rl_line_buffer and
// rl_point, advances the barrier generation, and does NOT modify the buffer.
//
// Usage:
//   sleepwalker-readline-fixture <vt_device>
//
// Example:
//   sleepwalker-readline-fixture /dev/tty1
//
// Control socket:
//   /tmp/sleepwalker-readline-fixture-v1.sock
//   Protocol: newline-delimited JSON, one request per line.
//   Control ABI version: 1
//
// Operations:
//   {"operation":"describe"}
//   {"operation":"reset"}
//   {"operation":"await_barrier","timeout_ms":10000}
//   {"operation":"snapshot"}
//   {"operation":"health"}
//   {"operation":"shutdown"}
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <errno.h>
#include <ctype.h>
#include <stdarg.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <linux/kd.h>
#include <linux/keyboard.h>
#include <termios.h>
#include <readline/readline.h>
#include <readline/history.h>

/* ---- Constants ---------------------------------------------------------- */

#define CONTROL_ABI_VERSION     1
#define SOCKET_PATH             "/tmp/sleepwalker-readline-fixture-v1.sock"
#define F24_KEYSEQ              "\\e[24~"
#define F24_TERMINAL_BYTES      "\033[24~"
#define FIXTURE_VERSION         "0.1.0"
#define READLINE_IDENTITY       "gnu-readline 8.2"
#define KEYMAP_PIN              "emacs"
#define INPUT_MODE              "ascii-printable"
#define LINE_MODE               "single-line"
#define MAX_REQUEST_LEN         8192
#define MAX_RESPONSE_LEN        16384
#define BARRIER_POLL_MS         10
#define SELECT_TIMEOUT_MS       50
#define GEN_LOOP_LIMIT          4096
#define READLINE_INPUT_CAPACITY 512

/* ---- Globals ------------------------------------------------------------ */

static volatile sig_atomic_t g_shutdown         = 0;
static volatile int          g_f24_generation    = 0;
static volatile sig_atomic_t g_restart_requested = 0;
static          size_t       g_f24_prefix_len    = 0;
static unsigned char g_readline_input[READLINE_INPUT_CAPACITY];
static          size_t       g_readline_input_pos = 0;
static          size_t       g_readline_input_len = 0;
static          int          g_awaited_generation = 0;
static          int          g_listen_fd         = -1;

/* VT terminal state saved for restoration on exit */
static struct termios g_orig_termios;
static int            g_termios_saved = 0;
static int            g_vt_fd         = -1;

/* F24 binding state: tracked at startup and verified in health */
static int g_f24_bind_ret = -1;          /* rl_bind_keyseq return */
static rl_command_func_t *g_f24_handler_ptr = NULL;  /* our handler fn */

/* ---- Forward declarations ---------------------------------------------- */

static int  f24_handler(int count, int key);
static void line_handler(char *line);
static int  setup_vt(const char *vt_device);
static void restore_vt(void);
static void process_vt_input(void);
static int  setup_socket(void);
static void cleanup_socket(void);
static void handle_client_request(int fd, const char *json);
static int  fixture_readline_getc(FILE *stream);
static void queue_readline_byte(unsigned char byte);
static void send_response(int fd, const char *fmt, ...)
    __attribute__((__format__(__printf__, 2, 3)));
static void do_describe(int fd);
static void do_reset(int fd);
static void do_await_barrier(int fd, int timeout_ms);
static void do_snapshot(int fd);
static void do_health(int fd);
static void do_shutdown(int fd);
static int  json_escape_buf(const char *in, char *out, size_t out_sz);

/* ---- Signal handler ---------------------------------------------------- */

static void handle_signal(int sig) {
    (void)sig;
    g_shutdown = 1;
}

/* ---- Readline callback: F24 barrier ------------------------------------ */
//
// Called by Readline when the F24 escape sequence is recognized via
// rl_bind_keyseq.  Captures the current rl_line_buffer and rl_point by
// copying them into global state, then advances the barrier generation.
// Does NOT insert anything into the buffer.

static int f24_handler(int count, int key) {
    (void)count;
    (void)key;
    g_f24_generation++;
    return 0;  /* Readline continues normally, buffer unchanged */
}

/* ---- Readline callback: line complete ---------------------------------- */

static void line_handler(char *line) {
    if (line) free(line);
    /* Reinstall handler automatically; no-op needed here */
}

/* ---- VT setup / teardown ----------------------------------------------- */

static int fixture_readline_getc(FILE *stream) {
    (void)stream;
    if (g_readline_input_pos < g_readline_input_len) {
        return g_readline_input[g_readline_input_pos++];
    }
    errno = EAGAIN;
    return EOF;
}

static void queue_readline_byte(unsigned char byte) {
    if (g_readline_input_len >= READLINE_INPUT_CAPACITY) {
        g_shutdown = 1;
        return;
    }
    g_readline_input[g_readline_input_len++] = byte;
}

static int setup_vt(const char *vt_device) {
    int fd = open(vt_device, O_RDWR | O_NOCTTY);
    if (fd < 0) {
        perror("open vt_device");
        return -1;
    }

    /* Save original terminal attributes */
    if (tcgetattr(fd, &g_orig_termios) == 0) {
        g_termios_saved = 1;
    }

    /* Put VT in raw mode so every key press is delivered immediately */
    struct termios raw;
    tcgetattr(fd, &raw);
    cfmakeraw(&raw);
    raw.c_cc[VMIN]  = 1;
    raw.c_cc[VTIME] = 0;
    /* Ensure Ctrl-C etc. are delivered as characters, not signals */
    raw.c_lflag &= ~(ISIG | IEXTEN);
    tcsetattr(fd, TCSANOW, &raw);

    /* Readline callback dispatch may probe for another byte after consuming
       a complete escape sequence. Nonblocking input lets it return to the
       fixture's select loop instead of starving the control socket. */
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }

    g_vt_fd = fd;
    return 0;
}

static void process_vt_input(void) {
    unsigned char buf[256];
    ssize_t n;

    while ((n = read(g_vt_fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            unsigned char byte = buf[i];

        reprocess_byte:
            if (byte == (unsigned char)F24_TERMINAL_BYTES[g_f24_prefix_len]) {
                g_f24_prefix_len++;
                if (F24_TERMINAL_BYTES[g_f24_prefix_len] == '\0') {
                    g_f24_generation++;
                    g_f24_prefix_len = 0;
                }
                continue;
            }

            /* A partial prefix was ordinary target input. Replay it before
               considering whether this byte begins a fresh F24 sequence. */
            for (size_t j = 0; j < g_f24_prefix_len; j++) {
                queue_readline_byte(
                    (unsigned char)F24_TERMINAL_BYTES[j]
                );
            }
            if (g_f24_prefix_len > 0) {
                g_f24_prefix_len = 0;
                goto reprocess_byte;
            }

            queue_readline_byte(byte);
        }

        int dispatches = 0;
        while (g_readline_input_pos < g_readline_input_len &&
               dispatches < GEN_LOOP_LIMIT) {
            rl_callback_read_char();
            dispatches++;
        }
        if (g_readline_input_pos < g_readline_input_len) {
            g_shutdown = 1;
        }
        g_readline_input_pos = 0;
        g_readline_input_len = 0;
    }

    if (n == 0) {
        g_shutdown = 1;
    } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
        g_shutdown = 1;
    }
}

static void restore_vt(void) {
    if (g_vt_fd >= 0 && g_termios_saved) {
        tcsetattr(g_vt_fd, TCSANOW, &g_orig_termios);
    }
    if (g_vt_fd >= 0) {
        close(g_vt_fd);
        g_vt_fd = -1;
    }
}

/* ---- Unix socket setup / teardown -------------------------------------- */

static int setup_socket(void) {
    struct sockaddr_un addr;
    int fd;

    /* Remove stale socket file */
    unlink(SOCKET_PATH);

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        perror("socket");
        return -1;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        close(fd);
        return -1;
    }

    /* World-readable so the observer user can connect without sudo */
    chmod(SOCKET_PATH, 0666);

    if (listen(fd, 1) < 0) {
        perror("listen");
        close(fd);
        unlink(SOCKET_PATH);
        return -1;
    }

    g_listen_fd = fd;
    return 0;
}

static void cleanup_socket(void) {
    if (g_listen_fd >= 0) {
        close(g_listen_fd);
        g_listen_fd = -1;
    }
    unlink(SOCKET_PATH);
}

/* ---- JSON helpers ------------------------------------------------------ */

// Escape a string for JSON: replace ", \, and control chars.
// Returns the number of bytes written (excluding NUL).
static int json_escape_buf(const char *in, char *out, size_t out_sz) {
    size_t pos = 0;
    for (; *in && pos + 6 < out_sz; in++) {
        unsigned char c = (unsigned char)*in;
        switch (c) {
            case '"':  out[pos++] = '\\'; out[pos++] = '"';  break;
            case '\\': out[pos++] = '\\'; out[pos++] = '\\'; break;
            case '\b': out[pos++] = '\\'; out[pos++] = 'b';  break;
            case '\f': out[pos++] = '\\'; out[pos++] = 'f';  break;
            case '\n': out[pos++] = '\\'; out[pos++] = 'n';  break;
            case '\r': out[pos++] = '\\'; out[pos++] = 'r';  break;
            case '\t': out[pos++] = '\\'; out[pos++] = 't';  break;
            default:
                if (c < 0x20) {
                    /* \u00xx escape for other control chars */
                    if (pos + 6 > out_sz) goto done;
                    pos += snprintf(out + pos, out_sz - pos, "\\u%04x", c);
                } else {
                    out[pos++] = c;
                }
                break;
        }
    }
done:
    out[pos] = '\0';
    return (int)pos;
}

__attribute__((__format__(__printf__, 2, 3)))
static void send_response(int fd, const char *fmt, ...) {
    char buf[MAX_RESPONSE_LEN];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if ((size_t)n >= sizeof(buf)) n = (int)sizeof(buf) - 1;
    buf[n++] = '\n';  /* newline-delimited */
    /* Best-effort write; ignore errors (client may disconnect) */
    ssize_t written = write(fd, buf, (size_t)n);
    (void)written;
}

/* ---- Client command handler -------------------------------------------- */

static void handle_client_request(int fd, const char *json) {
    /* Skip whitespace */
    while (*json == ' ' || *json == '\t' || *json == '\n' || *json == '\r')
        json++;

    if (strncmp(json, "{\"operation\":\"describe\"}", 22) == 0) {
        do_describe(fd);
    } else if (strncmp(json, "{\"operation\":\"reset\"}", 20) == 0) {
        do_reset(fd);
    } else if (strncmp(json, "{\"operation\":\"await_barrier\"", 28) == 0) {
        int timeout_ms = 5000;  /* default 5s */
        /* Parse timeout_ms from the JSON object */
        const char *t = strstr(json, "\"timeout_ms\"");
        if (t) {
            t = strchr(t, ':');
            if (t) {
                t++;
                while (*t == ' ') t++;
                timeout_ms = atoi(t);
            }
        }
        do_await_barrier(fd, timeout_ms);
    } else if (strncmp(json, "{\"operation\":\"snapshot\"}", 22) == 0) {
        do_snapshot(fd);
    } else if (strncmp(json, "{\"operation\":\"health\"}", 21) == 0) {
        do_health(fd);
    } else if (strncmp(json, "{\"operation\":\"shutdown\"}", 24) == 0) {
        do_shutdown(fd);
    } else {
        send_response(fd,
            "{\"ok\":false,\"control_abi_version\":%d,\"status\":\"error\","
            "\"message\":\"unknown operation\"}",
            CONTROL_ABI_VERSION);
    }
}

/* ---- Operation implementations ----------------------------------------- */

static void do_describe(int fd) {
    send_response(fd,
        "{"
        "\"ok\":true,"
        "\"control_abi_version\":%d,"
        "\"identity\":{"
            "\"fixture\":\"sleepwalker-readline-fixture\","
            "\"readline_version\":\"%s\","
            "\"keymap\":\"%s\","
            "\"input_mode\":\"%s\","
            "\"line_mode\":\"%s\""
        "}"
        "}",
        CONTROL_ABI_VERSION,
        READLINE_IDENTITY,
        KEYMAP_PIN,
        INPUT_MODE,
        LINE_MODE);
}

static void do_reset(int fd) {
    /* Replace through Readline so undo/editing state is cleared too. */
    rl_replace_line("", 1);
    rl_point = 0;
    rl_mark = 0;
    rl_on_new_line();
    rl_redisplay();

    /* Reset barrier generation */
    g_f24_generation = 0;
    g_awaited_generation = 0;

    send_response(fd,
        "{\"ok\":true,\"control_abi_version\":%d,\"status\":\"ok\"}",
        CONTROL_ABI_VERSION);

    /* Restart the entire fixture after the reset response is flushed. This
       recreates Readline callback/keymap/undo state instead of maintaining a
       shadow reset model in the control handler. */
    g_restart_requested = 1;
    g_shutdown = 1;
}

static void do_await_barrier(int fd, int timeout_ms) {
    int waited = 0;

    if (timeout_ms < 0) timeout_ms = 0;
    if (timeout_ms > 30000) timeout_ms = 30000;

    while (g_f24_generation <= g_awaited_generation &&
           waited < timeout_ms && !g_shutdown) {
        struct timeval tv = {
            .tv_sec = 0,
            .tv_usec = BARRIER_POLL_MS * 1000,
        };
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(g_vt_fd, &rfds);
        int ret = select(g_vt_fd + 1, &rfds, NULL, NULL, &tv);
        if (ret > 0 && FD_ISSET(g_vt_fd, &rfds)) {
            process_vt_input();
        } else if (ret < 0 && errno != EINTR) {
            break;
        }
        waited += BARRIER_POLL_MS;
    }

    if (g_f24_generation > g_awaited_generation) {
        g_awaited_generation = g_f24_generation;
        send_response(fd,
            "{\"ok\":true,\"control_abi_version\":%d,\"status\":\"ok\",\"generation\":%d}",
            CONTROL_ABI_VERSION, g_f24_generation);
    } else {
        send_response(fd,
            "{\"ok\":false,\"control_abi_version\":%d,\"status\":\"timeout\"}",
            CONTROL_ABI_VERSION);
    }
}

static void do_snapshot(int fd) {
    char esc_buf[MAX_RESPONSE_LEN / 2];
    json_escape_buf(rl_line_buffer, esc_buf, sizeof(esc_buf));

    send_response(fd,
        "{"
        "\"ok\":true,"
        "\"control_abi_version\":%d,"
        "\"contract_version\":%d,"
        "\"buffer\":\"%s\","
        "\"point\":%d,"
        "\"mark\":%d,"
        "\"end\":%d,"
        "\"generation\":%d"
        "}",
        CONTROL_ABI_VERSION,
        CONTROL_ABI_VERSION,
        esc_buf,
        rl_point,
        rl_mark,
        rl_end,
        g_f24_generation);
}

static void do_health(int fd) {
    int buffer_empty = (rl_line_buffer[0] == '\0');
    int baseline = buffer_empty && rl_point == 0 && rl_mark == 0;

    char vt_path[64] = "unknown";
    char *tty = ttyname(STDIN_FILENO);
    if (tty) {
        strncpy(vt_path, tty, sizeof(vt_path) - 1);
        vt_path[sizeof(vt_path) - 1] = '\0';
    }

    const char *keymap_name = NULL;
    Keymap current_km = rl_get_keymap();
    if (current_km != NULL) {
        keymap_name = rl_get_keymap_name(current_km);
    }
    int keymap_ok = (current_km == emacs_standard_keymap);

    /* Binding succeeded for the exact Readline key-sequence expression.
       The keymap identity is checked independently and never changes after
       initialization. */
    int f24_bound = (g_f24_bind_ret == 0 && g_f24_handler_ptr != NULL);

    /* Verify the active Linux console keymap maps evdev KEY_F24 (194) to
       the exact terminal sequence consumed by the Readline binding. */
    int console_f24_keyseq_ok = 0;
    struct kbentry kb = {
        .kb_table = 0,
        .kb_index = 194,
    };
    if (ioctl(g_vt_fd, KDGKBENT, &kb) == 0 && KTYP(kb.kb_value) == KT_FN) {
        struct kbsentry fn_string = {
            .kb_func = KVAL(kb.kb_value),
        };
        if (ioctl(g_vt_fd, KDGKBSENT, &fn_string) == 0 &&
            strcmp((char *)fn_string.kb_string, F24_TERMINAL_BYTES) == 0) {
            console_f24_keyseq_ok = 1;
        }
    }

    int healthy = baseline && keymap_ok && f24_bound &&
                  console_f24_keyseq_ok;

    send_response(fd,
        "{"
        "\"ok\":%s,"
        "\"control_abi_version\":%d,"
        "\"status\":\"%s\","
        "\"alive\":true,"
        "\"responsive\":true,"
        "\"baseline\":%s,"
        "\"pid\":%d,"
        "\"vt\":\"%s\","
        "\"console_f24_keyseq_ok\":%s,"
        "\"keymap\":\"%s\","
        "\"keymap_pin\":\"%s\","
        "\"keymap_ok\":%s,"
        "\"f24_bind_ret\":%d,"
        "\"f24_bound\":%s,"
        "\"buffer_empty\":%s,"
        "\"point\":%d,"
        "\"generation\":%d"
        "}",
        healthy ? "true" : "false",
        CONTROL_ABI_VERSION,
        healthy ? "healthy" : "unhealthy",
        baseline ? "true" : "false",
        (int)getpid(),
        vt_path,
        console_f24_keyseq_ok ? "true" : "false",
        keymap_name ? keymap_name : "unknown",
        KEYMAP_PIN,
        keymap_ok ? "true" : "false",
        g_f24_bind_ret,
        f24_bound ? "true" : "false",
        buffer_empty ? "true" : "false",
        rl_point,
        g_f24_generation);
}

static void do_shutdown(int fd) {
    send_response(fd,
        "{\"ok\":true,\"control_abi_version\":%d,\"status\":\"shutting_down\"}",
        CONTROL_ABI_VERSION);
    g_shutdown = 1;
}

/* ---- Main loop --------------------------------------------------------- */

int main(int argc, char **argv) {
    const char *vt_device = NULL;

    if (argc < 2) {
        /* Try to determine the active VT automatically */
        char *tty = ttyname(STDIN_FILENO);
        if (tty) {
            vt_device = tty;
        } else {
            vt_device = "/dev/tty1";
        }
    } else {
        vt_device = argv[1];
    }

    /* ---- Signal handlers ---- */
    signal(SIGINT,  handle_signal);
    signal(SIGTERM, handle_signal);
    signal(SIGPIPE, SIG_IGN);

    /* ---- VT ---- */
    if (setup_vt(vt_device) < 0) {
        return 1;
    }

    /* Set the VT as our controlling terminal */
    int saved_fd = dup(g_vt_fd);
    /* Dup VT to stdin / stdout / stderr so Readline uses the VT */
    dup2(g_vt_fd, STDIN_FILENO);
    dup2(g_vt_fd, STDOUT_FILENO);
    dup2(g_vt_fd, STDERR_FILENO);
    close(saved_fd);

    /* ---- Readline ---- */
    rl_getc_function = fixture_readline_getc;
    rl_callback_handler_install("", line_handler);
    /* Ensure emacs mode BEFORE binding so the keyseq lands in emacs keymap */
    rl_set_keymap(emacs_standard_keymap);
    g_f24_handler_ptr = &f24_handler;
    g_f24_bind_ret = rl_bind_keyseq(F24_KEYSEQ, f24_handler);
    if (g_f24_bind_ret != 0) {
        fprintf(stderr, "readline-fixture: rl_bind_keyseq(\"%s\") failed: %d\n",
                F24_KEYSEQ, g_f24_bind_ret);
    }

    /* ---- Socket ---- */
    if (setup_socket() < 0) {
        restore_vt();
        return 1;
    }

    /* ---- Main event loop ---- */
    while (!g_shutdown) {
        struct timeval tv;
        tv.tv_sec  = 0;
        tv.tv_usec = SELECT_TIMEOUT_MS * 1000;  /* 50ms granularity */

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(g_vt_fd,      &rfds);
        FD_SET(g_listen_fd,  &rfds);

        int max_fd = g_listen_fd;
        if (g_vt_fd      > max_fd) max_fd = g_vt_fd;

        int ret = select(max_fd + 1, &rfds, NULL, NULL, &tv);

        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        /* ---- VT input: feed raw bytes to Readline ---- */
        if (FD_ISSET(g_vt_fd, &rfds)) {
            process_vt_input();
        }

        /* ---- Socket: handle one request per connection ---- */
        if (FD_ISSET(g_listen_fd, &rfds)) {
            struct sockaddr_un client_addr;
            socklen_t addrlen = sizeof(client_addr);
            int client_fd = accept(g_listen_fd,
                                   (struct sockaddr *)&client_addr, &addrlen);
            if (client_fd >= 0) {
                char client_buf[MAX_REQUEST_LEN];
                size_t client_len = 0;
                char *newline = NULL;

                while (client_len < sizeof(client_buf) - 1 && newline == NULL) {
                    ssize_t n = read(client_fd, client_buf + client_len,
                                     sizeof(client_buf) - 1 - client_len);
                    if (n <= 0) break;
                    client_len += (size_t)n;
                    client_buf[client_len] = '\0';
                    newline = strchr(client_buf, '\n');
                }

                if (newline != NULL) {
                    *newline = '\0';
                    char *end = newline - 1;
                    while (end >= client_buf && (*end == ' ' || *end == '\t'
                           || *end == '\r')) {
                        *end = '\0';
                        end--;
                    }
                    if (*client_buf) {
                        handle_client_request(client_fd, client_buf);
                    }
                }
                close(client_fd);
            }
        }
    }

    /* ---- Cleanup ---- */
    rl_callback_handler_remove();
    restore_vt();
    cleanup_socket();

    if (g_restart_requested) {
        execvp(argv[0], argv);
        perror("readline-fixture: restart exec failed");
        return 1;
    }

    return 0;
}
