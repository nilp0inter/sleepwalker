# safety: firmware safety state machine.
#
# Boots DISARMED. HID injection is rejected until ARM. Supports DISARM,
# KILL (always accepted from bonded central), timeout, BLE disconnect,
# and release-all transitions.
idf_component_register(
  SRCS "src/safety.c"
  INCLUDE_DIRS "include"
  REQUIRES sleepwalker_protocol
)