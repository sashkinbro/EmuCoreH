//! Native branch target/delay/PR updates. The only callback accounts bus cycles.
pub(super) fn emit(op: u16) -> Option<Vec<u32>> {
    let n = u32::from((op >> 8) & 15);
    let conditional = matches!(op & 0xFF00, 0x8900 | 0x8B00 | 0x8D00 | 0x8F00);
    let delayed = !conditional || op & 0x0400 != 0;
    let link = matches!(op & 0xF000, 0xB000) || matches!(op & 0xF0FF, 0x400B | 0x0003);
    if !conditional
        && !matches!(op & 0xF000, 0xA000 | 0xB000)
        && !matches!(op & 0xF0FF, 0x402B | 0x400B | 0x0023 | 0x0003)
        && op & 0xF0FF != 0x000B
    {
        return None;
    }
    let mut c = vec![0xA9BF7BF3, 0xAA0003F3]; // context kept across cycle callback
    let mut skip = None;
    if conditional {
        c.extend([0xF9400662, 0x39400042]); // T pointer then value
        skip = Some(c.len());
        c.push(if op & 0x0200 == 0 { 0x34000002 } else { 0x35000002 });
    }
    c.extend([0xF9401662, 0xB9400041]); // current next_pc -> w1
    if link {
        c.extend([0xF9402663, 0xB9000061]);
    }
    if conditional || matches!(op & 0xF000, 0xA000 | 0xB000) {
        let d = if conditional {
            i32::from(op as u8 as i8) * 2
        } else {
            i32::from((op << 4) as i16 >> 4) * 2
        };
        c.push(if d == -4096 {
            0x51400421
        } else {
            (if d < 0 { 0x51000021 } else { 0x11000021 }) | (d.unsigned_abs() << 10)
        });
    } else if op & 0xF0FF == 0x000B {
        c.extend([0xF9402663, 0xB9400061]);
    } else {
        c.extend([0xF9400263, 0xB9400063 | (n << 10)]);
        if matches!(op & 0xF0FF, 0x0023 | 0x0003) {
            c.push(0x0B030021);
        } else {
            c.push(0x2A0303E1);
        }
    }
    c.push(0xB9000041); // update next_pc
    if delayed {
        c.extend([0xF9402263, 0x52800024, 0x39000064]);
    } else {
        c.extend([0xF9401E63, 0xB9000061, 0x11000821, 0xB9000041]);
    }
    c.extend([
        0xF9400A60,
        0x52800001 | ((if delayed { 1 } else { 2 }) << 5),
        0xF9402A70,
        0xD63F0200,
    ]);
    let exit = c.len();
    if let Some(i) = skip {
        c[i] |= ((exit - i) as u32) << 5;
    }
    c.extend([0xA8C17BF3, 0xD65F03C0]);
    Some(c)
}
