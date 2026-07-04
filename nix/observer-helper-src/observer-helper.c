#define _POSIX_C_SOURCE 200809L
// sleepwalker-hid-observer: JSONL evdev event observer.
//
// Reads a Linux input device (default /dev/input/by-id/sleepwalker-hid-keyboard)
// and emits one JSON object per evdev event on stdout, with device identity
// and timestamps. Supports exclusive grab (--grab) so injected HID events
// are not delivered to other userspace consumers during a smoke test.
//
// Usage:
//   sleepwalker-hid-observer <device-path> [--grab] [--timeout sec]
//
// Output example:
//   {"ts_ms":1234,"device":"/dev/input/by-id/sleepwalker-hid-keyboard",
//    "type":"EV_KEY","code":"KEY_SPACE","value":1,"type_code":1,"code_code":57}
//   {"ts_ms":1234,"device":"...","type":"EV_SYN","code":"SYN_REPORT","value":0,
//    "type_code":0,"code_code":0}
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/input.h>

static volatile sig_atomic_t g_stop = 0;
static void on_sig(int s) { (void)s; g_stop = 1; }

// Common event type names.
static const char *type_name(unsigned int t) {
    switch (t) {
    case EV_SYN: return "EV_SYN";
    case EV_KEY: return "EV_KEY";
    case EV_REL: return "EV_REL";
    case EV_ABS: return "EV_ABS";
    case EV_MSC: return "EV_MSC";
    case EV_LED: return "EV_LED";
    case EV_SND: return "EV_SND";
    case EV_REP: return "EV_REP";
    default: return "EV_UNKNOWN";
    }
}

// Common code names for EV_KEY.
static const char *key_name(unsigned int c) {
    switch (c) {
    case KEY_SPACE:    return "KEY_SPACE";
    case KEY_ENTER:    return "KEY_ENTER";
    case KEY_ESC:      return "KEY_ESC";
    case KEY_LEFTCTRL: return "KEY_LEFTCTRL";
    case KEY_RIGHTCTRL:return "KEY_RIGHTCTRL";
    case KEY_A:        return "KEY_A";
    case KEY_B:        return "KEY_B";
    case KEY_C:        return "KEY_C";
    default:           return "KEY_UNKNOWN";
    }
}

static const char *syn_name(unsigned int c) {
    switch (c) {
    case SYN_REPORT:  return "SYN_REPORT";
    case SYN_CONFIG:  return "SYN_CONFIG";
    case SYN_DROPPED: return "SYN_DROPPED";
    default:          return "SYN_UNKNOWN";
    }
}

static const char *code_name(unsigned int t, unsigned int c) {
    if (t == EV_SYN) return syn_name(c);
    if (t == EV_KEY) return key_name(c);
    return "CODE_UNKNOWN";
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: sleepwalker-hid-observer <device> [--grab] [--timeout sec]\n");
        return 2;
    }
    const char *dev = argv[1];
    int grab = 0;
    unsigned int timeout_sec = 0;
    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], "--grab") == 0) {
            grab = 1;
        } else if (strcmp(argv[i], "--timeout") == 0 && i + 1 < argc) {
            timeout_sec = (unsigned int)atoi(argv[++i]);
        } else {
            fprintf(stderr, "unknown arg: %s\n", argv[i]);
            return 2;
        }
    }

    int fd = open(dev, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "{\"ok\":false,\"reason\":\"open\",\"device\":\"%s\",\"errno\":%d}\n",
                dev, errno);
        return 3;
    }

    // Emit a device-found structured event first.
    char name[256] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
        name[0] = '\0';
    }
    printf("{\"ts_ms\":%lld,\"event\":\"device_found\",\"device\":\"%s\",\"name\":\"%s\"}\n",
           now_ms(), dev, name);
    fflush(stdout);

    if (grab) {
        if (ioctl(fd, EVIOCGRAB, 1) < 0) {
            fprintf(stderr, "{\"ok\":false,\"reason\":\"grab\",\"errno\":%d}\n", errno);
            close(fd);
            return 4;
        }
        printf("{\"ts_ms\":%lld,\"event\":\"grab_acquired\",\"device\":\"%s\"}\n",
               now_ms(), dev);
        fflush(stdout);
    }

    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);

    long long deadline = 0;
    if (timeout_sec > 0) {
        deadline = now_ms() + (long long)timeout_sec * 1000LL;
    }

    struct input_event ev;
    while (!g_stop) {
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "{\"ok\":false,\"reason\":\"read\",\"errno\":%d}\n", errno);
            break;
        }
        if ((size_t)n < sizeof(ev)) {
            break;
        }
        printf("{\"ts_ms\":%lld,\"device\":\"%s\",\"type\":\"%s\",\"code\":\"%s\","
               "\"value\":%d,\"type_code\":%u,\"code_code\":%u}\n",
               now_ms(), dev, type_name(ev.type), code_name(ev.type, ev.code),
               ev.value, ev.type, ev.code);
        fflush(stdout);
        if (timeout_sec > 0 && now_ms() >= deadline) {
            break;
        }
    }

    if (grab) {
        ioctl(fd, EVIOCGRAB, 0);
    }
    close(fd);
    printf("{\"ts_ms\":%lld,\"event\":\"stopped\",\"device\":\"%s\"}\n", now_ms(), dev);
    return 0;
}