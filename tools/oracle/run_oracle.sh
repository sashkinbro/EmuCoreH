#!/usr/bin/env bash
# Runs CoreOracleInstrumentedTest for one suite entry on one device and pulls the JSON.
# Usage: tools/oracle/run_oracle.sh <serial> <out-dir> <suite-name> [label-suffix]
# KEEP=1 pulls the checkpoint states, RELOAD=1 loads each checkpoint state right after saving it.
# Suites are defined in tools/oracle/suites.sh. Content must be pushed to
#   /sdcard/Android/data/com.sbro.emucoreh/files/oracle-content/
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
serial="$1"; out="$2"; suite="$3"; suffix="${4:-}"
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=suites.sh
source "$here/suites.sh"
C=/sdcard/Android/data/com.sbro.emucoreh/files/oracle-content
eval "core=\${SUITE_${suite}_CORE} game=\${SUITE_${suite}_GAME} frames=\${SUITE_${suite}_FRAMES:-1200} every=\${SUITE_${suite}_EVERY:-300} options=\${SUITE_${suite}_OPTIONS:-} input=\${SUITE_${suite}_INPUT:-} sysfiles=\${SUITE_${suite}_SYSFILES:-} runs=\${SUITE_${suite}_RUNS:-${RUNS:-1}}"
label="${suite}${suffix}"
# EXTRA_OPTIONS="k=v;k=v" appends core options for this run (e.g. jgenesis_jit_backend=native).
if [ -n "${EXTRA_OPTIONS:-}" ]; then options="${options:+$options;}$EXTRA_OPTIONS"; fi
mkdir -p "$out"
args=(-e class com.sbro.emucoreh.core.CoreOracleInstrumentedTest
      -e oracleCore "$core" -e oracleGame "$C/$game" -e oracleFrames "$frames"
      -e oracleEvery "$every" -e oracleRuns "$runs" -e oracleLabel "$label")
[ -n "$options" ] && args+=(-e oracleOptions "$options")
[ -n "$input" ] && args+=(-e oracleInput "$input")
[ "${KEEP:-0}" = 1 ] && args+=(-e oracleKeepStates true)
[ "${RELOAD:-0}" = 1 ] && args+=(-e oracleReloadStates true)
[ -n "$sysfiles" ] && args+=(-e oracleSystemFiles "${sysfiles//@C@/$C}")
# am instrument -e values may not contain spaces unquoted: quote every argument for the device shell.
quoted=""
for a in "${args[@]}"; do quoted+=" '${a//\'/\'\\\'\'}'"; done
"$ADB" -s "$serial" shell "am instrument -w -r$quoted com.sbro.emucoreh.test/androidx.test.runner.AndroidJUnitRunner" \
  > "$out/$label.log" 2>&1 || true
grep -E "ORACLE|INSTRUMENTATION_STATUS: stack|Error|FAILURES|OK \(" "$out/$label.log" | grep -v "^INSTRUMENTATION_STATUS: stream=$" | sed 's/^INSTRUMENTATION_STATUS: stream=//' | head -40
"$ADB" -s "$serial" pull "/sdcard/Android/data/com.sbro.emucoreh/files/oracle/$label.json" "$out/$label.json" >/dev/null 2>&1 \
  || echo "NO JSON for $label"
if [ "${KEEP:-0}" = 1 ]; then
  mkdir -p "$out/states"
  for f in $("$ADB" -s "$serial" shell "ls /sdcard/Android/data/com.sbro.emucoreh/files/oracle/ | grep '^$label-r0-f.*\.state\$'" | tr -d '\r'); do
    "$ADB" -s "$serial" pull "/sdcard/Android/data/com.sbro.emucoreh/files/oracle/$f" "$out/states/$f" >/dev/null
  done
  "$ADB" -s "$serial" shell "rm -f /sdcard/Android/data/com.sbro.emucoreh/files/oracle/$label-r*-f*.state"
fi
