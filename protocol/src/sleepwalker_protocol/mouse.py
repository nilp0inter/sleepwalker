"""Raw relative mouse report payload helpers for the sleepwalker HID protocol.

The relative mouse report is a single raw five-byte payload carried by the
MOUSE_REL_REPORT opcode (0x0100):

    buttons:u8  dx:i8  dy:i8  wheel:i8  pan:i8

The library owns button-mask state, movement chunking, clicks, drags, and
scrolling abstractions. Firmware validates the payload length and emits the
corresponding TinyUSB relative mouse report.

Button mask bits:
    bit 0: left
    bit 1: right
    bit 2: middle
    other bits: reserved (must be 0 in this implementation)
"""
from __future__ import annotations

from dataclasses import dataclass

from .opcodes import OPCODE_MOUSE_REL_REPORT

#: Expected payload length for a raw relative mouse report.
MOUSE_REL_PAYLOAD_LEN: int = 5

#: Button mask bits for the relative mouse report buttons byte.
MOUSE_BUTTON_LEFT: int = 0x01
MOUSE_BUTTON_RIGHT: int = 0x02
MOUSE_BUTTON_MIDDLE: int = 0x04

#: All currently-defined mouse button bits.
MOUSE_BUTTON_ALL: frozenset[int] = frozenset({
    MOUSE_BUTTON_LEFT,
    MOUSE_BUTTON_RIGHT,
    MOUSE_BUTTON_MIDDLE,
})


def _to_i8(b: int) -> int:
    """Interpret a byte as a signed two's-complement int8."""
    return b - 256 if b & 0x80 else b


def _from_i8(v: int) -> int:
    """Encode a signed int into a single byte (two's complement, low 8 bits)."""
    if not (-128 <= v <= 127):
        raise ValueError(f"value out of i8 range: {v}")
    return v & 0xFF


@dataclass(frozen=True)
class MouseRelReport:
    """A decoded raw relative mouse report payload.

    Attributes:
        buttons: button mask (MOUSE_BUTTON_* bits ORed together).
        dx:      relative X movement, signed (-128..127).
        dy:      relative Y movement, signed (-128..127).
        wheel:   vertical wheel movement, signed.
        pan:     horizontal pan movement, signed.
    """
    buttons: int
    dx: int
    dy: int
    wheel: int
    pan: int

    def to_bytes(self) -> bytes:
        """Encode the report into the canonical five-byte payload."""
        return bytes((
            self.buttons & 0xFF,
            _from_i8(self.dx),
            _from_i8(self.dy),
            _from_i8(self.wheel),
            _from_i8(self.pan),
        ))

    @classmethod
    def from_bytes(cls, data: bytes) -> "MouseRelReport":
        """Decode a five-byte payload into a MouseRelReport.

        Raises:
            ValueError: if the payload length is not exactly five bytes.
        """
        if len(data) != MOUSE_REL_PAYLOAD_LEN:
            raise ValueError(
                f"mouse rel payload must be {MOUSE_REL_PAYLOAD_LEN} bytes, "
                f"got {len(data)}")
        return cls(
            buttons=data[0],
            dx=_to_i8(data[1]),
            dy=_to_i8(data[2]),
            wheel=_to_i8(data[3]),
            pan=_to_i8(data[4]),
        )


def encode_mouse_rel(buttons: int, dx: int, dy: int,
                     wheel: int = 0, pan: int = 0) -> bytes:
    """Encode a raw relative mouse report payload (five bytes).

    Args:
        buttons: button mask (MOUSE_BUTTON_* bits ORed together).
        dx, dy:  relative movement, signed. Clamped to i8 range per-axis;
                 callers responsible for chunking large moves.
        wheel:   vertical wheel delta, signed.
        pan:     horizontal pan delta, signed.
    """
    return MouseRelReport(
        buttons=buttons & 0xFF,
        dx=max(-128, min(127, dx)),
        dy=max(-128, min(127, dy)),
        wheel=max(-128, min(127, wheel)),
        pan=max(-128, min(127, pan)),
    ).to_bytes()


def decode_mouse_rel(data: bytes) -> MouseRelReport:
    """Decode a five-byte relative mouse report payload."""
    return MouseRelReport.from_bytes(data)


def is_valid_mouse_rel_payload(data: bytes) -> bool:
    """True if the payload is exactly five bytes (the only valid length)."""
    return len(data) == MOUSE_REL_PAYLOAD_LEN


__all__ = [
    "MOUSE_REL_PAYLOAD_LEN",
    "MOUSE_BUTTON_LEFT",
    "MOUSE_BUTTON_RIGHT",
    "MOUSE_BUTTON_MIDDLE",
    "MOUSE_BUTTON_ALL",
    "MouseRelReport",
    "encode_mouse_rel",
    "decode_mouse_rel",
    "is_valid_mouse_rel_payload",
    "OPCODE_MOUSE_REL_REPORT",
]
