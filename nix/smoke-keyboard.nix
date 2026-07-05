# sleepwalker-smoke-keyboard: composed keyboard-only smoke operation.
#
# Orchestrates the smaller primitives to run the first physical E2E
# keyboard smoke scenario:
#   1. Validate bench config (reject before touching hardware if incomplete)
#   2. Start ESP UART, Android logcat, and HID observer captures in background
#   3. ADB connect, arm, inject USB_KEY_SPACE, release-all, kill
#   4. Poll HID observer output for correlated KEY_SPACE down/up evidence
#   5. Once found → kill captures, write summary, exit 0
#      If not found within 30s → kill captures, write summary, exit 1
#
# This is the only composed operation; the primitives it calls are also
# individually agent-callable. Requires a bench config TOML.
{ lib, writeShellScriptBin, coreutils, python3
, sleepwalker-bench-validate, sleepwalker-fw-uart, sleepwalker-adb-logcat
, sleepwalker-hid-observe, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-inject-key, sleepwalker-adb-release-all, sleepwalker-adb-kill }:
let
  primPath = lib.makeBinPath [
    sleepwalker-bench-validate sleepwalker-fw-uart sleepwalker-adb-logcat
    sleepwalker-hid-observe sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-inject-key sleepwalker-adb-release-all sleepwalker-adb-kill
  ];
in
writeShellScriptBin "sleepwalker-smoke-keyboard" ''
  set -uo pipefail
  export PATH="${primPath}:$PATH"
  BENCH="''${1:?usage: sleepwalker-smoke-keyboard <bench.toml>}"
  if [ ! -f "$BENCH" ]; then
    printf '{"ok":false,"reason":"bench config not found","bench":"%s"}\n' "$BENCH" >&2
    exit 2
  fi
  # 1. Validate bench config before touching hardware.
  if ! sleepwalker-bench-validate "$BENCH" >/dev/null 2>&1; then
    printf '{"ok":false,"reason":"bench config invalid","bench":"%s"}\n' "$BENCH" >&2
    exit 2
  fi
  # Parse bench config fields with python.
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
  RUN_ID="run_$(date +%s)"
  RUN_DIR="$ARTIFACT_DIR/$RUN_ID"
  mkdir -p "$RUN_DIR"

  # 2. Start captures in the background with generous safety-net timeouts.
  #    These will be killed early once evidence is found (step 4).
  sleepwalker-fw-uart "$ESP_UART" "$RUN_DIR/esp_uart.jsonl" 115200 60 &
  FW_PID=$!
  sleepwalker-adb-logcat "$RUN_DIR/android_logcat.jsonl" "$ADB_SERIAL" 60 &
  LC_PID=$!
  sleepwalker-hid-observe "$SSH_TARGET" "$RUN_DIR/hid_observer.jsonl" 60 \
    "$SSH_IDENTITY" "$SSH_KNOWN_HOSTS" &
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

  # 3. Drive the scenario.
  sleepwalker-adb-connect "$ADB_SERIAL" 2>&1 | tee "$RUN_DIR/adb_connect.log" || true
  sleep 2
  sleepwalker-adb-arm "$ADB_SERIAL" 1 2>&1 | tee "$RUN_DIR/adb_arm.log" || true
  sleep 1
  sleepwalker-adb-inject-key "$ADB_SERIAL" USB_KEY_SPACE 2 2>&1 | tee "$RUN_DIR/adb_inject.log" || true

  # 4. Poll HID observer output for KEY_SPACE down+up evidence.
  #    Expected: "KEY_SPACE" with value:1 AND value:0 in hid_observer.jsonl.
  EVIDENCE_TIMEOUT=30
  EVIDENCE_FOUND=false
  ELAPSED=0
  while [ "$ELAPSED" -lt "$EVIDENCE_TIMEOUT" ]; do
    if [ -f "$RUN_DIR/hid_observer.jsonl" ]; then
      if grep -q '"code":"KEY_SPACE","value":1' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null && \
         grep -q '"code":"KEY_SPACE","value":0' "$RUN_DIR/hid_observer.jsonl" 2>/dev/null; then
        EVIDENCE_FOUND=true
        break
      fi
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))
  done

  # Send remaining commands (release-all, kill) regardless of evidence.
  sleepwalker-adb-release-all "$ADB_SERIAL" 3 2>&1 | tee "$RUN_DIR/adb_release.log" || true
  sleep 1
  sleepwalker-adb-kill "$ADB_SERIAL" 4 2>&1 | tee "$RUN_DIR/adb_kill.log" || true

  # 5. Kill captures and collect artifacts.
  kill_captures
  cp "$BENCH" "$RUN_DIR/bench.toml"

  # 6. Write summary JSON with actual pass/fail based on evidence.
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
# Read the HID observer events for the summary.
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
key_space_down = any(
    e.get("code") == "KEY_SPACE" and e.get("value") == 1 for e in hid_events
)
key_space_up = any(
    e.get("code") == "KEY_SPACE" and e.get("value") == 0 for e in hid_events
)
summary = {
    "ok": status == "pass",
    "status": status,
    "scenario": "keyboard_smoke",
    "run_dir": run_dir,
    "evidence_elapsed_s": int(elapsed),
    "evidence": {
        "key_space_down": key_space_down,
        "key_space_up": key_space_up,
        "correlated": key_space_down and key_space_up,
    },
    "artifacts": {
        "bench": "bench.toml",
        "esp_uart": "esp_uart.jsonl",
        "android_logcat": "android_logcat.jsonl",
        "hid_observer": "hid_observer.jsonl",
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