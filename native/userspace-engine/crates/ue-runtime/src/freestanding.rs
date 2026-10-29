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
