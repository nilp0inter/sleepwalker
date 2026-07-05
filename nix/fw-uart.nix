# sleepwalker-fw-uart: capture ESP32-S3 auxiliary UART JSONL logs.
#
# Side-effectful: opens the ESP UART port and tee's line-oriented output to
# a JSONL artifact file. Stops on SIGINT or after an optional timeout.
# Designed to run in the background while other HIL operations drive the
# firmware, then be killed once the scenario completes.
{ lib, writeShellScriptBin, coreutils, python3, python3Packages }:
writeShellScriptBin "sleepwalker-fw-uart" ''
  set -euo pipefail
  PORT="''${1:?usage: sleepwalker-fw-uart <port> <out.jsonl> [baud] [timeout_sec]}"
  OUT="''${2:?usage: sleepwalker-fw-uart <port> <out.jsonl> [baud] [timeout_sec]}"
  BAUD="''${3:-115200}"
  TIMEOUT="''${4:-0}"
  mkdir -p "$(dirname "$OUT")"
  # Use python pyserial if available, else fall back to `stty` + `cat`.
  if command -v python3 >/dev/null 2>&1; then
    TIMEOUT_ARG="$TIMEOUT" PORT_ARG="$PORT" BAUD_ARG="$BAUD" OUT_ARG="$OUT" \
    python3 - <<'PYEOF'
import os, sys, time, signal, threading
port = os.environ["PORT_ARG"]; baud = int(os.environ["BAUD_ARG"])
out = os.environ["OUT_ARG"]; timeout = int(os.environ["TIMEOUT_ARG"])
try:
    import serial
except ImportError:
    print('{"ok":false,"reason":"pyserial not installed"}', file=sys.stderr)
    sys.exit(3)
ser = serial.Serial(port, baud, timeout=1)
stop = threading.Event()
def tock():
    if timeout > 0:
        time.sleep(timeout); stop.set()
if timeout > 0:
    threading.Thread(target=tock, daemon=True).start()
def on_sig(s, f): stop.set()
signal.signal(signal.SIGINT, on_sig); signal.signal(signal.SIGTERM, on_sig)
with open(out, "w") as fh:
    while not stop.is_set():
        line = ser.readline()
        if line:
            fh.write(line.decode(errors="replace"))
            fh.flush()
ser.close()
print('{"ok":true,"port":"%s","out":"%s"}' % (port, out))
PYEOF
  else
    stty -F "$PORT" "$BAUD" raw -echo 2>/dev/null || true
    if [ "$TIMEOUT" -gt 0 ]; then
      timeout "$TIMEOUT" cat "$PORT" > "$OUT"
    else
      cat "$PORT" > "$OUT"
    fi
    printf '{"ok":true,"port":"%s","out":"%s"}\n' "$PORT" "$OUT"
  fi
''