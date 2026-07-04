# hid_bridge: thread-safe queue between NimBLE BLE RX and the HID worker.
#
# NimBLE GATT write handlers must perform bounded work and return without
# calling TinyUSB. This component owns the FreeRTOS queue, fixed-size queue
# items (seq id + opcode + small payload), and queue-full reporting.
idf_component_register(
  SRCS "src/hid_bridge.c"
  INCLUDE_DIRS "include"
  REQUIRES sleepwalker_protocol
)