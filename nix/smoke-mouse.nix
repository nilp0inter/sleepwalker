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
{ lib, writeShellScriptBin, coreutils, gnugrep, python3
, sleepwalker-bench-validate, sleepwalker-fw-uart, sleepwalker-adb-logcat
, sleepwalker-hid-observe, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-mouse-click, sleepwalker-adb-mouse-move
, sleepwalker-adb-mouse-release, sleepwalker-adb-kill
, sleepwalker-esp-reset }:
let
  primPath = lib.makeBinPath [
    coreutils gnugrep python3
    sleepwalker-bench-validate sleepwalker-fw-uart sleepwalker-adb-logcat
    sleepwalker-hid-observe sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-mouse-click sleepwalker-adb-mouse-move
    sleepwalker-adb-mouse-release sleepwalker-adb-kill
    sleepwalker-esp-reset
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
  # Terminate any app-side session left by an interrupted prior smoke. The
  # following ESP reset clears the firmware's permanent KILL state.
  sleepwalker-adb-kill "$ADB_SERIAL" 0 \
    >"$RUN_DIR/adb_preflight_kill.log" 2>&1 || true
  # 1.5 Reset the ESP32-S3 via UART RTS pulse for a known-good state.
  sleepwalker-esp-reset "$ESP_UART" 115200 2>&1 | tee "$RUN_DIR/esp_reset.log" || true
  sleep 2

  # Start captures. The HID observer targets both keyboard and mouse
  # symlinks so the composite-capable helper can classify each node by
  # evdev capabilities; --grab acquires an exclusive grab on every
  # matched node used during the smoke window.
  sleepwalker-fw-uart "$ESP_UART" "$RUN_DIR/esp_uart.jsonl" 115200 60 &
  FW_PID=$!
  sleepwalker-adb-logcat "$RUN_DIR/android_logcat.jsonl" "$ADB_SERIAL" 60 &
  LC_PID=$!

  # Cleanup function: kill all background captures.
  kill_captures() {
    kill $FW_PID 2>/dev/null || true
    kill $LC_PID 2>/dev/null || true
    kill $HID_PID 2>/dev/null || true
    wait $FW_PID 2>/dev/null || true
    wait $LC_PID 2>/dev/null || true
    wait $HID_PID 2>/dev/null || true
  }

  # UART capture can observe transient USB re-enumeration after reset. Wait
  # for one complete BLE initialization and require the boot count to remain
  # stable before asking Android to connect.
  for _ in $(seq 1 15); do
    READY_COUNT=0
    if [ -f "$RUN_DIR/esp_uart.jsonl" ]; then
      READY_COUNT=$(grep -c '"component":"boot","event":"ble_init"' \
        "$RUN_DIR/esp_uart.jsonl" || true)
    fi
    if [ "$READY_COUNT" -gt 0 ]; then
      sleep 3
      NEXT_COUNT=$(grep -c '"component":"boot","event":"ble_init"' \
        "$RUN_DIR/esp_uart.jsonl" || true)
      [ "$READY_COUNT" = "$NEXT_COUNT" ] && break
    else
      sleep 1
    fi
  done
  sleepwalker-hid-observe "$SSH_TARGET" "$RUN_DIR/hid_observer.jsonl" 60 \
    "$SSH_IDENTITY" "$SSH_KNOWN_HOSTS" \
    /dev/input/by-id/sleepwalker-hid-keyboard \
    /dev/input/by-id/sleepwalker-hid-mouse \
    --grab &
  HID_PID=$!

  # Drive the mouse smoke scenario through the public library command path.
  sleepwalker-adb-connect "$ADB_SERIAL" 2>&1 | tee "$RUN_DIR/adb_connect.log" || true
  # Wait for BLE connection to establish before arming.
  BLE_WAIT_TIMEOUT=30
  BLE_READY=false
  for i in $(seq 1 "$BLE_WAIT_TIMEOUT"); do
    if [ -f "$RUN_DIR/android_logcat.jsonl" ] && \
       grep -q '"event":"subscribe"\|"event":"services_discovered"' "$RUN_DIR/android_logcat.jsonl" 2>/dev/null; then
      BLE_READY=true
      break
    fi
    sleep 1
  done
  if ! $BLE_READY; then
    kill_captures
    cp "$BENCH" "$RUN_DIR/bench.toml"
    python3 - "$RUN_DIR" <<'PYEOF'
import json, os, sys
run_dir = sys.argv[1]
summary = {
    "ok": False, "status": "fail", "scenario": "mouse_smoke",
    "run_dir": run_dir, "failure_layer": "ble_connection",
    "reason": "BLE did not reach subscribe/services_discovered within timeout",
    "evidence": {"btn_left_down": False, "btn_left_up": False,
                 "rel_x": False, "rel_y": False, "correlated": False},
    "artifacts": {"bench": "bench.toml", "android_logcat": "android_logcat.jsonl",
                  "hid_observer": "hid_observer.jsonl", "esp_uart": "esp_uart.jsonl"},
}
with open(os.path.join(run_dir, "summary.json"), "w") as f:
    json.dump(summary, f, indent=2); f.write("\n")
print(json.dumps(summary))
PYEOF
    exit 1
  fi
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
  # Prefer symbolic names emitted by observer helper >=0.2.0; fall back
  # to raw code_code values for observer ISOs that predate the symbolic
  # decoding so the smoke still produces evidence on a stale binary.
  # BTN_LEFT is evdev code 272 (0x110); REL_X is code 0, REL_Y is code 1.
  EVIDENCE_TIMEOUT=30
  EVIDENCE_FOUND=false
  ELAPSED=0
  while [ "$ELAPSED" -lt "$EVIDENCE_TIMEOUT" ]; do
    if [ -f "$RUN_DIR/hid_observer.jsonl" ]; then
      btn_down=$(grep -cE '"code":"BTN_LEFT","value":1|"type_code":1,"code_code":272,"value":1' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
      btn_up=$(grep -cE '"code":"BTN_LEFT","value":0|"type_code":1,"code_code":272,"value":0' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
      rel=$(grep -cE '"code":"REL_[XY]"|"type_code":2' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
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
# Observer device identity and helper version from device_found events.
device_found = [e for e in hid_events if e.get("event") == "device_found"]
observer_devices = [
    {"device": e.get("device"), "name": e.get("name"),
     "roles": e.get("roles", []), "grab": e.get("grab")}
    for e in device_found
]
helper_version = next(
    (e.get("helper_version") for e in device_found if e.get("helper_version")),
    None,
)
helper_path = next(
    (e.get("helper_path") for e in device_found if e.get("helper_path")),
    None,
)
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
    "observer": {
        "helper_version": helper_version,
        "helper_path": helper_path,
        "devices": observer_devices,
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
