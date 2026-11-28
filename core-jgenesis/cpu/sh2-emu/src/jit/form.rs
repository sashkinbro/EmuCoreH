//! Block discovery: turns cache-resident code into micro-ops with static cycle costs.
//!
//! Formation has no side effects: instruction words are read with `CpuCache::peek_fetch`, which
//! selects the way exactly like a real fetch. A block ends
//! * after a delayed branch and its delay slot (`BRA BSR BRAF BSRF JMP JSR RTS RTE BT/S BF/S`),
//! * after `TRAPA`/`SLEEP`,
//! * at `MAX_OPS` instructions (a trailing delay slot may add one) or `MAX_LINES` cache lines,
//! * where the next fetch would miss the cache or leave cached space.
//!
//! `BT`/`BF` do not end a block: they end a *segment* and leave the block when taken.
//!
//! Cycle costs are the interpreter's: every instruction costs 1 plus the fixed charges its handler
//! makes (`BRA BSR BRAF BSRF JMP JSR RTS`: +1; `BT`/`BF` taken: +2; `BT/S`/`BF/S` taken: +1).
//! Call-outs charge through the bus as the interpreter does, so their cost is not static.

use super::{
    EMPTY_UOP, F_FETCH, F_MEM, F_NOTE, F_SLOT, K_BRANCH, K_CALLOUT, K_DELAYED, K_NATIVE, Link,
    MAX_LINES, MAX_OPS, NativeFn, Seen, Uop,
};
use crate::Sh2;
use crate::bus::BusInterface;
use crate::predecode::{Flow, flow, handler_for, is_register_only};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct LineRef {
    // Address bits 4-31 of the line
    pub(crate) addr: u32,
    pub(crate) gen_value: u32,
    pub(crate) ram_base: u16,
    pub(crate) way: u8,
    pub(crate) entry: u8,
    pub(crate) lru_and: u8,
    pub(crate) lru_or: u8,
    pub(crate) _pad: u16,
}

impl LineRef {
    #[inline(always)]
    pub(crate) fn new(addr: u32, gen_value: u32, way: u8, entry: u8) -> Self {
        let mut line = Self { addr, gen_value, ram_base: 0, way, entry, lru_and: 0xFF, lru_or: 0, _pad: 0 };
        line.set_location(way, entry, gen_value);
        line
    }

    #[inline(always)]
    pub(crate) fn set_location(&mut self, way: u8, entry: u8, gen_value: u32) {
        self.way = way;
        self.entry = entry;
        self.gen_value = gen_value;
        self.ram_base = (u16::from(way) << 9) | (u16::from(entry) << 3);
        let (lru_and, lru_or) = match way {
            0 => (0xC7, 0x00),
            1 => (0xF9, 0x20),
            2 => (0xFE, 0x14),
            _ => (0xFF, 0x0B),
        };
        self.lru_and = lru_and;
        self.lru_or = lru_or;
    }
}

const EMPTY_LINE: LineRef = LineRef {
    addr: 0,
    gen_value: 0,
    ram_base: 0,
    way: 0,
    entry: 0,
    lru_and: 0xFF,
    lru_or: 0,
    _pad: 0,
};

#[derive(Clone, Debug)]
pub(crate) struct Block {
    pub(crate) start: u32,
    pub(crate) n: u8,
    pub(crate) nlines: u8,
    /// Bumped every time the table slot is overwritten (stale links compare unequal)
    pub(crate) version: u32,
    pub(crate) conf: u32,
    /// Cache code counter at the last successful validation
    pub(crate) seen: Seen,
    pub(crate) lines: [LineRef; MAX_LINES],
    /// Successor links: 0 = fall-through / static target, 1 = taken branch / dynamic target
    pub(crate) links: [Link; 2],
    /// Exactly `n` micro-ops (boxed so the table of block headers stays small)
    pub(crate) ops: Box<[Uop]>,
    /// Compiled code entry (0 = none), runs before compiling, compilation refused
    #[cfg_attr(not(any(test, feature = "jit-arm64")), allow(dead_code))]
    pub(crate) entry: usize,
    #[cfg_attr(not(any(test, feature = "jit-arm64")), allow(dead_code))]
    pub(crate) runs: u16,
    #[cfg_attr(not(any(test, feature = "jit-arm64")), allow(dead_code))]
    pub(crate) no_native: bool,
    #[cfg_attr(not(any(test, feature = "jit-arm64")), allow(dead_code))]
    pub(crate) slot: u16,
    #[cfg_attr(not(any(test, feature = "jit-arm64")), allow(dead_code))]
    pub(crate) last_materialize: bool,
    /// Native chaining records of links 0 and 1 (read by compiled code)
    #[cfg(any(test, feature = "jit-arm64"))]
    pub(crate) chain: [usize; 2],
}

impl Block {
    pub(crate) fn empty() -> Self {
        Self {
            start: 0,
            n: 0,
            nlines: 0,
            version: 0,
            conf: 0,
            seen: Seen::default(),
            lines: [EMPTY_LINE; MAX_LINES],
            links: [Link::default(); 2],
            ops: Box::new([]),
            entry: 0,
            runs: 0,
            no_native: false,
            slot: 0,
            last_materialize: false,
            #[cfg(any(test, feature = "jit-arm64"))]
            chain: [0; 2],
        }
    }

    /// Whether every line the block was recorded from is still exactly as recorded
    #[inline(always)]
    pub(crate) fn valid(&self, cache: &crate::cache::CpuCache) -> bool {
        self.seen.get() == cache.code_gen() || self.revalidate(cache)
    }

    #[cold]
    #[inline(never)]
    fn revalidate(&self, cache: &crate::cache::CpuCache) -> bool {
        if cache.conf_gen() != self.conf {
            return false;
        }
        for line in &self.lines[..usize::from(self.nlines)] {
            if cache.line_gen(line.way, line.entry) != line.gen_value {
                return false;
            }
        }
        self.seen.set(cache.code_gen());
        true
    }

    /// After the block's lines changed: if every line is (again) resident and holds exactly the
    /// recorded instruction words, adopt the lines' current ways and counters instead of forming
    /// the block again. Everything else in a block depends only on its addresses and words.
    #[cold]
    #[inline(never)]
    pub(crate) fn refresh(&mut self, cache: &crate::cache::CpuCache) -> bool {
        let region = self.start >> 29;
        let n = usize::from(self.n);
        for (index, line) in self.lines[..usize::from(self.nlines)].iter_mut().enumerate() {
            let (way, entry) = if region == 0 {
                let Some((entry, way)) = cache.lookup(line.addr) else { return false };
                (way, entry)
            } else {
                (line.way, line.entry)
            };
            for op in self.ops[..n].iter().filter(|op| usize::from(op.line) == index) {
                let word = if region == 0 {
                    cache.line_word(way, entry, op.addr)
                } else {
                    cache.peek_data_array(op.addr)
                };
                if word != op.opcode {
                    return false;
                }
            }
            line.set_location(way, entry, cache.line_gen(way, entry));
        }
        self.conf = cache.conf_gen();
        self.seen.set(cache.code_gen());
        true
    }

    /// Total static cost of the block when executed straight through (all conditional branches
    /// not taken), call-outs counted at their base cost of one cycle
    #[cfg(test)]
    pub(crate) fn static_cost(&self) -> u64 {
        self.ops[..usize::from(self.n)].iter().map(|op| u64::from(op.cost)).sum()
    }
}

/// A native micro-op candidate: `(function, kind, cost, extra, imm, flags)`
type Native = (NativeFn, u8, u8, u8, u32, u8);

// Hot instruction codes (`Uop::code`): dispatched with direct calls by the executor
pub(crate) const H_NOP: u8 = 1;
pub(crate) const H_BT: u8 = 2;
pub(crate) const H_BF: u8 = 3;
pub(crate) const H_BT_S: u8 = 4;
pub(crate) const H_BF_S: u8 = 5;
pub(crate) const H_BRA: u8 = 6;
pub(crate) const H_CMP_EQ_IMM: u8 = 7;
pub(crate) const H_TST_IMM: u8 = 8;
pub(crate) const H_CMP_EQ: u8 = 9;
pub(crate) const H_TST: u8 = 10;
pub(crate) const H_ADD_IMM: u8 = 11;
pub(crate) const H_SWAP_B: u8 = 12;
pub(crate) const H_DT: u8 = 13;
pub(crate) const H_MOV: u8 = 14;
pub(crate) const H_MOV_IMM: u8 = 15;
pub(crate) const C_MOV_W_GBR_R0: u8 = 32;
pub(crate) const C_MOV_L_IND_RN: u8 = 33;
pub(crate) const C_MOV_W_IND_RN: u8 = 34;
pub(crate) const C_MOV_L_PC_RN: u8 = 35;
pub(crate) const C_MOV_L_DISP_RN: u8 = 36;
pub(crate) const C_MOV_L_RM_IND: u8 = 37;

/// Hot code of a micro-op; must name exactly the function stored in `Uop::f`
fn hot_code(opcode: u16, kind: u8, slot: bool) -> u8 {
    if kind == K_CALLOUT {
        return match opcode & 0xF00F {
            0x6002 => C_MOV_L_IND_RN,
            0x6001 => C_MOV_W_IND_RN,
            0x2002 => C_MOV_L_RM_IND,
            _ => match opcode & 0xFF00 {
                0xC500 => C_MOV_W_GBR_R0,
                _ => match opcode & 0xF000 {
                    0xD000 => C_MOV_L_PC_RN,
                    0x5000 => C_MOV_L_DISP_RN,
                    _ => 0,
                },
            },
        };
    }
    if opcode == 0x0009 {
        return H_NOP;
    }
    match opcode & 0xF00F {
        0x3000 => return H_CMP_EQ,
        0x2008 => return H_TST,
        0x6008 => return H_SWAP_B,
        0x6003 => return H_MOV,
        _ => {}
    }
    if opcode & 0xF0FF == 0x4010 {
        return H_DT;
    }
    match opcode >> 12 {
        0x7 => return H_ADD_IMM,
        0xE => return H_MOV_IMM,
        0xA if !slot => return H_BRA,
        _ => {}
    }
    match opcode >> 8 {
        0x88 => H_CMP_EQ_IMM,
        0xC8 => H_TST_IMM,
        0x89 if !slot => H_BT,
        0x8B if !slot => H_BF,
        0x8D if !slot => H_BT_S,
        0x8F if !slot => H_BF_S,
        _ => 0,
    }
}

// Backward branch window of the idle-loop detector (see `instructions::branch`)
const MAX_IDLE_BODY_BYTES: i32 = 24;

fn note_flag(disp: i32) -> u8 {
    if (-MAX_IDLE_BODY_BYTES..0).contains(&disp) { F_NOTE } else { 0 }
}

macro_rules! op {
    ($method:ident) => {
        (|cpu: &mut Sh2, op: &Uop| {
            cpu.$method(op.opcode);
            false
        }) as NativeFn
    };
    ($method:ident::<$n:literal>) => {
        (|cpu: &mut Sh2, op: &Uop| {
            cpu.$method::<$n>(op.opcode);
            false
        }) as NativeFn
    };
}

macro_rules! op0 {
    ($method:ident) => {
        (|cpu: &mut Sh2, _: &Uop| {
            cpu.$method();
            false
        }) as NativeFn
    };
}

/// Register-only instructions whose handlers read nothing but the register file (in particular
/// not `pc`/`next_pc`): safe anywhere, including delay slots
fn plain_register_op(opcode: u16) -> Option<NativeFn> {
    let f = match opcode & 0xF00F {
        0x6003 => op!(mov_rm_rn),
        0x6008 => op!(swap_b),
        0x6009 => op!(swap_w),
        0x200D => op!(xtrct),
        0x300C => op!(add_rm_rn),
        0x300E => op!(addc),
        0x300F => op!(addv),
        0x3000 => op!(cmp_eq_rm_rn),
        0x3002 => op!(cmp_hs),
        0x3003 => op!(cmp_ge),
        0x3006 => op!(cmp_hi),
        0x3007 => op!(cmp_gt),
        0x200C => op!(cmp_str),
        0x3004 => op!(div1),
        0x2007 => op!(div0s),
        0x300D => op!(dmuls),
        0x3005 => op!(dmulu),
        0x600E => op!(exts_b),
        0x600F => op!(exts_w),
        0x600C => op!(extu_b),
        0x600D => op!(extu_w),
        0x0007 => op!(mul),
        0x200F => op!(muls),
        0x200E => op!(mulu),
        0x600B => op!(neg),
        0x600A => op!(negc),
        0x3008 => op!(sub_rm_rn),
        0x300A => op!(subc),
        0x300B => op!(subv),
        0x2009 => op!(and_rm_rn),
        0x6007 => op!(not),
        0x200B => op!(or_rm_rn),
        0x2008 => op!(tst_rm_rn),
        0x200A => op!(xor_rm_rn),
        _ => match opcode & 0xF0FF {
            0x0029 => op!(movt),
            0x4011 => op!(cmp_pz),
            0x4015 => op!(cmp_pl),
            0x4010 => op!(dt),
            0x4004 => op!(rotl),
            0x4005 => op!(rotr),
            0x4024 => op!(rotcl),
            0x4025 => op!(rotcr),
            0x4020 | 0x4000 => op!(shll),
            0x4021 => op!(shar),
            0x4001 => op!(shlr),
            0x4008 => op!(shlln::<2>),
            0x4009 => op!(shlrn::<2>),
            0x4018 => op!(shlln::<8>),
            0x4019 => op!(shlrn::<8>),
            0x4028 => op!(shlln::<16>),
            0x4029 => op!(shlrn::<16>),
            0x400E => op!(ldc_rm_sr),
            0x401E => op!(ldc_rm_gbr),
            0x402E => op!(ldc_rm_vbr),
            0x400A => op!(lds_rm_mach),
            0x401A => op!(lds_rm_macl),
            0x402A => op!(lds_rm_pr),
            0x0002 => op!(stc_sr_rn),
            0x0012 => op!(stc_gbr_rn),
            0x0022 => op!(stc_vbr_rn),
            0x000A => op!(sts_mach_rn),
            0x001A => op!(sts_macl_rn),
            0x002A => op!(sts_pr_rn),
            _ => match opcode {
                0x0019 => op0!(div0u),
                0x0008 => op0!(clrt),
                0x0028 => op0!(clrmac),
                0x0009 => (|_: &mut Sh2, _: &Uop| false) as NativeFn,
                0x0018 => op0!(sett),
                _ => match opcode & 0xF000 {
                    0xE000 => op!(mov_b_immediate_rn),
                    0x7000 => op!(add_imm_rn),
                    _ => match opcode & 0xFF00 {
                        0x8800 => op!(cmp_eq_imm_r0),
                        0xC800 => op!(tst_imm_r0),
                        0xC900 => op!(and_imm_r0),
                        0xCA00 => op!(xor_imm_r0),
                        0xCB00 => op!(or_imm_r0),
                        _ => return None,
                    },
                },
            },
        },
    };
    Some(f)
}

fn i12(opcode: u16) -> i32 {
    i32::from(((opcode as i16) << 4) >> 4)
}

fn rn(opcode: u16) -> usize {
    usize::from((opcode >> 8) & 0xF)
}

/// Native micro-op for the instruction at `addr`, if it has one. Branches and `MOVA` read the
/// program counter, which is only static outside delay slots; in a slot they are call-outs.
fn native_op<Bus: BusInterface>(opcode: u16, addr: u32, slot: bool) -> Option<Native> {
    if let Some(f) = plain_register_op(opcode) {
        return Some((f, K_NATIVE, 1, 0, 0, 0));
    }
    if slot {
        return None;
    }

    // During the handler the interpreter's `next_pc` is the address after the next instruction
    let next_pc = addr.wrapping_add(4);
    let native: Native = match opcode >> 12 {
        0xA | 0xB => {
            // BRA / BSR label
            let disp = i12(opcode) << 1;
            let target = next_pc.wrapping_add(disp as u32);
            let f: NativeFn = if opcode >> 12 == 0xB {
                |cpu, op| {
                    cpu.registers.pr = op.addr.wrapping_add(4);
                    bra::<Bus>(cpu, op)
                }
            } else {
                bra::<Bus>
            };
            (f, K_DELAYED, 2, 0, target, note_flag(disp))
        }
        0x8 => {
            let disp = i32::from(opcode as u8 as i8) << 1;
            let target = next_pc.wrapping_add(disp as u32);
            let note = note_flag(disp);
            match (opcode >> 8) & 0xF {
                0x9 => (cond_branch::<Bus, true> as NativeFn, K_BRANCH, 1, 2, target, note),
                0xB => (cond_branch::<Bus, false> as NativeFn, K_BRANCH, 1, 2, target, note),
                0xD => (delayed_cond_branch::<Bus, true> as NativeFn, K_DELAYED, 1, 1, target, note),
                0xF => (delayed_cond_branch::<Bus, false> as NativeFn, K_DELAYED, 1, 1, target, note),
                _ => return None,
            }
        }
        0xC if (opcode >> 8) & 0xF == 0x7 => {
            // MOVA @(disp,PC),R0
            let value = (next_pc & !3).wrapping_add(u32::from(opcode & 0xFF) << 2);
            let f: NativeFn = |cpu, op| {
                cpu.registers.gpr[0] = op.imm;
                false
            };
            (f, K_NATIVE, 1, 0, value, 0)
        }
        0x0 => match opcode & 0xF0FF {
            // BSRF Rm / BRAF Rm
            0x0003 | 0x0023 => {
                let f: NativeFn = if opcode & 0xFF == 0x03 {
                    |cpu, op| {
                        cpu.registers.pr = op.addr.wrapping_add(4);
                        braf(cpu, op)
                    }
                } else {
                    braf
                };
                (f, K_DELAYED, 2, 0, next_pc, 0)
            }
            // RTS
            0x000B => {
                let f: NativeFn = |cpu, _| {
                    cpu.registers.next_pc = cpu.registers.pr;
                    cpu.registers.next_op_in_delay_slot = true;
                    true
                };
                (f, K_DELAYED, 2, 0, 0, 0)
            }
            _ => return None,
        },
        0x4 => match opcode & 0xF0FF {
            // JSR @Rm / JMP @Rm
            0x400B | 0x402B => {
                let f: NativeFn = if opcode & 0xFF == 0x0B {
                    |cpu, op| {
                        cpu.registers.pr = op.addr.wrapping_add(4);
                        jmp(cpu, op)
                    }
                } else {
                    jmp
                };
                (f, K_DELAYED, 2, 0, 0, 0)
            }
            _ => return None,
        },
        _ => return None,
    };
    Some(native)
}

// Static target: link 0
pub(crate) fn bra<Bus: BusInterface>(cpu: &mut Sh2, op: &Uop) -> bool {
    cpu.registers.next_pc = op.imm;
    cpu.registers.next_op_in_delay_slot = true;
    if op.flags & F_NOTE != 0 {
        cpu.note_backward_branch::<Bus>(op.imm);
    }
    false
}

// Dynamic target: link 1
fn braf(cpu: &mut Sh2, op: &Uop) -> bool {
    cpu.registers.next_pc = op.imm.wrapping_add(cpu.registers.gpr[rn(op.opcode)]);
    cpu.registers.next_op_in_delay_slot = true;
    true
}

// Dynamic target: link 1
fn jmp(cpu: &mut Sh2, op: &Uop) -> bool {
    cpu.registers.next_pc = cpu.registers.gpr[rn(op.opcode)];
    cpu.registers.next_op_in_delay_slot = true;
    true
}

pub(crate) fn cond_branch<Bus: BusInterface, const IF_T: bool>(cpu: &mut Sh2, op: &Uop) -> bool {
    if cpu.registers.sr.t != IF_T {
        return false;
    }
    cpu.registers.pc = op.imm;
    cpu.registers.next_pc = op.imm.wrapping_add(2);
    cpu.registers.next_op_in_delay_slot = false;
    if op.flags & F_NOTE != 0 {
        cpu.note_backward_branch::<Bus>(op.imm);
    }
    true
}

pub(crate) fn delayed_cond_branch<Bus: BusInterface, const IF_T: bool>(cpu: &mut Sh2, op: &Uop) -> bool {
    if cpu.registers.sr.t != IF_T {
        // Not taken: the next instruction is an ordinary one and the block ends after it
        cpu.registers.next_pc = op.addr.wrapping_add(4);
        return false;
    }
    cpu.registers.next_pc = op.imm;
    cpu.registers.next_op_in_delay_slot = true;
    if op.flags & F_NOTE != 0 {
        cpu.note_backward_branch::<Bus>(op.imm);
    }
    true
}

impl Sh2 {
    /// Records the block starting at `pc` from cache-resident code only, without side effects.
    ///
    /// Two regions qualify: cached space (`pc >> 29 == 0`, words that hit the cache) and the cache
    /// data array used as on-chip RAM (`pc >> 29 == 6`, Virtua Racing Deluxe runs code there).
    /// Data-array fetches have no side effects at all (no LRU update, no memo, no cycles); writes
    /// to the array bump the change counter of the storage line exactly like cache line changes,
    /// so both regions validate the same way.
    #[cold]
    #[inline(never)]
    pub(super) fn jit_form<Bus: BusInterface>(&self, pc: u32) -> Option<Block> {
        let region = pc >> 29;
        if region != 0 && region != 6 {
            return None;
        }
        let table = Bus::opcode_table();
        let mut block = Block::empty();
        block.start = pc;
        block.conf = self.cache.conf_gen();

        let mut ops = [EMPTY_UOP; MAX_OPS + 1];
        let mut addr = pc;
        let mut slot = false;
        loop {
            let n = usize::from(block.n);
            if n >= MAX_OPS && !slot {
                break;
            }
            if addr >> 29 != region {
                break;
            }
            let (way, entry, opcode) = if region == 0 {
                let Some(hit) = self.cache.peek_fetch(addr) else {
                    break;
                };
                hit
            } else {
                // Data array offset = way << 10 | entry << 4 | byte
                let offset = addr & 0xFFF;
                ((offset >> 10) as u8, ((offset >> 4) & 0x3F) as u8, self.cache.peek_data_array(addr))
            };

            let line_addr = addr & !0xF;
            let nlines = usize::from(block.nlines);
            let new_line = nlines == 0 || block.lines[nlines - 1].addr != line_addr;
            if new_line {
                if nlines == MAX_LINES {
                    break;
                }
                block.lines[nlines] = LineRef::new(line_addr, self.cache.line_gen(way, entry), way, entry);
                block.nlines += 1;
            }

            let mut flags = 0;
            if slot {
                flags |= F_SLOT;
            }
            if region == 0 && (n == 0 || new_line || ops[n - 1].kind == K_CALLOUT) {
                flags |= F_FETCH;
            }

            ops[n] = match native_op::<Bus>(opcode, addr, slot) {
                Some((f, kind, cost, extra, imm, native_flags)) => Uop {
                    f: f as usize,
                    addr,
                    imm,
                    opcode,
                    code: hot_code(opcode, kind, slot),
                    kind,
                    flags: flags | native_flags,
                    cost,
                    extra,
                    line: block.nlines - 1,
                    seg_end: 0,
                    guard: 0,
                },
                None => Uop {
                    f: handler_for::<Bus>(opcode, table) as usize,
                    addr,
                    imm: 0,
                    opcode,
                    code: hot_code(opcode, K_CALLOUT, slot),
                    kind: K_CALLOUT,
                    flags: flags | if is_register_only(opcode) { 0 } else { F_MEM },
                    cost: 1,
                    extra: 0,
                    line: block.nlines - 1,
                    seg_end: 0,
                    guard: 0,
                },
            };
            block.n += 1;
            addr = addr.wrapping_add(2);

            if slot {
                break;
            }
            match flow(opcode) {
                Flow::Continue => {}
                Flow::Delayed => slot = true,
                Flow::End => break,
            }
        }

        if block.n == 0 {
            return None;
        }
        block.ops = ops[..usize::from(block.n)].into();
        let last = &block.ops[usize::from(block.n) - 1];
        #[cfg(any(test, feature = "jit-arm64"))]
        {
            block.last_materialize = last.kind != K_CALLOUT
                || super::native::inline_read(last).is_some()
                || super::native::inline_write(last).is_some();
        }
        #[cfg(not(any(test, feature = "jit-arm64")))]
        {
            block.last_materialize = last.kind != K_CALLOUT;
        }
        compute_segments(&mut block);
        Some(block)
    }
}

/// Segments are maximal runs of non-call-out micro-ops; a `BT`/`BF` ends its segment. For every
/// micro-op, `guard` is the worst-case cost from it up to, excluding, the segment's last one.
fn compute_segments(block: &mut Block) {
    let n = usize::from(block.n);
    let mut i = 0;
    while i < n {
        if block.ops[i].kind == K_CALLOUT {
            block.ops[i].seg_end = (i + 1) as u8;
            i += 1;
            continue;
        }
        let mut end = i;
        while end < n && block.ops[end].kind != K_CALLOUT {
            end += 1;
            if block.ops[end - 1].kind == K_BRANCH {
                break;
            }
        }
        let worst = |op: &Uop| u16::from(op.cost) + u16::from(op.extra);
        let last = worst(&block.ops[end - 1]);
        let mut suffix = 0u16;
        for j in (i..end).rev() {
            suffix += worst(&block.ops[j]);
            block.ops[j].seg_end = end as u8;
            block.ops[j].guard = suffix - last;
        }
        i = end;
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::bus::AccessContext;

    // Formation only needs a bus type for its opcode table
    pub(crate) struct NoBus;

    /// Runs one instruction through the interpreter's handler (register-only instructions only:
    /// `NoBus` panics on any access)
    pub(crate) fn interpret(cpu: &mut Sh2, opcode: u16) {
        (NoBus::opcode_table().decode(opcode))(cpu, opcode, &mut NoBus);
    }

    impl BusInterface for NoBus {
        type DebugView<'a> = crate::debug::DummySh2Debugger;

        fn read<const SIZE: u8>(&mut self, _: u32, _: AccessContext) -> u32 {
            unreachable!()
        }
        fn read_cache_line(&mut self, _: u32, _: AccessContext) -> [u16; 8] {
            unreachable!()
        }
        fn write<const SIZE: u8>(&mut self, _: u32, _: u32, _: AccessContext) {
            unreachable!()
        }
        fn reset(&self) -> bool {
            false
        }
        fn interrupt_level(&self) -> u8 {
            0
        }
        fn dma_request_0(&self) -> bool {
            false
        }
        fn dma_request_1(&self) -> bool {
            false
        }
        fn acknowledge_dreq_1(&mut self) {}
        fn serial_rx(&mut self) -> Option<u8> {
            None
        }
        fn serial_tx(&mut self, _: u8) {}
        fn increment_cycle_counter(&mut self, _: u64) {}
        fn should_stop_execution(&self) -> bool {
            false
        }
        crate::impl_sh2_opcode_table!(NoBus);
    }

    fn form(program: &[u16]) -> Block {
        let mut cpu = Sh2::new("form-test".into());
        for (i, &op) in program.iter().enumerate() {
            cpu.cache.write_data_array_u16(0xC000_0000 + 2 * i as u32, op);
        }
        cpu.jit_form::<NoBus>(0xC000_0000).expect("block forms")
    }

    #[test]
    fn segments_costs_guards_and_targets() {
        let block = form(&[
            0x7001, // add #1,R0
            0x3010, // cmp/eq R1,R0
            0x8902, // bt +4 -> C000000C
            0x6012, // mov.l @R1,R0 (call-out)
            0x0009, // nop
            0xA000, // bra +0 -> C000000E
            0x0009, // nop (delay slot)
            0x0009, // beyond the block
        ]);
        assert_eq!(block.n, 7);
        let ops = &block.ops[..7];
        let kinds: Vec<u8> = ops.iter().map(|o| o.kind).collect();
        assert_eq!(kinds, [K_NATIVE, K_NATIVE, K_BRANCH, K_CALLOUT, K_NATIVE, K_DELAYED, K_NATIVE]);
        let costs: Vec<(u8, u8)> = ops.iter().map(|o| (o.cost, o.extra)).collect();
        assert_eq!(costs, [(1, 0), (1, 0), (1, 2), (1, 0), (1, 0), (2, 0), (1, 0)]);
        let seg_ends: Vec<u8> = ops.iter().map(|o| o.seg_end).collect();
        assert_eq!(seg_ends, [3, 3, 3, 4, 7, 7, 7]);
        // Worst case from each op up to, excluding, its segment's last op
        let guards: Vec<u16> = ops.iter().map(|o| o.guard).collect();
        assert_eq!(guards, [2, 1, 0, 0, 3, 2, 0]);
        assert_eq!(ops[2].imm, 0xC000_000C);
        assert_eq!(ops[5].imm, 0xC000_000E);
        assert_eq!(ops[6].flags & F_SLOT, F_SLOT);
        // Data-array code has no fetch side effects
        assert!(ops.iter().all(|o| o.flags & F_FETCH == 0));
        assert_eq!(block.static_cost(), 8);
        // A backward branch within the idle-loop window is flagged for the detector
        let block = form(&[0x0009, 0xAFFD, 0x0009]); // nop; bra -6 -> C0000000; nop
        assert_eq!(block.ops[1].imm, 0xC000_0000);
        assert_eq!(block.ops[1].flags & F_NOTE, F_NOTE);
    }

    #[test]
    fn delay_slot_pc_readers_are_call_outs() {
        // bra; mova (reads pc in the slot): call-out
        let block = form(&[0xA000, 0xC701]);
        assert_eq!(block.ops[1].kind, K_CALLOUT);
        // Outside a slot mova is native with its value resolved: (C0000000 + 4) & !3 + 1 * 4
        let block = form(&[0xC701, 0x000B, 0x0009]); // mova; rts; nop
        assert_eq!(block.ops[0].kind, K_NATIVE);
        assert_eq!(block.ops[0].imm, 0xC000_0008);
        assert_eq!(block.ops[1].kind, K_DELAYED);
        assert_eq!(block.n, 3);
    }
}
