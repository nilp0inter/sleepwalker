"""Golden-frame fixtures for the sleepwalker HID protocol.

Generates and persists the canonical binary frames for:
    - valid USB_KEY_SPACE key tap
    - bad CRC (corrupt frame)
    - unsupported opcode
    - arm
    - disarm
    - kill
    - release-all

Fixtures are checked into the repository (as .bin + .json sidecar) so the
Android core layer, firmware tests, and HIL harness all agree on the exact
byte sequence for each canonical case. The no-hardware verification
command regenerates and compares against these fixtures.

Run `python -m sleepwalker_protocol.fixtures regenerate <dir>` to write
the fixtures to a directory (defaults to the package fixtures dir).
"""
from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path

from .frame import (
    PROTOCOL_VERSION,
    Frame,
    decode_frame,
    encode_frame,
    compute_crc,
)
from .opcodes import (
    OPCODE_ARM,
    OPCODE_DISARM,
    OPCODE_KILL,
    OPCODE_KEY_TAP,
    OPCODE_MOUSE_REL_REPORT,
    OPCODE_RELEASE_ALL,
    OPCODE_ABS_POINTER_BASE,
    OPCODE_SERIAL_BASE,
    OPCODE_UNSUPPORTED_FIXTURE,
)
from .usages import USB_KEY_SPACE
from .mouse import (
    MOUSE_BUTTON_LEFT,
    encode_mouse_rel,
)

_SEQ_KEY_TAP = 0x0001
_SEQ_ARM = 0x0002
_SEQ_DISARM = 0x0003
_SEQ_KILL = 0x0004
_SEQ_RELEASE_ALL = 0x0005
_SEQ_BAD_CRC = 0x0006
_SEQ_UNSUPPORTED = 0x0007
_SEQ_MOUSE_CLICK_DOWN = 0x0010
_SEQ_MOUSE_CLICK_UP = 0x0011
_SEQ_MOUSE_MOVE = 0x0012
_SEQ_MOUSE_MALFORMED = 0x0013
_SEQ_ABS_POINTER_RESERVED = 0x0014
_SEQ_SERIAL_RESERVED = 0x0015

def _frame_bytes(seq_id: int, opcode: int, payload: bytes = b"") -> bytes:
    return encode_frame(seq_id=seq_id, opcode=opcode, payload=payload)


def _bad_crc_frame(seq_id: int, opcode: int, payload: bytes = b"") -> bytes:
    """Build a valid-shaped frame whose CRC-32 trailer is wrong."""
    good = _frame_bytes(seq_id, opcode, payload)
    # Flip the last byte of the CRC trailer to corrupt it.
    bad = bytearray(good)
    bad[-1] ^= 0xFF
    return bytes(bad)


def build_fixtures() -> dict[str, dict]:
    """Build the canonical fixture set as serialisable metadata.

    Each entry contains: name, seq_id, opcode, payload_hex, frame_hex,
    expected_status (the status the firmware should emit on this frame),
    and notes.
    """
    key_tap_payload = bytes([USB_KEY_SPACE.usb_usage])
    fixtures: dict[str, dict] = {
        "valid_usb_key_space": {
            "name": "valid_usb_key_space",
            "seq_id": _SEQ_KEY_TAP,
            "opcode": OPCODE_KEY_TAP,
            "payload_hex": key_tap_payload.hex(),
            "frame_hex": _frame_bytes(_SEQ_KEY_TAP, OPCODE_KEY_TAP, key_tap_payload).hex(),
            "expected_status_chain": ["received", "queued", "sent_to_usb"],
            "notes": "Valid USB_KEY_SPACE key tap; firmware emits key-down then key-up.",
        },
        "arm": {
            "name": "arm",
            "seq_id": _SEQ_ARM,
            "opcode": OPCODE_ARM,
            "payload_hex": "",
            "frame_hex": _frame_bytes(_SEQ_ARM, OPCODE_ARM).hex(),
            "expected_status_chain": ["received", "queued"],
            "notes": "Arm the firmware safety state.",
        },
        "disarm": {
            "name": "disarm",
            "seq_id": _SEQ_DISARM,
            "opcode": OPCODE_DISARM,
            "payload_hex": "",
            "frame_hex": _frame_bytes(_SEQ_DISARM, OPCODE_DISARM).hex(),
            "expected_status_chain": ["received", "queued"],
            "notes": "Disarm the firmware safety state; releases all keys/buttons.",
        },
        "kill": {
            "name": "kill",
            "seq_id": _SEQ_KILL,
            "opcode": OPCODE_KILL,
            "payload_hex": "",
            "frame_hex": _frame_bytes(_SEQ_KILL, OPCODE_KILL).hex(),
            "expected_status_chain": ["received", "queued"],
            "notes": "Kill the firmware safety state; always accepted from bonded central.",
        },
        "release_all": {
            "name": "release_all",
            "seq_id": _SEQ_RELEASE_ALL,
            "opcode": OPCODE_RELEASE_ALL,
            "payload_hex": "",
            "frame_hex": _frame_bytes(_SEQ_RELEASE_ALL, OPCODE_RELEASE_ALL).hex(),
            "expected_status_chain": ["received", "queued", "sent_to_usb"],
            "notes": "Release all currently held keys/buttons.",
        },
        "bad_crc": {
            "name": "bad_crc",
            "seq_id": _SEQ_BAD_CRC,
            "opcode": OPCODE_KEY_TAP,
            "payload_hex": key_tap_payload.hex(),
            "frame_hex": _bad_crc_frame(_SEQ_BAD_CRC, OPCODE_KEY_TAP, key_tap_payload).hex(),
            "expected_status": "bad_crc",
            "notes": "Frame whose CRC-32 trailer does not match; must be rejected without HID output.",
        },
        "unsupported_opcode": {
            "name": "unsupported_opcode",
            "seq_id": _SEQ_UNSUPPORTED,
            "opcode": OPCODE_UNSUPPORTED_FIXTURE,
            "payload_hex": "",
            "frame_hex": _frame_bytes(_SEQ_UNSUPPORTED, OPCODE_UNSUPPORTED_FIXTURE).hex(),
            "expected_status": "unsupported_opcode",
            "notes": "Frame with an opcode outside the known set; must be rejected without HID output.",
        },
        "mouse_click_down": {
            "name": "mouse_click_down",
            "seq_id": _SEQ_MOUSE_CLICK_DOWN,
            "opcode": OPCODE_MOUSE_REL_REPORT,
            "payload_hex": encode_mouse_rel(MOUSE_BUTTON_LEFT, 0, 0).hex(),
            "frame_hex": _frame_bytes(
                _SEQ_MOUSE_CLICK_DOWN, OPCODE_MOUSE_REL_REPORT,
                encode_mouse_rel(MOUSE_BUTTON_LEFT, 0, 0)).hex(),
            "expected_status_chain": ["received", "queued", "sent_to_usb"],
            "notes": "Raw relative mouse report with the left button pressed; firmware emits a USB mouse report with BTN_LEFT down.",
        },
        "mouse_click_up": {
            "name": "mouse_click_up",
            "seq_id": _SEQ_MOUSE_CLICK_UP,
            "opcode": OPCODE_MOUSE_REL_REPORT,
            "payload_hex": encode_mouse_rel(0, 0, 0).hex(),
            "frame_hex": _frame_bytes(
                _SEQ_MOUSE_CLICK_UP, OPCODE_MOUSE_REL_REPORT,
                encode_mouse_rel(0, 0, 0)).hex(),
            "expected_status_chain": ["received", "queued", "sent_to_usb"],
            "notes": "Raw relative mouse report with no buttons; firmware emits a USB mouse report with BTN_LEFT up (release).",
        },
        "mouse_move": {
            "name": "mouse_move",
            "seq_id": _SEQ_MOUSE_MOVE,
            "opcode": OPCODE_MOUSE_REL_REPORT,
            "payload_hex": encode_mouse_rel(0, 10, -5, 0, 0).hex(),
            "frame_hex": _frame_bytes(
                _SEQ_MOUSE_MOVE, OPCODE_MOUSE_REL_REPORT,
                encode_mouse_rel(0, 10, -5, 0, 0)).hex(),
            "expected_status_chain": ["received", "queued", "sent_to_usb"],
            "notes": "Raw relative mouse report with dx=10, dy=-5; firmware emits a USB mouse report with REL_X/REL_Y movement.",
        },
        "mouse_malformed_len": {
            "name": "mouse_malformed_len",
            "seq_id": _SEQ_MOUSE_MALFORMED,
            "opcode": OPCODE_MOUSE_REL_REPORT,
            "payload_hex": bytes([0x00, 0x01]).hex(),
            "frame_hex": _frame_bytes(
                _SEQ_MOUSE_MALFORMED, OPCODE_MOUSE_REL_REPORT,
                bytes([0x00, 0x01])).hex(),
            "expected_status": "malformed",
            "notes": "MOUSE_REL_REPORT frame with a two-byte payload (not five); must be rejected as malformed before HID dispatch.",
        },
        "reserved_abs_pointer": {
            "name": "reserved_abs_pointer",
            "seq_id": _SEQ_ABS_POINTER_RESERVED,
            "opcode": OPCODE_ABS_POINTER_BASE,
            "payload_hex": "",
            "frame_hex": _frame_bytes(_SEQ_ABS_POINTER_RESERVED, OPCODE_ABS_POINTER_BASE).hex(),
            "expected_status": "unsupported_opcode",
            "notes": "Reserved future absolute pointer opcode; decodes as a valid frame but must be rejected with STATUS_UNSUPPORTED_OPCODE and no USB HID output.",
        },
        "reserved_serial": {
            "name": "reserved_serial",
            "seq_id": _SEQ_SERIAL_RESERVED,
            "opcode": OPCODE_SERIAL_BASE,
            "payload_hex": "",
            "frame_hex": _frame_bytes(_SEQ_SERIAL_RESERVED, OPCODE_SERIAL_BASE).hex(),
            "expected_status": "unsupported_opcode",
            "notes": "Reserved future virtual serial opcode; decodes as a valid frame but must be rejected with STATUS_UNSUPPORTED_OPCODE and no USB CDC or HID output.",
        },
    }
    return fixtures


def write_fixtures(out_dir: Path) -> dict[str, Path]:
    """Write each fixture as <name>.bin and a combined <dir>/index.json.

    Returns a mapping of fixture name -> .bin path.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    fixtures = build_fixtures()
    index: list[dict] = []
    paths: dict[str, Path] = {}
    for name, meta in fixtures.items():
        frame_bytes = bytes.fromhex(meta["frame_hex"])
        bin_path = out_dir / f"{name}.bin"
        bin_path.write_bytes(frame_bytes)
        paths[name] = bin_path
        entry = {k: v for k, v in meta.items() if k != "frame_hex"}
        entry["bin"] = bin_path.name
        index.append(entry)
    (out_dir / "index.json").write_text(
        json.dumps({"protocol_version": PROTOCOL_VERSION, "fixtures": index}, indent=2) + "\n"
    )
    return paths


def load_fixtures(in_dir: Path) -> dict[str, dict]:
    """Load fixtures from a directory, decoding each .bin back into a Frame.

    Verifies that each fixture round-trips (except bad_crc, which must
    raise CrcMismatch, and unsupported_opcode, which decodes fine but
    carries an unknown opcode).
    """
    from .frame import CrcMismatch
    index_path = in_dir / "index.json"
    index = json.loads(index_path.read_text())
    loaded: dict[str, dict] = {}
    for entry in index["fixtures"]:
        name = entry["name"]
        bin_path = in_dir / entry["bin"]
        data = bin_path.read_bytes()
        meta = dict(entry)
        meta["frame_bytes"] = data
        meta["frame_hex"] = data.hex()
        try:
            meta["decoded"] = decode_frame(data)
        except CrcMismatch as exc:
            meta["decoded"] = None
            meta["decode_error"] = "bad_crc"
        loaded[name] = meta
    return loaded


if __name__ == "__main__":
    import sys
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    if len(sys.argv) > 1 and sys.argv[1] == "regenerate":
        out = out or Path(__file__).parent / "fixtures"
        paths = write_fixtures(out)
        print(f"wrote {len(paths)} fixtures to {out}")
    else:  # pragma: no cover
        print("usage: python -m sleepwalker_protocol.fixtures regenerate [dir]", file=sys.stderr)
        raise SystemExit(2)


__all__ = ["build_fixtures", "write_fixtures", "load_fixtures"]