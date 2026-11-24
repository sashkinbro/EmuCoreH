#!/usr/bin/env bash
# Host-side jgenesis regression oracle: runs the device $JG_ALL suites (tools/oracle/suites.sh)
# through tools/jg-host-oracle (links core-jgenesis/frontend/jgenesis-libretro directly).
#
# Usage:
#   tools/oracle/host_jg.sh build                       # cargo build --release of the host oracle
#   tools/oracle/host_jg.sh run <out-dir> [suite...]    # default suites: $JG_ALL
#   tools/oracle/host_jg.sh compare <baseline-dir> <candidate-dir>
#
# Env:
#   JG_CONTENT  ROM folder (default E:/Sega)            JG_BIOS  BIOS folder (default "$JG_CONTENT/bios DreamCust")
#   RUNS        processes per suite (default 3); digest must agree across runs, ms = median
#   EVERY       checkpoint interval (default 300, like the device oracle)
#   JOBS        suites run in parallel (default 1; >1 is fine for hash checks, not for timing)
#   BIN         oracle binary (default tools/jg-host-oracle/target/release/jg-host-oracle.exe)
#   NO_BUILD=1  skip the implicit build in `run`
#   TOOLCHAIN   cargo toolchain (default 1.98.1-x86_64-pc-windows-gnu on Windows, else 1.98.1)
#
# Output per suite: <out>/<suite>.r<k>.json + .log; <out>/summary.txt lines:
#   <suite> <digest> <median_ms_per_frame> <runs> <OK|NONDET|FAIL>
# compare prints MATCH/DIFF per suite plus speed ratio (baseline_ms / candidate_ms, >1 = faster).
set -uo pipefail
export MSYS_NO_PATHCONV=1
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck source=suites.sh
source "$here/suites.sh"
crate="$root/tools/jg-host-oracle"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) exe=.exe; tc_default=1.98.1-x86_64-pc-windows-gnu;; *) exe=; tc_default=1.98.1;; esac
BIN="${BIN:-$crate/target/release/jg-host-oracle$exe}"
CONTENT="${JG_CONTENT:-E:/Sega}"
BIOS="${JG_BIOS:-$CONTENT/bios DreamCust}"
RUNS="${RUNS:-3}"
EVERY="${EVERY:-300}"
JOBS="${JOBS:-1}"

build() {
  [ -d /c/msys64/ucrt64/bin ] && export PATH="/c/msys64/ucrt64/bin:$PATH"
  (cd "$crate" && cargo "+${TOOLCHAIN:-$tc_default}" build --release --locked) >&2
}

# System directory with the same file names the app installs (never committed: lives in out dir).
make_system() {
  local sys="$1"
  mkdir -p "$sys"
  for f in 32X_G_BIOS.bin 32X_M_BIOS.bin 32X_S_BIOS.bin; do
    [ -f "$BIOS/$f" ] && cp -f "$BIOS/$f" "$sys/$f"
  done
  [ -f "$BIOS/bios_CD_E.bin" ] && cp -f "$BIOS/bios_CD_E.bin" "$sys/bios_CD_E.bin"
  local us
  for us in "$CONTENT/bios_CD_U.bin" "$BIOS/bios_CD_U.bin" "$CONTENT/Sega-CD Model 2 BIOS V2.11 (U) [b1].bin" "$BIOS/Sega-CD Model 2 BIOS V2.11 (U) [b1].bin"; do
    if [ -f "$us" ]; then cp -f "$us" "$sys/bios_CD_U.bin"; break; fi
  done
  for us in "$CONTENT/bios_CD_J.bin" "$BIOS/bios_CD_J.bin"; do
    if [ -f "$us" ]; then cp -f "$us" "$sys/bios_CD_J.bin"; break; fi
  done
}

run_suite() {
  local out="$1" suite="$2" game frames input k digests ms status
  eval "game=\${SUITE_${suite}_GAME:-} frames=\${SUITE_${suite}_FRAMES:-1200} input=\${SUITE_${suite}_INPUT:-}"
  if [ -z "$game" ]; then echo "$suite - 0 0 FAIL" > "$out/$suite.summary"; return; fi
  digests=(); ms=()
  for ((k = 0; k < RUNS; k++)); do
    "$BIN" --rom "$CONTENT/$game" --system "$out/system" --frames "$frames" --every "$EVERY" \
      --input "$input" --label "$suite" --json "$out/$suite.r$k.json" \
      --save "$out/save-$suite-$k" > "$out/$suite.r$k.log" 2>&1
    local line
    line="$(grep '^ORACLE_SUMMARY' "$out/$suite.r$k.log")"
    if [ -z "$line" ]; then
      # Expected boot rejections (e.g. protected Aleste Collection image) are a stable result too.
      line="$(grep '^ORACLE_ERROR' "$out/$suite.r$k.log" | sed -E 's/.*boot failed: ([A-Z0-9_]+).*/\1/')"
      if [[ "$line" =~ ^[A-Z0-9_]+$ ]]; then digests+=("BOOT:$line"); ms+=(0); else digests+=(FAIL); fi
      continue
    fi
    digests+=("$(sed -E 's/.* digest=([0-9a-f]+).*/\1/' <<<"$line")")
    ms+=("$(sed -E 's/.* ms_per_frame=([0-9.]+).*/\1/' <<<"$line")")
  done
  status=OK
  for d in "${digests[@]}"; do
    [ "$d" = FAIL ] && status=FAIL
    [ "$d" != "${digests[0]}" ] && [ "$status" = OK ] && status=NONDET
  done
  local median
  median="$(printf '%s\n' "${ms[@]:-0}" | sort -g | awk '{a[NR]=$1} END {print (NR%2) ? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2}')"
  echo "$suite ${digests[0]} $median $RUNS $status" > "$out/$suite.summary"
  echo "$suite ${digests[0]:0:16} ${median}ms/frame $status" >&2
}

cmd="${1:-}"; shift || true
case "$cmd" in
  build) build ;;
  run)
    out="${1:?out dir}"; shift
    # Native (non-MSYS) binary: hand it Windows-style paths.
    command -v cygpath >/dev/null 2>&1 && { mkdir -p "$out"; out="$(cygpath -m "$out")"; }
    suites=("$@"); [ ${#suites[@]} -eq 0 ] && read -r -a suites <<<"$JG_ALL"
    [ "${NO_BUILD:-0}" = 1 ] || build || exit 1
    [ -x "$BIN" ] || { echo "missing $BIN" >&2; exit 1; }
    mkdir -p "$out"; make_system "$out/system"
    if [ "$JOBS" -gt 1 ]; then
      for s in "${suites[@]}"; do
        while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done
        run_suite "$out" "$s" &
      done
      wait
    else
      for s in "${suites[@]}"; do run_suite "$out" "$s"; done
    fi
    : > "$out/summary.txt"
    for s in "${suites[@]}"; do cat "$out/$s.summary" >> "$out/summary.txt"; done
    rm -rf "$out"/save-* "$out/system"
    echo "wrote $out/summary.txt" >&2
    ;;
  compare)
    base="${1:?baseline dir}"; cand="${2:?candidate dir}"
    awk 'NR == FNR { d[$1] = $2; m[$1] = $3; s[$1] = $5; next }
         {
           if (!($1 in d)) { printf "%-14s MISSING-IN-BASELINE\n", $1; bad++; next }
           r = ($3 > 0) ? m[$1] / $3 : 0
           st = ($2 == d[$1] && $5 == "OK" && s[$1] == "OK") ? "MATCH" : "DIFF"
           if (st != "MATCH") bad++
           printf "%-14s %-5s base=%8.4f cand=%8.4f ms/frame  speedup=%.3fx%s\n", $1, st, m[$1], $3, r, ($5 != "OK" ? " (" $5 ")" : "")
           if (m[$1] > 0 && $3 > 0) { lb += log(m[$1]); lc += log($3); n++ }
         }
         END {
           if (n) printf "geomean speedup %.3fx over %d suites, %d not matching\n", exp((lb - lc) / n), n, bad
           exit bad ? 1 : 0
         }' "$base/summary.txt" "$cand/summary.txt"
    ;;
  *) sed -n '2,22p' "$0"; exit 1 ;;
esac
