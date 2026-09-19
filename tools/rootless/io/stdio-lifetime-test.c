#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdio_ext.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/resource.h>
static void *close_worker(void *stream) { fclose(stream); return NULL; }
static void check_text(const char *path, const char *expected) {
    FILE *f = fopen(path, "r"); assert(f);
    char text[128]; assert(fgets(text, sizeof(text), f));
    assert(!strcmp(text, expected) && !fclose(f));
}
int main(void) {
    alarm(30);
    void (*set_gate)(FILE *, const char *) = dlsym(RTLD_DEFAULT, "terminal_gate_set");
    void (*wait_gate)(void) = dlsym(RTLD_DEFAULT, "terminal_gate_wait");
    FILE *(*replacement)(void) = dlsym(RTLD_DEFAULT, "terminal_gate_replacement");
    assert(set_gate && wait_gate && replacement);
    char path[] = "/tmp/terminal-stdio-lifetime-XXXXXX";
    int fd = mkstemp(path); assert(fd >= 0 && !close(fd));
    FILE *original = fopen(path, "w"); assert(original && __fbufsize(original) == 65536);
    assert(fputs("cancelled close keeps its buffer", original) >= 0);
    set_gate(original, NULL);
    pthread_t thread; assert(!pthread_create(&thread, NULL, close_worker, original));
    wait_gate(); assert(!pthread_cancel(thread));
    void *result; assert(!pthread_join(thread, &result) && result == PTHREAD_CANCELED);
    set_gate(NULL, NULL);
    FILE *live[32];
    for (int i = 0; i < 32; i++) {
        live[i] = fopen(path, "r"); assert(live[i]);
        assert((__fbufsize(live[i]) == 65536) == (i < 31));
    }
    for (int i = 0; i < 32; i++) assert(!fclose(live[i]));
    assert(!fflush(original) && !fclose(original));
    check_text(path, "cancelled close keeps its buffer");
    // libc can hand the freed FILE address to another fopen before our close returns.
    original = fopen(path, "r"); assert(original);
    uintptr_t old_address = (uintptr_t)original;
    set_gate(original, path);
    assert(!fclose(original));
    FILE *next = replacement(); assert(next && __fbufsize(next) == 65536);
    int address_reused = old_address == (uintptr_t)next;
    for (int i = 0; i < 31; i++) {
        live[i] = fopen(path, "r"); assert(live[i]);
        assert((__fbufsize(live[i]) == 65536) == (i < 30));
    }
    for (int i = 0; i < 31; i++) assert(!fclose(live[i]));
    assert(!fflush(next) && !fclose(next));
    check_text(path, "replacement stays buffered");
    // fclose failure still closes the stream; its buffer must become reusable.
    struct rlimit previous, limited;
    assert(!getrlimit(RLIMIT_FSIZE, &previous)); limited = previous; limited.rlim_cur = 0;
    void (*old_handler)(int) = signal(SIGXFSZ, SIG_IGN);
    original = fopen(path, "w"); assert(original);
    assert(fputs("cannot flush", original) >= 0 && !setrlimit(RLIMIT_FSIZE, &limited));
    errno = 0; assert(fclose(original) == EOF && errno == EFBIG);
    assert(!setrlimit(RLIMIT_FSIZE, &previous)); signal(SIGXFSZ, old_handler);
    for (int i = 0; i < 32; i++) {
        live[i] = fopen(path, "r"); assert(live[i]);
        assert((__fbufsize(live[i]) == 65536) == (i < 31));
    }
    for (int i = 0; i < 32; i++) assert(!fclose(live[i]));
    assert(!unlink(path));
    printf("PASS: cancelled close retains buffer; replacement stream protected (FILE address reused=%d)\n", address_reused);
}
