/* SPDX-License-Identifier: Apache-2.0 */
/* Guest exec and APK runtime reentry; never kernel-execute guest data files. */
#define _GNU_SOURCE
#include "runtime.h"
#include "threads.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <time.h>

#define IMAGE_LIMIT (128UL * 1024 * 1024)
#define STACK_SIZE (8UL * 1024 * 1024)
#define PH_LIMIT 128
#define ARG_BYTES_MIN (128UL * 1024)
#define ARG_BYTES_MAX (6UL * 1024 * 1024)
#define SCRIPT_EXTRA_ARGS 8

struct image {
    uintptr_t base, entry, phdr, low, high, alignment;
    Elf64_Ehdr header;
    Elf64_Phdr ph[PH_LIMIT];
    unsigned char prefix[256];
    size_t file_size, read_bytes;
    int source_fd;
    size_t phnum;
    void *reservation;
    size_t reservation_size;
    char interpreter[DL_PATH], canonical[DL_PATH];
};

static uintptr_t down(uintptr_t x) { return x & ~(dl_state.page - 1); }
static uintptr_t up(uintptr_t x) { return (x + dl_state.page - 1) & ~(dl_state.page - 1); }
static int range(uint64_t start, uint64_t size, uint64_t limit) { return start <= limit && size <= limit - start; }

static long read_exact(int fd, void *destination, size_t size, size_t offset, struct image *image)
{
    size_t copied = 0;
    while (copied < size) {
        long n = DL_CALL(SYS_pread64, fd, (char *)destination + copied, size - copied, offset + copied, 0, 0);
        if (n == -EINTR) continue;
        if (n <= 0) return n < 0 ? n : -ENOEXEC;
        copied += n; if (image) image->read_bytes += n;
    }
    return 0;
}

static long read_image_fd(int fd, struct image *out, int interpreter)
{
    out->source_fd = -1;
    if (fd < 0) return fd;
    struct stat st;
    Elf64_Ehdr header;
    Elf64_Phdr *ph = out->ph;
    long result = -ENOEXEC;
    uintptr_t low = UINTPTR_MAX, high = 0, alignment = dl_state.page, phdr = 0;
    int loads = 0, interpreters = 0, executable_entry = 0;
    result = DL_CALL(SYS_fstat, fd, &st, 0, 0, 0, 0);
    if (result) goto done;
    if (!S_ISREG(st.st_mode)) { result = -EACCES; goto done; }
    result = -ENOEXEC;
    if (st.st_size <= 0 ||
            (uint64_t)st.st_size > IMAGE_LIMIT) {
        goto done;
    }
    if (dl_executable(fd) < 0 || !(st.st_mode & 0111) || (st.st_mode & (S_ISUID | S_ISGID))) { result = -EACCES; goto done; }
    out->file_size = (size_t)st.st_size;
    size_t prefix_size = out->file_size < sizeof(out->prefix) ? out->file_size : sizeof(out->prefix);
    long read = read_exact(fd, out->prefix, prefix_size, 0, out);
    if (read) { result = read; goto done; }
    if (dl_file_path(fd, out->canonical)) goto done;
    if (!interpreter && out->file_size >= 2 && out->prefix[0] == '#' && out->prefix[1] == '!') {
        result = 1; goto done;
    }
    if (out->file_size < sizeof(header)) goto done;
    dl_copy(&header, out->prefix, sizeof(header));
    if (!(header.e_ident[0] == 127 && header.e_ident[1] == 'E' && header.e_ident[2] == 'L' && header.e_ident[3] == 'F') || header.e_ident[EI_CLASS] != ELFCLASS64 ||
            header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_ident[EI_VERSION] != EV_CURRENT ||
            header.e_machine != EM_AARCH64 || header.e_version != EV_CURRENT ||
            (header.e_type != ET_EXEC && header.e_type != ET_DYN) ||
            header.e_ehsize != sizeof(header) || header.e_phentsize != sizeof(Elf64_Phdr) ||
            !header.e_phnum || header.e_phnum > PH_LIMIT ||
            !range(header.e_phoff, header.e_phnum * sizeof(Elf64_Phdr), (uint64_t)st.st_size)) {
        goto done;
    }
    read = read_exact(fd, ph, header.e_phnum * sizeof(Elf64_Phdr), header.e_phoff, out);
    if (read) { result = read; goto done; }
    for (unsigned i = 0; i < header.e_phnum; i++) {
        Elf64_Phdr *p = ph + i;
        if (p->p_type == PT_INTERP) {
            if (interpreter || ++interpreters != 1 || p->p_filesz < 2 || p->p_filesz > DL_PATH ||
                    !range(p->p_offset, p->p_filesz, st.st_size)) {
                goto done;
            }
            read = read_exact(fd, out->interpreter, p->p_filesz, p->p_offset, out);
            if (read) { result = read; goto done; }
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

static long read_image(int dirfd, const char *path, int flags, struct image *out, int interpreter)
{
    int open_flags = O_RDONLY | O_CLOEXEC | O_NONBLOCK;
    long fd;
    if (!path[0] && (flags & AT_EMPTY_PATH)) fd = dirfd == AT_FDCWD
        ? -EACCES : dl_reopen(dirfd, open_flags);
    else fd = dl_open(dirfd, path, open_flags | ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0), 0);
    return read_image_fd((int)fd, out, interpreter);
}

static unsigned long profile_clock(void);

static int metadata_matches(const struct image *image, const Elf64_Phdr *segment,
    size_t offset, const void *snapshot, size_t size)
{
    size_t start = offset > segment->p_offset ? offset : segment->p_offset;
    size_t end = offset + size < segment->p_offset + segment->p_filesz
        ? offset + size : segment->p_offset + segment->p_filesz;
    return start >= end || dl_equal_memory((void *)(image->base + segment->p_vaddr + start - segment->p_offset),
        (const char *)snapshot + start - offset, end - start);
}

static int verified_metadata(const struct image *image, const Elf64_Phdr *segment)
{
    if (!metadata_matches(image, segment, 0, &image->header, sizeof(image->header)) ||
            !metadata_matches(image, segment, image->header.e_phoff, image->ph,
                image->header.e_phnum * sizeof(Elf64_Phdr))) return 0;
    for (unsigned i = 0; i < image->header.e_phnum; i++) {
        const Elf64_Phdr *p = image->ph + i;
        if (p->p_type == PT_INTERP &&
                !metadata_matches(image, segment, p->p_offset, image->interpreter, p->p_filesz)) return 0;
    }
    return 1;
}

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
        // Recheck every mapped byte of the metadata used to choose mappings and
        // the interpreter. Code/data are ordinary demand-paged file contents,
        // not authenticated immutable snapshots. The same FD pins the inode.
        if (!verified_metadata(out, p)) return -EAGAIN;
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

// A bounded snapshot used only while copying one argv/env vector. Two slots
// keep its pointer table and strings from evicting each other. The loader
// allocates exactly two host pages rather than a worst-case fixed buffer.
struct source_page {
    uintptr_t address;
    int valid;
    unsigned char *bytes;
};
struct preparation {
    struct image main, interpreter;
    char path[DL_PATH + 40]; // execveat adds /dev/fd/N/ to an input pathname
    uintptr_t *argv, *env;
    size_t argc, envc, argv_capacity, string_limit;
    char *stack, *top;
    uintptr_t execfn;
    struct source_page pointers, strings;
    int main_slot, interpreter_slot;
    unsigned char source_pages[];
};

static void zero(void *destination, size_t size)
{
    unsigned char *p = destination;
    for (size_t i = 0; i < size; i++) p[i] = 0;
}

static long source_bytes(struct source_page *cache, uintptr_t address, const unsigned char **bytes, size_t *size)
{
    uintptr_t page = down(address);
    if (!cache->valid || cache->address != page) {
        cache->valid = 0;
        long r = dl_read_memory(cache->bytes, (void *)page, dl_state.page);
        if (r) return r;
        cache->address = page; cache->valid = 1;
    }
    *bytes = cache->bytes + address - page;
    *size = dl_state.page - (address - page);
    return 0;
}

static long stack_string(struct preparation *p, const char *source, uintptr_t *result, struct source_page *cache)
{
    // Copy bounded pages before committing, so invalid pointers leave the old
    // image intact. Linux permits 32 host pages per string, including NUL.
    size_t maximum = 32 * dl_state.page;
    if ((size_t)(p->top - p->stack) < maximum) return -E2BIG;
    char *destination = p->top - maximum;
    size_t used = 0;
    while (used < maximum) {
        size_t n = dl_state.page - ((uintptr_t)source % dl_state.page);
        const unsigned char *bytes = (void *)(destination + used);
        if (cache) {
            long r = source_bytes(cache, (uintptr_t)source, &bytes, &n);
            if (r) return r;
        }
        if (n > maximum - used) n = maximum - used;
        if (!cache) {
            long r = dl_read_memory(destination + used, source, n);
            if (r) return r;
        }
        size_t size = 0;
        while (size < n && bytes[size]) size++;
        int terminated = size < n;
        if (terminated) size++;
        if (cache) dl_copy(destination + used, bytes, size);
        if (terminated) {
            size_t length = used + size;
            size_t available = p->string_limit - (size_t)(p->stack + STACK_SIZE - p->top);
            if (length > available) return -E2BIG;
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

static long vector_pointer(struct preparation *p, char *const *source, size_t index, uintptr_t *pointer)
{
    if (index > (UINTPTR_MAX - (uintptr_t)source) / sizeof(*source)) return -EFAULT;
    uintptr_t address = (uintptr_t)source + index * sizeof(*source);
    size_t copied = 0;
    // Also accept an unaligned pointer table spanning two readable pages.
    while (copied < sizeof(*pointer)) {
        const unsigned char *bytes; size_t n;
        if (address > UINTPTR_MAX - copied) return -EFAULT;
        long r = source_bytes(&p->pointers, address + copied, &bytes, &n);
        if (r) return r;
        if (n > sizeof(*pointer) - copied) n = sizeof(*pointer) - copied;
        dl_copy((char *)pointer + copied, bytes, n); copied += n;
    }
    return 0;
}

static long vector_count(struct preparation *p, char *const *source, size_t *count)
{
    *count = 0; p->pointers.valid = 0;
    if (!source) return 0;
    for (;;) {
        uintptr_t pointer;
        long r = vector_pointer(p, source, *count, &pointer); if (r) return r;
        if (!pointer) return 0;
        if (*count == INT32_MAX) return -E2BIG;
        ++*count;
    }
}

static long vector(struct preparation *p, char *const *source, uintptr_t *out, size_t count)
{
    p->pointers.valid = p->strings.valid = 0;
    // argv[0] is last on the downward-growing string stack. A shebang can
    // reclaim it just as remove_arg_zero() does, including at the byte limit.
    while (count) {
        uintptr_t pointer; --count;
        long r = vector_pointer(p, source, count, &pointer); if (r) return r;
        r = stack_string(p, (void *)pointer, out + count, &p->strings); if (r) return r;
    }
    return 0;
}

static long argument_limit(size_t *limit)
{
    struct rlimit stack;
    long r = DL_CALL(SYS_prlimit64, 0, RLIMIT_STACK, 0, &stack, 0, 0); if (r) return r;
    *limit = stack.rlim_cur / 4;
    if (*limit > ARG_BYTES_MAX) *limit = ARG_BYTES_MAX;
    if (*limit < ARG_BYTES_MIN) *limit = ARG_BYTES_MIN;
    return 0;
}

static long allocate_vectors(struct preparation *p)
{
    p->argv_capacity = (p->argc ? p->argc : 1) + SCRIPT_EXTRA_ARGS;
    long memory = dl_scratch_map(dl_thread_scratch(),
        (p->argv_capacity + p->envc) * sizeof(uintptr_t), PROT_READ | PROT_WRITE);
    if (memory < 0) return memory;
    p->argv = (void *)memory; p->env = p->argv + p->argv_capacity;
    return 0;
}

static long prepare_arguments(struct preparation *p, char *const *argv, char *const *env)
{
    long r = vector_count(p, argv, &p->argc); if (r) return r;
    r = vector_count(p, env, &p->envc); if (r) return r;
    size_t limit; r = argument_limit(&limit); if (r) return r;
    size_t pointers = ((p->argc ? p->argc : 1) + p->envc) * sizeof(uintptr_t);
    if (pointers >= limit) return -E2BIG;
    p->string_limit = limit - pointers;
    r = allocate_vectors(p); if (r) return r;
    r = stack_string(p, p->path, &p->execfn, NULL); if (r) return r;
    r = vector(p, env, p->env, p->envc); if (r) return r;
    r = vector(p, argv, p->argv, p->argc); if (r) return r;
    if (!p->argc) { p->argc = 1; r = stack_string(p, "", &p->argv[0], NULL); }
    return r;
}

static uintptr_t finish_stack(struct preparation *p)
{
    // Argument limits reserve the Linux pointer budget; auxv and alignment
    // fit in the remaining loader stack but still check before committing.
    size_t overhead = (1 + p->argc + 1 + p->envc + 1) * sizeof(uintptr_t) +
        24 * sizeof(Elf64_auxv_t) + 16 + sizeof("aarch64") + 15;
    if ((size_t)(p->top - p->stack) < overhead) return 0;
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
    if (image->source_fd >= 0) DL_CALL(SYS_close, image->source_fd, 0, 0, 0, 0, 0);
    image->source_fd = -1;
}

static long protect_image(int dirfd, const char *path, int flags, struct image *image, int interpreter,
    int *slot, uintptr_t stack, int replacing, int *inaccessible)
{
    dl_lock(&dl_files_lock);
    long r = dirfd != AT_FDCWD && path[0] != '/' && dl_internal_fd(dirfd)
        ? -EBADF : read_image(dirfd, path, flags, image, interpreter);
    if (inaccessible) {
        *inaccessible = 0;
        if (r == 1 && dirfd != AT_FDCWD && path[0] != '/') {
            long state = DL_CALL(SYS_fcntl, dirfd, F_GETFD, 0, 0, 0, 0);
            *inaccessible = state < 0 || (state & FD_CLOEXEC);
        }
    }
    if (!r && replacing) {
        r = dl_io_hold(image->source_fd, stack);
        if (r >= 0) { *slot = (int)r; r = 0; }
        else dispose_image(image);
    }
    dl_unlock(&dl_files_lock); return r;
}

static void dispose_preparation(struct preparation *p)
{
    if (p->argv) dl_scratch_unmap(p->argv);
    dl_lock(&dl_files_lock);
    if (p->interpreter_slot >= 0) { dl_io_release((unsigned)p->interpreter_slot); p->interpreter.source_fd = -1; }
    if (p->main_slot >= 0) { dl_io_release((unsigned)p->main_slot); p->main.source_fd = -1; }
    dispose_image(&p->main); dispose_image(&p->interpreter);
    dl_unlock(&dl_files_lock);
}

static long reset_exec_state(int main_fd, int interpreter_fd)
{
    long reset = dl_signal_reset_exec(); if (reset) return reset;
    // Old glibc pointers must not be consulted by the kernel after unmapping.
    long r = DL_CALL(SYS_set_robust_list, 0, 24, 0, 0, 0, 0); if (r) return r;
    DL_CALL(SYS_set_tid_address, 0, 0, 0, 0, 0, 0);
    return dl_close_exec_fds(main_fd, interpreter_fd);
}

static long script_arguments(struct preparation *p, int inaccessible)
{
    char line[257]; size_t size = p->main.file_size;
    if (size > 256) size = 256;
    size_t end = 2;
    while (end < size && p->main.prefix[end] != '\n' && p->main.prefix[end]) end++;
    // Reject a truncated first line instead of executing a truncated pathname.
    if (end == 256) return -ENOEXEC;
    dl_copy(line, p->main.prefix, end); line[end] = 0;
    while (end > 2 && (line[end - 1] == ' ' || line[end - 1] == '\t')) line[--end] = 0;
    size_t start = 2;
    while (line[start] == ' ' || line[start] == '\t') start++;
    if (!line[start]) return -ENOEXEC;
    size_t next = start;
    while (line[next] && line[next] != ' ' && line[next] != '\t') next++;
    if (line[next]) line[next++] = 0;
    while (line[next] == ' ' || line[next] == '\t') next++;
    int optional = line[next] != 0;
    // Linux rejects a script whose generated /dev/fd path will disappear at
    // exec. Do this before committing or changing any guest descriptor flags.
    if (inaccessible) return -ENOENT;
    if (p->argc + 1 + optional > p->argv_capacity) return -E2BIG;
    // The kernel only reserves the original argv/env pointer budget, then
    // replaces argv[0] and pushes interpreter arguments against that budget.
    p->top += dl_length((char *)p->argv[0]) + 1;
    uintptr_t script, interpreter, option = 0;
    long r = stack_string(p, p->path, &script, NULL); if (r) return r;
    if (optional) { r = stack_string(p, line + next, &option, NULL); if (r) return r; }
    r = stack_string(p, line + start, &interpreter, NULL); if (r) return r;
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

static void profile_report(unsigned long times[8], unsigned long costs[5], unsigned long bytes,
    unsigned long read_bytes, int replacing)
{
    char out[512]; const char prefix[] = "EXEC_PROFILE {\"replacing\":";
    dl_copy(out, prefix, sizeof(prefix) - 1); size_t n = sizeof(prefix) - 1;
    n += number(out + n, replacing);
    const char size_key[] = ",\"bytes\":";
    dl_copy(out + n, size_key, sizeof(size_key) - 1); n += sizeof(size_key) - 1;
    n += number(out + n, bytes);
    const char read_key[] = ",\"metadata_read_bytes\":";
    dl_copy(out + n, read_key, sizeof(read_key) - 1); n += sizeof(read_key) - 1;
    n += number(out + n, read_bytes);
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

static long install_image(struct preparation *p, struct dl_entry *entry, unsigned long times[8], unsigned long costs[5])
{
    if (map_image(&p->main, costs) || (p->interpreter.source_fd >= 0 && map_image(&p->interpreter, costs))) return -ENOEXEC;
    times[5] = profile_clock();
    long heap = dl_mmap(0, DL_HEAP, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (heap < 0 || dl_track((uintptr_t)p->stack - dl_state.page, STACK_SIZE + 2 * dl_state.page)) return -ENOMEM;
    dl_state.heap_start = dl_state.heap_end = dl_state.heap_mapped = (uintptr_t)heap;
    entry->sp = finish_stack(p); if (!entry->sp) return -E2BIG;
    entry->pc = p->interpreter.entry ? p->interpreter.entry : p->main.entry;
    dl_copy(dl_state.executable, p->main.canonical, dl_length(p->main.canonical) + 1);
    const char *name = p->path;
    for (const char *c = p->path; *c; c++) if (*c == '/' && c[1]) name = c + 1;
    DL_CALL(SYS_prctl, PR_SET_NAME, name, 0, 0, 0, 0);
    if (dl_state.vfork_fd >= 0) {
        DL_CALL(SYS_close, dl_state.vfork_fd, 0, 0, 0, 0, 0); dl_state.vfork_fd = -1;
    }
    dl_scratch_detach(p->stack - dl_state.page);
    p->stack = NULL; times[6] = profile_clock(); return 0;
}

#define HANDOFF_MAGIC 0x32464f444e414c44ULL
struct handoff {
    uint64_t magic;
    size_t string_size;
    int root_fd, flags;
    unsigned long mask, virtual_sigsys;
    char root[DL_PATH];
    struct preparation prepared;
};

static long write_all(int fd, const void *data, size_t size)
{
    const char *bytes = data;
    while (size) {
        long r = DL_CALL(SYS_write, fd, bytes, size, 0, 0, 0);
        if (r == -EINTR) continue;
        if (r <= 0) return r ? r : -EIO;
        bytes += r; size -= r;
    }
    return 0;
}

static long write_offsets(int fd, const uintptr_t *pointers, size_t count, uintptr_t base)
{
    // Stream bounded chunks, including in a borrowed vfork arena: retaining
    // a second worst-case pointer table would exceed the arena's peak budget.
    uintptr_t offsets[256];
    while (count) {
        size_t n = count < 256 ? count : 256;
        for (size_t i = 0; i < n; i++) offsets[i] = pointers[i] - base;
        long r = write_all(fd, offsets, n * sizeof(*offsets)); if (r) return r;
        pointers += n; count -= n;
    }
    return 0;
}

static long kernel_exec(struct preparation *p, const struct dl_entry *entry)
{
    long address = dl_scratch_map(dl_thread_scratch(), sizeof(struct handoff), PROT_READ | PROT_WRITE);
    if (address < 0) return address;
    struct handoff *wire = (void *)address;
    wire->magic = HANDOFF_MAGIC; wire->root_fd = dl_state.root_fd;
    wire->mask = entry->mask; wire->virtual_sigsys = entry->virtual_sigsys;
    wire->flags = dl_state.trace | (dl_state.exec_profile << 1) | (dl_state.startup_profile << 2);
    dl_copy(wire->root, dl_state.root, DL_PATH);
    dl_copy(&wire->prepared, p, sizeof(*p));
    wire->string_size = (size_t)(p->stack + STACK_SIZE - p->top);
    wire->prepared.argv = wire->prepared.env = NULL;
    wire->prepared.execfn -= (uintptr_t)p->top;
    wire->prepared.stack = wire->prepared.top = NULL;
    wire->prepared.pointers = wire->prepared.strings = (struct source_page){0};
    wire->prepared.main_slot = wire->prepared.interpreter_slot = -1;
    uintptr_t stack; __asm__ volatile("mov %0, sp" : "=r"(stack));
    dl_lock(&dl_files_lock);
    long fd = DL_CALL(SYS_memfd_create, "direct-linux-exec", MFD_CLOEXEC | MFD_ALLOW_SEALING, 0, 0, 0, 0);
    long slot = fd < 0 ? fd : dl_io_hold((int)fd, stack);
    if (slot < 0 && fd >= 0) DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0);
    dl_unlock(&dl_files_lock);
    long r = slot;
    if (slot < 0) goto done;
    // Data only: no executable memfd or writable-data kernel executable.
    r = DL_CALL(SYS_fchmod, fd, 0600, 0, 0, 0, 0);
    if (!r) r = write_all((int)fd, wire, sizeof(*wire));
    if (!r) r = write_offsets((int)fd, p->argv, p->argc, (uintptr_t)p->top);
    if (!r) r = write_offsets((int)fd, p->env, p->envc, (uintptr_t)p->top);
    if (!r) r = write_all((int)fd, p->top, wire->string_size);
    if (!r) r = DL_CALL(SYS_fcntl, fd, F_ADD_SEALS, F_SEAL_SEAL | F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK, 0, 0, 0);
    if (!r) {
        int descriptors[] = {dl_state.root_fd, p->main.source_fd, p->interpreter.source_fd, (int)fd};
        long original[] = {-1, -1, -1, -1};
        dl_lock(&dl_files_lock);
        for (unsigned i = 0; !r && i < 4; i++) if (descriptors[i] >= 0) {
            original[i] = DL_CALL(SYS_fcntl, descriptors[i], F_GETFD, 0, 0, 0, 0);
            r = original[i] < 0 ? original[i] : DL_CALL(SYS_fcntl, descriptors[i], F_SETFD, original[i] & ~FD_CLOEXEC, 0, 0, 0);
        }
        dl_unlock(&dl_files_lock);
        if (!r) {
            char value[24]; size_t count = number(value, (unsigned long)fd); value[count] = 0;
            char *arguments[] = {"direct-linux-loader", "--exec-resume", value, NULL};
            char *environment[] = {NULL};
            char executable[48]; size_t length = dl_fd_path(executable, -1);
            dl_copy(executable + length - 2, "exe", 4); // live TID, including a dead group leader
            // On success the old VM can remain alive in a CLONE_VM parent.
            // Never leave its runtime locks held across the irreversible step.
            dl_exec_mark(1);
            dl_exec_end();
            r = DL_CALL(SYS_execve, executable, arguments, environment, 0, 0, 0);
            (void)dl_exec_begin();
            dl_exec_mark(0);
        }
        dl_lock(&dl_files_lock);
        for (unsigned i = 0; i < 4; i++) if (original[i] >= 0)
            DL_CALL(SYS_fcntl, descriptors[i], F_SETFD, original[i], 0, 0, 0);
        dl_unlock(&dl_files_lock);
    }
    dl_lock(&dl_files_lock); dl_io_release((unsigned)slot); dl_unlock(&dl_files_lock);
done:
    dl_scratch_unmap(wire); return r;
}

static int terminated(const char *text, size_t size)
{
    for (size_t i = 0; i < size; i++) if (!text[i]) return 1;
    return 0;
}
static int same_metadata(const struct image *a, const struct image *b)
{
    return dl_equal_memory(&a->header, &b->header, sizeof(a->header)) &&
        dl_equal_memory(a->ph, b->ph, a->header.e_phnum * sizeof(a->ph[0])) &&
        dl_equal_memory(a->interpreter, b->interpreter, sizeof(a->interpreter));
}

long dl_exec_resume(int fd, struct dl_entry *entry)
{
    int seals = F_SEAL_SEAL | F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK;
    long actual = DL_CALL(SYS_fcntl, fd, F_GET_SEALS, 0, 0, 0, 0);
    if (actual < 0 || (actual & seals) != seals) return -EINVAL;
    long address = dl_scratch_map(dl_thread_scratch(), sizeof(struct handoff), PROT_READ | PROT_WRITE);
    if (address < 0) return address;
    struct handoff *wire = (void *)address;
    long r = read_exact(fd, wire, sizeof(*wire), 0, NULL);
    struct preparation *saved = &wire->prepared;
    struct stat st;
    size_t limit = 0;
    if (!r) r = argument_limit(&limit);
    if (!r && (wire->magic != HANDOFF_MAGIC || !wire->string_size ||
            saved->string_limit > limit || wire->string_size > saved->string_limit ||
            !saved->argc || saved->argc > limit / sizeof(uintptr_t) + SCRIPT_EXTRA_ARGS ||
            saved->envc > limit / sizeof(uintptr_t) ||
            (saved->argc + saved->envc) * sizeof(uintptr_t) + wire->string_size >
                limit + SCRIPT_EXTRA_ARGS * sizeof(uintptr_t) || wire->root_fd < 0 ||
            saved->main.source_fd < 0 || saved->main.source_fd == wire->root_fd ||
            saved->interpreter.source_fd < -1 || saved->interpreter.source_fd == saved->main.source_fd ||
            saved->interpreter.source_fd == wire->root_fd || wire->root_fd == fd ||
            saved->main.source_fd == fd || saved->interpreter.source_fd == fd ||
            !terminated(wire->root, DL_PATH) || wire->root[0] != '/' || dl_equal(wire->root, "/") ||
            !terminated(saved->path, sizeof(saved->path)) || (wire->flags & ~7) ||
            DL_CALL(SYS_fstat, fd, &st, 0, 0, 0, 0) || st.st_size != (long)(sizeof(*wire) +
                (saved->argc + saved->envc) * sizeof(uintptr_t) + wire->string_size))) r = -EINVAL;
    if (r) goto done;
    dl_state.root_fd = wire->root_fd;
    dl_copy(dl_state.root, wire->root, DL_PATH);
    dl_state.trace = wire->flags & 1; dl_state.exec_profile = !!(wire->flags & 2); dl_state.startup_profile = !!(wire->flags & 4);
    if (DL_CALL(SYS_fcntl, dl_state.root_fd, F_SETFD, FD_CLOEXEC, 0, 0, 0)) { r = -EBADF; goto done; }
    long memory = dl_scratch_map(dl_thread_scratch(), sizeof(struct preparation), PROT_READ | PROT_WRITE);
    if (memory < 0) { r = memory; goto done; }
    struct preparation *p = (void *)memory;
    p->main_slot = p->interpreter_slot = -1; p->main.source_fd = p->interpreter.source_fd = -1;
    p->argc = saved->argc; p->envc = saved->envc; p->string_limit = saved->string_limit;
    unsigned long times[8] = {0}, costs[5] = {0}; times[0] = profile_clock();
    r = allocate_vectors(p); if (r) goto prepared;
    size_t offset = sizeof(*wire);
    r = read_exact(fd, p->argv, p->argc * sizeof(uintptr_t), offset, NULL); if (r) goto prepared;
    offset += p->argc * sizeof(uintptr_t);
    r = read_exact(fd, p->env, p->envc * sizeof(uintptr_t), offset, NULL); if (r) goto prepared;
    offset += p->envc * sizeof(uintptr_t);
    long mapping = dl_scratch_map(dl_thread_scratch(), STACK_SIZE + 2 * dl_state.page, PROT_NONE);
    if (mapping < 0) { r = mapping; goto prepared; }
    p->stack = (char *)mapping + dl_state.page;
    p->top = p->stack + STACK_SIZE - wire->string_size;
    r = DL_CALL(SYS_mprotect, p->stack, STACK_SIZE, PROT_READ | PROT_WRITE, 0, 0, 0); if (r) goto prepared;
    r = read_exact(fd, p->top, wire->string_size, offset, NULL); if (r) goto prepared;
    p->execfn = saved->execfn;
    size_t expected_offset = 0;
    for (unsigned vector = 0; vector < 3 && !r; vector++) {
        size_t count = vector == 0 ? p->argc : vector == 1 ? p->envc : 1;
        uintptr_t *to = vector == 0 ? p->argv : vector == 1 ? p->env : &p->execfn;
        for (size_t i = 0; i < count; i++) {
            if (to[i] != expected_offset || to[i] >= wire->string_size) { r = -EINVAL; break; }
            size_t maximum = wire->string_size - to[i];
            if (maximum > 32 * dl_state.page) maximum = 32 * dl_state.page;
            if (!terminated(p->top + to[i], maximum)) { r = -EINVAL; break; }
            to[i] += (uintptr_t)p->top;
            expected_offset += dl_length((char *)to[i]) + 1;
        }
    }
    if (expected_offset != wire->string_size) r = -EINVAL;
    if (r) goto prepared;
    dl_copy(p->path, saved->path, sizeof(p->path)); times[1] = profile_clock();
    r = read_image_fd(saved->main.source_fd, &p->main, 0);
    if (!r && !same_metadata(&p->main, &saved->main)) r = -EAGAIN;
    if (!r && saved->interpreter.source_fd >= 0) {
        r = read_image_fd(saved->interpreter.source_fd, &p->interpreter, 1);
        if (!r && !same_metadata(&p->interpreter, &saved->interpreter)) r = -EAGAIN;
    }
    if (r) goto prepared;
    times[2] = times[3] = times[4] = profile_clock();
    r = dl_devices_init(); if (r) goto prepared;
    r = install_image(p, entry, times, costs);
    entry->mask = wire->mask & ~(1UL << (SIGSYS - 1));
    entry->virtual_sigsys = wire->virtual_sigsys & (1UL << (SIGSYS - 1));
prepared:
    if (p->stack) dl_scratch_unmap(p->stack - dl_state.page);
    unsigned long bytes = p->main.file_size + p->interpreter.file_size;
    unsigned long read_bytes = p->main.read_bytes + p->interpreter.read_bytes;
    dispose_preparation(p);
    dl_scratch_unmap(p);
    times[7] = profile_clock();
    if (!r && dl_state.exec_profile) profile_report(times, costs, bytes, read_bytes, 2);
done:
    DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0);
    dl_scratch_unmap(wire); return r;
}

long dl_execat(int dirfd, const char *path, char *const *argv, char *const *env, int flags,
    struct dl_entry *entry, int replacing)
{
    if (flags & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW)) return -EINVAL;
    unsigned long times[8] = {0}, costs[5] = {0}, bytes = 0, read_bytes = 0;
    times[0] = profile_clock();
    long memory = dl_scratch_map(dl_thread_scratch(), sizeof(struct preparation) + 2 * dl_state.page,
        PROT_READ | PROT_WRITE);
    if (memory < 0) return memory;
    struct preparation *p = (void *)memory;
    p->main_slot = p->interpreter_slot = -1;
    p->pointers.bytes = p->source_pages;
    p->strings.bytes = p->source_pages + dl_state.page;
    p->main.source_fd = p->interpreter.source_fd = -1;
    char lookup[DL_PATH];
    long r = dl_string(lookup, path);
    if (r) goto done;
    if (!lookup[0] && !(flags & AT_EMPTY_PATH)) { r = -ENOENT; goto done; }
    if (dirfd == AT_FDCWD || lookup[0] == '/') dl_copy(p->path, lookup, dl_length(lookup) + 1);
    else {
        if (dirfd < 0 || dl_internal_fd(dirfd)) { r = -EBADF; goto done; }
        const char prefix[] = "/dev/fd/";
        size_t count = sizeof(prefix) - 1; dl_copy(p->path, prefix, count);
        count += number(p->path + count, (unsigned)dirfd);
        if (lookup[0]) p->path[count++] = '/';
        dl_copy(p->path + count, lookup, dl_length(lookup) + 1);
    }
    long stack = dl_scratch_map(dl_thread_scratch(), STACK_SIZE + 2 * dl_state.page, PROT_NONE);
    if (stack < 0) { r = stack; goto done; }
    p->stack = (char *)stack + dl_state.page; p->top = p->stack + STACK_SIZE;
    r = DL_CALL(SYS_mprotect, p->stack, STACK_SIZE, PROT_READ | PROT_WRITE, 0, 0, 0); if (r) goto done;
    r = prepare_arguments(p, argv, env); if (r) goto done;
    times[1] = profile_clock();
    uintptr_t runtime_stack; __asm__ volatile("mov %0, sp" : "=r"(runtime_stack));
    for (unsigned depth = 0;; depth++) {
        int inaccessible;
        r = protect_image(depth ? AT_FDCWD : dirfd, depth ? p->path : lookup, depth ? 0 : flags,
            &p->main, 0, &p->main_slot, runtime_stack, replacing, &inaccessible);
        if (r != 1) break;
        if (depth == 4) { r = -ELOOP; goto done; }
        r = script_arguments(p, inaccessible); if (r) goto done;
    }
    if (r) goto done;
    if (p->main.interpreter[0]) {
        r = protect_image(AT_FDCWD, p->main.interpreter, 0, &p->interpreter, 1,
            &p->interpreter_slot, runtime_stack, replacing, NULL); if (r) goto done;
    }
    times[2] = profile_clock();
    bytes = p->main.file_size + p->interpreter.file_size;
    read_bytes = p->main.read_bytes + p->interpreter.read_bytes;
    if (replacing == 2) { r = kernel_exec(p, entry); goto done; }
    // Commit point: validated metadata snapshots and strings no longer refer to
    // the old image. Failures before here return errno without changing it.
    // Failures after here cannot return into unmapped guest code.
    if (replacing) {
        if (reset_exec_state(p->main.source_fd, p->interpreter.source_fd)) goto fatal;
    }
    times[3] = profile_clock();
    if (replacing) dl_unmap_all();
    times[4] = profile_clock();
    if (install_image(p, entry, times, costs)) goto fatal;
    r = 0;
done:
    if (p->stack) dl_scratch_unmap(p->stack - dl_state.page);
    dispose_preparation(p);
    dl_scratch_unmap(p);
    times[7] = profile_clock();
    if (!r && dl_state.exec_profile) profile_report(times, costs, bytes, read_bytes, replacing);
    return r;
fatal:
    {
        const char message[] = "Direct Linux: exec failed after image commit\n";
        DL_CALL(SYS_write, 2, message, sizeof(message) - 1, 0, 0, 0);
        DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0);
        __builtin_unreachable();
    }
}

long dl_exec(const char *path, char *const *argv, char *const *env, struct dl_entry *entry, int replacing)
{
    return dl_execat(AT_FDCWD, path, argv, env, 0, entry, replacing);
}
