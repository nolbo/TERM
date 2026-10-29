//! AArch64 Linux kernel UAPI layouts (not libc's sigaction layout).
//! Extended signal records remain in the kernel frame; entry code must not erase them.

#[repr(C)]
pub struct Sigaction {
    pub handler: u64,
    pub flags: u64,
    pub restorer: u64,
    pub mask: u64,
}

#[repr(C)]
pub struct SigsysInfo {
    pub signo: i32,
    pub errno: i32,
    pub code: i32,
    pub padding: i32,
    pub call_addr: u64,
    pub syscall: i32,
    pub arch: u32,
    pub unused: [u8; 96],
}

#[repr(C)]
pub struct SignalStack { pub sp: u64, pub flags: i32, pub padding: u32, pub size: u64 }

#[repr(C, align(16))]
pub struct ExtensionArea(pub [u8; 4096]);

#[repr(C)]
pub struct MachineContext {
    pub fault_address: u64,
    pub regs: [u64; 31],
    pub sp: u64,
    pub pc: u64,
    pub pstate: u64,
    pub extensions: ExtensionArea,
}

#[repr(C)]
pub struct UContext {
    pub flags: u64,
    pub link: u64,
    pub stack: SignalStack,
    pub mask: u64,
    pub unused_mask: [u8; 120],
    pub machine: MachineContext,
}

const _: () = assert!(core::mem::size_of::<Sigaction>() == 32);
const _: () = assert!(core::mem::size_of::<SigsysInfo>() == 128);
const _: () = assert!(core::mem::offset_of!(SigsysInfo, syscall) == 24);
const _: () = assert!(core::mem::offset_of!(UContext, machine) == 176);
const _: () = assert!(core::mem::offset_of!(MachineContext, extensions) == 288);
const _: () = assert!(core::mem::size_of::<UContext>() == 4560);
