//! Closed-world entry experiment, not the guest execution engine.
//! Only the syscall set below is dispatched. No general FD, signal, task or VFS
//! compatibility is implied by these fixtures. Run in a dedicated process.

use core::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use ue_abi::{nr, RawReturn, RawSyscall, AUDIT_ARCH_AARCH64};
use ue_core::{identity::Credentials, patch::svc_branch};
use ue_platform::{aarch64::{gate_ip, syscall, synchronize_code, ue_sigreturn},
    filter::ProbeFilter, signal::{Sigaction, SigsysInfo, UContext}};

static INSTALLED: AtomicBool = AtomicBool::new(false);
static TRAPS: AtomicU64 = AtomicU64::new(0);
pub const VIRTUAL_UID: i64 = 12345;
pub const CREDENTIALS: Credentials = Credentials { uid: VIRTUAL_UID as u32, euid: VIRTUAL_UID as u32, gid: 23456, egid: 23456 };

pub fn trap_count() -> u64 { TRAPS.load(Ordering::Relaxed) }

/// # Safety
/// Must run in an isolated, single-threaded fixture with a default SIGSYS action.
/// Installs an irreversible seccomp filter and no_new_privs; it is not a guest API.
pub unsafe fn install() -> Result<(), RawReturn> {
    // install() requires a single-threaded process; no read-modify-write helper
    // (and therefore no libc-dependent outlined-atomics initialization) is needed.
    if INSTALLED.load(Ordering::Relaxed) { return Err(RawReturn::errno(114)); }
    INSTALLED.store(true, Ordering::Relaxed);
    let numbers = [nr::GETPID, nr::GETTID, nr::GETUID, nr::GETEUID, nr::GETGID, nr::GETEGID, nr::READ, nr::WRITE];
    let numbers = numbers.map(|nr| nr as u32);
    let filter = ProbeFilter::new(gate_ip(), &numbers).map_err(|_| RawReturn::errno(22))?;
    let program = filter.program();
    let mut old = Sigaction { handler: 0, flags: 0, restorer: 0, mask: 0 };
    let result = unsafe { syscall(nr::RT_SIGACTION, [31, 0, (&mut old as *mut Sigaction) as u64, 8, 0, 0]) };
    if result.is_error() { return Err(result); }
    if old.handler != 0 { return Err(RawReturn::errno(17)); }
    let action = Sigaction { handler: sigsys as *const () as u64, flags: 4 | 0x04000000,
        restorer: ue_sigreturn as *const () as u64, mask: 0 };
    let result = unsafe { syscall(nr::RT_SIGACTION, [31, (&action as *const Sigaction) as u64, 0, 8, 0, 0]) };
    if result.is_error() { return Err(result); }
    let result = unsafe { syscall(nr::PRCTL, [38, 1, 0, 0, 0, 0]) };
    let result = if result.is_error() { result } else {
        unsafe { syscall(nr::PRCTL, [22, 2, (&program as *const _) as u64, 0, 0, 0]) }
    };
    if result.is_error() {
        unsafe { syscall(nr::RT_SIGACTION, [31, (&old as *const Sigaction) as u64, 0, 8, 0, 0]); }
        return Err(result);
    }
    Ok(())
}

fn dispatch(call: RawSyscall) -> RawReturn {
    if let Some(result) = CREDENTIALS.query(call.nr) { return result; }
    match call.nr {
        // Fixture FD set contains only stdio and explicitly constructed test pipes.
        nr::GETPID | nr::GETTID | nr::READ | nr::WRITE => unsafe { syscall(call.nr, call.args) },
        _ => RawReturn::errno(38),
    }
}

unsafe extern "C" fn sigsys(signal: i32, info: *const SigsysInfo, context: *mut UContext) {
    // These pointers are supplied by the Linux kernel, never a guest API argument.
    let info = unsafe { &*info };
    if signal != 31 || info.signo != 31 || info.code != 1 || info.arch != AUDIT_ARCH_AARCH64 || info.syscall < 0 {
        unsafe { syscall(nr::EXIT_GROUP, [125, 0, 0, 0, 0, 0]); }
        loop { core::hint::spin_loop(); }
    }
    let machine = unsafe { &mut (*context).machine };
    let call = RawSyscall { nr: info.syscall as u64, args: machine.regs[..6].try_into().unwrap(),
        guest_pc: machine.pc.wrapping_sub(4), guest_sp: machine.sp };
    // This probe has one thread and SIGSYS is blocked during its handler. There
    // is one writer; main only observes the count between fixture calls.
    TRAPS.store(TRAPS.load(Ordering::Relaxed) + 1, Ordering::Relaxed);
    // Do not advance PC: the kernel already points past the trapped SVC.
    machine.regs[0] = dispatch(call).0 as u64;
}

#[no_mangle]
extern "C" fn ue_probe_dispatch(nr: u64, a0: u64, a1: u64, a2: u64, a3: u64, a4: u64, a5: u64) -> i64 {
    // This known AAPCS wrapper has no guest signal-PC reconstruction yet.
    dispatch(RawSyscall { nr, args: [a0, a1, a2, a3, a4, a5], guest_pc: 0, guest_sp: 0 }).0
}

core::arch::global_asm!(include_str!("probe-entry.S"));
unsafe extern "C" {
    fn ue_probe_direct_entry();
    pub fn ue_probe_registers(nr: u64) -> u64;
}

pub type FixtureCall = unsafe extern "C" fn(u64, u64, u64, u64, u64, u64, u64) -> i64;

/// An engine-owned, unpublished AAPCS wrapper, never arbitrary program text.
pub struct Fixture {
    address: usize,
    length: usize,
}

impl Fixture {
    pub fn create(page_size: usize, connected: bool) -> Result<Self, RawReturn> {
        if !page_size.is_power_of_two() || !(4096..=65536).contains(&page_size) { return Err(RawReturn::errno(22)); }
        let result = unsafe { syscall(nr::MMAP, [0, page_size as u64, 3, 0x22, u64::MAX, 0]) };
        if result.is_error() { return Err(result); }
        if result.0 == 0 {
            unsafe { syscall(nr::MUNMAP, [0, page_size as u64, 0, 0, 0, 0]); }
            return Err(RawReturn::errno(12));
        }
        let fixture = Self { address: result.0 as usize, length: page_size };
        // mov x8,x0; shift the six C arguments; svc #0; ret.
        let mut instructions = [0xaa0003e8_u32, 0xaa0103e0, 0xaa0203e1, 0xaa0303e2,
            0xaa0403e3, 0xaa0503e4, 0xaa0603e5, 0xd4000001, 0xd65f03c0];
        if connected {
            instructions[7] = svc_branch((fixture.address + 28) as u64,
                (fixture.address + 64) as u64, instructions[7]).map_err(|_| RawReturn::errno(22))?;
        }
        unsafe {
            core::ptr::copy_nonoverlapping(instructions.as_ptr(), fixture.address as *mut u32, instructions.len());
            if connected {
                // The known C wrapper permits x16 clobber; not valid for arbitrary SVC sites.
                ((fixture.address + 64) as *mut u32).write(0x58000050); // ldr x16, +8
                ((fixture.address + 68) as *mut u32).write(0xd61f0200); // br x16
                ((fixture.address + 72) as *mut u64).write(ue_probe_direct_entry as *const () as u64);
            }
            synchronize_code(fixture.address, fixture.address + 80);
        }
        let result = unsafe { syscall(nr::MPROTECT, [fixture.address as u64, page_size as u64, 5, 0, 0, 0]) };
        if result.is_error() { return Err(result); }
        Ok(fixture)
    }

    /// # Safety
    /// Callers must satisfy the invoked syscall's memory and lifetime contracts.
    /// The returned pointer must not outlive this mapping.
    pub unsafe fn function(&self) -> FixtureCall {
        unsafe { core::mem::transmute(self.address) }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        unsafe { syscall(nr::MUNMAP, [self.address as u64, self.length as u64, 0, 0, 0, 0]); }
    }
}
