# sleepwalker-smoke-text-identity: End-to-end text identity hypothesis test wrapper.
#
# Hypothesis: The text planning and HID generation pipeline produces
# rendered output that matches the original input when using the
# Linux console US keymap backend.
#
# Usage:
#   sleepwalker-smoke-text-identity <bench.toml> [profile]
{ lib, writeShellScriptBin, coreutils, jq, sleepwalker-bench-validate
, sleepwalker-fw-uart, sleepwalker-adb-logcat, sleepwalker-hid-observe
, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-type-text-encoded, sleepwalker-adb-release-all
, sleepwalker-adb-kill, sleepwalker-esp-reset
, sleepwalker-observer-prepare, sleepwalker-text-sink-start
, sleepwalker-text-sink-read, sleepwalker-text-sink-ctl, python3 }:
let
  primPath = lib.makeBinPath [
    sleepwalker-bench-validate sleepwalker-fw-uart sleepwalker-adb-logcat
    sleepwalker-hid-observe sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-type-text-encoded sleepwalker-adb-release-all sleepwalker-adb-kill
    sleepwalker-esp-reset sleepwalker-observer-prepare jq coreutils
    sleepwalker-text-sink-start sleepwalker-text-sink-read sleepwalker-text-sink-ctl
  ];
in
writeShellScriptBin "sleepwalker-smoke-text-identity" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  BENCH="''${1:?usage: sleepwalker-smoke-text-identity <bench.toml>}"
  PROFILE="''${2:-quick}"

  # Create a directory for this run's temporary files
  OUT_DIR="artifacts/run_text_identity_$(date +%s)"
  mkdir -p "$OUT_DIR"

  # Create temporary known_hosts file for this run
  # (workaround for Nix sandbox filesystem namespace restrictions)
  KNOWN_HOSTS_TMP="$OUT_DIR/known_hosts"
  if [ -f /tmp/sleepwalker_known_hosts ]; then
    cp /tmp/sleepwalker_known_hosts "$KNOWN_HOSTS_TMP"
  else
    touch "$KNOWN_HOSTS_TMP"
  fi

  # Run Python runner
  ${python3}/bin/python3 ${./smoke-text-identity.py} "$BENCH" "$OUT_DIR" "$KNOWN_HOSTS_TMP" --profile "$PROFILE"
''
