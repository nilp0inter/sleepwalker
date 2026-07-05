// Test harness for the observer helper: creates two uinput devices
// (one keyboard, one mouse) and emits a known event sequence, then
// verifies the observer helper's symbolic decoding, classification,
// helper-version reporting, and exclusive grab.
//
// Host-side unit test for the observer helper. Requires write access
// to /dev/uinput (runs as root or with uinput permissions). Skipped
// gracefully when /dev/uinput is not writable so it is safe to invoke
// from a Nix build sandbox.
//
// Build: cc -O2 -Wall -o observer-helper-test observer-helper-test.c
// Run:   ./observer-helper-test <path-to-sleepwalker-hid-observer>
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <linux/input.h>
#include <linux/uinput.h>

static void die(const char *m) { perror(m); exit(1); }

static int make_uinput(const char *name, int keyboard, int mouse) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    struct uinput_setup usetup = {0};
    strncpy(usetup.name, name, sizeof(usetup.name) - 1);
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor = 0x303a;
    usetup.id.product = 0x4001;
    if (keyboard) {
        ioctl(fd, UI_SET_EVBIT, EV_KEY);
        ioctl(fd, UI_SET_KEYBIT, KEY_SPACE);
        ioctl(fd, UI_SET_KEYBIT, KEY_A);
    }
    if (mouse) {
        ioctl(fd, UI_SET_EVBIT, EV_KEY);
        ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
        ioctl(fd, UI_SET_EVBIT, EV_REL);
        ioctl(fd, UI_SET_RELBIT, REL_X);
        ioctl(fd, UI_SET_RELBIT, REL_Y);
        ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    }
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) { close(fd); return -1; }
    if (ioctl(fd, UI_DEV_CREATE, NULL) < 0) { close(fd); return -1; }
    return fd;
}

static void emit(int ufd, unsigned short type, unsigned short code, int val) {
    struct input_event ev = {0};
    ev.type = type;
    ev.code = code;
    ev.value = val;
    write(ufd, &ev, sizeof(ev));
    struct input_event syn = {0};
    syn.type = EV_SYN;
    syn.code = SYN_REPORT;
    syn.value = 0;
    write(ufd, &syn, sizeof(syn));
}

int main(int argc, char **argv) {
    const char *helper = argc > 1 ? argv[1] : "./sleepwalker-hid-observer";
    // Probe uinput availability; skip gracefully if unavailable.
    int probe = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (probe < 0) {
        printf("SKIP: /dev/uinput not writable (errno=%d %s)\n", errno,
               strerror(errno));
        return 77;
    }
    close(probe);

    int kfd = make_uinput("Sleepwalker Test Keyboard", 1, 0);
    int mfd = make_uinput("Sleepwalker Test Mouse", 0, 1);
    if (kfd < 0 || mfd < 0) {
        fprintf(stderr, "FAIL: could not create uinput devices\n");
        return 1;
    }
    char kpath[256] = {0}, mpath[256] = {0};
    FILE *p = popen("ls /sys/class/input/ | grep event | head -40", "r");
    if (!p) die("popen");
    char line[256];
    while (fgets(line, sizeof(line), p)) {
        line[strcspn(line, "\n")] = 0;
        char np[512];
        snprintf(np, sizeof(np), "/sys/class/input/%s/device/name", line);
        FILE *nf = fopen(np, "r");
        if (nf) {
            char nbuf[256] = {0};
            fgets(nbuf, sizeof(nbuf) - 1, nf);
            fclose(nf);
            nbuf[strcspn(nbuf, "\n")] = 0;
            char devp[256];
            snprintf(devp, sizeof(devp), "/dev/input/%s", line);
            if (strstr(nbuf, "Keyboard") && !kpath[0]) {
                strncpy(kpath, devp, sizeof(kpath) - 1);
            } else if (strstr(nbuf, "Mouse") && !mpath[0]) {
                strncpy(mpath, devp, sizeof(mpath) - 1);
            }
        }
    }
    pclose(p);
    if (!kpath[0] || !mpath[0]) {
        fprintf(stderr, "FAIL: could not find uinput event nodes k=%s m=%s\n",
                kpath, mpath);
        return 1;
    }
    fprintf(stderr, "keyboard node: %s\nmouse node: %s\n", kpath, mpath);

    int pipefd[2];
    pipe(pipefd);
    pid_t pid = fork();
    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        execl(helper, "sleepwalker-hid-observer", kpath, mpath,
              "--grab", "--timeout", "4", (char *)NULL);
        _exit(127);
    }
    close(pipefd[1]);
    sleep(1);
    emit(kfd, EV_KEY, KEY_SPACE, 1);
    usleep(100000);
    emit(kfd, EV_KEY, KEY_SPACE, 0);
    usleep(100000);
    emit(mfd, EV_KEY, BTN_LEFT, 1);
    usleep(100000);
    emit(mfd, EV_KEY, BTN_LEFT, 0);
    usleep(100000);
    emit(mfd, EV_REL, REL_X, 10);
    usleep(100000);
    emit(mfd, EV_REL, REL_WHEEL, -1);

    char buf[16384] = {0};
    ssize_t total = 0;
    while (1) {
        ssize_t n = read(pipefd[0], buf + total, sizeof(buf) - total - 1);
        if (n <= 0) break;
        total += n;
        buf[total] = 0;
    }
    close(pipefd[0]);
    int status;
    waitpid(pid, &status, 0);

    int found_kbd = 0, found_mouse = 0, found_helper_ver = 0;
    int saw_space_down = 0, saw_space_up = 0;
    int saw_btn_down = 0, saw_btn_up = 0, saw_relx = 0, saw_rel_wheel = 0;
    int saw_grab_acquired = 0;
    char *save = NULL;
    for (char *tok = strtok_r(buf, "\n", &save); tok; tok = strtok_r(NULL, "\n", &save)) {
        if (strstr(tok, "\"event\":\"device_found\"")) {
            if (strstr(tok, "\"roles\":[\"keyboard\"]")) found_kbd = 1;
            if (strstr(tok, "\"roles\":[\"mouse\"]")) found_mouse = 1;
            if (strstr(tok, "\"helper_version\":\"0.2.0\"")) found_helper_ver = 1;
        }
        if (strstr(tok, "\"event\":\"grab_acquired\"") || strstr(tok, "\"grab\":\"acquired\"")) {
            saw_grab_acquired = 1;
        }
        if (strstr(tok, "\"code\":\"KEY_SPACE\"") && strstr(tok, "\"value\":1")) saw_space_down = 1;
        if (strstr(tok, "\"code\":\"KEY_SPACE\"") && strstr(tok, "\"value\":0")) saw_space_up = 1;
        if (strstr(tok, "\"code\":\"BTN_LEFT\"") && strstr(tok, "\"value\":1")) saw_btn_down = 1;
        if (strstr(tok, "\"code\":\"BTN_LEFT\"") && strstr(tok, "\"value\":0")) saw_btn_up = 1;
        if (strstr(tok, "\"code\":\"REL_X\"")) saw_relx = 1;
        if (strstr(tok, "\"code\":\"REL_WHEEL\"")) saw_rel_wheel = 1;
    }
    int ok = found_kbd && found_mouse && found_helper_ver && saw_grab_acquired &&
             saw_space_down && saw_space_up && saw_btn_down && saw_btn_up &&
             saw_relx && saw_rel_wheel;
    printf("found_kbd=%d found_mouse=%d helper_ver=%d grab=%d\n",
           found_kbd, found_mouse, found_helper_ver, saw_grab_acquired);
    printf("space_down=%d space_up=%d btn_down=%d btn_up=%d relx=%d wheel=%d\n",
           saw_space_down, saw_space_up, saw_btn_down, saw_btn_up, saw_relx, saw_rel_wheel);
    printf("RESULT: %s\n", ok ? "PASS" : "FAIL");

    ioctl(kfd, UI_DEV_DESTROY);
    ioctl(mfd, UI_DEV_DESTROY);
    close(kfd);
    close(mfd);
    return ok ? 0 : 1;
}
