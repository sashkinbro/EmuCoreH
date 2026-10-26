//! Effective addresses and register updates are generated instructions. Typed helpers
//! perform only individual data accesses through the normal cache/MMIO/bus path.
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
use super::Context;
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(super) struct Runtime<B: crate::bus::BusInterface> {
    pub cpu: *mut crate::Sh2,
    pub bus: *mut B,
    pub fast: *const super::fast::State,
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
unsafe extern "C" fn unused_read(_: *mut std::ffi::c_void, _: u32, _: u32) -> u32 {
    unreachable!()
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
unsafe extern "C" fn unused_write(_: *mut std::ffi::c_void, _: u32, _: u32, _: u32) {
    unreachable!()
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
unsafe extern "C" fn unused_cycles(_: *mut std::ffi::c_void, _: u32) {
    unreachable!()
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(super) fn empty_context() -> Context {
    Context {
        gpr: std::ptr::null_mut(),
        t: std::ptr::null_mut(),
        runtime: std::ptr::null_mut(),
        read: unused_read,
        write: unused_write,
        next_pc: std::ptr::null_mut(),
        gbr: std::ptr::null_mut(),
        pc: std::ptr::null_mut(),
        delay: std::ptr::null_mut(),
        pr: std::ptr::null_mut(),
        cycles: unused_cycles,
        mach: std::ptr::null_mut(),
        macl: std::ptr::null_mut(),
        m: std::ptr::null_mut(),
        q: std::ptr::null_mut(),
        s: std::ptr::null_mut(),
        mask: std::ptr::null_mut(),
        vbr: std::ptr::null_mut(),
    }
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(super) fn context<B: crate::bus::BusInterface>(r: &mut Runtime<B>) -> Context {
    unsafe extern "C" fn read<B: crate::bus::BusInterface>(
        r: *mut std::ffi::c_void,
        a: u32,
        s: u32,
    ) -> u32 {
        let r = unsafe { &mut *r.cast::<Runtime<B>>() };
        let (cpu, bus) = unsafe { (&mut *r.cpu, &mut *r.bus) };
        super::fast::synchronize(cpu, r.fast);
        match s {
            0 => u32::from(cpu.read_byte(a, bus)),
            1 => u32::from(cpu.read_word(a, bus)),
            3 => u32::from(bus.read_byte(a & crate::EXTERNAL_ADDRESS_MASK, cpu.data_ctx)),
            _ => cpu.read_longword(a, bus),
        }
    }
    unsafe extern "C" fn write<B: crate::bus::BusInterface>(
        r: *mut std::ffi::c_void,
        a: u32,
        v: u32,
        s: u32,
    ) {
        let r = unsafe { &mut *r.cast::<Runtime<B>>() };
        let (cpu, bus) = unsafe { (&mut *r.cpu, &mut *r.bus) };
        super::fast::synchronize(cpu, r.fast);
        match s {
            0 => cpu.write_byte(a, v as u8, bus),
            1 => cpu.write_word(a, v as u16, bus),
            _ => cpu.write_longword(a, v, bus),
        }
    }
    unsafe extern "C" fn cycles<B: crate::bus::BusInterface>(r: *mut std::ffi::c_void, c: u32) {
        let r = unsafe { &mut *r.cast::<Runtime<B>>() };
        super::fast::synchronize(unsafe { &mut *r.cpu }, r.fast);
        unsafe { &mut *r.bus }.increment_cycle_counter(u64::from(c));
    }
    let cpu = unsafe { &mut *r.cpu };
    Context {
        gpr: cpu.registers.gpr.as_mut_ptr(),
        t: &mut cpu.registers.sr.t,
        runtime: (r as *mut Runtime<B>).cast(),
        read: read::<B>,
        write: write::<B>,
        next_pc: &mut cpu.registers.next_pc,
        gbr: &mut cpu.registers.gbr,
        pc: &mut cpu.registers.pc,
        delay: &mut cpu.registers.next_op_in_delay_slot,
        pr: &mut cpu.registers.pr,
        cycles: cycles::<B>,
        mach: &mut cpu.registers.mach,
        macl: &mut cpu.registers.macl,
        m: &mut cpu.registers.sr.m,
        q: &mut cpu.registers.sr.q,
        s: &mut cpu.registers.sr.s,
        mask: &mut cpu.registers.sr.interrupt_mask,
        vbr: &mut cpu.registers.vbr,
    }
}
pub(super) fn emit(op: u16) -> Option<Vec<u32>> {
    let n = u32::from((op >> 8) & 15);
    let m = u32::from((op >> 4) & 15);
    let load = |r: u32, s: u32| 0xB9400280 | (r << 10) | s; // [x20,#r*4]
    let store = |r: u32, s: u32| 0xB9000280 | (r << 10) | s;
    let mut c = vec![0xA9BE53F3, 0xA9017BF5, 0xAA0003F3, 0xF9400014]; // save x19..21/LR, context and GPR
    let size;
    let read;
    let dst;
    let mut post = None;
    let mut pre = None;
    match op & 0xF00F {
        0x6000 | 0x6001 | 0x6002 | 0x6004 | 0x6005 | 0x6006 => {
            size = u32::from(op & 3);
            read = true;
            dst = n;
            c.push(load(m, 1));
            if op & 4 != 0 {
                post = Some(m);
            }
        }
        0x2000 | 0x2001 | 0x2002 | 0x2004 | 0x2005 | 0x2006 => {
            size = u32::from(op & 3);
            read = false;
            dst = 0;
            c.extend([load(n, 1), load(m, 2)]);
            if op & 4 != 0 {
                pre = Some(n);
            }
        }
        0x0004 | 0x0005 | 0x0006 | 0x000C | 0x000D | 0x000E => {
            size = u32::from(op & 3);
            read = op & 8 != 0;
            dst = n;
            c.extend([load(if read { m } else { n }, 1), load(0, 3), 0x0B030021]);
            if !read {
                c.push(load(m, 2));
            }
        }
        _ => match op & 0xF000 {
            0x1000 | 0x5000 => {
                size = 2;
                read = op & 0xF000 == 0x5000;
                dst = n;
                c.push(load(if read { m } else { n }, 1));
                c.push(0x11000021 | (u32::from(op & 15) * 4 << 10));
                if !read {
                    c.push(load(m, 2));
                }
            }
            0x9000 | 0xD000 => {
                size = if op & 0xF000 == 0x9000 { 1 } else { 2 };
                read = true;
                dst = n;
                c.extend([0xF9401661, 0xB9400021]);
                if size == 2 {
                    c.push(0x121E7421);
                }
                c.push(0x11000021 | ((u32::from(op & 255) << size) << 10));
            }
            0x8000 => {
                let selector = op & 0x0F00;
                if !matches!(selector, 0x000 | 0x100 | 0x400 | 0x500) {
                    return None;
                }
                size = u32::from((op >> 8) & 1);
                read = op & 0x0400 != 0;
                dst = 0;
                c.push(load(m, 1));
                c.push(0x11000021 | ((u32::from(op & 15) << size) << 10));
                if !read {
                    c.push(load(0, 2));
                }
            }
            0xC000 => {
                let selector = (op >> 8) & 15;
                if !matches!(selector, 0 | 1 | 2 | 4 | 5 | 6 | 7) {
                    return None;
                }
                if selector == 7 {
                    c.extend([
                        0xF9401661,
                        0xB9400021,
                        0x121E7421,
                        0x11000021 | ((u32::from(op & 255) * 4) << 10),
                        store(0, 1),
                        0xA9417BF5,
                        0xA8C253F3,
                        0xD65F03C0,
                    ]);
                    return Some(c);
                }
                size = u32::from(selector & 3);
                read = selector & 4 != 0;
                dst = 0;
                c.extend([
                    0xF9401A61,
                    0xB9400021,
                    0x11000021 | ((u32::from(op & 255) << size) << 10),
                ]);
                if !read {
                    c.push(load(0, 2));
                }
            }
            _ => return None,
        },
    }
    if let Some(r) = pre {
        c.push(0x51000021 | ((1 << size) << 10));
        c.push(store(r, 1));
    }
    if read {
        // x21 retains postincrement address across a possibly reentrant memory helper.
        if post.is_some() {
            c.push(0x2A0103F5);
        }
        c.extend([0xF9400A60, 0x52800002 | (size << 5), 0xF9400E70, 0xD63F0200]);
        if let Some(r) = post {
            c.extend([0x110002A1 | ((1 << size) << 10), store(r, 1)]);
        }
        c.push(0x2A0003E1);
        if size != 2 {
            c.push(if size == 0 { 0x13001C21 } else { 0x13003C21 });
        }
        c.push(store(dst, 1));
    } else {
        c.extend([0xF9400A60, 0x52800003 | (size << 5), 0xF9401270, 0xD63F0200]);
    }
    c.extend([0xA9417BF5, 0xA8C253F3, 0xD65F03C0]);
    Some(c)
}
