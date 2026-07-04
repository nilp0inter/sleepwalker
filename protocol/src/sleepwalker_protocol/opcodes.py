"""Command opcodes for the sleepwalker HID protocol.

Opcodes are the second field in the command frame (see frame.py) and are
shared by Android, firmware, and HIL tests. Kept as plain int constants
for trivial cross-language generation.

Safety contract:
    - Safety state boots DISARMED.
    - HID injection commands are rejected with STATUS_DISARMED until
      an ARM command is accepted.
    - KILL is always accepted from a bonded central and forces
      release-all + return to DISARMED.
    - A timeout returns to DISARMED.
    - BLE disconnect forces release-all + return to DISARMED.

Numeric ranges:
    0x0000        - reserved / invalid
    0x0001..0010  - safety state control
    0x0011..00ff  - HID injection commands
    0x0100+       - reserved for future (mouse, macros, text injection)
"""
from __future__ import annotations

#: Reserved / invalid opcode. Must never be accepted by firmware.
OPCODE_RESERVED: int = 0x0000

#: Arm the firmware safety state. Disallowed while KILLED.
OPCODE_ARM: int = 0x0001

#: Disarm the firmware safety state. Releases all keys/buttons.
OPCODE_DISARM: int = 0x0002

#: Kill the firmware safety state. Always accepted from bonded central.
#: Forces release-all and returns to DISARMED.
OPCODE_KILL: int = 0x0003

#: Release all currently held keys/buttons. Valid in ARMED state.
OPCODE_RELEASE_ALL: int = 0x0004

#: Inject a keyboard key tap: key-down report followed by key-up report.
#: Payload: one byte USB HID usage id (see usages.py).
OPCODE_KEY_TAP: int = 0x0011

#: Inject a keyboard key-down report (no automatic release).
#: Payload: one byte USB HID usage id.
OPCODE_KEY_DOWN: int = 0x0012

#: Inject a keyboard key-up report (release a previously held key).
#: Payload: one byte USB HID usage id, or empty for release-all.
OPCODE_KEY_UP: int = 0x0013

#: Set of all currently-defined opcodes for validation.
ALL_OPCODES: frozenset[int] = frozenset({
    OPCODE_ARM,
    OPCODE_DISARM,
    OPCODE_KILL,
    OPCODE_RELEASE_ALL,
    OPCODE_KEY_TAP,
    OPCODE_KEY_DOWN,
    OPCODE_KEY_UP,
})

#: Opcode intentionally outside the known set, used by the
#: unsupported-opcode golden fixture and tests.
OPCODE_UNSUPPORTED_FIXTURE: int = 0xFFFE


def is_known_opcode(opcode: int) -> bool:
    """Return True if the opcode is recognized by the firmware."""
    return opcode in ALL_OPCODES


__all__ = [
    "OPCODE_RESERVED",
    "OPCODE_ARM",
    "OPCODE_DISARM",
    "OPCODE_KILL",
    "OPCODE_RELEASE_ALL",
    "OPCODE_KEY_TAP",
    "OPCODE_KEY_DOWN",
    "OPCODE_KEY_UP",
    "ALL_OPCODES",
    "OPCODE_UNSUPPORTED_FIXTURE",
    "is_known_opcode",
]