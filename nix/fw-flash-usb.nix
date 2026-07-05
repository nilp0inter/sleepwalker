# sleepwalker-fw-flash-usb: flash the built firmware to the ESP32-S3 via native USB.
#
# Uses esptool.py to flash over the ESP32-S3 native USB port.
# Requires the device to be in download mode (GPIO0 pulled low on reset).
# On a fresh device, this typically means holding BOOT/FLASH button while pressing RST.
#
# This command flashes the bootloader, partition table, and firmware at the
# correct addresses to match idf.py flash behavior.
{
  lib,
  writeShellScriptBin,
  python3,
  esptool,
}: writeShellScriptBin "sleepwalker-fw-flash-usb" ''
  set -euo pipefail
  PORT="''${1:?usage: sleepwalker-fw-flash-usb <port> [baud]}"
  BAUD="''${2:-921600}"
  FIRMWARE_DIR="''${3:-$(git rev-parse --show-toplevel 2>/dev/null || echo .)/firmware}"
  BIN_DIR="''${4:-$FIRMWARE_DIR/build}"

  # Verify necessary binaries exist
  BOOTLOADER="$BIN_DIR/bootloader/bootloader.bin"
  PARTITION_TABLE="$BIN_DIR/partition_table/partition-table.bin"
  FIRMWARE="$BIN_DIR/sleepwalker-firmware.bin"

  for bin in "$BOOTLOADER" "$PARTITION_TABLE" "$FIRMWARE"; do
    if [ ! -f "$bin" ]; then
      printf '{"ok":false,"reason":"firmware binary not found","path":"%s"}\n' "$bin" >&2
      exit 2
    fi
  done

  # Verify port exists
  if [ ! -e "$PORT" ]; then
    printf '{"ok":false,"reason":"port not found","port":"%s"}\n' "$PORT" >&2
    exit 2
  fi

  # Flash bootloader, partition table, and firmware at correct addresses
  # Matching idf.py flash behavior:
  # - 0x0 = bootloader
  # - 0x8000 = partition table
  # - 0x10000 = firmware
  python3 ${esptool}/bin/.esptool.py-wrapped \
    --port "$PORT" \
    --baud "$BAUD" \
    --before default_reset \
    --after hard_reset \
    write_flash \
    0x0 "$BOOTLOADER" \
    0x8000 "$PARTITION_TABLE" \
    0x10000 "$FIRMWARE" \
    >/tmp/sleepwalker-fw-flash-usb.log 2>&1

  rc=$?
  if [ $rc -eq 0 ]; then
    printf '{"ok":true,"port":"%s","baud":"%s","bootloader":"%s","partition_table":"%s","firmware":"%s","log":"/tmp/sleepwalker-fw-flash-usb.log"}\n' "$PORT" "$BAUD" "$BOOTLOADER" "$PARTITION_TABLE" "$FIRMWARE"
  else
    printf '{"ok":false,"reason":"esptool.py write_flash failed","rc":%d,"log":"/tmp/sleepwalker-fw-flash-usb.log"}\n' $rc >&2
    cat /tmp/sleepwalker-fw-flash-usb.log >&2
    exit $rc
  fi
''