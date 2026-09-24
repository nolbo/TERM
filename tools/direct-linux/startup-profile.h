/* SPDX-License-Identifier: Apache-2.0 */
/* Opt-in, single-thread diagnostic. No writes while an image is starting.
 * Native/allowed syscalls are not counted; ns covers dispatcher work only. */
#include <time.h>

static unsigned long startup_enter, startup_leave;
static struct { unsigned long count, ns; } startup_calls[512];

static unsigned long startup_clock(void)
{
    struct timespec time;
    if (DL_CALL(SYS_clock_gettime, CLOCK_MONOTONIC, &time, 0, 0, 0, 0)) return 0;
    return (unsigned long)time.tv_sec * 1000000000UL + time.tv_nsec;
}

static void startup_exec(unsigned long enter, unsigned long leave)
{
    startup_enter = enter; startup_leave = leave;
    for (unsigned i = 0; i < 512; i++) startup_calls[i].count = startup_calls[i].ns = 0;
}

static size_t startup_number(char *out, unsigned long value)
{
    char digits[24]; size_t count = 0, n = 0;
    do { digits[count++] = '0' + value % 10; value /= 10; } while (value);
    while (count) out[n++] = digits[--count];
    return n;
}

static void startup_report(unsigned long exit_enter)
{
    char out[4096]; size_t n = 0;
#define TEXT(s) do { const char text[] = s; dl_copy(out + n, text, sizeof(text) - 1); n += sizeof(text) - 1; } while (0)
#define NUMBER(v) do { n += startup_number(out + n, (v)); } while (0)
    TEXT("STARTUP_RUNTIME {\"pid\":"); NUMBER(DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0));
    TEXT(",\"exec_enter_ns\":"); NUMBER(startup_enter);
    TEXT(",\"exec_leave_ns\":"); NUMBER(startup_leave);
    TEXT(",\"exit_enter_ns\":"); NUMBER(exit_enter);
    TEXT(",\"calls\":[");
    int comma = 0, truncated = 0;
    for (unsigned i = 0; i < 512; i++) {
        if (!startup_calls[i].count) continue;
        if (n + 96 >= sizeof(out)) { truncated = 1; break; }
        if (comma++) out[n++] = ',';
        out[n++] = '['; NUMBER(i); out[n++] = ',';
        NUMBER(startup_calls[i].count); out[n++] = ',';
        NUMBER(startup_calls[i].ns); out[n++] = ']';
    }
    TEXT("],\"truncated\":"); NUMBER(truncated); TEXT("}\n");
    DL_CALL(SYS_write, 2, out, n, 0, 0, 0);
#undef NUMBER
#undef TEXT
}
