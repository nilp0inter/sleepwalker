# main: sleepwalker firmware app.
#
# Wires the components: init sw_log, hid_bridge, safety, ble_uart, usb_hid,
# and the HID worker task. The HID worker owns TinyUSB report emission.
idf_component_register(
  SRCS "src/main.c"
  INCLUDE_DIRS "include"
  REQUIRES sleepwalker_protocol hid_bridge safety ble_uart usb_hid sw_log nvs_flash
)