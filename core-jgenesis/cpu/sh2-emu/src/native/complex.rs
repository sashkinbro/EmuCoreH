//! Native multi-access instructions. Bus callbacks perform exactly one read/write;
//! generated instructions implement SR packing, addresses, ALU, and register ordering.
fn ptr(off: u32, r: u32) -> u32 {
    0xF9400260 | ((off / 8) << 10) | r
}
fn load(off: u32, r: u32, byte: bool) -> [u32; 2] {
    [ptr(off, 14), (if byte { 0x394001C0 } else { 0xB94001C0 }) | r]
}
fn store(off: u32, r: u32, byte: bool) -> [u32; 2] {
    [ptr(off, 14), (if byte { 0x390001C0 } else { 0xB90001C0 }) | r]
}
fn read(c: &mut Vec<u32>, size: u32) {
    c.extend([0xF9400A60, 0x52800002 | (size << 5), 0xF9400E70, 0xD63F0200]);
}
fn write(c: &mut Vec<u32>, size: u32) {
    c.extend([0xF9400A60, 0x52800003 | (size << 5), 0xF9401270, 0xD63F0200]);
}
fn cycles(c: &mut Vec<u32>, n: u32) {
    c.extend([0xF9400A60, 0x52800001 | (n << 5), 0xF9402A70, 0xD63F0200]);
}
fn gpr(r: u32, s: u32) -> u32 {
    0xB9400280 | (r << 10) | s
}
fn gpr_store(r: u32, s: u32) -> u32 {
    0xB9000280 | (r << 10) | s
}
fn sr_read(c: &mut Vec<u32>) {
    c.push(0xAA1303E6);
    super::special::sr_read(c);
}
fn sr_write(c: &mut Vec<u32>) {
    c.push(0xAA1303E6);
    super::special::sr_write(c);
}
fn constant(c: &mut Vec<u32>, r: u32, v: u64) {
    for i in 0..4 {
        c.push(
            (if i == 0 { 0xD2800000 } else { 0xF2800000 })
                | (i << 21)
                | ((((v >> (i * 16)) & 65535) as u32) << 5)
                | r,
        );
    }
}
fn cmp(a: u32, b: u32) -> u32 {
    0xEB00001F | (b << 16) | (a << 5)
}
fn select(dst: u32, a: u32, b: u32, cond: u32) -> u32 {
    0x9A800000 | (b << 16) | (cond << 12) | (a << 5) | dst
}
fn clamp(c: &mut Vec<u32>, r: u32, min: i64, max: i64) {
    constant(c, 23, min as u64);
    constant(c, 24, max as u64);
    c.extend([cmp(r, 23), select(r, 23, r, 11), cmp(r, 24), select(r, 24, r, 12)]);
}
pub(super) fn emit(op: u16) -> Option<Vec<u32>> {
    let n = u32::from((op >> 8) & 15);
    let m = u32::from((op >> 4) & 15);
    let mut c = vec![0xA9BC53F3, 0xA9015BF5, 0xA90263F7, 0xA9037BF9, 0xAA0003F3, 0xF9400014];
    match op & 0xF0FF {
        0x4002 | 0x4012 | 0x4022 | 0x4003 | 0x4013 | 0x4023 => {
            c.extend([gpr(n, 21), 0x510012B5, gpr_store(n, 21)]);
            if op & 255 == 3 {
                sr_read(&mut c);
            } else {
                c.extend(load(
                    match op & 255 {
                        2 => 88,
                        0x12 => 96,
                        0x22 => 72,
                        0x13 => 48,
                        _ => 136,
                    },
                    1,
                    false,
                ));
            }
            c.extend([0x2A0103E2, 0x2A1503E1]);
            write(&mut c, 2);
            if op & 1 != 0 {
                cycles(&mut c, 1);
            }
        }
        0x4006 | 0x4016 | 0x4026 | 0x4007 | 0x4017 | 0x4027 => {
            c.extend([gpr(n, 1), 0x11001035, gpr_store(n, 21)]);
            read(&mut c, 2);
            c.push(0x2A0003E1);
            if op & 255 == 7 {
                sr_write(&mut c);
            } else {
                c.extend(store(
                    match op & 255 {
                        6 => 88,
                        0x16 => 96,
                        0x26 => 72,
                        0x17 => 48,
                        _ => 136,
                    },
                    1,
                    false,
                ));
            }
            if op & 1 != 0 {
                cycles(&mut c, 2);
            }
        }
        0x401B => {
            cycles(&mut c, 3);
            c.extend([gpr(n, 21), 0x2A1503E1]);
            read(&mut c, 3);
            c.push(0x2A0003F6);
            c.extend([0x52801002, 0x2A0202C2, 0x2A1503E1]);
            write(&mut c, 0);
            c.extend([0x710002DF, 0x1A9F17E3]);
            c.extend(store(8, 3, true));
        }
        0x002B => {
            c.push(gpr(15, 1));
            read(&mut c, 2);
            c.extend(store(40, 0, false));
            c.push(0x52800023);
            c.extend(store(64, 3, true));
            c.extend([gpr(15, 1), 0x11001021, gpr_store(15, 1)]);
            read(&mut c, 2);
            c.push(0x2A0003E1);
            sr_write(&mut c);
            c.extend([gpr(15, 1), 0x11001021, gpr_store(15, 1)]);
        }
        _ => match op & 0xFF00 {
            0xCC00 | 0xCD00 | 0xCE00 | 0xCF00 => {
                cycles(&mut c, 2);
                c.extend(load(48, 21, false));
                c.extend([gpr(0, 1), 0x0B0102B5, 0x2A1503E1]);
                read(&mut c, 0);
                c.push(0x52800002 | (u32::from(op & 255) << 5));
                match op >> 8 {
                    0xCC => {
                        c.extend([0x6A02001F, 0x1A9F17E3]);
                        c.extend(store(8, 3, true));
                    }
                    _ => {
                        c.push(match op >> 8 {
                            0xCD => 0x0A020002,
                            0xCE => 0x4A020002,
                            _ => 0x2A020002,
                        });
                        c.push(0x2A1503E1);
                        write(&mut c, 0);
                    }
                }
            }
            0xC300 => {
                c.extend([gpr(15, 21), 0x510012B5, gpr_store(15, 21)]);
                sr_read(&mut c);
                c.extend([0x2A0103E2, 0x2A1503E1]);
                write(&mut c, 2);
                c.extend([gpr(15, 21), 0x510012B5, gpr_store(15, 21)]);
                c.extend(load(40, 2, false));
                c.push(0x2A1503E1);
                write(&mut c, 2);
                c.extend(load(136, 1, false));
                c.push(0x11000021 | ((u32::from(op & 255) * 4) << 10));
                read(&mut c, 2);
                c.extend(store(56, 0, false));
                c.push(0x11000801);
                c.extend(store(40, 1, false));
                cycles(&mut c, 7);
            }
            _ => match op & 0xF00F {
                0x000F | 0x400F => {
                    let word = op & 0xF000 == 0x4000;
                    let size = if word { 1 } else { 2 };
                    let step = 1 << size;
                    c.push(gpr(m, 1));
                    read(&mut c, size);
                    c.push(0x2A0003F5);
                    c.extend([gpr(m, 1), 0x11000021 | (step << 10), gpr_store(m, 1)]);
                    c.push(gpr(n, 1));
                    read(&mut c, size);
                    c.extend([gpr(n, 1), 0x11000021 | (step << 10), gpr_store(n, 1)]);
                    if word {
                        c.extend([0x13003EB5, 0x13003C00]);
                    } // signed 16-bit operands
                    c.push(0x9B207EB9); // smull x25,w21,w0
                    c.extend(load(96, 21, false));
                    c.extend(load(88, 22, false));
                    c.push(0xAA1682B5); // mac64
                    c.push(0x8B1902B5); // wrapping product sum
                    c.extend(load(120, 2, true));
                    if word {
                        c.extend(load(96, 22, false));
                        c.extend([0x93407ED6, 0x8B1902D6]);
                        clamp(&mut c, 22, i64::from(i32::MIN), i64::from(i32::MAX));
                        c.extend([0x7100005F, select(21, 21, 22, 0)]);
                        c.extend(store(96, 21, false));
                        let skip = c.len();
                        c.push(0x35000002);
                        c.push(0xD360FEB5);
                        c.extend(store(88, 21, false));
                        let end = c.len();
                        c[skip] |= ((end - skip) as u32) << 5;
                    } else {
                        c.push(0xAA1503F6);
                        clamp(&mut c, 22, -(1 << 47), (1 << 47) - 1);
                        c.extend([0x7100005F, select(21, 21, 22, 0)]);
                        c.extend(store(96, 21, false));
                        c.push(0xD360FEB5);
                        c.extend(store(88, 21, false));
                    }
                }
                _ => return None,
            },
        },
    }
    c.extend([0xA9437BF9, 0xA94263F7, 0xA9415BF5, 0xA8C453F3, 0xD65F03C0]);
    Some(c)
}
