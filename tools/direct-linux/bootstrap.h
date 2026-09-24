/* SPDX-License-Identifier: Apache-2.0 */
/* Bionic-only preparation, before any guest/signal-stack mapping is created. */
static int bootstrap_fork_policy(void)
{
    const Elf64_Phdr *headers = (void *)getauxval(AT_PHDR);
    size_t count = getauxval(AT_PHNUM);
    uintptr_t base = 0; int found = 0;
    if (!headers || !count || count > 128) return -1;
    for (size_t i = 0; i < count; i++) if (headers[i].p_type == PT_PHDR) {
        base = (uintptr_t)headers - headers[i].p_vaddr; found = 1; break;
    }
    if (!found) return -1;
    // Preserve the entire runtime LOAD span, including page padding and BSS.
    uintptr_t begin = UINTPTR_MAX, end = 0;
    for (size_t i = 0; i < count; i++) if (headers[i].p_type == PT_LOAD) {
        uintptr_t lo = (base + headers[i].p_vaddr) & ~(dl_state.page - 1);
        uintptr_t hi = (base + headers[i].p_vaddr + headers[i].p_memsz + dl_state.page - 1) & ~(dl_state.page - 1);
        if (lo < begin) begin = lo;
        if (hi > end) end = hi;
    }
    if (begin >= end || (uintptr_t)&dl_state < begin || (uintptr_t)&dl_state >= end ||
            (uintptr_t)dl_host_syscall < begin || (uintptr_t)dl_host_syscall >= end) return -1;
    uintptr_t host_tls;
    __asm__ volatile("mrs %0, tpidr_el0" : "=r"(host_tls));
    FILE *maps = fopen("/proc/self/maps", "re");
    if (!maps) return -1;
    char line[8192]; unsigned seen = 0, omitted = 0; unsigned long bytes = 0;
    int result = 0;
    while (fgets(line, sizeof(line), maps)) {
        unsigned long lo, hi;
        if (!strchr(line, '\n') || sscanf(line, "%lx-%lx", &lo, &hi) != 2 || lo >= hi) { result = -1; break; }
        seen++;
        // Retain host TLS too: the kernel may still hold an rseq registration
        // there. Omitting it can SIGSEGV on fork return before guest code runs.
        // The runtime and kernel-provided clock mappings are used after entry.
        // Everything else here predates the guest: linker/libc/allocator/
        // original APK stack. The initial process may keep using Bionic until
        // dl_enter; fork children execute only the audited freestanding code.
        if ((lo < end && hi > begin) || (lo <= host_tls && host_tls < hi) || strstr(line, "[vdso]") || strstr(line, "[vvar")) continue;
        if (madvise((void *)lo, hi - lo, MADV_DONTFORK)) { result = -1; break; }
        omitted++; bytes += hi - lo;
    }
    if (ferror(maps)) result = -1;
    fclose(maps);
    if (dl_state.startup_profile) fprintf(stderr,
        "STARTUP_HOST {\"mappings\":%u,\"omitted\":%u,\"virtual_bytes\":%lu}\n", seen, omitted, bytes);
    return result;
}
