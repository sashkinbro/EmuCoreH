#!/usr/bin/env python3
"""Generate BIOS metadata from the bundled Flycast table; no firmware is read or bundled."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / "core/core/hw/naomi/naomi_roms.cpp").read_text()
source = source[source.index("const BIOS_t BIOS[]"):source.index("const Game Games[]")]
source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
source = re.sub(r"//[^\n]*", "", source)
blocks, depth, start = [], 0, 0
for i, char in enumerate(source):
    if char == "{":
        depth += 1
        if depth == 2: start = i
    elif char == "}":
        if depth == 2: blocks.append(source[start:i + 1])
        depth -= 1
rows = []
for block in blocks:
    name = re.match(r'\{\s*"([^"]+)"', block)
    if not name: continue
    parent = re.search(r'\}\s*,\s*"([^"]+)"\s*,?\s*\}$', block)
    archive = parent[1] if parent else name[1]
    for filename, size, crc in re.findall(
        r'\{\s*\d+,\s*"([^"]+)"\s*,\s*0x[0-9a-fA-F]+\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)', block):
        row = archive, filename, int(size, 16), int(crc, 16)
        if row not in rows: rows.append(row)
# Flycast maps 64 KiB of an Atomiswave chip, but MAME archives contain
# its full 128 KiB. The alternative dumps are listed alongside BIOS_t.
for crc in (0x719b2b0b, 0xd3e80a9f, 0x82a105f0):
    rows.append(("awbios", "bios0.ic23", 131072, crc))
text = """package com.sbro.emucoreh.core

/** Generated from core/core/hw/naomi/naomi_roms.cpp BIOS metadata; no firmware bytes.
 * Regenerate: python core-android/tools/generate_bios_fingerprints.py
 * Archive aliases follow BIOS_t.filename, exactly as the core loader does.
 */
internal object ArcadeBiosFingerprints {
    data class Rom(val archive: String, val name: String, val size: Int, val crc: Long)
    val roms = listOf(
"""
for archive, name, size, crc in rows:
    text += f'        Rom("{archive}", "{name}", {size}, 0x{crc:08x}L),\n'
text += "    )\n}\n"
(ROOT / "app/src/main/java/com/sbro/emucoreh/core/ArcadeBiosFingerprints.kt").write_text(text, encoding="utf-8")
print(f"Generated {len(rows)} BIOS fingerprints")
