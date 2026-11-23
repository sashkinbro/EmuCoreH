#!/usr/bin/env bash
# Runs every oracle suite (or the ones given) on one device, keeping states for masked comparison.
# Usage: tools/oracle/run_all.sh <serial> <out-dir> [suite...]
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
serial="$1"; out="$2"; shift 2
suites=("$@")
[ ${#suites[@]} -eq 0 ] && suites=(md sor sms s32x doom scd sat dc m3 vf3)
for s in "${suites[@]}"; do
  KEEP=1 bash "$here/run_oracle.sh" "$serial" "$out" "$s" 2>&1 | grep -E "FAILURES|NO JSON|AssertionError" | head -2
  echo "done $s"
done
