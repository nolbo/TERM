#![no_std]
#![no_main]

use ue_abi::nr;
use ue_platform::aarch64::syscall;
use ue_runtime::{loader::{Error, InitialStack, Program}, probe};

#[path = "../freestanding.rs"]
mod freestanding;

core::arch::global_asm!(r#"
.text
.global _start
.type _start, %function
_start:
    mov x0, sp
    mov x29, xzr
    mov x30, xzr
    bl ue_loader_main
    brk #0
.size _start, .-_start
.section .note.GNU-stack,"",%progbits
"#);

fn exit(code: u64) -> ! {
    unsafe { syscall(nr::EXIT_GROUP, [code, 0, 0, 0, 0, 0]); }
    loop { core::hint::spin_loop(); }
}

fn fail(error: Error) -> ! {
    use core::fmt::Write;
    struct Output;
    impl core::fmt::Write for Output {
        fn write_str(&mut self, value: &str) -> core::fmt::Result {
            let mut bytes = value.as_bytes();
            while !bytes.is_empty() {
                let result = unsafe { syscall(nr::WRITE, [2, bytes.as_ptr() as u64, bytes.len() as u64, 0, 0, 0]) };
                if result.0 == -4 { continue; }
                if result.0 <= 0 { return Err(core::fmt::Error); }
                bytes = &bytes[result.0 as usize..];
            }
            Ok(())
        }
    }
    let _ = writeln!(Output, "ue-loader-probe: {error:?}");
    exit(126)
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! { fail(Error::Input) }

#[no_mangle]
unsafe extern "C" fn ue_loader_main(stack: *const usize) -> ! {
    let result = (|| {
        let initial = unsafe { InitialStack::from_kernel(stack) }?;
        let trap = initial.argument(1)?.to_bytes() == b"--probe-trap";
        let first = if trap { 2 } else { 1 };
        if initial.aux(23).unwrap_or(0) != 0 { return Err(Error::Input); }
        let program = Program::load(initial.argument(first)?, initial.aux(6).ok_or(Error::Input)?)?;
        let credentials = if trap { probe::CREDENTIALS } else { initial.credentials()? };
        let stack = program.stack(&initial, first, credentials)?;
        if trap { unsafe { probe::install() }.map_err(|e| Error::Host(e.0))?; }
        unsafe { program.enter(stack) }
    })();
    match result { Err(error) => fail(error), Ok(()) => exit(125) }
}
