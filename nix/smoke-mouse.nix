# sleepwalker-smoke-mouse: composed relative mouse smoke operation.
#
# Orchestrates the smaller primitives to run the physical E2E relative
# mouse smoke scenario:
#   1. Validate bench config (reject before touching hardware if incomplete)
#   2. Start ESP UART, Android logcat, and HID mouse observer captures
#   3. ADB connect, arm, mouse-click (left down/up), mouse-move, release
#   4. Poll HID observer output for BTN_LEFT down/up and REL_X/REL_Y
#   5. Once found -> kill captures, write summary, exit 0
#      If not found within 30s -> kill captures, write summary, exit 1
#
# This exercises the public app/library mouse command path (task 5.4)
# and verifies physical evdev mouse events on the observer host.
{ lib, writeShellScriptBin, coreutils, python3
, sleepwalker-bench-validate, sleepwalker-fw-uart, sleepwalker-adb-logcat
, sleepwalker-hid-observe, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-mouse-click, sleepwalker-adb-mouse-move
, sleepwalker-adb-mouse-release, sleepwalker-adb-kill }:
let
  primPath = lib.makeBinPath [
    sleepwalker-bench-validate sleepwalker-fw-uart sleepwalker-adb-logcat
    sleepwalker-hid-observe sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-mouse-click sleepwalker-adb-mouse-move
    sleepwalker-adb-mouse-release sleepwalker-adb-kill
  ];
in
writeShellScriptBin "sleepwalker-smoke-mouse" ''
  set -uo pipefail
  export PATH="${primPath}:$PATH"
  BENCH="''${1:?usage: sleepwalker-smoke-mouse <bench.toml>}"
  if [ ! -f "$BENCH" ]; then
    printf '{"ok":false,"reason":"bench config not found","bench":"%s"}\n' "$BENCH" >&2
    exit 2
  fi
  if ! sleepwalker-bench-validate "$BENCH" >/dev/null 2>&1; then
    printf '{"ok":false,"reason":"bench config invalid","bench":"%s"}\n' "$BENCH" >&2
    exit 2
  fi
  eval "$(python3 - "$BENCH" <<'PYEOF'
import sys, json
try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib
with open(sys.argv[1], "rb") as f:
    cfg = tomllib.load(f)
def q(name, val):
    print(f'{name}={json.dumps(str(val))}')
q("ADB_SERIAL", cfg["android"]["adb_serial"])
q("ESP_UART", cfg["esp"]["uart_port"])
q("SSH_TARGET", cfg["hid_observer"]["ssh_target"])
q("SSH_IDENTITY", cfg["hid_observer"].get("identity_file", ""))
q("SSH_KNOWN_HOSTS", cfg["hid_observer"].get("known_hosts", ""))
q("ARTIFACT_DIR", cfg["artifacts"]["dir"])
PYEOF
  )"
  RUN_ID="run_mouse_$(date +%s)"
  RUN_DIR="$ARTIFACT_DIR/$RUN_ID"
  mkdir -p "$RUN_DIR"

  # Start captures. The HID observer targets the mouse device symlink.
  sleepwalker-fw-uart "$ESP_UART" "$RUN_DIR/esp_uart.jsonl" 115200 60 &
  FW_PID=$!
  sleepwalker-adb-logcat "$RUN_DIR/android_logcat.jsonl" "$ADB_SERIAL" 60 &
  LC_PID=$!
  sleepwalker-hid-observe "$SSH_TARGET" "$RUN_DIR/hid_observer.jsonl" 60 \
    "$SSH_IDENTITY" "$SSH_KNOWN_HOSTS" /dev/input/by-id/sleepwalker-hid-mouse &
  HID_PID=$!

  # Drive the mouse smoke scenario through the public library command path.
  sleepwalker-adb-connect "$ADB_SERIAL" 2>&1 | tee "$RUN_DIR/adb_connect.log" || true
  sleep 2
  sleepwalker-adb-arm "$ADB_SERIAL" 1 2>&1 | tee "$RUN_DIR/adb_arm.log" || true
  sleep 1
  # Mouse click (left down + up) through the library mouse API.
  sleepwalker-adb-mouse-click "$ADB_SERIAL" 2 2>&1 | tee "$RUN_DIR/adb_mouse_click.log" || true
  sleep 1
  # Relative move (dx=10, dy=0) through the library mouse API.
  sleepwalker-adb-mouse-move "$ADB_SERIAL" 10 0 3 2>&1 | tee "$RUN_DIR/adb_mouse_move.log" || true
  sleep 1
  sleepwalker-adb-mouse-release "$ADB_SERIAL" 4 2>&1 | tee "$RUN_DIR/adb_mouse_release.log" || true

  # Poll HID observer output for BTN_LEFT down+up and REL evidence.
  # BTN_LEFT is evdev code 272 (0x110); REL_X is code 0, REL_Y is code 1.
  # Match on raw code_code values so the smoke passes against observer
  # ISOs that predate the BTN_LEFT/REL_X name mapping.
  EVIDENCE_TIMEOUT=30
  EVIDENCE_FOUND=false
  ELAPSED=0
  while [ "$ELAPSED" -lt "$EVIDENCE_TIMEOUT" ]; do
    if [ -f "$RUN_DIR/hid_observer.jsonl" ]; then
      btn_down=$(grep -c '"type_code":1,"code_code":272,"value":1' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || echo 0)
      btn_up=$(grep -c '"type_code":1,"code_code":272,"value":0' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || echo 0)
      rel=$(grep -c '"type_code":2' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || echo 0)
      if [ "$btn_down" -gt 0 ] && [ "$btn_up" -gt 0 ] && [ "$rel" -gt 0 ]; then
        EVIDENCE_FOUND=true
        break
      fi
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))
  done

  sleepwalker-adb-kill "$ADB_SERIAL" 5 2>&1 | tee "$RUN_DIR/adb_kill.log" || true
  kill_captures
  cp "$BENCH" "$RUN_DIR/bench.toml"

  if $EVIDENCE_FOUND; then
    OK=true
    STATUS="pass"
  else
    OK=false
    STATUS="fail"
  fi
  python3 - "$RUN_DIR" "$STATUS" "$ELAPSED" <<'PYEOF'
import sys, json, os
run_dir, status, elapsed = sys.argv[1], sys.argv[2], sys.argv[3]
hid_events = []
hid_path = os.path.join(run_dir, "hid_observer.jsonl")
if os.path.exists(hid_path):
    with open(hid_path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    hid_events.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
btn_left_down = any(
    (e.get("code") == "BTN_LEFT" or e.get("code_code") == 272)
    and e.get("value") == 1 for e in hid_events)
btn_left_up = any(
    (e.get("code") == "BTN_LEFT" or e.get("code_code") == 272)
    and e.get("value") == 0 for e in hid_events)
rel_x = any(e.get("type") == "EV_REL" and
    (e.get("code") == "REL_X" or e.get("code_code") == 0) for e in hid_events)
rel_y = any(e.get("type") == "EV_REL" and
    (e.get("code") == "REL_Y" or e.get("code_code") == 1) for e in hid_events)
rel_any = any(e.get("type") == "EV_REL" for e in hid_events)
summary = {
    "ok": status == "pass",
    "status": status,
    "scenario": "mouse_smoke",
    "run_dir": run_dir,
    "evidence_elapsed_s": int(elapsed),
    "evidence": {
        "btn_left_down": btn_left_down,
        "btn_left_up": btn_left_up,
        "rel_x": rel_x,
        "rel_y": rel_y,
        "rel_any": rel_any,
        "correlated": btn_left_down and btn_left_up and rel_any,
    },
    "artifacts": {
        "bench": "bench.toml",
        "esp_uart": "esp_uart.jsonl",
        "android_logcat": "android_logcat.jsonl",
        "hid_observer": "hid_observer.jsonl",
        "adb_mouse_click": "adb_mouse_click.log",
        "adb_mouse_move": "adb_mouse_move.log",
        "adb_mouse_release": "adb_mouse_release.log",
    },
}
with open(os.path.join(run_dir, "summary.json"), "w") as f:
    json.dump(summary, f, indent=2)
    f.write("\n")
print(json.dumps(summary))
PYEOF
  if $EVIDENCE_FOUND; then
    exit 0
  else
    exit 1
  fi
''
