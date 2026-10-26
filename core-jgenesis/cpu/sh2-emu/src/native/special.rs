//! Native multiply/divide and special-register bodies; only the explicit Context ABI
//! is embedded in instructions, never the layout of Sh2 or its Rust status register.
fn ptr(offset: u32, reg: u32) -> u32 {
    0xF94000C0 | ((offset / 8) << 10) | reg
}
fn load(offset: u32, reg: u32, byte: bool) -> [u32; 2] {
    [ptr(offset, 14), (if byte { 0x394001C0 } else { 0xB94001C0 }) | reg]
}
fn store(offset: u32, reg: u32, byte: bool) -> [u32; 2] {
    [ptr(offset, 14), (if byte { 0x390001C0 } else { 0xB90001C0 }) | reg]
}
fn cset(c: u32, r: u32) -> u32 {
    0x1A9F07E0 | ((c ^ 1) << 12) | r
}
pub(super) fn sr_read(c: &mut Vec<u32>) {
    c.extend(load(8, 1, true));
    for (offset, shift) in [(120, 1), (128, 4), (112, 8), (104, 9)] {
        c.extend(load(offset, 2, true));
        c.push(0x2A020021 | (shift << 10));
    }
}
pub(super) fn sr_write(c: &mut Vec<u32>) {
    for (offset, shift, bits) in [(8, 0, 1), (120, 1, 1), (128, 4, 4), (112, 8, 1), (104, 9, 1)] {
        c.push(0x53000022 | (shift << 16) | ((shift + bits - 1) << 10));
        c.extend(store(offset, 2, true));
    }
}
pub(super) fn emit(op: u16) -> Option<Vec<u32>> {
    let n = u32::from((op >> 8) & 15);
    let m = u32::from((op >> 4) & 15);
    let mut c = vec![0xAA0003E6, 0xF9400000]; // x6 context, x0 registers
    let gpr = |r: u32, s: u32| 0xB9400000 | (r << 10) | s;
    match op & 0xF00F {
        0x0007 | 0x200E | 0x200F | 0x3005 | 0x300D => {
            c.extend([gpr(n, 1), gpr(m, 2)]);
            match op & 0xF00F {
                0x200E => c.extend([0x53003C21, 0x53003C42, 0x1B027C23]),
                0x200F => c.extend([0x13003C21, 0x13003C42, 0x1B027C23]),
                0x3005 => c.push(0x9BA27C23),
                0x300D => c.push(0x9B227C23),
                _ => c.push(0x1B027C23),
            }
            c.extend(store(96, 3, false));
            if op & 0xF000 == 0x3000 {
                c.push(0xD360FC63);
                c.extend(store(88, 3, false));
            }
        }
        0x200C => {
            // Any equal byte in XOR, using the standard zero-byte detector.
            c.extend([
                gpr(n, 1),
                gpr(m, 2),
                0x4A020021,
                0x52802022,
                0x72A02022,
                0x4B020022,
                0x0A210042,
                0x52901003,
                0x72B01003,
                0x6A03005F,
                cset(1, 3),
            ]);
            c.extend(store(8, 3, true));
        }
        0x2007 => {
            c.extend([gpr(n, 1), gpr(m, 2), 0x531F7C21, 0x531F7C42]);
            c.extend(store(112, 1, true));
            c.extend(store(104, 2, true));
            c.push(0x4A020023);
            c.extend(store(8, 3, true));
        }
        0x3004 => {
            c.extend([gpr(n, 1), gpr(m, 2), 0x531F7C28]); // previous sign -> w8
            c.extend(load(8, 3, true));
            c.extend(load(112, 4, true));
            c.extend(load(104, 7, true));
            c.extend([
                0x531F7821,
                0x2A030021,
                0x6B020029,
                cset(3, 11),
                0x2B02002A,
                cset(2, 12),
                0x6B07009F,
                0x1A8A0121,
                0x1A8C016B,
                0x4A080163,
                0x4A070063,
            ]);
            c.extend(store(112, 3, true));
            c.extend([0x6B07007F, cset(0, 3)]);
            c.extend(store(8, 3, true));
            c.push(0xB9000001 | (n << 10));
        }
        _ => match op & 0xF0FF {
            0x0002 => {
                sr_read(&mut c);
                c.push(0xB9000001 | (n << 10));
            }
            0x0012 | 0x0022 | 0x000A | 0x001A | 0x002A => {
                c.extend(load(
                    match op & 255 {
                        0x12 => 48,
                        0x22 => 136,
                        0xA => 88,
                        0x1A => 96,
                        _ => 72,
                    },
                    1,
                    false,
                ));
                c.push(0xB9000001 | (n << 10));
            }
            0x400E => {
                c.push(gpr(n, 1));
                sr_write(&mut c);
            }
            0x401E | 0x402E | 0x400A | 0x401A | 0x402A => {
                c.push(gpr(n, 1));
                c.extend(store(
                    match op & 255 {
                        0x1E => 48,
                        0x2E => 136,
                        0xA => 88,
                        0x1A => 96,
                        _ => 72,
                    },
                    1,
                    false,
                ));
            }
            0x0019 => {
                c.push(0x2A1F03E3);
                for offset in [8, 104, 112] {
                    c.extend(store(offset, 3, true));
                }
            }
            0x0028 => {
                c.extend(store(88, 31, false));
                c.extend(store(96, 31, false));
            }
            0x0008 | 0x0018 => {
                c.push(0x52800003 | (u32::from(op & 255 == 0x18) << 5));
                c.extend(store(8, 3, true));
            }
            0x0009 => {}
            0x001B => {
                c.extend(load(56, 1, false));
                c.extend(store(40, 1, false));
                c.push(0x51000821);
                c.extend(store(56, 1, false));
            }
            _ => return None,
        },
    }
    c.push(0xD65F03C0);
    Some(c)
}
