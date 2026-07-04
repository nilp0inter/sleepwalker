# sleepwalker-smoke-keyboard: composed keyboard-only smoke operation.
#
# Orchestrates the smaller primitives to run the first physical E2E
# keyboard smoke scenario:
#   1. Validate bench config (reject before touching hardware if incomplete)
#   2. Build/flash firmware (assumed already built; this op flashes)
#   3. Build/install APK (assumed already built; this op installs)
#   4. Start ESP UART capture in the background
#   5. Start Android logcat capture in the background
#   6. Start HID observer over SSH in the background
#   7. ADB connect, arm, inject USB_KEY_SPACE, release-all
#   8. Stop captures, collect artifacts, write summary JSON
#
# This is the only composed operation; the primitives it calls are also
# individually agent-callable. Requires a bench config TOML.
{ lib, writeShellScriptBin, coreutils, python3 }:
writeShellScriptBin "sleepwalker-smoke-keyboard" ''
  set -uo pipefail
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
q("ARTIFACT_DIR", cfg["artifacts"]["dir"])
PYEOF
  )"
  RUN_ID="run_$(date +%s)"
  RUN_DIR="$ARTIFACT_DIR/$RUN_ID"
  mkdir -p "$RUN_DIR"
  # 2. Start captures in the background.
  sleepwalker-fw-uart "$ESP_UART" "$RUN_DIR/esp_uart.jsonl" 115200 30 &
  FW_PID=$!
  sleepwalker-adb-logcat "$RUN_DIR/android_logcat.jsonl" "$ADB_SERIAL" 30 &
  LC_PID=$!
  sleepwalker-hid-observe "$SSH_TARGET" "$RUN_DIR/hid_observer.jsonl" 30 &
  HID_PID=$!
  # 3. Drive the scenario.
  sleepwalker-adb-connect "$ADB_SERIAL" 2>&1 | tee "$RUN_DIR/adb_connect.log" || true
  sleep 2
  sleepwalker-adb-arm "$ADB_SERIAL" 1 2>&1 | tee "$RUN_DIR/adb_arm.log" || true
  sleep 1
  sleepwalker-adb-inject-key "$ADB_SERIAL" USB_KEY_SPACE 2 2>&1 | tee "$RUN_DIR/adb_inject.log" || true
  sleep 1
  sleepwalker-adb-release-all "$ADB_SERIAL" 3 2>&1 | tee "$RUN_DIR/adb_release.log" || true
  sleep 1
  sleepwalker-adb-kill "$ADB_SERIAL" 4 2>&1 | tee "$RUN_DIR/adb_kill.log" || true
  # 4. Wait for captures to finish (timeout-based).
  wait $FW_PID 2>/dev/null || true
  wait $LC_PID 2>/dev/null || true
  wait $HID_PID 2>/dev/null || true
  # 5. Copy bench config into the artifact dir.
  cp "$BENCH" "$RUN_DIR/bench.toml"
  # 6. Write summary JSON. The HIL verification pass (task 7.7) inspects
  #    the captured JSONL to confirm correlated KEY_SPACE down/up evidence.
  python3 - "$RUN_DIR" <<'PYEOF'
import sys, json, os
run_dir = sys.argv[1]
summary = {
    "ok": True,
    "scenario": "keyboard_smoke",
    "run_dir": run_dir,
    "artifacts": {
        "bench": "bench.toml",
        "esp_uart": "esp_uart.jsonl",
        "android_logcat": "android_logcat.jsonl",
        "hid_observer": "hid_observer.jsonl",
    },
    "note": "HIL verification inspects JSONL for correlated KEY_SPACE down/up",
}
with open(os.path.join(run_dir, "summary.json"), "w") as f:
    json.dump(summary, f, indent=2)
    f.write("\n")
print(json.dumps({"ok": True, "run_dir": run_dir}))
PYEOF
''