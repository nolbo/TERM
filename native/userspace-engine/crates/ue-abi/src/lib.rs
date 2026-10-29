#![no_std]

pub const REVISION: u32 = 1;
pub const AUDIT_ARCH_AARCH64: u32 = 0xc00000b7;

/// Captured Linux syscall ABI, not a complete register save area.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(C)]
pub struct RawSyscall {
    pub nr: u64,
    pub args: [u64; 6],
    pub guest_pc: u64,
    pub guest_sp: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(transparent)]
pub struct RawReturn(pub i64);

impl RawReturn {
    pub const fn errno(errno: u16) -> Self {
        Self(-(errno as i64))
    }

    pub const fn is_error(self) -> bool {
        self.0 >= -4095 && self.0 < 0
    }
}

pub mod nr {
    pub const OPENAT: u64 = 56;
    pub const CLOSE: u64 = 57;
    pub const PIPE2: u64 = 59;
    pub const READ: u64 = 63;
    pub const WRITE: u64 = 64;
    pub const EXIT_GROUP: u64 = 94;
    pub const CLOCK_GETTIME: u64 = 113;
    pub const RT_SIGACTION: u64 = 134;
    pub const RT_SIGRETURN: u64 = 139;
    pub const PRCTL: u64 = 167;
    pub const GETPID: u64 = 172;
    pub const GETUID: u64 = 174;
    pub const GETEUID: u64 = 175;
    pub const GETGID: u64 = 176;
    pub const GETEGID: u64 = 177;
    pub const GETTID: u64 = 178;
    pub const MUNMAP: u64 = 215;
    pub const MMAP: u64 = 222;
    pub const MPROTECT: u64 = 226;
}

const _: () = assert!(core::mem::size_of::<RawSyscall>() == 72);
const _: () = assert!(core::mem::offset_of!(RawSyscall, guest_pc) == 56);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_errno_does_not_confuse_large_negative_values_with_errors() {
        assert!(RawReturn::errno(14).is_error());
        assert!(RawReturn(-4095).is_error());
        assert!(!RawReturn(-4096).is_error());
        assert!(!RawReturn(0).is_error());
        assert!(!RawReturn(i64::MIN).is_error());
    }
}
