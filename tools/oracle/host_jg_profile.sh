#!/usr/bin/env bash
# Host sampling profile of one jgenesis oracle suite (Windows x64, MSYS2 binutils).
# Usage: tools/oracle/host_jg_profile.sh <out-dir> <suite> [top-n]
# Builds the `profiling` cargo profile (release + debuginfo), runs the suite with --profile,
# and prints the hottest functions: "self" = innermost inlined frame, "fn" = containing symbol.
set -euo pipefail
export MSYS_NO_PATHCONV=1
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
source "$here/suites.sh"
out="$1"; suite="$2"; top="${3:-40}"
crate="$root/tools/jg-host-oracle"
[ -d /c/msys64/ucrt64/bin ] && export PATH="/c/msys64/ucrt64/bin:$PATH"
(cd "$crate" && cargo "+${TOOLCHAIN:-1.98.1-x86_64-pc-windows-gnu}" build --profile profiling --locked) >&2
exe="$(cygpath -m "$crate/target/profiling/jg-host-oracle.exe")"
mkdir -p "$out"; out="$(cygpath -m "$out")"
CONTENT="${JG_CONTENT:-E:/Sega}"; BIOS="${JG_BIOS:-$CONTENT/bios DreamCust}"
sys="$out/system"; mkdir -p "$sys"
cp -f "$BIOS"/32X_*_BIOS.bin "$BIOS/bios_CD_E.bin" "$sys/" 2>/dev/null || true
cp -f "$CONTENT/Sega-CD Model 2 BIOS V2.11 (U) [b1].bin" "$sys/bios_CD_U.bin" 2>/dev/null || true
eval "game=\${SUITE_${suite}_GAME} frames=\${SUITE_${suite}_FRAMES:-1200} input=\${SUITE_${suite}_INPUT:-}"
"$exe" --rom "$CONTENT/$game" --system "$sys" --frames "${FRAMES:-$frames}" --input "$input" \
  --label "$suite" --profile "$out/$suite.samples" | tail -1 >&2
base=$(objdump -p "$exe" | awk '/^ImageBase/ {print $2}')
awk -v b="$base" 'BEGIN { hb = strtonum("0x" b) } { printf "0x%x %d\n", hb + strtonum("0x" $1), $2 }' \
  "$out/$suite.samples" > "$out/$suite.vma"
cut -d' ' -f1 "$out/$suite.vma" | addr2line -f -i -C -a -e "$exe" > "$out/$suite.sym"
# addr2line -a: "0xADDR" line, then (func, file:line) pairs innermost first.
awk 'NR == FNR { n[$1] = $2; total += $2; next }
     /^0x/ { addr = sprintf("0x%x", strtonum($0)); cur = n[addr]; depth = 0; outer = ""; next }
     { if ((FNR - 1) % 1 == 0 && $0 !~ /^[A-Za-z]:|^\?\?:|:[0-9?]+( \(discriminator [0-9]+\))?$/) {
         if (depth == 0) selfc[$0] += cur
         last[addr] = $0; depth++ } }
     END {
       for (a in last) fn[last[a]] += n[a]
       printf "total samples %d\n--- self (innermost inlined frame) ---\n", total
       for (k in selfc) printf "%6.2f%% %s\n", 100 * selfc[k] / total, k | "sort -rn | head -'"$top"'"
       close("sort -rn | head -'"$top"'")
       printf "--- containing (outermost) function ---\n"
       for (k in fn) printf "%6.2f%% %s\n", 100 * fn[k] / total, k | "sort -rn | head -'"$top"'"
     }' "$out/$suite.vma" "$out/$suite.sym"
