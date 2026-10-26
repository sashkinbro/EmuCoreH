//! Pure register runs keep live guest registers in w8..w23. Every code guard runs
//! before those registers are loaded. No callback/debug/trace/cache write can occur
//! inside a run; all state is committed before a helper, actual budget/stop, or exit.
use super::fast::{branch, constant, load_x, patch};
fn body(op: u16) -> Option<(Vec<u32>, u16, u16)> {
    let n = u32::from((op >> 8) & 15);
    let m = u32::from((op >> 4) & 15);
    let d = n + 8;
    let s = m + 8;
    let bit = |r: u32| 1u16 << r;
    let mut c = Vec::new();
    // x24 is the stable Context pointer inside a pure run. Flags are committed
    // immediately, so every budget/stop prefix exposes exactly its last T value.
    let store_t = |code: &mut Vec<u32>| code.extend([0xF9400701, 0x39000022]);
    let compare_t = |code: &mut Vec<u32>, condition: u32| {
        code.push(0x1A9F07E2 | ((condition ^ 1) << 12));
        store_t(code);
    };
    let reads;
    let writes;
    if op & 0xFF00 == 0x8800 || op & 0xFF00 == 0xC800 {
        let immediate =
            if op & 0xFF00 == 0x8800 { op as u8 as i8 as i32 as u32 } else { u32::from(op as u8) };
        constant(&mut c, 1, immediate);
        c.push(if op & 0xFF00 == 0x8800 { 0x6B01011F } else { 0x6A01011F });
        compare_t(&mut c, 0);
        return Some((c, bit(0), 0));
    }
    match op & 0xF000 {
        0xE000 => {
            constant(&mut c, d, op as u8 as i8 as i32 as u32);
            reads = 0;
            writes = bit(n);
        }
        0x7000 => {
            let i = op as u8 as i8 as i32;
            c.push(
                (if i < 0 { 0x51000000 } else { 0x11000000 })
                    | (i.unsigned_abs() << 10)
                    | (d << 5)
                    | d,
            );
            reads = bit(n);
            writes = bit(n);
        }
        _ => match op & 0xF00F {
            0x3000 | 0x3002 | 0x3003 | 0x3006 | 0x3007 | 0x2008 => {
                c.push(
                    (if op & 0xF00F == 0x2008 { 0x6A00001F } else { 0x6B00001F })
                        | (s << 16)
                        | (d << 5),
                );
                let condition = match op & 0xF00F {
                    0x3002 => 2,
                    0x3003 => 10,
                    0x3006 => 8,
                    0x3007 => 12,
                    _ => 0,
                };
                compare_t(&mut c, condition);
                reads = bit(n) | bit(m);
                writes = 0;
            }
            0x6003 => {
                c.push(0x2A0003E0 | (s << 16) | d);
                reads = bit(m);
                writes = bit(n);
            }
            0x300C | 0x3008 | 0x2009 | 0x200A | 0x200B => {
                c.push(
                    (match op & 0xF00F {
                        0x300C => 0x0B000000,
                        0x3008 => 0x4B000000,
                        0x2009 => 0x0A000000,
                        0x200A => 0x4A000000,
                        _ => 0x2A000000,
                    }) | (s << 16)
                        | (d << 5)
                        | d,
                );
                reads = bit(n) | bit(m);
                writes = bit(n);
            }
            0x6007 | 0x600B | 0x600C | 0x600D | 0x600E | 0x600F => {
                c.push(match op & 15 {
                    7 => 0x2A2003E0 | (s << 16) | d,
                    11 => 0x4B0003E0 | (s << 16) | d,
                    other => {
                        (match other {
                            12 => 0x53001C00,
                            13 => 0x53003C00,
                            14 => 0x13001C00,
                            _ => 0x13003C00,
                        }) | (s << 5)
                            | d
                    }
                });
                reads = bit(m);
                writes = bit(n);
            }
            0x6008 => {
                c.extend([0x53107C00 | (s << 5) | 1, 0x5AC00400 | (s << 5) | d, 0x33103C20 | d]);
                reads = bit(m);
                writes = bit(n);
            }
            0x6009 => {
                c.push(0x13804000 | (s << 16) | (s << 5) | d);
                reads = bit(m);
                writes = bit(n);
            }
            0x200D => {
                if n == m {
                    c.push(0x13804000 | (d << 16) | (d << 5) | d);
                } else {
                    c.extend([0x53107C00 | (d << 5) | d, 0x2A004000 | (s << 16) | (d << 5) | d]);
                }
                reads = bit(n) | bit(m);
                writes = bit(n);
            }
            _ => match op & 0xF0FF {
                0x4010 => {
                    c.push(0x71000400 | (d << 5) | d);
                    compare_t(&mut c, 0);
                    reads = bit(n);
                    writes = bit(n);
                }
                0x4011 | 0x4015 => {
                    c.push(0x7100001F | (d << 5));
                    compare_t(&mut c, if op & 4 == 0 { 10 } else { 12 });
                    reads = bit(n);
                    writes = 0;
                }
                0x0029 => {
                    c.extend([0xF9400701, 0x39400020 | d]);
                    reads = 0;
                    writes = bit(n);
                }
                0x0008 | 0x0018 => {
                    c.push(if op & 0x10 == 0 { 0x2A1F03E2 } else { 0x52800022 });
                    store_t(&mut c);
                    reads = 0;
                    writes = 0;
                }
                0x4008 | 0x4018 | 0x4028 | 0x4009 | 0x4019 | 0x4029 => {
                    let a = match op & 0xF0 {
                        0 => 2,
                        0x10 => 8,
                        _ => 16,
                    };
                    let right = op & 1 != 0;
                    c.push(if right {
                        0x53000000 | (a << 16) | (31 << 10) | (d << 5) | d
                    } else {
                        0x53000000 | (((32 - a) & 31) << 16) | ((31 - a) << 10) | (d << 5) | d
                    });
                    reads = bit(n);
                    writes = bit(n);
                }
                0x0009 => {
                    reads = 0;
                    writes = 0;
                }
                _ => return None,
            },
        },
    }
    Some((c, reads, writes))
}
pub(super) fn length(trace: &[(u32, u16)]) -> usize {
    let first = trace[0].0;
    trace
        .iter()
        .take_while(|&&(pc, op)| {
            pc >> 29 == first >> 29
                && (first >> 29 != 0 || pc & !15 == first & !15)
                && body(op).is_some()
        })
        .count()
}
fn conditional(c: &mut Vec<u32>, list: &mut Vec<usize>, cond: u32) {
    branch(c, list, 0x54000000 | cond);
}
fn guards(
    trace: &[(u32, u16)],
    c: &mut Vec<u32>,
    exits: &mut Vec<usize>,
    mismatches: &mut Vec<usize>,
) {
    let first = trace[0].0;
    c.extend([load_x(8, 9), 0xB940012A]);
    constant(c, 11, first);
    c.push(0x6B0B015F);
    // A taken branch may leave this learned path without changing its code.
    conditional(c, exits, 1);
    // A pure run may not span a nonsequential pending branch-delay target.
    c.extend([load_x(16, 9), 0xB940012A]);
    constant(c, 11, first.wrapping_add(2));
    c.push(0x6B0B015F);
    conditional(c, exits, 1);
    if first >> 29 == 1 {
        constant(c, 10, first);
        c.push(0xB940AB8B);
        c.push(0x6B0B015F);
        conditional(c, exits, 3);
        constant(c, 10, trace.last().unwrap().0);
        c.push(0xB940AF8B);
        c.push(0x6B0B015F);
        conditional(c, exits, 2);
        constant(c, 10, first);
        c.push(0xB940AB8B);
        c.push(0x4B0B014A);
        c.push(load_x(160, 4));
        c.push(0x8B2A4084); // add x4,x4,w10,uxtw
        for (i, &(_, op)) in trace.iter().enumerate() {
            c.push(0x7940008A | ((i as u32) << 10));
            constant(c, 11, u32::from(op));
            c.push(0x6B0B015F);
            conditional(c, mismatches, 1);
        }
    } else {
        c.extend([load_x(48, 9), 0x39400129]);
        branch(c, exits, 0x34000009);
        let index = (first >> 4) & 63;
        let tag = (first & 0x1FFFFFFF) >> 10;
        let mut found = Vec::new();
        for way in (0..4).rev() {
            c.extend([load_x(104 + way * 8, 9), 0xF9400129]);
            let tb = c.len();
            c.push(0x36000009 | ((index & 31) << 19) | ((index >> 5) << 31));
            c.extend([load_x(72 + way * 8, 9), 0xB940012A | (index << 10)]);
            constant(c, 11, tag);
            c.push(0x6B0B015F);
            let next = c.len();
            c.push(0x54000001);
            for &(pc, op) in trace {
                let offset = (way << 10) | (index << 4) | (pc & 14);
                c.extend([load_x(56, 9), 0x7940012A | ((offset / 2) << 10)]);
                constant(c, 11, u32::from(op));
                c.push(0x6B0B015F);
                conditional(c, mismatches, 1);
            }
            // Same-way same-line fetch LRU updates are idempotent and nothing in a pure
            // register run accesses cache. One update has exactly the final live LRU value.
            c.extend([load_x(64, 9), 0x3940012A | (index << 10)]);
            let (and, or) = match way {
                0 => (0xC7, 0),
                1 => (0xF9, 0x20),
                2 => (0xFE, 0x14),
                _ => (0xFF, 0x0B),
            };
            constant(c, 11, and);
            c.push(0x0A0B014A);
            constant(c, 11, or);
            c.push(0x2A0B014A);
            c.push(0x3900012A | (index << 10));
            branch(c, &mut found, 0x14000000);
            let label = c.len();
            patch(c, tb, label, 14);
            patch(c, next, label, 19);
        }
        branch(c, exits, 0x14000000);
        let label = c.len();
        for at in found {
            patch(c, at, label, 26);
        }
    }
}
pub(super) fn emit(
    trace: &[(u32, u16)],
    c: &mut Vec<u32>,
    exits: &mut Vec<usize>,
    mismatches: &mut Vec<usize>,
) -> Option<()> {
    guards(trace, c, exits, mismatches);
    let mut needed = 0u16;
    let mut written = 0u16;
    let mut bodies = Vec::new();
    for &(_, op) in trace {
        let (b, reads, writes) = body(op)?;
        needed |= reads & !written;
        written |= writes;
        bodies.push(b);
    }
    // A budget/stop may exit before a future write-only destination is defined.
    // Preserve its input value as well so every executed prefix can spill safely.
    needed |= written;
    c.extend([
        load_x(0, 24),
        0xF9400307,
        load_x(136, 25),
        load_x(32, 1),
        0xF940003A,
        load_x(40, 1),
        0xF940003B,
        load_x(144, 5),
    ]);
    for r in 0..16 {
        if needed & (1 << r) != 0 {
            c.push(0xB94000E0 | (r << 10) | (r + 8));
        }
    }
    if trace[0].0 >> 29 == 0 {
        c.push(0xD2800026);
    } else {
        c.extend([load_x(176, 6), 0x910004C6]);
    }
    let mut stops = Vec::new();
    let mut budgets = Vec::new();
    for body in bodies {
        c.extend(body);
        c.extend([0x91000739, 0x8B06035A, 0xEB1B035F]);
        let at = c.len();
        c.push(0x54000002);
        stops.push(at);
        c.push(0xEB05033F);
        let at = c.len();
        c.push(0x54000002);
        budgets.push(at);
    }
    let mut finalize = Vec::new();
    let mut stub = |c: &mut Vec<u32>, pc: u32, op: u16, reason: u32| {
        constant(c, 4, pc);
        constant(c, 3, u32::from(op));
        c.push(0x52800002 | (reason << 5));
        branch(c, &mut finalize, 0x14000000);
    };
    let &(pc, op) = trace.last()?;
    stub(c, pc, op, 3);
    for (i, &(pc, op)) in trace.iter().enumerate() {
        let at = c.len();
        patch(c, stops[i], at, 19);
        stub(c, pc, op, 2);
        let at = c.len();
        patch(c, budgets[i], at, 19);
        stub(c, pc, op, 0);
    }
    let finish = c.len();
    for at in finalize {
        patch(c, at, finish, 26);
    }
    for r in 0..16 {
        if written & (1 << r) != 0 {
            c.push(0xB90000E0 | (r << 10) | (r + 8));
        }
    }
    c.extend([
        0xF9004799,
        load_x(32, 1),
        0xF900003A,
        0xB9009B84,
        0xB9009F83,
        load_x(8, 1),
        0x11000884,
        0xB9000024,
        load_x(16, 1),
        0x11000884,
        0xB9000024,
        load_x(24, 1),
        0x3900003F,
        0xB900BB82,
        0x71000C5F,
    ]);
    let continuation = c.len();
    c.push(0x54000000);
    branch(c, exits, 0x14000000);
    let label = c.len();
    patch(c, continuation, label, 19);
    Some(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn comparisons_and_t_transfers_stay_in_one_register_resident_run() {
        for opcode in [
            0x3120, 0x3122, 0x3123, 0x3126, 0x3127, 0x2128, 0x4110, 0x4111, 0x4115, 0x0229, 0x88FF,
            0xC87F, 0x0008, 0x0018,
        ] {
            let trace = [(0x20000100, 0xE101), (0x20000102, opcode), (0x20000104, 0x7101)];
            assert_eq!(super::length(&trace), trace.len(), "{opcode:04X}");
        }
    }
}
