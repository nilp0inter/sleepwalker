# sleepwalker-smoke-composite: combined keyboard + relative mouse smoke.
#
# Orchestrates the smaller primitives to run one autonomous composite HID
# smoke scenario against the same firmware build, Android app build, BLE
# session, and observer session:
#   1. Validate bench config (reject before touching hardware if incomplete)
#   2. Start one ESP UART capture, one Android logcat capture, and one
#      HID observer session observing both keyboard and mouse evdev nodes
#      with exclusive grab
#   3. ADB connect, arm, inject USB_KEY_SPACE, then mouse-click + mouse-move,
#      release-all, kill
#   4. Poll the single observer JSONL for keyboard KEY_SPACE down/up AND
#      mouse BTN_LEFT down/up AND relative movement
#   5. Once all found -> kill captures, write composite summary, exit 0
#      If not found within 60s -> kill captures, write failing summary, exit 1
#
# The summary separates keyboard, mouse, observer-device, and correlation
# evidence so a future failure can be attributed to a specific layer.
# Preserves autonomous operation: no new human gates for a commissioned
# bench. Protocol frame, opcode, and public library API behavior unchanged.
{ lib, writeShellScriptBin, coreutils, python3
, sleepwalker-bench-validate, sleepwalker-fw-uart, sleepwalker-adb-logcat
, sleepwalker-hid-observe, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-inject-key, sleepwalker-adb-release-all
, sleepwalker-adb-mouse-click, sleepwalker-adb-mouse-move
, sleepwalker-adb-mouse-release, sleepwalker-adb-kill
, sleepwalker-esp-reset }:
let
  primPath = lib.makeBinPath [
    sleepwalker-bench-validate sleepwalker-fw-uart sleepwalker-adb-logcat
    sleepwalker-hid-observe sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-inject-key sleepwalker-adb-release-all
    sleepwalker-adb-mouse-click sleepwalker-adb-mouse-move
    sleepwalker-adb-mouse-release sleepwalker-adb-kill
    sleepwalker-esp-reset
  ];
in
writeShellScriptBin "sleepwalker-smoke-composite" ''
  set -uo pipefail
  export PATH="${primPath}:$PATH"
  BENCH="''${1:?usage: sleepwalker-smoke-composite <bench.toml>}"
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
  RUN_ID="run_composite_$(date +%s)"
  RUN_DIR="$ARTIFACT_DIR/$RUN_ID"
  mkdir -p "$RUN_DIR"
  # 1.5 Reset the ESP32-S3 via UART RTS pulse so it boots into a
  #     known-good state before starting captures and BLE commands.
  sleepwalker-esp-reset "$ESP_UART" 115200 2>&1 | tee "$RUN_DIR/esp_reset.log" || true
  sleep 2

  # 2. Start one capture set. The HID observer watches both keyboard and
  #    mouse symlinks in one session with --grab so the composite-capable
  #    helper classifies each node by evdev capabilities and grabs every
  #    matched node for the active observation window.
  sleepwalker-fw-uart "$ESP_UART" "$RUN_DIR/esp_uart.jsonl" 115200 90 &
  FW_PID=$!
  sleepwalker-adb-logcat "$RUN_DIR/android_logcat.jsonl" "$ADB_SERIAL" 90 &
  LC_PID=$!
  sleepwalker-hid-observe "$SSH_TARGET" "$RUN_DIR/hid_observer.jsonl" 90 \
    "$SSH_IDENTITY" "$SSH_KNOWN_HOSTS" \
    /dev/input/by-id/sleepwalker-hid-keyboard \
    /dev/input/by-id/sleepwalker-hid-mouse \
    --grab &
  HID_PID=$!

  # Cleanup function: kill all background captures.
  kill_captures() {
    kill $FW_PID 2>/dev/null || true
    kill $LC_PID 2>/dev/null || true
    kill $HID_PID 2>/dev/null || true
    wait $FW_PID 2>/dev/null || true
    wait $LC_PID 2>/dev/null || true
    wait $HID_PID 2>/dev/null || true
  }

  # 3. Drive the composite scenario through the public app/library command
  #    path. Sequence IDs are explicit so cross-layer correlation is
  #    machine-readable: seq 1=arm, 2=keyboard inject, 3=mouse click,
  #    4=mouse move, 5=release-all, 6=kill.
  sleepwalker-adb-connect "$ADB_SERIAL" 2>&1 | tee "$RUN_DIR/adb_connect.log" || true
  # Wait for BLE connection to establish before arming. The ESP32-S3
  # may need time to scan/connect/subscribe after a previous kill.
  # Poll the logcat for "subscribe" or "services_discovered" events
  # (indicators that the BLE GATT session is ready for commands).
  BLE_WAIT_TIMEOUT=15
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
    # BLE never connected; skip arm/inject and write a failing summary
    # that identifies the connection layer as the failure point.
    kill_captures
    cp "$BENCH" "$RUN_DIR/bench.toml"
    python3 - "$RUN_DIR" <<'PYEOF'
import json, os, sys
run_dir = sys.argv[1]
summary = {
    "ok": False,
    "status": "fail",
    "scenario": "composite_smoke",
    "run_dir": run_dir,
    "failure_layer": "ble_connection",
    "reason": "BLE did not reach subscribe/services_discovered within timeout",
    "evidence": {
        "keyboard_pass": False,
        "mouse_pass": False,
        "observer_ok": False,
        "correlation_ok": False,
        "correlated": False,
    },
    "artifacts": {
        "bench": "bench.toml",
        "android_logcat": "android_logcat.jsonl",
        "hid_observer": "hid_observer.jsonl",
        "esp_uart": "esp_uart.jsonl",
        "adb_connect": "adb_connect.log",
    },
}
with open(os.path.join(run_dir, "summary.json"), "w") as f:
    json.dump(summary, f, indent=2)
    f.write("\n")
print(json.dumps(summary))
PYEOF
    exit 1
  fi
  sleepwalker-adb-arm "$ADB_SERIAL" 1 2>&1 | tee "$RUN_DIR/adb_arm.log" || true
  sleep 1
  # Keyboard: USB_KEY_SPACE via the existing public command path.
  sleepwalker-adb-inject-key "$ADB_SERIAL" USB_KEY_SPACE 2 2>&1 | tee "$RUN_DIR/adb_inject.log" || true
  sleep 1
  # Mouse: left click (down + up) via the library mouse API.
  sleepwalker-adb-mouse-click "$ADB_SERIAL" 3 2>&1 | tee "$RUN_DIR/adb_mouse_click.log" || true
  sleep 1
  # Mouse: relative move (dx=10, dy=0) via the library mouse API.
  sleepwalker-adb-mouse-move "$ADB_SERIAL" 10 0 4 2>&1 | tee "$RUN_DIR/adb_mouse_move.log" || true
  sleep 1
  sleepwalker-adb-mouse-release "$ADB_SERIAL" 5 2>&1 | tee "$RUN_DIR/adb_mouse_release.log" || true
  sleep 1
  sleepwalker-adb-release-all "$ADB_SERIAL" 6 2>&1 | tee "$RUN_DIR/adb_release.log" || true
  sleep 1
  sleepwalker-adb-kill "$ADB_SERIAL" 7 2>&1 | tee "$RUN_DIR/adb_kill.log" || true

  # 4. Poll the single observer JSONL for keyboard AND mouse evidence.
  #    Prefer symbolic names emitted by observer helper >=0.2.0; fall back
  #    to raw code_code values for observer ISOs that predate the symbolic
  #    decoding so the smoke still produces evidence on a stale binary.
  EVIDENCE_TIMEOUT=60
  EVIDENCE_FOUND=false
  ELAPSED=0
  while [ "$ELAPSED" -lt "$EVIDENCE_TIMEOUT" ]; do
    if [ -f "$RUN_DIR/hid_observer.jsonl" ]; then
      kbd_down=$(grep -cE '"code":"KEY_SPACE","value":1' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
      kbd_up=$(grep -cE '"code":"KEY_SPACE","value":0' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
      btn_down=$(grep -cE '"code":"BTN_LEFT","value":1|"type_code":1,"code_code":272,"value":1' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
      btn_up=$(grep -cE '"code":"BTN_LEFT","value":0|"type_code":1,"code_code":272,"value":0' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
      rel=$(grep -cE '"code":"REL_[XY]"|"type_code":2' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null || true)
      if [ "$kbd_down" -gt 0 ] && [ "$kbd_up" -gt 0 ] && \
         [ "$btn_down" -gt 0 ] && [ "$btn_up" -gt 0 ] && [ "$rel" -gt 0 ]; then
        EVIDENCE_FOUND=true
        break
      fi
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))
  done

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

def load_jsonl(name):
    path = os.path.join(run_dir, name)
    out = []
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return out

hid_events = load_jsonl("hid_observer.jsonl")
# Android logcat lines are "TAG <json>"; pull the embedded JSON.
android_events = []
lc_path = os.path.join(run_dir, "android_logcat.jsonl")
if os.path.exists(lc_path):
    with open(lc_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            idx = line.find("{")
            if idx < 0:
                continue
            try:
                android_events.append(json.loads(line[idx:]))
            except json.JSONDecodeError:
                pass
esp_events = load_jsonl("esp_uart.jsonl")

# ---- Keyboard evidence ----
key_space_down = any(
    e.get("code") == "KEY_SPACE" and e.get("value") == 1 for e in hid_events
)
key_space_up = any(
    e.get("code") == "KEY_SPACE" and e.get("value") == 0 for e in hid_events
)

# ---- Mouse evidence ----
btn_left_down = any(
    (e.get("code") == "BTN_LEFT" or e.get("code_code") == 272)
    and e.get("value") == 1 for e in hid_events
)
btn_left_up = any(
    (e.get("code") == "BTN_LEFT" or e.get("code_code") == 272)
    and e.get("value") == 0 for e in hid_events
)
rel_x = any(e.get("type") == "EV_REL" and
    (e.get("code") == "REL_X" or e.get("code_code") == 0) for e in hid_events)
rel_y = any(e.get("type") == "EV_REL" and
    (e.get("code") == "REL_Y" or e.get("code_code") == 1) for e in hid_events)
rel_any = any(e.get("type") == "EV_REL" for e in hid_events)

# ---- Observer device identity and helper version ----
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

# ---- Cross-layer sequence correlation ----
# The composite smoke has two seq namespaces:
#   - ADB command seq (1-7): the smoke's command sequence (intake/command events).
#     1=arm, 2=keyboard inject, 3=mouse click, 4=mouse move,
#     5=release-all, 6=kill (sent as 7 by the kill op; tolerate drift).
#   - BLE frame seq (0, 19, 20, ...): the protocol-level frame sequence
#     (encode/write/ack events). Multi-frame commands like mouse-click
#     expand a single command seq into multiple frame seqs.
# ESP UART only sees BLE frame seqs. Derive expected frame seqs from
# Android's frame encode events, then check those against ESP UART.
expected_cmd_seqs = [1, 2, 3, 4, 5, 6]
def seqs_in(events, key="seq"):
    return {e.get(key) for e in events if isinstance(e.get(key), int)}
android_cmd_seqs = seqs_in(android_events)
esp_seqs = seqs_in(esp_events)
# Firmware-visible frame seqs: Android's ack "sent_to_usb" events carry
# the frame seqs that the firmware should have received and acted on.
# These map ADB command seqs (e.g. mouse-click seq=3) to actual BLE
# frame seqs (e.g. seq=19) because multi-frame commands expand.
android_firmware_seqs = {
    e.get("seq") for e in android_events
    if e.get("component") == "ack" and e.get("event") == "sent_to_usb"
    and isinstance(e.get("seq"), int)
}
# HID observer events do not carry command seq; correlation across HID is
# by temporal ordering and by the symbolic event identity above.
correlation = {
    "expected_cmd_seqs": expected_cmd_seqs,
    "android_cmd_seqs_present": sorted(s for s in expected_cmd_seqs if s in android_cmd_seqs),
    "android_cmd_seqs_missing": sorted(s for s in expected_cmd_seqs if s not in android_cmd_seqs),
    "android_firmware_seqs": sorted(android_firmware_seqs),
    "esp_seqs": sorted(esp_seqs),
    "esp_seqs_present": sorted(s for s in android_firmware_seqs if s in esp_seqs),
    "esp_seqs_missing": sorted(s for s in android_firmware_seqs if s not in esp_seqs),
}
# Every frame that Android sent to USB must appear in ESP UART.
all_frames_seen_by_esp = len(android_firmware_seqs) > 0 and all(
    s in esp_seqs for s in android_firmware_seqs
)
keyboard_pass = key_space_down and key_space_up
mouse_pass = btn_left_down and btn_left_up and rel_any
observer_ok = len(observer_devices) > 0 and helper_version is not None
correlation_ok = all_frames_seen_by_esp
overall = keyboard_pass and mouse_pass and observer_ok and correlation_ok
summary = {
    "ok": status == "pass" and overall,
    "status": status if overall else "fail",
    "scenario": "composite_smoke",
    "run_dir": run_dir,
    "evidence_elapsed_s": int(elapsed),
    "keyboard": {
        "key_space_down": key_space_down,
        "key_space_up": key_space_up,
        "pass": keyboard_pass,
    },
    "mouse": {
        "btn_left_down": btn_left_down,
        "btn_left_up": btn_left_up,
        "rel_x": rel_x,
        "rel_y": rel_y,
        "rel_any": rel_any,
        "pass": mouse_pass,
    },
    "observer": {
        "helper_version": helper_version,
        "helper_path": helper_path,
        "devices": observer_devices,
        "grab_applied": all(d.get("grab") == "acquired" for d in observer_devices)
                        if observer_devices else False,
    },
    "correlation": correlation,
    "evidence": {
        "keyboard_pass": keyboard_pass,
        "mouse_pass": mouse_pass,
        "observer_ok": observer_ok,
        "correlation_ok": correlation_ok,
        "correlated": overall,
    },
    "artifacts": {
        "bench": "bench.toml",
        "esp_uart": "esp_uart.jsonl",
        "android_logcat": "android_logcat.jsonl",
        "hid_observer": "hid_observer.jsonl",
        "adb_inject": "adb_inject.log",
        "adb_mouse_click": "adb_mouse_click.log",
        "adb_mouse_move": "adb_mouse_move.log",
        "adb_mouse_release": "adb_mouse_release.log",
        "adb_release": "adb_release.log",
        "adb_kill": "adb_kill.log",
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
