/* Behavioral compatibility gate; no timings, no changes to installed packages. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdio_ext.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

int main(void) {
    char dir[] = "/tmp/terminal-stdio-semantics-XXXXXX", path[256];
    assert(mkdtemp(dir));
    assert(snprintf(path, sizeof(path), "%s/data", dir) > 0);
    const char *flag = getenv("PROOT_STDIO_BUFFER");
    assert(flag && (!strcmp(flag, "0") || !strcmp(flag, "1")));
    int enabled = !strcmp(flag, "1");
    FILE *f = fopen(path, "we");
    assert(f && !!(fcntl(fileno(f), F_GETFD) & FD_CLOEXEC));
    assert((__fbufsize(f) == 65536) == enabled);
    for (int i = 0; i < 70000; ++i) assert(fputc(i % 251, f) != EOF);
    assert(!fflush(f) && !fsync(fileno(f)));
    int fd = open(path, O_RDONLY); assert(fd >= 0);
    unsigned char *map = mmap(NULL, 70000, PROT_READ, MAP_SHARED, fd, 0);
    assert(map != MAP_FAILED);
    for (int i = 0; i < 70000; ++i) assert(map[i] == i % 251);
    assert(!munmap(map, 70000) && !close(fd) && !fclose(f));

    f = fopen(path, "re"); assert(f);
    assert(fgetc(f) == 0 && ftello(f) == 1);
    fpos_t saved; assert(!fgetpos(f, &saved));
    assert(!fseeko(f, 65535, SEEK_SET) && fgetc(f) == 65535 % 251);
    assert(!fsetpos(f, &saved) && fgetc(f) == 1);
    assert(ungetc(99, f) == 99 && fgetc(f) == 99 && fgetc(f) == 2);
    /* POSIX active-handle handoff: synchronize buffered input before raw I/O. */
    assert(!fflush(f) && lseek(fileno(f), 0, SEEK_CUR) == 3);
    unsigned char byte;
    assert(read(fileno(f), &byte, 1) == 1 && byte == 3);
    assert(!fseeko(f, 4, SEEK_SET) && fgetc(f) == 4);
    assert(!fseeko(f, 0, SEEK_END) && fgetc(f) == EOF && feof(f));
    fd = open(path, O_WRONLY | O_APPEND); assert(fd >= 0);
    assert(write(fd, "Z", 1) == 1 && !close(fd));
    clearerr(f);
    assert(fgetc(f) == 'Z' && !ferror(f) && !fclose(f));

    /* A sparse file exercises 64-bit offsets without allocating 4 GiB. */
    f = fopen64(path, "w"); assert(f);
    off_t offset = ((off_t)1 << 32) + 17;
    assert(!fseeko(f, offset, SEEK_SET) && fputc('Q', f) == 'Q');
    assert(ftello(f) == offset + 1 && !fclose(f));
    struct stat st; assert(!stat(path, &st) && st.st_size == offset + 1);
    f = fopen64(path, "r"); assert(f);
    assert(!fseeko(f, offset - 1, SEEK_SET));
    assert(fgetc(f) == 0 && fgetc(f) == 'Q' && fgetc(f) == EOF);
    assert(!fclose(f));
    errno = 0; assert(!fopen(path, "wx") && errno == EEXIST);
    f = fopen(path, "a"); assert(f && __fbufsize(f) != 65536);
    assert(fputc('A', f) == 'A' && !fclose(f));
    assert(!stat(path, &st) && st.st_size == offset + 2);
    assert(!unlink(path) && !rmdir(dir));
    printf("PASS stdio semantics buffer=%d: seek, positions, ungetc, raw handoff, EOF append, mmap visibility, sparse offsets, exclusive/CLOEXEC/append\n", enabled);
}
