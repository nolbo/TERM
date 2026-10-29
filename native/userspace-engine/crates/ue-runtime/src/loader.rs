//! File-backed ELF loading for the standalone loader experiment. No VFS or exec
//! virtualization is implied: paths and subsequent syscalls still use the host.
use core::ffi::CStr;
use ue_core::{elf::{self, Executable}, stack};
use ue_core::identity::Credentials;
use ue_platform::{aarch64::synchronize_code, file::File,
    memory::{Region, READ, WRITE, EXEC}};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error { Host(i64), Elf(elf::Error), Stack(stack::Error), Input, NestedInterpreter }
impl From<i64> for Error { fn from(e: i64) -> Self { Self::Host(e) } }
impl From<elf::Error> for Error { fn from(e: elf::Error) -> Self { Self::Elf(e) } }
impl From<stack::Error> for Error { fn from(e: stack::Error) -> Self { Self::Stack(e) } }

struct Image {
    _mapping: Region,
    bias: u64,
    entry: u64,
    phdr: u64,
    phnum: u64,
}

fn map_image(file: &File, elf: &Executable<'_>, page: u64) -> Result<Image, Error> {
    let layout = elf.layout()?;
    let span = layout.end - layout.start;
    let mut region = if elf.position_independent {
        Region::reserve((span + layout.alignment) as usize, None)?
    } else {
        Region::reserve(span as usize, Some(layout.start as usize))?
    };
    let base = region.address() as u64;
    let delta = if elf.position_independent {
        (layout.start % layout.alignment + layout.alignment - base % layout.alignment) % layout.alignment
    } else { 0 };
    let bias = base.checked_add(delta).and_then(|start| start.checked_sub(layout.start)).ok_or(Error::Input)?;
    // Validate all relocated endpoints before mutating the reservation.
    bias.checked_add(layout.end).ok_or(Error::Input)?;
    for load in elf.segments() {
        if load.memory_size == 0 { continue; }
        let page_start = load.virtual_address & !(page - 1);
        let start = bias + page_start;
        let end = bias + elf::align_up(load.end()?, page)?;
        let offset = (start - base) as usize;
        let length = (end - start) as usize;
        let protection = if load.flags & 4 != 0 { READ } else { 0 } |
            if load.flags & 2 != 0 { WRITE } else { 0 } |
            if load.flags & 1 != 0 { EXEC } else { 0 };
        let zero_tail = load.file_size != 0 && load.memory_size > load.file_size;
        let initial = (protection & !EXEC) | if zero_tail { WRITE } else { 0 };
        let file_length = if load.file_size == 0 { 0 } else {
            elf::align_up(load.virtual_address - page_start + load.file_size, page)? as usize
        };
        if file_length != 0 {
            unsafe { region.replace(offset, file_length, initial,
                Some((file, load.file_offset & !(page - 1))))?; }
        }
        if file_length < length {
            unsafe { region.replace(offset + file_length, length - file_length, initial, None)?; }
        }
        if zero_tail {
            let zero_start = bias + load.virtual_address + load.file_size;
            let zero_end = start + file_length as u64;
            // Match zero-fill through the end of the file's last private page.
            // This cannot modify the source file (MAP_PRIVATE), and never uses RWX.
            unsafe {
                core::ptr::write_bytes(zero_start as *mut u8, 0, (zero_end - zero_start) as usize);
                if protection & EXEC != 0 && zero_end > zero_start {
                    synchronize_code(zero_start as usize, zero_end as usize);
                }
            }
        }
        unsafe { region.protect(offset, length, protection)?; }
    }
    Ok(Image { _mapping: region, bias, entry: bias + elf.entry,
        phdr: bias + layout.phdr, phnum: layout.phnum as u64 })
}

pub struct Program { main: Image, interpreter: Option<Image> }

impl Program {
    pub fn load(path: &CStr, page: u64) -> Result<Self, Error> {
        // Copy bounded metadata, not mutable file mappings exposed as Rust slices.
        // Header/PT_INTERP placement outside this prefix is an explicit current limit.
        let mut bytes = [0_u8; 65536];
        let file = File::open_executable(path)?;
        let length = file.prefix(&mut bytes)?;
        let elf = Executable::parse_prefix(&bytes[..length], file.length, page)?;
        let main = map_image(&file, &elf, page)?;
        let interpreter = if let Some(path) = elf.interpreter() {
            let mut name = [0_u8; 4096];
            name[..path.len()].copy_from_slice(path);
            let name = CStr::from_bytes_with_nul(&name[..path.len()+1]).map_err(|_| Error::Input)?;
            Some(load_interpreter(name, page)?)
        } else { None };
        Ok(Self { main, interpreter })
    }

    pub fn stack(&self, initial: &InitialStack<'_>, first_argument: usize,
        credentials: Credentials) -> Result<GuestStack, Error> {
        let page = initial.aux(6).ok_or(Error::Input)? as usize;
        if !page.is_power_of_two() || !(4096..=65536).contains(&page) ||
            initial.aux(23).unwrap_or(0) != 0 { return Err(Error::Input); }
        let mut region = Region::reserve(8 * 1024 * 1024 + page, None)?;
        let base = region.address() + page;
        let length = region.length() - page;
        unsafe { region.protect(page, length, READ | WRITE)?; }
        // This entire interval is our writable anonymous mapping; the bottom
        // guard page stays PROT_NONE. It has not been published to any other task.
        let bytes = unsafe { core::slice::from_raw_parts_mut(base as *mut u8, length) };
        let mut builder = stack::Builder::new(bytes, base as u64)?;
        let args = initial.args.get(first_argument..).ok_or(Error::Input)?;
        if args.is_empty() { return Err(Error::Input); }
        let mut argv = [0_u64; MAX_VECTOR];
        let mut envp = [0_u64; MAX_VECTOR];
        for (i, value) in args.iter().enumerate() {
            argv[i] = builder.push_string(unsafe { kernel_string(*value)? }.to_bytes())?;
        }
        for (i, value) in initial.env.iter().enumerate() {
            envp[i] = builder.push_string(unsafe { kernel_string(*value)? }.to_bytes())?;
        }
        let execfn = builder.push_string(initial.argument(first_argument)?.to_bytes())?;
        let random = initial.aux(25).filter(|address| *address != 0).ok_or(Error::Input)?;
        let random = builder.push_bytes(unsafe { core::slice::from_raw_parts(random as *const u8, 16) })?;
        let mut aux = [(0_u64, 0_u64); 32];
        let mut count = 0;
        for pair in [(3, self.main.phdr), (4, 56), (5, self.main.phnum), (6, page as u64),
            (7, self.interpreter.as_ref().map_or(0, |i| i.bias)), (8, 0),
            (9, self.main.entry), (11, credentials.uid as u64), (12, credentials.euid as u64),
            (13, credentials.gid as u64), (14, credentials.egid as u64),
            (23, 0), (25, random), (31, execfn)] {
            aux[count] = pair; count += 1;
        }
        // Only known scalar fields and the still-mapped vDSO address survive.
        // Pointer-valued strings are copied; unknown aux entries are not forwarded.
        for tag in [16, 17, 26, 27, 28, 33, 51] {
            if let Some(value) = initial.aux(tag) { aux[count] = (tag, value); count += 1; }
        }
        for tag in [15, 24] {
            if let Some(value) = initial.aux(tag).filter(|v| *v != 0) {
                let value = builder.push_string(unsafe { kernel_string(value as usize)? }.to_bytes())?;
                aux[count] = (tag, value); count += 1;
            }
        }
        let sp = builder.finish(&argv[..args.len()], &envp[..initial.env.len()], &aux[..count])?;
        Ok(GuestStack { _mapping: region, sp })
    }

    /// # Safety
    /// This replaces the calling thread's stack/TLS and never returns. No other
    /// threads may run, and the caller must accept execution of the loaded image.
    /// This diagnostic entry is NOT kernel exec or guest syscall virtualization.
    pub unsafe fn enter(self, stack: GuestStack) -> ! {
        let entry = self.interpreter.as_ref().map_or(self.main.entry, |i| i.entry);
        let sp = stack.sp;
        core::mem::forget(self);
        core::mem::forget(stack);
        unsafe { ue_enter_guest(entry, sp) }
    }
}

fn load_interpreter(path: &CStr, page: u64) -> Result<Image, Error> {
    let mut bytes = [0_u8; 65536];
    let file = File::open_executable(path)?;
    let length = file.prefix(&mut bytes)?;
    let elf = Executable::parse_prefix(&bytes[..length], file.length, page)?;
    if elf.interpreter().is_some() { return Err(Error::NestedInterpreter); }
    map_image(&file, &elf, page)
}

pub struct GuestStack { _mapping: Region, sp: u64 }
const MAX_VECTOR: usize = 4096;

/// Borrows only the original kernel-built startup vectors. Not a guest-memory API.
pub struct InitialStack<'a> { args: &'a [usize], env: &'a [usize], auxiliary: &'a [usize] }

impl<'a> InitialStack<'a> {
    /// # Safety
    /// `stack` must be this process's intact Linux initial SP. Its vectors, strings,
    /// and pointer-valued aux entries must remain valid for the returned lifetime.
    pub unsafe fn from_kernel(stack: *const usize) -> Result<Self, Error> {
        let argc = unsafe { *stack };
        if argc == 0 || argc > MAX_VECTOR || unsafe { *stack.add(argc+1) } != 0 { return Err(Error::Input); }
        let args = unsafe { core::slice::from_raw_parts(stack.add(1), argc) };
        let env_start = unsafe { stack.add(argc+2) };
        let mut envc = 0;
        while unsafe { *env_start.add(envc) } != 0 {
            if envc == MAX_VECTOR { return Err(Error::Input); }
            envc += 1;
        }
        let env = unsafe { core::slice::from_raw_parts(env_start, envc) };
        let auxiliary = unsafe { env_start.add(envc+1) };
        for pairs in 0..128 {
            if unsafe { *auxiliary.add(pairs*2) } == 0 {
                return Ok(Self { args, env, auxiliary: unsafe { core::slice::from_raw_parts(auxiliary, pairs*2) } });
            }
        }
        Err(Error::Input)
    }
    pub fn argument(&self, index: usize) -> Result<&CStr, Error> {
        unsafe { kernel_string(*self.args.get(index).ok_or(Error::Input)?) }
    }
    pub fn aux(&self, tag: u64) -> Option<u64> {
        self.auxiliary.chunks_exact(2).find(|p| p[0] as u64 == tag).map(|p| p[1] as u64)
    }
    pub fn credentials(&self) -> Result<Credentials, Error> {
        let value = |tag| u32::try_from(self.aux(tag).ok_or(Error::Input)?).map_err(|_| Error::Input);
        Ok(Credentials { uid: value(11)?, euid: value(12)?, gid: value(13)?, egid: value(14)? })
    }
}

unsafe fn kernel_string<'a>(address: usize) -> Result<&'a CStr, Error> {
    if address == 0 { return Err(Error::Input); }
    for length in 0..131072 {
        if unsafe { (address as *const u8).add(length).read() } == 0 {
            return CStr::from_bytes_with_nul(unsafe {
                core::slice::from_raw_parts(address as *const u8, length+1)
            }).map_err(|_| Error::Input);
        }
    }
    Err(Error::Input)
}

core::arch::global_asm!(include_str!("loader-entry.S"));
unsafe extern "C" { fn ue_enter_guest(entry: u64, sp: u64) -> !; }

#[cfg(test)]
mod tests {
    extern crate std;
    use super::*;
    use self::std::{ffi::CString, fs};

    fn counts() -> (usize, usize) {
        (fs::read_to_string("/proc/self/maps").unwrap().lines().count(),
            fs::read_dir("/proc/self/fd").unwrap().count())
    }

    #[test]
    fn owned_mappings_survive_collision_and_rollback_without_fd_or_mapping_leaks() {
        let auxiliary = fs::read("/proc/self/auxv").unwrap();
        let page = auxiliary.chunks_exact(16).find(|p|
            u64::from_ne_bytes(p[..8].try_into().unwrap()) == 6)
            .map(|p| u64::from_ne_bytes(p[8..].try_into().unwrap()) as usize).unwrap();
        let region = Region::reserve(page, None).unwrap();
        assert!(matches!(Region::reserve(page, Some(region.address())), Err(-17)));
        drop(region);
        let own = std::env::current_exe().unwrap();
        let path = CString::new(own.as_os_str().as_encoded_bytes()).unwrap();
        drop(Program::load(&path, page as u64).unwrap());
        // A missing interpreter fails after the main image has already mapped.
        let mut data = fs::read(&own).unwrap();
        let offset = u64::from_le_bytes(data[32..40].try_into().unwrap()) as usize;
        let count = u16::from_le_bytes(data[56..58].try_into().unwrap()) as usize;
        let interp = (0..count).map(|i| offset+i*56).find(|i|
            u32::from_le_bytes(data[*i..*i+4].try_into().unwrap()) == 3).unwrap();
        let start = u64::from_le_bytes(data[interp+8..interp+16].try_into().unwrap()) as usize;
        data[start..start+9].copy_from_slice(b"/missing\0");
        let temporary = std::env::temp_dir().join(std::format!("ue-load-rollback-{}", std::process::id()));
        {
            use self::std::{io::Write, os::unix::fs::OpenOptionsExt};
            let mut file = fs::OpenOptions::new().create_new(true).write(true).mode(0o700).open(&temporary).unwrap();
            file.write_all(&data).unwrap();
        }
        let broken = CString::new(temporary.as_os_str().as_encoded_bytes()).unwrap();
        let before = counts();
        for _ in 0..64 {
            drop(Program::load(&path, page as u64).unwrap());
            assert!(matches!(Program::load(&broken, page as u64), Err(Error::Host(-2))));
        }
        let after = counts();
        fs::remove_file(temporary).unwrap();
        assert_eq!(before, after, "load/drop and partial-load failure must release mappings and FDs");
    }
}
