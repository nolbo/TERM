#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdio_ext.h>
#include <stdlib.h>
#include <unistd.h>
int main(void) {
    unsigned (*checks)(void) = dlsym(RTLD_DEFAULT, "terminal_test_stdio_checks");
    assert(checks && checks() == 0);
    char path[] = "/tmp/terminal-stdio-pool-XXXXXX";
    int fd = mkstemp(path); assert(fd >= 0);
    assert(write(fd, "x", 1) == 1 && !close(fd));
    FILE *live[32];
    for (int i = 0; i < 32; i++) {
        live[i] = fopen(path, "r"); assert(live[i] && __fbufsize(live[i]) == 65536);
        assert(fgetc(live[i]) == 'x');
    }
    assert(checks() == 64);
    for (int i = 0; i < 1000; i++) {
        FILE *f = fopen(path, "r"); assert(f && __fbufsize(f) != 65536);
        assert(fgetc(f) == 'x' && !fclose(f));
    }
    assert(checks() == 64); /* No more candidate metadata checks after saturation. */
    for (int i = 0; i < 32; i++) assert(!fclose(live[i]));
    for (int i = 0; i < 1000; i++) {
        FILE *f = fopen(path, "r"); assert(f && __fbufsize(f) == 65536);
        assert(fgetc(f) == 'x' && !fclose(f));
    }
    assert(checks() == 2064);
    assert(!unlink(path));
    puts("PASS: full-pool opens add zero checks; 1000 later streams reuse buffers");
}
