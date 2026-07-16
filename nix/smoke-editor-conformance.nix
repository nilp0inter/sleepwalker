# sleepwalker-smoke-editor-conformance: Hypothesis-driven Editor conformance HIL.
#
# Generates related complete-text snapshot sequences using Hypothesis, calls
# setText through ADB -> Android -> BLE -> firmware -> USB HID path, and
# compares the requested, predicted, and observed Editor state after every
# synchronized call. Uses the Readline fixture for authoritative snapshots
# and a non-grabbing HID observer for wire-level diagnostics only.
#
# Failure classifications:
#   semantic    plan delivered, barrier consumed, observed != predicted/desired
#   planning    pre-execution Editor rejection, no HID emitted
#   fixture     fixture misbehavior (health/reset/snapshot/identity mismatch)
#   sync        F24 barrier not consumed within window
#   transport   BLE/firmware fault (DISARMED/QUEUE_FULL/USB_NOT_MOUNTED/KILLED)
#   environment console keymap, device enumeration, SSH unreachable
#   non_reproducible  step fails once but succeeds on same-input replay
#
# Usage:
#   sleepwalker-smoke-editor-conformance <bench.toml> [profile]
{ lib, writeShellScriptBin, coreutils, jq
, sleepwalker-bench-validate, sleepwalker-esp-reset
, sleepwalker-fw-uart, sleepwalker-adb-logcat, sleepwalker-hid-observe
, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-set-text-encoded, sleepwalker-adb-reset-editor
, sleepwalker-adb-inject-key
, sleepwalker-adb-release-all, sleepwalker-adb-kill
, sleepwalker-readline-fixture-start, sleepwalker-readline-fixture-ctl
, python3 }:
let
  primPath = lib.makeBinPath [
    sleepwalker-bench-validate sleepwalker-esp-reset
    sleepwalker-fw-uart sleepwalker-adb-logcat sleepwalker-hid-observe
    sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-set-text-encoded sleepwalker-adb-reset-editor
    sleepwalker-adb-inject-key
    sleepwalker-adb-release-all sleepwalker-adb-kill
    sleepwalker-readline-fixture-start sleepwalker-readline-fixture-ctl
    jq coreutils
  ];
in
writeShellScriptBin "sleepwalker-smoke-editor-conformance" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  # Mode dispatch: dry-run and replay modes don't need a bench.toml
  case "''${1:-}" in
    --dry-run|--replay)
      exec ${python3}/bin/python3 ${./smoke-editor-conformance.py} "$@"
      ;;
  esac

  # Hardware mode: first arg must be a bench.toml path
  BENCH="''${1:?usage: sleepwalker-smoke-editor-conformance <bench.toml> [--profile quick|deep] [--seed <N>] [--timeout-mult <N>]}"
  shift

  if [ ! -f "$BENCH" ]; then
    printf '{"ok":false,"reason":"bench config not found","bench":"%s"}\n' "$BENCH" >&2
    exit 2
  fi

  OUT_DIR="artifacts/run_editor_conformance_$(date +%s)"
  mkdir -p "$OUT_DIR"

  KNOWN_HOSTS_TMP="$OUT_DIR/known_hosts"
  if [ -f /tmp/sleepwalker_known_hosts ]; then
    cp /tmp/sleepwalker_known_hosts "$KNOWN_HOSTS_TMP"
  else
    touch "$KNOWN_HOSTS_TMP"
  fi

  exec ${python3}/bin/python3 ${./smoke-editor-conformance.py} \
    "$BENCH" "$OUT_DIR" "$KNOWN_HOSTS_TMP" "$@"
''
