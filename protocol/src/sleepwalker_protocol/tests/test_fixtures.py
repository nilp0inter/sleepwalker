"""Golden-frame fixture tests (no hardware required).

Verifies that the canonical fixtures:
  - round-trip cleanly through encode/decode for the valid cases,
  - are rejected with the right error for bad_crc and unsupported_opcode,
  - agree with the on-disk .bin/.json fixture set under protocol/fixtures.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from sleepwalker_protocol.frame import (
    PROTOCOL_VERSION,
    CrcMismatch,
    decode_frame,
    encode_frame,
)
from sleepwalker_protocol.fixtures import build_fixtures, write_fixtures, load_fixtures
from sleepwalker_protocol.opcodes import (
    OPCODE_ARM,
    OPCODE_DISARM,
    OPCODE_KILL,
    OPCODE_KEY_TAP,
    OPCODE_MOUSE_REL_REPORT,
    OPCODE_RELEASE_ALL,
    OPCODE_ABS_POINTER_BASE,
    OPCODE_SERIAL_BASE,
    OPCODE_UNSUPPORTED_FIXTURE,
    is_known_opcode,
    is_reserved_future_opcode,
)
from sleepwalker_protocol.usages import USB_KEY_SPACE
from sleepwalker_protocol.mouse import (
    MOUSE_BUTTON_LEFT,
    decode_mouse_rel,
    encode_mouse_rel,
    is_valid_mouse_rel_payload,
)


# Location of the checked-in fixtures (protocol/fixtures relative to repo).
_FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures"


class TestFixtureBuild:
    """Task 2.4: golden-frame fixtures for all canonical cases."""

    def test_build_fixtures_has_all_required_cases(self):
        fixtures = build_fixtures()
        required = {
            "valid_usb_key_space",
            "valid_usb_key_f24",
            "bad_crc",
            "unsupported_opcode",
            "arm",
            "disarm",
            "kill",
            "release_all",
            "mouse_click_down",
            "mouse_click_up",
            "mouse_move",
            "mouse_malformed_len",
            "reserved_abs_pointer",
            "reserved_serial",
        }
        assert required.issubset(fixtures.keys())

    def test_valid_usb_key_space_round_trips(self):
        meta = build_fixtures()["valid_usb_key_space"]
        data = bytes.fromhex(meta["frame_hex"])
        frame = decode_frame(data)
        assert frame.seq_id == meta["seq_id"]
        assert frame.opcode == OPCODE_KEY_TAP
        assert frame.payload == bytes([USB_KEY_SPACE.usb_usage])


    def test_valid_usb_key_f24_round_trips_as_canonical_key_tap(self):
        meta = build_fixtures()["valid_usb_key_f24"]
        data = bytes.fromhex(meta["frame_hex"])
        assert data.hex() == "01080011000100731365290f"
        frame = decode_frame(data)
        assert frame.seq_id == 8
        assert frame.opcode == OPCODE_KEY_TAP
        assert frame.payload == b"\x73"

    def test_arm_frame_round_trips(self):
        meta = build_fixtures()["arm"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_ARM
        assert frame.payload == b""

    def test_disarm_frame_round_trips(self):
        meta = build_fixtures()["disarm"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_DISARM

    def test_kill_frame_round_trips(self):
        meta = build_fixtures()["kill"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_KILL

    def test_release_all_frame_round_trips(self):
        meta = build_fixtures()["release_all"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_RELEASE_ALL

    def test_bad_crc_fixture_is_rejected(self):
        meta = build_fixtures()["bad_crc"]
        with pytest.raises(CrcMismatch):
            decode_frame(bytes.fromhex(meta["frame_hex"]))

    def test_unsupported_opcode_fixture_decodes_but_is_unknown(self):
        meta = build_fixtures()["unsupported_opcode"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_UNSUPPORTED_FIXTURE
        assert not is_known_opcode(frame.opcode)

    def test_all_fixture_frames_match_reencode(self):
        # Every valid fixture must re-encode to the exact same bytes.
        fixtures = build_fixtures()
        for name, meta in fixtures.items():
            if name in ("bad_crc",):
                continue  # bad_crc is intentionally corrupt
            data = bytes.fromhex(meta["frame_hex"])
            frame = decode_frame(data)
            reencoded = encode_frame(
                seq_id=frame.seq_id, opcode=frame.opcode, payload=frame.payload
            )
            assert reencoded == data, f"fixture {name} did not re-encode identically"


class TestFixtureOnDisk:
    """The checked-in fixture directory must agree with build_fixtures()."""

    def test_fixtures_dir_exists(self):
        assert _FIXTURES_DIR.is_dir(), f"fixtures dir missing: {_FIXTURES_DIR}"

    def test_index_json_exists_and_lists_all_fixtures(self):
        index_path = _FIXTURES_DIR / "index.json"
        assert index_path.is_file(), "fixtures/index.json missing"
        index = json.loads(index_path.read_text())
        names = {f["name"] for f in index["fixtures"]}
        required = {
            "valid_usb_key_space", "bad_crc", "unsupported_opcode",
            "valid_usb_key_f24",
            "arm", "disarm", "kill", "release_all",
            "mouse_click_down", "mouse_click_up", "mouse_move",
            "mouse_malformed_len", "reserved_abs_pointer", "reserved_serial",
        }
        assert required.issubset(names)

    def test_disk_fixtures_match_build(self):
        on_disk = load_fixtures(_FIXTURES_DIR)
        built = build_fixtures()
        for name, meta_built in built.items():
            meta_disk = on_disk[name]
            assert meta_disk["frame_hex"] == meta_built["frame_hex"], (
                f"fixture {name} on disk does not match build_fixtures()"
            )

    def test_disk_index_protocol_version_matches(self):
        index = json.loads((_FIXTURES_DIR / "index.json").read_text())
        assert index["protocol_version"] == PROTOCOL_VERSION


def test_regenerate_to_tmp_matches_build(tmp_path):
    """Regenerating fixtures to a temp dir must produce identical bytes."""
    paths = write_fixtures(tmp_path)
    built = build_fixtures()
    for name, meta in built.items():
        disk_bytes = paths[name].read_bytes()
        assert disk_bytes == bytes.fromhex(meta["frame_hex"]), name


class TestMouseFixtures:
    """Task 1.5: golden fixtures and parity tests for relative mouse frames."""

    def test_mouse_click_down_round_trips(self):
        meta = build_fixtures()["mouse_click_down"]
        data = bytes.fromhex(meta["frame_hex"])
        frame = decode_frame(data)
        assert frame.opcode == OPCODE_MOUSE_REL_REPORT
        assert frame.seq_id == meta["seq_id"]
        assert is_valid_mouse_rel_payload(frame.payload)
        rep = decode_mouse_rel(frame.payload)
        assert rep.buttons == MOUSE_BUTTON_LEFT
        assert rep.dx == 0
        assert rep.dy == 0

    def test_mouse_click_up_round_trips(self):
        meta = build_fixtures()["mouse_click_up"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_MOUSE_REL_REPORT
        rep = decode_mouse_rel(frame.payload)
        assert rep.buttons == 0

    def test_mouse_move_round_trips(self):
        meta = build_fixtures()["mouse_move"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_MOUSE_REL_REPORT
        rep = decode_mouse_rel(frame.payload)
        assert rep.dx == 10
        assert rep.dy == -5

    def test_mouse_malformed_len_decodes_but_payload_invalid(self):
        """A MOUSE_REL_REPORT frame with wrong payload length decodes as a
        valid frame (CRC ok) but the payload is not a valid mouse report.
        Firmware must reject it as malformed before HID dispatch.
        """
        meta = build_fixtures()["mouse_malformed_len"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_MOUSE_REL_REPORT
        # The frame decodes (CRC valid) but payload length is not 5.
        assert not is_valid_mouse_rel_payload(frame.payload)
        with pytest.raises(ValueError):
            decode_mouse_rel(frame.payload)

    def test_mouse_fixtures_reencode_identically(self):
        for name in ("mouse_click_down", "mouse_click_up", "mouse_move",
                     "mouse_malformed_len"):
            meta = build_fixtures()[name]
            data = bytes.fromhex(meta["frame_hex"])
            frame = decode_frame(data)
            reencoded = encode_frame(
                seq_id=frame.seq_id, opcode=frame.opcode, payload=frame.payload)
            assert reencoded == data, f"fixture {name} did not re-encode"

    def test_mouse_payload_byte_parity(self):
        """Cross-language byte parity: the canonical mouse payloads are
        fixed so Python, Kotlin, and firmware agree on field order and
        button bit assignments.
        """
        # LEFT button down: buttons=0x01, dx=0, dy=0, wheel=0, pan=0
        assert encode_mouse_rel(MOUSE_BUTTON_LEFT, 0, 0) == bytes(
            [0x01, 0x00, 0x00, 0x00, 0x00])
        # move dx=10 dy=-5: 0x0A, 0xFB
        assert encode_mouse_rel(0, 10, -5) == bytes(
            [0x00, 0x0A, 0xFB, 0x00, 0x00])


class TestReservedFutureOpcodes:
    """Task 1.6: reserved absolute pointer and serial opcodes decode as
    valid frames but are rejected as unsupported by current dispatch.
    """

    def test_reserved_abs_pointer_decodes(self):
        meta = build_fixtures()["reserved_abs_pointer"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_ABS_POINTER_BASE
        # Decodes fine (valid frame)...
        assert frame.payload == b""
        # ...but is not a known/implemented opcode.
        assert not is_known_opcode(frame.opcode)
        # ...and is classified as a reserved future opcode.
        assert is_reserved_future_opcode(frame.opcode)

    def test_reserved_serial_decodes(self):
        meta = build_fixtures()["reserved_serial"]
        frame = decode_frame(bytes.fromhex(meta["frame_hex"]))
        assert frame.opcode == OPCODE_SERIAL_BASE
        assert not is_known_opcode(frame.opcode)
        assert is_reserved_future_opcode(frame.opcode)

    def test_reserved_opcodes_not_in_known_set(self):
        """Reserved future opcodes must not appear in ALL_OPCODES."""
        from sleepwalker_protocol.opcodes import ALL_OPCODES
        assert OPCODE_ABS_POINTER_BASE not in ALL_OPCODES
        assert OPCODE_SERIAL_BASE not in ALL_OPCODES

    def test_reserved_fixtures_reencode_identically(self):
        for name in ("reserved_abs_pointer", "reserved_serial"):
            meta = build_fixtures()[name]
            data = bytes.fromhex(meta["frame_hex"])
            frame = decode_frame(data)
            reencoded = encode_frame(
                seq_id=frame.seq_id, opcode=frame.opcode, payload=frame.payload)
            assert reencoded == data, f"fixture {name} did not re-encode"