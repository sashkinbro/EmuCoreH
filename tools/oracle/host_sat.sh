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
CONTENT="${HOST_SAT_CONTENT:-E:/Sega}"
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
  "$exe" "$CONTENT/$game" "$(system_dir)" "$save" "$frames" 300 "$options" "$input" "$prof" 2>/dev/null
}

compare() {
  local ref="$1"; shift
  local suites=("$@"); [ ${#suites[@]} -eq 0 ] && read -r -a suites <<< "$SAT_ALL"
  local base_src="$WORK/base-src"
  rm -rf "$base_src"; mkdir -p "$base_src"
  git -C "$repo" archive "$ref" core-saturn | tar -x -C "$base_src"
  # The baseline is always built with the candidate's harness so only the core differs.
  build "$WORK/build-base" "$base_src/core-saturn"
  build "$WORK/build-cand" "$repo/core-saturn"
  local fail=0
  for s in "${suites[@]}"; do
    local hb hc bt=() ct=()
    for ((i = 0; i < REPS; i++)); do
      local ob oc
      ob="$(run "$WORK/build-base/host_sat.exe" "$s")"
      oc="$(run "$WORK/build-cand/host_sat.exe" "$s")"
      hb="$(grep '^f' <<< "$ob")"; hc="$(grep '^f' <<< "$oc")"
      bt+=("$(grep TIME_ALL <<< "$ob" | sed 's/.*avg_ms=\([0-9.]*\).*/\1/')")
      ct+=("$(grep TIME_ALL <<< "$oc" | sed 's/.*avg_ms=\([0-9.]*\).*/\1/')")
      if [ "$hb" != "$hc" ]; then echo "$s: DIFF (rep $i)"; diff <(echo "$hb") <(echo "$hc") || true; fail=1; break; fi
    done
    local best_b best_c
    best_b="$(printf '%s\n' "${bt[@]}" | sort -g | head -1)"; best_c="$(printf '%s\n' "${ct[@]}" | sort -g | head -1)"
    local verdict=MATCH; [ "$hb" != "$hc" ] && verdict=DIFF
    awk -v s="$s" -v v="$verdict" -v b="$best_b" -v c="$best_c" -v h="$(tail -1 <<< "$hc")" \
      'BEGIN { printf "%-12s %s base=%.4f ms cand=%.4f ms speedup=%+.1f%%  [%s]\n", s, v, b, c, (b / c - 1) * 100, h }'
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
