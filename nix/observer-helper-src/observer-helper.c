#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700
// sleepwalker-hid-observer: JSONL evdev event observer for composite HID.
//
// Reads one or more Linux input devices and emits one JSON object per
// evdev event on stdout, with device identity, role classification,
// helper version/path, and timestamps. Supports exclusive grab (--grab)
// so injected HID events are not delivered to other userspace consumers
// during a smoke test.
//
// Discovery model (stabilize-composite-hid-regressions):
//   - Device paths are passed on the command line. The caller (HIL) is
//     responsible for handing in descriptor-derived stable paths (e.g.
//     /dev/input/by-id/sleepwalker-hid-keyboard and ...-mouse, which the
//     observer ISO's udev rules tag by USB VID/PID).
//   - The helper classifies each opened device as keyboard-capable and/or
//     mouse-capable by probing evdev capability bitmaps (EVIOCGBIT),
//     instead of assuming /dev/input/eventX numbering or a keyboard-only
//     product shape.
//   - If the same underlying node is passed twice (composite descriptor
//     exposing one combined event node), it is opened once and reported
//     for both roles.
//
// Usage:
//   sleepwalker-hid-observer <device>... [--grab] [--timeout sec]
//
// Output examples:
//   {"ts_ms":1234,"event":"device_found","device":"/dev/input/by-id/...",
//    "name":"Sleepwalker Sleepwalker HID Keyboard","roles":["keyboard"],
//    "helper_version":"0.2.0","helper_path":"/run/.../sleepwalker-hid-observer",
//    "grab":"acquired"}
//   {"ts_ms":1234,"device":"/dev/input/by-id/...","type":"EV_KEY",
//    "code":"KEY_SPACE","value":1,"type_code":1,"code_code":57}
//   {"ts_ms":1234,"device":"/dev/input/by-id/...","type":"EV_SYN",
//    "code":"SYN_REPORT","value":0,"type_code":0,"code_code":0}
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>
#include <limits.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <linux/input.h>

#define SW_HELPER_VERSION "0.2.0"
#define SW_MAX_DEVICES 16
#define SW_NAME_LEN 256
#define SW_PATH_LEN 512
#define SW_EVTYPE_LEN (EV_CNT / 8 + 1)
#define SW_KEYCODE_LEN (KEY_CNT / 8 + 1)
#define SW_RELCODE_LEN (REL_CNT / 8 + 1)

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

// Common code names for EV_KEY (keyboard keys + mouse buttons).
static const char *key_name(unsigned int c) {
    switch (c) {
    case KEY_SPACE:     return "KEY_SPACE";
    case KEY_ENTER:     return "KEY_ENTER";
    case KEY_ESC:       return "KEY_ESC";
    case KEY_LEFTCTRL:  return "KEY_LEFTCTRL";
    case KEY_RIGHTCTRL: return "KEY_RIGHTCTRL";
    case KEY_LEFTSHIFT: return "KEY_LEFTSHIFT";
    case KEY_RIGHTSHIFT:return "KEY_RIGHTSHIFT";
    case KEY_LEFTALT:   return "KEY_LEFTALT";
    case KEY_RIGHTALT:  return "KEY_RIGHTALT";
    case KEY_LEFTMETA:  return "KEY_LEFTMETA";
    case KEY_RIGHTMETA: return "KEY_RIGHTMETA";
    case KEY_TAB:       return "KEY_TAB";
    case KEY_BACKSPACE: return "KEY_BACKSPACE";
    case KEY_A:         return "KEY_A";
    case KEY_B:         return "KEY_B";
    case KEY_C:         return "KEY_C";
    case KEY_D:         return "KEY_D";
    case KEY_E:         return "KEY_E";
    case KEY_F:         return "KEY_F";
    case KEY_G:         return "KEY_G";
    case KEY_H:         return "KEY_H";
    case KEY_I:         return "KEY_I";
    case KEY_J:         return "KEY_J";
    case KEY_K:         return "KEY_K";
    case KEY_L:         return "KEY_L";
    case KEY_M:         return "KEY_M";
    case KEY_N:         return "KEY_N";
    case KEY_O:         return "KEY_O";
    case KEY_P:         return "KEY_P";
    case KEY_Q:         return "KEY_Q";
    case KEY_R:         return "KEY_R";
    case KEY_S:         return "KEY_S";
    case KEY_T:         return "KEY_T";
    case KEY_U:         return "KEY_U";
    case KEY_V:         return "KEY_V";
    case KEY_W:         return "KEY_W";
    case KEY_X:         return "KEY_X";
    case KEY_Y:         return "KEY_Y";
    case KEY_Z:         return "KEY_Z";
    case KEY_1:         return "KEY_1";
    case KEY_2:         return "KEY_2";
    case KEY_3:         return "KEY_3";
    case KEY_4:         return "KEY_4";
    case KEY_5:         return "KEY_5";
    case KEY_6:         return "KEY_6";
    case KEY_7:         return "KEY_7";
    case KEY_8:         return "KEY_8";
    case KEY_9:         return "KEY_9";
    case KEY_0:         return "KEY_0";
    // Punctuation keys
    case KEY_MINUS:     return "KEY_MINUS";
    case KEY_EQUAL:     return "KEY_EQUAL";
    case KEY_LEFTBRACE: return "KEY_LEFTBRACE";
    case KEY_RIGHTBRACE:return "KEY_RIGHTBRACE";
    case KEY_BACKSLASH: return "KEY_BACKSLASH";
    case KEY_SEMICOLON: return "KEY_SEMICOLON";
    case KEY_APOSTROPHE:return "KEY_APOSTROPHE";
    case KEY_GRAVE:     return "KEY_GRAVE";
    case KEY_COMMA:     return "KEY_COMMA";
    case KEY_DOT:       return "KEY_DOT";
    case KEY_SLASH:     return "KEY_SLASH";
    // Mouse buttons (composite keyboard/mouse device).
    case BTN_LEFT:      return "BTN_LEFT";
    case BTN_RIGHT:     return "BTN_RIGHT";
    case BTN_MIDDLE:    return "BTN_MIDDLE";
    case BTN_SIDE:      return "BTN_SIDE";
    case BTN_EXTRA:     return "BTN_EXTRA";
    case BTN_FORWARD:   return "BTN_FORWARD";
    case BTN_BACK:      return "BTN_BACK";
    case BTN_TASK:      return "BTN_TASK";
    default:            return "KEY_UNKNOWN";
    }
}

// Common code names for EV_REL (relative mouse movement).
static const char *rel_name(unsigned int c) {
    switch (c) {
    case REL_X:         return "REL_X";
    case REL_Y:         return "REL_Y";
    case REL_Z:         return "REL_Z";
    case REL_WHEEL:     return "REL_WHEEL";
    case REL_HWHEEL:    return "REL_HWHEEL";
    case REL_WHEEL_HI_RES:  return "REL_WHEEL_HI_RES";
    case REL_HWHEEL_HI_RES: return "REL_HWHEEL_HI_RES";
    default:            return "REL_UNKNOWN";
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
    if (t == EV_REL) return rel_name(c);
    return "CODE_UNKNOWN";
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

// JSON-escape a fixed buffer into out (null-terminated). Escapes ", \, and
// control chars. out_sz is the capacity of out. Returns the number of
// bytes written (excluding the NUL). Truncates on overflow.
static size_t json_escape(const char *in, char *out, size_t out_sz) {
    if (out_sz == 0) return 0;
    size_t j = 0;
    for (size_t i = 0; in[i] != '\0' && j + 2 < out_sz; i++) {
        unsigned char c = (unsigned char)in[i];
        if (c == '"' || c == '\\') {
            if (j + 3 >= out_sz) break;
            out[j++] = '\\';
            out[j++] = (char)c;
        } else if (c == '\n') {
            if (j + 3 >= out_sz) break;
            out[j++] = '\\'; out[j++] = 'n';
        } else if (c == '\r') {
            if (j + 3 >= out_sz) break;
            out[j++] = '\\'; out[j++] = 'r';
        } else if (c == '\t') {
            if (j + 3 >= out_sz) break;
            out[j++] = '\\'; out[j++] = 't';
        } else if (c < 0x20) {
            // Skip other control chars to keep output printable.
            continue;
        } {
            out[j++] = (char)c;
        }
    }
    out[j] = '\0';
    return j;
}

// Test whether bit `bit` is set in the byte array `bits`.
static int bit_test(const unsigned char *bits, unsigned int bit) {
    return (bits[bit / 8] >> (bit % 8)) & 1;
}

// Probe capabilities of an opened fd. Sets *is_keyboard and *is_mouse
// based on evdev capability bitmaps:
//   keyboard: EV_KEY supported AND KEY_SPACE bit set.
//   mouse: (EV_KEY BTN_LEFT set) AND/OR (EV_REL REL_X or REL_Y set).
void classify_device(int fd, int *is_keyboard, int *is_mouse) {
    *is_keyboard = 0;
    *is_mouse = 0;
    unsigned char evbits[SW_EVTYPE_LEN] = {0};
    if (ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), evbits) < 0) {
        return;
    }
    int has_key = bit_test(evbits, EV_KEY);
    int has_rel = bit_test(evbits, EV_REL);
    if (has_key) {
        unsigned char keybits[SW_KEYCODE_LEN] = {0};
        if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) >= 0) {
            if (bit_test(keybits, KEY_SPACE)) {
                *is_keyboard = 1;
            }
            if (bit_test(keybits, BTN_LEFT)) {
                *is_mouse = 1;
            }
        }
    }
    if (has_rel && !*is_mouse) {
        unsigned char relbits[SW_RELCODE_LEN] = {0};
        if (ioctl(fd, EVIOCGBIT(EV_REL, sizeof(relbits)), relbits) >= 0) {
            if (bit_test(relbits, REL_X) || bit_test(relbits, REL_Y)) {
                *is_mouse = 1;
            }
        }
    }
}

// Resolve a path to a canonical real path; returns 0 on success.
static int resolve_real(const char *path, char *out, size_t out_sz) {
    if (out_sz == 0) return -1;
    // Use /proc/self/fd/<fd> via realpath for stable dedupe of symlinks.
    char rp[PATH_MAX];
    if (realpath(path, rp) == NULL) {
        // Fallback: copy as-is if realpath fails.
        snprintf(out, out_sz, "%s", path);
        return 0;
    }
    snprintf(out, out_sz, "%s", rp);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: sleepwalker-hid-observer <device>... [--grab] [--timeout sec]\n");
        return 2;
    }

    const char *devices[SW_MAX_DEVICES];
    int n_devices = 0;
    int grab = 0;
    unsigned int timeout_sec = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--grab") == 0) {
            grab = 1;
        } else if (strcmp(argv[i], "--timeout") == 0 && i + 1 < argc) {
            timeout_sec = (unsigned int)atoi(argv[++i]);
        } else if (strcmp(argv[i], "--version") == 0) {
            printf("{\"event\":\"helper_version\",\"helper_version\":\"%s\"}\n", SW_HELPER_VERSION);
            return 0;
        } else if (argv[i][0] == '-' && argv[i][1] != '\0') {
            fprintf(stderr, "{\"ok\":false,\"reason\":\"unknown_arg\",\"arg\":\"%s\"}\n", argv[i]);
            return 2;
        } else {
            if (n_devices >= SW_MAX_DEVICES) {
                fprintf(stderr, "{\"ok\":false,\"reason\":\"too many devices\",\"max\":%d}\n", SW_MAX_DEVICES);
                return 2;
            }
            devices[n_devices++] = argv[i];
        }
    }
    if (n_devices == 0) {
        fprintf(stderr, "usage: sleepwalker-hid-observer <device>... [--grab] [--timeout sec]\n");
        return 2;
    }

    // Helper identity for device_found events.
    char helper_path[PATH_MAX] = {0};
    ssize_t hp = readlink("/proc/self/exe", helper_path, sizeof(helper_path) - 1);
    if (hp < 0) {
        snprintf(helper_path, sizeof(helper_path), "%s", argv[0]);
    }

    // Open devices, deduping by realpath.
    int fds[SW_MAX_DEVICES];
    char real_paths[SW_MAX_DEVICES][PATH_MAX];
    int n_open = 0;
    for (int i = 0; i < n_devices; i++) {
        char rp[PATH_MAX];
        resolve_real(devices[i], rp, sizeof(rp));
        int dup = 0;
        for (int j = 0; j < n_open; j++) {
            if (strcmp(real_paths[j], rp) == 0) {
                dup = 1;
                break;
            }
        }
        if (dup) {
            // Same underlying node already opened; skip re-opening.
            continue;
        }
        int fd = open(devices[i], O_RDONLY);
        if (fd < 0) {
            fprintf(stderr, "{\"ok\":false,\"reason\":\"open\",\"device\":\"%s\",\"errno\":%d}\n",
                    devices[i], errno);
            continue;
        }
        // Record under the caller-supplied path for stable reporting.
        snprintf(real_paths[n_open], PATH_MAX, "%s", rp);
        fds[n_open] = fd;
        n_open++;
    }
    if (n_open == 0) {
        fprintf(stderr, "{\"ok\":false,\"reason\":\"no devices opened\"}\n");
        return 3;
    }

    // For each opened device, probe capabilities and emit device_found.
    for (int i = 0; i < n_open; i++) {
        char name[SW_NAME_LEN] = {0};
        if (ioctl(fds[i], EVIOCGNAME(sizeof(name) - 1), name) < 0) {
            name[0] = '\0';
        }
        char name_esc[SW_NAME_LEN * 2] = {0};
        json_escape(name, name_esc, sizeof(name_esc));
        int is_kbd = 0, is_mouse = 0;
        classify_device(fds[i], &is_kbd, &is_mouse);
        // Build roles array string.
        char roles[64] = "[]";
        if (is_kbd && is_mouse) {
            snprintf(roles, sizeof(roles), "[\"keyboard\",\"mouse\"]");
        } else if (is_kbd) {
            snprintf(roles, sizeof(roles), "[\"keyboard\"]");
        } else if (is_mouse) {
            snprintf(roles, sizeof(roles), "[\"mouse\"]");
        }
        const char *grab_state = "not_requested";
        if (grab) {
            if (ioctl(fds[i], EVIOCGRAB, 1) < 0) {
                grab_state = "failed";
            } else {
                grab_state = "acquired";
            }
        }
        printf("{\"ts_ms\":%lld,\"event\":\"device_found\",\"device\":\"%s\","
               "\"name\":\"%s\",\"roles\":%s,"
               "\"helper_version\":\"%s\",\"helper_path\":\"%s\","
               "\"grab\":\"%s\"}\n",
               now_ms(), devices[i], name_esc, roles,
               SW_HELPER_VERSION, helper_path, grab_state);
        if (grab) {
            printf("{\"ts_ms\":%lld,\"event\":\"grab_%s\",\"device\":\"%s\"}\n",
                   now_ms(), grab_state, devices[i]);
        }
    }
    fflush(stdout);

    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);

    long long deadline = 0;
    if (timeout_sec > 0) {
        deadline = now_ms() + (long long)timeout_sec * 1000LL;
    }

    // Select across all opened fds. Block up to 250ms so the timeout
    // check fires even with no input.
    while (!g_stop) {
        fd_set rfds;
        FD_ZERO(&rfds);
        int maxfd = -1;
        for (int i = 0; i < n_open; i++) {
            if (fds[i] >= 0) {
                FD_SET(fds[i], &rfds);
                if (fds[i] > maxfd) maxfd = fds[i];
            }
        }
        struct timeval tv = {0, 250000};
        int rc = select(maxfd + 1, &rfds, NULL, NULL, &tv);
        if (rc < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "{\"ok\":false,\"reason\":\"select\",\"errno\":%d}\n", errno);
            break;
        }
        if (rc == 0) {
            if (timeout_sec > 0 && now_ms() >= deadline) {
                break;
            }
            continue;
        }
        for (int i = 0; i < n_open; i++) {
            if (fds[i] < 0) continue;
            if (!FD_ISSET(fds[i], &rfds)) continue;
            struct input_event ev;
            ssize_t n = read(fds[i], &ev, sizeof(ev));
            if (n < 0) {
                if (errno == EINTR) continue;
                // ENODEV: device disappeared mid-observation. Close and
                // continue with remaining devices.
                if (errno == ENODEV) {
                    printf("{\"ts_ms\":%lld,\"event\":\"device_removed\",\"device\":\"%s\"}\n",
                           now_ms(), devices[i]);
                    fflush(stdout);
                    close(fds[i]);
                    fds[i] = -1;
                    continue;
                }
                fprintf(stderr, "{\"ok\":false,\"reason\":\"read\",\"device\":\"%s\",\"errno\":%d}\n",
                        devices[i], errno);
                close(fds[i]);
                fds[i] = -1;
                continue;
            }
            if ((size_t)n < sizeof(ev)) {
                continue;
            }
            printf("{\"ts_ms\":%lld,\"device\":\"%s\",\"type\":\"%s\",\"code\":\"%s\","
                   "\"value\":%d,\"type_code\":%u,\"code_code\":%u}\n",
                   now_ms(), devices[i], type_name(ev.type),
                   code_name(ev.type, ev.code), ev.value, ev.type, ev.code);
            fflush(stdout);
        }
        if (timeout_sec > 0 && now_ms() >= deadline) {
            break;
        }
    }

    for (int i = 0; i < n_open; i++) {
        if (fds[i] < 0) continue;
        if (grab) {
            ioctl(fds[i], EVIOCGRAB, 0);
        }
        close(fds[i]);
        printf("{\"ts_ms\":%lld,\"event\":\"stopped\",\"device\":\"%s\"}\n",
               now_ms(), devices[i]);
    }
    fflush(stdout);
    return 0;
}
