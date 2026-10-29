#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <unistd.h>
#ifdef DYNAMIC_FIXTURE
#include <dlfcn.h>
#endif

static volatile unsigned char bss[256 * 1024 + 13];
static __thread unsigned long tls = 0x12345678;
static int constructed;
__attribute__((constructor)) static void construct(void) { constructed = 1; tls += 3; }
#define REQUIRE(value) do { if (!(value)) { fprintf(stderr, "fixture failure at %d\n", __LINE__); return 90; } } while (0)

int main(int argc, char **argv) {
    REQUIRE(argc == 4 && !strcmp(argv[1], "two words") && !strcmp(argv[2], "") && !strcmp(argv[3], "한글"));
    REQUIRE(getenv("UE_FIXTURE") && !strcmp(getenv("UE_FIXTURE"), "preserved=value"));
    REQUIRE(constructed && tls == 0x1234567b);
    for (size_t i = 0; i < sizeof bss; ++i) REQUIRE(bss[i] == 0);
    bss[sizeof bss - 1] = 91;
    REQUIRE(bss[sizeof bss - 1] == 91);
    REQUIRE(getauxval(AT_PAGESZ) == (unsigned long)sysconf(_SC_PAGESIZE));
    REQUIRE(getauxval(AT_PHENT) == sizeof(Elf64_Phdr));
    REQUIRE(getauxval(AT_SECURE) == 0 && getauxval(AT_RANDOM) != 0);
    REQUIRE(getauxval(AT_UID) == getuid() && getauxval(AT_EUID) == geteuid());
    REQUIRE(getauxval(AT_GID) == getgid() && getauxval(AT_EGID) == getegid());
    REQUIRE(!strcmp((const char *)getauxval(AT_EXECFN), argv[0]));
#ifdef DYNAMIC_FIXTURE
    REQUIRE(getauxval(AT_BASE) != 0);
    void *math = dlopen("libm.so.6", RTLD_NOW | RTLD_LOCAL);
    REQUIRE(math != NULL);
    double (*cosine)(double) = (double (*)(double))dlsym(math, "cos");
    REQUIRE(cosine && cosine(0.0) == 1.0);
    REQUIRE(dlclose(math) == 0);
#else
    REQUIRE(getauxval(AT_BASE) == 0);
#endif
    const Elf64_Phdr *headers = (const Elf64_Phdr *)getauxval(AT_PHDR);
    size_t loads = 0;
    for (unsigned long i = 0; i < getauxval(AT_PHNUM); ++i) {
        if (headers[i].p_type == PT_LOAD) {
            REQUIRE((headers[i].p_flags & (PF_W | PF_X)) != (PF_W | PF_X));
            loads++;
        }
    }
    REQUIRE(loads >= 2 && getauxval(AT_ENTRY) != 0);
    unsigned char *heap = calloc(1, 128 * 1024);
    REQUIRE(heap != NULL);
    for (size_t i = 0; i < 128 * 1024; ++i) { REQUIRE(heap[i] == 0); heap[i] = (unsigned char)i; }
    free(heap);
    for (int fd = 3; fd < 64; ++fd) {
        errno = 0;
        REQUIRE(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
    }
    if (getenv("UE_TRAP_EXPECTED")) REQUIRE(getuid() == 12345 && geteuid() == 12345);
    puts("PASS argv env constructor TLS BSS auxv heap FD-cleanup");
    return 37;
}
