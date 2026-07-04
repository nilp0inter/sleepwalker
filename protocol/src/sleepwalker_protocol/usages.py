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


#: Canonical mapping for the first keyboard smoke scenario.
USB_KEY_SPACE: HidUsage = HidUsage(
    name="USB_KEY_SPACE",
    usb_usage=0x2C,        # USB HID keyboard usage id for Spacebar
    evdev_code=0x39,       # Linux evdev KEY_SPACE
)

#: Reserved / null usage for release-all reports and no-key states.
USB_KEY_NONE: HidUsage = HidUsage(
    name="USB_KEY_NONE",
    usb_usage=0x00,
    evdev_code=0x00,
)

#: Registry of all symbolic usages, keyed by name.
#: This is the authoritative mapping the Android core layer, firmware
#: keymap, and HIL tests consult. Adding a new symbolic key requires
#: adding an entry here AND a matching fixture/test.
USAGE_REGISTRY: dict[str, HidUsage] = {
    USB_KEY_SPACE.name: USB_KEY_SPACE,
    USB_KEY_NONE.name: USB_KEY_NONE,
}

#: Reverse lookup: USB HID usage id -> symbolic usage.
USAGE_BY_USB: dict[int, HidUsage] = {
    u.usb_usage: u for u in USAGE_REGISTRY.values() if u.usb_usage != 0
}

#: Reverse lookup: Linux evdev code -> symbolic usage.
USAGE_BY_EVDEV: dict[int, HidUsage] = {
    u.evdev_code: u for u in USAGE_REGISTRY.values() if u.evdev_code != 0
}


def usage_by_name(name: str) -> HidUsage:
    """Resolve a symbolic usage name to its HidUsage.

    Raises:
        KeyError: if the name is not in the registry.
    """
    return USAGE_REGISTRY[name]


def usage_by_usb(usb_usage: int) -> HidUsage | None:
    """Resolve a USB HID usage id to its symbolic usage, or None."""
    return USAGE_BY_USB.get(usb_usage)


def usage_by_evdev(evdev_code: int) -> HidUsage | None:
    """Resolve a Linux evdev code to its symbolic usage, or None."""
    return USAGE_BY_EVDEV.get(evdev_code)


__all__ = [
    "HidUsage",
    "USB_KEY_SPACE",
    "USB_KEY_NONE",
    "USAGE_REGISTRY",
    "USAGE_BY_USB",
    "USAGE_BY_EVDEV",
    "usage_by_name",
    "usage_by_usb",
    "usage_by_evdev",
]