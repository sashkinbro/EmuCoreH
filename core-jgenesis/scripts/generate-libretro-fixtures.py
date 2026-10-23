"""Generate original, redistributable synthetic Sega smoke content (no firmware)."""
import argparse
import struct
from pathlib import Path


def genesis_rom():
    rom = bytearray(32768)
    rom[:8] = struct.pack(">II", 0x00FFFFF0, 0x200)
    rom[0x100:0x110] = b"SEGA MEGA DRIVE "
    rom[0x120:0x150] = b"EMUCORE SYNTHETIC SMOKE TEST".ljust(48)
    rom[0x1F0] = ord("U")
    program = bytearray.fromhex("23fc c0000000 00c00004")
    for value, address in [(0x000E, 0xC00000), (0x8700, 0xC00004), (0x8144, 0xC00004)]:
        program += bytes.fromhex("33fc") + struct.pack(">HI", value, address)
    for value in [0x80, 0x10, 0x90]:
        program += bytes.fromhex("13fc") + struct.pack(">HI", value, 0xC00011)
    program += bytes.fromhex("4e72 2700 60fa")
    rom[0x200:0x200 + len(program)] = program
    return rom


def smsgg_rom():
    rom = bytearray(32768)
    program = bytearray.fromhex("f3 31 f0df")
    for port, value in [(0xBF, 0), (0xBF, 0x80), (0xBF, 0x40), (0xBF, 0x81),
                        (0xBF, 2), (0xBF, 0x87), (6, 0x3F),
                        (0x7F, 0x80), (0x7F, 0x10), (0x7F, 0x90)]:
        program += bytes([0x3E, value, 0xD3, port])
    program += bytes.fromhex("18fe")
    rom[:len(program)] = program
    return rom


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    output = parser.parse_args().output
    output.mkdir(parents=True, exist_ok=True)
    for extension in ["md", "32x"]:
        (output / f"smoke.{extension}").write_bytes(genesis_rom())
    for extension in ["sms", "gg", "sg"]:
        (output / f"smoke.{extension}").write_bytes(smsgg_rom())
    disc = bytearray(2048 * 16)
    disc[:14] = b"SEGADISCSYSTEM"
    (output / "disc.bin").write_bytes(disc)
    cue = 'FILE "disc.bin" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n'
    (output / "disc.cue").write_text(cue, encoding="ascii")
    (output / "disc2.bin").write_bytes(disc)
    (output / "disc2.cue").write_text(cue.replace("disc.bin", "disc2.bin"), encoding="ascii")
    bios = bytearray(128 * 1024)
    bios[:8] = struct.pack(">II", 0x00FFFFF0, 0x200)
    bios[0x200:0x206] = bytes.fromhex("4e72 2700 60fa")
    (output / "bios_CD_U.bin").write_bytes(bios)
    (output / "README.txt").write_text(
        "Original EmuCoreH synthetic CPU programs and sectors. No Sega firmware or retail content.\n"
        "32X file exercises the Genesis compatibility path within 32X hardware, not SH-2/PWM gameplay.\n"
        "Sega CD BIOS is a dummy 68000 STOP program; disc sectors contain only a synthetic header.\n",
        encoding="ascii")
    print(output.resolve())


if __name__ == "__main__":
    main()
