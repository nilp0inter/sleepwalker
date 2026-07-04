# usb_hid: TinyUSB HID keyboard device (native USB).
#
# Owns the keyboard HID descriptor and report emission. Only the HID
# worker task calls into this component to emit key-down / key-up reports.
idf_component_register(
  SRCS "src/usb_hid.c"
  INCLUDE_DIRS "include"
  REQUIRES sleepwalker_protocol sw_log
)