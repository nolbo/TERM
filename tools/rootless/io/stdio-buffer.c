/* SPDX-License-Identifier: GPL-2.0-or-later
 * Opt-in experiment, not a packaged runtime input. Keep caller-owned setvbuf
 * storage alive for the process lifetime: no fclose/freopen hooks or FILE ABI.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#if !defined(__GLIBC__)
#error "This experiment requires glibc"
#endif
#define SLOTS 32
#define BUFFER_SIZE (64 * 1024)
static unsigned char buffers[SLOTS][BUFFER_SIZE];
static _Atomic unsigned next_slot;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static FILE *(*real_fopen)(const char *, const char *);
static FILE *(*real_fopen64)(const char *, const char *);
static int enabled;
static void initialize(void) {
    real_fopen = dlsym(RTLD_NEXT, "fopen");
    real_fopen64 = dlsym(RTLD_NEXT, "fopen64");
    const char *flag = getenv("PROOT_STDIO_BUFFER");
    enabled = flag && !strcmp(flag, "1");
}
static FILE *tune(FILE *stream, const char *mode) {
    if (!stream || !enabled || !mode || (mode[0] != 'r' && mode[0] != 'w') ||
        strchr(mode, '+') || strchr(mode, 'm')) return stream;
    // Once exhausted, avoid two extra syscalls on every later small-file open.
    if (atomic_load_explicit(&next_slot, memory_order_relaxed) >= SLOTS) return stream;
    int saved = errno;
    struct stat st;
    int fd = fileno(stream), flags = fcntl(fd, F_GETFL);
    if (flags < 0 || flags & (O_DIRECT | O_SYNC | O_DSYNC | O_NONBLOCK) ||
        fstat(fd, &st) || !S_ISREG(st.st_mode)) goto out;
    /* Saturating allocation: never wrap around or recycle storage behind a
     * live FILE, including after freopen, cancellation, fork or caller setvbuf. */
    unsigned slot = atomic_load_explicit(&next_slot, memory_order_relaxed);
    while (slot < SLOTS) {
        if (atomic_compare_exchange_weak_explicit(&next_slot, &slot, slot + 1,
                memory_order_relaxed, memory_order_relaxed)) {
            (void)setvbuf(stream, (char *)buffers[slot], _IOFBF, BUFFER_SIZE);
            break;
        }
    }
out:
    errno = saved;
    return stream;
}
FILE *fopen(const char *path, const char *mode) {
    pthread_once(&once, initialize);
    if (!real_fopen) { errno = ENOSYS; return NULL; }
    return tune(real_fopen(path, mode), mode);
}
FILE *fopen64(const char *path, const char *mode) {
    pthread_once(&once, initialize);
    if (!real_fopen64) { errno = ENOSYS; return NULL; }
    return tune(real_fopen64(path, mode), mode);
}
