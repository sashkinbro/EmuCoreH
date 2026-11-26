#!/usr/bin/env bash
# Host-side Model 3 (Supermodel) regression oracle and benchmark: runs the device m3/vf3 suites
# (tools/oracle/suites.sh) headless through tools/m3-host-oracle (the core sources built for the
# host with the Android code paths; Real3D scene built on the CPU and hashed, no GPU).
#
# Usage:
#   tools/oracle/host_m3.sh build                         # configure + build into $BUILD
#   tools/oracle/host_m3.sh baseline <git-ref> <dir>      # build <git-ref> core into <dir>; binary: <dir>/build/m3-host-oracle.exe
#   tools/oracle/host_m3.sh run <out-dir> [suite...]      # default suites: m3 vf3
#   tools/oracle/host_m3.sh compare <baseline-dir> <candidate-dir>
#   tools/oracle/host_m3.sh bench <base-bin> <cand-bin> [suite...]   # interleaved timing, min of $ROUNDS
#   tools/oracle/host_m3.sh profile <out-dir> <suite> [top-n]        # sampling profile (debug line tables)
#
# Env:
#   M3_CONTENT  ROM folder (default E:/Sega/model-3-romset)
#   BUILD       build dir (default tools/m3-host-oracle/build)   BIN  oracle binary (default $BUILD/m3-host-oracle.exe)
#   RUNS        processes per suite for `run` (default 2); digests must agree, ms = median
#   ROUNDS      interleaved base/cand rounds for `bench` (default 7)
#   FRAMES      override suite frame count       EVERY  checkpoint interval (default 300)
#   JOBS        suites in parallel for `run` (default 1; >1 is fine for hashes, not timing)
#   NDK         Android NDK (for GLES headers; default newest under $LOCALAPPDATA/Android/Sdk/ndk)
#   M3_PIN_CPU  pin the emulation thread to this logical CPU (less timing noise)
#
# Digest = hash over every checkpoint of: normalized savestate (all blocks: PPC regs + RAM, 68K,
# SCSP, DSB, Real3D, TileGen, ...), Real3D scene (New3D SceneFrame), 2D tile layers and audio.
# summary.txt lines: <suite> <digest> <median_ms_per_frame> <runs> <OK|NONDET|FAIL>
set -uo pipefail
export MSYS_NO_PATHCONV=1
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck source=suites.sh
source "$here/suites.sh"
[ -d /c/msys64/ucrt64/bin ] && export PATH="/c/msys64/ucrt64/bin:$PATH"
BUILD="${BUILD:-$root/tools/m3-host-oracle/build}"
BIN="${BIN:-$BUILD/m3-host-oracle.exe}"
_default_m3_content="E:/Sega/model-3-romset"
if [ -d "D:/Sega/model-3-romset" ]; then _default_m3_content="D:/Sega/model-3-romset"
elif [ -d "D:/Sega" ]; then _default_m3_content="D:/Sega"; fi
CONTENT="${M3_CONTENT:-$_default_m3_content}"
RUNS="${RUNS:-2}"
ROUNDS="${ROUNDS:-7}"
EVERY="${EVERY:-300}"
JOBS="${JOBS:-1}"
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

gles_headers() {
  local dst="$1" ndk="${NDK:-}"
  [ -f "$dst/GLES3/gl3.h" ] && return 0
  if [ -z "$ndk" ]; then
    local sdk="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"
    ndk="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)"
  fi
  local inc
  inc="$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/sysroot/usr/include 2>/dev/null | head -1)"
  [ -n "$inc" ] || { echo "NDK GLES headers not found (set NDK=)" >&2; return 1; }
  mkdir -p "$dst"; cp -r "$inc/GLES2" "$inc/GLES3" "$inc/KHR" "$dst/"
}

# build <source-root> <build-dir> [extra cmake args]
build_tree() {
  local src="$1" bld="$2"; shift 2
  gles_headers "$bld/gles" || return 1
  cmake -S "$(win "$src/tools/m3-host-oracle")" -B "$(win "$bld")" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DM3_CORE_DIR="$(win "$src/core-supermodel")" \
    -DM3_GLES_INCLUDE="$(win "$bld/gles")" "$@" >&2 && ninja -C "$(win "$bld")" >&2
}

run_suite() {
  local out="$1" suite="$2" game frames input k digests ms status
  eval "game=\${SUITE_${suite}_GAME:-} frames=\${SUITE_${suite}_FRAMES:-1800} input=\${SUITE_${suite}_INPUT:-}"
  frames="${FRAMES:-$frames}"
  if [ -z "$game" ]; then echo "$suite - 0 0 FAIL" > "$out/$suite.summary"; return; fi
  digests=(); ms=()
  for ((k = 0; k < RUNS; k++)); do
    "$BIN" --rom "$CONTENT/$game" --games-xml "$(win "$root/core-supermodel/Config/Games.xml")" --frames "$frames" \
      --every "$EVERY" --input "$input" --label "$suite" --json "$out/$suite.r$k.json" \
      ${DUMP:+--dump-state "$out/$suite.r$k.state"} > "$out/$suite.r$k.log" 2>&1
    local line
    line="$(grep '^ORACLE_SUMMARY' "$out/$suite.r$k.log")"
    if [ -z "$line" ]; then digests+=(FAIL); ms+=(0); continue; fi
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
  echo "$suite ${digests[0]} ${median}ms/frame $status" >&2
}

# one timed run without hashing overhead beyond the checkpoints; prints "<digest> <ms_per_frame>"
time_once() {
  local bin="$1" suite="$2" game frames input line
  eval "game=\${SUITE_${suite}_GAME} frames=\${SUITE_${suite}_FRAMES:-1800} input=\${SUITE_${suite}_INPUT:-}"
  line="$("$bin" --rom "$CONTENT/$game" --games-xml "$(win "$root/core-supermodel/Config/Games.xml")" \
    --frames "${FRAMES:-$frames}" --every "$EVERY" --input "$input" --label "$suite" | grep '^ORACLE_SUMMARY')"
  echo "$(sed -E 's/.* digest=([0-9a-f]+).*/\1/' <<<"$line") $(sed -E 's/.* ms_per_frame=([0-9.]+).*/\1/' <<<"$line")"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  build) build_tree "$root" "$BUILD" ;;
  baseline)
    ref="${1:?git ref}"; dir="${2:?dir}"
    mkdir -p "$dir/src"
    git -C "$root" archive "$ref" core-supermodel tools/m3-host-oracle | tar -x -C "$dir/src" || exit 1
    # Older refs predate the oracle: use this checkout's harness against the ref's core.
    [ -f "$dir/src/tools/m3-host-oracle/CMakeLists.txt" ] || { mkdir -p "$dir/src/tools"; cp -r "$root/tools/m3-host-oracle" "$dir/src/tools/"; rm -rf "$dir/src/tools/m3-host-oracle/build"; }
    build_tree "$dir/src" "$dir/build"
    echo "baseline binary: $dir/build/m3-host-oracle.exe" >&2
    ;;
  run)
    out="${1:?out dir}"; shift
    mkdir -p "$out"; out="$(win "$out")"
    suites=("$@"); [ ${#suites[@]} -eq 0 ] && suites=(m3 vf3)
    [ "${NO_BUILD:-0}" = 1 ] || build_tree "$root" "$BUILD" || exit 1
    [ -x "$BIN" ] || { echo "missing $BIN" >&2; exit 1; }
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
    echo "wrote $out/summary.txt" >&2
    ;;
  compare)
    base="${1:?baseline dir}"; cand="${2:?candidate dir}"
    awk 'NR == FNR { d[$1] = $2; m[$1] = $3; s[$1] = $5; next }
         {
           if (!($1 in d)) { printf "%-8s MISSING-IN-BASELINE\n", $1; bad++; next }
           r = ($3 > 0) ? m[$1] / $3 : 0
           st = ($2 == d[$1] && $5 == "OK" && s[$1] == "OK") ? "MATCH" : "DIFF"
           if (st != "MATCH") bad++
           printf "%-8s %-5s base=%8.4f cand=%8.4f ms/frame  speedup=%.3fx%s\n", $1, st, m[$1], $3, r, ($5 != "OK" ? " (" $5 ")" : "")
         }
         END { exit bad ? 1 : 0 }' "$base/summary.txt" "$cand/summary.txt"
    ;;
  bench)
    base="${1:?base bin}"; cand="${2:?cand bin}"; shift 2
    suites=("$@"); [ ${#suites[@]} -eq 0 ] && suites=(m3 vf3)
    bad=0
    for s in "${suites[@]}"; do
      bmin=""; cmin=""; bd=""; cd=""
      for ((r = 0; r < ROUNDS; r++)); do
        read -r d1 t1 < <(time_once "$base" "$s")
        read -r d2 t2 < <(time_once "$cand" "$s")
        [ -z "$bd" ] && bd="$d1"; [ -z "$cd" ] && cd="$d2"
        [ "$d1" != "$bd" ] && bd="NONDET"; [ "$d2" != "$cd" ] && cd="NONDET"
        bmin="$(awk -v a="$bmin" -v b="$t1" 'BEGIN { print (a == "" || b < a) ? b : a }')"
        cmin="$(awk -v a="$cmin" -v b="$t2" 'BEGIN { print (a == "" || b < a) ? b : a }')"
        echo "  $s round $r base=$t1 cand=$t2" >&2
      done
      st=MATCH; { [ "$bd" != "$cd" ] || [ "$bd" = NONDET ]; } && { st=DIFF; bad=1; }
      awk -v s="$s" -v st="$st" -v b="$bmin" -v c="$cmin" -v d="$cd" \
        'BEGIN { printf "%-6s %-5s digest=%s base_min=%.4f cand_min=%.4f ms/frame speedup=%.2f%%\n", s, st, d, b, c, (b / c - 1) * 100 }'
    done
    exit $bad
    ;;
  profile)
    out="${1:?out dir}"; suite="${2:?suite}"; top="${3:-40}"
    pbuild="${PBUILD:-$root/tools/m3-host-oracle/build-profile}"
    build_tree "$root" "$pbuild" -DM3_PROFILE_SYMBOLS=ON || exit 1
    exe="$(win "$pbuild/m3-host-oracle.exe")"
    mkdir -p "$out"; out="$(win "$out")"
    eval "game=\${SUITE_${suite}_GAME} frames=\${SUITE_${suite}_FRAMES:-1800} input=\${SUITE_${suite}_INPUT:-}"
    "$exe" --rom "$CONTENT/$game" --games-xml "$(win "$root/core-supermodel/Config/Games.xml")" --frames "${FRAMES:-$frames}" \
      --input "$input" --label "$suite" --no-scene-hash --profile "$out/$suite.samples" | grep ORACLE_SUMMARY >&2
    base=$(objdump -p "$exe" | awk '/^ImageBase/ {print $2}')
    awk -v b="$base" 'BEGIN { hb = strtonum("0x" b) } { printf "0x%x %d\n", hb + strtonum("0x" $1), $2 }' \
      "$out/$suite.samples" > "$out/$suite.vma"
    cut -d' ' -f1 "$out/$suite.vma" | addr2line -f -i -C -a -e "$exe" > "$out/$suite.sym"
    awk 'NR == FNR { n[$1] = $2; total += $2; next }
         /^0x/ { addr = sprintf("0x%x", strtonum($0)); cur = n[addr]; depth = 0; next }
         { if ($0 !~ /^[A-Za-z]:|^\?\?:|:[0-9?]+( \(discriminator [0-9]+\))?$/) {
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
    ;;
  *) sed -n '2,27p' "$0"; exit 1 ;;
esac
