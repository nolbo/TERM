/* SPDX-License-Identifier: Apache-2.0 */
/* ARM64 host test of the actual ELF reader/mapper with ordinary file syscalls.
 * Path/argument helpers below are a host adapter, not guest-path validation. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>
#include "exec.c"

struct dl_state dl_state;
unsigned dl_memory_lock, dl_files_lock;
void dl_lock(unsigned *lock) { assert(!*lock); *lock = 1; }
void dl_unlock(unsigned *lock) { assert(*lock); *lock = 0; }
long dl_host_syscall(long n, long a, long b, long c, long d, long e, long f)
{
    long result = syscall(n, a, b, c, d, e, f); return result < 0 ? -errno : result;
}
size_t dl_length(const char *s) { return strlen(s); }
int dl_equal(const char *a, const char *b) { return !strcmp(a, b); }
long dl_devices_init(void) { assert(0); return -1; }
static unsigned memory_reads;
long dl_read_memory(void *d, const void *s, size_t n)
{
    struct iovec local = {d, n}, remote = {(void *)s, n};
    memory_reads++;
    long r = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
    return r < 0 ? -errno : (size_t)r == n ? 0 : -EFAULT;
}
long dl_string(char *d, const char *s) { if (strlen(s) >= DL_PATH) return -ENAMETOOLONG; strcpy(d, s); return 0; }
long dl_open(int fd, const char *path, int flags, unsigned mode)
{
    int result = openat(fd, path, flags, mode); return result < 0 ? -errno : result;
}
long dl_file_path(int fd, char out[DL_PATH])
{
    char name[64]; snprintf(name, sizeof(name), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(name, out, DL_PATH - 1); if (n < 0) return -errno; out[n] = 0; return 0;
}
long dl_executable(int fd)
{
    char name[64]; snprintf(name, sizeof(name), "/proc/self/fd/%d", fd);
    return access(name, X_OK) ? -errno : 0;
}
long dl_signal_reset_exec(void) { return 0; }
long dl_close_exec_fds(int a, int b) { (void)a; (void)b; return 0; }
long dl_io_hold(int fd, uintptr_t stack) { (void)fd; (void)stack; assert(0); return -1; }
void dl_io_release(unsigned slot) { (void)slot; assert(0); }
long dl_exec_begin(void) { assert(0); return 0; }
void dl_exec_end(void) { assert(0); }

static void argument_snapshots(void)
{
    size_t page = dl_state.page, count;
    struct preparation *p = calloc(1, sizeof(*p) + 2 * page); assert(p);
    p->pointers.bytes = p->source_pages; p->strings.bytes = p->source_pages + page;
    p->stack = mmap(NULL, STACK_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(p->stack != MAP_FAILED); p->top = p->stack + STACK_SIZE;
    char *data = mmap(NULL, 4 * page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(data != MAP_FAILED && !mprotect(data + page, 2 * page, PROT_READ | PROT_WRITE));
    char *text = data + 3 * page - 4; memcpy(text, "end", 4);
    char **table = (void *)(data + page);
    table[0] = text; table[1] = NULL;
    assert(!vector(p, table, p->argv, &count, ARG_LIMIT));
    assert(count == 1 && !strcmp((char *)p->argv[0], "end"));
    // Repeated strings/table entries reuse two snapshots within this vector.
    for (int i = 0; i < ARG_LIMIT; i++) table[i] = text;
    table[ARG_LIMIT] = NULL; memory_reads = 0;
    assert(!vector(p, table, p->argv, &count, ARG_LIMIT) && count == ARG_LIMIT);
    assert(memory_reads == 2);
    for (size_t i = 0; i < count; i++) assert(!strcmp((char *)p->argv[i], "end"));
    table[ARG_LIMIT] = text;
    assert(vector(p, table, p->argv, &count, ARG_LIMIT) == -E2BIG);
    // A new vector (including envp or a subsequent exec) must read fresh bytes.
    memcpy(text, "new", 4); table[0] = text; table[1] = NULL;
    assert(!vector(p, table, p->env, &count, ENV_LIMIT));
    assert(count == 1 && !strcmp((char *)p->env[0], "new"));
    for (int i = 0; i < ENV_LIMIT; i++) table[i] = text;
    table[ENV_LIMIT] = NULL;
    assert(!vector(p, table, p->env, &count, ENV_LIMIT) && count == ENV_LIMIT);
    assert(!vector(p, NULL, p->argv, &count, ARG_LIMIT) && !count);
    // Unaligned table entry straddles two accessible pages.
    char *cross = data + 2 * page - 3;
    memcpy(cross, &text, sizeof(text)); memset(cross + sizeof(text), 0, sizeof(text));
    assert(!vector(p, (void *)cross, p->argv, &count, ARG_LIMIT));
    assert(count == 1 && !strcmp((char *)p->argv[0], "new"));
    // The terminating NULL can be the last word of a readable page.
    table = (void *)(data + 3 * page - sizeof(char *)); *table = NULL;
    assert(!vector(p, table, p->argv, &count, ARG_LIMIT) && !count);
    *table = data + page; strcpy(*table, "valid");
    assert(vector(p, table, p->argv, &count, ARG_LIMIT) == -EFAULT);
    char *invalid[] = {data, NULL};
    assert(vector(p, invalid, p->argv, &count, ARG_LIMIT) == -EFAULT);
    assert(vector(p, (void *)1, p->argv, &count, ARG_LIMIT) == -EFAULT);
    // Nonterminated strings must fault instead of using bytes past a guard.
    text = data + 3 * page - 4; memset(text, 'x', 4);
    char *unterminated[] = {text, NULL};
    assert(vector(p, unterminated, p->argv, &count, ARG_LIMIT) == -EFAULT);
    char *large = mmap(NULL, 131072, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(large != MAP_FAILED); memset(large, 'a', 131072); large[131071] = 0;
    char *long_vector[] = {large, NULL};
    assert(!vector(p, long_vector, p->argv, &count, ARG_LIMIT) && count == 1);
    assert(strlen((char *)p->argv[0]) == 131071 && !memcmp((char *)p->argv[0], large, 131072));
    large[131071] = 'a';
    assert(vector(p, long_vector, p->argv, &count, ARG_LIMIT) == -E2BIG);
    assert(!munmap(large, 131072) && !munmap(data, 4 * page) && !munmap(p->stack, STACK_SIZE)); free(p);
    puts("PASS exec argument snapshots, fresh vectors, limits, unaligned tables and guarded page boundaries");
}

int main(void)
{
    dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    argument_snapshots();
    size_t size = 2 * dl_state.page, phoff = dl_state.page - 32, interp = phoff + 2 * sizeof(Elf64_Phdr) + 64;
    unsigned char *bytes = calloc(1, size); assert(bytes);
    Elf64_Ehdr *header = (void *)bytes;
    memcpy(header->e_ident, ELFMAG, SELFMAG);
    header->e_ident[EI_CLASS] = ELFCLASS64; header->e_ident[EI_DATA] = ELFDATA2LSB;
    header->e_ident[EI_VERSION] = EV_CURRENT;
    header->e_type = ET_DYN; header->e_machine = EM_AARCH64; header->e_version = EV_CURRENT;
    header->e_entry = 512; header->e_phoff = phoff; header->e_ehsize = sizeof(*header);
    header->e_phentsize = sizeof(Elf64_Phdr); header->e_phnum = 2;
    Elf64_Phdr *ph = (void *)(bytes + phoff);
    ph[0] = (Elf64_Phdr){.p_type = PT_LOAD, .p_flags = PF_R | PF_X, .p_filesz = size, .p_memsz = size, .p_align = dl_state.page};
    const char interpreter[] = "/fixture-ld.so"; memcpy(bytes + interp, interpreter, sizeof(interpreter));
    ph[1] = (Elf64_Phdr){.p_type = PT_INTERP, .p_offset = interp, .p_filesz = sizeof(interpreter)};
    char path[] = "/tmp/direct-linux-elf-metadata-XXXXXX";
    int fd = mkstemp(path); assert(fd >= 0 && !fchmod(fd, 0700));
    assert(write(fd, bytes, size) == (ssize_t)size);
    const size_t mutations[] = {offsetof(Elf64_Ehdr, e_entry), phoff + sizeof(Elf64_Phdr), interp + 1};
    for (unsigned i = 0; i < sizeof(mutations) / sizeof(mutations[0]); i++) {
        struct image image = {0}; unsigned long costs[5] = {0};
        assert(read_image(path, &image, 0) == 0);
        assert(image.read_bytes == 256 + 2 * sizeof(Elf64_Phdr) + sizeof(interpreter));
        unsigned char changed = bytes[mutations[i]] ^ 1;
        assert(pwrite(fd, &changed, 1, mutations[i]) == 1);
        assert(map_image(&image, costs) == -EAGAIN);
        dl_unmap_all(); dispose_image(&image);
        assert(pwrite(fd, bytes + mutations[i], 1, mutations[i]) == 1);
    }
    struct image image = {0}; unsigned long costs[5] = {0};
    assert(read_image(path, &image, 0) == 0);
    // Ordinary payload bytes are demand-paged contents, not a promised immutable
    // executable snapshot. Metadata checks above must remain strict regardless.
    unsigned char changed = 0x69; assert(pwrite(fd, &changed, 1, 512) == 1);
    assert(map_image(&image, costs) == 0 && *(unsigned char *)(image.base + 512) == changed);
    dl_unmap_all(); dispose_image(&image);
    assert(read_image(path, &image, 0) == 0);
    assert(unlink(path) == 0);
    int replacement = open(path, O_CREAT | O_EXCL | O_WRONLY, 0700); assert(replacement >= 0);
    assert(write(replacement, "not an ELF", 10) == 10 && !close(replacement));
    assert(map_image(&image, costs) == 0 && *(unsigned char *)(image.base + 512) == changed);
    dl_unmap_all(); dispose_image(&image);
    assert(!close(fd) && !unlink(path)); free(bytes);
    puts("PASS ELF metadata mutation rejection, page-crossing program headers, payload paging and pinned source inode");
    return 0;
}
