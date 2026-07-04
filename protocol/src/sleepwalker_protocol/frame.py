"""Versioned binary command frame layout for the sleepwalker HID protocol.

Frame layout (little-endian):

    +-----+-----+-----+-----+-----+-----+-----+-----+-----+----------+
    | ver | seq_id      | opcode      | payload_len | payload ...     |
    +-----+-----+-----+-----+-----+-----+-----+-----+-----+----------+
    | crc32 (over ver..payload)                                       |
    +-----+-----+-----+-----+-----+-----+-----+-----+-----+----------+

Fields:
    ver        : uint8   - protocol version (PROTOCOL_VERSION)
    seq_id     : uint16  - sequence identifier, wraps around
    opcode     : uint16  - command opcode (see opcodes.py)
    payload_len: uint16  - length of payload in bytes
    payload    : bytes   - payload, up to MAX_PAYLOAD_LEN
    crc32      : uint32  - zlib.crc32 over ver..payload (corruption detection only)

CRC-32 is corruption detection only. It is NOT authorization or
authentication. The initial authorization boundary is BLE bonding plus
explicit firmware safety state.

This module is the single source of truth for the frame binary layout and
is shared by Android, firmware (via generated constants), and HIL tests.
"""
from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass

#: Current protocol version. Bumped on incompatible frame layout changes.
PROTOCOL_VERSION: int = 1

#: Fixed header size: ver(1) + seq(2) + opcode(2) + payload_len(2) = 7 bytes.
HEADER_SIZE: int = 7

#: Fixed CRC-32 trailer size.
CRC_SIZE: int = 4

#: Maximum payload length. Frames are tiny for keyboard/mouse commands;
#: MTU only matters for text injection, macros, or batched commands.
MAX_PAYLOAD_LEN: int = 240

#: Maximum total frame size.
MAX_FRAME_SIZE: int = HEADER_SIZE + MAX_PAYLOAD_LEN + CRC_SIZE

# Little-endian header struct: version, sequence id, opcode, payload length.
_HEADER_STRUCT = struct.Struct("<BHHH")


class FrameError(Exception):
    """Raised when a frame cannot be decoded or is invalid."""


class CrcMismatch(FrameError):
    """Raised when a frame CRC-32 does not match its contents."""

    def __init__(self, expected: int, got: int) -> None:
        super().__init__(f"CRC mismatch: expected {expected:#010x}, got {got:#010x}")
        self.expected = expected
        self.got = got


class MalformedFrame(FrameError):
    """Raised when a frame is too short, too long, or has an impossible length."""


class UnsupportedVersion(FrameError):
    """Raised when a frame carries an unsupported protocol version."""

    def __init__(self, version: int) -> None:
        super().__init__(f"unsupported protocol version {version}")
        self.version = version


@dataclass(frozen=True)
class Frame:
    """A decoded sleepwalker command frame.

    Attributes:
        version:    protocol version (PROTOCOL_VERSION).
        seq_id:     sequence identifier for cross-layer correlation.
        opcode:     command opcode (see opcodes.py).
        payload:    raw payload bytes.
        crc32:      CRC-32 carried in the frame trailer.
    """
    version: int
    seq_id: int
    opcode: int
    payload: bytes
    crc32: int

    @property
    def payload_len(self) -> int:
        return len(self.payload)


def compute_crc(version: int, seq_id: int, opcode: int, payload: bytes) -> int:
    """Compute the CRC-32 over the header + payload (little-endian)."""
    header = _HEADER_STRUCT.pack(version, seq_id, opcode, len(payload))
    return zlib.crc32(header + payload) & 0xFFFFFFFF


def encode_frame(seq_id: int, opcode: int, payload: bytes = b"",
                 version: int = PROTOCOL_VERSION) -> bytes:
    """Encode a command frame into bytes, inserting the CRC-32 trailer.

    Raises:
        ValueError: if payload exceeds MAX_PAYLOAD_LEN or fields overflow.
    """
    if len(payload) > MAX_PAYLOAD_LEN:
        raise ValueError(
            f"payload too large: {len(payload)} > {MAX_PAYLOAD_LEN}")
    if not (0 <= seq_id <= 0xFFFF):
        raise ValueError(f"seq_id out of uint16 range: {seq_id}")
    if not (0 <= opcode <= 0xFFFF):
        raise ValueError(f"opcode out of uint16 range: {opcode}")
    if not (0 <= version <= 0xFF):
        raise ValueError(f"version out of uint8 range: {version}")

    crc = compute_crc(version, seq_id, opcode, payload)
    header = _HEADER_STRUCT.pack(version, seq_id, opcode, len(payload))
    return header + payload + struct.pack("<I", crc)


def decode_frame(data: bytes) -> Frame:
    """Decode and validate a command frame, verifying its CRC-32.

    Raises:
        MalformedFrame:       if the frame is too short/long or length disagrees.
        UnsupportedVersion:   if the protocol version is not supported.
        CrcMismatch:          if the CRC-32 does not match.
    """
    if len(data) < HEADER_SIZE + CRC_SIZE:
        raise MalformedFrame(
            f"frame too short: {len(data)} < {HEADER_SIZE + CRC_SIZE}")
    version, seq_id, opcode, payload_len = _HEADER_STRUCT.unpack_from(data, 0)
    expected_total = HEADER_SIZE + payload_len + CRC_SIZE
    if len(data) != expected_total:
        raise MalformedFrame(
            f"frame length mismatch: got {len(data)}, expected {expected_total}")
    if payload_len > MAX_PAYLOAD_LEN:
        raise MalformedFrame(
            f"payload length too large: {payload_len} > {MAX_PAYLOAD_LEN}")
    if version != PROTOCOL_VERSION:
        raise UnsupportedVersion(version)
    payload = data[HEADER_SIZE:HEADER_SIZE + payload_len]
    crc_carried = struct.unpack_from("<I", data, HEADER_SIZE + payload_len)[0]
    crc_computed = compute_crc(version, seq_id, opcode, payload)
    if crc_carried != crc_computed:
        raise CrcMismatch(crc_computed, crc_carried)
    return Frame(version=version, seq_id=seq_id, opcode=opcode,
                 payload=payload, crc32=crc_carried)


__all__ = [
    "PROTOCOL_VERSION",
    "HEADER_SIZE",
    "CRC_SIZE",
    "MAX_PAYLOAD_LEN",
    "MAX_FRAME_SIZE",
    "Frame",
    "FrameError",
    "CrcMismatch",
    "MalformedFrame",
    "UnsupportedVersion",
    "compute_crc",
    "encode_frame",
    "decode_frame",
]