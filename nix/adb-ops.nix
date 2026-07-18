# sleepwalker-adb-*: ADB-driven operations against the Android companion.
#
# Each operation is a thin, structured wrapper around `adb shell am broadcast`
# targeting the sleepwalker AdbCommandReceiver. Emits JSON on stdout and
# exits non-zero on ADB failure.
{ lib, writeShellScriptBin, androidSdk, python3 }:
let
  adb = "${androidSdk}/share/android-sdk/platform-tools/adb";
  mkAdb = name: extraArgs:
    writeShellScriptBin name ''
      set -euo pipefail
      export PATH="${lib.makeBinPath [ python3 ]}:$PATH"
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

  # mouse-click: left button click (down + up) via the library mouse API.
  sleepwalker-adb-mouse-click = mkAdb "sleepwalker-adb-mouse-click" ''
    SEQ="''${2:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd mouse-click --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"mouse-click","seq":%s,"adb_out":%s}\n' "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # mouse-move: relative mouse movement via the library mouse API (chunked).
  sleepwalker-adb-mouse-move = mkAdb "sleepwalker-adb-mouse-move" ''
    DX="''${2:-0}"
    DY="''${3:-0}"
    SEQ="''${4:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd mouse-move --ei dx "$DX" --ei dy "$DY" --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"mouse-move","dx":%s,"dy":%s,"seq":%s,"adb_out":%s}\n' "$DX" "$DY" "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # mouse-scroll: vertical scroll via the library mouse API.
  sleepwalker-adb-mouse-scroll = mkAdb "sleepwalker-adb-mouse-scroll" ''
    AMOUNT="''${2:-0}"
    SEQ="''${3:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd mouse-scroll --ei amount "$AMOUNT" --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"mouse-scroll","amount":%s,"seq":%s,"adb_out":%s}\n' "$AMOUNT" "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # mouse-release: release all mouse buttons.
  sleepwalker-adb-mouse-release = mkAdb "sleepwalker-adb-mouse-release" ''
    SEQ="''${2:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd mouse-release --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"mouse-release","seq":%s,"adb_out":%s}\n' "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # type-text: type a string of text.
  sleepwalker-adb-type-text = mkAdb "sleepwalker-adb-type-text" ''
    TEXT="''${2:-}"
    SEQ="''${3:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd type-text --es text "$TEXT" --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"type-text","text":"%s","seq":%s,"adb_out":%s}\n' "$TEXT" "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # type-text-encoded: type a base64url-encoded string of text.
  # Use this for text containing shell-sensitive characters.
  sleepwalker-adb-type-text-encoded = mkAdb "sleepwalker-adb-type-text-encoded" ''
    TEXT_ENCODED="''${2:-}"
    SEQ="''${3:-0}"
    OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd type-text --es text_encoded "$TEXT_ENCODED" --ei seq "$SEQ" 2>&1) || true
    printf '{"ok":true,"op":"type-text-encoded","text_encoded":"%s","seq":%s,"adb_out":%s}\n' "$TEXT_ENCODED" "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
  '';

  # reset-editor: reset the app Editor to empty known state between HIL sequences.
  sleepwalker-adb-reset-editor = mkAdb "sleepwalker-adb-reset-editor" ''
    SEQ="''${2:-0}"
    if OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast --receiver-foreground -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd reset-editor --ei seq "$SEQ" 2>&1); then
      printf '{"ok":true,"op":"reset-editor","seq":%s,"adb_out":%s}\n' "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
    else
      RC=$?
      printf '{"ok":false,"op":"reset-editor","seq":%s,"adb_rc":%s,"adb_out":%s}\n' "$SEQ" "$RC" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')" >&2
      exit "$RC"
    fi
  '';

  # set-text-encoded: set Editor text via a base64url-encoded payload.
  # Decoded exactly once and passed to Editor.setText(). Accepts a
  # complete-text snapshot; the Editor reconciles against its assumed
  # state and produces a plan. Use this for shell-safe Editor commands.
  # Returns structured Editor diagnostics (lcp, old_mid, new_mid,
  # plan_ops, predicted state, transport status) in adb_out.
  sleepwalker-adb-set-text-encoded = mkAdb "sleepwalker-adb-set-text-encoded" ''
    TEXT_ENCODED="''${2:-}"
    SEQ="''${3:-0}"
    OPAQUE_INPUT_STATE="''${4:-}"
    CURRENT_TEXT_ENCODED="''${5:-}"
    TEXT_ENCODED_ARG="$TEXT_ENCODED"
    if [ -z "$TEXT_ENCODED_ARG" ]; then
      # adb shell joins argv into a remote shell command; an ordinary empty
      # argv element disappears and makes --es consume the following --ei.
      TEXT_ENCODED_ARG='""'
    fi
    EXTRA_ARGS=()
    if [ -n "$OPAQUE_INPUT_STATE" ]; then
      EXTRA_ARGS+=(--es opaque_input_state "$OPAQUE_INPUT_STATE")
    fi
    if [ -n "$CURRENT_TEXT_ENCODED" ]; then
      EXTRA_ARGS+=(--es current_text_encoded "$CURRENT_TEXT_ENCODED")
    fi
    if OUT=$(${adb} "''${ADB_ARGS[@]}" shell am broadcast --receiver-foreground -a io.sleepwalker.app.COMMAND \
      -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd set-text --es text_encoded "$TEXT_ENCODED_ARG" --ei seq "$SEQ" "''${EXTRA_ARGS[@]}" 2>&1); then
      printf '{"ok":true,"op":"set-text-encoded","text_encoded":"%s","seq":%s,"adb_out":%s}\n' "$TEXT_ENCODED" "$SEQ" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
    else
      RC=$?
      printf '{"ok":false,"op":"set-text-encoded","seq":%s,"adb_rc":%s,"adb_out":%s}\n' "$SEQ" "$RC" "$(printf '%s' "$OUT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')" >&2
      exit "$RC"
    fi
  '';

  # launch-readline: launch MainActivity with Readline editor mode via stable
  # EXTRA_READLINE intent extra. Used by the fixed UI HIL scenario.
  sleepwalker-adb-launch-readline = mkAdb "sleepwalker-adb-launch-readline" ''
    ${adb} "''${ADB_ARGS[@]}" shell am start -n io.sleepwalker.app/.MainActivity --ez io.sleepwalker.app.EXTRA_READLINE true 2>&1
  '';

  # input-text: type text into the currently focused EditText field.
  # Text is single-argument (simple ASCII, no spaces needed by design).
  sleepwalker-adb-input-text = mkAdb "sleepwalker-adb-input-text" ''
    TEXT="''${2:-}"
    ${adb} "''${ADB_ARGS[@]}" shell input text "$TEXT" 2>&1
  '';

  # keyevent: send a key event by Android keycode (e.g. 67=DEL, 123=END).
  sleepwalker-adb-keyevent = mkAdb "sleepwalker-adb-keyevent" ''
    KEYCODE="''${2:-0}"
    ${adb} "''${ADB_ARGS[@]}" shell input keyevent "$KEYCODE" 2>&1
  '';

  # keycombination: send a key combination (keycode + metastate).
  # Used for Ctrl+A (113 29), Ctrl+C (113 31), Ctrl+V (113 50).
  sleepwalker-adb-keycombination = mkAdb "sleepwalker-adb-keycombination" ''
    KEYCODE="''${2:-0}"
    META="''${3:-0}"
    ${adb} "''${ADB_ARGS[@]}" shell input keycombination "$KEYCODE" "$META" 2>&1
  '';

  # dismiss-keyguard: dismiss the Android lock screen / keyguard.
  sleepwalker-adb-dismiss-keyguard = mkAdb "sleepwalker-adb-dismiss-keyguard" ''
    ${adb} "''${ADB_ARGS[@]}" shell wm dismiss-keyguard 2>&1
  '';
}