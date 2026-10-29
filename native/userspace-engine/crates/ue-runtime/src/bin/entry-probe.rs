#![no_std]
#![no_main]

use ue_abi::{nr, RawReturn, AUDIT_ARCH_AARCH64};
use ue_platform::{aarch64::syscall, filter::{equal, load, ret, Program, ALLOW, ERRNO, KILL_PROCESS}};
use ue_runtime::probe::{self, Fixture, FixtureCall};

core::arch::global_asm!(r#"
.text
.global _start
.type _start, %function
_start:
    mov x0, sp
    mov x29, xzr
    mov x30, xzr
    bl ue_probe_main
    brk #0
.size _start, .-_start
.section .note.GNU-stack,"",%progbits
"#);

fn exit(code: u64) -> ! {
    unsafe { syscall(nr::EXIT_GROUP, [code, 0, 0, 0, 0, 0]); }
    loop { core::hint::spin_loop(); }
}

fn emit(bytes: &[u8]) {
    let mut offset = 0;
    while offset < bytes.len() {
        let result = unsafe { syscall(nr::WRITE, [1, bytes[offset..].as_ptr() as u64, (bytes.len() - offset) as u64, 0, 0, 0]) };
        if result == RawReturn::errno(4) { continue; }
        if result.0 <= 0 { exit(124); }
        offset += result.0 as usize;
    }
}

fn number(mut value: u64) {
    let mut digits = [0_u8; 20];
    let mut start = digits.len();
    loop {
        start -= 1;
        digits[start] = b'0' + (value % 10) as u8;
        value /= 10;
        if value == 0 { break; }
    }
    emit(&digits[start..]);
}

fn check(condition: bool, label: &[u8]) {
    if !condition { emit(b"FAIL "); emit(label); emit(b"\n"); exit(1); }
    emit(b"PASS "); emit(label); emit(b"\n");
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! { emit(b"FAIL panic\n"); exit(120); }

unsafe fn call(function: FixtureCall, nr: u64, args: [u64; 6]) -> i64 {
    unsafe { function(nr, args[0], args[1], args[2], args[3], args[4], args[5]) }
}

unsafe fn page_size(stack: *const usize) -> Option<usize> {
    let argc = unsafe { *stack };
    if argc > 4096 { return None; }
    let mut index = argc + 2;
    for _ in 0..16384 {
        let env = unsafe { *stack.add(index) };
        index += 1;
        if env == 0 {
            for _ in 0..128 {
                let tag = unsafe { *stack.add(index) };
                let value = unsafe { *stack.add(index + 1) };
                if tag == 6 { return Some(value); }
                if tag == 0 { return None; }
                index += 2;
            }
            return None;
        }
    }
    None
}

fn now() -> u64 {
    let mut time = [0_u64; 2];
    let result = unsafe { syscall(nr::CLOCK_GETTIME, [1, time.as_mut_ptr() as u64, 0, 0, 0, 0]) };
    if result.is_error() { exit(121); }
    time[0] * 1_000_000_000 + time[1]
}

const ITERATIONS: u64 = 10_000;

unsafe fn time_fixture(function: FixtureCall, expected_pid: i64) -> u64 {
    let start = now();
    for _ in 0..ITERATIONS {
        if unsafe { call(function, nr::GETPID, [0; 6]) } != expected_pid { exit(122); }
    }
    now() - start
}

#[no_mangle]
unsafe extern "C" fn ue_probe_main(stack: *const usize) -> ! {
    let page = unsafe { page_size(stack) }.unwrap_or_else(|| exit(123));
    emit(b"ue-entry-probe abi=1 page_size="); number(page as u64); emit(b"\n");
    let inherited_mode = unsafe { syscall(nr::PRCTL, [21, 0, 0, 0, 0, 0]) };
    check(!inherited_mode.is_error(), b"read-inherited-seccomp-mode");
    emit(b"inherited_seccomp_mode="); number(inherited_mode.0 as u64); emit(b"\n");
    let original = Fixture::create(page, false).unwrap();
    let connected = Fixture::create(page, true).unwrap();
    let raw = unsafe { original.function() };
    let patched = unsafe { connected.function() };
    let pid = unsafe { syscall(nr::GETPID, [0; 6]) }.0;
    check(pid > 0 && unsafe { call(raw, nr::GETPID, [0; 6]) } == pid, b"unmodified-wrapper");
    check(unsafe { call(patched, nr::GETPID, [0; 6]) } == pid, b"connected-wrapper");
    check(unsafe { call(patched, nr::GETUID, [0; 6]) } == probe::VIRTUAL_UID, b"connected-identity");
    check(unsafe { probe::ue_probe_registers(nr::GETPID) } == 0, b"native-register-sample");
    let baseline_ns = unsafe { time_fixture(raw, pid) };
    unsafe { probe::install() }.unwrap();
    let count = probe::trap_count();
    check(unsafe { call(raw, nr::GETPID, [0; 6]) } == pid && probe::trap_count() == count + 1, b"trap-return-and-exact-gate");
    check(unsafe { call(raw, nr::GETUID, [0; 6]) } == probe::VIRTUAL_UID, b"trap-identity");
    check(unsafe { probe::ue_probe_registers(nr::GETPID) } == 0, b"trap-gpr-nzcv-fpsimd-sample");
    let count = probe::trap_count();
    check(unsafe { call(patched, nr::GETPID, [0; 6]) } == pid && probe::trap_count() == count, b"connected-without-signal");

    let mut fds = [-1_i32; 2];
    let result = unsafe { syscall(nr::PIPE2, [fds.as_mut_ptr() as u64, 0, 0, 0, 0, 0]) };
    check(result.0 == 0, b"pipe-create");
    let bytes = b"independent engine\n";
    let written = unsafe { call(patched, nr::WRITE, [fds[1] as u64, bytes.as_ptr() as u64, bytes.len() as u64, 0, 0, 0]) };
    let mut output = [0_u8; 64];
    let read = unsafe { call(raw, nr::READ, [fds[0] as u64, output.as_mut_ptr() as u64, output.len() as u64, 0, 0, 0]) };
    check(written == bytes.len() as i64 && read == written && &output[..read as usize] == bytes, b"connected-write-trap-read");
    let bad = [fds[1] as u64, 1, 8, 0, 0, 0];
    check(unsafe { call(raw, nr::WRITE, bad) } == -14 && unsafe { call(patched, nr::WRITE, bad) } == -14, b"efault-without-pointer-dereference");
    for fd in fds { unsafe { syscall(nr::CLOSE, [fd as u64, 0, 0, 0, 0, 0]); } }

    let count = probe::trap_count();
    let connected_ns = unsafe { time_fixture(patched, pid) };
    check(probe::trap_count() == count, b"connected-loop-no-traps");
    let trap_ns = unsafe { time_fixture(raw, pid) };
    check(probe::trap_count() == count + ITERATIONS, b"trap-loop-exact-count");

    // A second filter denies getpid even at our own gate. This must not be bypassed.
    let deny = [load(4), equal(AUDIT_ARCH_AARCH64, 1, 0), ret(KILL_PROCESS),
        load(0), equal(nr::GETPID as u32, 0, 1), ret(ERRNO | 1), ret(ALLOW)];
    let program = Program::from_slice(&deny).unwrap();
    let result = unsafe { syscall(nr::PRCTL, [22, 2, (&program as *const _) as u64, 0, 0, 0]) };
    check(result.0 == 0, b"additional-filter-install");
    check(unsafe { syscall(nr::GETPID, [0; 6]) }.0 == -1 &&
        unsafe { call(raw, nr::GETPID, [0; 6]) } == -1 &&
        unsafe { call(patched, nr::GETPID, [0; 6]) } == -1, b"gate-respects-other-filter");
    drop(connected);
    drop(original);
    emit(b"RESULT {\"status\":\"pass\",\"iterations\":"); number(ITERATIONS);
    emit(b",\"native_ns\":"); number(baseline_ns);
    emit(b",\"connected_ns\":"); number(connected_ns);
    emit(b",\"trap_ns\":"); number(trap_ns);
    emit(b",\"traps\":"); number(probe::trap_count()); emit(b"}\n");
    exit(0)
}

// Compiler lowering may emit these even in no_std code. Volatile byte operations
// prevent the optimizer from replacing their bodies with recursive libc calls.
#[no_mangle]
unsafe extern "C" fn memcpy(dst: *mut u8, src: *const u8, length: usize) -> *mut u8 {
    for i in 0..length { unsafe { dst.add(i).write_volatile(src.add(i).read_volatile()); } }
    dst
}
#[no_mangle]
unsafe extern "C" fn memset(dst: *mut u8, value: i32, length: usize) -> *mut u8 {
    for i in 0..length { unsafe { dst.add(i).write_volatile(value as u8); } }
    dst
}
#[no_mangle]
unsafe extern "C" fn memmove(dst: *mut u8, src: *const u8, length: usize) -> *mut u8 {
    if (dst as usize) <= src as usize {
        unsafe { memcpy(dst, src, length); }
    } else {
        for i in (0..length).rev() { unsafe { dst.add(i).write_volatile(src.add(i).read_volatile()); } }
    }
    dst
}
#[no_mangle]
unsafe extern "C" fn memcmp(a: *const u8, b: *const u8, length: usize) -> i32 {
    for i in 0..length {
        let difference = unsafe { i32::from(a.add(i).read_volatile()) - i32::from(b.add(i).read_volatile()) };
        if difference != 0 { return difference; }
    }
    0
}
