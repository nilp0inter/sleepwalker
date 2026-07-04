# ESP-IDF project: sleepwalker-firmware
#
# ESP32-S3 BLE HID bridge firmware:
#   - NimBLE custom UART GATT service (BLE-only, no Classic)
#   - Thread-safe hid_bridge queue between BLE RX and TinyUSB HID output
#   - TinyUSB keyboard HID device emitting USB_KEY_SPACE
#   - Safety state machine (DISARMED/ARMED/KILLED, timeout, disconnect)
#   - Structured JSONL diagnostics on the auxiliary UART
#
# Built via the Nix flake `sleepwalker-fw-build` app using the
# nixpkgs-esp-dev toolchain. No hardware is touched at build time.
cmake_minimum_required(VERSION 3.16)

# ESP-IDF build system entry point. IDF_PATH is provided by the
# sleepwalker-esp-idf package in the dev shell / flake app.
include($ENV{IDF_PATH}/tools/cmake/project.cmake)

project(sleepwalker-firmware)