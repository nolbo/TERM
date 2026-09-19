/* Test-only linker wrappers count candidate checks, not libc's internal I/O. */
#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <sys/stat.h>
static _Atomic unsigned checks;
extern int __real_fcntl(int, int, ...);
extern int __real_fstat(int, struct stat *);
int __wrap_fcntl(int fd, int command, ...) {
    assert(command == F_GETFL);
    atomic_fetch_add(&checks, 1);
    return __real_fcntl(fd, command);
}
int __wrap_fstat(int fd, struct stat *st) {
    atomic_fetch_add(&checks, 1);
    return __real_fstat(fd, st);
}
unsigned terminal_test_stdio_checks(void) { return atomic_load(&checks); }
