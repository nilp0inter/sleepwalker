// sw_log.h: structured JSONL diagnostics over the auxiliary UART.
//
// Native USB is used for the TinyUSB HID device, so structured logs are
// emitted on the auxiliary UART as line-oriented JSON. Each event carries
// a stable component name, event name, optional sequence id, and a small
// set of fields, so the HIL harness can correlate one command through
// Android, BLE, firmware queueing, USB HID emission, and Linux evdev
// observation.
#pragma once

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Initialize the auxiliary UART log sink. Defaults to UART0 115200 8N1.
void sw_log_init(void);

// Emit a structured event. `component` and `event` are stable short names
// (e.g. "ble", "rx"). `seq_id` is 0 when not applicable. `fields_json` is
// an optional pre-formatted JSON object fragment (may be NULL).
//
// Example line:
//   {"ts_ms":1234,"component":"ble","event":"rx","seq":1,
//    "opcode":"0x0011","len":12}
void sw_log_event(const char *component, const char *event,
                  uint16_t seq_id, const char *fields_json);

// Convenience wrappers for common shapes.
void sw_log_boot(const char *event, const char *fields_json);
void sw_log_ble(const char *event, uint16_t seq_id, const char *fields_json);
void sw_log_safety(const char *event, const char *fields_json);
void sw_log_bridge(const char *event, uint16_t seq_id, const char *fields_json);
void sw_log_usb(const char *event, uint16_t seq_id, const char *fields_json);
void sw_log_cmd(const char *event, uint16_t seq_id, const char *fields_json);

#ifdef __cplusplus
}
#endif