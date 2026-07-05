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

Device-class opcode namespaces (see design.md "Preserve frame v1 and
extend by opcode namespace"):

    0x0000        - reserved / invalid
    0x0001..0x000F - safety/control
    0x0010..0x00FF - keyboard HID
    0x0100..0x01FF - relative mouse HID
    0x0200..0x02FF - future absolute pointer HID (reserved)
    0x0300..0x03FF - future virtual serial / USB CDC (reserved)
    0x0400..0x04FF - future capabilities/configuration (reserved)
    0xF000..0xFFFF - private/test/invalid fixtures

Reserved namespaces are defined so protocol decoders accept frames in
those ranges as well-formed but firmware rejects them with
STATUS_UNSUPPORTED_OPCODE. This keeps frame v1 unchanged while leaving
room for future absolute pointer, virtual serial, and
capabilities/configuration commands.
"""
from __future__ import annotations

#: Reserved / invalid opcode. Must never be accepted by firmware.
OPCODE_RESERVED: int = 0x0000

# ---- Safety/control namespace: 0x0001..0x000F ----
#: Arm the firmware safety state. Disallowed while KILLED.
OPCODE_ARM: int = 0x0001

#: Disarm the firmware safety state. Releases all keys/buttons.
OPCODE_DISARM: int = 0x0002

#: Kill the firmware safety state. Always accepted from bonded central.
#: Forces release-all and returns to DISARMED.
OPCODE_KILL: int = 0x0003

#: Release all currently held keys/buttons. Valid in ARMED state.
OPCODE_RELEASE_ALL: int = 0x0004

# ---- Keyboard HID namespace: 0x0010..0x00FF ----
#: Inject a keyboard key tap: key-down report followed by key-up report.
#: Payload: one byte USB HID usage id (see usages.py).
OPCODE_KEY_TAP: int = 0x0011

#: Inject a keyboard key-down report (no automatic release).
#: Payload: one byte USB HID usage id.
OPCODE_KEY_DOWN: int = 0x0012

#: Inject a keyboard key-up report (release a previously held key).
#: Payload: one byte USB HID usage id, or empty for release-all.
OPCODE_KEY_UP: int = 0x0013

#: Inject a keyboard tap script payload.
OPCODE_KEYBOARD_TAP_SCRIPT: int = 0x0014

# ---- Relative mouse HID namespace: 0x0100..0x01FF ----
#: Raw relative mouse report. Payload is exactly five bytes:
#: buttons:u8, dx:i8, dy:i8, wheel:i8, pan:i8.
OPCODE_MOUSE_REL_REPORT: int = 0x0100

# ---- Reserved future namespaces (defined for decoder/dispatch parity) ----
#: Reserved namespace base for future absolute pointer HID reports.
OPCODE_ABS_POINTER_BASE: int = 0x0200

#: Reserved namespace base for future virtual serial / USB CDC commands.
OPCODE_SERIAL_BASE: int = 0x0300

#: Reserved namespace base for future capabilities/configuration commands.
OPCODE_CAPABILITY_BASE: int = 0x0400

# ---- Fixture-only opcode outside the known set ----
#: Opcode intentionally outside the known set, used by the
#: unsupported-opcode golden fixture and tests.
OPCODE_UNSUPPORTED_FIXTURE: int = 0xFFFE


# ---- Namespace range helpers ----

#: Lower bounds (inclusive) of each device-class namespace.
NAMESPACE_RANGES: tuple[tuple[int, int, str], ...] = (
    (0x0001, 0x000F, "safety"),
    (0x0010, 0x00FF, "keyboard"),
    (0x0100, 0x01FF, "relative_mouse"),
    (0x0200, 0x02FF, "absolute_pointer_reserved"),
    (0x0300, 0x03FF, "serial_reserved"),
    (0x0400, 0x04FF, "capability_reserved"),
    (0xF000, 0xFFFF, "private_fixture"),
)


def namespace_for(opcode: int) -> str | None:
    """Return the device-class namespace name for an opcode, or None.

    Used to classify reserved future opcodes (absolute pointer, serial,
    capability) so firmware can emit a structured unsupported-opcode
    status with the namespace identified.
    """
    for lo, hi, name in NAMESPACE_RANGES:
        if lo <= opcode <= hi:
            return name
    return None


def is_reserved_future_opcode(opcode: int) -> bool:
    """True if the opcode is in a reserved-but-unimplemented namespace.

    Reserved future namespaces: absolute pointer, virtual serial,
    capabilities/configuration. Frames carrying these opcodes decode
    successfully but are rejected by current dispatch with
    STATUS_UNSUPPORTED_OPCODE.
    """
    ns = namespace_for(opcode)
    return ns in (
        "absolute_pointer_reserved",
        "serial_reserved",
        "capability_reserved",
    )


#: Set of all currently-implemented opcodes accepted by firmware dispatch.
ALL_OPCODES: frozenset[int] = frozenset({
    OPCODE_ARM,
    OPCODE_DISARM,
    OPCODE_KILL,
    OPCODE_RELEASE_ALL,
    OPCODE_KEY_TAP,
    OPCODE_KEY_DOWN,
    OPCODE_KEY_UP,
    OPCODE_KEYBOARD_TAP_SCRIPT,
    OPCODE_MOUSE_REL_REPORT,
})


def is_known_opcode(opcode: int) -> bool:
    """Return True if the opcode is implemented by current firmware."""
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
    "OPCODE_KEYBOARD_TAP_SCRIPT",
    "OPCODE_MOUSE_REL_REPORT",
    "OPCODE_ABS_POINTER_BASE",
    "OPCODE_SERIAL_BASE",
    "OPCODE_CAPABILITY_BASE",
    "OPCODE_UNSUPPORTED_FIXTURE",
    "NAMESPACE_RANGES",
    "namespace_for",
    "is_reserved_future_opcode",
    "ALL_OPCODES",
    "is_known_opcode",
]
