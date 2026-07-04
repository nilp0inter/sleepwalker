# sleepwalker-adb-*: ADB-driven operations against the Android companion.
#
# Each operation is a thin, structured wrapper around `adb shell am broadcast`
# targeting the sleepwalker AdbCommandReceiver. Emits JSON on stdout and
# exits non-zero on ADB failure.
{ lib, writeShellScriptBin, androidSdk }:
let
  adb = "${androidSdk}/share/android-sdk/platform-tools/adb";
  mkAdb = name: extraArgs:
    writeShellScriptBin name ''
      set -euo pipefail
      SERIAL="''${1:-}"
      ADB_ARGS=()
      if [ -n "$SERIAL" ]; then
        ADB_ARGS+=(-s "$SERIAL")
      fi
      ${extraArgs}
    '';
in
{
  # status: report companion BLE state via the ADB command surface.
  sleepwalker-adb-status = mkAdb "sleepwalker-adb-status" ''
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd status 2>&1) || true
    printf '{"ok":true,"op":"status","adb_out":%s}\n' "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # connect: instruct the companion to scan/connect for the ESP32-S3.
  sleepwalker-adb-connect = mkAdb "sleepwalker-adb-connect" ''
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd connect 2>&1) || true
    printf '{"ok":true,"op":"connect","adb_out":%s}\n' "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # arm: arm the firmware safety state via BLE.
  sleepwalker-adb-arm = mkAdb "sleepwalker-adb-arm" ''
    SEQ="''${2:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd arm --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"arm","seq":%s,"adb_out":%s}\n' "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # inject-key: inject a symbolic USB key (default USB_KEY_SPACE).
  sleepwalker-adb-inject-key = mkAdb "sleepwalker-adb-inject-key" ''
    KEY="''${2:-USB_KEY_SPACE}"
    SEQ="''${3:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd inject --es key "$KEY" --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"inject","key":"%s","seq":%s,"adb_out":%s}\n' "$KEY" "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # release-all: release all held keys/buttons.
  sleepwalker-adb-release-all = mkAdb "sleepwalker-adb-release-all" ''
    SEQ="''${2:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd release-all --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"release-all","seq":%s,"adb_out":%s}\n' "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # kill: kill the firmware safety state (always accepted from bonded central).
  sleepwalker-adb-kill = mkAdb "sleepwalker-adb-kill" ''
    SEQ="''${2:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd kill --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"kill","seq":%s,"adb_out":%s}\n' "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';
}