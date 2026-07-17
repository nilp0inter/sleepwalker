#!/usr/bin/env python3
"""sleepwalker-smoke-editor-conformance: Hypothesis-driven Editor conformance runner.

Generates related complete-text snapshot sequences using Hypothesis,
calls setText through ADB -> Android -> BLE -> firmware -> USB path,
and compares the requested, predicted, and observed Editor state
after every synchronized call.

Usage (hardware):
  smoke-editor-conformance.py <bench_toml> <out_dir> <known_hosts_tmp> \\
      [--profile quick|deep] [--seed <N>] [--timeout-mult <N>]

Usage (dry-run / schema validation):
  smoke-editor-conformance.py --dry-run --out-dir <out_dir> \\
      [--profile quick|deep] [--seed <N>] [--classify <class>]

Usage (standalone replay validation):
  smoke-editor-conformance.py --replay <replay_context.json|replay.json>

Usage (replay on hardware):
  smoke-editor-conformance.py --replay <replay_context.json|replay.json> \\
      --single-step --bench-toml <bench.toml> [--out-dir <dir>]

Failure classifications (mutually exclusive):
  pass               step completed successfully (not a failure)
  semantic           plan delivered, barrier consumed, observed != predicted/desired
  planning           pre-execution Editor rejection, no HID emitted
  fixture            fixture misbehavior (health/reset/snapshot/identity mismatch)
  sync               F24 barrier not consumed within window
  transport          BLE/firmware fault (DISARMED/QUEUE_FULL/USB_NOT_MOUNTED/KILLED/timeout)
  environment        console keymap, device enumeration, SSH unreachable
  non_reproducible   step fails once but succeeds on same-input replay
"""

import sys
import os
import json
import time
import base64
import subprocess
import string
import re
import shutil
import traceback
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Callable, Optional

from hypothesis import given, settings, Phase, HealthCheck, strategies as st
from hypothesis.database import ExampleDatabase


# ── Constants ────────────────────────────────────────────────────────────────

US_PRINTABLE_ALPHABET = (
    string.ascii_letters + string.digits +
    "!@#$%^&*()-_=+[{]}|;:'\",<.>/?~` "
)

SEQUENCE_TRANSFORMS = [
    "append", "insert_middle", "replace_middle",
    "delete_prefix", "delete_suffix", "delete_middle", "truncate",
]

CLASSIFICATIONS = frozenset({
    "semantic", "planning", "fixture", "sync", "transport",
    "environment", "non_reproducible", "completion_timeout",
})

# Fixed UI scenario step types for the deterministic Readline editor drive.
UI_STEP_TYPES = frozenset({"insert", "delete", "replace", "paste", "clear"})

# Deterministic fixed UI scenario steps. Each tuple:
#   (step_type: str, expected_text: str, adb_ops: list[tuple[str, ...]])
# adb_ops is a list of ("text", str) | ("keyevent", int) | ("keycombination", int, int).
# Uses only simple ASCII without spaces (adb input text limitation).
FIXED_UI_STEPS: list[tuple[str, str, list[tuple]]] = [
    ("insert",  "ab",     [("text", "ab")]),
    ("delete",  "a",      [("keyevent", 67)]),
    ("insert",  "acde",   [("text", "cde")]),
    ("replace", "xyz",    [("keycombination", 113, 29), ("text", "xyz")]),
    ("paste",   "xyzxyz", [
        ("keycombination", 113, 29),
        ("keycombination", 113, 31),
        ("keyevent", 123),
        ("keycombination", 113, 50),
    ]),
    ("clear",   "",       [("keycombination", 113, 29), ("keyevent", 67)]),
]


# ── Fixed UI Scenario Dry-Run Step Generator ────────────────────────────


def _generate_fixed_ui_steps(args: dict) -> list[dict]:
    """Generate deterministic fixed UI step records without hardware.

    Used by dry-run mode and tests. Args dict supports:
      - out_dir  : ignored, kept for future use
      - classify : if set, injects that classification into every step
                   (used for testing classification propagation)

    Returns a list of step dicts matching the UI step record schema.
    """
    classify = args.get("classify")
    steps: list[dict] = []
    for seq, (step_type, expected_text, _adb_ops) in enumerate(FIXED_UI_STEPS, 1):
        step: dict = {
            "seq": seq,
            "step_type": step_type,
            "desired_text": expected_text,
            "change_id": seq,
            "generation": 1,
            "editor_state": None,
            "completion_classification": None,  # Synced
            "match": True,
            "classification": "pass",
            "failure_detail": None,
            "barrier_consumed": True,
            "observed": {"buffer": expected_text, "point": len(expected_text)},
            "predicted": {"buffer": expected_text, "point": len(expected_text)},
            "duration_sec": 0.001,
        }
        if classify and classify != "pass":
            step["match"] = False
            step["barrier_consumed"] = False
            step["classification"] = classify
            if classify == "completion_timeout":
                step["failure_detail"] = "completion event not received within timeout"
            elif classify == "semantic":
                step["observed"] = {"buffer": "hxllo", "point": 2}
                step["failure_detail"] = "observed != predicted"
            elif classify == "sync":
                step["failure_detail"] = "F24 barrier not consumed"
            elif classify == "planning":
                step["failure_detail"] = "planning failure: InconsistentPrediction"
            elif classify == "transport":
                step["failure_detail"] = "transport failure: disarmed"
            elif classify == "fixture":
                step["failure_detail"] = "fixture health check failed"
            elif classify == "environment":
                step["failure_detail"] = "SSH unreachable"
            elif classify == "non_reproducible":
                step["failure_detail"] = "non-reproducible on replay"
        steps.append(step)
    return steps


# ── Parsed Editor Diagnostic ─────────────────────────────────────────────────

class EditorDiagnostic:
    """Machine-readable Editor response parsed from ADB broadcast output."""

    __slots__ = (
        "seq", "ok", "failure", "failure_class",
        "package_id", "package_version", "host_abi",
        "decoded_len", "lcp", "old_mid", "new_mid",
        "plan_ops", "predicted_buffer", "predicted_point",
        "predicted_revision", "transport_status",
    )

    def __init__(self, raw: dict[str, Any]):
        self.seq: int = raw.get("seq", 0)
        self.ok: bool = raw.get("ok", False)
        self.failure: Optional[str] = raw.get("failure")
        self.failure_class: Optional[str] = raw.get("failure_class")

        pkg = raw.get("package", {}) or {}
        self.package_id: Optional[str] = pkg.get("id")
        self.package_version: Optional[str] = pkg.get("version")
        self.host_abi: Optional[int] = pkg.get("host_abi")

        self.decoded_len: int = raw.get("decoded_len", 0)
        self.lcp: int = raw.get("lcp", 0)
        self.old_mid: str = raw.get("old_mid", "")
        self.new_mid: str = raw.get("new_mid", "")
        self.plan_ops: list[str] = raw.get("plan_ops", [])

        pred = raw.get("predicted") or {}
        self.predicted_buffer: Optional[str] = pred.get("buffer")
        self.predicted_point: Optional[int] = pred.get("point")
        self.predicted_revision: Optional[int] = pred.get("revision")

        self.transport_status: Optional[str] = raw.get("transport_status")

    def is_planning_failure(self) -> bool:
        if self.failure_class == "planning":
            return True
        if self.failure_class == "transport":
            return False
        # Heuristic: planning failures have a failure reason but no plan ops
        return self.failure is not None and not self.plan_ops

    def is_transport_failure(self) -> bool:
        return (
            self.failure_class == "transport"
            or self.transport_status
            in ("disarmed", "queue_full", "usb_not_mounted", "killed")
        )

    @classmethod
    def from_adb_response(cls, raw_stdout: str) -> "EditorDiagnostic":
        """Parse the Editor diagnostic from an ADB command response JSON.

        The adb-ops wrapper emits:
          {"ok":true,"op":"set-text-encoded","text_encoded":"...",
           "seq":N,"adb_out":"<raw adb output>"}

        The raw adb output contains the broadcast response with the
        Editor diagnostic embedded after 'Broadcast completed: result=0, data='.
        """
        try:
            wrapper = json.loads(raw_stdout)
        except (json.JSONDecodeError, ValueError):
            return cls({"seq": 0, "ok": False, "transport_status": "parse_error"})

        adb_out: str = wrapper.get("adb_out", "")
        diagnostic = cls._extract_diagnostic(adb_out)
        if diagnostic is not None:
            return cls(diagnostic)

        # Fallback: if adb_out contains a bare JSON object
        try:
            return cls(json.loads(adb_out))
        except (json.JSONDecodeError, ValueError):
            pass

        return cls({"seq": wrapper.get("seq", 0), "ok": False,
                     "transport_status": "no_diagnostic"})

    @staticmethod
    def _extract_diagnostic(adb_out: str) -> Optional[dict]:
        """Extract the nested Editor diagnostic from ordered-broadcast output."""
        match = re.search(
            r"Broadcast completed: result=\d+,\s*data=(.+)$",
            adb_out,
            re.MULTILINE,
        )
        if match is None:
            return None

        payload = match.group(1).strip()
        candidates = [payload]
        if payload.startswith('"') and payload.endswith('"'):
            # Android versions render resultData either as a JSON-quoted string
            # or between unescaped quote delimiters. Try both forms without
            # mutating escapes that belong to diagnostic field values.
            candidates.append(payload[1:-1])

        for candidate in candidates:
            try:
                diagnostic = json.loads(candidate)
                if isinstance(diagnostic, str):
                    diagnostic = json.loads(diagnostic)
            except (json.JSONDecodeError, TypeError):
                continue
            if isinstance(diagnostic, dict):
                return diagnostic
        return None

    def to_dict(self) -> dict:
        return {
            "seq": self.seq,
            "ok": self.ok,
            "failure": self.failure,
            "failure_class": self.failure_class,
            "package_id": self.package_id,
            "package_version": self.package_version,
            "host_abi": self.host_abi,
            "decoded_len": self.decoded_len,
            "lcp": self.lcp,
            "old_mid": self.old_mid,
            "new_mid": self.new_mid,
            "plan_ops": self.plan_ops,
            "predicted_buffer": self.predicted_buffer,
            "predicted_point": self.predicted_point,
            "predicted_revision": self.predicted_revision,
            "transport_status": self.transport_status,
        }


# ── Fixture Response ─────────────────────────────────────────────────────────

class FixtureResponse:
    """Parsed response from readline-fixture-ctl operations."""

    __slots__ = ("ok", "data", "raw")

    def __init__(self, raw_stdout: str):
        self.raw = raw_stdout.strip()
        try:
            parsed = json.loads(self.raw)
            self.ok = parsed.get("ok", False)
            self.data = parsed
        except (json.JSONDecodeError, ValueError):
            self.ok = False
            self.data = {}

    def require_ok(self, operation: str) -> None:
        if not self.ok:
            raise RuntimeError(
                f"fixture {operation} failed: {self.raw}"
            )


# ── Snapshot Sequence Strategy ───────────────────────────────────────────────

@st.composite
def snapshot_sequences(draw, max_len=20, max_steps=5):
    """Generate related complete-text sequences using LCP/LCS-friendly transforms.

    Yields lists of strings where each element is derived from the previous
    via append, insert-middle, replace-middle, delete-prefix/suffix/middle,
    or truncate operations. The first element is the starting snapshot.
    """
    # Start text: 0 to max_len chars
    start = draw(
        st.text(alphabet=US_PRINTABLE_ALPHABET, min_size=0, max_size=max_len)
    )

    # Number of transforms to apply
    n_transforms = draw(st.integers(min_value=1, max_value=max_steps))

    # Pick transform types
    transforms = draw(
        st.lists(
            st.sampled_from(SEQUENCE_TRANSFORMS),
            min_size=n_transforms,
            max_size=n_transforms,
        )
    )

    snapshots = [start]
    current = start

    for transform in transforms:
        if not current:
            # From empty: only append or insert_middle make sense
            allowed = ["append", "insert_middle"]
            if transform not in allowed:
                transform = draw(st.sampled_from(allowed))

        if transform == "append":
            suffix = draw(
                st.text(alphabet=US_PRINTABLE_ALPHABET, min_size=0, max_size=5)
            )
            current = current + suffix

        elif transform == "insert_middle":
            inset = draw(
                st.text(alphabet=US_PRINTABLE_ALPHABET, min_size=0, max_size=5)
            )
            if not current:
                current = inset
            else:
                pos = draw(st.integers(min_value=0, max_value=len(current)))
                current = current[:pos] + inset + current[pos:]

        elif transform == "replace_middle":
            if not current:
                current = draw(
                    st.text(alphabet=US_PRINTABLE_ALPHABET, min_size=0, max_size=5)
                )
            else:
                start_pos = draw(
                    st.integers(min_value=0, max_value=len(current) - 1)
                )
                end_pos = draw(
                    st.integers(min_value=start_pos, max_value=len(current))
                )
                replacement = draw(
                    st.text(
                        alphabet=US_PRINTABLE_ALPHABET, min_size=0, max_size=5
                    )
                )
                current = current[:start_pos] + replacement + current[end_pos:]

        elif transform == "delete_prefix":
            if current:
                n = draw(st.integers(min_value=0, max_value=len(current)))
                current = current[n:]

        elif transform == "delete_suffix":
            if current:
                n = draw(st.integers(min_value=0, max_value=len(current)))
                current = current[: len(current) - n]

        elif transform == "delete_middle":
            if not current:
                pass  # stay empty
            elif len(current) == 1:
                current = ""
            else:
                start_pos = draw(
                    st.integers(min_value=0, max_value=len(current) - 1)
                )
                end_pos = draw(
                    st.integers(min_value=start_pos + 1, max_value=len(current))
                )
                current = current[:start_pos] + current[end_pos:]

        elif transform == "truncate":
            if current:
                n = draw(st.integers(min_value=0, max_value=len(current)))
                current = current[:n]

        snapshots.append(current)

    return snapshots


# ── CLI Args ─────────────────────────────────────────────────────────────────

def parse_args(argv: list[str]) -> dict:
    args: dict = {
        "bench_toml": None,
        "out_dir": None,
        "known_hosts": None,
        "profile": "quick",
        "seed": None,
        "dry_run": False,
        "replay": None,
        "single_step": False,
        "classify": None,
        "timeout_mult": 1.0,
    }

    # Replay mode
    if "--replay" in argv:
        idx = argv.index("--replay")
        if idx + 1 < len(argv):
            args["replay"] = argv[idx + 1]
        if "--single-step" in argv:
            args["single_step"] = True
        # Parse bench.toml for --replay --single-step (hardware replay
        # needs hardware addresses)
        if "--bench-toml" in argv:
            bidx = argv.index("--bench-toml")
            if bidx + 1 < len(argv):
                args["bench_toml"] = argv[bidx + 1]
        # Also accept positional bench.toml after --single-step
        # e.g. --replay <file> --single-step <bench.toml>
        if args.get("single_step") and not args.get("bench_toml"):
            # Look for a positional arg that's not a flag
            for a in argv:
                if a not in ("--replay", "--single-step") and \
                   not a.startswith("--") and a != args.get("replay"):
                    args["bench_toml"] = a
                    break
        # Parse out-dir for replay artifacts
        if "--out-dir" in argv:
            oidx = argv.index("--out-dir")
            if oidx + 1 < len(argv):
                args["out_dir"] = argv[oidx + 1]
        return args

    # Dry-run mode
    if "--dry-run" in argv:
        args["dry_run"] = True
        if "--out-dir" in argv:
            idx = argv.index("--out-dir")
            if idx + 1 < len(argv):
                args["out_dir"] = argv[idx + 1]
        if "--classify" in argv:
            idx = argv.index("--classify")
            if idx + 1 < len(argv):
                args["classify"] = argv[idx + 1]
        if "--profile" in argv:
            idx = argv.index("--profile")
            if idx + 1 < len(argv):
                args["profile"] = argv[idx + 1]
        if "--seed" in argv:
            idx = argv.index("--seed")
            if idx + 1 < len(argv):
                args["seed"] = int(argv[idx + 1])
        return args

    # Hardware mode
    if len(argv) < 4:
        print(
            "Usage: smoke-editor-conformance.py <bench_toml> <out_dir>"
            " <known_hosts> [--profile quick|deep] [--seed <N>]"
            " [--timeout-mult <N>]",
            file=sys.stderr,
        )
        print(
            "       smoke-editor-conformance.py --dry-run --out-dir <dir>"
            " [--profile quick|deep] [--seed <N>] [--classify <class>]",
            file=sys.stderr,
        )
        print(
            "       smoke-editor-conformance.py --replay <replay.json>"
            " [--single-step]",
            file=sys.stderr,
        )
        sys.exit(2)

    args["bench_toml"] = argv[1]
    args["out_dir"] = argv[2]
    args["known_hosts"] = argv[3]

    if "--profile" in argv:
        idx = argv.index("--profile")
        if idx + 1 < len(argv):
            args["profile"] = argv[idx + 1]

    if "--seed" in argv:
        idx = argv.index("--seed")
        if idx + 1 < len(argv):
            args["seed"] = int(argv[idx + 1])

    if "--timeout-mult" in argv:
        idx = argv.index("--timeout-mult")
        if idx + 1 < len(argv):
            args["timeout_mult"] = float(argv[idx + 1])

    return args


# ── Command Runner ───────────────────────────────────────────────────────────

def run_cmd(args_list: list[str], capture_output=True, check=True,
            timeout=None) -> subprocess.CompletedProcess:
    """Run a subprocess command with logging."""
    try:
        res = subprocess.run(
            args_list,
            capture_output=capture_output,
            text=True,
            timeout=timeout,
        )
        if check and res.returncode != 0:
            raise subprocess.CalledProcessError(
                res.returncode, args_list, res.stdout, res.stderr
            )
        return res
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f"command timed out: {' '.join(args_list)}") from e


# ── Fixture Helpers ──────────────────────────────────────────────────────────

def fixture_ctl(ssh_target: str, operation: str,
                identity: str = "", known_hosts: str = "",
                check: bool = True) -> FixtureResponse:
    """Invoke readline-fixture-ctl and return parsed response."""
    cmd = [
        "sleepwalker-readline-fixture-ctl",
        ssh_target,
        operation,
        "",  # timeout is used only by await_barrier
        identity,
        known_hosts,
    ]
    res = run_cmd(cmd, check=check)
    resp = FixtureResponse(res.stdout)
    if check:
        resp.require_ok(operation)
    return resp


def fixture_start(ssh_target: str, identity: str = "",
                  known_hosts: str = "") -> None:
    """Start the Readline fixture on the observer."""
    cmd = [
        "sleepwalker-readline-fixture-start",
        ssh_target,
        identity,
        known_hosts,
    ]
    run_cmd(cmd)


def fixture_describe(ssh_target: str, identity: str = "",
                     known_hosts: str = "") -> dict:
    """Get fixture description."""
    return fixture_ctl(ssh_target, "describe", identity, known_hosts).data


def fixture_reset(ssh_target: str, identity: str = "",
                  known_hosts: str = "") -> None:
    """Reset fixture to empty known state."""
    fixture_ctl(ssh_target, "reset", identity, known_hosts)


def fixture_await_barrier(ssh_target: str, identity: str = "",
                          known_hosts: str = "",
                          timeout_sec: float = 15.0) -> bool:
    """Wait for fixture to confirm F24 consumption. Returns True if consumed."""
    try:
        cmd = [
            "sleepwalker-readline-fixture-ctl",
            ssh_target,
            "await_barrier",
            str(round(timeout_sec * 1000)),
            identity,
            known_hosts,
        ]
        res = run_cmd(cmd, timeout=timeout_sec + 2.0)
        response = FixtureResponse(res.stdout)
        response.require_ok("await_barrier")
        return response.data.get("status") == "ok"
    except (RuntimeError, subprocess.TimeoutExpired,
            subprocess.CalledProcessError):
        return False


def fixture_snapshot(ssh_target: str, identity: str = "",
                     known_hosts: str = "") -> dict:
    """Get authoritative fixture snapshot."""
    return fixture_ctl(ssh_target, "snapshot", identity, known_hosts).data


def fixture_health(ssh_target: str, identity: str = "",
                   known_hosts: str = "") -> dict:
    """Get fixture health status."""
    try:
        return fixture_ctl(ssh_target, "health", identity, known_hosts,
                           check=True).data
    except (RuntimeError, subprocess.CalledProcessError):
        return {"ok": False, "alive": False, "responsive": False,
                "baseline": False}


def fixture_shutdown(ssh_target: str, identity: str = "",
                     known_hosts: str = "") -> None:
    """Shut down fixture cleanly."""
    try:
        fixture_ctl(ssh_target, "shutdown", identity, known_hosts,
                    check=False)
    except Exception:
        pass


# ── ADB Helpers ──────────────────────────────────────────────────────────────

def adb_set_text_encoded(serial: str, text: str, seq: int) -> EditorDiagnostic:
    """Send set-text via ADB and return parsed Editor diagnostic."""
    encoded = base64.urlsafe_b64encode(text.encode("utf-8")).decode("ascii")
    res = run_cmd([
        "sleepwalker-adb-set-text-encoded", serial, encoded, str(seq),
    ])
    return EditorDiagnostic.from_adb_response(res.stdout.strip())


def adb_connect(serial: str, seq: int) -> None:
    """BLE connect Android to ESP32-S3."""
    run_cmd(["sleepwalker-adb-connect", serial, str(seq)])


def adb_arm(serial: str, seq: int) -> None:
    """Arm the firmware safety state."""
    run_cmd(["sleepwalker-adb-arm", serial, str(seq)])


def adb_reset_editor(serial: str, seq: int) -> None:
    """Reset the app Editor to empty known state without emitting HID."""
    run_cmd(["sleepwalker-adb-reset-editor", serial, str(seq)])


def adb_inject_f24(serial: str, seq: int) -> None:
    """Inject USB_KEY_F24 as a separate HID operation (never in Editor plan)."""
    run_cmd(["sleepwalker-adb-inject-key", serial, "USB_KEY_F24", str(seq)])


def adb_release_all(serial: str, seq: int) -> None:
    """Release all held keys."""
    run_cmd(["sleepwalker-adb-release-all", serial, str(seq)], check=False)


def adb_kill(serial: str, seq: int) -> None:
    """Kill the firmware safety state."""
    run_cmd(["sleepwalker-adb-kill", serial, str(seq)], check=False)



# ── Fixed UI Scenario ADB Helpers ──────────────────────────────────────────

def adb_launch_readline(serial: str) -> None:
    """Launch MainActivity in Readline editor mode via EXTRA_READLINE extra."""
    run_cmd(["sleepwalker-adb-launch-readline", serial])


def adb_input_text(serial: str, text: str) -> None:
    """Type text into the currently focused EditText field."""
    run_cmd(["sleepwalker-adb-input-text", serial, text])


def adb_keyevent(serial: str, keycode: int) -> None:
    """Send a key event by Android keycode."""
    run_cmd(["sleepwalker-adb-keyevent", serial, str(keycode)])


def adb_keycombination(serial: str, keycode: int, metastate: int) -> None:
    """Send a key combination (keycode + metastate)."""
    run_cmd(["sleepwalker-adb-keycombination", serial, str(keycode), str(metastate)])


def adb_dismiss_keyguard(serial: str) -> None:
    """Dismiss the Android lock screen / keyguard."""
    run_cmd(["sleepwalker-adb-dismiss-keyguard", serial])


def execute_ui_adb_ops(serial: str, ops: list[tuple]) -> None:
    """Execute a list of ADB operations for one UI step.

    ops items: ("text", str) | ("keyevent", int) | ("keycombination", int, int)
    """
    for op in ops:
        kind = op[0]
        if kind == "text":
            adb_input_text(serial, op[1])
        elif kind == "keyevent":
            adb_keyevent(serial, op[1])
        elif kind == "keycombination":
            adb_keycombination(serial, op[1], op[2])
        else:
            raise ValueError(f"unknown UI ADB op: {kind}")

# ── Artifact Writer ──────────────────────────────────────────────────────────

class ArtifactWriter:
    """Writes structured conformance artifacts under out_dir."""

    def __init__(self, out_dir: str):
        self.out_dir = Path(out_dir)
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.replay_dir = self.out_dir / "replay"
        self.replay_dir.mkdir(exist_ok=True)
        self.fixture_snapshot_path = self.out_dir / "fixture_snapshot.jsonl"
        self.hid_observer_path = self.out_dir / "hid_observer.jsonl"
        self.esp_uart_path = self.out_dir / "esp_uart.jsonl"
        self.android_logcat_path = self.out_dir / "android_logcat.jsonl"
        self.summary_path = self.out_dir / "summary.json"
        self.result_txt_path = self.out_dir / "result.txt"
        self.steps: list[dict] = []
        self.classification_totals: dict[str, int] = {c: 0 for c in CLASSIFICATIONS}

    def write_fixture_snapshot(self, data: dict) -> None:
        with open(self.fixture_snapshot_path, "a") as f:
            f.write(json.dumps(data) + "\n")

    def record_step(self, step: dict) -> None:
        self.steps.append(step)
        cls = step.get("classification", "environment")
        if cls in self.classification_totals:
            self.classification_totals[cls] += 1

        # Write per-step replay data for failing steps
        if step.get("match") is False:
            replay_data = {
                "seq": step.get("seq"),
                "desired": step.get("desired"),
                "lcp": step.get("lcp"),
                "old_mid": step.get("old_mid"),
                "new_mid": step.get("new_mid"),
                "plan_ops": step.get("plan_ops"),
                "predicted": step.get("predicted"),
                "observed": step.get("observed"),
                "classification": cls,
                "barrier_consumed": step.get("barrier_consumed", False),
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
            step_dir = self.replay_dir / f"step_{step['seq']}_{cls}"
            step_dir.mkdir(exist_ok=True)
            with open(step_dir / "replay.json", "w") as f:
                json.dump(replay_data, f, indent=2)
            desired = step.get("desired", "")
            with open(step_dir / "desired.txt", "w") as f:
                f.write(desired)
            pred = step.get("predicted", {}) or {}
            with open(step_dir / "predicted.txt", "w") as f:
                f.write(pred.get("buffer", ""))
            obs = step.get("observed", {}) or {}
            with open(step_dir / "observed.txt", "w") as f:
                f.write(obs.get("buffer", ""))

    def write_summary(self, summary: dict) -> None:
        summary["per_step"] = self.steps
        summary["classification_totals"] = dict(self.classification_totals)
        with open(self.summary_path, "w") as f:
            json.dump(summary, f, indent=2)

    def write_result_txt(self, status: str, details: str = "") -> None:
        with open(self.result_txt_path, "w") as f:
            f.write(f"{status.upper()}: Editor conformance scenario\n")
            if details:
                f.write(f"Details: {details}\n")
            f.write(f"Steps: {len(self.steps)}\n")
            f.write(f"Classification totals: {json.dumps(self.classification_totals)}\n")

    def mark_hid_observer_diagnostics_only(self) -> None:
        """Write marker file so consumers know hid observer is non-authoritative."""
        marker = self.out_dir / "hid_observer.diagnostics_only"
        marker.write_text(
            "This HID observer output is diagnostics-only. "
            "Do not use for authoritative synchronization or state read-back.\n"
        )

    def copy_bench_config(self, bench_path: str) -> None:
        shutil.copy2(bench_path, self.out_dir / "bench.toml")

    def write_replay_context(
        self,
        full_sequence: list[str],
        failing_prefix: list[str],
        seed: Optional[int],
        profile: str,
        max_len: int,
        max_steps: int,
        hypothesis_failure: Optional[str],
    ) -> None:
        """Persist full shrunk sequence/prefix, seed and replay context.

        Writes a replay_context.json containing the full Hypothesis-generated
        sequence, the failing prefix (for single-step replay), the seed, and
        the generation parameters. Also writes sequence.txt and prefix.txt
        for human-readable recovery.
        """
        replay_context = {
            "full_sequence": full_sequence,
            "failing_prefix": failing_prefix,
            "seed": seed,
            "profile": profile,
            "max_len": max_len,
            "max_steps": max_steps,
            "hypothesis_failure": hypothesis_failure,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "sequence_length": len(full_sequence),
            "prefix_length": len(failing_prefix),
        }
        ctx_path = self.out_dir / "replay_context.json"
        with open(ctx_path, "w") as f:
            json.dump(replay_context, f, indent=2)

        # Human-readable sequence for deterministic recovery
        with open(self.out_dir / "sequence.txt", "w") as f:
            for i, s in enumerate(full_sequence):
                f.write(f"[{i}] {s}\n")

        if failing_prefix:
            with open(self.out_dir / "failing_prefix.txt", "w") as f:
                for i, s in enumerate(failing_prefix):
                    f.write(f"[{i}] {s}\n")


# ── Conformance Runner ───────────────────────────────────────────────────────

class ConformanceRunner:
    """Orchestrates the Editor conformance scenario."""

    def __init__(self, args: dict):
        self.args = args
        self.bench: Optional[dict] = None
        self.observer_target: str = ""
        self.android_serial: str = ""
        self.observer_identity: str = ""
        self.known_hosts: str = ""
        self.artifacts: Optional[ArtifactWriter] = None
        self.seq_counter: int = 0
        self.fqdn_package_id: Optional[str] = None
        self.fqdn_package_version: Optional[str] = None
        self.fqdn_host_abi: Optional[int] = None
        self.fixture_identity: Optional[dict] = None
        self.fixture_control_abi: Optional[int] = None
        self.fixture_health_status: Optional[dict] = None
        self.capture_pids: list[int] = []
        self.start_time: float = 0.0
        self.all_steps: list[dict] = []
        self.failing_steps: list[dict] = []

    def next_seq(self) -> int:
        self.seq_counter += 1
        return self.seq_counter

    # ── Fixture Validation Helpers ─────────────────────────────────────

    # Expected fixture identity contract (from readline-fixture.c defines)
    EXPECTED_FIXTURE = {
        "control_abi_version": 1,
        "identity.fixture": "sleepwalker-readline-fixture",
        "identity.readline_version": "gnu-readline 8.2",
        "identity.keymap": "emacs",
        "identity.input_mode": "ascii-printable",
        "identity.line_mode": "single-line",
    }

    def _validate_fixture_identity(self, desc: dict) -> None:
        """Validate fixture describe response matches the expected contract.

        Checks: control ABI version, fixture identity fields (readline pin,
        keymap/mode, input mode, line model). Raises RuntimeError on mismatch.
        """
        errors: list[str] = []

        abi = desc.get("control_abi_version")
        if abi != self.EXPECTED_FIXTURE["control_abi_version"]:
            errors.append(
                f"control_abi_version={abi}, expected "
                f"{self.EXPECTED_FIXTURE['control_abi_version']}"
            )

        identity = desc.get("identity") or {}
        for field, expected in [
            ("fixture", self.EXPECTED_FIXTURE["identity.fixture"]),
            ("readline_version",
             self.EXPECTED_FIXTURE["identity.readline_version"]),
            ("keymap", self.EXPECTED_FIXTURE["identity.keymap"]),
            ("input_mode", self.EXPECTED_FIXTURE["identity.input_mode"]),
            ("line_mode", self.EXPECTED_FIXTURE["identity.line_mode"]),
        ]:
            actual = identity.get(field)
            if actual != expected:
                errors.append(
                    f"identity.{field}={actual!r}, expected {expected!r}"
                )

        if errors:
            raise RuntimeError(
                "fixture identity mismatch: " + "; ".join(errors)
                + f" | full describe: {json.dumps(desc)}"
            )

    def _validate_fixture_health(self, health: dict, phase: str) -> None:
        """Validate fixture health requires ok/alive/responsive/baseline and F24 bound.

        Raises RuntimeError on any required field failure.
        """
        errors: list[str] = []

        if not health.get("ok", False):
            errors.append(f"ok={health.get('ok')}")
        if not health.get("alive", False):
            errors.append(f"alive={health.get('alive')}")
        if not health.get("responsive", False):
            errors.append(f"responsive={health.get('responsive')}")
        if not health.get("baseline", False):
            errors.append(f"baseline={health.get('baseline')}")
        # F24 binding must be verified before text operations
        if not health.get("f24_bound", False):
            errors.append(f"f24_bound={health.get('f24_bound')}")
        if not health.get("keymap_ok", False):
            errors.append(
                f"keymap={health.get('keymap')!r}, "
                f"pin={health.get('keymap_pin')!r}"
            )
        vt = health.get("vt", "")
        if not isinstance(vt, str) or not vt.startswith("/dev/tty"):
            errors.append(f"vt={vt!r}")
        if not health.get("console_f24_keyseq_ok", False):
            errors.append(
                f"console_f24_keyseq_ok="
                f"{health.get('console_f24_keyseq_ok')}"
            )

        if errors:
            raise RuntimeError(
                f"fixture health check failed ({phase}): "
                + ", ".join(errors)
                + f" | full health: {json.dumps(health)}"
            )

    def _wait_for_android_event(self, predicate: Callable[[dict], bool],
                                phase: str, timeout_sec: float = 30.0) -> dict:
        """Wait for one structured SwLog event from this run's logcat."""
        if self.artifacts is None:
            raise RuntimeError("artifact writer not initialized")

        deadline = time.monotonic() + timeout_sec
        seen_lines = 0
        while time.monotonic() < deadline:
            try:
                with open(self.artifacts.android_logcat_path,
                          encoding="utf-8", errors="replace") as log_file:
                    lines = log_file.readlines()
            except FileNotFoundError:
                lines = []

            for line in lines[seen_lines:]:
                json_start = line.find("{")
                if json_start < 0:
                    continue
                try:
                    event = json.loads(line[json_start:])
                except json.JSONDecodeError:
                    continue
                if predicate(event):
                    return event
            seen_lines = len(lines)
            time.sleep(0.1)

        raise RuntimeError(
            f"Android event timeout ({phase}, {timeout_sec:.1f}s)"
        )

    # ── Session Setup ────────────────────────────────────────────────────

    def setup_session(self) -> bool:
        """One-time session setup. Returns True if successful."""
        print(json.dumps({"phase": "session_setup", "status": "start"}))

        try:
            bench_path = self.args["bench_toml"]

            # Validate bench config
            run_cmd(["sleepwalker-bench-validate", bench_path])

            # Load bench config
            try:
                import tomllib
            except ModuleNotFoundError:
                import tomli as tomllib  # type: ignore
            with open(bench_path, "rb") as f:
                self.bench = tomllib.load(f)

            self.observer_target = self.bench["hid_observer"]["ssh_target"]
            self.android_serial = self.bench["android"]["adb_serial"]
            self.observer_identity = self.bench["hid_observer"].get(
                "identity_file", ""
            )
            self.known_hosts = (
                self.args.get("known_hosts")
                or self.bench["hid_observer"].get("known_hosts", "")
            )

            # Create artifact writer
            self.artifacts = ArtifactWriter(self.args["out_dir"])
            self.artifacts.copy_bench_config(bench_path)
            self.artifacts.mark_hid_observer_diagnostics_only()

            # Parse ESP UART settings for reset and capture
            uart_port = self.bench["esp"]["uart_port"]
            uart_baud = self.bench["esp"].get("uart_baud", 115200)

            # Reset ESP to known state (pass parsed port+baud, not bench path)
            print(json.dumps({"phase": "esp_reset", "port": uart_port,
                              "baud": uart_baud}))
            run_cmd(["sleepwalker-esp-reset", uart_port, str(uart_baud)])
            time.sleep(2)

            # Start background ESP UART capture
            print(json.dumps({"phase": "start_esp_uart"}))
            esp_proc = subprocess.Popen(
                ["sleepwalker-fw-uart", uart_port,
                 str(self.artifacts.esp_uart_path), str(uart_baud)],
            )
            self.capture_pids.append(esp_proc.pid)
            time.sleep(1)

            # Start background ADB logcat capture
            print(json.dumps({"phase": "start_logcat"}))
            logcat_proc = subprocess.Popen(
                ["sleepwalker-adb-logcat",
                 str(self.artifacts.android_logcat_path), self.android_serial],
            )
            self.capture_pids.append(logcat_proc.pid)
            time.sleep(1)

            # Start non-grabbing HID observer (diagnostics only)
            print(json.dumps({"phase": "start_hid_observer"}))
            hid_proc = subprocess.Popen(
                ["sleepwalker-hid-observe", self.observer_target,
                 str(self.artifacts.hid_observer_path), "300",
                 self.observer_identity, self.known_hosts]
            )
            self.capture_pids.append(hid_proc.pid)
            time.sleep(1)

            # Start Readline fixture
            print(json.dumps({"phase": "fixture_start"}))
            fixture_start(self.observer_target, self.observer_identity,
                          self.known_hosts)
            time.sleep(2)

            # Get fixture description and validate identity/ABI/pin/mode/charset/line model
            print(json.dumps({"phase": "fixture_describe"}))
            desc = fixture_describe(self.observer_target,
                                    self.observer_identity, self.known_hosts)
            self.fixture_identity = desc
            self.fixture_control_abi = desc.get("control_abi_version")

            # Validate fixture identity before any text operations
            self._validate_fixture_identity(desc)

            # Health check: require ok/alive/responsive/baseline and F24 bound
            print(json.dumps({"phase": "fixture_health"}))
            health = fixture_health(self.observer_target,
                                    self.observer_identity, self.known_hosts)
            self.fixture_health_status = health
            self._validate_fixture_health(health, "session_setup")

            # BLE connect and wait for GATT setup. A fixed delay can arm
            # before a reset ESP has started accepting connections.
            print(json.dumps({"phase": "ble_connect"}))
            adb_connect(self.android_serial, self.next_seq())
            self._wait_for_android_event(
                lambda event: event.get("component") == "ble"
                and event.get("event") == "mtu"
                and event.get("status") == 0,
                "ble_connect",
            )

            # Arm and require the firmware's QUEUED acknowledgement before
            # any Editor execution or synchronization-key injection.
            print(json.dumps({"phase": "ble_arm"}))
            arm_seq = self.next_seq()
            adb_arm(self.android_serial, arm_seq)
            self._wait_for_android_event(
                lambda event: event.get("component") == "ack"
                and event.get("event") == "queued"
                and event.get("seq") == arm_seq,
                "ble_arm",
            )

            print(json.dumps({"phase": "session_setup", "status": "complete"}))
            return True

        except Exception as e:
            print(json.dumps({
                "phase": "session_setup",
                "status": "failed",
                "error": str(e),
                "traceback": traceback.format_exc(),
            }), file=sys.stderr)
            return False

    # ── Per-Sequence Setup ───────────────────────────────────────────────

    def setup_sequence(self) -> bool:
        """Reset fixture before a generated sequence. Returns True if OK."""
        try:
            fixture_reset(self.observer_target, self.observer_identity,
                          self.known_hosts)
            # The fixture and Editor must start from the same empty state.
            # Resetting only the fixture leaves the Editor's assumed document
            # from the preceding Hypothesis example and corrupts the property.
            adb_reset_editor(self.android_serial, self.next_seq())
            # Reset restarts the Readline process. Wait for its replacement
            # control socket and verified empty baseline before proceeding.
            deadline = time.monotonic() + 10.0
            while True:
                try:
                    health = fixture_health(
                        self.observer_target,
                        self.observer_identity,
                        self.known_hosts,
                    )
                    self._validate_fixture_health(health, "sequence_setup")
                    return True
                except Exception as health_error:
                    if time.monotonic() >= deadline:
                        raise RuntimeError(
                            "restarted fixture did not reach healthy baseline"
                        ) from health_error
                    time.sleep(0.1)
        except Exception as e:
            print(json.dumps({
                "phase": "sequence_setup",
                "status": "failed",
                "error": str(e),
            }), file=sys.stderr)
            return False

    # ── Single Step Execution ────────────────────────────────────────────

    def execute_step(self, seq: int, desired: str,
                     timeout_mult: float = 1.0) -> dict:
        """Execute one Editor step: setText + F24 + barrier + snapshot + compare.

        Returns a step record dict.
        """
        step: dict = {
            "seq": seq,
            "desired": desired,
            "lcp": None,
            "old_mid": None,
            "new_mid": None,
            "plan_ops": None,
            "predicted": None,
            "observed": None,
            "barrier_consumed": False,
            "match": None,
            "classification": None,
            "failure_detail": None,
        }

        start = time.monotonic()

        try:
            # A. Send setText via ADB
            diagnostic = adb_set_text_encoded(
                self.android_serial, desired, seq
            )

            if diagnostic.package_id is not None:
                self.fqdn_package_id = diagnostic.package_id
            if diagnostic.package_version is not None:
                self.fqdn_package_version = diagnostic.package_version
            if diagnostic.host_abi is not None:
                self.fqdn_host_abi = diagnostic.host_abi

            step.update({
                "lcp": diagnostic.lcp,
                "old_mid": diagnostic.old_mid,
                "new_mid": diagnostic.new_mid,
                "plan_ops": diagnostic.plan_ops,
            })
            if diagnostic.predicted_buffer is not None:
                step["predicted"] = {
                    "buffer": diagnostic.predicted_buffer,
                    "point": diagnostic.predicted_point,
                    "revision": diagnostic.predicted_revision,
                }

            if diagnostic.ok and diagnostic.transport_status:
                step["transport_status"] = diagnostic.transport_status

            # B. Classify missing/invalid set-text diagnostics before F24.
            # If no valid diagnostic was received, the ADB/transport layer
            # failed before the Editor ran — classify as transport (parse
            # errors) or environment (no diagnostic at all).
            if diagnostic.transport_status in (
                "parse_error", "no_diagnostic"
            ):
                step["classification"] = "transport"
                step["match"] = False
                step["failure_detail"] = (
                    f"invalid set-text diagnostic: "
                    f"{diagnostic.transport_status} "
                    f"({diagnostic.failure or 'no response from ADB'})"
                )
                # Propagate infrastructure failures, do not proceed to F24
                return step

            # C. Handle planning failure (pre-execution rejection, no HID)
            if diagnostic.is_planning_failure():
                step["classification"] = "planning"
                step["match"] = False
                step["failure_detail"] = (
                    f"planning failure: {diagnostic.failure or 'unknown'}"
                )
                # No F24 (no HID emitted — spec requirement)
                return step

            # D. Handle transport failure (no HID delivered)
            if diagnostic.is_transport_failure():
                step["classification"] = "transport"
                step["match"] = False
                step["failure_detail"] = (
                    f"transport failure: {diagnostic.transport_status} "
                    f"({diagnostic.failure or 'unknown'})"
                )
                return step

            # E. Inject F24 (separate HIL operation, never in Editor plan)
            adb_inject_f24(self.android_serial, self.next_seq())

            # F. Wait for fixture to consume F24 barrier
            barrier_timeout = 10.0 * timeout_mult
            barrier_ok = fixture_await_barrier(
                self.observer_target, self.observer_identity,
                self.known_hosts, barrier_timeout,
            )
            step["barrier_consumed"] = barrier_ok

            if not barrier_ok:
                step["classification"] = "sync"
                step["match"] = False
                step["failure_detail"] = (
                    f"F24 barrier not consumed within {barrier_timeout}s"
                )
                return step

            # G. Take authoritative snapshot
            snap = fixture_snapshot(
                self.observer_target, self.observer_identity, self.known_hosts
            )
            step["observed"] = {
                "buffer": snap.get("buffer", ""),
                "point": snap.get("point"),
                "contract_version": snap.get("contract_version"),
            }

            if self.artifacts:
                self.artifacts.write_fixture_snapshot({
                    "seq": seq,
                    "desired": desired,
                    "snapshot": snap,
                    "timestamp": time.monotonic(),
                })

            # H. Compare buffers AND points
            observed_buffer = step["observed"]["buffer"]
            observed_point = step["observed"]["point"]
            predicted_buffer = (
                diagnostic.predicted_buffer if diagnostic.predicted_buffer
                else desired
            )
            predicted_point = diagnostic.predicted_point
            # If the Editor didn't predict a point, expect end-of-buffer
            if predicted_point is None:
                predicted_point = len(predicted_buffer)

            buffer_match = observed_buffer == predicted_buffer
            point_match = observed_point == predicted_point

            # Semantic match: observed == predicted == desired, point matches
            if buffer_match and point_match and predicted_buffer == desired:
                # Passing steps are not classified as semantic failures.
                # Use "pass" so classification_totals don't inflate the
                # semantic failure bucket.
                step["classification"] = "pass"
                step["match"] = True
            elif buffer_match and point_match:
                # predicted == observed but both != desired
                step["classification"] = "semantic"
                step["match"] = False
                step["failure_detail"] = (
                    f"predicted==observed but != desired: "
                    f"desired={repr(desired)}, "
                    f"predicted={repr(predicted_buffer)}, "
                    f"observed={repr(observed_buffer)}, "
                    f"predicted_point={predicted_point}, "
                    f"observed_point={observed_point}"
                )
            elif not buffer_match:
                step["classification"] = "semantic"
                step["match"] = False
                step["failure_detail"] = (
                    f"observed != predicted: "
                    f"desired={repr(desired)}, "
                    f"predicted={repr(predicted_buffer)}, "
                    f"observed={repr(observed_buffer)}, "
                    f"predicted_point={predicted_point}, "
                    f"observed_point={observed_point}"
                )
            else:
                # Buffer matches but point doesn't
                step["classification"] = "semantic"
                step["match"] = False
                step["failure_detail"] = (
                    f"observed_point != predicted_point: "
                    f"desired={repr(desired)}, "
                    f"predicted={repr(predicted_buffer)}, "
                    f"observed={repr(observed_buffer)}, "
                    f"predicted_point={predicted_point}, "
                    f"observed_point={observed_point}"
                )

        except subprocess.CalledProcessError as e:
            step["classification"] = "environment"
            step["match"] = False
            step["failure_detail"] = (
                f"command failed: {' '.join(e.cmd)}: {e.stderr[:200]}"
            )
        except RuntimeError as e:
            step["classification"] = "fixture"
            step["match"] = False
            step["failure_detail"] = str(e)[:500]
        except Exception as e:
            step["classification"] = "environment"
            step["match"] = False
            step["failure_detail"] = f"unexpected error: {e}"

        step["duration_sec"] = time.monotonic() - start
        return step

    # ── Cleanup ──────────────────────────────────────────────────────────

    def cleanup(self) -> None:
        """Clean up all resources."""
        print(json.dumps({"phase": "cleanup", "status": "start"}))
        try:
            fixture_shutdown(self.observer_target, self.observer_identity,
                             self.known_hosts)
        except Exception:
            pass

        # Kill background captures
        for pid in self.capture_pids:
            try:
                os.kill(pid, 15)  # SIGTERM
            except (OSError, ProcessLookupError):
                pass
        time.sleep(1)

        # ADB release + kill + ESP reset
        try:
            adb_release_all(self.android_serial, self.next_seq())
        except Exception:
            pass
        try:
            adb_kill(self.android_serial, self.next_seq())
        except Exception:
            pass
        try:
            if self.bench:
                uart_port = self.bench["esp"]["uart_port"]
                uart_baud = self.bench["esp"].get("uart_baud", 115200)
                run_cmd(["sleepwalker-esp-reset", uart_port, str(uart_baud)],
                        check=False)
        except Exception:
            pass

        print(json.dumps({"phase": "cleanup", "status": "complete"}))

    # ── Hypothesis Strategy ──────────────────────────────────────────────

    def get_hypothesis_settings(self, profile: str):
        """Get Hypothesis settings for the given profile."""
        if profile == "deep":
            max_examples = 100
            max_len = 50
            max_steps = 7
            deadline = None
        else:  # quick
            max_examples = 10
            max_len = 20
            max_steps = 4
            deadline = None

        settings.register_profile(
            profile,
            max_examples=max_examples,
            deadline=deadline,
            suppress_health_check=[HealthCheck.too_slow,
                                    HealthCheck.data_too_large],
            phases=[Phase.reuse, Phase.generate, Phase.shrink],
        )
        settings.load_profile(profile)

        return {
            "max_examples": max_examples,
            "max_len": max_len,
            "max_steps": max_steps,
            "profile": profile,
        }

    # ── Run ──────────────────────────────────────────────────────────────

    def run(self) -> tuple[bool, list[dict]]:
        """Run the full conformance scenario.

        Returns (hypothesis_passed: bool, hypothesis_steps: list[dict],
                 ui_steps: list[dict]).
        """
        self.start_time = time.monotonic()
        profile = self.args.get("profile", "quick")
        timeout_mult = self.args.get("timeout_mult", 1.0)
        seed = self.args.get("seed")
        hypo_settings = self.get_hypothesis_settings(profile)
        max_len = hypo_settings["max_len"]
        max_steps = hypo_settings["max_steps"]

        # Session setup
        setup_ok = self.setup_session()
        if not setup_ok:
            return False, []

        all_steps: list[dict] = []
        # Track the full sequence and failing prefix for replay persistence
        current_sequence: list[str] = []
        failing_prefix: list[str] = []
        hypothesis_failure: Optional[str] = None
        actual_seed: Optional[int] = seed

        try:
            # Define Hypothesis-driven property
            @given(snapshot_sequences(max_len=max_len, max_steps=max_steps))
            def test_editor_conformance(seq: list[str]):
                nonlocal all_steps, current_sequence, failing_prefix

                # Record the full sequence for replay persistence
                current_sequence = list(seq)

                # Per-sequence reset
                if not self.setup_sequence():
                    raise RuntimeError("fixture sequence reset failed")

                for i, desired in enumerate(seq):
                    step = self.execute_step(
                        self.next_seq(), desired, timeout_mult,
                    )
                    all_steps.append(step)

                    # Record step in artifacts
                    if self.artifacts:
                        self.artifacts.record_step(step)

                    # Fail-fast within Hypothesis on match=False
                    if step.get("match") is False:
                        cls = step.get("classification", "unknown")
                        detail = step.get("failure_detail", "")
                        # Capture the failing prefix (all steps up to and
                        # including the failing one) for replay persistence
                        failing_prefix = list(seq[:i + 1])
                        raise AssertionError(
                            f"step {step['seq']} failed: "
                            f"classification={cls}, {detail}"
                        )

            # Apply seed: use @seed decorator on the test function
            # so --seed actually controls Hypothesis generation.
            if seed is not None:
                from hypothesis import seed as hypo_seed
                test_editor_conformance = hypo_seed(seed)(
                    test_editor_conformance
                )

            print(json.dumps({
                "phase": "running_properties",
                "profile": profile,
                "seed": seed,
                "max_len": max_len,
                "max_steps": max_steps,
            }))

            test_editor_conformance()
            failed = False

            # Capture actual seed from Hypothesis if not explicitly set
            if actual_seed is None and hasattr(
                test_editor_conformance, "hypothesis"
            ):
                hypo_inner = getattr(
                    test_editor_conformance, "hypothesis"
                )
                if hasattr(hypo_inner, "inner_test"):
                    # Hypothesis stores the seed used; best-effort
                    pass

        except Exception as e:
            hypothesis_failure = str(e)
            print(json.dumps({
                "phase": "hypothesis_failed",
                "error": str(e),
                "traceback": traceback.format_exc(),
            }))
            failed = True

        # ── Same-input replay before choosing non_reproducible ──────────
        # If a step failed, replay the exact same input to distinguish
        # genuine failures from non-reproducible ones.
        if failed and failing_prefix and self.artifacts:
            self._replay_and_reclassify(
                failing_prefix, all_steps, timeout_mult
            )
        # ── Persist full shrunk sequence/prefix, seed and replay context ─
        if self.artifacts and (failed or failing_prefix):
            self.artifacts.write_replay_context(
                full_sequence=current_sequence,
                failing_prefix=failing_prefix,
                seed=actual_seed,
                profile=profile,
                max_len=max_len,
                max_steps=max_steps,
                hypothesis_failure=hypothesis_failure,
            )

        # ── Fixed UI scenario ────────────────────────────────────────────
        # Always runs after the Hypothesis property, regardless of its
        # success. The Hypothesis property and UI scenario are independent
        # validations with separate results.
        self.ui_steps: list[dict] = []
        try:
            self.ui_steps = run_fixed_ui_scenario(
                serial=self.android_serial,
                observer_target=self.observer_target,
                observer_identity=self.observer_identity,
                known_hosts=self.known_hosts,
                artifacts=self.artifacts,
                logcat_path=(
                    str(self.artifacts.android_logcat_path)
                    if self.artifacts else ""
                ),
                timeout_mult=timeout_mult,
            )
        except Exception as e:
            print(json.dumps({
                "phase": "fixed_ui_scenario",
                "status": "error",
                "error": str(e)[:300],
            }))

        return not failed, all_steps

    def _replay_and_reclassify(
        self, failing_prefix: list[str], all_steps: list[dict],
        timeout_mult: float,
    ) -> None:
        """Replay the failing prefix with the same inputs to distinguish
        genuine failures from non-reproducible ones.

        If the replay succeeds, reclassify the original failing step as
        non_reproducible. If the replay also fails, keep the original
        classification (the failure is reproducible).
        """
        print(json.dumps({
            "phase": "same_input_replay",
            "prefix_len": len(failing_prefix),
        }))

        try:
            # Reset fixture for replay
            if not self.setup_sequence():
                print(json.dumps({
                    "phase": "same_input_replay",
                    "status": "fixture_reset_failed",
                }))
                return

            replay_ok = True
            for i, desired in enumerate(failing_prefix):
                replay_step = self.execute_step(
                    self.next_seq(), desired, timeout_mult,
                )
                if replay_step.get("match") is False:
                    replay_ok = False
                    print(json.dumps({
                        "phase": "same_input_replay",
                        "step": i,
                        "status": "failed_again",
                        "classification": replay_step.get(
                            "classification"
                        ),
                        "failure_detail": replay_step.get(
                            "failure_detail", ""
                        )[:200],
                    }))
                    break

            if replay_ok:
                # The failure was non-reproducible: it failed once but
                # succeeds on same-input replay. Reclassify.
                print(json.dumps({
                    "phase": "same_input_replay",
                    "status": "passed_on_replay",
                    "reclassified": "non_reproducible",
                }))
                # Reclassify only the most recent failing step: Hypothesis may
                # have recorded earlier candidates while shrinking.
                step = next(
                    (candidate for candidate in reversed(all_steps)
                     if candidate.get("match") is False),
                    None,
                )
                if step is not None:
                    old_cls = step.get("classification")
                    step["classification"] = "non_reproducible"
                    step["failure_detail"] = (
                        f"non-reproducible on replay (was {old_cls}): "
                        f"{step.get('failure_detail', '')}"
                    )
                    # The step is shared with artifacts.steps, but totals were
                    # counted when it was first recorded and need correction.
                    totals = (
                        self.artifacts.classification_totals
                        if self.artifacts else None
                    )
                    if totals is not None and old_cls in totals:
                        totals[old_cls] = max(0, totals[old_cls] - 1)
                        totals["non_reproducible"] += 1
        except Exception as e:
            print(json.dumps({
                "phase": "same_input_replay",
                "status": "error",
                "error": str(e)[:200],
            }))

# ── Fixed UI Scenario Runner ─────────────────────────────────────────────

def run_fixed_ui_scenario(
    serial: str,
    observer_target: str,
    observer_identity: str,
    known_hosts: str,
    artifacts: Optional[ArtifactWriter] = None,
    logcat_path: str = "",
    timeout_mult: float = 1.0,
) -> list[dict]:
    """Run the deterministic fixed UI scenario against the hardware bench.

    Each step:
      1. Execute ADB input command(s) to trigger a TextWatcher change.
      2. Wait for the matching editor/ui_change_result in logcat.
      3. If completion classification is non-null → classify as planning/transport.
      4. Inject F24 barrier key.
      5. Wait for fixture to consume F24.
      6. Take authoritative fixture snapshot.
      7. Compare observed buffer/point with predicted/desired.

    Resets the fixture and Editor before driving the UI steps.

    Returns a list of step record dicts.
    """
    steps: list[dict] = []

    # Reset fixture and Editor before starting the activity.
    try:
        fixture_reset(observer_target, observer_identity, known_hosts)
        adb_reset_editor(serial, 0)
        deadline = time.monotonic() + 10.0 * timeout_mult
        while True:
            health = fixture_health(observer_target, observer_identity, known_hosts)
            if health.get("ok") and health.get("baseline"):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("fixture did not reach healthy baseline for UI scenario")
            time.sleep(0.1)
    except Exception as e:
        err_step: dict = {
            "seq": -1,
            "step_type": "setup",
            "desired_text": "",
            "change_id": None,
            "generation": None,
            "editor_state": None,
            "completion_classification": "environment",
            "match": False,
            "classification": "fixture",
            "failure_detail": f"UI scenario setup failed: {e}",
            "barrier_consumed": False,
            "observed": None,
            "predicted": None,
            "duration_sec": 0.0,
        }
        steps.append(err_step)
        print(json.dumps({"phase": "fixed_ui_scenario", "status": "setup_failed",
                           "error": str(e)[:200]}))
        return steps

    # Wake device, dismiss keyguard, and launch Readline activity.
    try:
        adb_keyevent(serial, 224)  # KEYCODE_WAKEUP is idempotent; POWER would toggle an awake display off.
        time.sleep(1.0)
        adb_dismiss_keyguard(serial)
        time.sleep(1.0)
        adb_launch_readline(serial)
        time.sleep(2.0)  # Allow activity to start and bind service
    except Exception as e:
        err_step = {
            "seq": -1,
            "step_type": "wake",
            "desired_text": "",
            "change_id": None,
            "generation": None,
            "editor_state": None,
            "completion_classification": "environment",
            "match": False,
            "classification": "environment",
            "failure_detail": f"wake/launch failed: {e}",
            "barrier_consumed": False,
            "observed": None,
            "predicted": None,
            "duration_sec": 0.0,
        }
        steps.append(err_step)
        print(json.dumps({"phase": "fixed_ui_scenario", "status": "wake_launch_failed",
                           "error": str(e)[:200]}))
        return steps
    seq_counter: int = 0

    for step_idx, (step_type, expected_text, adb_ops) in enumerate(FIXED_UI_STEPS):
        seq_counter += 1
        step = {
            "seq": seq_counter,
            "step_type": step_type,
            "desired_text": expected_text,
            "change_id": None,
            "generation": None,
            "editor_state": None,
            "completion_classification": None,
            "match": None,
            "classification": None,
            "failure_detail": None,
            "barrier_consumed": False,
            "observed": None,
            "predicted": None,
            "duration_sec": 0.0,
        }
        step_start = time.monotonic()

        try:
            # 1. Execute ADB input operations for this step
            execute_ui_adb_ops(serial, adb_ops)
            time.sleep(0.5)  # Allow TextWatcher + FIFO to process

            # 2. Wait for editor/ui_change_result completion event
            try:
                completion_event = _wait_for_android_event_generic(
                    logcat_path,
                    lambda event: (
                        event.get("component") == "editor"
                        and event.get("event") == "ui_change_result"
                    ),
                    "fixed_ui_step",
                    timeout_sec=20.0 * timeout_mult,
                )
            except RuntimeError as e:
                step["classification"] = "completion_timeout"
                step["match"] = False
                step["failure_detail"] = str(e)[:300]
                step["duration_sec"] = time.monotonic() - step_start
                steps.append(step)
                
                continue

            # Extract completion fields
            fields = completion_event.get("fields", {}) or {}
            change_id = fields.get("change_id")
            generation = fields.get("generation")
            desired_text_event = fields.get("desired_text", "")
            editor_state = fields.get("editor_state")
            completion_cls = fields.get("classification")  # null = Synced

            step["change_id"] = change_id
            step["generation"] = generation
            step["editor_state"] = editor_state
            step["completion_classification"] = completion_cls

            # 3. Classify completion outcome
            if completion_cls is not None:
                # Non-null classification = Editor rejected before HID
                if completion_cls in (
                    "PlanningError", "InconsistentPrediction",
                    "UnrepresentableContent", "UnsupportedBehavior",
                ):
                    step["classification"] = "planning"
                elif completion_cls == "TransportFailure":
                    step["classification"] = "transport"
                else:
                    step["classification"] = "environment"
                step["match"] = False
                step["failure_detail"] = (
                    f"Editor completion classification={completion_cls}"
                )
                step["duration_sec"] = time.monotonic() - step_start
                steps.append(step)
                
                continue

            # Synced — proceed with F24 barrier
            # 4. Inject F24
            adb_inject_f24(serial, seq_counter)

            # 5. Await barrier
            barrier_ok = fixture_await_barrier(
                observer_target, observer_identity, known_hosts,
                timeout_sec=15.0 * timeout_mult,
            )
            step["barrier_consumed"] = barrier_ok

            if not barrier_ok:
                step["classification"] = "sync"
                step["match"] = False
                step["failure_detail"] = "F24 barrier not consumed for UI step"
                step["duration_sec"] = time.monotonic() - step_start
                steps.append(step)
                
                continue

            # 6. Take authoritative fixture snapshot
            snap = fixture_snapshot(
                observer_target, observer_identity, known_hosts,
            )
            observed_buffer = snap.get("buffer", "")
            observed_point = snap.get("point")
            observed_contract = snap.get("contract_version")

            step["observed"] = {
                "buffer": observed_buffer,
                "point": observed_point,
                "contract_version": observed_contract,
            }

            # 7. Compare buffer/point
            # Predicted buffer comes from editor_state.predictedBuffer, fallback to desired_text
            predicted_buffer = None
            predicted_point = None
            if editor_state and isinstance(editor_state, dict):
                predicted_buffer = editor_state.get("predictedBuffer")
                predicted_point = editor_state.get("predictedPoint")
            if predicted_buffer is None:
                predicted_buffer = expected_text
            if predicted_point is None:
                predicted_point = len(predicted_buffer)

            step["predicted"] = {
                "buffer": predicted_buffer,
                "point": predicted_point,
            }

            buffer_match = observed_buffer == predicted_buffer
            point_match = observed_point == predicted_point

            if buffer_match and point_match and predicted_buffer == expected_text:
                step["classification"] = "pass"
                step["match"] = True
            elif buffer_match and point_match:
                # predicted==observed but != expected_text
                step["classification"] = "semantic"
                step["match"] = False
                step["failure_detail"] = (
                    f"predicted==observed but != expected_text: "
                    f"expected={repr(expected_text)}, "
                    f"predicted={repr(predicted_buffer)}, "
                    f"observed={repr(observed_buffer)}"
                )
            elif not buffer_match:
                step["classification"] = "semantic"
                step["match"] = False
                step["failure_detail"] = (
                    f"observed != predicted: "
                    f"expected={repr(expected_text)}, "
                    f"predicted={repr(predicted_buffer)}, "
                    f"observed={repr(observed_buffer)}, "
                    f"point={observed_point}"
                )
            else:
                step["classification"] = "semantic"
                step["match"] = False
                step["failure_detail"] = (
                    f"point mismatch: "
                    f"observed_point={observed_point}, "
                    f"predicted_point={predicted_point}"
                )

            # Write per-step fixture snapshot
            if artifacts:
                artifacts.write_fixture_snapshot({
                    "seq": seq_counter,
                    "step_type": step_type,
                    "desired": expected_text,
                    "snapshot": snap,
                    "timestamp": time.monotonic(),
                    "scenario": "fixed_ui",
                })

        except subprocess.CalledProcessError as e:
            step["classification"] = "environment"
            step["match"] = False
            step["failure_detail"] = (
                f"command failed: {' '.join(e.cmd)}: {e.stderr[:200]}"
            )
        except RuntimeError as e:
            step["classification"] = "fixture"
            step["match"] = False
            step["failure_detail"] = str(e)[:500]
        except Exception as e:
            step["classification"] = "environment"
            step["match"] = False
            step["failure_detail"] = f"unexpected error: {e}"

        step["duration_sec"] = time.monotonic() - step_start
        steps.append(step)
        

    print(json.dumps({
        "phase": "fixed_ui_scenario",
        "status": "complete",
        "steps": len(steps),
        "failing": sum(1 for s in steps if s.get("match") is False),
    }))

    return steps


def _wait_for_android_event_generic(
    logcat_path: str,
    predicate: Callable[[dict], bool],
    phase: str,
    timeout_sec: float = 30.0,
) -> dict:
    """Wait for one structured SwLog event from a logcat file.

    Standalone version of ConformanceRunner._wait_for_android_event
    for use by the module-level fixed UI scenario function.
    """
    deadline = time.monotonic() + timeout_sec
    seen_lines = 0
    while time.monotonic() < deadline:
        try:
            with open(logcat_path, encoding="utf-8", errors="replace") as log_file:
                lines = log_file.readlines()
        except FileNotFoundError:
            lines = []

        for line in lines[seen_lines:]:
            json_start = line.find("{")
            if json_start < 0:
                continue
            try:
                event = json.loads(line[json_start:])
            except json.JSONDecodeError:
                continue
            if predicate(event):
                return event
        seen_lines = len(lines)
        time.sleep(0.1)

    raise RuntimeError(
        f"Android event timeout ({phase}, {timeout_sec:.1f}s)"
    )

def run_dry_run(args: dict) -> bool:
    """Validate classification and artifact schema without hardware."""
    out_dir = args.get("out_dir", "/tmp/smoke_editor_dry_run")
    profile = args.get("profile", "quick")
    classify = args.get("classify")

    writer = ArtifactWriter(out_dir)
    writer.mark_hid_observer_diagnostics_only()

    hypo_max_len = 20 if profile == "quick" else 50
    hypo_max_steps = 4 if profile == "quick" else 7

    print(json.dumps({
        "phase": "dry_run",
        "out_dir": out_dir,
        "profile": profile,
        "classify": classify,
    }))

    # Generate sequences to validate the strategy works.
    # Use @given with @seed so --seed actually controls Hypothesis
    # generation. A bare hypothesis.seed() call or strategy.example()
    # without @given does not produce deterministic output.
    seed = args.get("seed")
    from hypothesis import given as hypo_given, seed as hypo_seed, \
        settings as hypo_settings, HealthCheck

    strategy = snapshot_sequences(
        max_len=hypo_max_len, max_steps=hypo_max_steps
    )

    # Define a capturing test decorated with @given + @seed
    generated_sequences: list[list[str]] = []

    @hypo_given(strategy)
    @hypo_settings(
        max_examples=3, deadline=None,
        suppress_health_check=[HealthCheck.too_slow,
                                HealthCheck.data_too_large],
        database=None,
    )
    def _capture_sequences(seq: list[str]):
        generated_sequences.append(list(seq))

    if seed is not None:
        _capture_sequences = hypo_seed(seed)(_capture_sequences)

    try:
        _capture_sequences()
    except Exception as e:
        print(json.dumps({
            "phase": "dry_run_strategy_error",
            "error": str(e),
        }))

    examples_generated = len(generated_sequences)
    for i, seq in enumerate(generated_sequences):
        print(json.dumps({
            "phase": "dry_run_sequence",
            "example": i + 1,
            "steps": len(seq),
            "previews": [s[:20] for s in seq],
        }))

    if classify:
        # Generate a step record with the requested classification
        cls = classify
        assert cls in CLASSIFICATIONS | {"pass"}, f"invalid classification: {cls}"

        step = {
            "seq": 1,
            "desired": "hello",
            "lcp": 0,
            "old_mid": "",
            "new_mid": "hello",
            "plan_ops": ["ctrl-a", "type hello"],
            "predicted": {"buffer": "hello", "point": 5, "revision": 1},
            "observed": {"buffer": "hello", "point": 5},
            "barrier_consumed": True,
            "match": True,
            "classification": cls,
            "failure_detail": None,
            "duration_sec": 0.001,
        }
        if cls == "planning":
            step.update({
                "plan_ops": [],
                "predicted": None,
                "barrier_consumed": False,
                "match": False,
                "failure_detail": "planning failure: InconsistentPrediction",
            })
        elif cls == "sync":
            step.update({
                "barrier_consumed": False,
                "match": False,
                "failure_detail": "F24 barrier not consumed",
            })
        elif cls == "transport":
            step.update({
                "barrier_consumed": False,
                "match": False,
                "transport_status": "disarmed",
                "failure_detail": "transport failure: disarmed",
            })
        elif cls == "fixture":
            step.update({
                "barrier_consumed": False,
                "match": False,
                "failure_detail": "fixture health check failed",
            })
        elif cls == "environment":
            step.update({
                "barrier_consumed": False,
                "match": False,
                "failure_detail": "SSH unreachable",
            })
        elif cls == "non_reproducible":
            step.update({
                "match": False,
                "failure_detail": "non-reproducible on replay",
            })
        elif cls == "semantic":
            step.update({
                "observed": {"buffer": "hxllo", "point": 2},
                "match": False,
                "failure_detail": "observed != predicted",
            })
        writer.record_step(step)

    # ── Fixed UI scenario dry-run records ─────────────────────────────────
    # Generate deterministic UI scenario step records without hardware.
    ui_dry_steps = _generate_fixed_ui_steps(args)

    ui_cls_totals: dict[str, int] = {c: 0 for c in CLASSIFICATIONS}
    ui_cls_totals["pass"] = len(ui_dry_steps)


    # Build summary with all required fields
    summary = {
        "scenario": "editor_conformance",
        "status": "dry_run",
        "profile": profile,
        "dry_run": True,
        "seed": seed,
        "target_package": {
            "id": "readline-emacs-ascii",
            "version": "1.0.0",
            "host_abi": 1,
            "target_pin": "8.2",
            "mode": "emacs",
            "charset": "ascii-printable",
            "line_model": "single-line",
        },
        "fixture": {
            "identity": "readline-emacs-ascii",
            "control_abi_version": 1,
            "health": {
                "alive": True,
                "responsive": True,
                "baseline": True,
            },
        },
        "hypothesis": {
            "settings": {
                "max_examples": 100 if profile == "deep" else 10,
                "max_len": hypo_max_len,
                "max_steps": hypo_max_steps,
                "alphabet_size": len(US_PRINTABLE_ALPHABET),
            },
            "examples_generated": examples_generated,
            "seed": seed,
            "duration_sec": 0.0,
        },
        "ui_scenario": {
            "status": "dry_run",
            "steps": ui_dry_steps,
            "classification_totals": ui_cls_totals,
            "failing": 0,
        },
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    writer.write_summary(summary)
    writer.write_result_txt("dry_run", f"profile={profile} classify={classify}")

    # Persist replay context artifact in dry-run mode so the full
    # deterministic sequence is recoverable from the failure artifact
    # without hardware. Use the first generated sequence.
    dry_run_sequence = (
        generated_sequences[0] if generated_sequences else []
    )
    writer.write_replay_context(
        full_sequence=dry_run_sequence,
        failing_prefix=[] if not classify or classify == "pass"
            else dry_run_sequence,
        seed=seed,
        profile=profile,
        max_len=hypo_max_len,
        max_steps=hypo_max_steps,
        hypothesis_failure=None,
    )

    print(json.dumps({
        "phase": "dry_run_complete",
        "out_dir": out_dir,
        "summary": str(writer.summary_path),
        "classification_totals": writer.classification_totals,
        "examples_generated": examples_generated,
    }))

    return True


# ── Replay Mode ──────────────────────────────────────────────────────────────

def run_replay(args: dict) -> bool:
    """Replay from saved replay data.

    Accepts either a per-step replay.json or a replay_context.json
    containing the full sequence.

    In single-step mode (--single-step), executes the full sequence
    (or single step) on hardware: setText + F24 + barrier + snapshot.
    Requires bench.toml for hardware addresses.

    In validation mode (no --single-step), just validates the replay
    data structure without hardware.
    """
    replay_path = args["replay"]
    single_step = args.get("single_step", False)
    if single_step and not args.get("out_dir"):
        args["out_dir"] = (
            f"artifacts/run_editor_replay_{int(time.time())}"
        )

    try:
        with open(replay_path) as f:
            replay = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(json.dumps({
            "phase": "replay_error",
            "error": f"cannot load replay data: {e}",
        }), file=sys.stderr)
        return False

    # Detect replay_context.json (full sequence) vs per-step replay.json
    is_context = "full_sequence" in replay or "failing_prefix" in replay

    if is_context:
        # Validate replay context structure
        required_fields = [
            "full_sequence", "failing_prefix", "seed", "profile",
        ]
        missing = [f for f in required_fields if f not in replay]
        if missing:
            print(json.dumps({
                "phase": "replay_validation_error",
                "error": f"missing replay context fields: {missing}",
            }), file=sys.stderr)
            return False

        full_seq = replay.get("full_sequence", [])
        failing_prefix = replay.get("failing_prefix", [])
        seed = replay.get("seed")

        print(json.dumps({
            "phase": "replay_start",
            "replay": replay_path,
            "type": "replay_context",
            "single_step": single_step,
            "sequence_length": len(full_seq),
            "prefix_length": len(failing_prefix),
            "seed": seed,
        }))

        if not single_step:
            # Validate mode: just check structure, no hardware
            print(json.dumps({
                "phase": "replay_validation",
                "status": "valid",
                "fields": required_fields,
                "sequence_length": len(full_seq),
                "prefix_length": len(failing_prefix),
                "seed": seed,
            }))
            return True

        # Hardware replay: execute the full failing prefix sequence
        bench_path = args.get("bench_toml")
        if not bench_path:
            print(json.dumps({
                "phase": "replay_error",
                "error": "replay --single-step requires --bench-toml",
            }), file=sys.stderr)
            return False

        runner = ConformanceRunner(args)
        setup_ok = runner.setup_session()
        if not setup_ok:
            runner.cleanup()
            return False

        # A failing prefix records an expected failure; an empty prefix is a
        # passing-sequence replay. Command success means the recorded outcome
        # reproduced, not merely that the replayed step happened to pass.
        replay_sequence = failing_prefix if failing_prefix else full_seq
        expected_failure = bool(failing_prefix)
        replay_steps = []
        all_match = True

        try:
            if not runner.setup_sequence():
                print(json.dumps({
                    "phase": "replay_error",
                    "error": "fixture reset failed for replay",
                }), file=sys.stderr)
                runner.cleanup()
                return False

            for i, desired in enumerate(replay_sequence):
                step = runner.execute_step(
                    runner.next_seq(), desired,
                    args.get("timeout_mult", 1.0),
                )
                replay_steps.append(step)
                if step.get("match") is False:
                    all_match = False
                    print(json.dumps({
                        "phase": "replay_step",
                        "step": i,
                        "status": "failed",
                        "classification": step.get("classification"),
                        "failure_detail": (
                            step.get("failure_detail") or ""
                        )[:200],
                    }))
                    break
                else:
                    print(json.dumps({
                        "phase": "replay_step",
                        "step": i,
                        "status": "passed",
                    }))
        finally:
            runner.cleanup()

        reproduces = (not all_match) if expected_failure else all_match
        print(json.dumps({
            "phase": "replay_result",
            "sequence_length": len(replay_sequence),
            "steps_executed": len(replay_steps),
            "all_match": all_match,
            "expected_failure": expected_failure,
            "reproduces": reproduces,
            "steps": replay_steps,
        }))
        return reproduces

    else:
        # Per-step replay.json
        required_fields = [
            "desired", "lcp", "old_mid", "new_mid",
            "plan_ops", "predicted", "classification",
        ]
        missing = [f for f in required_fields if f not in replay]
        if missing:
            print(json.dumps({
                "phase": "replay_validation_error",
                "error": f"missing required replay fields: {missing}",
            }), file=sys.stderr)
            return False

        print(json.dumps({
            "phase": "replay_start",
            "replay": replay_path,
            "type": "per_step",
            "single_step": single_step,
            "classification": replay.get("classification"),
            "desired": replay.get("desired", "")[:50],
        }))

        if not single_step:
            # Validate mode: just check structure, no hardware
            print(json.dumps({
                "phase": "replay_validation",
                "status": "valid",
                "fields": required_fields,
                "classification": replay.get("classification"),
            }))
            return True

        bench_path = args.get("bench_toml")
        if not bench_path:
            print(json.dumps({
                "phase": "replay_error",
                "error": "replay --single-step requires --bench-toml",
            }), file=sys.stderr)
            return False

        runner = ConformanceRunner(args)
        setup_ok = runner.setup_session()
        if not setup_ok:
            runner.cleanup()
            return False

        try:
            if not runner.setup_sequence():
                print(json.dumps({
                    "phase": "replay_error",
                    "error": "fixture reset failed for replay",
                }), file=sys.stderr)
                return False

            desired = replay["desired"]
            step = runner.execute_step(
                runner.next_seq(), desired,
                args.get("timeout_mult", 1.0),
            )
        finally:
            runner.cleanup()

        expected_classification = replay.get("classification")
        actual_classification = step.get("classification")
        reproduces = actual_classification == expected_classification

        print(json.dumps({
            "phase": "replay_result",
            "expected_classification": expected_classification,
            "actual_classification": actual_classification,
            "step": step,
            "match": step.get("match"),
            "reproduces": reproduces,
        }))
        return reproduces


# ── Main ──────────────────────────────────────────────────────────────────────

def main(argv: list[str]) -> int:
    args = parse_args(argv)

    # Dry-run mode
    if args.get("dry_run"):
        ok = run_dry_run(args)
        return 0 if ok else 1

    # Replay mode
    if args.get("replay"):
        ok = run_replay(args)
        return 0 if ok else 1

    # Hardware mode
    runner = ConformanceRunner(args)
    hypo_passed, hypo_steps = runner.run()
    ui_steps = getattr(runner, "ui_steps", [])

    # Always cleanup
    runner.cleanup()
    # Build final summary
    duration = int(time.monotonic() - runner.start_time)
    saw_hypo_failure = any(
        s.get("match") is False for s in hypo_steps
    )
    hypo_success = hypo_passed and not saw_hypo_failure

    saw_ui_failure = any(
        s.get("match") is False for s in ui_steps
    )
    ui_success = len(ui_steps) == len(FIXED_UI_STEPS) and not saw_ui_failure

    hypo_settings = runner.get_hypothesis_settings(
        args.get("profile", "quick")
    )

    # Count UI classifications
    ui_cls_totals: dict[str, int] = {c: 0 for c in CLASSIFICATIONS}
    for s in ui_steps:
        cls = s.get("classification", "environment")
        if cls in ui_cls_totals:
            ui_cls_totals[cls] += 1

    summary = {
        "scenario": "editor_conformance",
        "status": "pass" if hypo_success and ui_success else "fail",
        "profile": args.get("profile", "quick"),
        "target_package": {
            "id": runner.fqdn_package_id or "readline-emacs-ascii",
            "version": runner.fqdn_package_version,
            "host_abi": runner.fqdn_host_abi,
            "target_pin": "8.2",
            "mode": "emacs",
            "charset": "ascii-printable",
        },
        "fixture": {
            "identity": runner.fixture_identity,
            "control_abi_version": runner.fixture_control_abi,
            "health": runner.fixture_health_status,
        },
        "hypothesis": {
            "settings": hypo_settings,
            "examples": len(hypo_steps),
            "seed": args.get("seed"),
            "duration_sec": duration,
        },
        "ui_scenario": {
            "status": "pass" if ui_success else "fail",
            "steps": ui_steps,
            "classification_totals": ui_cls_totals,
            "failing": sum(1 for s in ui_steps if s.get("match") is False),
        },
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "log_paths": {
            "android_logcat": str(runner.artifacts.android_logcat_path)
                if runner.artifacts else None,
            "esp_uart": str(runner.artifacts.esp_uart_path)
                if runner.artifacts else None,
            "hid_observer": str(runner.artifacts.hid_observer_path)
                if runner.artifacts else None,
            "fixture_snapshot": str(runner.artifacts.fixture_snapshot_path)
                if runner.artifacts else None,
        },
    }

    # Combined pass/fail for exit code: both must pass
    all_success = hypo_success and ui_success

    if runner.artifacts:
        runner.artifacts.write_summary(summary)
        status_str = "pass" if all_success else "fail"
        failure_detail = None
        for s in hypo_steps + ui_steps:
            if s.get("failure_detail"):
                failure_detail = s["failure_detail"]
                break
        runner.artifacts.write_result_txt(status_str, failure_detail or "")

    print(json.dumps({
        "phase": "result",
        "status": "pass" if all_success else "fail",
        "hypothesis_steps": len(hypo_steps),
        "hypothesis_failing": sum(1 for s in hypo_steps if s.get("match") is False),
        "ui_steps": len(ui_steps),
        "ui_failing": sum(1 for s in ui_steps if s.get("match") is False),
        "classification_totals": runner.artifacts.classification_totals
            if runner.artifacts else {},
        "duration": duration,
        "summary": str(runner.artifacts.summary_path)
            if runner.artifacts else None,
    }))

    return 0 if all_success else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
