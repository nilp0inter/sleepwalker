"""Symbolic USB HID usages for the sleepwalker HID protocol.

The system uses symbolic USB HID usage names at external command
boundaries instead of platform-specific numeric keycodes. Numeric
keycodes differ between Android KeyEvent, Linux evdev, USB HID usage
IDs, and firmware internals, so the protocol exposes symbolic names and
maps them to the canonical USB keyboard usage IDs that the ESP32-S3
TinyUSB keyboard report emits.

The first keyboard smoke scenario uses USB_KEY_SPACE, mapped to:
    - USB HID keyboard usage id 0x2c (spacebar)
    - Linux evdev KEY_SPACE (0x39)

The Linux evdev code is exposed for the HID observer helper and tests so
the observer can match observed evdev events against the expected
symbolic command without hardcoding platform numbers in the harness.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class HidUsage:
    """A symbolic USB HID usage with its canonical numeric mappings.

    Attributes:
        name:        symbolic name used at external boundaries, e.g. "USB_KEY_SPACE".
        usb_usage:   USB HID keyboard usage id (TinyUSB report byte).
        evdev_code:  Linux evdev KEY_* code for the HID observer helper.
    """
    name: str
    usb_usage: int
    evdev_code: int


USB_KEY_NONE: HidUsage = HidUsage("USB_KEY_NONE", 0x00, 0x00)
USB_KEY_A: HidUsage = HidUsage("USB_KEY_A", 0x04, 30)
USB_KEY_B: HidUsage = HidUsage("USB_KEY_B", 0x05, 48)
USB_KEY_C: HidUsage = HidUsage("USB_KEY_C", 0x06, 46)
USB_KEY_D: HidUsage = HidUsage("USB_KEY_D", 0x07, 32)
USB_KEY_E: HidUsage = HidUsage("USB_KEY_E", 0x08, 18)
USB_KEY_F: HidUsage = HidUsage("USB_KEY_F", 0x09, 33)
USB_KEY_G: HidUsage = HidUsage("USB_KEY_G", 0x0A, 34)
USB_KEY_H: HidUsage = HidUsage("USB_KEY_H", 0x0B, 35)
USB_KEY_I: HidUsage = HidUsage("USB_KEY_I", 0x0C, 23)
USB_KEY_J: HidUsage = HidUsage("USB_KEY_J", 0x0D, 36)
USB_KEY_K: HidUsage = HidUsage("USB_KEY_K", 0x0E, 37)
USB_KEY_L: HidUsage = HidUsage("USB_KEY_L", 0x0F, 38)
USB_KEY_M: HidUsage = HidUsage("USB_KEY_M", 0x10, 50)
USB_KEY_N: HidUsage = HidUsage("USB_KEY_N", 0x11, 49)
USB_KEY_O: HidUsage = HidUsage("USB_KEY_O", 0x12, 24)
USB_KEY_P: HidUsage = HidUsage("USB_KEY_P", 0x13, 25)
USB_KEY_Q: HidUsage = HidUsage("USB_KEY_Q", 0x14, 16)
USB_KEY_R: HidUsage = HidUsage("USB_KEY_R", 0x15, 19)
USB_KEY_S: HidUsage = HidUsage("USB_KEY_S", 0x16, 31)
USB_KEY_T: HidUsage = HidUsage("USB_KEY_T", 0x17, 20)
USB_KEY_U: HidUsage = HidUsage("USB_KEY_U", 0x18, 22)
USB_KEY_V: HidUsage = HidUsage("USB_KEY_V", 0x19, 47)
USB_KEY_W: HidUsage = HidUsage("USB_KEY_W", 0x1A, 17)
USB_KEY_X: HidUsage = HidUsage("USB_KEY_X", 0x1B, 45)
USB_KEY_Y: HidUsage = HidUsage("USB_KEY_Y", 0x1C, 21)
USB_KEY_Z: HidUsage = HidUsage("USB_KEY_Z", 0x1D, 44)
USB_KEY_1: HidUsage = HidUsage("USB_KEY_1", 0x1E, 2)
USB_KEY_2: HidUsage = HidUsage("USB_KEY_2", 0x1F, 3)
USB_KEY_3: HidUsage = HidUsage("USB_KEY_3", 0x20, 4)
USB_KEY_4: HidUsage = HidUsage("USB_KEY_4", 0x21, 5)
USB_KEY_5: HidUsage = HidUsage("USB_KEY_5", 0x22, 6)
USB_KEY_6: HidUsage = HidUsage("USB_KEY_6", 0x23, 7)
USB_KEY_7: HidUsage = HidUsage("USB_KEY_7", 0x24, 8)
USB_KEY_8: HidUsage = HidUsage("USB_KEY_8", 0x25, 9)
USB_KEY_9: HidUsage = HidUsage("USB_KEY_9", 0x26, 10)
USB_KEY_0: HidUsage = HidUsage("USB_KEY_0", 0x27, 11)
USB_KEY_ENTER: HidUsage = HidUsage("USB_KEY_ENTER", 0x28, 28)
USB_KEY_ESCAPE: HidUsage = HidUsage("USB_KEY_ESCAPE", 0x29, 1)
USB_KEY_SPACE: HidUsage = HidUsage("USB_KEY_SPACE", 0x2C, 57)
USB_KEY_MINUS: HidUsage = HidUsage("USB_KEY_MINUS", 0x2D, 12)
USB_KEY_EQUAL: HidUsage = HidUsage("USB_KEY_EQUAL", 0x2E, 13)
USB_KEY_LEFTBRACE: HidUsage = HidUsage("USB_KEY_LEFTBRACE", 0x2F, 26)
USB_KEY_RIGHTBRACE: HidUsage = HidUsage("USB_KEY_RIGHTBRACE", 0x30, 27)
USB_KEY_BACKSLASH: HidUsage = HidUsage("USB_KEY_BACKSLASH", 0x31, 43)
USB_KEY_SEMICOLON: HidUsage = HidUsage("USB_KEY_SEMICOLON", 0x33, 39)
USB_KEY_APOSTROPHE: HidUsage = HidUsage("USB_KEY_APOSTROPHE", 0x34, 40)
USB_KEY_GRAVE: HidUsage = HidUsage("USB_KEY_GRAVE", 0x35, 41)
USB_KEY_COMMA: HidUsage = HidUsage("USB_KEY_COMMA", 0x36, 51)
USB_KEY_DOT: HidUsage = HidUsage("USB_KEY_DOT", 0x37, 52)
USB_KEY_SLASH: HidUsage = HidUsage("USB_KEY_SLASH", 0x38, 53)
USB_KEY_F24: HidUsage = HidUsage("USB_KEY_F24", 0x73, 194)
USB_KEY_NONUSBACKSLASH: HidUsage = HidUsage("USB_KEY_NONUSBACKSLASH", 0x32, 86)
USB_KEY_RO: HidUsage = HidUsage("USB_KEY_RO", 0x87, 135)
USB_KEY_LEFTCTRL: HidUsage = HidUsage("USB_KEY_LEFTCTRL", 0xE0, 29)
USB_KEY_LEFTSHIFT: HidUsage = HidUsage("USB_KEY_LEFTSHIFT", 0xE1, 42)
USB_KEY_LEFTALT: HidUsage = HidUsage("USB_KEY_LEFTALT", 0xE2, 56)
USB_KEY_LEFTMETA: HidUsage = HidUsage("USB_KEY_LEFTMETA", 0xE3, 125)
USB_KEY_RIGHTCTRL: HidUsage = HidUsage("USB_KEY_RIGHTCTRL", 0xE4, 97)
USB_KEY_RIGHTSHIFT: HidUsage = HidUsage("USB_KEY_RIGHTSHIFT", 0xE5, 54)
USB_KEY_RIGHTALT: HidUsage = HidUsage("USB_KEY_RIGHTALT", 0xE6, 100)
USB_KEY_RIGHTMETA: HidUsage = HidUsage("USB_KEY_RIGHTMETA", 0xE7, 126)

USAGE_REGISTRY: dict[str, HidUsage] = {
    u.name: u for u in [
        USB_KEY_NONE, USB_KEY_A, USB_KEY_B, USB_KEY_C, USB_KEY_D, USB_KEY_E, USB_KEY_F, USB_KEY_G,
        USB_KEY_H, USB_KEY_I, USB_KEY_J, USB_KEY_K, USB_KEY_L, USB_KEY_M, USB_KEY_N, USB_KEY_O,
        USB_KEY_P, USB_KEY_Q, USB_KEY_R, USB_KEY_S, USB_KEY_T, USB_KEY_U, USB_KEY_V, USB_KEY_W,
        USB_KEY_X, USB_KEY_Y, USB_KEY_Z, USB_KEY_1, USB_KEY_2, USB_KEY_3, USB_KEY_4, USB_KEY_5,
        USB_KEY_6, USB_KEY_7, USB_KEY_8, USB_KEY_9, USB_KEY_0, USB_KEY_ENTER, USB_KEY_ESCAPE,
        USB_KEY_SPACE, USB_KEY_MINUS, USB_KEY_EQUAL, USB_KEY_LEFTBRACE, USB_KEY_RIGHTBRACE,
        USB_KEY_BACKSLASH, USB_KEY_SEMICOLON, USB_KEY_APOSTROPHE, USB_KEY_GRAVE, USB_KEY_COMMA,
        USB_KEY_DOT, USB_KEY_SLASH, USB_KEY_NONUSBACKSLASH, USB_KEY_RO,
        USB_KEY_F24,
        USB_KEY_LEFTCTRL, USB_KEY_LEFTSHIFT, USB_KEY_LEFTALT, USB_KEY_LEFTMETA,
        USB_KEY_RIGHTCTRL, USB_KEY_RIGHTSHIFT, USB_KEY_RIGHTALT, USB_KEY_RIGHTMETA
    ]
}

USAGE_BY_USB: dict[int, HidUsage] = {
    u.usb_usage: u for u in USAGE_REGISTRY.values() if u.usb_usage != 0
}

USAGE_BY_EVDEV: dict[int, HidUsage] = {
    u.evdev_code: u for u in USAGE_REGISTRY.values() if u.evdev_code != 0
}

def usage_by_name(name: str) -> HidUsage:
    return USAGE_REGISTRY[name]

def usage_by_usb(usb_usage: int) -> HidUsage | None:
    return USAGE_BY_USB.get(usb_usage)

def usage_by_evdev(evdev_code: int) -> HidUsage | None:
    return USAGE_BY_EVDEV.get(evdev_code)

__all__ = [
    "HidUsage",
    "USB_KEY_NONE",
    "USB_KEY_A", "USB_KEY_B", "USB_KEY_C", "USB_KEY_D", "USB_KEY_E", "USB_KEY_F", "USB_KEY_G",
    "USB_KEY_H", "USB_KEY_I", "USB_KEY_J", "USB_KEY_K", "USB_KEY_L", "USB_KEY_M", "USB_KEY_N",
    "USB_KEY_O", "USB_KEY_P", "USB_KEY_Q", "USB_KEY_R", "USB_KEY_S", "USB_KEY_T", "USB_KEY_U",
    "USB_KEY_V", "USB_KEY_W", "USB_KEY_X", "USB_KEY_Y", "USB_KEY_Z",
    "USB_KEY_1", "USB_KEY_2", "USB_KEY_3", "USB_KEY_4", "USB_KEY_5",
    "USB_KEY_6", "USB_KEY_7", "USB_KEY_8", "USB_KEY_9", "USB_KEY_0",
    "USB_KEY_ENTER", "USB_KEY_ESCAPE", "USB_KEY_SPACE",
    "USB_KEY_MINUS", "USB_KEY_EQUAL", "USB_KEY_LEFTBRACE", "USB_KEY_RIGHTBRACE",
    "USB_KEY_BACKSLASH", "USB_KEY_SEMICOLON", "USB_KEY_APOSTROPHE", "USB_KEY_GRAVE",
    "USB_KEY_COMMA", "USB_KEY_DOT", "USB_KEY_SLASH", "USB_KEY_NONUSBACKSLASH", "USB_KEY_RO",
    "USB_KEY_F24",
    "USB_KEY_LEFTCTRL", "USB_KEY_LEFTSHIFT", "USB_KEY_LEFTALT", "USB_KEY_LEFTMETA",
    "USB_KEY_RIGHTCTRL", "USB_KEY_RIGHTSHIFT", "USB_KEY_RIGHTALT", "USB_KEY_RIGHTMETA",
    "USAGE_REGISTRY",
    "USAGE_BY_USB",
    "USAGE_BY_EVDEV",
    "usage_by_name",
    "usage_by_usb",
    "usage_by_evdev",
]