#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700
// sleepwalker-text-sink: raw-mode Linux console text capture for HIL identity tests.
//
// Runs on the active virtual console in raw mode and captures rendered input
// bytes delivered by the Linux console input path. The sink resets, reads,
// and stops over SSH for generated text example isolation.
//
// Usage:
//   sleepwalker-text-sink <artifact-file>
//
// Control via SSH signals:
//   SIGUSR1: reset the capture buffer (clear all captured bytes)
//   SIGUSR2: stop and flush (write captured bytes to artifact file, then exit)
//
// The sink runs in raw mode: disables canonical input buffering, echo, and
// signal interpretation so printable text can be captured without shell or
// line-editing side effects. Initially excludes newline/control characters
// until raw-mode console byte behavior is characterized.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <termios.h>
#include <signal.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

#define SINK_VERSION "0.1.0"
#define SINK_BUFFER_SIZE (256 * 1024)  // 256KB max captured text per example

static volatile sig_atomic_t g_reset = 0;
static volatile sig_atomic_t g_stop = 0;

static unsigned char *g_buffer = NULL;
static size_t g_buffer_len = 0;
static const char *g_artifact_path = NULL;

static void on_sigusr1(int s) { (void)s; g_reset = 1; }
static void on_sigusr2(int s) { (void)s; g_stop = 1; }

// Flush captured bytes to artifact file. Returns 0 on success, -1 on error.
static int flush_artifact(void) {
    if (!g_artifact_path || g_buffer_len == 0) {
        return 0;  // Nothing to flush
    }

    FILE *f = fopen(g_artifact_path, "wb");
    if (!f) {
        perror("fopen artifact");
        return -1;
    }

    size_t written = fwrite(g_buffer, 1, g_buffer_len, f);
    if (written != g_buffer_len) {
        perror("fwrite artifact");
        fclose(f);
        return -1;
    }

    fclose(f);
    return 0;
}

// Reset the capture buffer (clear all captured bytes).
static void reset_buffer(void) {
    g_buffer_len = 0;
}

// Configure stdin in raw mode for direct byte capture.
static int set_raw_mode(int fd, struct termios *orig_tm) {
    struct termios tm;

    if (tcgetattr(fd, &tm) < 0) {
        perror("tcgetattr");
        return -1;
    }

    *orig_tm = tm;  // Save original

    // Raw mode: disable canonical input, echo, and signal processing
    tm.c_lflag &= ~(ICANON | ECHO | ISIG);
    tm.c_iflag &= ~(IXON | IXOFF | ISTRIP);  // No XON/XOFF, no strip
    tm.c_oflag &= ~OPOST;  // No output processing
    tm.c_cc[VMIN] = 1;    // Block until at least 1 byte available
    tm.c_cc[VTIME] = 0;   // No inter-character timer

    if (tcsetattr(fd, TCSANOW, &tm) < 0) {
        perror("tcsetattr");
        return -1;
    }

    return 0;
}

// Restore original terminal settings.
static void restore_mode(int fd, struct termios *orig_tm) {
    tcsetattr(fd, TCSANOW, orig_tm);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "Usage: %s <artifact-file>\n", argv[0]);
        fprintf(stderr, "Version: %s\n", SINK_VERSION);
        return 1;
    }

    g_artifact_path = argv[1];
    struct termios orig_tm;

    // Allocate capture buffer
    g_buffer = malloc(SINK_BUFFER_SIZE);
    if (!g_buffer) {
        perror("malloc buffer");
        return 1;
    }

    // Set up signal handlers for reset/stop control
    signal(SIGUSR1, on_sigusr1);
    signal(SIGUSR2, on_sigusr2);
    signal(SIGINT, SIG_IGN);      // Ignore SIGINT (managed by SSH)
    signal(SIGTERM, SIG_IGN);     // Ignore SIGTERM (managed by SSH)

    // Put stdin in raw mode
    if (set_raw_mode(STDIN_FILENO, &orig_tm) < 0) {
        free(g_buffer);
        return 1;
    }

    // Main capture loop
    while (!g_stop) {
        if (g_reset) {
            reset_buffer();
            g_reset = 0;
        }

        unsigned char buf[1024];
        ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));

        if (n < 0) {
            if (errno == EINTR) {
                continue;  // Interrupted by signal, check flags and continue
            }
            perror("read stdin");
            break;
        }

        if (n == 0) {
            break;  // EOF
        }

        // Append to buffer if space available
        if (g_buffer_len + n <= SINK_BUFFER_SIZE) {
            memcpy(g_buffer + g_buffer_len, buf, n);
            g_buffer_len += n;
        } else {
            // Buffer overflow: truncate but continue (should not happen with bounded examples)
            fprintf(stderr, "warning: capture buffer full, truncating\n");
        }
    }

    // Restore terminal mode
    restore_mode(STDIN_FILENO, &orig_tm);

    // Flush captured bytes to artifact
    if (flush_artifact() < 0) {
        free(g_buffer);
        return 1;
    }

    free(g_buffer);
    return 0;
}
