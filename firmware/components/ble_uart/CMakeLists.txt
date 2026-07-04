# ble_uart: BLE-only custom UART GATT service (NimBLE peripheral).
#
# Exposes an RX characteristic for Android-to-ESP writes and a TX
# characteristic for ESP-to-Android status notifications. No Bluetooth
idf_component_register(
  SRCS "src/ble_uart.c"
  INCLUDE_DIRS "include"
  REQUIRES sleepwalker_protocol hid_bridge safety sw_log
  PRIV_REQUIRES bt
)