"""ACK/status values for the sleepwalker HID protocol.

Status notifications are correlated by sequence identifier (see frame.py)
and emitted on the BLE TX characteristic as structured events. They
distinguish at least: received, queued, sent-to-USB, malformed frame,
bad CRC, disarmed, queue full, USB not mounted, and unsupported opcode.

Status values are uint8 to keep status notifications tiny.
"""
from __future__ import annotations

#: Frame received and parsed successfully. The first positive status in
#: the command lifecycle chain.
STATUS_RECEIVED: int = 0x01

#: Command accepted and enqueued into the hid_bridge queue.
STATUS_QUEUED: int = 0x02

#: Command emitted as a TinyUSB HID report (key-down or key-up report sent).
STATUS_SENT_TO_USB: int = 0x03

#: Frame could not be parsed (bad length, unsupported version, etc).
#: No HID report is emitted.
STATUS_MALFORMED: int = 0x10

#: Frame CRC-32 mismatch. No HID report is emitted.
STATUS_BAD_CRC: int = 0x11

#: Command rejected because firmware safety state is DISARMED.
#: No HID report is emitted.
STATUS_DISARMED: int = 0x12

#: Command rejected because the hid_bridge queue is full.
#: No HID report is emitted.
STATUS_QUEUE_FULL: int = 0x13

#: Command rejected because TinyUSB is not mounted / not ready.
#: No HID report is emitted.
STATUS_USB_NOT_MOUNTED: int = 0x14

#: Command rejected because the opcode is unknown/unsupported.
#: No HID report is emitted.
STATUS_UNSUPPORTED_OPCODE: int = 0x15

#: Command rejected because the safety state is KILLED.
#: No HID report is emitted.
STATUS_KILLED: int = 0x16

#: Set of all known status values for validation.
ALL_STATUSES: frozenset[int] = frozenset({
    STATUS_RECEIVED,
    STATUS_QUEUED,
    STATUS_SENT_TO_USB,
    STATUS_MALFORMED,
    STATUS_BAD_CRC,
    STATUS_DISARMED,
    STATUS_QUEUE_FULL,
    STATUS_USB_NOT_MOUNTED,
    STATUS_UNSUPPORTED_OPCODE,
    STATUS_KILLED,
})


def is_known_status(status: int) -> bool:
    """Return True if the status value is recognized."""
    return status in ALL_STATUSES


__all__ = [
    "STATUS_RECEIVED",
    "STATUS_QUEUED",
    "STATUS_SENT_TO_USB",
    "STATUS_MALFORMED",
    "STATUS_BAD_CRC",
    "STATUS_DISARMED",
    "STATUS_QUEUE_FULL",
    "STATUS_USB_NOT_MOUNTED",
    "STATUS_UNSUPPORTED_OPCODE",
    "STATUS_KILLED",
    "ALL_STATUSES",
    "is_known_status",
]