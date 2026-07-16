# sleepwalker-adb-logcat: capture structured sleepwalker logcat JSONL.
#
# Filters logcat to the "sleepwalker" tag and writes line-oriented JSON
# to an artifact file. Stops on SIGINT or after an optional timeout.
{ lib, writeShellScriptBin, androidSdk, coreutils }:
let
  adb = "${androidSdk}/share/android-sdk/platform-tools/adb";
in
writeShellScriptBin "sleepwalker-adb-logcat" ''
  set -euo pipefail
  OUT="''${1:?usage: sleepwalker-adb-logcat <out.jsonl> [serial] [timeout_sec]}"
  SERIAL="''${2:-}"
  TIMEOUT="''${3:-0}"
  mkdir -p "$(dirname "$OUT")"
  ADB_ARGS=()
  if [ -n "$SERIAL" ]; then
    ADB_ARGS+=(-s "$SERIAL")
  fi
  # Clear the logcat buffer first so we only capture this scenario.
  ${adb} "''${ADB_ARGS[@]}" logcat -c 2>/dev/null || true
  CAPTURE_PID=""
  stop_capture() {
    if [ -n "$CAPTURE_PID" ]; then
      kill "$CAPTURE_PID" 2>/dev/null || true
      wait "$CAPTURE_PID" 2>/dev/null || true
    fi
    exit 0
  }
  trap stop_capture INT TERM
  if [ "$TIMEOUT" -gt 0 ]; then
    ${coreutils}/bin/timeout "$TIMEOUT" \
      ${adb} "''${ADB_ARGS[@]}" logcat -s sleepwalker:I > "$OUT" &
  else
    ${adb} "''${ADB_ARGS[@]}" logcat -s sleepwalker:I > "$OUT" &
  fi
  CAPTURE_PID=$!
  rc=0
  wait "$CAPTURE_PID" || rc=$?
  CAPTURE_PID=""
  trap - INT TERM
  if [ "$rc" -ne 0 ] && [ "$rc" -ne 124 ]; then
    exit "$rc"
  fi
  printf '{"ok":true,"out":"%s","serial":"%s"}\n' "$OUT" "$SERIAL"
''