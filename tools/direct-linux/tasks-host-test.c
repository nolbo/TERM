/* SPDX-License-Identifier: Apache-2.0 */
/* Production registry, observation and reclamation with real CLONE_VM tasks.
 * Registration below is a component adapter; no guest dispatcher is installed. */
#define _GNU_SOURCE
#include <assert.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "threads.c"
#include "signals.c"
#include "tasks.c"

struct dl_state dl_state = {.root_fd = -1, .vfork_fd = -1};
void dl_copy(void *d, const void *s, size_t n) { memcpy(d, s, n); }
int dl_equal_memory(const void *a, const void *b, size_t n) { return !memcmp(a, b, n); }
long dl_read_memory(void *d, const void *s, size_t n) { memcpy(d, s, n); return 0; }
long dl_write_memory(void *d, const void *s, size_t n) { memcpy(d, s, n); return 0; }

enum { EXIT, KILL, EXEC, WORKER_EXEC, LEADER_EXIT, HIDDEN, EXHAUSTED, EQUAL_STACK, SCENARIOS };
struct job {
    int scenario, read_fd, write_fd, error;
    unsigned ready, go;
    int guest_tid[2];
    struct dl_thread *leader, *worker;
    void *scratch[2];
    const char *executable;
    char descriptor[24];
};
#define CHILD_CHECK(c) do { if (!(c)) { job->error = __LINE__; DL_CALL(SYS_exit_group, 90, 0, 0, 0, 0, 0); } } while (0)

static void spin(unsigned *word, unsigned value)
{
    while (__atomic_load_n(word, __ATOMIC_ACQUIRE) < value) DL_CALL(SYS_sched_yield, 0, 0, 0, 0, 0, 0);
}

static void publish(struct job *job, struct dl_thread *record, unsigned index)
{
    __atomic_store_n(&record->tid, (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    __atomic_store_n(&record->tgid, (int)DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    job->guest_tid[index] = record->tid;
    CHILD_CHECK(DL_CALL(SYS_set_tid_address, &job->guest_tid[index], 0, 0, 0, 0, 0) == record->tid);
    long p = dl_scratch_map(&record->scratch, dl_state.page, PROT_READ | PROT_WRITE);
    CHILD_CHECK(p > 0); job->scratch[index] = (void *)p;
    *(unsigned char *)p = 47;
    __atomic_store_n(&record->state, RUNNING, __ATOMIC_RELEASE);
}

static int execute(struct job *job)
{
    char *args[] = {(char *)job->executable, "--after-exec", job->descriptor, NULL};
    char *env[] = {NULL};
    dl_lock(&thread_lock); dl_exec_mark(1); dl_unlock(&thread_lock);
    CHILD_CHECK(DL_CALL(SYS_execve, "/direct-linux-missing-executable", args, env, 0, 0, 0) == -ENOENT);
    dl_lock(&thread_lock); dl_exec_mark(0); dl_unlock(&thread_lock);
    CHILD_CHECK(*(unsigned char *)job->scratch[job->worker != NULL] == 47);
    dl_lock(&thread_lock); dl_exec_mark(1); dl_unlock(&thread_lock);
    CHILD_CHECK(DL_CALL(SYS_execve, job->executable, args, env, 0, 0, 0) == 0);
    return 91;
}

static int worker(void *opaque)
{
    struct job *job = opaque;
    publish(job, job->worker, 1);
    __atomic_store_n(&job->ready, 2, __ATOMIC_RELEASE);
    spin(&job->go, 2);
    return execute(job);
}

static int child(void *opaque)
{
    struct job *job = opaque;
    CHILD_CHECK(!DL_CALL(SYS_prctl, PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0, 0));
    CHILD_CHECK(!DL_CALL(SYS_close, job->write_fd, 0, 0, 0, 0, 0));
    CHILD_CHECK(!DL_CALL(SYS_prctl, PR_SET_NAME, "a )\nb) name", 0, 0, 0, 0));
    publish(job, job->leader, 0);
    if (job->scenario == HIDDEN) CHILD_CHECK(!DL_CALL(SYS_prctl, PR_SET_DUMPABLE, 0, 0, 0, 0, 0));
    if (job->worker) {
        int flags = CLONE_VM | CLONE_THREAD | CLONE_SIGHAND | CLONE_FILES;
        CHILD_CHECK(clone(worker, (void *)job->worker->stack_end, flags, job) > 0);
        spin(&job->go, 1);
        if (job->scenario == LEADER_EXIT) DL_CALL(SYS_exit, 0, 0, 0, 0, 0, 0);
        for (;;) DL_CALL(SYS_futex, &job->go, FUTEX_WAIT_PRIVATE, 2, NULL, NULL, 0);
    }
    __atomic_store_n(&job->ready, 1, __ATOMIC_RELEASE);
    spin(&job->go, 2);
    if (job->scenario == HIDDEN) CHILD_CHECK(!DL_CALL(SYS_prctl, PR_SET_DUMPABLE, 1, 0, 0, 0, 0));
    if (job->scenario == EXIT) return 0;
    return execute(job);
}

static struct dl_thread *record(unsigned slot, struct dl_sighand *shared)
{
    struct dl_thread *t = threads + slot; assert(t->state == FREE);
    memset(t, 0, sizeof(*t));
    size_t size = DL_SIGNAL_STACK_SIZE + 2 * dl_state.page;
    void *mapping = mmap(NULL, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(mapping != MAP_FAILED);
    void *stack = (char *)mapping + dl_state.page;
    assert(!mprotect(stack, DL_SIGNAL_STACK_SIZE, PROT_READ | PROT_WRITE));
    t->allocation = mapping; t->allocation_size = size;
    t->stack_begin = (uintptr_t)stack; t->stack_end = t->stack_begin + DL_SIGNAL_STACK_SIZE;
    if (shared) { t->sighand = shared; dl_sighand_retain(shared); }
    else {
        dl_lock(dl_sighand_lock(threads[0].sighand));
        assert(!dl_sighand_copy(threads[0].sighand, &t->sighand));
        dl_unlock(dl_sighand_lock(threads[0].sighand));
    }
    t->files_id = 2;
    __atomic_store_n(&t->state, STARTING, __ATOMIC_RELEASE);
    return t;
}

static unsigned collect(void)
{
    dl_lock(&thread_lock); unsigned count = reap(); dl_unlock(&thread_lock); return count;
}
static void pause_poll(void)
{
    struct timespec delay = {.tv_nsec = 1000000}; nanosleep(&delay, NULL);
}
static void unmapped(void *address)
{
    unsigned char resident;
    assert(DL_CALL(SYS_mincore, address, dl_state.page, &resident, 0, 0, 0) == -ENOMEM);
}

static void parser(void)
{
    char data[2048]; int used = snprintf(data, sizeof(data), "123 (a )\nb) name) S ");
    for (unsigned field = 4; field <= 52; field++)
        used += snprintf(data + used, sizeof(data) - (size_t)used, "%ld%c", field == 28 ? 8192L : field == 19 ? -3L : 0L, field == 52 ? '\n' : ' ');
    struct dl_task_status status = {0};
    assert(!parse_status(data, (size_t)used, 123, &status));
    assert(status.tid == 123 && status.state == 'S' && status.start_stack == 8192);
    struct dl_task_status saved = status;
    assert(parse_status(data, (size_t)used, 124, &status) == -EAGAIN);
    assert(parse_status(data, 20, 123, &status) == -EIO);
    assert(parse_status("99999999999999999999", 20, 123, &status) == -EIO);
    assert(status.tid == saved.tid && status.state == saved.state && status.start_stack == saved.start_stack);
    puts("PASS bounded proc parsing: embedded parentheses/newline, signed fields, truncation and unchanged failure output");
}

int main(int argc, char **argv)
{
    if (argc == 3 && !strcmp(argv[1], "--after-exec")) {
        int fd = 0; for (const char *p = argv[2]; *p; p++) fd = fd * 10 + *p - '0';
        char byte; return read(fd, &byte, 1) == 1 && byte == 'x' ? 0 : 92;
    }
#ifdef __ANDROID__
    assert(geteuid() >= 10000);
#endif
    FILE *policy = fopen("/proc/self/status", "r"); assert(policy);
    char line[256];
    while (fgets(line, sizeof(line), policy))
        if (!strncmp(line, "TracerPid:", 10) || !strncmp(line, "CapEff:", 7) ||
                !strncmp(line, "Seccomp:", 8) || !strncmp(line, "NoNewPrivs:", 11)) printf("POLICY %s", line);
    assert(!fclose(policy));
    alarm(50); dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    parser();
    void *parent_stack = mmap(NULL, dl_state.page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(parent_stack != MAP_FAILED);
    dl_thread_init(parent_stack, dl_state.page, parent_stack, dl_state.page);
    assert(kernel_stack && threads[0].tgid == getpid() && threads[0].tid == getpid());
    long sentinel = dl_scratch_map(dl_thread_scratch(), dl_state.page, PROT_READ | PROT_WRITE); assert(sentinel > 0);
    *(unsigned char *)sentinel = 83;
    char executable[4096]; ssize_t size = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    assert(size > 0 && (size_t)size < sizeof(executable) - 1); executable[size] = 0;
    uintptr_t original_stack = kernel_stack;
    for (int scenario = 0; scenario < SCENARIOS; scenario++) for (unsigned iteration = 0; iteration < 32; iteration++) {
        int pipes[2]; assert(!pipe(pipes));
        struct job job = {.scenario = scenario, .read_fd = pipes[0], .write_fd = pipes[1], .executable = executable};
        snprintf(job.descriptor, sizeof(job.descriptor), "%d", pipes[0]);
        job.leader = record(1, NULL);
        if (scenario == WORKER_EXEC || scenario == LEADER_EXIT) job.worker = record(2, job.leader->sighand);
        void *allocations[] = {job.leader->allocation, job.worker ? job.worker->allocation : NULL};
        void *table = job.leader->sighand;
        pid_t pid = clone(child, (void *)job.leader->stack_end, CLONE_VM | SIGCHLD, &job); assert(pid > 0);
        assert(!close(pipes[0]));
        while (__atomic_load_n(&job.ready, __ATOMIC_ACQUIRE) != (job.worker ? 2U : 1U)) pause_poll();
        assert(!job.error);
        unsigned expected = job.worker ? 3 : 2;
        assert(collect() == expected);
        assert(dl_exec_begin() == 1); dl_exec_end();
        if (scenario == HIDDEN) {
            struct dl_task_status status;
            long observed = dl_task_status(pid, &status);
            // Android proc mounts can deny/hide the whole file instead of
            // returning the zeroed ptrace-protected fields used on the host.
            if (!iteration) printf("HIDDEN_PROC result=%ld zero_stack=%d\n", observed, !observed && !status.start_stack);
            assert((!observed && !status.start_stack) || observed == -EACCES || observed == -EPERM || observed == -ENOENT);
            assert(!kill(pid, 0));
            for (unsigned i = 0; i < 16; i++) assert(collect() == expected);
        }
        if (scenario == EXHAUSTED) {
            struct rlimit limit, saved; assert(!getrlimit(RLIMIT_NOFILE, &saved)); limit = saved; limit.rlim_cur = 32;
            assert(!setrlimit(RLIMIT_NOFILE, &limit));
            int fds[32], count = 0, fd;
            while ((fd = dup(0)) >= 0) { assert(count < 32); fds[count++] = fd; }
            assert(errno == EMFILE);
            for (unsigned i = 0; i < 16; i++) assert(collect() == expected);
            for (int i = 0; i < count; i++) assert(!close(fds[i]));
            assert(!setrlimit(RLIMIT_NOFILE, &saved));
        }
        if (scenario == LEADER_EXIT) {
            __atomic_store_n(&job.go, 1, __ATOMIC_RELEASE);
            while (collect() != 2) pause_poll();
            assert(job.worker->state == RUNNING && !job.guest_tid[0]);
            assert(dl_exec_begin() == 1); dl_exec_end();
        }
        if (scenario == KILL) assert(!kill(pid, SIGKILL));
        else __atomic_store_n(&job.go, 2, __ATOMIC_RELEASE);
        if (scenario == EQUAL_STACK) {
            struct dl_task_status status;
            do { assert(!dl_task_status(pid, &status)); pause_poll(); }
            while (!status.start_stack || status.start_stack == original_stack);
            // Simulate an exec reusing the old stack address: no false proof.
            kernel_stack = status.start_stack;
            assert(collect() == expected && job.leader->state == RUNNING);
            kernel_stack = original_stack;
        }
        while (collect() != 1) pause_poll();
        assert(!job.error && *(unsigned char *)sentinel == 83 && !job.guest_tid[0]);
        for (unsigned i = 0; i < (job.worker ? 2U : 1U); i++) {
            unmapped(allocations[i]); unmapped(job.scratch[i]); assert(!job.guest_tid[i]);
        }
        unmapped(table);
        assert(dl_exec_begin() == 0); dl_exec_end();
        if (scenario != EXIT && scenario != KILL) {
            assert(!kill(pid, 0)); // old-VM resources were reclaimed while the new image is alive
            assert(write(pipes[1], "x", 1) == 1);
        }
        assert(!close(pipes[1]));
        int status; assert(waitpid(pid, &status, 0) == pid); // registry must not consume guest wait status
        if (scenario == KILL) assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
        else assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    dl_scratch_clear(dl_thread_scratch()); unmapped((void *)sentinel);
    puts("PASS 256 real shared-VM process lifecycles: exit, SIGKILL, leader/worker exec, leader exit, hidden proc, EMFILE and equal-stack deferral");
    puts("PASS production registry reclamation, exec selection, independent stacks/scratch/sighand, guest clear-TID and unconsumed wait status");
    return 0;
}
