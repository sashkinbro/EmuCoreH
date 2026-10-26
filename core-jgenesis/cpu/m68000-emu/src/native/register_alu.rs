//! Register ALU lowering. Narrow arithmetic is aligned to bit 31 so A64 NZCV
//! describes the guest operand width exactly, including carry and signed overflow.
#[derive(Clone, Copy)]
enum Op { Move, Add, Sub, Cmp, And, Or, Xor }
fn decode(opcode: u16) -> Option<(Op, u32, u32, u32, Option<u32>)> {
    let top = opcode >> 12;
    let source = u32::from(opcode & 7);
    let dest = u32::from((opcode >> 9) & 7);
    if opcode & 0x01F8 == 0 && matches!(top, 1..=3) {
        return Some((Op::Move, source, dest, match top {1=>1,2=>4,_=>2}, None));
    }
    let size = (opcode >> 6) & 3;
    if size == 3 || opcode & 0x38 != 0 { return None; }
    let bytes = 1 << size;
    if top == 5 {
        let immediate = if dest == 0 {8} else {dest};
        return Some((if opcode & 0x100 == 0 {Op::Add} else {Op::Sub}, source, source, bytes, Some(immediate)));
    }
    let op = match (top, opcode & 0x100 != 0) {
        (0xD,false)=>Op::Add, (9,false)=>Op::Sub, (0xB,false)=>Op::Cmp,
        (0xC,false)=>Op::And, (8,false)=>Op::Or, (0xB,true)=>Op::Xor,
        _=>return None,
    };
    if matches!(op,Op::Xor) {Some((op,dest,source,bytes,None))}
    else {Some((op,source,dest,bytes,None))}
}
pub(super) fn cycles(opcode: u16) -> Option<u32> {
    let (op,_,_,size,quick)=decode(opcode)?;
    Some(if matches!(op,Op::Move) {4} else if size != 4 {4}
    else if matches!(op,Op::Cmp) {6} else if quick.is_some() {8} else {8})
}
#[cfg(any(test, all(target_arch="aarch64", target_endian="little", any(target_os="android",target_os="linux"))))]
pub(super) fn emit(opcode:u16)->Option<Vec<u32>> {
    let (op,source,dest,size,immediate)=decode(opcode)?;
    let arithmetic=matches!(op,Op::Add|Op::Sub|Op::Cmp);
    let mut code=vec![0xB9400001|(dest<<10)]; // w1 = destination
    if let Some(value)=immediate {code.push(0x52800002|(value<<5));}
    else {code.push(0xB9400002|(source<<10));}
    if arithmetic && size!=4 {
        let shift=32-size*8;
        for reg in [1,2] {code.push(0x53000000|(((32-shift)&31)<<16)|((31-shift)<<10)|(reg<<5)|reg);}
    }
    code.push(match op {
        Op::Move=>0x2A0203E1, Op::Add=>0x2B020021, Op::Sub|Op::Cmp=>0x6B020021,
        Op::And=>0x0A020021, Op::Or=>0x2A020021, Op::Xor=>0x4A020021,
    });
    if arithmetic {
        // Capture NZCV before another flag-setting operation. SUB carry is inverted
        // because the 68000 sets C on borrow whereas A64 sets C on no borrow.
        let carry=if matches!(op,Op::Add) {3} else {2};
        code.extend([0x1A9F07E3|(carry<<12), // cset w3,carry
            0x1A9F77E4, // cset w4,vs
            0x2A040463, // orr w3,w3,w4,lsl#1
            0x1A9F17E4, // cset w4,eq
            0x2A040863, // orr w3,w3,w4,lsl#2
            0x1A9F57E4, // cset w4,mi
            0x2A040C63]);
        if matches!(op,Op::Cmp) {code.extend([0xB9402002,0x121C0042,0x2A020063]);}
        else {code.extend([0x12000064,0x2A041063]);} // X = C
        code.push(0xB9002003);
        if size!=4 {let shift=32-size*8;code.push(0x53000021|(shift<<16)|(31<<10));}
    }
    if !matches!(op,Op::Cmp) {
        code.push(match size {1=>0x39000001|(dest<<12),2=>0x79000001|(dest<<11),_=>0xB9000001|(dest<<10)});
    }
    if !arithmetic {
        if size!=4 {code.push(if size==1 {0x13001C21}else{0x13003C21});}
        code.extend([0xB9402002,0x121C0042,0x7100003F,0x1A9F17E3,0x531E7463,
            0x531F7C24,0x2A040C63,0x2A030042,0xB9002002]);
    }
    code.push(0xD65F03C0); Some(code)
}
#[cfg(test)]
mod tests {
    #[test] fn register_alu_decoding_excludes_special_and_memory_forms() {
        for base in [0x1000,0x2000,0x3000,0xD000,0x9000,0xB000,0xC000,0x8000,0xB100,0x5000,0x5100] {
            for dst in 0..8 {for src in 0..8 {assert!(super::emit(base|(dst<<9)|src).is_some());}}
        }
        for op in [0xD100,0x9100,0xC100,0x8100,0xD010,0xD0C0,0x50C0,0x3040,0x2040] {assert!(super::emit(op).is_none(),"{op:04X}");}
    }
}
