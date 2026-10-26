//! Native register bodies. x0 is GPR storage; x5 points to T.
fn cset(c: u32) -> u32 {
    0x1A9F07E3 | ((c ^ 1) << 12)
}
fn shift(r: u32, a: u32, right: bool, signed: bool) -> u32 {
    if right {
        (if signed { 0x13000000 } else { 0x53000000 }) | (a << 16) | (31 << 10) | (r << 5) | r
    } else {
        0x53000000 | (((32 - a) & 31) << 16) | ((31 - a) << 10) | (r << 5) | r
    }
}
pub(super) fn emit(op: u16) -> Option<Vec<u32>> {
    let n = u32::from((op >> 8) & 15);
    let m = u32::from((op >> 4) & 15);
    let load = |r: u32, s: u32| 0xB9400000 | (r << 10) | s;
    let mut c = Vec::new();
    let mut write = true;
    let mut flag = false;
    match op & 0xF00F {
        0x3008 => c.extend([load(n, 1), load(m, 2), 0x4B020021]),
        0x6007 | 0x600B | 0x600C | 0x600D | 0x600E | 0x600F | 0x6008 | 0x6009 => {
            c.push(load(m, 1));
            c.push(match op & 15 {
                7 => 0x2A2103E1,
                11 => 0x4B0103E1,
                12 => 0x53001C21,
                13 => 0x53003C21,
                14 => 0x13001C21,
                15 => 0x13003C21,
                8 => 0x5AC00421,
                9 => 0x13814021,
                _ => unreachable!(),
            });
            if op & 15 == 8 {
                c.extend([load(m, 2), 0x53107C42, 0x33103C41]);
            }
        }
        0x200D => c.extend([load(n, 1), load(m, 2), 0x53107C21, 0x2A024021]),
        0x3000 | 0x3002 | 0x3003 | 0x3006 | 0x3007 => {
            c.extend([
                load(n, 1),
                load(m, 2),
                0x6B02003F,
                cset(match op & 15 {
                    0 => 0,
                    2 => 2,
                    3 => 10,
                    6 => 8,
                    7 => 12,
                    _ => unreachable!(),
                }),
            ]);
            write = false;
            flag = true;
        }
        0x2008 => {
            c.extend([load(n, 1), load(m, 2), 0x6A02003F, cset(0)]);
            write = false;
            flag = true;
        }
        0x300E | 0x300F | 0x300A | 0x300B | 0x600A => {
            let low = op & 15;
            c.extend([load(n, 1), load(m, 2)]);
            if low == 14 || low == 10 {
                c.push(0x394000A3);
                if low == 14 {
                    c.extend([0x7100047F, 0x3A020021, cset(2)]);
                } else {
                    c.extend([
                        0x6B0303FF,
                        if op & 0xF000 == 0x6000 { 0x7A0203E1 } else { 0x7A020021 },
                        cset(3),
                    ]);
                }
            } else {
                c.extend([if low == 15 { 0x2B020021 } else { 0x6B020021 }, cset(6)]);
            }
            flag = true;
        }
        _ => match op & 0xF0FF {
            0x4008 | 0x4018 | 0x4028 | 0x4009 | 0x4019 | 0x4029 => c.extend([
                load(n, 1),
                shift(
                    1,
                    match op & 0xF0 {
                        0 => 2,
                        0x10 => 8,
                        _ => 16,
                    },
                    op & 1 != 0,
                    false,
                ),
            ]),
            0x4000 | 0x4020 | 0x4001 | 0x4021 | 0x4004 | 0x4005 | 0x4024 | 0x4025 => {
                c.push(load(n, 1));
                let right = op & 1 != 0;
                c.push(if right { 0x12000023 } else { 0x531F7C23 });
                match op & 255 {
                    0 | 0x20 => c.push(shift(1, 1, false, false)),
                    1 | 0x21 => c.push(shift(1, 1, true, op & 0x20 != 0)),
                    4 | 5 => c.push(0x13810021 | ((if right { 1 } else { 31 }) << 10)),
                    0x24 | 0x25 => {
                        c.push(0x394000A2);
                        c.push(shift(1, 1, right, false));
                        c.push(if right { 0x2A027C21 } else { 0x2A020021 });
                    }
                    _ => unreachable!(),
                }
                flag = true;
            }
            0x4010 => {
                c.extend([load(n, 1), 0x71000421, cset(0)]);
                flag = true;
            }
            0x4011 | 0x4015 => {
                c.extend([load(n, 1), 0x7100003F, cset(if op & 4 == 0 { 10 } else { 12 })]);
                write = false;
                flag = true;
            }
            0x0029 => c.push(0x394000A1),
            _ => match op & 0xFF00 {
                0xC900 | 0xCA00 | 0xCB00 | 0xC800 | 0x8800 => {
                    c.extend([load(0, 1), 0x52800002 | (u32::from(op as u8) << 5)]);
                    match op >> 8 {
                        0xC9 => c.push(0x0A020021),
                        0xCA => c.push(0x4A020021),
                        0xCB => c.push(0x2A020021),
                        0xC8 => {
                            c.extend([0x6A02003F, cset(0)]);
                            write = false;
                            flag = true;
                        }
                        _ => {
                            let i = op as u8 as i8 as i32 as u32;
                            c.pop();
                            c.extend([
                                0x52800002 | ((i & 65535) << 5),
                                0x72A00002 | ((i >> 16) << 5),
                                0x6B02003F,
                                cset(0),
                            ]);
                            write = false;
                            flag = true;
                        }
                    }
                    if write {
                        c.push(0xB9000001);
                        write = false;
                    }
                }
                _ => match op {
                    0x0008 | 0x0018 => {
                        c.push(0x52800003 | (u32::from(op == 0x0018) << 5));
                        write = false;
                        flag = true;
                    }
                    _ => return None,
                },
            },
        },
    }
    if write {
        c.push(0xB9000001 | (n << 10));
    }
    if flag {
        c.push(0x390000A3);
    }
    c.push(0xD65F03C0);
    Some(c)
}
