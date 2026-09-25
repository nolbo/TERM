/* SPDX-License-Identifier: Apache-2.0 */
/* Freestanding APK entry, including reentry under an inherited seccomp filter. */
#define _GNU_SOURCE
#include "runtime.h"
#include "threads.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/seccomp.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

extern long dl_guest_syscall(long, long, long, long, long, long, long);

static void print(const char *text) { DL_CALL(SYS_write, 2, text, dl_length(text), 0, 0, 0); }
static size_t number(char *out, unsigned long value)
{
    char digits[24]; size_t count = 0, size = 0;
    do { digits[count++] = '0' + value % 10; value /= 10; } while (value);
    while (count) out[size++] = digits[--count];
    out[size] = 0; return size;
}
static int fail(const char *text, long error)
{
    print("Direct Linux: "); print(text);
    if (error) { char value[24]; number(value, error < 0 ? 0UL - (unsigned long)error : (unsigned long)error); print(" ("); print(value); print(")"); }
    print("\n"); return 126;
}
static int enabled(char **environment, const char *key)
{
    size_t size = dl_length(key);
    for (char **p = environment; *p; p++)
        if (dl_length(*p) == size + 2 && dl_equal_memory(*p, key, size) && (*p)[size] == '=' && (*p)[size + 1] == '1') return 1;
    return 0;
}

struct reader { int fd; size_t position, size; char bytes[4096]; };
static int line(struct reader *reader, char *out, size_t capacity)
{
    size_t used = 0;
    for (;;) {
        if (reader->position == reader->size) {
            long r = DL_CALL(SYS_read, reader->fd, reader->bytes, sizeof(reader->bytes), 0, 0, 0);
            if (r == -EINTR) continue;
            if (r < 0) return (int)r;
            if (!r) { out[used] = 0; return used ? 1 : 0; }
            reader->position = 0; reader->size = (size_t)r;
        }
        char c = reader->bytes[reader->position++];
        if (c == '\n') { out[used] = 0; return 1; }
        if (used == capacity - 1) return -E2BIG;
        out[used++] = c;
    }
}
static int prefix(const char *text, const char *key)
{
    while (*key) if (*key++ != *text++) return 0;
    return 1;
}
static int zero_value(const char *text)
{
    while (*text && *text != ':') text++;
    if (!*text++) return 0;
    while (*text == ' ' || *text == '\t') text++;
    if (!*text) return 0;
    while (*text == '0') text++;
    return !*text;
}
static int check_policy(int report)
{
    struct reader reader; reader.position = reader.size = 0;
    reader.fd = (int)DL_CALL(SYS_openat, AT_FDCWD, "/proc/self/status", O_RDONLY | O_CLOEXEC, 0, 0, 0);
    if (reader.fd < 0) return fail("cannot inspect launch policy", reader.fd);
    int untraced = 0, unprivileged = 0, r; char text[1024];
    while ((r = line(&reader, text, sizeof(text))) > 0) {
        if (prefix(text, "TracerPid:")) untraced = zero_value(text);
        if (prefix(text, "CapEff:")) unprivileged = zero_value(text);
        if (report && (prefix(text, "TracerPid:") || prefix(text, "CapEff:") ||
                prefix(text, "NoNewPrivs:") || prefix(text, "Seccomp:"))) { print("POLICY "); print(text); print("\n"); }
    }
    DL_CALL(SYS_close, reader.fd, 0, 0, 0, 0, 0);
    if (r < 0) return fail("cannot read launch policy", r);
    return untraced && unprivileged && DL_CALL(SYS_getuid, 0, 0, 0, 0, 0, 0) != 0 ? 0
        : fail("ordinary untraced app/user execution required", 0);
}
static int hexadecimal(const char **text, uintptr_t *value)
{
    const char *p = *text; *value = 0;
    while ((*p >= '0' && *p <= '9') || (*p >= 'a' && *p <= 'f')) {
        unsigned digit = *p <= '9' ? (unsigned)(*p - '0') : (unsigned)(*p - 'a' + 10);
        if (*value > (UINTPTR_MAX - digit) / 16) return 0;
        *value = *value * 16 + digit; p++;
    }
    if (p == *text) return 0;
    *text = p; return 1;
}
static long bootstrap_stack_policy(uintptr_t original_stack)
{
    // A static bootstrap has no Bionic/linker/TLS mappings to omit. Keep the
    // kernel vDSO/vvar and runtime; only the initial argv/auxv stack is unused
    // after entering the independently mapped guest and runtime signal stack.
    struct reader reader; reader.position = reader.size = 0;
    reader.fd = (int)DL_CALL(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0, 0, 0);
    if (reader.fd < 0) return reader.fd;
    char text[8192]; unsigned long seen = 0, omitted = 0, bytes = 0; int r;
    while ((r = line(&reader, text, sizeof(text))) > 0) {
        const char *p = text; uintptr_t low, high;
        if (!hexadecimal(&p, &low) || *p++ != '-' || !hexadecimal(&p, &high) || low >= high) { r = -EINVAL; break; }
        seen++;
        if (low <= original_stack && original_stack < high) {
            r = (int)DL_CALL(SYS_madvise, low, high - low, MADV_DONTFORK, 0, 0, 0);
            if (r) break;
            omitted++; bytes += high - low;
        }
    }
    DL_CALL(SYS_close, reader.fd, 0, 0, 0, 0, 0);
    if (r < 0 || omitted != 1) return r < 0 ? r : -EINVAL;
    if (dl_state.startup_profile) {
        char value[24];
        print("STARTUP_HOST {\"mappings\":"); number(value, seen); print(value);
        print(",\"omitted\":"); number(value, omitted); print(value);
        print(",\"virtual_bytes\":"); number(value, bytes); print(value); print("}\n");
    }
    return 0;
}
static long signal_stack(int filtering)
{
    size_t size = DL_SIGNAL_STACK_SIZE + 2 * dl_state.page;
    long mapping = DL_CALL(SYS_mmap, 0, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping < 0) return mapping;
    long r = DL_CALL(SYS_mprotect, mapping + dl_state.page, DL_SIGNAL_STACK_SIZE, PROT_READ | PROT_WRITE, 0, 0, 0);
    if (r) return r;
    dl_thread_init((void *)mapping, size, (void *)(mapping + dl_state.page), DL_SIGNAL_STACK_SIZE);
    return dl_install((void *)(mapping + dl_state.page), DL_SIGNAL_STACK_SIZE, filtering);
}

static int reexec_probe(int argc, char **argv)
{
    unsigned remaining = 200;
    if (argc == 3) {
        remaining = 0;
        for (const char *p = argv[2]; *p; p++) {
            if (*p < '0' || *p > '9' || remaining > 200) return fail("invalid reentry count", 0);
            remaining = remaining * 10 + (unsigned)(*p - '0');
        }
        if (DL_CALL(SYS_prctl, PR_GET_SECCOMP, 0, 0, 0, 0, 0) != SECCOMP_MODE_FILTER)
            return fail("missing inherited filter", 0);
    } else if (argc != 2) return fail("invalid reentry arguments", 0);
    long r = signal_stack(argc == 2); if (r) return fail("reentry signal stack", r);
    // Prove that the handler was restored after kernel exec reset it.
    if (dl_guest_syscall(SYS_rt_sigaction, SIGSYS, 0, 0, 8, 0, 0) != -EOPNOTSUPP)
        return fail("reentry dispatch", 0);
    if (!remaining) { print("PASS 200 kernel exec transitions with the same inherited filter and restored dispatcher\n"); return 0; }
    char count[24]; number(count, remaining - 1);
    char *arguments[] = {argv[0], "--reexec-probe", count, NULL};
    char *environment[] = {NULL};
    r = DL_CALL(SYS_execve, "/proc/self/exe", arguments, environment, 0, 0, 0);
    return fail("kernel runtime reentry", r);
}

int dl_bootstrap(uintptr_t *stack)
{
    int argc = (int)stack[0]; char **argv = (void *)(stack + 1);
    char **environment = argv + argc + 1, **end = environment;
    while (*end) end++;
    const Elf64_auxv_t *aux = (const void *)(end + 1);
    const Elf64_Phdr *headers = NULL; size_t header_count = 0, header_size = 0;
    for (; aux->a_type; aux++) switch (aux->a_type) {
    case AT_PHDR: headers = (const void *)aux->a_un.a_val; break;
    case AT_PHNUM: header_count = aux->a_un.a_val; break;
    case AT_PHENT: header_size = aux->a_un.a_val; break;
    case AT_PAGESZ: dl_state.page = aux->a_un.a_val; break;
    case AT_HWCAP: dl_state.hwcap = aux->a_un.a_val; break;
    case AT_HWCAP2: dl_state.hwcap2 = aux->a_un.a_val; break;
    case AT_SYSINFO_EHDR: dl_state.vdso = aux->a_un.a_val; break;
    case AT_MINSIGSTKSZ: dl_state.minsigstksz = aux->a_un.a_val; break;
    case AT_CLKTCK: dl_state.clktck = aux->a_un.a_val; break;
    }
    if (dl_state.page < 4096 || dl_state.page > 65536 || (dl_state.page & (dl_state.page - 1))) return fail("invalid page size", 0);
    if (!headers || !header_count || header_count > 128 || header_size != sizeof(*headers)) return fail("invalid bootstrap headers", 0);
    // No dynamic linker remains to seal our GOT/relro pages. The fixed image
    // has no relocations, so protect them before processing external input.
    for (size_t i = 0; i < header_count; i++) if (headers[i].p_type == PT_GNU_RELRO) {
        uintptr_t begin = headers[i].p_vaddr & ~(dl_state.page - 1);
        uintptr_t end = (headers[i].p_vaddr + headers[i].p_memsz) & ~(dl_state.page - 1);
        if (end > begin) {
            long r = DL_CALL(SYS_mprotect, begin, end - begin, PROT_READ, 0, 0, 0);
            if (r) return fail("bootstrap RELRO protection", r);
        }
    }
    if (check_policy(enabled(environment, "DIRECT_LINUX_POLICY"))) return 126;
    if (argc >= 2 && dl_equal(argv[1], "--reexec-probe")) return reexec_probe(argc, argv);
    if (argc == 3 && dl_equal(argv[1], "--exec-resume")) {
        unsigned long fd = 0;
        if (!argv[2][0]) return fail("missing exec handoff", 0);
        for (const char *p = argv[2]; *p; p++) {
            if (*p < '0' || *p > '9' || fd > (0x7fffffffUL - (unsigned)(*p - '0')) / 10)
                return fail("invalid exec handoff descriptor", 0);
            fd = fd * 10 + (unsigned)(*p - '0');
        }
        if (DL_CALL(SYS_prctl, PR_GET_SECCOMP, 0, 0, 0, 0, 0) != SECCOMP_MODE_FILTER)
            return fail("missing inherited exec filter", 0);
        struct dl_entry entry;
        long r = dl_exec_resume((int)fd, &entry); if (r) return fail("exec handoff rejected after kernel commit", r);
        r = bootstrap_stack_policy((uintptr_t)stack); if (r) return fail("exec bootstrap inheritance", r);
        r = signal_stack(0); if (r) return fail("exec dispatcher restoration", r);
        dl_thread_self()->signals.virtual_sigsys_mask = entry.virtual_sigsys;
        r = DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &entry.mask, NULL, 8, 0, 0);
        if (r) return fail("exec signal mask restoration", r);
        dl_enter(entry.pc, entry.sp);
    }
    if (argc < 3 || argv[2][0] != '/') return fail("usage: loader ROOT_DIRECTORY /guest/executable [arguments...]", 0);
    dl_state.trace = enabled(environment, "DIRECT_LINUX_TRACE");
    dl_state.exec_profile = enabled(environment, "DIRECT_LINUX_EXEC_PROFILE");
    dl_state.startup_profile = enabled(environment, "DIRECT_LINUX_STARTUP_PROFILE");
    long root = DL_CALL(SYS_openat, AT_FDCWD, argv[1], O_PATH | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
    if (root < 0) return fail("cannot open root directory", root);
    char link[40]; dl_fd_path(link, (int)root);
    long size = DL_CALL(SYS_readlinkat, AT_FDCWD, link, dl_state.root, DL_PATH - 1, 0, 0);
    if (size <= 0 || size == DL_PATH - 1) return fail("cannot resolve root directory", size);
    dl_state.root[size] = 0;
    if (dl_equal(dl_state.root, "/")) return fail("an independent root directory is required", 0);
    dl_state.root_fd = (int)DL_CALL(SYS_fcntl, root, F_DUPFD_CLOEXEC, 256, 0, 0, 0);
    DL_CALL(SYS_close, root, 0, 0, 0, 0, 0);
    if (dl_state.root_fd < 0 || DL_CALL(SYS_fchdir, dl_state.root_fd, 0, 0, 0, 0, 0)) return fail("cannot enter root directory", 0);
    long r = dl_devices_init(); if (r) return fail("device view setup failed", r);
    char *guest_environment[] = {"PATH=/usr/bin:/bin", "HOME=/", "LANG=C", "TMPDIR=/tmp", NULL, NULL};
    unsigned char termios[128];
    if (!DL_CALL(SYS_ioctl, 0, TCGETS, termios, 0, 0, 0)) guest_environment[4] = "TERM=xterm-256color";
    r = bootstrap_stack_policy((uintptr_t)stack); if (r) return fail("bootstrap mapping inheritance", r);
    struct dl_entry entry;
    r = dl_exec(argv[2], argv + 2, guest_environment, &entry, 0); if (r) return fail("ELF preparation failed", r);
    r = signal_stack(1); if (r) return fail("syscall dispatcher rejected by platform", r);
    dl_enter(entry.pc, entry.sp);
}
