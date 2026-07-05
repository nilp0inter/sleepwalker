// usb_hid.h: TinyUSB HID keyboard + relative mouse device on native USB.
//
// Only the HID worker task calls into this component. BLE RX handlers must
// NOT call these functions directly (see hid_bridge).
//
// HID report identity (future-compatible):
//   Report ID 1: keyboard
//   Report ID 2: relative mouse
//   Report ID 3: future absolute pointer (reserved)
#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Initialize TinyUSB with the composite keyboard + mouse HID descriptor.
// Called once from app_main.
void sw_usb_hid_init(void);

// True when TinyUSB is mounted and ready to accept HID reports.
bool sw_usb_hid_ready(void);

// Emit a keyboard report with a single usage pressed (key-down).
// `usb_usage` is a USB keyboard usage id (e.g. SW_USB_USAGE_KEY_SPACE).
// Returns true on success.
bool sw_usb_hid_keyboard_press(uint8_t usb_usage);

// Emit a keyboard release report (no keys pressed). Returns true on success.
bool sw_usb_hid_keyboard_release(void);

// Emit a custom keyboard report with specified modifiers and usage. Returns true on success.
bool sw_usb_hid_keyboard_report(uint8_t modifiers, uint8_t usb_usage);
// Emit a relative mouse report. `buttons` is a button mask (bit 0 = left,
// bit 1 = right, bit 2 = middle). dx/dy/wheel/pan are signed 8-bit
// relative deltas. Returns true on success.
bool sw_usb_hid_mouse_rel_report(uint8_t buttons, int8_t dx, int8_t dy,
                                 int8_t wheel, int8_t pan);

// Emit a relative mouse release report (all buttons cleared, zero movement).
// Returns true on success.
bool sw_usb_hid_mouse_release(void);

#ifdef __cplusplus
}
#endif
