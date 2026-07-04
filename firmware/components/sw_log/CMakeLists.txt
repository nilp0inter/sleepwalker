# sw_log: structured JSONL diagnostics over the auxiliary UART.
#
# Native USB is reserved for the TinyUSB HID device; structured logs go
# out the auxiliary UART (UART0 by default) as one JSON object per line
# with component, event, sequence id, and relevant fields.
idf_component_register(
  SRCS "src/sw_log.c"
  INCLUDE_DIRS "include"
  PRIV_REQUIRES esp_driver_uart
)