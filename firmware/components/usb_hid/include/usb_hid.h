// usb_hid.h: TinyUSB HID keyboard device on native USB.
//
// Only the HID worker task calls into this component. BLE RX handlers must
// NOT call these functions directly (see hid_bridge).
#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Initialize TinyUSB with the keyboard HID descriptor. Called once from
// app_main.
void sw_usb_hid_init(void);

// True when TinyUSB is mounted and ready to accept HID reports.
bool sw_usb_hid_ready(void);

// Emit a keyboard report with a single usage pressed (key-down).
// `usb_usage` is a USB keyboard usage id (e.g. SW_USB_USAGE_KEY_SPACE).
// Returns true on success.
bool sw_usb_hid_keyboard_press(uint8_t usb_usage);

// Emit a keyboard release report (no keys pressed). Returns true on success.
bool sw_usb_hid_keyboard_release(void);

#ifdef __cplusplus
}
#endif