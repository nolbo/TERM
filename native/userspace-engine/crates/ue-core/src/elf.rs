//! Bounds-checked ARM64 ELF executable inspection. Parsing is not executable mapping.
//! No host calls, allocator, borrowed native ELF structs, or unchecked file arithmetic.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    Truncated, Format, Architecture, Kind, ProgramHeaders, Segment,
    Alignment, PageCongruence, Interpreter, Entry, PageSize,
    OverlappingPages, UnsupportedProtection, ImageTooLarge, UnmappedHeaders,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Segment {
    pub flags: u32,
    pub file_offset: u64,
    pub virtual_address: u64,
    pub file_size: u64,
    pub memory_size: u64,
    pub alignment: u64,
}

impl Segment {
    pub fn end(self) -> Result<u64, Error> {
        self.virtual_address.checked_add(self.memory_size).ok_or(Error::Segment)
    }
}

#[derive(Debug)]
pub struct Executable<'a> {
    bytes: &'a [u8],
    headers: &'a [u8],
    pub entry: u64,
    pub position_independent: bool,
    interpreter: Option<&'a [u8]>,
    page_size: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Layout {
    pub start: u64,
    pub end: u64,
    pub alignment: u64,
    pub phdr: u64,
    pub phnum: u16,
}

pub fn align_up(value: u64, alignment: u64) -> Result<u64, Error> {
    if !alignment.is_power_of_two() { return Err(Error::Alignment); }
    value.checked_add(alignment - 1).map(|n| n & !(alignment - 1)).ok_or(Error::Segment)
}

fn u16_at(bytes: &[u8], offset: usize) -> u16 { u16::from_le_bytes([bytes[offset], bytes[offset + 1]]) }
fn u32_at(bytes: &[u8], offset: usize) -> u32 { u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap()) }
fn u64_at(bytes: &[u8], offset: usize) -> u64 { u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap()) }

fn range(bytes: &[u8], offset: u64, length: u64) -> Result<&[u8], Error> {
    let end = offset.checked_add(length).ok_or(Error::Truncated)?;
    let start = usize::try_from(offset).map_err(|_| Error::Truncated)?;
    let end = usize::try_from(end).map_err(|_| Error::Truncated)?;
    bytes.get(start..end).ok_or(Error::Truncated)
}

fn segment(header: &[u8]) -> Segment {
    Segment { flags: u32_at(header, 4), file_offset: u64_at(header, 8),
        virtual_address: u64_at(header, 16), file_size: u64_at(header, 32),
        memory_size: u64_at(header, 40), alignment: u64_at(header, 48) }
}

impl<'a> Executable<'a> {
    pub fn parse(bytes: &'a [u8], page_size: u64) -> Result<Self, Error> {
        Self::parse_prefix(bytes, bytes.len() as u64, page_size)
    }

    /// Inspect a copied metadata prefix without mapping a mutable file as a Rust
    /// slice. Program headers and PT_INTERP must fit in the supplied prefix;
    /// segment file bounds are checked against the independently obtained length.
    pub fn parse_prefix(bytes: &'a [u8], file_length: u64, page_size: u64) -> Result<Self, Error> {
        if bytes.len() as u64 > file_length { return Err(Error::Truncated); }
        if !page_size.is_power_of_two() || !(4096..=65536).contains(&page_size) { return Err(Error::PageSize); }
        if bytes.len() < 64 { return Err(Error::Truncated); }
        if &bytes[..7] != b"\x7fELF\x02\x01\x01" || u32_at(bytes, 20) != 1 || u16_at(bytes, 52) != 64 { return Err(Error::Format); }
        if u16_at(bytes, 18) != 183 { return Err(Error::Architecture); }
        let kind = u16_at(bytes, 16);
        if !matches!(kind, 2 | 3) { return Err(Error::Kind); }
        let count = u16_at(bytes, 56);
        if count == 0 || count > 256 || u16_at(bytes, 54) != 56 { return Err(Error::ProgramHeaders); }
        let headers = range(bytes, u64_at(bytes, 32), u64::from(count) * 56)?;
        let entry = u64_at(bytes, 24);
        let mut executable_entry = false;
        let mut interpreter = None;
        for header in headers.chunks_exact(56) {
            match u32_at(header, 0) {
                1 => {
                    let load = segment(header);
                    if load.file_size > load.memory_size { return Err(Error::Segment); }
                    if load.file_offset.checked_add(load.file_size).ok_or(Error::Truncated)? > file_length {
                        return Err(Error::Truncated);
                    }
                    let end = load.end()?;
                    if load.alignment > 1 && (!load.alignment.is_power_of_two() ||
                        load.virtual_address % load.alignment != load.file_offset % load.alignment) { return Err(Error::Alignment); }
                    if load.virtual_address % page_size != load.file_offset % page_size { return Err(Error::PageCongruence); }
                    if load.flags & 1 != 0 && entry >= load.virtual_address && entry < end { executable_entry = true; }
                }
                3 => {
                    if interpreter.is_some() { return Err(Error::Interpreter); }
                    let size = u64_at(header, 32);
                    if !(2..=4096).contains(&size) { return Err(Error::Interpreter); }
                    let path = range(bytes, u64_at(header, 8), size)?;
                    let length = path.iter().position(|byte| *byte == 0).ok_or(Error::Interpreter)?;
                    if length == 0 { return Err(Error::Interpreter); }
                    interpreter = Some(&path[..length]);
                }
                _ => {}
            }
        }
        if !executable_entry { return Err(Error::Entry); }
        Ok(Self { bytes, headers, entry, position_independent: kind == 3, interpreter, page_size })
    }

    pub fn interpreter(&self) -> Option<&'a [u8]> { self.interpreter }
    pub fn segments(&self) -> impl Iterator<Item = Segment> + '_ {
        self.headers.chunks_exact(56).filter(|header| u32_at(header, 0) == 1).map(segment)
    }
    pub fn file_bytes(&self, load: Segment) -> Result<&'a [u8], Error> {
        range(self.bytes, load.file_offset, load.file_size)
    }

    /// Build a bounded, non-overlapping reservation plan before any host mutation.
    /// Shared PT_LOAD pages and executable stacks require a later mapping policy;
    /// they are rejected explicitly rather than silently changing permissions.
    pub fn layout(&self) -> Result<Layout, Error> {
        let mut start = u64::MAX;
        let mut end = 0;
        let mut alignment = self.page_size;
        let phoff = u64_at(self.bytes, 32);
        let phend = phoff.checked_add(self.headers.len() as u64).ok_or(Error::ProgramHeaders)?;
        let mut phdr = None;
        for (index, load) in self.segments().enumerate() {
            if load.memory_size == 0 { continue; }
            if load.flags & 3 == 3 { return Err(Error::UnsupportedProtection); }
            let lo = load.virtual_address & !(self.page_size - 1);
            let hi = align_up(load.end()?, self.page_size)?;
            for other in self.segments().take(index) {
                if other.memory_size == 0 { continue; }
                let other_lo = other.virtual_address & !(self.page_size - 1);
                let other_hi = align_up(other.end()?, self.page_size)?;
                if lo < other_hi && other_lo < hi { return Err(Error::OverlappingPages); }
            }
            start = start.min(lo);
            end = end.max(hi);
            alignment = alignment.max(load.alignment);
            let file_end = load.file_offset.checked_add(load.file_size).ok_or(Error::Segment)?;
            if load.flags & 4 != 0 && phoff >= load.file_offset && phend <= file_end {
                phdr = Some(load.virtual_address.checked_add(phoff - load.file_offset).ok_or(Error::Segment)?);
            }
        }
        if start >= end || end - start > (1 << 40) || alignment > (1 << 30) { return Err(Error::ImageTooLarge); }
        for header in self.headers.chunks_exact(56) {
            if u32_at(header, 0) == 0x6474e551 && u32_at(header, 4) & 1 != 0 {
                return Err(Error::UnsupportedProtection);
            }
        }
        Ok(Layout { start, end, alignment, phdr: phdr.ok_or(Error::UnmappedHeaders)?,
            phnum: (self.headers.len() / 56) as u16 })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> [u8; 512] {
        let mut bytes = [0; 512];
        bytes[..7].copy_from_slice(b"\x7fELF\x02\x01\x01");
        bytes[16..18].copy_from_slice(&3_u16.to_le_bytes());
        bytes[18..20].copy_from_slice(&183_u16.to_le_bytes());
        bytes[20..24].copy_from_slice(&1_u32.to_le_bytes());
        bytes[24..32].copy_from_slice(&256_u64.to_le_bytes());
        bytes[32..40].copy_from_slice(&64_u64.to_le_bytes());
        bytes[52..54].copy_from_slice(&64_u16.to_le_bytes());
        bytes[54..56].copy_from_slice(&56_u16.to_le_bytes());
        bytes[56..58].copy_from_slice(&1_u16.to_le_bytes());
        bytes[64..68].copy_from_slice(&1_u32.to_le_bytes());
        bytes[68..72].copy_from_slice(&5_u32.to_le_bytes());
        bytes[96..104].copy_from_slice(&512_u64.to_le_bytes());
        bytes[104..112].copy_from_slice(&1024_u64.to_le_bytes());
        bytes[112..120].copy_from_slice(&4096_u64.to_le_bytes());
        bytes
    }

    #[test]
    fn parses_pie_and_bss_without_allocating() {
        let bytes = fixture();
        for page in [4096, 16384, 65536] {
            let image = Executable::parse(&bytes, page).unwrap();
            assert!(image.position_independent);
            assert_eq!(image.entry, 256);
            let load = image.segments().next().unwrap();
            assert_eq!(load.memory_size, 1024);
            assert_eq!(image.file_bytes(load).unwrap().len(), 512);
            assert_eq!(image.interpreter(), None);
        }
    }

    #[test]
    fn rejects_every_truncation_before_segment_end() {
        let bytes = fixture();
        for end in 0..bytes.len() { assert!(Executable::parse(&bytes[..end], 4096).is_err(), "accepted {end}"); }
    }

    #[test]
    fn rejects_overflow_and_unsupported_header_values() {
        for (offset, value) in [(32, u64::MAX), (72, u64::MAX), (80, u64::MAX), (96, 1025), (104, u64::MAX), (112, 3)] {
            let mut bytes = fixture();
            bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
            if offset == 104 { bytes[80..88].copy_from_slice(&4096_u64.to_le_bytes()); }
            assert!(Executable::parse(&bytes, 4096).is_err(), "offset {offset}");
        }
        let mut bytes = fixture();
        bytes[18] = 62;
        assert_eq!(Executable::parse(&bytes, 4096).unwrap_err(), Error::Architecture);
        assert_eq!(Executable::parse(&fixture(), 8193).unwrap_err(), Error::PageSize);
    }

    #[test]
    fn distinguishes_16k_congruence_from_4k_alignment() {
        let mut bytes = fixture();
        bytes[80..88].copy_from_slice(&4096_u64.to_le_bytes());
        bytes[24..32].copy_from_slice(&4352_u64.to_le_bytes());
        assert!(Executable::parse(&bytes, 4096).is_ok());
        assert_eq!(Executable::parse(&bytes, 16384).unwrap_err(), Error::PageCongruence);
    }

    #[test]
    fn interpreter_is_bounded_and_requires_a_terminator() {
        let mut bytes = fixture();
        bytes[56..58].copy_from_slice(&2_u16.to_le_bytes());
        bytes[120..124].copy_from_slice(&3_u32.to_le_bytes());
        bytes[128..136].copy_from_slice(&300_u64.to_le_bytes());
        bytes[152..160].copy_from_slice(&4_u64.to_le_bytes());
        bytes[300..304].copy_from_slice(b"/ld\0");
        assert_eq!(Executable::parse(&bytes, 4096).unwrap().interpreter(), Some(&b"/ld"[..]));
        bytes[303] = b'x';
        assert_eq!(Executable::parse(&bytes, 4096).unwrap_err(), Error::Interpreter);
        bytes[152..160].copy_from_slice(&4097_u64.to_le_bytes());
        assert_eq!(Executable::parse(&bytes, 4096).unwrap_err(), Error::Interpreter);
    }

    #[test]
    fn prefix_inspection_uses_real_file_length_and_derives_phdr() {
        let bytes = fixture();
        let image = Executable::parse_prefix(&bytes[..120], 512, 4096).unwrap();
        assert_eq!(image.layout().unwrap(), Layout { start: 0, end: 4096, alignment: 4096, phdr: 64, phnum: 1 });
        assert!(Executable::parse_prefix(&bytes[..119], 512, 4096).is_err());
        assert!(Executable::parse_prefix(&bytes[..120], 511, 4096).is_err());
    }

    #[test]
    fn planning_rejects_shared_pages_and_wx_before_mapping() {
        let mut bytes = fixture();
        bytes[56..58].copy_from_slice(&2_u16.to_le_bytes());
        let first: [u8; 56] = bytes[64..120].try_into().unwrap();
        bytes[120..176].copy_from_slice(&first);
        assert_eq!(Executable::parse(&bytes, 4096).unwrap().layout(), Err(Error::OverlappingPages));
        bytes[56..58].copy_from_slice(&1_u16.to_le_bytes());
        bytes[68..72].copy_from_slice(&7_u32.to_le_bytes());
        assert_eq!(Executable::parse(&bytes, 4096).unwrap().layout(), Err(Error::UnsupportedProtection));
        bytes[68..72].copy_from_slice(&1_u32.to_le_bytes());
        assert_eq!(Executable::parse(&bytes, 4096).unwrap().layout(), Err(Error::UnmappedHeaders));
        assert_eq!(align_up(u64::MAX, 4096), Err(Error::Segment));
    }
}
