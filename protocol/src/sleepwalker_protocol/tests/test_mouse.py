"""Relative mouse report payload tests (no hardware required).

Verifies that the raw five-byte mouse payload encodes/decodes cleanly
and that the button mask and signed axis fields agree across Python,
Kotlin (see MouseRelTest.kt), and firmware (see sw_proto_decode_mouse_rel).
"""
from __future__ import annotations

import pytest

from sleepwalker_protocol.mouse import (
    MOUSE_BUTTON_LEFT,
    MOUSE_BUTTON_RIGHT,
    MOUSE_BUTTON_MIDDLE,
    MOUSE_BUTTON_ALL,
    MOUSE_REL_PAYLOAD_LEN,
    MouseRelReport,
    encode_mouse_rel,
    decode_mouse_rel,
    is_valid_mouse_rel_payload,
)


class TestMouseRelPayload:
    """Task 1.2 / 1.5: raw relative mouse report payload helpers."""

    def test_payload_len_is_five(self):
        assert MOUSE_REL_PAYLOAD_LEN == 5

    def test_button_mask_bits(self):
        assert MOUSE_BUTTON_LEFT == 0x01
        assert MOUSE_BUTTON_RIGHT == 0x02
        assert MOUSE_BUTTON_MIDDLE == 0x04

    def test_button_all_set(self):
        assert MOUSE_BUTTON_ALL == frozenset({0x01, 0x02, 0x04})

    def test_encode_left_click_down(self):
        # buttons=LEFT, dx=0, dy=0, wheel=0, pan=0
        b = encode_mouse_rel(MOUSE_BUTTON_LEFT, 0, 0)
        assert b == bytes([0x01, 0x00, 0x00, 0x00, 0x00])

    def test_encode_release(self):
        b = encode_mouse_rel(0, 0, 0)
        assert b == bytes([0x00, 0x00, 0x00, 0x00, 0x00])

    def test_encode_signed_axes(self):
        b = encode_mouse_rel(0, 10, -5, -1, 1)
        assert b == bytes([0x00, 0x0A, 0xFB, 0xFF, 0x01])

    def test_encode_negative_max(self):
        b = encode_mouse_rel(0, -128, 127, -128, 127)
        assert b == bytes([0x00, 0x80, 0x7F, 0x80, 0x7F])

    def test_encode_clamps_out_of_range(self):
        # Values outside i8 range are clamped, not wrapped.
        b = encode_mouse_rel(0, 200, -200, 0, 0)
        # 200 -> clamped to 127 (0x7F); -200 -> clamped to -128 (0x80)
        assert b == bytes([0x00, 0x7F, 0x80, 0x00, 0x00])

    def test_decode_round_trip(self):
        rep = MouseRelReport(
            buttons=MOUSE_BUTTON_LEFT | MOUSE_BUTTON_RIGHT,
            dx=42, dy=-42, wheel=1, pan=-1)
        b = rep.to_bytes()
        decoded = decode_mouse_rel(b)
        assert decoded.buttons == (MOUSE_BUTTON_LEFT | MOUSE_BUTTON_RIGHT)
        assert decoded.dx == 42
        assert decoded.dy == -42
        assert decoded.wheel == 1
        assert decoded.pan == -1

    def test_decode_signed_extrema(self):
        b = bytes([0x00, 0x80, 0x7F, 0x80, 0x7F])
        rep = decode_mouse_rel(b)
        assert rep.dx == -128
        assert rep.dy == 127
        assert rep.wheel == -128
        assert rep.pan == 127

    def test_decode_wrong_length_raises(self):
        with pytest.raises(ValueError):
            decode_mouse_rel(bytes([0x00, 0x01]))

    def test_is_valid_payload(self):
        assert is_valid_mouse_rel_payload(bytes(5))
        assert not is_valid_mouse_rel_payload(bytes(4))
        assert not is_valid_mouse_rel_payload(bytes(6))
