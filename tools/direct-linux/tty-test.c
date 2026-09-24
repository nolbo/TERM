/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "TTY FAIL %d errno=%d\n", __LINE__, errno); return 1; } } while (0)
static volatile sig_atomic_t resized;
static void resize(int signal) { if (signal == SIGWINCH) resized = 1; }

int main(int argc, char **argv)
{
    CHECK(argc >= 2);
    struct stat st;
    CHECK(stat("/dev/tty", &st) == 0 && S_ISCHR(st.st_mode) && st.st_rdev == 0x500);
    if (!strcmp(argv[1], "no-terminal")) {
        CHECK(!isatty(STDIN_FILENO));
        CHECK(open("/dev/tty", O_RDWR | O_NOCTTY) == -1 && errno == ENXIO);
        puts("PASS no controlling terminal"); return 0;
    }
    CHECK(isatty(0) && isatty(1) && isatty(2));
    CHECK(getpgrp() == tcgetpgrp(0));
    int tty = open("/dev/tty", O_RDWR | O_CLOEXEC); CHECK(tty >= 0);
    CHECK(tcgetsid(tty) == getsid(0) && tcgetpgrp(tty) == getpgrp());
    struct termios attributes;
    CHECK(tcgetattr(tty, &attributes) == 0);
    CHECK((attributes.c_lflag & (ISIG | ICANON)) == (ISIG | ICANON));
    struct winsize size;
    if (!strcmp(argv[1], "check")) {
        CHECK(argc == 4 && ioctl(tty, TIOCGWINSZ, &size) == 0);
        CHECK(size.ws_row == atoi(argv[2]) && size.ws_col == atoi(argv[3]));
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            if (setsid() < 0) _exit(91);
            int detached = open("/dev/tty", O_RDWR | O_NOCTTY);
            _exit(detached == -1 && errno == ENXIO && isatty(0) ? 0 : 92);
        }
        int status;
        CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        // Blocking SIGTTOU inside a syscall handler would incorrectly let
        // this background child take the foreground instead of stopping.
        child = fork(); CHECK(child >= 0);
        if (!child) {
            if (setpgid(0, 0)) _exit(93);
            struct sigaction action = {.sa_handler = SIG_DFL}; sigemptyset(&action.sa_mask);
            if (sigaction(SIGTTOU, &action, NULL)) _exit(94);
            sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGTTOU);
            if (sigprocmask(SIG_UNBLOCK, &mask, NULL)) _exit(95);
            tcsetpgrp(tty, getpgrp()); _exit(96);
        }
        CHECK(waitpid(child, &status, WUNTRACED) == child && WIFSTOPPED(status) && WSTOPSIG(status) == SIGTTOU);
        CHECK(kill(child, SIGKILL) == 0 && waitpid(child, &status, 0) == child && WIFSIGNALED(status));
        CHECK(tcgetpgrp(tty) == getpgrp());
        CHECK(close(tty) == 0);
        puts("PASS tty foreground/termios/winsize/setsid/SIGTTOU"); return 0;
    }
    if (!strcmp(argv[1], "resize")) {
        struct sigaction action = {.sa_handler = resize}; sigemptyset(&action.sa_mask);
        CHECK(sigaction(SIGWINCH, &action, NULL) == 0);
        sigset_t blocked, old;
        sigemptyset(&blocked); sigaddset(&blocked, SIGWINCH);
        CHECK(sigprocmask(SIG_BLOCK, &blocked, &old) == 0);
        puts("READY resize"); fflush(stdout);
        while (!resized) CHECK(sigsuspend(&old) == -1 && errno == EINTR);
        CHECK(sigprocmask(SIG_SETMASK, &old, NULL) == 0);
        CHECK(ioctl(tty, TIOCGWINSZ, &size) == 0 && size.ws_row == 37 && size.ws_col == 111);
        CHECK(close(tty) == 0);
        puts("PASS SIGWINCH resize 37 111"); return 0;
    }
    if (!strcmp(argv[1], "interrupt") || !strcmp(argv[1], "stop")) {
        printf("READY %s\n", argv[1]); fflush(stdout);
        for (;;) { struct timespec delay = {.tv_sec = 30}; nanosleep(&delay, NULL); }
    }
    if (!strcmp(argv[1], "hangup")) {
        struct sigaction action = {.sa_handler = SIG_DFL}; sigemptyset(&action.sa_mask);
        CHECK(sigaction(SIGHUP, &action, NULL) == 0);
        printf("READY hangup leader:%d\n", getpid() == getsid(0)); fflush(stdout);
        // The PTY session leader receives SIGHUP. PRoot's guest is instead a
        // child of that leader; it must observe actual hangup/EOF and loss of
        // its controlling terminal, not necessarily terminate from a signal.
        struct pollfd input = {.fd = STDIN_FILENO, .events = POLLIN};
        CHECK(poll(&input, 1, 5000) > 0 && (input.revents & POLLHUP));
        char byte; CHECK(read(STDIN_FILENO, &byte, 1) == 0);
        CHECK(open("/dev/tty", O_RDWR | O_NOCTTY) == -1 && errno == ENXIO);
        return 78;
    }
    CHECK(0);
}
