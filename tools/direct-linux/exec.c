/* SPDX-License-Identifier: Apache-2.0 */
/* In-process exec: no libc, TLS, allocator, or kernel execution of data files. */
#define _GNU_SOURCE
#include "runtime.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <time.h>

#define IMAGE_LIMIT (128UL * 1024 * 1024)
#define STACK_SIZE (8UL * 1024 * 1024)
#define PH_LIMIT 128
#define ARG_LIMIT 128

struct image {
    uintptr_t base, entry, phdr, low, high, alignment;
    Elf64_Ehdr header;
    Elf64_Phdr ph[PH_LIMIT];
    unsigned char *bytes;
    size_t bytes_size;
    int source_fd;
    size_t phnum;
    void *reservation;
    size_t reservation_size;
    char interpreter[DL_PATH], canonical[DL_PATH];
};

static uintptr_t down(uintptr_t x) { return x & ~(dl_state.page - 1); }
static uintptr_t up(uintptr_t x) { return (x + dl_state.page - 1) & ~(dl_state.page - 1); }
static int range(uint64_t start, uint64_t size, uint64_t limit) { return start <= limit && size <= limit - start; }

static long read_image(const char *path, struct image *out, int interpreter)
{
    out->source_fd = -1;
    int fd = (int)dl_open(AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return fd;
    struct stat st;
    Elf64_Ehdr header;
    Elf64_Phdr *ph = out->ph;
    long result = -ENOEXEC;
    uintptr_t low = UINTPTR_MAX, high = 0, alignment = dl_state.page, phdr = 0;
    int loads = 0, interpreters = 0, executable_entry = 0;
    if (DL_CALL(SYS_fstat, fd, &st, 0, 0, 0, 0) || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
            (uint64_t)st.st_size > IMAGE_LIMIT) {
        goto done;
    }
    if (dl_executable(fd) < 0 || !(st.st_mode & 0111) || (st.st_mode & (S_ISUID | S_ISGID))) { result = -EACCES; goto done; }
    out->bytes_size = (size_t)st.st_size;
    long memory = DL_CALL(SYS_mmap, 0, out->bytes_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (memory < 0) { result = memory; goto done; }
    out->bytes = (void *)memory;
    size_t offset = 0;
    while (offset < out->bytes_size) {
        long n = DL_CALL(SYS_pread64, fd, out->bytes + offset, out->bytes_size - offset, offset, 0, 0);
        if (n == -EINTR) continue;
        if (n <= 0) { result = n < 0 ? n : -ENOEXEC; goto done; }
        offset += n;
    }
    if (dl_file_path(fd, out->canonical)) goto done;
    if (!interpreter && out->bytes_size >= 2 && out->bytes[0] == '#' && out->bytes[1] == '!') {
        result = 1; goto done;
    }
    if (out->bytes_size < sizeof(header)) goto done;
    dl_copy(&header, out->bytes, sizeof(header));
    if (!(header.e_ident[0] == 127 && header.e_ident[1] == 'E' && header.e_ident[2] == 'L' && header.e_ident[3] == 'F') || header.e_ident[EI_CLASS] != ELFCLASS64 ||
            header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_ident[EI_VERSION] != EV_CURRENT ||
            header.e_machine != EM_AARCH64 || header.e_version != EV_CURRENT ||
            (header.e_type != ET_EXEC && header.e_type != ET_DYN) ||
            header.e_ehsize != sizeof(header) || header.e_phentsize != sizeof(Elf64_Phdr) ||
            !header.e_phnum || header.e_phnum > PH_LIMIT ||
            !range(header.e_phoff, header.e_phnum * sizeof(Elf64_Phdr), (uint64_t)st.st_size)) {
        goto done;
    }
    dl_copy(ph, out->bytes + header.e_phoff, header.e_phnum * sizeof(Elf64_Phdr));
    for (unsigned i = 0; i < header.e_phnum; i++) {
        Elf64_Phdr *p = ph + i;
        if (p->p_type == PT_INTERP) {
            if (interpreter || ++interpreters != 1 || p->p_filesz < 2 || p->p_filesz > DL_PATH ||
                    !range(p->p_offset, p->p_filesz, st.st_size)) {
                goto done;
            }
            dl_copy(out->interpreter, out->bytes + p->p_offset, p->p_filesz);
            if (out->interpreter[0] != '/' || out->interpreter[p->p_filesz - 1] ||
                    dl_length(out->interpreter) != p->p_filesz - 1) {
                goto done;
            }
        }
        if (p->p_type == PT_GNU_STACK && (p->p_flags & PF_X)) {
            goto done;
        }
        if (p->p_type != PT_LOAD) continue;
        if (!p->p_memsz) {
            if (p->p_filesz) { goto done; }
            continue;
        }
        if (p->p_filesz > p->p_memsz || !range(p->p_offset, p->p_filesz, st.st_size) ||
                !range(p->p_vaddr, p->p_memsz, UINTPTR_MAX - dl_state.page) ||
                p->p_memsz > IMAGE_LIMIT || p->p_flags & ~(PF_R | PF_W | PF_X) ||
                ((p->p_flags & (PF_W | PF_X)) == (PF_W | PF_X)) ||
                (p->p_align > 1 && ((p->p_align & (p->p_align - 1)) || p->p_align > IMAGE_LIMIT ||
                    (p->p_vaddr % p->p_align != p->p_offset % p->p_align))) ||
                p->p_vaddr % dl_state.page != p->p_offset % dl_state.page) {
            goto done;
        }
        uintptr_t begin = down(p->p_vaddr), end = up(p->p_vaddr + p->p_memsz);
        // Reject ambiguous overlapping pages before making any executable mapping.
        for (unsigned j = 0; j < i; j++) {
            if (ph[j].p_type == PT_LOAD && ph[j].p_memsz && begin < up(ph[j].p_vaddr + ph[j].p_memsz) &&
                    down(ph[j].p_vaddr) < end) {
                goto done;
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
        goto done;
    }
    out->header = header; out->low = low; out->high = high; out->alignment = alignment; out->phdr = phdr;
    result = 0;
done:
    if (!result) out->source_fd = fd;
    else DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0);
    return result;
}

static unsigned long profile_clock(void);

static long map_image(struct image *out, unsigned long costs[5])
{
    unsigned long before = profile_clock(), now;
    Elf64_Ehdr header = out->header;
    Elf64_Phdr *ph = out->ph;
    uintptr_t low = out->low, high = out->high, alignment = out->alignment;
    size_t span = high - low;
    void *reserved;
    if (header.e_type == ET_EXEC) {
        reserved = (void *)dl_mmap(low, span, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        if (reserved != (void *)low) {
            if ((long)reserved >= 0) dl_munmap((uintptr_t)reserved, span);
            return -ENOMEM;
        }
        out->reservation_size = span;
        out->base = 0;
    } else {
        reserved = (void *)dl_mmap(0, span + alignment, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if ((long)reserved < 0) { return -ENOMEM; }
        out->reservation_size = span + alignment;
        // The load bias, not merely the first mapping, satisfies p_align.
        out->base = ((uintptr_t)reserved - low + alignment - 1) & ~(alignment - 1);
    }
    now = profile_clock(); costs[0] += now - before;
    out->reservation = reserved;
    for (unsigned i = 0; i < header.e_phnum; i++) {
        Elf64_Phdr *p = ph + i;
        if (p->p_type != PT_LOAD || !p->p_memsz) continue;
        uintptr_t begin = out->base + down(p->p_vaddr);
        size_t size = up(p->p_vaddr + p->p_memsz) - down(p->p_vaddr);
        int prot = ((p->p_flags & PF_R) ? PROT_READ : 0) | ((p->p_flags & PF_W) ? PROT_WRITE : 0) |
            ((p->p_flags & PF_X) ? PROT_EXEC : 0);
        before = profile_clock();
        size_t file_size = p->p_filesz ? up(p->p_vaddr + p->p_filesz) - down(p->p_vaddr) : 0;
        if (file_size) {
            // Ordinary MAP_PRIVATE mappings preserve the kernel page cache and
            // mapping policy. No executable memfd, disk cache or policy bypass.
            long mapped = dl_mmap(begin, file_size, prot | PROT_READ, MAP_PRIVATE | MAP_FIXED,
                out->source_fd, down(p->p_offset));
            if (mapped < 0) return mapped;
        }
        now = profile_clock(); costs[1] += now - before; before = now;
        // The ELF metadata was parsed from a private snapshot. Check that the
        // mapped file bytes still match before entering any guest code. Later
        // writes/truncation have normal file-mapping semantics, as for DSOs.
        if (!dl_equal_memory((void *)(out->base + p->p_vaddr), out->bytes + p->p_offset, p->p_filesz)) return -EAGAIN;
        now = profile_clock(); costs[2] += now - before; before = now;
        if (file_size < size) {
            long mapped = dl_mmap(begin + file_size, size - file_size, prot,
                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
            if (mapped < 0) return mapped;
        }
        uintptr_t zero_begin = out->base + p->p_vaddr + p->p_filesz;
        // Linux clears the entire partial page after file bytes when BSS is
        // present, including padding beyond p_memsz. glibc's early allocator
        // uses that zeroed tail at _end before requesting another mapping.
        uintptr_t zero_end = p->p_memsz > p->p_filesz ? begin + file_size : zero_begin;
        if (zero_begin < zero_end) {
            // The file-backed final page may contain non-BSS bytes on disk.
            // Zero the private partial page without ever granting W+X.
            uintptr_t page = down(zero_begin);
            if (DL_CALL(SYS_mprotect, page, dl_state.page, PROT_READ | PROT_WRITE, 0, 0, 0)) goto unmap;
            for (uintptr_t at = zero_begin; at < zero_end; at++) *(unsigned char *)at = 0;
            if (p->p_flags & PF_X) dl_clear_cache(page, page + dl_state.page);
            if (DL_CALL(SYS_mprotect, page, dl_state.page, prot | PROT_READ, 0, 0, 0)) goto unmap;
        }
        now = profile_clock(); costs[3] += now - before; before = now;
        if (!(prot & PROT_READ) && file_size && DL_CALL(SYS_mprotect, begin, file_size, prot, 0, 0, 0)) goto unmap;
        now = profile_clock(); costs[4] += now - before;
    }
    out->entry = out->base + header.e_entry;
    out->phdr = out->base + out->phdr;
    out->phnum = header.e_phnum;
    return 0;
unmap:
    return -ENOMEM;
}

#define ENV_LIMIT 256
struct preparation {
    struct image main, interpreter;
    char path[DL_PATH];
    uintptr_t argv[ARG_LIMIT], env[ENV_LIMIT];
    size_t argc, envc;
    char *stack, *top;
    uintptr_t execfn;
};

static void zero(void *destination, size_t size)
{
    unsigned char *p = destination;
    for (size_t i = 0; i < size; i++) p[i] = 0;
}

static long stack_string(struct preparation *p, const char *source, uintptr_t *result)
{
    // Copy bounded pages before committing, so invalid pointers leave the old
    // image intact. Reserve 128 KiB per string; unused bytes are recovered.
    char *destination = p->top - 131072;
    if (destination < p->stack + STACK_SIZE - 2 * 1024 * 1024) return -E2BIG;
    size_t used = 0;
    while (used < 131072) {
        size_t n = dl_state.page - ((uintptr_t)source % dl_state.page);
        if (n > 131072 - used) n = 131072 - used;
        long r = dl_read_memory(destination + used, source, n);
        if (r) return r;
        for (size_t i = 0; i < n; i++) if (!destination[used + i]) {
            size_t length = used + i + 1;
            p->top -= length;
            // May overlap: move toward the higher address backwards.
            for (size_t j = length; j; j--) p->top[j - 1] = destination[j - 1];
            *result = (uintptr_t)p->top; return 0;
        }
        if ((uintptr_t)source > UINTPTR_MAX - n) return -EFAULT;
        source += n; used += n;
    }
    return -E2BIG;
}

static long vector(struct preparation *p, char *const *source, uintptr_t *out, size_t *count, size_t limit)
{
    *count = 0;
    if (!source) return 0;
    for (;;) {
        uintptr_t pointer;
        long r = dl_read_memory(&pointer, source, sizeof(pointer)); if (r) return r;
        if (!pointer) return 0;
        if (*count == limit) return -E2BIG;
        r = stack_string(p, (void *)pointer, out + (*count)++); if (r) return r;
        if ((uintptr_t)source > UINTPTR_MAX - sizeof(pointer)) return -EFAULT;
        source++;
    }
}

static uintptr_t finish_stack(struct preparation *p)
{
    p->top -= 16;
    if (DL_CALL(SYS_getrandom, p->top, 16, 0, 0, 0, 0) != 16) return 0;
    uintptr_t random = (uintptr_t)p->top;
    p->top -= sizeof("aarch64"); dl_copy(p->top, "aarch64", sizeof("aarch64"));
    uintptr_t platform = (uintptr_t)p->top;
    Elf64_auxv_t aux[24]; size_t count = 0;
#define AUX(type, value) do { aux[count].a_type = (type); aux[count++].a_un.a_val = (value); } while (0)
    AUX(AT_PHDR, p->main.phdr); AUX(AT_PHENT, sizeof(Elf64_Phdr)); AUX(AT_PHNUM, p->main.phnum);
    AUX(AT_PAGESZ, dl_state.page); AUX(AT_BASE, p->interpreter.base); AUX(AT_ENTRY, p->main.entry);
    AUX(AT_FLAGS, 0);
    AUX(AT_UID, DL_CALL(SYS_getuid, 0, 0, 0, 0, 0, 0)); AUX(AT_EUID, DL_CALL(SYS_geteuid, 0, 0, 0, 0, 0, 0));
    AUX(AT_GID, DL_CALL(SYS_getgid, 0, 0, 0, 0, 0, 0)); AUX(AT_EGID, DL_CALL(SYS_getegid, 0, 0, 0, 0, 0, 0));
    AUX(AT_SECURE, 0); AUX(AT_RANDOM, random); AUX(AT_EXECFN, p->execfn); AUX(AT_PLATFORM, platform);
    AUX(AT_HWCAP, dl_state.hwcap); AUX(AT_HWCAP2, dl_state.hwcap2); AUX(AT_CLKTCK, dl_state.clktck);
    AUX(AT_SYSINFO_EHDR, dl_state.vdso); AUX(AT_MINSIGSTKSZ, dl_state.minsigstksz); AUX(AT_NULL, 0);
#undef AUX
    size_t bytes = (1 + p->argc + 1 + p->envc + 1) * sizeof(uintptr_t) + count * sizeof(Elf64_auxv_t);
    uintptr_t stack = ((uintptr_t)p->top - bytes) & ~15UL;
    uintptr_t *words = (uintptr_t *)stack;
    *words++ = p->argc;
    for (size_t i = 0; i < p->argc; i++) *words++ = p->argv[i];
    *words++ = 0;
    for (size_t i = 0; i < p->envc; i++) *words++ = p->env[i];
    *words++ = 0; dl_copy(words, aux, count * sizeof(Elf64_auxv_t));
    return stack;
}

static void dispose_image(struct image *image)
{
    if (image->bytes) DL_CALL(SYS_munmap, image->bytes, image->bytes_size, 0, 0, 0, 0);
    image->bytes = NULL;
    if (image->source_fd >= 0) DL_CALL(SYS_close, image->source_fd, 0, 0, 0, 0, 0);
    image->source_fd = -1;
}

static long close_exec_fds(int main_fd, int interpreter_fd)
{
    // Single-threaded execution only: the FD table cannot change underneath us.
    long fd = DL_CALL(SYS_openat, AT_FDCWD, "/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
    if (fd < 0) return fd;
    char buffer[4096]; long result = 0;
    struct dent { uint64_t ino; int64_t off; unsigned short size; unsigned char type; char name[]; };
    for (;;) {
        long n = DL_CALL(SYS_getdents64, fd, buffer, sizeof(buffer), 0, 0, 0);
        if (n == -EINTR) continue;
        if (n <= 0) { result = n; break; }
        for (long offset = 0; offset < n;) {
            struct dent *d = (void *)(buffer + offset);
            if (d->size < offsetof(struct dent, name) + 2 || d->size > n - offset) { result = -EIO; goto done; }
            unsigned value = 0; size_t i = 0;
            while (i < d->size - offsetof(struct dent, name) && d->name[i] >= '0' && d->name[i] <= '9') {
                if (value > 214748364U) { result = -EIO; goto done; }
                value = value * 10 + d->name[i++] - '0';
            }
            if (i && i < d->size - offsetof(struct dent, name) && !d->name[i] &&
                    value != (unsigned)fd && value != (unsigned)dl_state.root_fd && value != (unsigned)dl_state.vfork_fd &&
                    value != (unsigned)main_fd && value != (unsigned)interpreter_fd) {
                long flags = DL_CALL(SYS_fcntl, value, F_GETFD, 0, 0, 0, 0);
                if (flags >= 0 && (flags & FD_CLOEXEC)) DL_CALL(SYS_close, value, 0, 0, 0, 0, 0);
            }
            offset += d->size;
        }
    }
done:
    DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0); return result;
}

static long reset_exec_state(int main_fd, int interpreter_fd)
{
    struct { uintptr_t handler, flags, restorer, mask; } action;
    for (int s = 1; s <= 64; s++) {
        if (s == SIGKILL || s == SIGSTOP || s == SIGSYS) continue;
        long r = DL_CALL(SYS_rt_sigaction, s, 0, &action, 8, 0, 0); if (r) return r;
        if (action.handler > 1) {
            zero(&action, sizeof(action));
            r = DL_CALL(SYS_rt_sigaction, s, &action, 0, 8, 0, 0); if (r) return r;
        }
    }
    // Old glibc pointers must not be consulted by the kernel after unmapping.
    long r = DL_CALL(SYS_set_robust_list, 0, 24, 0, 0, 0, 0); if (r) return r;
    DL_CALL(SYS_set_tid_address, 0, 0, 0, 0, 0, 0);
    return close_exec_fds(main_fd, interpreter_fd);
}

static long script_arguments(struct preparation *p)
{
    char line[257]; size_t size = p->main.bytes_size;
    if (size > 256) size = 256;
    size_t end = 2;
    while (end < size && p->main.bytes[end] != '\n' && p->main.bytes[end]) end++;
    // Reject a truncated first line instead of executing a truncated pathname.
    if (end == 256) return -ENOEXEC;
    dl_copy(line, p->main.bytes, end); line[end] = 0;
    while (end > 2 && (line[end - 1] == ' ' || line[end - 1] == '\t')) line[--end] = 0;
    size_t start = 2;
    while (line[start] == ' ' || line[start] == '\t') start++;
    if (!line[start]) return -ENOEXEC;
    size_t next = start;
    while (line[next] && line[next] != ' ' && line[next] != '\t') next++;
    if (line[next]) line[next++] = 0;
    while (line[next] == ' ' || line[next] == '\t') next++;
    int optional = line[next] != 0;
    if (p->argc + 1 + optional > ARG_LIMIT) return -E2BIG;
    uintptr_t script, interpreter, option = 0;
    long r = stack_string(p, p->path, &script); if (r) return r;
    r = stack_string(p, line + start, &interpreter); if (r) return r;
    if (optional) { r = stack_string(p, line + next, &option); if (r) return r; }
    for (size_t i = p->argc; i > 1; i--) p->argv[i + optional] = p->argv[i - 1];
    p->argv[0] = interpreter;
    if (optional) p->argv[1] = option;
    p->argv[1 + optional] = script;
    p->argc += 1 + optional;
    dl_copy(p->path, line + start, dl_length(line + start) + 1);
    dispose_image(&p->main); zero(&p->main, sizeof(p->main));
    return 0;
}

static unsigned long profile_clock(void)
{
    if (!dl_state.exec_profile) return 0;
    struct timespec t;
    if (DL_CALL(SYS_clock_gettime, CLOCK_MONOTONIC, &t, 0, 0, 0, 0)) return 0;
    return (unsigned long)t.tv_sec * 1000000000UL + t.tv_nsec;
}

static size_t number(char *text, unsigned long value)
{
    char digits[24]; size_t n = 0, size = 0;
    do { digits[n++] = '0' + value % 10; value /= 10; } while (value);
    while (n) text[size++] = digits[--n];
    return size;
}

static void profile_report(unsigned long times[8], unsigned long costs[5], unsigned long bytes, int replacing)
{
    char out[384]; const char prefix[] = "EXEC_PROFILE {\"replacing\":";
    dl_copy(out, prefix, sizeof(prefix) - 1); size_t n = sizeof(prefix) - 1;
    n += number(out + n, replacing);
    const char size_key[] = ",\"bytes\":";
    dl_copy(out + n, size_key, sizeof(size_key) - 1); n += sizeof(size_key) - 1;
    n += number(out + n, bytes);
    const char times_key[] = ",\"ns\":[";
    dl_copy(out + n, times_key, sizeof(times_key) - 1); n += sizeof(times_key) - 1;
    for (unsigned i = 1; i < 8; i++) {
        n += number(out + n, times[i] - times[i - 1]); out[n++] = i == 7 ? ']' : ',';
    }
    const char costs_key[] = ",\"map_ns\":[";
    dl_copy(out + n, costs_key, sizeof(costs_key) - 1); n += sizeof(costs_key) - 1;
    for (unsigned i = 0; i < 5; i++) {
        n += number(out + n, costs[i]); out[n++] = i == 4 ? ']' : ',';
    }
    out[n++] = '}'; out[n++] = '\n';
    DL_CALL(SYS_write, 2, out, n, 0, 0, 0);
}

long dl_exec(const char *path, char *const *argv, char *const *env, struct dl_entry *entry, int replacing)
{
    unsigned long times[8] = {0}, costs[5] = {0}, bytes = 0;
    times[0] = profile_clock();
    long memory = DL_CALL(SYS_mmap, 0, sizeof(struct preparation), PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (memory < 0) return memory;
    struct preparation *p = (void *)memory;
    p->main.source_fd = p->interpreter.source_fd = -1;
    long r = dl_string(p->path, path);
    if (r) goto done;
    long stack = DL_CALL(SYS_mmap, 0, STACK_SIZE + 2 * dl_state.page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (stack < 0) { r = stack; goto done; }
    p->stack = (char *)stack + dl_state.page; p->top = p->stack + STACK_SIZE;
    r = DL_CALL(SYS_mprotect, p->stack, STACK_SIZE, PROT_READ | PROT_WRITE, 0, 0, 0); if (r) goto done;
    r = vector(p, argv, p->argv, &p->argc, ARG_LIMIT); if (r) goto done;
    r = vector(p, env, p->env, &p->envc, ENV_LIMIT); if (r) goto done;
    if (!p->argc) { p->argc = 1; r = stack_string(p, "", &p->argv[0]); if (r) goto done; }
    r = stack_string(p, p->path, &p->execfn); if (r) goto done;
    times[1] = profile_clock();
    for (unsigned depth = 0;; depth++) {
        r = read_image(p->path, &p->main, 0);
        if (r != 1) break;
        if (depth == 4) { r = -ELOOP; goto done; }
        r = script_arguments(p); if (r) goto done;
    }
    if (r) goto done;
    if (p->main.interpreter[0]) {
        r = read_image(p->main.interpreter, &p->interpreter, 1); if (r) goto done;
    }
    times[2] = profile_clock();
    bytes = p->main.bytes_size + p->interpreter.bytes_size;
    // Commit point: validated input snapshots and strings no longer refer to
    // the old image. Failures before here return errno without changing it.
    // Failures after here cannot return into unmapped guest code.
    if (replacing) {
        if (reset_exec_state(p->main.source_fd, p->interpreter.source_fd)) goto fatal;
    }
    times[3] = profile_clock();
    if (replacing) dl_unmap_all();
    times[4] = profile_clock();
    if (map_image(&p->main, costs) || (p->interpreter.bytes && map_image(&p->interpreter, costs))) goto fatal;
    times[5] = profile_clock();
    long heap = dl_mmap(0, DL_HEAP, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (heap < 0 || dl_track((uintptr_t)stack, STACK_SIZE + 2 * dl_state.page)) goto fatal;
    dl_state.heap_start = dl_state.heap_end = dl_state.heap_mapped = (uintptr_t)heap;
    entry->sp = finish_stack(p); if (!entry->sp) goto fatal;
    entry->pc = p->interpreter.entry ? p->interpreter.entry : p->main.entry;
    dl_copy(dl_state.executable, p->main.canonical, dl_length(p->main.canonical) + 1);
    const char *name = p->path;
    for (const char *c = p->path; *c; c++) if (*c == '/' && c[1]) name = c + 1;
    DL_CALL(SYS_prctl, PR_SET_NAME, name, 0, 0, 0, 0);
    if (dl_state.vfork_fd >= 0) {
        DL_CALL(SYS_close, dl_state.vfork_fd, 0, 0, 0, 0, 0); dl_state.vfork_fd = -1;
    }
    p->stack = NULL; r = 0;
    times[6] = profile_clock();
done:
    if (p->stack) DL_CALL(SYS_munmap, p->stack - dl_state.page, STACK_SIZE + 2 * dl_state.page, 0, 0, 0, 0);
    dispose_image(&p->main); dispose_image(&p->interpreter);
    DL_CALL(SYS_munmap, p, sizeof(*p), 0, 0, 0, 0);
    times[7] = profile_clock();
    if (!r && dl_state.exec_profile) profile_report(times, costs, bytes, replacing);
    return r;
fatal:
    {
        const char message[] = "Direct Linux: exec failed after image commit\n";
        DL_CALL(SYS_write, 2, message, sizeof(message) - 1, 0, 0, 0);
        DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0);
        __builtin_unreachable();
    }
}
