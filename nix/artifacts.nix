# sleepwalker-artifacts: collect HIL smoke artifacts into a structured
# directory.
#
# Copies the bench config, command logs, Android logcat JSONL, ESP UART
# JSONL, HID observer JSONL, and writes a summary JSON describing the
# scenario result. Called by sleepwalker-smoke-keyboard at the end of a
# scenario (success or failure).
{ lib, writeShellScriptBin, coreutils, python3 }:
writeShellScriptBin "sleepwalker-artifacts" ''
  set -euo pipefail
  OUT_DIR="''${1:?usage: sleepwalker-artifacts <out_dir> <summary_json>}"
  SUMMARY="''${2:?usage: sleepwalker-artifacts <out_dir> <summary_json>}"
  mkdir -p "$OUT_DIR"
  # Copy in any artifact files passed as remaining args.
  shift 2 || true
  for f in "$@"; do
    if [ -f "$f" ]; then
      cp "$f" "$OUT_DIR/$(basename "$f")"
    fi
  done
  # Write the summary JSON if not already a file.
  if [ -f "$SUMMARY" ]; then
    cp "$SUMMARY" "$OUT_DIR/summary.json"
  else
    printf '%s\n' "$SUMMARY" > "$OUT_DIR/summary.json"
  fi
  printf '{"ok":true,"dir":"%s"}\n' "$OUT_DIR"
''