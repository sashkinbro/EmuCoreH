#!/usr/bin/env bash
# Host (Windows + MSYS2 ucrt64) oracle for the Beetle Saturn core.
# Builds core-saturn plus tools/oracle/host_sat/host_sat.cpp natively, replays the
# sat suites from suites.sh and compares state/frame/audio hashes and ms/frame.
#
#   tools/oracle/host_sat.sh build <build-dir> [core-saturn-dir]
#   tools/oracle/host_sat.sh run <exe> <suite> [profile-out]
#   tools/oracle/host_sat.sh compare <baseline-git-ref> [suite ...]   (default: $SAT_ALL)
#   tools/oracle/host_sat.sh profile <suite> [top-n]                   (candidate tree)
#
# Env: HOST_SAT_CONTENT (default E:/Sega, owner content, never committed),
#      HOST_SAT_WORK (scratch, default $TMP/host_sat), REPS (timing repeats, default 3).
# compare exits 1 if any suite's hashes differ from the baseline.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
export PATH="/c/msys64/ucrt64/bin:$PATH"
_default_content="E:/Sega"
[ -d "D:/Sega" ] && _default_content="D:/Sega"
CONTENT="${HOST_SAT_CONTENT:-$_default_content}"
WORK="${HOST_SAT_WORK:-${TMP:-/tmp}/host_sat}"
REPS="${REPS:-3}"
mkdir -p "$WORK"
# shellcheck source=suites.sh
source "$here/suites.sh"

system_dir() {
  local sys="$WORK/system"
  if [ ! -f "$sys/mpr-17933.bin" ]; then
    mkdir -p "$sys"
    cp "$CONTENT/Sega Saturn BIOS (E) (1.00).bin" "$sys/mpr-17933.bin"
    cp "$CONTENT/Sega Saturn BIOS (J) (1.01).bin" "$sys/sega_101.bin"
  fi
  echo "$sys"
}

build() {
  local out="$1" src="${2:-$repo/core-saturn}"
  cmake -S "$here/host_sat" -B "$out" -G Ninja -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_BUILD_TYPE=Release -DSATURN_DIR="$src" >/dev/null
  ninja -C "$out" >/dev/null
}

# run <exe> <suite> [profile-out]: prints the hash lines and TIME_* lines.
run() {
  local exe="$1" suite="$2" prof="${3:-}"
  local game frames input options
  eval "game=\${SUITE_${suite}_GAME} frames=\${SUITE_${suite}_FRAMES:-1200} input=\${SUITE_${suite}_INPUT:-} options=\${SUITE_${suite}_OPTIONS:-}"
  local save; save="$(mktemp -d -p "$WORK" save.XXXXXX)"
  # Seed the SMPC NV file (RTC valid, 1993-12-31 23:59:59, SaveMem 0). Without it the core reads
  # RTC.Valid from an uninitialized byte (SMPC_LoadNV on a missing file), which differs between
  # builds: valid boots the game, invalid stops at the BIOS language screen.
  local stem="${game##*/}"; stem="${stem%.*}"
  printf '\001\031\223\134\061\043\131\131\000\000\000\000' > "$save/$stem.smpc"
  "$exe" "$CONTENT/$game" "$(system_dir)" "$save" "$frames" 300 "$options" "$input" "$prof" 2>"${HOST_SAT_STDERR:-/dev/null}"
}

# Speed metric: emulation-thread Mcycles/frame (robust against preemption by other
# processes); METRIC=ms uses wall-clock ms/frame instead.
metric() {
  if [ "${METRIC:-cycles}" = ms ]; then sed -n 's/^TIME_ALL.*avg_ms=\([0-9.]*\).*/\1/p'
  else sed -n 's/^CYCLES_ALL.*mcycles_per_frame=\([0-9.]*\).*/\1/p'; fi
}

compare() {
  local ref="$1"; shift
  local suites=("$@"); [ ${#suites[@]} -eq 0 ] && read -r -a suites <<< "$SAT_ALL"
  local base_src="$WORK/base-src" tree
  tree="$(git -C "$repo" rev-parse "$ref:core-saturn")"
  # Re-export (and fully rebuild) the baseline only when its core-saturn tree changed.
  if [ "$(cat "$WORK/build-base/.tree" 2>/dev/null)" != "$tree" ]; then
    rm -rf "$base_src" "$WORK/build-base"; mkdir -p "$base_src"
    git -C "$repo" archive "$ref" core-saturn | tar -x -C "$base_src"
  fi
  # The baseline is always built with the candidate's harness so only the core differs.
  build "$WORK/build-base" "$base_src/core-saturn"
  echo "$tree" > "$WORK/build-base/.tree"
  build "$WORK/build-cand" "$repo/core-saturn"
  local fail=0
  for s in "${suites[@]}"; do
    local hb hc bt=() ct=()
    for ((i = 0; i < REPS; i++)); do
      local ob oc
      ob="$(run "$WORK/build-base/host_sat.exe" "$s")"
      oc="$(run "$WORK/build-cand/host_sat.exe" "$s")"
      hb="$(grep '^f' <<< "$ob")"; hc="$(grep '^f' <<< "$oc")"
      bt+=("$(metric <<< "$ob")")
      ct+=("$(metric <<< "$oc")")
      if [ "$hb" != "$hc" ]; then echo "$s: DIFF (rep $i)"; diff <(echo "$hb") <(echo "$hc") || true; fail=1; break; fi
    done
    local best_b best_c
    best_b="$(printf '%s\n' "${bt[@]}" | sort -g | head -1)"; best_c="$(printf '%s\n' "${ct[@]}" | sort -g | head -1)"
    local verdict=MATCH; [ "$hb" != "$hc" ] && verdict=DIFF
    awk -v s="$s" -v v="$verdict" -v b="$best_b" -v c="$best_c" -v h="$(tail -1 <<< "$hc")" -v u="${METRIC:-cycles}" \
      'BEGIN { printf "%-12s %s base=%.4f cand=%.4f %s/frame speedup=%+.1f%%  [%s]\n", s, v, b, c, (u == "ms" ? "ms" : "Mcycles"), (b / c - 1) * 100, h }'
  done
  return $fail
}

profile() {
  local suite="$1" top="${2:-40}"
  build "$WORK/build-cand" "$repo/core-saturn"
  local exe="$WORK/build-cand/host_sat.exe" raw="$WORK/profile-$suite.txt"
  run "$exe" "$suite" "$raw" | grep TIME
  # Symbolize: image base relative RVAs -> function (+ line for the hottest addresses).
  local base; base="$(objdump -p "$exe" | awk '/^ImageBase/ { print $2 }')"
  awk -v b="$base" '{ printf "%s 0x%x\n", $1, strtonum($2) + strtonum("0x" b) }' "$raw" | sort -k2 > "$WORK/prof-abs.txt"
  cut -d' ' -f2 "$WORK/prof-abs.txt" | addr2line -f -C -i -e "$exe" -a > "$WORK/prof-sym.txt" 2>/dev/null || true
  "${PYTHON:-python3}" - "$WORK/prof-abs.txt" "$WORK/prof-sym.txt" "$top" <<'EOF'
import sys, collections
counts = [int(l.split()[0]) for l in open(sys.argv[1])]
blocks, cur = [], None
for line in open(sys.argv[2]):
    line = line.rstrip('\n')
    if line.startswith('0x'):
        cur = []; blocks.append(cur)
    else:
        cur.append(line)
func = collections.Counter(); loc = collections.Counter(); total = sum(counts)
for c, b in zip(counts, blocks):
    # outermost (non-inlined) function is the last function name in the -i chain
    names = b[0::2]; files = b[1::2]
    func[names[-1]] += c
    loc[(names[0], files[0].split('/')[-1])] += c
top = int(sys.argv[3])
print("== by function (%d samples)" % total)
for k, v in func.most_common(top): print("%6.2f%% %s" % (100.0 * v / total, k[:150]))
print("== by innermost line")
for k, v in loc.most_common(top): print("%6.2f%% %s %s" % (100.0 * v / total, k[1], k[0][:100]))
EOF
}

cmd="${1:-}"; shift || true
case "$cmd" in
  build) build "$@" ;;
  run) run "$@" ;;
  compare) compare "$@" ;;
  profile) profile "$@" ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
