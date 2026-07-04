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
    OPCODE_RELEASE_ALL,
    OPCODE_UNSUPPORTED_FIXTURE,
    is_known_opcode,
)
from sleepwalker_protocol.usages import USB_KEY_SPACE


# Location of the checked-in fixtures (protocol/fixtures relative to repo).
_FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures"


class TestFixtureBuild:
    """Task 2.4: golden-frame fixtures for all canonical cases."""

    def test_build_fixtures_has_all_required_cases(self):
        fixtures = build_fixtures()
        required = {
            "valid_usb_key_space",
            "bad_crc",
            "unsupported_opcode",
            "arm",
            "disarm",
            "kill",
            "release_all",
        }
        assert required.issubset(fixtures.keys())

    def test_valid_usb_key_space_round_trips(self):
        meta = build_fixtures()["valid_usb_key_space"]
        data = bytes.fromhex(meta["frame_hex"])
        frame = decode_frame(data)
        assert frame.seq_id == meta["seq_id"]
        assert frame.opcode == OPCODE_KEY_TAP
        assert frame.payload == bytes([USB_KEY_SPACE.usb_usage])

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
            "arm", "disarm", "kill", "release_all",
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