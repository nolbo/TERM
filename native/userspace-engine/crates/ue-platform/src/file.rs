//! Owned, pinned executable input. This is not a guest VFS or an exec permission model.
use core::ffi::CStr;
use ue_abi::nr;
use crate::aarch64::syscall;

pub struct File { fd: u64, pub length: u64 }

impl File {
    pub fn open_executable(path: &CStr) -> Result<Self, i64> {
        // Legacy faccessat uses real credentials. Do not silently apply that test
        // to a privileged/effective-credential launch.
        for (real, effective) in [(nr::GETUID, nr::GETEUID), (nr::GETGID, nr::GETEGID)] {
            let a = unsafe { syscall(real, [0; 6]) };
            let b = unsafe { syscall(effective, [0; 6]) };
            if a.is_error() { return Err(a.0); }
            if b.is_error() { return Err(b.0); }
            if a != b { return Err(-1); }
        }
        // O_PATH pins identity without opening a FIFO/device for I/O. Validate
        // type and permissions before obtaining a readable descriptor.
        let result = unsafe { syscall(nr::OPENAT, [(-100_i64) as u64,
            path.as_ptr() as u64, 0x280000, 0, 0, 0]) }; // O_PATH | O_CLOEXEC
        if result.is_error() { return Err(result.0); }
        let mut file = Self { fd: result.0 as u64, length: 0 };
        // AArch64 kernel struct stat: verified independently against Linux UAPI.
        let mut stat = [0_u64; 16];
        let result = unsafe { syscall(nr::FSTAT, [file.fd, stat.as_mut_ptr() as u64, 0, 0, 0, 0]) };
        if result.is_error() { return Err(result.0); }
        let mode = stat[2] as u32;
        if mode & 0o170000 != 0o100000 || mode & 0o6000 != 0 { return Err(-13); }
        let length = stat[6] as i64;
        if length <= 0 { return Err(-8); }
        file.length = length as u64;
        // Check the pinned inode, not the original pathname after a rename race.
        let mut name = [0_u8; 48];
        let prefix = b"/proc/self/fd/";
        name[..prefix.len()].copy_from_slice(prefix);
        let mut digits = [0_u8; 20];
        let mut index = digits.len();
        let mut value = file.fd;
        loop {
            index -= 1;
            digits[index] = b'0' + (value % 10) as u8;
            value /= 10;
            if value == 0 { break; }
        }
        name[prefix.len()..prefix.len()+digits.len()-index].copy_from_slice(&digits[index..]);
        let result = unsafe { syscall(nr::FACCESSAT, [(-100_i64) as u64,
            name.as_ptr() as u64, 1, 0, 0, 0]) };
        if result.is_error() { return Err(result.0); }
        let result = unsafe { syscall(nr::OPENAT, [(-100_i64) as u64,
            name.as_ptr() as u64, 0x80000, 0, 0, 0]) };
        if result.is_error() { return Err(result.0); }
        // Reopen the pinned inode, then release the O_PATH descriptor via Drop.
        Ok(Self { fd: result.0 as u64, length: file.length })
    }

    pub fn fd(&self) -> u64 { self.fd }

    pub fn prefix(&self, bytes: &mut [u8]) -> Result<usize, i64> {
        let length = bytes.len().min(self.length as usize);
        let mut done = 0;
        while done < length {
            let result = unsafe { syscall(nr::PREAD64, [self.fd,
                bytes[done..].as_mut_ptr() as u64, (length-done) as u64, done as u64, 0, 0]) };
            if result.0 == -4 { continue; }
            if result.is_error() { return Err(result.0); }
            if result.0 == 0 { return Err(-8); }
            done += result.0 as usize;
        }
        Ok(done)
    }
}

impl Drop for File {
    fn drop(&mut self) {
        // Linux may have closed the FD even when close reports EINTR: never retry.
        unsafe { syscall(nr::CLOSE, [self.fd, 0, 0, 0, 0, 0]); }
    }
}
