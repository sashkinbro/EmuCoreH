//! Test-only AArch64 subset simulator: executes the straight-line code the block compiler emits
//! on an x86 host, so translations can be checked against the interpreter without a device.
//!
//! It decodes exactly the instruction forms `a64::Asm` produces for register-only code (moves,
//! add/sub immediate and shifted register with flags, logical register and bitmask immediate,
//! bitfield moves, `EXTR`, `REV16`, `CSEL/CSINC`, `MADD`, unsigned-offset loads/stores, `RET`) and
//! panics on anything else, including memory accesses outside the window it was given.

use std::ptr;

pub(crate) struct Sim {
    x: [u64; 32],
    sp: u64,
    _stack: Vec<u64>,
    n: bool,
    z: bool,
    c: bool,
    v: bool,
}

fn sext(value: u64, bits: u32) -> i64 {
    ((value << (64 - bits)) as i64) >> (64 - bits)
}

fn decode_bitmask(n: u32, immr: u32, imms: u32, width: u32) -> u64 {
    let len = 31 - ((n << 6) | (!imms & 0x3F)).leading_zeros();
    let esize = 1u32 << len;
    let s = imms & (esize - 1);
    let r = immr & (esize - 1);
    let emask = if esize == 64 { u64::MAX } else { (1u64 << esize) - 1 };
    let elem = if s + 1 == 64 { u64::MAX } else { (1u64 << (s + 1)) - 1 };
    let rotated = if r == 0 { elem } else { ((elem >> r) | (elem << (esize - r))) & emask };
    let mut v = 0u64;
    let mut i = 0;
    while i < width {
        v |= rotated << i;
        i += esize;
    }
    v
}

impl Sim {
    pub(crate) fn set_x(&mut self, r: usize, value: u64) {
        self.x[r] = value;
    }

    pub(crate) fn new() -> Self {
        let stack = vec![0u64; 256];
        let sp = stack.as_ptr() as u64 + 256 * 8;
        Self { x: [0; 32], sp, _stack: stack, n: false, z: false, c: false, v: false }
    }

    fn reg(&self, r: u32, sf: bool) -> u64 {
        if r == 31 {
            return 0;
        }
        if sf { self.x[r as usize] } else { self.x[r as usize] & 0xFFFF_FFFF }
    }

    fn set(&mut self, r: u32, value: u64, sf: bool) {
        if r != 31 {
            self.x[r as usize] = if sf { value } else { value & 0xFFFF_FFFF };
        }
    }

    fn cond(&self, cond: u32) -> bool {
        let result = match cond >> 1 {
            0 => self.z,
            1 => self.c,
            2 => self.n,
            3 => self.v,
            4 => self.c && !self.z,
            5 => self.n == self.v,
            6 => self.n == self.v && !self.z,
            _ => true,
        };
        if cond & 1 == 1 && cond != 15 { !result } else { result }
    }

    fn add_with_flags(&mut self, a: u64, b: u64, carry: bool, sf: bool, set_flags: bool) -> u64 {
        let bits = if sf { 64 } else { 32 };
        let mask = if sf { u64::MAX } else { 0xFFFF_FFFF };
        let (a, b) = (a & mask, b & mask);
        let wide = u128::from(a) + u128::from(b) + u128::from(carry);
        let result = (wide as u64) & mask;
        if set_flags {
            self.n = (result >> (bits - 1)) & 1 == 1;
            self.z = result == 0;
            self.c = wide >> bits != 0;
            let (sa, sb, sr) = (sext(a, bits), sext(b, bits), sext(result, bits));
            self.v = (sa < 0) == (sb < 0) && (sr < 0) != (sa < 0);
        }
        result
    }

    /// Runs `code` until `RET` with `x19` pointing at `window` (`len` bytes, the only memory the
    /// code may access)
    ///
    /// # Safety
    /// `window..window + len` must be valid for reads and writes of any byte pattern the code
    /// stores (the caller passes plain-data structs whose fields it then compares).
    pub(crate) unsafe fn run(&mut self, code: &[u32], window: *mut u8, len: usize) {
        self.x[19] = window as u64;
        unsafe {
            self.exec(code.as_ptr(), Some((window as usize, len)), &mut |_, _, _| panic!("no calls"));
        }
    }

    /// Calls generated code with `x0 = arg` (AAPCS64, raw memory access) and returns `x0`.
    /// `BLR` calls `host(target, x0, x1)` instead of jumping.
    ///
    /// # Safety
    /// The code may access any memory; the caller vouches for every address it computes.
    pub(crate) unsafe fn call(&mut self, code: *const u32, arg: u64, host: &mut dyn FnMut(u64, u64, u64) -> u64) -> u64 {
        self.x[0] = arg;
        unsafe { self.exec(code, None, host) }
    }

    unsafe fn exec(&mut self, code: *const u32, window: Option<(usize, usize)>, host: &mut dyn FnMut(u64, u64, u64) -> u64) -> u64 {
        let (window, len) = window.unwrap_or((0, usize::MAX));
        let window = window as *mut u8;
        let raw = len == usize::MAX;
        let mut pc = 0isize;
        let mut base = code;
        loop {
            // SAFETY: generated code is contiguous and ends in RET on every path
            let word = unsafe { *base.offset(pc) };
            pc += 1;
            let at = pc - 1;
            let imm19 = (((word >> 5) & 0x7FFFF) as i32) << 13 >> 13;
            let sf = word >> 31 == 1;
            let rd = word & 0x1F;
            let rn = (word >> 5) & 0x1F;
            let rm = (word >> 16) & 0x1F;
            let bits = if sf { 64 } else { 32 };

            if word == 0xD65F_03C0 {
                return self.x[0];
            } else if word & 0xFC00_0000 == 0x1400_0000 {
                // B
                pc = at + ((((word & 0x03FF_FFFF) as i32) << 6 >> 6) as isize);
            } else if word & 0xFF00_0010 == 0x5400_0000 {
                // B.cond
                if self.cond(word & 0xF) {
                    pc = at + imm19 as isize;
                }
            } else if word & 0x7E00_0000 == 0x3400_0000 {
                // CBZ / CBNZ
                let zero = self.reg(rd, sf) == 0;
                if zero != ((word >> 24) & 1 == 1) {
                    pc = at + imm19 as isize;
                }
            } else if word & 0x7E00_0000 == 0x3600_0000 {
                // TBZ / TBNZ
                let bit = ((word >> 31) << 5) | ((word >> 19) & 0x1F);
                let set = (self.reg(rd, true) >> bit) & 1 == 1;
                if set == ((word >> 24) & 1 == 1) {
                    pc = at + (((((word >> 5) & 0x3FFF) as i32) << 18 >> 18) as isize);
                }
            } else if word & 0x7FE0_FC00 == 0x1AC0_2400 {
                // LSRV
                let amount = self.reg(rm, sf) % u64::from(bits);
                self.set(rd, self.reg(rn, sf) >> amount, sf);
            } else if word & 0x3B20_0C00 == 0x3820_0800 {
                // LDR/STR (register offset, LSL #0 or #size)
                let size = word >> 30;
                let shift = if (word >> 12) & 1 == 1 { size } else { 0 };
                let address = (self.x[rn as usize].wrapping_add(self.reg(rm, true) << shift)) as usize;
                // SAFETY: as for unsigned-offset accesses (raw mode: the caller vouches)
                unsafe {
                    let p = address as *mut u8;
                    if (word >> 22) & 1 == 1 {
                        let value = match size {
                            0 => u64::from(ptr::read_unaligned(p)),
                            1 => u64::from(ptr::read_unaligned(p.cast::<u16>())),
                            2 => u64::from(ptr::read_unaligned(p.cast::<u32>())),
                            _ => ptr::read_unaligned(p.cast::<u64>()),
                        };
                        self.set(rd, value, true);
                    } else {
                        let value = self.reg(rd, true);
                        match size {
                            0 => ptr::write_unaligned(p, value as u8),
                            1 => ptr::write_unaligned(p.cast::<u16>(), value as u16),
                            2 => ptr::write_unaligned(p.cast::<u32>(), value as u32),
                            _ => ptr::write_unaligned(p.cast::<u64>(), value),
                        }
                    }
                }
            } else if word & 0xFFFF_FC1F == 0xD61F_0000 {
                // BR: continue in other generated code
                base = self.reg(rn, true) as *const u32;
                pc = 0;
            } else if word & 0xFFFF_FC1F == 0xD63F_0000 {
                // BLR: host call
                let target = self.reg(rn, true);
                let result = host(target, self.x[0], self.x[1]);
                // Caller-saved registers are clobbered
                for r in 0..19 {
                    self.x[r] = 0xDEAD_0000_0000_0000 | r as u64;
                }
                self.x[0] = result;
            } else if (0x2A2..=0x2A7).contains(&(word >> 22)) {
                // STP/LDP 64-bit: post (A8), signed offset (A9 bit 23 = 0), pre (A9 bit 23 = 1)
                let load = (word >> 22) & 1 == 1;
                let imm = ((((word >> 15) & 0x7F) as i32) << 25 >> 25) as i64 * 8;
                let rt2 = (word >> 10) & 0x1F;
                let mode = (word >> 23) & 3; // 1 post, 2 offset, 3 pre
                let base = if rn == 31 { self.sp } else { self.x[rn as usize] };
                let address = if mode == 1 { base } else { base.wrapping_add(imm as u64) };
                unsafe {
                    let p = address as *mut u64;
                    if load {
                        let (a, b) = (p.read_unaligned(), p.add(1).read_unaligned());
                        self.set(rd, a, true);
                        self.set(rt2, b, true);
                    } else {
                        p.write_unaligned(self.reg(rd, true));
                        p.add(1).write_unaligned(self.reg(rt2, true));
                    }
                }
                if mode != 2 {
                    let updated = base.wrapping_add(imm as u64);
                    if rn == 31 { self.sp = updated } else { self.x[rn as usize] = updated }
                }
            } else if word & 0x1F80_0000 == 0x1280_0000 {
                // MOVN / MOVZ / MOVK
                let shift = ((word >> 21) & 3) * 16;
                let imm = u64::from((word >> 5) & 0xFFFF) << shift;
                let value = match (word >> 29) & 3 {
                    0 => !imm,
                    2 => imm,
                    3 => (self.reg(rd, true) & !(0xFFFFu64 << shift)) | imm,
                    _ => panic!("unsupported move {word:08x}"),
                };
                self.set(rd, value, sf);
            } else if word & 0x1F00_0000 == 0x1100_0000 {
                // ADD/SUB (immediate); rn = 31 is SP, unused by the translator
                let imm = u64::from((word >> 10) & 0xFFF) << if (word >> 22) & 1 == 1 { 12 } else { 0 };
                let a = self.reg(rn, sf);
                let sub = (word >> 30) & 1 == 1;
                let flags = (word >> 29) & 1 == 1;
                let result = if sub {
                    self.add_with_flags(a, !imm, true, sf, flags)
                } else {
                    self.add_with_flags(a, imm, false, sf, flags)
                };
                self.set(rd, result, sf);
            } else if word & 0x1F20_0000 == 0x0B00_0000 {
                // ADD/SUB (shifted register)
                let amount = (word >> 10) & 0x3F;
                let b = self.reg(rm, sf);
                let b = match (word >> 22) & 3 {
                    0 => b << amount,
                    1 => b >> amount,
                    2 => (sext(b, bits) >> amount) as u64,
                    _ => panic!("bad shift"),
                };
                let a = self.reg(rn, sf);
                let sub = (word >> 30) & 1 == 1;
                let flags = (word >> 29) & 1 == 1;
                let result = if sub {
                    self.add_with_flags(a, !b, true, sf, flags)
                } else {
                    self.add_with_flags(a, b, false, sf, flags)
                };
                self.set(rd, result, sf);
            } else if word & 0x1FE0_0000 == 0x1A00_0000 {
                // ADC / ADCS / SBC / SBCS
                let a = self.reg(rn, sf);
                let b = self.reg(rm, sf);
                let sub = (word >> 30) & 1 == 1;
                let flags = (word >> 29) & 1 == 1;
                let carry = self.c;
                let result = self.add_with_flags(a, if sub { !b } else { b }, carry, sf, flags);
                self.set(rd, result, sf);
            } else if word & 0x1F00_0000 == 0x0A00_0000 {
                // Logical (shifted register), shift amount 0 only
                assert_eq!((word >> 10) & 0x3F, 0, "logical shift unsupported");
                let mut b = self.reg(rm, sf);
                if (word >> 21) & 1 == 1 {
                    b = !b;
                }
                let a = self.reg(rn, sf);
                let mask = if sf { u64::MAX } else { 0xFFFF_FFFF };
                let result = match (word >> 29) & 3 {
                    0 | 3 => a & b,
                    1 => a | b,
                    _ => a ^ b,
                } & mask;
                if (word >> 29) & 3 == 3 {
                    self.n = (result >> (bits - 1)) & 1 == 1;
                    self.z = result == 0;
                    self.c = false;
                    self.v = false;
                }
                self.set(rd, result, sf);
            } else if word & 0x1F80_0000 == 0x1200_0000 {
                // Logical (immediate)
                let imm = decode_bitmask((word >> 22) & 1, (word >> 16) & 0x3F, (word >> 10) & 0x3F, bits);
                let a = self.reg(rn, sf);
                let mask = if sf { u64::MAX } else { 0xFFFF_FFFF };
                let result = match (word >> 29) & 3 {
                    0 | 3 => a & imm,
                    1 => a | imm,
                    _ => a ^ imm,
                } & mask;
                if (word >> 29) & 3 == 3 {
                    self.n = (result >> (bits - 1)) & 1 == 1;
                    self.z = result == 0;
                    self.c = false;
                    self.v = false;
                }
                self.set(rd, result, sf);
            } else if word & 0x1F80_0000 == 0x1300_0000 {
                // SBFM / BFM / UBFM
                let immr = (word >> 16) & 0x3F;
                let imms = (word >> 10) & 0x3F;
                let src = self.reg(rn, sf);
                let mask = if sf { u64::MAX } else { 0xFFFF_FFFF };
                let rot = if immr == 0 { src } else { ((src >> immr) | (src << (bits - immr))) & mask };
                let (wmask, tmask) = if imms >= immr {
                    // Extract field src[imms:immr] to the bottom
                    let width = imms - immr + 1;
                    (if width == 64 { u64::MAX } else { (1u64 << width) - 1 }, width)
                } else {
                    // Insert src[imms:0] at bit (bits - immr)
                    (0, 0)
                };
                let result = if imms >= immr {
                    let field = rot & wmask;
                    match (word >> 29) & 3 {
                        0 => (sext(field, tmask) as u64) & mask,
                        1 => (self.reg(rd, sf) & !wmask) | field,
                        _ => field,
                    }
                } else {
                    let width = imms + 1;
                    let field = src & ((1u64 << width) - 1);
                    let pos = bits - immr;
                    let placed = (field << pos) & mask;
                    let fmask = (((1u64 << width) - 1) << pos) & mask;
                    match (word >> 29) & 3 {
                        0 => {
                            let signed = (sext(field, width) as u64) << pos;
                            signed & mask
                        }
                        1 => (self.reg(rd, sf) & !fmask) | placed,
                        _ => placed,
                    }
                };
                self.set(rd, result, sf);
            } else if word & 0x1FA0_0000 == 0x1380_0000 {
                // EXTR: (Rn:Rm) >> lsb
                let lsb = (word >> 10) & 0x3F;
                let hi = u128::from(self.reg(rn, sf));
                let lo = u128::from(self.reg(rm, sf));
                let joined = (hi << bits) | lo;
                self.set(rd, (joined >> lsb) as u64, sf);
            } else if word & 0x7FFF_FC00 == 0x5AC0_0400 {
                // REV16
                let v = self.reg(rn, sf);
                let swapped = ((v & 0x00FF_00FF_00FF_00FF) << 8) | ((v >> 8) & 0x00FF_00FF_00FF_00FF);
                self.set(rd, swapped, sf);
            } else if word & 0x1FE0_0000 == 0x1A80_0000 {
                // CSEL / CSINC
                let cond = (word >> 12) & 0xF;
                let value = if self.cond(cond) {
                    self.reg(rn, sf)
                } else {
                    let m = self.reg(rm, sf);
                    if (word >> 10) & 3 == 1 { m.wrapping_add(1) } else { m }
                };
                self.set(rd, value, sf);
            } else if word & 0x7FE0_8000 == 0x1B00_0000 {
                // MADD
                let ra = (word >> 10) & 0x1F;
                let value = self.reg(rn, sf).wrapping_mul(self.reg(rm, sf)).wrapping_add(self.reg(ra, sf));
                self.set(rd, value, sf);
            } else if word & 0x3B00_0000 == 0x3900_0000 {
                // LDR/STR (unsigned offset): size in bits 30-31, load if bit 22
                let size = word >> 30;
                let offset = (((word >> 10) & 0xFFF) as usize) << size;
                let base = self.x[rn as usize] as usize;
                let start = window as usize;
                let bytes = 1usize << size;
                assert!(
                    raw || (base + offset >= start && base + offset + bytes <= start + len),
                    "access outside the window: {:x}+{offset:x}",
                    base
                );
                // SAFETY: inside the window (checked above), unaligned accesses are allowed
                unsafe {
                    let address = (base + offset) as *mut u8;
                    if (word >> 22) & 1 == 1 {
                        let value = match bytes {
                            1 => u64::from(ptr::read_unaligned(address)),
                            2 => u64::from(ptr::read_unaligned(address.cast::<u16>())),
                            4 => u64::from(ptr::read_unaligned(address.cast::<u32>())),
                            _ => ptr::read_unaligned(address.cast::<u64>()),
                        };
                        self.set(rd, value, true);
                    } else {
                        let value = self.reg(rd, true);
                        match bytes {
                            1 => ptr::write_unaligned(address, value as u8),
                            2 => ptr::write_unaligned(address.cast::<u16>(), value as u16),
                            4 => ptr::write_unaligned(address.cast::<u32>(), value as u32),
                            _ => ptr::write_unaligned(address.cast::<u64>(), value),
                        }
                    }
                }
            } else {
                panic!("simulator: unsupported instruction {word:08x} at {at}");
            }
        }
    }
}
