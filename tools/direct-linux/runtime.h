/* SPDX-License-Identifier: Apache-2.0 */
#ifndef DIRECT_LINUX_RUNTIME_H
#define DIRECT_LINUX_RUNTIME_H
#include <stddef.h>
#include <stdint.h>

#define DL_PATH 4096
#define DL_HEAP (64UL * 1024 * 1024)
extern long dl_host_syscall(long, long, long, long, long, long, long);
extern char dl_host_return[];
extern void dl_sigreturn(void);
extern _Noreturn void dl_enter(uintptr_t entry, uintptr_t stack);
#define DL_CALL(n,a,b,c,d,e,f) dl_host_syscall(n,(long)(a),(long)(b),(long)(c),(long)(d),(long)(e),(long)(f))

struct dl_state {
    int root_fd;
    int vfork_fd;
    int trace;
    int exec_profile;
    size_t page;
    unsigned long hwcap, hwcap2, vdso, minsigstksz, clktck;
    char root[DL_PATH];
    char executable[DL_PATH];
    uintptr_t heap_start, heap_end, heap_mapped;
    unsigned long virtual_sigsys_mask;
};
extern struct dl_state dl_state;
size_t dl_length(const char *s);
void dl_copy(void *destination, const void *source, size_t size);
int dl_equal_memory(const void *a, const void *b, size_t size);
int dl_equal(const char *a, const char *b);
long dl_read_memory(void *destination, const void *source, size_t size);
long dl_write_memory(void *destination, const void *source, size_t size);
long dl_string(char *destination, const char *source);
long dl_open(int dirfd, const char *path, int flags, unsigned mode);
long dl_filesystem(long nr, unsigned long args[6]);
long dl_devices_init(void);
int dl_device_index(int dirfd, const char *name);
long dl_device_open(int index, int dirfd, int flags, unsigned mode);
long dl_device_change(int parent, const char *name, int removing);
long dl_getdents(int fd, void *buffer, unsigned count);
long dl_file_path(int fd, char out[DL_PATH]);
long dl_executable(int fd);
long dl_virtual_cwd(char path[DL_PATH]);
int dl_install(void *signal_stack, size_t size);
struct dl_entry { uintptr_t pc, sp; };
long dl_exec(const char *path, char *const *argv, char *const *env, struct dl_entry *entry, int replacing);
long dl_mmap(uintptr_t address, size_t size, int prot, int flags, int fd, unsigned long offset);
long dl_munmap(uintptr_t address, size_t size);
long dl_track(uintptr_t address, size_t size);
void dl_unmap_all(void);
void dl_clear_cache(uintptr_t begin, uintptr_t end);
#endif
