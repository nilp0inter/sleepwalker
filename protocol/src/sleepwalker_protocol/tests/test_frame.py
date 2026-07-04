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
from sleepwalker_protocol.usages import USB_KEY_SPACE, usage_by_name, usage_by_usb, usage_by_evdev


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