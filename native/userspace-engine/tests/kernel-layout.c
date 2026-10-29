/* Compile against the host's AArch64 Linux UAPI, independently of Rust layouts. */
#include <stddef.h>
#include <asm/signal.h>
#include <asm/siginfo.h>
#include <asm/sigcontext.h>
#include <asm/ucontext.h>

_Static_assert(sizeof(struct sigaction) == 32, "kernel sigaction size");
_Static_assert(sizeof(siginfo_t) == 128, "kernel siginfo size");
_Static_assert(offsetof(siginfo_t, si_syscall) == 24, "SIGSYS syscall offset");
_Static_assert(offsetof(siginfo_t, si_arch) == 28, "SIGSYS arch offset");
_Static_assert(offsetof(struct ucontext, uc_mcontext) == 176, "mcontext offset");
_Static_assert(offsetof(struct sigcontext, __reserved) == 288, "extension offset");
_Static_assert(sizeof(struct ucontext) == 4560, "ucontext size");
