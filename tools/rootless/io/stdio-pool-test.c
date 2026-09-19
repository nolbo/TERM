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
    for (int i = 0; i < 32; i++) {
        FILE *f = fopen(path, "r"); assert(f && __fbufsize(f) == 65536);
        assert(fgetc(f) == 'x' && !fclose(f));
    }
    assert(checks() == 64);
    for (int i = 0; i < 1000; i++) {
        FILE *f = fopen(path, "r"); assert(f && __fbufsize(f) != 65536);
        assert(fgetc(f) == 'x' && !fclose(f));
    }
    assert(checks() == 64); /* No more candidate metadata checks after saturation. */
    assert(!unlink(path));
    puts("PASS: 1000 post-exhaustion opens add zero candidate fcntl/fstat calls");
}
