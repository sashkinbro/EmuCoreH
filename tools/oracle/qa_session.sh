#!/usr/bin/env bash
# Drives ReleaseGameplayInstrumentedTest (the production CoreRuntime loop with AAudio and pacing).
# Usage: tools/oracle/qa_session.sh <serial> <out-dir> <token> <commands.jsonl>
# The command file holds one JSON object per line, e.g. {"op":"game","path":"<abs path>"},
# {"op":"run","ms":10000,"mask":0}, {"op":"capture","name":"shot"}, {"op":"quit"}.
# Firmware: oracle-content/dc_boot.bin (Dreamcast BIOS) is imported for the session.
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
serial="$1"; out="$2"; token="$3"; script="$4"
R=/sdcard/Android/data/com.sbro.emucoreh/files/release-audit/$token
BIOS=file:///sdcard/Android/data/com.sbro.emucoreh/files/oracle-content/dc_boot.bin
mkdir -p "$out"
"$ADB" -s "$serial" shell "rm -rf $R; mkdir -p $R"
"$ADB" -s "$serial" shell "am instrument -w -r -e class com.sbro.emucoreh.core.ReleaseGameplayInstrumentedTest -e releaseQaSession $token -e releaseQaBios $BIOS com.sbro.emucoreh.test/androidx.test.runner.AndroidJUnitRunner" > "$out/$token.instrument.log" 2>&1 &
pid=$!
for _ in $(seq 1 120); do
  "$ADB" -s "$serial" shell "grep -q ready $R/events.jsonl 2>/dev/null" && break
  sleep 1
done
"$ADB" -s "$serial" push "$script" "$R/commands.tmp" >/dev/null
"$ADB" -s "$serial" shell "mv $R/commands.tmp $R/commands.jsonl"
wait $pid
"$ADB" -s "$serial" pull "$R" "$out/$token" >/dev/null 2>&1
grep -E "OK \(|FAILURES|Error|Assertion" "$out/$token.instrument.log" | head -5
cat "$out/$token/events.jsonl" 2>/dev/null
