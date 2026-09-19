/* Test-only next-preload fclose: cancellation and FILE-address reuse windows. */
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdatomic.h>
#include <stdio.h>
static pthread_once_t once = PTHREAD_ONCE_INIT;
static int (*libc_close)(FILE *);
static sem_t entered, blocked;
static _Atomic(FILE *) target;
static const char *reopen_path;
static FILE *replacement;
static void initialize(void) {
    libc_close = dlsym(RTLD_NEXT, "fclose"); assert(libc_close);
    assert(!sem_init(&entered, 0, 0) && !sem_init(&blocked, 0, 0));
}
void terminal_gate_set(FILE *stream, const char *path) {
    pthread_once(&once, initialize);
    reopen_path = path; replacement = NULL; atomic_store(&target, stream);
}
void terminal_gate_wait(void) { assert(!sem_wait(&entered)); }
FILE *terminal_gate_replacement(void) { return replacement; }
int fclose(FILE *stream) {
    pthread_once(&once, initialize);
    if (atomic_load(&target) != stream) return libc_close(stream);
    if (!reopen_path) {
        assert(!sem_post(&entered));
        sem_wait(&blocked); /* Cancellation leaves the original FILE open. */
        assert(0);
    }
    atomic_store(&target, NULL);
    int result = libc_close(stream);
    replacement = fopen(reopen_path, "w"); assert(replacement);
    assert(fputs("replacement stays buffered", replacement) >= 0);
    return result;
}
