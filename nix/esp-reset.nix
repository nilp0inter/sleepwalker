# sleepwalker-esp-reset: reset the ESP32-S3 via UART RTS pulse.
#
# ESP32-S3 dev boards route RTS to EN (active-low reset) and DTR to
# GPIO0 (boot mode select). To reset into normal boot mode:
#   1. Open the serial port with DTR deasserted (GPIO0 high = normal boot).
#   2. Assert RTS (EN low = reset), hold briefly.
#   3. Release RTS (EN high = boot from flash).
#   4. Close the port so fw-uart can reopen it for capture.
#
# This primitive does not capture UART data. Run it before HIL smoke
# captures start so the board is in a known-good state.
{ lib, writeShellScriptBin, python3, python3Packages }:
let
  pythonWithPyserial = python3.withPackages (ps: [ ps.pyserial ]);
in
writeShellScriptBin "sleepwalker-esp-reset" ''
  set -euo pipefail
  PORT="''${1:?usage: sleepwalker-esp-reset <port> [baud]}"
  BAUD="''${2:-115200}"
  PORT_ARG="$PORT" BAUD_ARG="$BAUD" \
  ${pythonWithPyserial}/bin/python3 - <<'PYEOF'
import os, sys, time, serial
port = os.environ["PORT_ARG"]; baud = int(os.environ["BAUD_ARG"])
s = serial.Serial()
s.port = port
s.baudrate = baud
s.dtr = False   # GPIO0 high = normal boot mode (not download)
s.rts = False   # EN high = not in reset
s.open()
# Override any OS/driver defaults immediately after open.
s.setDTR(False)  # GPIO0 high
s.setRTS(False)  # EN high
time.sleep(0.05)
# Pulse RTS to reset: EN low then EN high.
s.setRTS(True)   # EN low = reset asserted
time.sleep(0.1)
s.setRTS(False)  # EN high = reset released, boot from flash
time.sleep(0.05)
# Ensure DTR stays deasserted (GPIO0 high = normal boot).
s.setDTR(False)
s.close()
print('{"ok":true,"port":"%s","action":"esp32_reset"}' % port)
PYEOF
''
