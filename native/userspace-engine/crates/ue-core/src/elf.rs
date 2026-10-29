//! Bounds-checked ARM64 ELF executable inspection. Parsing is not executable mapping.
//! No host calls, allocator, borrowed native ELF structs, or unchecked file arithmetic.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    Truncated, Format, Architecture, Kind, ProgramHeaders, Segment,
    Alignment, PageCongruence, Interpreter, Entry, PageSize,
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
                    range(bytes, load.file_offset, load.file_size)?;
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
        Ok(Self { bytes, headers, entry, position_independent: kind == 3, interpreter })
    }

    pub fn interpreter(&self) -> Option<&'a [u8]> { self.interpreter }
    pub fn segments(&self) -> impl Iterator<Item = Segment> + '_ {
        self.headers.chunks_exact(56).filter(|header| u32_at(header, 0) == 1).map(segment)
    }
    pub fn file_bytes(&self, load: Segment) -> Result<&'a [u8], Error> {
        range(self.bytes, load.file_offset, load.file_size)
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
}
