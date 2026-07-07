"""Frame encoding/decoding and CRC tests (no hardware required)."""
from __future__ import annotations

import struct
import zlib

import pytest

from sleepwalker_protocol.frame import (
    PROTOCOL_VERSION,
    HEADER_SIZE,
    CRC_SIZE,
    MAX_PAYLOAD_LEN,
    Frame,
    CrcMismatch,
    MalformedFrame,
    UnsupportedVersion,
    compute_crc,
    encode_frame,
    decode_frame,
)
from sleepwalker_protocol.opcodes import (
    OPCODE_ARM,
    OPCODE_KEY_TAP,
    OPCODE_KILL,
    OPCODE_RELEASE_ALL,
    OPCODE_UNSUPPORTED_FIXTURE,
    is_known_opcode,
)
from sleepwalker_protocol.status import (
    STATUS_RECEIVED,
    STATUS_QUEUED,
    STATUS_SENT_TO_USB,
    STATUS_BAD_CRC,
    STATUS_UNSUPPORTED_OPCODE,
    STATUS_DISARMED,
    STATUS_MALFORMED,
    STATUS_QUEUE_FULL,
    STATUS_USB_NOT_MOUNTED,
    STATUS_KILLED,
    is_known_status,
)
from sleepwalker_protocol.usages import (
    USB_KEY_SPACE, USB_KEY_NONE, USAGE_REGISTRY,
    usage_by_name, usage_by_usb, usage_by_evdev
)


class TestFrameLayout:
    """Task 2.1: versioned command frame layout, seq id, opcode, payload len, payload, CRC-32."""

    def test_header_size_is_fixed(self):
        # ver(1) + seq(2) + opcode(2) + payload_len(2) = 7
        assert HEADER_SIZE == 7

    def test_crc_size_is_four(self):
        assert CRC_SIZE == 4

    def test_protocol_version_is_one(self):
        assert PROTOCOL_VERSION == 1

    def test_round_trip_minimal_frame(self):
        data = encode_frame(seq_id=1, opcode=OPCODE_ARM, payload=b"")
        assert len(data) == HEADER_SIZE + CRC_SIZE
        frame = decode_frame(data)
        assert frame.version == PROTOCOL_VERSION
        assert frame.seq_id == 1
        assert frame.opcode == OPCODE_ARM
        assert frame.payload == b""
        assert frame.payload_len == 0

    def test_round_trip_with_payload(self):
        payload = bytes([USB_KEY_SPACE.usb_usage])
        data = encode_frame(seq_id=42, opcode=OPCODE_KEY_TAP, payload=payload)
        frame = decode_frame(data)
        assert frame.seq_id == 42
        assert frame.opcode == OPCODE_KEY_TAP
        assert frame.payload == payload
        assert frame.payload_len == 1

    def test_seq_id_correlation_survives_round_trip(self):
        for seq in (0, 1, 0x1234, 0xFFFF):
            data = encode_frame(seq_id=seq, opcode=OPCODE_ARM)
            assert decode_frame(data).seq_id == seq

    def test_payload_max_length_accepted(self):
        payload = b"\x00" * MAX_PAYLOAD_LEN
        data = encode_frame(seq_id=1, opcode=OPCODE_KEY_TAP, payload=payload)
        frame = decode_frame(data)
        assert len(frame.payload) == MAX_PAYLOAD_LEN

    def test_payload_too_large_rejected(self):
        with pytest.raises(ValueError):
            encode_frame(seq_id=1, opcode=OPCODE_KEY_TAP, payload=b"\x00" * (MAX_PAYLOAD_LEN + 1))

    def test_crc_matches_zlib_crc32(self):
        payload = bytes([USB_KEY_SPACE.usb_usage])
        data = encode_frame(seq_id=1, opcode=OPCODE_KEY_TAP, payload=payload)
        # Recompute the CRC the same way and compare to the trailer.
        import struct as _s
        header = _s.Struct("<BHHH").pack(PROTOCOL_VERSION, 1, OPCODE_KEY_TAP, len(payload))
        expected = zlib.crc32(header + payload) & 0xFFFFFFFF
        carried = _s.unpack_from("<I", data, len(data) - CRC_SIZE)[0]
        assert carried == expected


class TestCrcCorruption:
    """Task 2.1 scenario: corrupt frame rejected."""

    def test_crc_mismatch_raises(self):
        data = bytearray(encode_frame(seq_id=1, opcode=OPCODE_ARM))
        data[-1] ^= 0xFF  # corrupt the CRC trailer
        with pytest.raises(CrcMismatch):
            decode_frame(bytes(data))

    def test_crc_mismatch_carries_expected_and_got(self):
        data = bytearray(encode_frame(seq_id=1, opcode=OPCODE_ARM))
        data[-1] ^= 0xFF
        try:
            decode_frame(bytes(data))
            assert False, "expected CrcMismatch"
        except CrcMismatch as exc:
            assert exc.expected != exc.got


class TestMalformed:
    def test_too_short_raises(self):
        with pytest.raises(MalformedFrame):
            decode_frame(b"\x00")

    def test_length_mismatch_raises(self):
        # Claim a payload_len of 5 but provide only 1 byte.
        header = struct.pack("<BHHH", PROTOCOL_VERSION, 1, OPCODE_ARM, 5)
        bad = header + b"\x00" + struct.pack("<I", 0)
        with pytest.raises(MalformedFrame):
            decode_frame(bad)

    def test_unsupported_version_raises(self):
        data = bytearray(encode_frame(seq_id=1, opcode=OPCODE_ARM))
        data[0] = 0xFE  # unsupported version
        with pytest.raises(UnsupportedVersion):
            decode_frame(bytes(data))


class TestSymbolicUsages:
    """Task 2.2: symbolic HID usages and canonical USB_KEY_SPACE mapping."""

    def test_usb_key_space_name(self):
        assert USB_KEY_SPACE.name == "USB_KEY_SPACE"

    def test_usb_key_space_maps_to_usb_usage_0x2c(self):
        assert USB_KEY_SPACE.usb_usage == 0x2C

    def test_usb_key_space_maps_to_evdev_key_space(self):
        # Linux evdev KEY_SPACE is 0x39.
        assert USB_KEY_SPACE.evdev_code == 0x39

    def test_usage_by_name_resolves(self):
        assert usage_by_name("USB_KEY_SPACE") is USB_KEY_SPACE

    def test_usage_by_usb_resolves(self):
        assert usage_by_usb(0x2C) is USB_KEY_SPACE

    def test_usage_by_evdev_resolves(self):
        assert usage_by_evdev(0x39) is USB_KEY_SPACE

    def test_usages_parity(self):
        expected = {
            "USB_KEY_NONE": (0x00, 0),
            "USB_KEY_A": (0x04, 30),
            "USB_KEY_B": (0x05, 48),
            "USB_KEY_C": (0x06, 46),
            "USB_KEY_D": (0x07, 32),
            "USB_KEY_E": (0x08, 18),
            "USB_KEY_F": (0x09, 33),
            "USB_KEY_G": (0x0A, 34),
            "USB_KEY_H": (0x0B, 35),
            "USB_KEY_I": (0x0C, 23),
            "USB_KEY_J": (0x0D, 36),
            "USB_KEY_K": (0x0E, 37),
            "USB_KEY_L": (0x0F, 38),
            "USB_KEY_M": (0x10, 50),
            "USB_KEY_N": (0x11, 49),
            "USB_KEY_O": (0x12, 24),
            "USB_KEY_P": (0x13, 25),
            "USB_KEY_Q": (0x14, 16),
            "USB_KEY_R": (0x15, 19),
            "USB_KEY_S": (0x16, 31),
            "USB_KEY_T": (0x17, 20),
            "USB_KEY_U": (0x18, 22),
            "USB_KEY_V": (0x19, 47),
            "USB_KEY_W": (0x1A, 17),
            "USB_KEY_X": (0x1B, 45),
            "USB_KEY_Y": (0x1C, 21),
            "USB_KEY_Z": (0x1D, 44),
            "USB_KEY_1": (0x1E, 2),
            "USB_KEY_2": (0x1F, 3),
            "USB_KEY_3": (0x20, 4),
            "USB_KEY_4": (0x21, 5),
            "USB_KEY_5": (0x22, 6),
            "USB_KEY_6": (0x23, 7),
            "USB_KEY_7": (0x24, 8),
            "USB_KEY_8": (0x25, 9),
            "USB_KEY_9": (0x26, 10),
            "USB_KEY_0": (0x27, 11),
            "USB_KEY_ENTER": (0x28, 28),
            "USB_KEY_ESCAPE": (0x29, 1),
            "USB_KEY_SPACE": (0x2C, 57),
            "USB_KEY_MINUS": (0x2D, 12),
            "USB_KEY_EQUAL": (0x2E, 13),
            "USB_KEY_LEFTBRACE": (0x2F, 26),
            "USB_KEY_RIGHTBRACE": (0x30, 27),
            "USB_KEY_BACKSLASH": (0x31, 43),
            "USB_KEY_SEMICOLON": (0x33, 39),
            "USB_KEY_APOSTROPHE": (0x34, 40),
            "USB_KEY_GRAVE": (0x35, 41),
            "USB_KEY_COMMA": (0x36, 51),
            "USB_KEY_DOT": (0x37, 52),
            "USB_KEY_SLASH": (0x38, 53),
            "USB_KEY_NONUSBACKSLASH": (0x32, 86),
            "USB_KEY_RO": (0x87, 135),
            "USB_KEY_LEFTCTRL": (0xE0, 29),
            "USB_KEY_LEFTSHIFT": (0xE1, 42),
            "USB_KEY_LEFTALT": (0xE2, 56),
            "USB_KEY_LEFTMETA": (0xE3, 125),
            "USB_KEY_RIGHTCTRL": (0xE4, 97),
            "USB_KEY_RIGHTSHIFT": (0xE5, 54),
            "USB_KEY_RIGHTALT": (0xE6, 100),
            "USB_KEY_RIGHTMETA": (0xE7, 126),
        }
        assert len(USAGE_REGISTRY) == len(expected)
        for name, (usb, evdev) in expected.items():
            u = USAGE_REGISTRY[name]
            assert u.name == name
            assert u.usb_usage == usb
            assert u.evdev_code == evdev

    def test_unknown_name_raises(self):
        with pytest.raises(KeyError):
            usage_by_name("USB_KEY_NONEXISTENT")


class TestOpcodes:
    """Task 2.1 / 2.3: opcodes are defined and validated."""

    def test_known_opcodes(self):
        assert is_known_opcode(OPCODE_ARM)
        assert is_known_opcode(OPCODE_KEY_TAP)
        assert is_known_opcode(OPCODE_KILL)
        assert is_known_opcode(OPCODE_RELEASE_ALL)

    def test_unsupported_opcode_fixture_is_unknown(self):
        assert not is_known_opcode(OPCODE_UNSUPPORTED_FIXTURE)


class TestStatus:
    """Task 2.3: ACK/status values for received, queued, sent-to-USB, and rejections."""

    def test_lifecycle_statuses_distinct(self):
        chain = {STATUS_RECEIVED, STATUS_QUEUED, STATUS_SENT_TO_USB}
        assert len(chain) == 3

    def test_rejection_statuses_distinct(self):
        rejections = {
            STATUS_MALFORMED,
            STATUS_BAD_CRC,
            STATUS_DISARMED,
            STATUS_QUEUE_FULL,
            STATUS_USB_NOT_MOUNTED,
            STATUS_UNSUPPORTED_OPCODE,
            STATUS_KILLED,
        }
        assert len(rejections) == 7

    def test_all_statuses_known(self):
        for s in (STATUS_RECEIVED, STATUS_QUEUED, STATUS_SENT_TO_USB,
                  STATUS_MALFORMED, STATUS_BAD_CRC, STATUS_DISARMED,
                  STATUS_QUEUE_FULL, STATUS_USB_NOT_MOUNTED,
                  STATUS_UNSUPPORTED_OPCODE, STATUS_KILLED):
            assert is_known_status(s)