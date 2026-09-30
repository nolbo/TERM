/* SPDX-License-Identifier: Apache-2.0 */
/* The same Linux ABI/lifetime assertions run natively and under the loader. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/signalfd.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/timerfd.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL timers:%d %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static int realtime;
static void sleep_ms(long ms)
{
    struct timespec pause = {.tv_sec = ms / 1000, .tv_nsec = ms % 1000 * 1000000};
    while (nanosleep(&pause, &pause)) CHECK(errno == EINTR);
}
static void block_signal(int signal)
{
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, signal);
    CHECK(pthread_sigmask(SIG_BLOCK, &mask, NULL) == 0);
}
static siginfo_t wait_signal(int signal)
{
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, signal);
    struct timespec timeout = {.tv_sec = 3}; siginfo_t info;
    CHECK(sigtimedwait(&mask, &info, &timeout) == signal); return info;
}
static void reap(pid_t child)
{
    int status; CHECK(waitpid(child, &status, 0) == child);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
static int make_timer(int notify, int signal, int value, int tid)
{
    struct sigevent event = {.sigev_notify = notify, .sigev_signo = signal, .sigev_value.sival_int = value};
    event._sigev_un._tid = tid;
    int id = -1; CHECK(syscall(SYS_timer_create, CLOCK_MONOTONIC, &event, &id) == 0); return id;
}
static void arm_timer(int id, long first_ms, long interval_ms)
{
    struct itimerspec setting = {.it_value = {.tv_sec = first_ms / 1000, .tv_nsec = first_ms % 1000 * 1000000},
        .it_interval = {.tv_sec = interval_ms / 1000, .tv_nsec = interval_ms % 1000 * 1000000}};
    CHECK(syscall(SYS_timer_settime, id, 0, &setting, NULL) == 0);
}
static void interval_timers(void)
{
    block_signal(SIGALRM);
    struct itimerval setting = {.it_value = {.tv_usec = 10000}}, old, current;
    CHECK(setitimer(ITIMER_REAL, &setting, &old) == 0);
    CHECK(!old.it_value.tv_sec && !old.it_value.tv_usec);
    CHECK(getitimer(ITIMER_REAL, &current) == 0);
    CHECK(current.it_value.tv_sec || current.it_value.tv_usec);
    CHECK(wait_signal(SIGALRM).si_code == SI_KERNEL);
    setting.it_value.tv_sec = 20; setting.it_value.tv_usec = 0;
    CHECK(setitimer(ITIMER_REAL, &setting, NULL) == 0);
    struct itimerval invalid = {.it_value = {.tv_usec = 1000000}};
    CHECK(setitimer(ITIMER_REAL, &invalid, NULL) == -1 && errno == EINVAL);
    CHECK(getitimer(ITIMER_REAL, &current) == 0 && current.it_value.tv_sec >= 18);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) { CHECK(getitimer(ITIMER_REAL, &current) == 0 && !current.it_value.tv_sec && !current.it_value.tv_usec); _exit(0); }
    reap(child);
    setting = (struct itimerval){0}; CHECK(setitimer(ITIMER_REAL, &setting, NULL) == 0);
    CHECK(getitimer(-1, &current) == -1 && errno == EINVAL);
    puts("PASS set/getitimer expiration, failed-set recovery and fork noninheritance");
}
static volatile sig_atomic_t handler_value, handler_code, handler_mask;
static void queued_handler(int signal, siginfo_t *info, void *context)
{
    (void)signal; (void)context;
    sigset_t mask;
    handler_value = info->si_value.sival_int; handler_code = info->si_code;
    if (!sigprocmask(SIG_SETMASK, NULL, &mask)) handler_mask = sigismember(&mask, SIGSYS);
    (void)getpid();
}
static void queued_signals(int direct)
{
    block_signal(realtime);
    CHECK(sigqueue(getpid(), realtime, (union sigval){.sival_int = 101}) == 0);
    CHECK(sigqueue(getpid(), realtime, (union sigval){.sival_int = 102}) == 0);
    siginfo_t info = wait_signal(realtime);
    CHECK(info.si_code == SI_QUEUE && info.si_value.sival_int == 101 && info.si_pid == getpid() && info.si_uid == getuid());
    CHECK(wait_signal(realtime).si_value.sival_int == 102);
    info = (siginfo_t){.si_signo = realtime, .si_code = SI_QUEUE, .si_pid = getpid(), .si_uid = getuid()};
    info.si_value.sival_int = 103;
    CHECK(syscall(SYS_rt_tgsigqueueinfo, getpid(), syscall(SYS_gettid), realtime, &info) == 0);
    CHECK(wait_signal(realtime).si_value.sival_int == 103);
    CHECK(syscall(SYS_rt_sigqueueinfo, getpid(), realtime, (void *)1) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_rt_tgsigqueueinfo, getpid(), -1, realtime, &info) == -1 && errno == EINVAL);
    struct sigaction action = {.sa_sigaction = queued_handler, .sa_flags = SA_SIGINFO};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    CHECK(sigqueue(getpid(), SIGUSR1, (union sigval){.sival_int = 104}) == 0);
    CHECK(handler_value == 104 && handler_code == SI_QUEUE && handler_mask == 1);
    if (direct) {
        CHECK(syscall(SYS_rt_sigqueueinfo, getpid(), SIGSYS, &info) == -1 && errno == EOPNOTSUPP);
        CHECK(syscall(SYS_rt_tgsigqueueinfo, getpid(), syscall(SYS_gettid), SIGSYS, &info) == -1 && errno == EOPNOTSUPP);
    }
    puts("PASS queued realtime FIFO/value/identity, thread queue, SA_SIGINFO and logical masks");
}
static void signal_descriptors(int direct)
{
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, realtime);
    int fd = signalfd(-1, &mask, SFD_NONBLOCK | SFD_CLOEXEC); CHECK(fd >= 0);
    CHECK((fcntl(fd, F_GETFD) & FD_CLOEXEC) != 0);
    struct signalfd_siginfo info;
    CHECK(read(fd, &info, sizeof(info)) == -1 && errno == EAGAIN);
    int epoll = epoll_create1(EPOLL_CLOEXEC); CHECK(epoll >= 0);
    struct epoll_event event = {.events = EPOLLIN, .data.fd = fd}; CHECK(epoll_ctl(epoll, EPOLL_CTL_ADD, fd, &event) == 0);
    CHECK(sigqueue(getpid(), realtime, (union sigval){.sival_int = 201}) == 0);
    CHECK(epoll_wait(epoll, &event, 1, 1000) == 1 && event.data.fd == fd);
    CHECK(read(fd, &info, sizeof(info)) == sizeof(info));
    CHECK(info.ssi_signo == (unsigned)realtime && (int)info.ssi_code == SI_QUEUE && info.ssi_int == 201 && info.ssi_pid == (unsigned)getpid());
    block_signal(SIGUSR2); sigemptyset(&mask); sigaddset(&mask, SIGUSR2);
    CHECK(signalfd(fd, &mask, 0) == fd);
    CHECK(sigqueue(getpid(), SIGUSR2, (union sigval){.sival_int = 202}) == 0);
    CHECK(read(fd, &info, sizeof(info)) == sizeof(info) && info.ssi_int == 202);
    if (direct) {
        sigaddset(&mask, SIGSYS);
        CHECK(signalfd(fd, &mask, 0) == -1 && errno == EOPNOTSUPP);
        CHECK(sigqueue(getpid(), SIGUSR2, (union sigval){.sival_int = 203}) == 0);
        CHECK(read(fd, &info, sizeof(info)) == sizeof(info) && info.ssi_int == 203);
    }
    CHECK(close(epoll) == 0 && close(fd) == 0);
    puts("PASS signalfd read/epoll/queue payload, mask update and failed-update recovery");
}
static void timer_descriptors(void)
{
    int fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC); CHECK(fd >= 0);
    uint64_t ticks; CHECK(read(fd, &ticks, sizeof(ticks)) == -1 && errno == EAGAIN);
    struct itimerspec setting = {.it_value = {.tv_nsec = 2000000}, .it_interval = {.tv_nsec = 2000000}}, old, current;
    CHECK(timerfd_settime(fd, 0, &setting, &old) == 0 && !old.it_value.tv_nsec);
    sleep_ms(60);
    CHECK(read(fd, &ticks, sizeof(ticks)) == sizeof(ticks) && ticks >= 2);
    CHECK(timerfd_gettime(fd, &current) == 0 && current.it_interval.tv_nsec == 2000000);
    CHECK(timerfd_settime(fd, ~0, &setting, NULL) == -1 && errno == EINVAL);
    CHECK(timerfd_gettime(fd, &current) == 0 && current.it_interval.tv_nsec == 2000000);
    setting = (struct itimerspec){0}; CHECK(timerfd_settime(fd, 0, &setting, NULL) == 0);
    CHECK(timerfd_settime(fd, 0, (void *)1, NULL) == -1 && errno == EFAULT);
    int duplicate = dup(fd); CHECK(duplicate >= 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        setting.it_value.tv_nsec = 1000000; CHECK(timerfd_settime(duplicate, 0, &setting, NULL) == 0);
        sleep_ms(20); _exit(0);
    }
    reap(child); CHECK(read(fd, &ticks, sizeof(ticks)) == sizeof(ticks) && ticks == 1);
    CHECK(read(duplicate, &ticks, sizeof(ticks)) == -1 && errno == EAGAIN);
    CHECK(close(fd) == 0 && close(duplicate) == 0);
    puts("PASS timerfd expiration count, periodic state/errors, dup and fork shared consumption");
}
static void posix_timers(int direct)
{
    int id = make_timer(SIGEV_SIGNAL, realtime, 301, 0);
    arm_timer(id, 2, 2); sleep_ms(60);
    siginfo_t info = wait_signal(realtime);
    CHECK(info.si_code == SI_TIMER && info.si_value.sival_int == 301 && info.si_overrun >= 1);
    CHECK(syscall(SYS_timer_getoverrun, id) == info.si_overrun);
    struct itimerspec setting;
    CHECK(syscall(SYS_timer_gettime, id, &setting) == 0 && setting.it_interval.tv_nsec == 2000000);
    setting.it_value.tv_nsec = 1000000000;
    CHECK(syscall(SYS_timer_settime, id, 0, &setting, NULL) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_timer_gettime, id, &setting) == 0 && setting.it_interval.tv_nsec == 2000000);
    CHECK(syscall(SYS_timer_delete, id) == 0);
    CHECK(syscall(SYS_timer_gettime, id, &setting) == -1 && errno == EINVAL);
    // Linux may retain a queued signal from the deleted timer on older kernels.
    sigset_t set; sigemptyset(&set); sigaddset(&set, realtime); struct timespec zero = {0};
    while (sigtimedwait(&set, &info, &zero) >= 0) {}
    CHECK(errno == EAGAIN);
    id = make_timer(SIGEV_NONE, 0, 0, 0); arm_timer(id, 20000, 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) { CHECK(syscall(SYS_timer_gettime, id, &setting) == -1 && errno == EINVAL); _exit(0); }
    reap(child);
    CHECK(syscall(SYS_timer_gettime, id, &setting) == 0 && setting.it_value.tv_sec >= 18);
    CHECK(syscall(SYS_timer_delete, id) == 0);
    CHECK(syscall(SYS_timer_create, CLOCK_MONOTONIC, NULL, &id) == 0); arm_timer(id, 5, 0);
    info = wait_signal(SIGALRM); CHECK(info.si_code == SI_TIMER && info.si_value.sival_int == id);
    CHECK(syscall(SYS_timer_delete, id) == 0);
    struct sigevent event = {.sigev_notify = SIGEV_NONE};
    CHECK(syscall(SYS_timer_create, CLOCK_MONOTONIC, &event, (void *)1) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_timer_create, CLOCK_MONOTONIC, (void *)1, &id) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_timer_create, -1234, &event, &id) == -1 && errno == EINVAL);
    if (direct) {
        event.sigev_notify = SIGEV_SIGNAL; event.sigev_signo = SIGSYS;
        CHECK(syscall(SYS_timer_create, CLOCK_MONOTONIC, &event, &id) == -1 && errno == EOPNOTSUPP);
    }
    id = make_timer(SIGEV_NONE, SIGSYS, 0, 0); CHECK(syscall(SYS_timer_delete, id) == 0);
    puts("PASS POSIX timer expiration/overrun/default signal, errors and fork noninheritance");
}
static void cpu_timers(void)
{
    const clockid_t clocks[] = {CLOCK_PROCESS_CPUTIME_ID, CLOCK_THREAD_CPUTIME_ID};
    for (unsigned i = 0; i < sizeof(clocks) / sizeof(clocks[0]); i++) {
        struct sigevent event = {.sigev_notify = SIGEV_SIGNAL, .sigev_signo = realtime, .sigev_value.sival_int = 350 + (int)i};
        int id; CHECK(syscall(SYS_timer_create, clocks[i], &event, &id) == 0); arm_timer(id, 5, 0);
        struct timespec start, now; CHECK(clock_gettime(clocks[i], &start) == 0);
        do { CHECK(clock_gettime(clocks[i], &now) == 0); }
        while ((now.tv_sec - start.tv_sec) * 1000000000L + now.tv_nsec - start.tv_nsec < 20000000);
        siginfo_t info = wait_signal(realtime); CHECK(info.si_code == SI_TIMER && info.si_value.sival_int == 350 + (int)i);
        CHECK(syscall(SYS_timer_delete, id) == 0);
    }
    puts("PASS process and thread CPU-clock timer expiration");
}
static atomic_int worker_tid, callback_value;
static int worker_timer;
static void *target_thread(void *unused)
{
    (void)unused; atomic_store(&worker_tid, syscall(SYS_gettid));
    siginfo_t info = wait_signal(realtime);
    CHECK(info.si_code == SI_QUEUE && info.si_value.sival_int == 401);
    info = wait_signal(realtime); CHECK(info.si_code == SI_TIMER && info.si_value.sival_int == 402);
    worker_timer = make_timer(SIGEV_NONE, 0, 0, 0); arm_timer(worker_timer, 20000, 0); return NULL;
}
static void timer_callback(union sigval value) { atomic_store(&callback_value, value.sival_int); }
static void thread_timers(void)
{
    pthread_t worker; CHECK(pthread_create(&worker, NULL, target_thread, NULL) == 0);
    while (!atomic_load(&worker_tid)) sleep_ms(1);
    siginfo_t info = {.si_signo = realtime, .si_code = SI_QUEUE, .si_pid = getpid(), .si_uid = getuid()};
    info.si_value.sival_int = 401;
    CHECK(syscall(SYS_rt_tgsigqueueinfo, getpid(), atomic_load(&worker_tid), realtime, &info) == 0);
    int id = make_timer(SIGEV_THREAD_ID, realtime, 402, atomic_load(&worker_tid)); arm_timer(id, 10, 0);
    CHECK(pthread_join(worker, NULL) == 0); CHECK(syscall(SYS_timer_delete, id) == 0);
    struct itimerspec current;
    CHECK(syscall(SYS_timer_gettime, worker_timer, &current) == 0 && current.it_value.tv_sec >= 18);
    CHECK(syscall(SYS_timer_delete, worker_timer) == 0);
    timer_t timer; struct sigevent event = {.sigev_notify = SIGEV_THREAD, .sigev_notify_function = timer_callback,
        .sigev_value.sival_int = 403};
    CHECK(timer_create(CLOCK_MONOTONIC, &event, &timer) == 0);
    struct itimerspec setting = {.it_value = {.tv_nsec = 10000000}}; CHECK(timer_settime(timer, 0, &setting, NULL) == 0);
    for (int i = 0; i < 3000 && !atomic_load(&callback_value); i++) sleep_ms(1);
    CHECK(atomic_load(&callback_value) == 403); CHECK(timer_delete(timer) == 0);
    puts("PASS thread-targeted queue/timer, creator-thread exit and libc SIGEV_THREAD callback");
}
static void exec_check(char **argv)
{
    int id = atoi(argv[2]), fd = atoi(argv[3]), closed = atoi(argv[4]), signal_fd = atoi(argv[5]);
    struct itimerspec current; CHECK(syscall(SYS_timer_gettime, id, &current) == -1 && errno == EINVAL);
    struct itimerval interval; CHECK(getitimer(ITIMER_REAL, &interval) == 0 && interval.it_value.tv_sec >= 18);
    CHECK(fcntl(closed, F_GETFD) == -1 && errno == EBADF);
    uint64_t ticks; CHECK(read(fd, &ticks, sizeof(ticks)) == sizeof(ticks) && ticks == 1);
    struct signalfd_siginfo info; CHECK(read(signal_fd, &info, sizeof(info)) == sizeof(info) && info.ssi_int == 501);
    interval = (struct itimerval){0}; CHECK(setitimer(ITIMER_REAL, &interval, NULL) == 0);
    CHECK(close(fd) == 0 && close(signal_fd) == 0);
    puts("PASS exec deletes POSIX timer, preserves itimer/pending signal/FD and closes CLOEXEC");
}
static void *exec_sibling(void *unused) { (void)unused; sleep_ms(10000); return NULL; }
static void exec_lifetime(const char *self, int threaded)
{
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        int id = make_timer(SIGEV_NONE, 0, 0, 0); arm_timer(id, 20000, 0);
        struct itimerval interval = {.it_value = {.tv_sec = 30}}; CHECK(setitimer(ITIMER_REAL, &interval, NULL) == 0);
        int fd = timerfd_create(CLOCK_MONOTONIC, 0), closed = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC);
        CHECK(fd >= 0 && closed >= 0);
        struct itimerspec setting = {.it_value = {.tv_nsec = 1000000}}; CHECK(timerfd_settime(fd, 0, &setting, NULL) == 0);
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGUSR2);
        int signal_fd = signalfd(-1, &mask, 0); CHECK(signal_fd >= 0);
        CHECK(sigqueue(getpid(), SIGUSR2, (union sigval){.sival_int = 501}) == 0);
        pthread_t sibling;
        if (threaded) CHECK(pthread_create(&sibling, NULL, exec_sibling, NULL) == 0);
        char ids[4][32]; snprintf(ids[0], 32, "%d", id); snprintf(ids[1], 32, "%d", fd);
        snprintf(ids[2], 32, "%d", closed); snprintf(ids[3], 32, "%d", signal_fd);
        char *args[] = {(char *)self, "exec-check", ids[0], ids[1], ids[2], ids[3], NULL};
        execv(self, args); CHECK(0);
    }
    reap(child);
    puts(threaded ? "PASS timer lifetime across multithreaded kernel exec" : "PASS timer lifetime across single-thread exec");
}
int main(int argc, char **argv)
{
    realtime = SIGRTMIN + 3;
    if (argc == 6 && !strcmp(argv[1], "exec-check")) { exec_check(argv); return 0; }
    // Android's ProcessBuilder may block SIGUSR1 in its launch thread. This
    // fixture explicitly chooses its initial mask; the exec helper above must
    // retain the mask whose inheritance it is testing.
    sigset_t initial; sigemptyset(&initial);
    CHECK(pthread_sigmask(SIG_SETMASK, &initial, NULL) == 0);
    int direct = argc == 2 && !strcmp(argv[1], "direct");
    interval_timers(); queued_signals(direct); signal_descriptors(direct); timer_descriptors();
    posix_timers(direct); cpu_timers(); exec_lifetime(argv[0], 0); exec_lifetime(argv[0], 1); thread_timers();
    puts("PASS timer and queued-signal compatibility"); return 0;
}
