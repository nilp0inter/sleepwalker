# sleepwalker-fw-flash: flash the built firmware to the ESP32-S3.
#
# Side-effectful: uses esptool via idf.py flash. Requires the ESP UART/flash
# port to be passed (from bench config) and the device to be in bootloader
# mode (manual BOOT/RESET, or DTR/RTS wiring handled by the USB-to-TTL).
{ lib, writeShellScriptBin, sleepwalker-esp-idf, cmake, ninja, python3 }:
writeShellScriptBin "sleepwalker-fw-flash" ''
  set -euo pipefail
  PORT="''${1:?usage: sleepwalker-fw-flash <port> [baud]}"
  BAUD="''${2:-115200}"
  FIRMWARE_DIR="''${3:-$(git rev-parse --show-toplevel 2>/dev/null || echo .)/firmware}"
  export IDF_PATH="${sleepwalker-esp-idf}"
  export IDF_TOOLS_PATH="$IDF_PATH/tools"
  export IDF_PYTHON_CHECK_CONSTRAINTS=no
  export IDF_PYTHON_ENV_PATH="$(readlink "$IDF_PATH/python-env")"
  PROPAGATED=""
  if [ -f "$IDF_PATH/nix-support/propagated-build-inputs" ]; then
    PROPAGATED="$(cat "$IDF_PATH/nix-support/propagated-build-inputs")"
  fi
  EXTRA_PATH=""
  for p in $PROPAGATED; do
    if [ -d "$p/bin" ]; then
      EXTRA_PATH="$p/bin:$EXTRA_PATH"
    fi
  done
  export PATH="${cmake}/bin:${ninja}/bin:${python3}/bin:$EXTRA_PATH$IDF_TOOLS_PATH:$IDF_PATH/components/espcoredump:$IDF_PATH/components/partition_table:$IDF_PATH/components/app_update:$PATH"
  [ -e "$IDF_PATH/.tool-env" ] && . "$IDF_PATH/.tool-env" || true
  if [ -e "$IDF_PATH/etc/gitconfig" ]; then
    export GIT_CONFIG_SYSTEM="$IDF_PATH/etc/gitconfig"
  fi
  cd "$FIRMWARE_DIR"
  idf.py -p "$PORT" -b "$BAUD" flash >/tmp/sleepwalker-fw-flash.log 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    printf '{"ok":true,"port":"%s","baud":"%s","log":"/tmp/sleepwalker-fw-flash.log"}\n' "$PORT" "$BAUD"
  else
    printf '{"ok":false,"reason":"idf.py flash failed","rc":%d,"log":"/tmp/sleepwalker-fw-flash.log"}\n' $rc >&2
    cat /tmp/sleepwalker-fw-flash.log >&2
    exit $rc
  fi
''