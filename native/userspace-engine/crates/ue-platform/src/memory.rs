//! Reservation ownership: MAP_FIXED may only replace pages already owned here.
use ue_abi::nr;
use crate::{aarch64::syscall, file::File};

pub const READ: u64 = 1;
pub const WRITE: u64 = 2;
pub const EXEC: u64 = 4;

pub struct Region { address: usize, length: usize }

impl Region {
    pub fn reserve(length: usize, exact: Option<usize>) -> Result<Self, i64> {
        if length == 0 { return Err(-22); }
        let flags = 0x22 | if exact.is_some() { 0x100000 } else { 0 }; // NOREPLACE
        let result = unsafe { syscall(nr::MMAP, [exact.unwrap_or(0) as u64,
            length as u64, 0, flags, u64::MAX, 0]) };
        if result.is_error() { return Err(result.0); }
        let region = Self { address: result.0 as usize, length };
        // Older kernels may ignore NOREPLACE. A different mapping is never usable.
        if region.address == 0 || exact.is_some_and(|want| want != region.address) {
            return Err(-17);
        }
        Ok(region)
    }
    pub fn address(&self) -> usize { self.address }
    pub fn length(&self) -> usize { self.length }

    fn check(&self, offset: usize, length: usize, protection: u64) -> Result<usize, i64> {
        if length == 0 || offset.checked_add(length).is_none_or(|end| end > self.length) ||
            protection & (WRITE | EXEC) == (WRITE | EXEC) || protection & !7 != 0 { return Err(-22); }
        self.address.checked_add(offset).ok_or(-22)
    }

    /// # Safety
    /// No live references or executing threads may touch the replaced pages.
    /// The offset/length and file offset must be aligned to the host page size.
    pub unsafe fn replace(&mut self, offset: usize, length: usize, protection: u64,
        file: Option<(&File, u64)>) -> Result<(), i64> {
        let address = self.check(offset, length, protection)?;
        let (flags, fd, file_offset) = match file {
            Some((file, offset)) => (0x12, file.fd(), offset),
            None => (0x32, u64::MAX, 0),
        };
        let result = unsafe { syscall(nr::MMAP, [address as u64, length as u64,
            protection, flags, fd, file_offset]) };
        if result.is_error() { return Err(result.0); }
        Ok(())
    }

    /// # Safety
    /// No live references/execution may require permissions removed by this call.
    pub unsafe fn protect(&mut self, offset: usize, length: usize, protection: u64) -> Result<(), i64> {
        let address = self.check(offset, length, protection)?;
        let result = unsafe { syscall(nr::MPROTECT, [address as u64, length as u64,
            protection, 0, 0, 0]) };
        if result.is_error() { return Err(result.0); }
        Ok(())
    }
}

impl Drop for Region {
    fn drop(&mut self) {
        unsafe { syscall(nr::MUNMAP, [self.address as u64, self.length as u64, 0, 0, 0, 0]); }
    }
}
