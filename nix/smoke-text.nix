# sleepwalker-smoke-text: high-level text rendering E2E smoke operation.
#
# Orchestrates text input verification over BLE:
#   - Direct text path using sleepwalker-adb-type-text "aA1"
#   - UI text path using MainActivity + adb shell input text "aA1"
{ lib, writeShellScriptBin, coreutils, python3, openssh
, sleepwalker-bench-validate, sleepwalker-fw-uart, sleepwalker-adb-logcat
, sleepwalker-hid-observe, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-type-text, sleepwalker-adb-release-all, sleepwalker-adb-kill
, sleepwalker-esp-reset, androidSdk }:
let
  ssh = "${openssh}/bin/ssh";
  adb = "${androidSdk}/share/android-sdk/platform-tools/adb";
  primPath = lib.makeBinPath [
    sleepwalker-bench-validate sleepwalker-fw-uart sleepwalker-adb-logcat
    sleepwalker-hid-observe sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-type-text sleepwalker-adb-release-all sleepwalker-adb-kill
    sleepwalker-esp-reset python3
  ];
in
writeShellScriptBin "sleepwalker-smoke-text" ''
  set -uo pipefail
  export PATH="${primPath}:$PATH"
  BENCH="''${1:?usage: sleepwalker-smoke-text <bench.toml>}"
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

  RUN_ID="run_text_$(date +%s)"
  RUN_DIR="$ARTIFACT_DIR/$RUN_ID"
  mkdir -p "$RUN_DIR"

  # Reset the ESP32-S3
  sleepwalker-esp-reset "$ESP_UART" 115200 2>&1 | tee "$RUN_DIR/esp_reset.log" || true
  sleep 5

  # Start captures
  sleepwalker-fw-uart "$ESP_UART" "$RUN_DIR/esp_uart.jsonl" 115200 90 &
  FW_PID=$!
  sleepwalker-adb-logcat "$RUN_DIR/android_logcat.jsonl" "$ADB_SERIAL" 90 &
  LC_PID=$!
  kill_remote_observer() {
    SSH_ARGS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new)
    if [ -n "$SSH_IDENTITY" ]; then
      SSH_ARGS+=(-i "$SSH_IDENTITY")
    fi
    if [ -n "$SSH_KNOWN_HOSTS" ]; then
      SSH_ARGS+=(-o UserKnownHostsFile="$SSH_KNOWN_HOSTS")
    fi
    ${ssh} "''${SSH_ARGS[@]}" "$SSH_TARGET" "pkill -f sleepwalker-hid-observer || true" 2>/dev/null || true
  }

  kill_captures() {
    kill $FW_PID 2>/dev/null || true
    kill $LC_PID 2>/dev/null || true
    wait $FW_PID 2>/dev/null || true
    wait $LC_PID 2>/dev/null || true
    kill_remote_observer
  }

  # Clean any stale observers at startup
  kill_remote_observer

  # Connect BLE
  sleepwalker-adb-connect "$ADB_SERIAL" 2>&1 | tee "$RUN_DIR/adb_connect.log" || true
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
    kill_captures
    cp "$BENCH" "$RUN_DIR/bench.toml"
    printf '{"ok":false,"status":"fail","scenario":"text_smoke","reason":"BLE not ready"}\n' >&2
    exit 1
  fi

  # Arm the device
  sleepwalker-adb-arm "$ADB_SERIAL" 1 2>&1 | tee "$RUN_DIR/adb_arm.log" || true
  sleep 1

  # Scenario 1: Direct text smoke
  echo "starting scenario: direct_text"
  
  # Start observer for Scenario 1
  sleepwalker-hid-observe "$SSH_TARGET" "$RUN_DIR/hid_observer_direct.jsonl" 45 \
    "$SSH_IDENTITY" "$SSH_KNOWN_HOSTS" \
    /dev/input/by-id/sleepwalker-hid-keyboard \
    /dev/input/by-id/sleepwalker-hid-mouse \
    --grab &
  OBS1_PID=$!
  sleep 2

  sleepwalker-adb-type-text "$ADB_SERIAL" "aA1" 2 2>&1 | tee "$RUN_DIR/adb_type_direct.log" || true

  # Poll for direct text evidence using precise sequence and timing verification
  EVIDENCE_TIMEOUT=15
  DIRECT_OK=false
  ELAPSED=0
  while [ "$ELAPSED" -lt "$EVIDENCE_TIMEOUT" ]; do
    if [ -f "$RUN_DIR/hid_observer_direct.jsonl" ]; then
      if python3 - "$RUN_DIR/hid_observer_direct.jsonl" 250 <<'PYEOF'
import sys, json, os
path, max_dur = sys.argv[1], int(sys.argv[2])
events = []
if not os.path.exists(path):
    sys.exit(1)
with open(path) as f:
    for line in f:
        try:
            ev = json.loads(line)
            if ev.get("type") == "EV_KEY" and ev.get("code") in ("KEY_A", "KEY_LEFTSHIFT", "KEY_1"):
                events.append(ev)
        except Exception:
            pass
expected = [
    ("KEY_A", 1), ("KEY_A", 0),
    ("KEY_LEFTSHIFT", 1), ("KEY_A", 1), ("KEY_LEFTSHIFT", 0), ("KEY_A", 0),
    ("KEY_1", 1), ("KEY_1", 0)
]
for i in range(len(events) - len(expected) + 1):
    window = events[i : i + len(expected)]
    if all(w.get("code") == e[0] and w.get("value") == e[1] for w, e in zip(window, expected)):
        duration = window[-1].get("ts_ms") - window[0].get("ts_ms")
        if duration <= max_dur:
            sys.exit(0)
sys.exit(1)
PYEOF
      then
        DIRECT_OK=true
        break
      fi
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))
  done
  kill $OBS1_PID 2>/dev/null || true
  wait $OBS1_PID 2>/dev/null || true
  kill_remote_observer
  sleep 2
  # Scenario 2: UI text smoke
  echo "starting scenario: ui_text"
  
  # Start observer for Scenario 2
  sleepwalker-hid-observe "$SSH_TARGET" "$RUN_DIR/hid_observer_ui.jsonl" 45 \
    "$SSH_IDENTITY" "$SSH_KNOWN_HOSTS" \
    /dev/input/by-id/sleepwalker-hid-keyboard \
    /dev/input/by-id/sleepwalker-hid-mouse \
    --grab &
  OBS2_PID=$!
  sleep 2

  # Wake and unlock screen to ensure focus succeeds
  ${adb} -s "$ADB_SERIAL" shell input keyevent KEYCODE_WAKE || true
  ${adb} -s "$ADB_SERIAL" shell wm dismiss-keyguard || true
  sleep 1

  # Launch MainActivity
  ${adb} -s "$ADB_SERIAL" shell am start -n io.sleepwalker.app/.MainActivity 2>&1 | tee "$RUN_DIR/am_start.log" || true
  sleep 3 # Wait for activity to focus
  
  # Type text using Android input
  ${adb} -s "$ADB_SERIAL" shell input text "aA1" 2>&1 | tee "$RUN_DIR/input_text.log" || true

  # Poll for UI text evidence using precise sequence and timing verification
  ELAPSED=0
  UI_OK=false
  while [ "$ELAPSED" -lt "$EVIDENCE_TIMEOUT" ]; do
    if [ -f "$RUN_DIR/hid_observer_ui.jsonl" ]; then
      if python3 - "$RUN_DIR/hid_observer_ui.jsonl" 5000 <<'PYEOF'
import sys, json, os
path, max_dur = sys.argv[1], int(sys.argv[2])
events = []
if not os.path.exists(path):
    sys.exit(1)
with open(path) as f:
    for line in f:
        try:
            ev = json.loads(line)
            if ev.get("type") == "EV_KEY" and ev.get("code") in ("KEY_A", "KEY_LEFTSHIFT", "KEY_1"):
                events.append(ev)
        except Exception:
            pass
expected = [
    ("KEY_A", 1), ("KEY_A", 0),
    ("KEY_LEFTSHIFT", 1), ("KEY_A", 1), ("KEY_LEFTSHIFT", 0), ("KEY_A", 0),
    ("KEY_1", 1), ("KEY_1", 0)
]
for i in range(len(events) - len(expected) + 1):
    window = events[i : i + len(expected)]
    if all(w.get("code") == e[0] and w.get("value") == e[1] for w, e in zip(window, expected)):
        duration = window[-1].get("ts_ms") - window[0].get("ts_ms")
        if duration <= max_dur:
            sys.exit(0)
sys.exit(1)
PYEOF
      then
        UI_OK=true
        break
      fi
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))
  done
  kill $OBS2_PID 2>/dev/null || true
  wait $OBS2_PID 2>/dev/null || true

  # Release all and kill
  sleepwalker-adb-release-all "$ADB_SERIAL" 3 2>&1 | tee "$RUN_DIR/adb_release.log" || true
  sleep 1
  sleepwalker-adb-kill "$ADB_SERIAL" 4 2>&1 | tee "$RUN_DIR/adb_kill.log" || true

  # Kill captures
  kill_captures
  cp "$BENCH" "$RUN_DIR/bench.toml"

  # Compile final summary
  STATUS="fail"
  if $DIRECT_OK && $UI_OK; then
    STATUS="pass"
  fi

  python3 - "$RUN_DIR" "$STATUS" "$DIRECT_OK" "$UI_OK" <<'PYEOF'
import sys, json, os
run_dir, status, direct_ok, ui_ok = sys.argv[1], sys.argv[2], sys.argv[3] == "true", sys.argv[4] == "true"

summary = {
    "ok": status == "pass",
    "status": status,
    "scenario": "text_smoke",
    "scenarios": {
        "direct_text": direct_ok,
        "ui_text": ui_ok
    },
    "artifacts": {
        "bench": "bench.toml",
        "esp_uart": "esp_uart.jsonl",
        "android_logcat": "android_logcat.jsonl",
        "hid_observer_direct": "hid_observer_direct.jsonl",
        "hid_observer_ui": "hid_observer_ui.jsonl"
    }
}
with open(os.path.join(run_dir, "summary.json"), "w") as f:
    json.dump(summary, f, indent=2)
    f.write("\n")
print(json.dumps(summary))
PYEOF

  if [ "$STATUS" = "pass" ]; then
    exit 0
  else
    exit 1
  fi
''
