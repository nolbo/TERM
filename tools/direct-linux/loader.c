/* SPDX-License-Identifier: Apache-2.0 */
/* Bionic is used only during preparation. After entry all runtime code is
 * freestanding and must not touch Bionic TLS, PLT functions or allocators. */
#define _GNU_SOURCE
#include "runtime.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define IMAGE_LIMIT (128UL * 1024 * 1024)
#define STACK_SIZE (8UL * 1024 * 1024)
#define PH_LIMIT 128
#define ARG_LIMIT 128

struct image {
    uintptr_t base, entry, phdr;
    size_t phnum;
    void *reservation;
    size_t reservation_size;
    char interpreter[DL_PATH];
};

static int fail(const char *message)
{
    fprintf(stderr, "Direct Linux: %s\n", message);
    return -1;
}
static uintptr_t down(uintptr_t x) { return x & ~(dl_state.page - 1); }
static uintptr_t up(uintptr_t x) { return (x + dl_state.page - 1) & ~(dl_state.page - 1); }
static int range(uint64_t start, uint64_t size, uint64_t limit) { return start <= limit && size <= limit - start; }

static int pread_all(int fd, void *target, size_t length, off_t offset)
{
    unsigned char *p = target;
    while (length) {
        ssize_t n = pread(fd, p, length, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return fail("truncated or unreadable ELF");
        p += n; length -= n; offset += n;
    }
    return 0;
}

static int load_image(const char *path, struct image *out, int interpreter)
{
    int fd = (int)dl_open(AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return fail("cannot open guest ELF");
    struct stat st;
    Elf64_Ehdr header;
    Elf64_Phdr ph[PH_LIMIT];
    int result = -1;
    uintptr_t low = UINTPTR_MAX, high = 0, alignment = dl_state.page, phdr = 0;
    int loads = 0, interpreters = 0, executable_entry = 0;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size < (off_t)sizeof(header) ||
            (uint64_t)st.st_size > IMAGE_LIMIT || !(st.st_mode & 0111) || (st.st_mode & (S_ISUID | S_ISGID))) {
        fail("ELF must be a bounded executable regular file without set-ID bits"); goto done;
    }
    if (pread_all(fd, &header, sizeof(header), 0)) goto done;
    if (memcmp(header.e_ident, ELFMAG, SELFMAG) || header.e_ident[EI_CLASS] != ELFCLASS64 ||
            header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_ident[EI_VERSION] != EV_CURRENT ||
            header.e_machine != EM_AARCH64 || header.e_version != EV_CURRENT ||
            (header.e_type != ET_EXEC && header.e_type != ET_DYN) ||
            header.e_ehsize != sizeof(header) || header.e_phentsize != sizeof(Elf64_Phdr) ||
            !header.e_phnum || header.e_phnum > PH_LIMIT ||
            !range(header.e_phoff, header.e_phnum * sizeof(Elf64_Phdr), (uint64_t)st.st_size)) {
        fail("invalid or unsupported ARM64 ELF header"); goto done;
    }
    if (pread_all(fd, ph, header.e_phnum * sizeof(Elf64_Phdr), header.e_phoff)) goto done;
    for (unsigned i = 0; i < header.e_phnum; i++) {
        Elf64_Phdr *p = ph + i;
        if (p->p_type == PT_INTERP) {
            if (interpreter || ++interpreters != 1 || p->p_filesz < 2 || p->p_filesz > DL_PATH ||
                    !range(p->p_offset, p->p_filesz, st.st_size)) {
                fail("invalid ELF interpreter"); goto done;
            }
            if (pread_all(fd, out->interpreter, p->p_filesz, p->p_offset)) goto done;
            if (out->interpreter[0] != '/' || out->interpreter[p->p_filesz - 1] ||
                    strlen(out->interpreter) != p->p_filesz - 1) {
                fail("invalid ELF interpreter path"); goto done;
            }
        }
        if (p->p_type == PT_GNU_STACK && (p->p_flags & PF_X)) {
            fail("executable guest stack is unsupported"); goto done;
        }
        if (p->p_type != PT_LOAD) continue;
        if (!p->p_memsz) {
            if (p->p_filesz) { fail("LOAD file bytes exceed memory size"); goto done; }
            continue;
        }
        if (p->p_filesz > p->p_memsz || !range(p->p_offset, p->p_filesz, st.st_size) ||
                !range(p->p_vaddr, p->p_memsz, UINTPTR_MAX - dl_state.page) ||
                p->p_memsz > IMAGE_LIMIT || p->p_flags & ~(PF_R | PF_W | PF_X) ||
                ((p->p_flags & (PF_W | PF_X)) == (PF_W | PF_X)) ||
                (p->p_align > 1 && ((p->p_align & (p->p_align - 1)) || p->p_align > IMAGE_LIMIT ||
                    (p->p_vaddr % p->p_align != p->p_offset % p->p_align))) ||
                p->p_vaddr % dl_state.page != p->p_offset % dl_state.page) {
            fail("invalid, unaligned or writable-executable LOAD segment"); goto done;
        }
        uintptr_t begin = down(p->p_vaddr), end = up(p->p_vaddr + p->p_memsz);
        // Reject ambiguous overlapping pages before making any executable mapping.
        for (unsigned j = 0; j < i; j++) {
            if (ph[j].p_type == PT_LOAD && ph[j].p_memsz && begin < up(ph[j].p_vaddr + ph[j].p_memsz) &&
                    down(ph[j].p_vaddr) < end) {
                fail("overlapping LOAD pages"); goto done;
            }
        }
        if (begin < low) low = begin;
        if (end > high) high = end;
        if (p->p_align > alignment) alignment = p->p_align;
        if ((p->p_flags & PF_X) && header.e_entry >= p->p_vaddr && header.e_entry - p->p_vaddr < p->p_filesz)
            executable_entry = 1;
        if ((p->p_flags & PF_R) && header.e_phoff >= p->p_offset &&
                range(header.e_phoff - p->p_offset, header.e_phnum * sizeof(Elf64_Phdr), p->p_filesz))
            phdr = p->p_vaddr + header.e_phoff - p->p_offset;
        loads++;
    }
    if (!loads || high - low > IMAGE_LIMIT || high > (1ULL << 48) ||
            (header.e_type == ET_DYN && low > IMAGE_LIMIT) || !executable_entry || !phdr) {
        fail("ELF lacks a bounded image, executable entry or mapped program headers"); goto done;
    }
    size_t span = high - low;
    void *reserved;
    if (header.e_type == ET_EXEC) {
        reserved = mmap((void *)low, span, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        if (reserved != (void *)low) {
            if (reserved != MAP_FAILED) munmap(reserved, span);
            fail("fixed ELF address unavailable"); goto done;
        }
        out->reservation_size = span;
        out->base = 0;
    } else {
        reserved = mmap(NULL, span + alignment, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (reserved == MAP_FAILED) { fail("cannot reserve ELF address space"); goto done; }
        out->reservation_size = span + alignment;
        // The load bias, not merely the first mapping, satisfies p_align.
        out->base = ((uintptr_t)reserved - low + alignment - 1) & ~(alignment - 1);
    }
    out->reservation = reserved;
    for (unsigned i = 0; i < header.e_phnum; i++) {
        Elf64_Phdr *p = ph + i;
        if (p->p_type != PT_LOAD || !p->p_memsz) continue;
        uintptr_t begin = out->base + down(p->p_vaddr);
        size_t size = up(p->p_vaddr + p->p_memsz) - down(p->p_vaddr);
        if (mprotect((void *)begin, size, PROT_READ | PROT_WRITE)) { fail("cannot populate ELF mapping"); goto unmap; }
        if (pread_all(fd, (void *)(out->base + p->p_vaddr), p->p_filesz, p->p_offset)) goto unmap;
        __builtin___clear_cache((char *)begin, (char *)(begin + size));
        int prot = ((p->p_flags & PF_R) ? PROT_READ : 0) | ((p->p_flags & PF_W) ? PROT_WRITE : 0) |
            ((p->p_flags & PF_X) ? PROT_EXEC : 0);
        if (mprotect((void *)begin, size, prot)) { fail("ELF final mapping rejected by platform"); goto unmap; }
    }
    out->entry = out->base + header.e_entry;
    out->phdr = out->base + phdr;
    out->phnum = header.e_phnum;
    result = 0; goto done;
unmap:
    munmap(reserved, out->reservation_size); out->reservation = NULL;
done:
    close(fd); return result;
}

static void *guarded(size_t size)
{
    void *base = mmap(NULL, size + 2 * dl_state.page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) return NULL;
    void *usable = (char *)base + dl_state.page;
    if (mprotect(usable, size, PROT_READ | PROT_WRITE)) { munmap(base, size + 2 * dl_state.page); return NULL; }
    return usable;
}

static int check_policy(void)
{
    FILE *status = fopen("/proc/self/status", "r");
    if (!status) return fail("cannot inspect launch policy");
    int untraced = 0, unprivileged = 0;
    int report = getenv("DIRECT_LINUX_POLICY") && !strcmp(getenv("DIRECT_LINUX_POLICY"), "1");
    char line[256];
    while (fgets(line, sizeof(line), status)) {
        unsigned long long value;
        if (sscanf(line, "TracerPid: %llu", &value) == 1) untraced = value == 0;
        if (sscanf(line, "CapEff: %llx", &value) == 1) unprivileged = value == 0;
        if (report && (!strncmp(line, "TracerPid:", 10) || !strncmp(line, "CapEff:", 7) ||
                !strncmp(line, "NoNewPrivs:", 11) || !strncmp(line, "Seccomp:", 8))) fprintf(stderr, "POLICY %s", line);
    }
    fclose(status);
    return untraced && unprivileged && getuid() != 0 ? 0 : fail("ordinary untraced app/user execution required");
}

static uintptr_t make_stack(int argc, char **argv, const struct image *main, const struct image *interpreter)
{
    char *memory = guarded(STACK_SIZE);
    if (!memory) return 0;
    char *top = memory + STACK_SIZE;
    const char *environment[] = {"PATH=/usr/bin:/bin", "HOME=/", "LANG=C", "TMPDIR=/tmp"};
    uintptr_t arguments[ARG_LIMIT], env[4];
    for (int i = 0; i < argc; i++) {
        size_t n = strlen(argv[i]) + 1; if (n > 131072) return 0;
        if ((size_t)(top - memory) < n + 65536) return 0;
        top -= n; memcpy(top, argv[i], n); arguments[i] = (uintptr_t)top;
    }
    for (unsigned i = 0; i < 4; i++) {
        size_t n = strlen(environment[i]) + 1; top -= n; memcpy(top, environment[i], n); env[i] = (uintptr_t)top;
    }
    top -= 16;
    if (syscall(SYS_getrandom, top, 16, 0) != 16) return 0;
    uintptr_t random = (uintptr_t)top;
    top -= sizeof("aarch64"); memcpy(top, "aarch64", sizeof("aarch64"));
    uintptr_t platform = (uintptr_t)top;
    Elf64_auxv_t aux[24]; size_t count = 0;
#define AUX(type, value) do { aux[count].a_type = (type); aux[count++].a_un.a_val = (value); } while (0)
    AUX(AT_PHDR, main->phdr); AUX(AT_PHENT, sizeof(Elf64_Phdr)); AUX(AT_PHNUM, main->phnum);
    AUX(AT_PAGESZ, dl_state.page); AUX(AT_BASE, interpreter->base); AUX(AT_ENTRY, main->entry);
    AUX(AT_FLAGS, 0); AUX(AT_UID, getuid()); AUX(AT_EUID, geteuid()); AUX(AT_GID, getgid()); AUX(AT_EGID, getegid());
    AUX(AT_SECURE, 0); AUX(AT_RANDOM, random); AUX(AT_EXECFN, arguments[0]); AUX(AT_PLATFORM, platform);
    AUX(AT_HWCAP, getauxval(AT_HWCAP)); AUX(AT_HWCAP2, getauxval(AT_HWCAP2)); AUX(AT_CLKTCK, sysconf(_SC_CLK_TCK));
    AUX(AT_SYSINFO_EHDR, getauxval(AT_SYSINFO_EHDR)); AUX(AT_MINSIGSTKSZ, getauxval(AT_MINSIGSTKSZ)); AUX(AT_NULL, 0);
#undef AUX
    size_t bytes = (1 + argc + 1 + 4 + 1) * sizeof(uintptr_t) + count * sizeof(Elf64_auxv_t);
    uintptr_t stack = ((uintptr_t)top - bytes) & ~15UL;
    uintptr_t *words = (uintptr_t *)stack;
    *words++ = argc;
    for (int i = 0; i < argc; i++) *words++ = arguments[i];
    *words++ = 0;
    for (unsigned i = 0; i < 4; i++) *words++ = env[i];
    *words++ = 0; memcpy(words, aux, count * sizeof(Elf64_auxv_t));
    return stack;
}

int main(int argc, char **argv)
{
    if (argc < 3 || argc - 2 > ARG_LIMIT || argv[2][0] != '/') {
        fprintf(stderr, "usage: loader ROOT_DIRECTORY /guest/executable [arguments...]\n"); return 2;
    }
    if (check_policy()) return 2;
    dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    if (dl_state.page < 4096 || dl_state.page > 65536 || (dl_state.page & (dl_state.page - 1))) return 2;
    if (!realpath(argv[1], dl_state.root) || dl_equal(dl_state.root, "/")) { fail("an independent root directory is required"); return 2; }
    if (strlen(argv[2]) >= DL_PATH) return 2;
    strcpy(dl_state.executable, argv[2]);
    dl_state.trace = getenv("DIRECT_LINUX_TRACE") && !strcmp(getenv("DIRECT_LINUX_TRACE"), "1");
    int root = open(dl_state.root, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (root < 0) { fail("cannot open root directory"); return 2; }
    dl_state.root_fd = fcntl(root, F_DUPFD_CLOEXEC, 256); close(root);
    if (dl_state.root_fd < 0 || fchdir(dl_state.root_fd)) { fail("cannot enter root directory"); return 2; }
    struct image main_image = {0}, interpreter = {0};
    if (load_image(argv[2], &main_image, 0)) return 126;
    if (main_image.interpreter[0] && load_image(main_image.interpreter, &interpreter, 1)) return 126;
    void *heap = mmap(NULL, DL_HEAP, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (heap == MAP_FAILED) { fail("cannot reserve guest heap"); return 126; }
    dl_state.heap_start = dl_state.heap_end = dl_state.heap_mapped = (uintptr_t)heap;
    uintptr_t stack = make_stack(argc - 2, argv + 2, &main_image, &interpreter);
    void *signals = guarded(256 * 1024);
    if (!stack || !signals) { fail("cannot construct guest stack"); return 126; }
    fflush(NULL);
    int installed = dl_install(signals, 256 * 1024);
    if (installed) {
        const char error[] = "Direct Linux: syscall dispatcher rejected by platform\n";
        DL_CALL(SYS_write, 2, error, sizeof(error) - 1, 0, 0, 0);
        DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0);
    }
    dl_enter(interpreter.entry ? interpreter.entry : main_image.entry, stack);
}
