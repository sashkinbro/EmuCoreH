//! Typed primitive callbacks for the native MOVE/EA translator. This module never
//! decodes or executes an instruction; all EA sequencing and flags live in A64.
use super::*;
use crate::native::memory::Context;
struct Runtime<'a, 'cpu, 'bus, B: BusInterface> {
    executor: &'a mut InstructionExecutor<'cpu, 'bus, B>,
    exception: Option<Exception>,
}
unsafe fn runtime<'a, 'cpu, 'bus, B: BusInterface>(
    c: *mut Context,
) -> &'a mut Runtime<'a, 'cpu, 'bus, B> {
    unsafe { &mut *((*c).opaque.cast()) }
}
fn result<T: Default>(
    context: *mut Context,
    runtime: &mut Runtime<'_, '_, '_, impl BusInterface>,
    result: ExecuteResult<T>,
) -> T {
    match result {
        Ok(v) => v,
        Err(e) => {
            runtime.exception = Some(e);
            unsafe {
                (*context).error = 1;
            }
            T::default()
        }
    }
}
unsafe extern "C" fn fetch<B: BusInterface>(c: *mut Context) -> u32 {
    let r = unsafe { runtime::<B>(c) };
    let v = r.executor.fetch_operand();
    u32::from(result(c, r, v))
}
unsafe extern "C" fn read<B: BusInterface>(c: *mut Context, address: u32, size: u32) -> u32 {
    let r = unsafe { runtime::<B>(c) };
    let ea = ResolvedAddress::Memory(address);
    let v = match size {
        1 => Ok(u32::from(r.executor.read_byte_resolved(ea))),
        2 => r.executor.read_word_resolved(ea).map(u32::from),
        4 => r.executor.read_long_word_resolved(ea),
        _ => unreachable!(),
    };
    result(c, r, v)
}
unsafe extern "C" fn write<B: BusInterface>(c: *mut Context, address: u32, value: u32, size: u32) {
    let r = unsafe { runtime::<B>(c) };
    let ea = ResolvedAddress::Memory(address);
    let v = match size {
        1 => {
            r.executor.write_byte_resolved(ea, value as u8);
            Ok(())
        }
        2 => r.executor.write_word_resolved(ea, value as u16),
        4 => r.executor.write_long_word_resolved(ea, value),
        _ => unreachable!(),
    };
    result(c, r, v);
}
unsafe extern "C" fn read_register<B: BusInterface>(c: *mut Context, index: u32) -> u32 {
    let r = unsafe { runtime::<B>(c) };
    let regs = &r.executor.cpu.registers;
    match index {
        0..=7 => DataRegister(index as u8).read_from(regs),
        8..=15 => AddressRegister((index - 8) as u8).read_from(regs),
        16 => regs.pc,
        _ => unreachable!(),
    }
}
unsafe extern "C" fn write_register<B: BusInterface>(
    c: *mut Context,
    index: u32,
    value: u32,
    size: u32,
) {
    let r = unsafe { runtime::<B>(c) };
    let regs = &mut r.executor.cpu.registers;
    if index < 8 {
        let d = DataRegister(index as u8);
        match size {
            1 => d.write_byte_to(regs, value as u8),
            2 => d.write_word_to(regs, value as u16),
            4 => d.write_long_word_to(regs, value),
            _ => unreachable!(),
        }
    } else {
        let a = AddressRegister((index - 8) as u8);
        match size {
            2 => a.write_word_to(regs, value as u16),
            4 => a.write_long_word_to(regs, value),
            _ => unreachable!(),
        }
    }
}
unsafe extern "C" fn set_ccr<B: BusInterface>(c: *mut Context, value: u32) {
    let r = unsafe { runtime::<B>(c) };
    r.executor.cpu.registers.ccr = (value as u8).into();
}
unsafe extern "C" fn jump<B: BusInterface>(c: *mut Context, address: u32) {
    let r = unsafe { runtime::<B>(c) };
    let v = r.executor.jump_to_address(address);
    result(c, r, v);
}
unsafe extern "C" fn status<B: BusInterface>(c: *mut Context, op: u32, value: u32) -> u32 {
    let r = unsafe { runtime::<B>(c) };
    let regs = &mut r.executor.cpu.registers;
    match op {
        0 => regs.status_register().into(),
        1 => {
            regs.set_status_register(value as u16);
            0
        }
        2 => regs.usp,
        3 => {
            regs.usp = value;
            0
        }
        4 => {
            regs.stopped = true;
            0
        }
        _ => unreachable!(),
    }
}
unsafe extern "C" fn raise<B: BusInterface>(c: *mut Context, kind: u32, value: u32) {
    let r = unsafe { runtime::<B>(c) };
    r.exception = Some(match kind {
        0 => Exception::PrivilegeViolation,
        1 => Exception::IllegalInstruction(value as u16),
        2 => Exception::Trap(value),
        3 => Exception::DivisionByZero { cycles: value },
        4 => Exception::CheckRegister { cycles: value },
        _ => unreachable!(),
    });
    unsafe {
        (*c).error = 1;
    }
}
impl<B: BusInterface> InstructionExecutor<'_, '_, B> {
    pub(super) fn try_native_memory(
        &mut self,
        native: &mut crate::native::NativeExecutor,
        opcode: u16,
    ) -> Option<ExecuteResult<u32>> {
        use crate::native::memory::Op;
        let cycles = if let Some((op, size, source, dest)) = crate::native::memory::plan(opcode) {
            // Register-only instructions keep the compact leaf ABI.
            if source.is_data_direct() && dest.is_data_direct() {
                return None;
            }
            if matches!(source, AddressingMode::Quick(_))
                && dest.is_data_direct()
                && matches!(op, Op::Add | Op::Sub)
            {
                return None;
            }
            match op {
                Op::Move => {
                    (if matches!(dest, AddressingMode::AddressIndirectPredecrement(..)) {
                        2
                    } else {
                        4
                    }) + source.address_calculation_cycles(size)
                        + dest.address_calculation_cycles(size)
                }
                Op::Cmp if dest.is_address_direct() => 6 + source.address_calculation_cycles(size),
                Op::Cmp => instructions::cycles_if_move_btst_cmp(opcode).expect("CMP timing"),
                Op::Clear | Op::Not | Op::Neg => instructions::unary_op_cycles(size, dest),
                Op::Test => 4 + dest.address_calculation_cycles(size),
                _ => instructions::binary_op_cycles(size, source, dest),
            }
        } else {
            crate::native::memory::control::base_cycles(opcode)
                .or_else(|| crate::native::memory::shift::base_cycles(opcode))
                .or_else(|| crate::native::memory::misc::base_cycles(opcode))
                .or_else(|| crate::native::memory::extended::base_cycles(opcode))
                .or_else(|| crate::native::memory::transfer::base_cycles(opcode))
                .or_else(|| crate::native::memory::special::base_cycles(opcode))
                .or_else(|| crate::native::memory::divide::base_cycles(opcode))?
        };
        let mut runtime = Runtime { executor: self, exception: None };
        let mut context = make_context(&mut runtime, cycles);
        if !native.execute_memory(opcode, &mut context) {
            return None;
        }
        Some(match runtime.exception {
            Some(e) => Err(e),
            None => Ok(context.cycles),
        })
    }
}

fn make_context<B: BusInterface>(runtime: &mut Runtime<'_, '_, '_, B>, cycles: u32) -> Context {
    Context {
        fetch: fetch::<B>,
        read: read::<B>,
        write: write::<B>,
        read_register: read_register::<B>,
        write_register: write_register::<B>,
        set_ccr: set_ccr::<B>,
        error: 0,
        ccr: u32::from(u8::from(runtime.executor.cpu.registers.ccr)),
        opaque: std::ptr::from_mut(runtime).cast(),
        jump: jump::<B>,
        cycles,
        allow_tas_writes: u32::from(runtime.executor.cpu.allow_tas_writes),
        status: status::<B>,
        raise: raise::<B>,
        supervisor: u32::from(runtime.executor.cpu.registers.supervisor_mode),
    }
}
#[cfg(feature = "jit-runtime")]
fn native_cycles(opcode: u16) -> Option<u32> {
    use crate::native::memory::Op;
    if let Some((op, size, source, dest)) = crate::native::memory::plan(opcode) {
        Some(match op {
            Op::Move => {
                (if matches!(dest, AddressingMode::AddressIndirectPredecrement(..)) {
                    2
                } else {
                    4
                }) + source.address_calculation_cycles(size)
                    + dest.address_calculation_cycles(size)
            }
            Op::Cmp if dest.is_address_direct() => 6 + source.address_calculation_cycles(size),
            Op::Cmp => instructions::cycles_if_move_btst_cmp(opcode)?,
            Op::Clear | Op::Not | Op::Neg => instructions::unary_op_cycles(size, dest),
            Op::Test => 4 + dest.address_calculation_cycles(size),
            _ => instructions::binary_op_cycles(size, source, dest),
        })
    } else {
        crate::native::memory::control::base_cycles(opcode)
            .or_else(|| crate::native::memory::shift::base_cycles(opcode))
            .or_else(|| crate::native::memory::misc::base_cycles(opcode))
            .or_else(|| crate::native::memory::extended::base_cycles(opcode))
            .or_else(|| crate::native::memory::transfer::base_cycles(opcode))
            .or_else(|| crate::native::memory::special::base_cycles(opcode))
            .or_else(|| crate::native::memory::divide::base_cycles(opcode))
            .or_else(|| match instructions::Instruction::decode_from(opcode) {
                instructions::Instruction::NoOp
                | instructions::Instruction::MoveQuick(..)
                | instructions::Instruction::Swap(..)
                | instructions::Instruction::Extend(..) => Some(4),
                _ => None,
            })
    }
}
#[cfg(feature = "jit-runtime")]
pub(super) mod block;
