#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdio_ext.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static char directory[] = "/tmp/terminal-stdio-XXXXXX";
static void *worker(void *argument) {
    char path[256];
    snprintf(path, sizeof(path), "%s/thread-%ld", directory, (long)argument);
    FILE *f = fopen(path, "w");
    assert(f);
    assert(__fbufsize(f) == 65536);
    for (int n = 0; n < 1000; n++) assert(fputs("buffered content\n", f) >= 0);
    assert(fflush(f) == 0 && fsync(fileno(f)) == 0);
    assert(fclose(f) == 0);
    f = fopen(path, "r");
    assert(f);
    char line[64];
    for (int n = 0; n < 1000; n++) {
        assert(fgets(line, sizeof(line), f));
        assert(!strcmp(line, "buffered content\n"));
    }
    assert(fgetc(f) == EOF && feof(f));
    assert(fclose(f) == 0 && unlink(path) == 0);
    return NULL;
}
int main(void) {
    assert(mkdtemp(directory));
    pthread_t threads[4];
    for (long i = 0; i < 4; i++) assert(!pthread_create(&threads[i], NULL, worker, (void *)i));
    for (int i = 0; i < 4; i++) assert(!pthread_join(threads[i], NULL));
    char path[256]; snprintf(path, sizeof(path), "%s/file", directory);
    FILE *f = fopen64(path, "w"); assert(f && __fbufsize(f) == 65536);
    assert(fputs("first", f) >= 0);
    assert(fflush(f) == 0);
    int fd = open(path, O_RDONLY); assert(fd >= 0);
    char text[16] = {0}; assert(read(fd, text, sizeof(text)) == 5 && !strcmp(text, "first"));
    assert(!close(fd));
    f = freopen(path, "r", f); assert(f);
    assert(fgets(text, sizeof(text), f) && !strcmp(text, "first"));
    assert(!fclose(f));
    f = fopen(path, "w"); assert(f);
    char caller_buffer[8192];
    assert(!setvbuf(f, caller_buffer, _IOFBF, sizeof(caller_buffer)));
    assert(__fbufsize(f) == sizeof(caller_buffer));
    assert(fputs("caller", f) >= 0 && !fclose(f));
    f = fopen(path, "r+"); assert(f && __fbufsize(f) != 65536);
    assert(!fseek(f, 0, SEEK_END) && fputs(" update", f) >= 0 && !fclose(f));
    f = fopen("/dev/null", "w"); assert(f && __fbufsize(f) != 65536 && !fclose(f));
    f = fopen(path, "r"); assert(f);
    pid_t child = fork(); assert(child >= 0);
    if (!child) { assert(fgetc(f) == 'c'); assert(!fclose(f)); _exit(0); }
    int status; assert(waitpid(child, &status, 0) == child && status == 0);
    assert(!fseek(f, 0, SEEK_SET) && fgetc(f) == 'c' && !fclose(f));
    int fallback = 0;
    for (int i = 0; i < 80; i++) {
        f = fopen(path, "r"); assert(f);
        if (__fbufsize(f) == 65536) fallback++;
        assert(fgetc(f) == 'c' && !fclose(f));
    }
    assert(fallback == 80); /* Closing streams recycles their bounded storage. */
    errno = 0; assert(!fopen("/nonexistent/terminal-test", "r") && errno == ENOENT);
    assert(!unlink(path) && !rmdir(directory));
    puts("PASS stdio: threads, content, fflush/fsync, reopen, caller buffer, update mode, device, fork, exhaustion, errno");
}
