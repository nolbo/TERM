/* SPDX-License-Identifier: GPL-2.0-or-later
 * Opt-in experiment, not a packaged runtime input. Keep caller-owned setvbuf
 * storage until fclose returns. Cancelled closes retain their slots; no FILE ABI.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
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
static _Atomic uint32_t reserved;
static _Atomic(FILE *) owners[SLOTS];
static pthread_once_t once = PTHREAD_ONCE_INIT;
static FILE *(*real_fopen)(const char *, const char *);
static FILE *(*real_fopen64)(const char *, const char *);
static int (*real_fclose)(FILE *);
static int enabled;
static void initialize(void) {
    real_fopen = dlsym(RTLD_NEXT, "fopen");
    real_fopen64 = dlsym(RTLD_NEXT, "fopen64");
    real_fclose = dlsym(RTLD_NEXT, "fclose");
    const char *flag = getenv("PROOT_STDIO_BUFFER");
    enabled = flag && !strcmp(flag, "1");
}
static FILE *tune(FILE *stream, const char *mode) {
    if (!stream || !enabled || !mode || (mode[0] != 'r' && mode[0] != 'w') ||
        strchr(mode, '+') || strchr(mode, 'm')) return stream;
    // A full pool adds no eligibility syscalls to later opens.
    if (atomic_load_explicit(&reserved, memory_order_relaxed) == UINT32_MAX) return stream;
    int saved = errno;
    struct stat st;
    int fd = fileno(stream), flags = fcntl(fd, F_GETFL);
    if (flags < 0 || flags & (O_DIRECT | O_SYNC | O_DSYNC | O_NONBLOCK) ||
        fstat(fd, &st) || !S_ISREG(st.st_mode)) goto out;
    uint32_t observed = atomic_load_explicit(&reserved, memory_order_relaxed);
    while (observed != UINT32_MAX) {
        unsigned slot = (unsigned)__builtin_ctz(~observed);
        uint32_t bit = UINT32_C(1) << slot;
        if (atomic_compare_exchange_weak_explicit(&reserved, &observed, observed | bit,
                memory_order_acquire, memory_order_relaxed)) {
            // Even a failed setvbuf conservatively retains storage until close.
            (void)setvbuf(stream, (char *)buffers[slot], _IOFBF, BUFFER_SIZE);
            atomic_store_explicit(&owners[slot], stream, memory_order_release);
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

int fclose(FILE *stream) {
    pthread_once(&once, initialize);
    if (!real_fclose) { errno = ENOSYS; return EOF; }
    uint32_t closing = 0;
    if (enabled) {
        // Detach BEFORE libc can free/reuse the FILE address. Collect all stale
        // associations too (e.g. failed freopen), never search again after close.
        uint32_t occupied = atomic_load_explicit(&reserved, memory_order_acquire);
        while (occupied) {
            unsigned slot = (unsigned)__builtin_ctz(occupied);
            occupied &= occupied - 1;
            if (atomic_load_explicit(&owners[slot], memory_order_acquire) != stream) continue;
            FILE *expected = stream;
            if (atomic_compare_exchange_strong_explicit(&owners[slot], &expected, NULL,
                    memory_order_acq_rel, memory_order_relaxed))
                closing |= UINT32_C(1) << slot;
        }
    }
    int result = real_fclose(stream);
    int saved = errno;
    // Cancellation/longjmp before return deliberately leaves these bits reserved:
    // the stream may still use its buffer. Never release from a cleanup handler.
    if (closing) atomic_fetch_and_explicit(&reserved, ~closing, memory_order_release);
    errno = saved;
    return result;
}
