# sleepwalker-fw-uart: capture ESP32-S3 auxiliary UART JSONL logs.
#
# Side-effectful: opens the ESP UART port and tee's line-oriented output to
# a JSONL artifact file. Stops on SIGINT or after an optional timeout.
# Designed to run in the background while other HIL operations drive the
# firmware, then be killed once the scenario completes.
{ lib, writeShellScriptBin, coreutils, python3, python3Packages }:
let
  pythonWithPyserial = python3.withPackages (ps: [ ps.pyserial ]);
in
writeShellScriptBin "sleepwalker-fw-uart" ''
  set -euo pipefail
  PORT="''${1:?usage: sleepwalker-fw-uart <port> <out.jsonl> [baud] [timeout_sec]}"
  OUT="''${2:?usage: sleepwalker-fw-uart <port> <out.jsonl> [baud] [timeout_sec]}"
  BAUD="''${3:-115200}"
  TIMEOUT="''${4:-0}"
  mkdir -p "$(dirname "$OUT")"
  TIMEOUT_ARG="$TIMEOUT" PORT_ARG="$PORT" BAUD_ARG="$BAUD" OUT_ARG="$OUT" \
  ${pythonWithPyserial}/bin/python3 - <<'PYEOF'
import os, sys, time, signal, threading
port = os.environ["PORT_ARG"]; baud = int(os.environ["BAUD_ARG"])
out = os.environ["OUT_ARG"]; timeout = int(os.environ["TIMEOUT_ARG"])
import serial
# Open with DTR/RTS deasserted. ESP32-S3 dev boards reset when DTR/RTS
# are toggled (the USB-to-TTL bridge's DTR/RTS are wired to EN/GPIO0).
# Asserting them on open would reset the board and cause USB HID
# re-enumeration, making the observer lose the device mid-smoke.
ser = serial.Serial()
ser.port = port
ser.baudrate = baud
ser.timeout = 1
ser.dtr = False
ser.rts = False
ser.open()
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
''
