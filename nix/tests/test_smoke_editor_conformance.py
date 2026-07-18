"""Behavioral contracts for pure-ABI Editor conformance evidence."""
from __future__ import annotations

import base64
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest


EVIDENCE_FIELDS = {
    "seq",
    "current_text",
    "desired_text",
    "opaque_input_state",
    "opaque_output_state",
    "symbolic_actions",
    "compiled_operations",
    "package_id",
    "package_version",
    "package_source_hash",
    "host_abi",
    "layout_id",
    "cost_metric_id",
    "policy_id",
    "transaction_outcome",
    "fixture_result",
    "barrier_consumed",
    "match",
    "classification",
    "failure_detail",
    "duration_sec",
}


@pytest.fixture(scope="module")
def runner_module():
    """Load the runner without invoking its command-line entry point."""
    runner_path = Path(
        os.environ.get(
            "SLEEPWALKER_EDITOR_RUNNER",
            Path(__file__).resolve().parents[1] / "smoke-editor-conformance.py",
        )
    )
    spec = importlib.util.spec_from_file_location(
        "sleepwalker_smoke_editor_conformance_test_subject", runner_path
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(autouse=True)
def forbid_external_commands(runner_module, monkeypatch):
    """Keep every contract test hermetic."""
    monkeypatch.setattr(
        runner_module,
        "run_cmd",
        lambda *args, **kwargs: pytest.fail(f"unexpected external command: {args[0]!r}"),
    )


def _abi_evidence(**overrides):
    raw = {
        "seq": 17,
        "ok": True,
        "current_text": "before",
        "desired_text": "after",
        "opaque_input_state": {"mode": "insert", "cursor": [2, 4]},
        "opaque_output_state": {"mode": "command", "cursor": [5, 1]},
        "symbolic_actions": [
            {"kind": "down", "usage": "USB_KEY_LEFTSHIFT"},
            {"kind": "text", "text": "after"},
            {"kind": "up", "usage": "USB_KEY_LEFTSHIFT"},
        ],
        "compiled_operations": [
            "down USB_KEY_LEFTSHIFT",
            "tap USB_KEY_A",
            "up USB_KEY_LEFTSHIFT",
        ],
        "package_id": "readline-emacs-ascii",
        "package_version": "2.0.0",
        "package_source_hash": "sha256:package-source",
        "host_abi": 1,
        "layout_id": "us-qwerty:2026-07",
        "cost_metric_id": "low-level-ops:v1",
        "policy_id": "CONFORMANCE",
        "transaction_outcome": "COMMITTED",
    }
    raw.update(overrides)
    return raw


def _runner(module):
    runner = module.ConformanceRunner({})
    runner.android_serial = "test-android"
    runner.observer_target = "test-observer"
    runner.observer_identity = "test-identity"
    runner.known_hosts = "test-known-hosts"
    return runner


def _replay_step(**overrides):
    step = {
        "seq": 3,
        "current_text": "before",
        "desired_text": "after",
        "opaque_input_state": {"mode": "insert", "cursor": [2, 4]},
        "opaque_output_state": {"mode": "command", "cursor": [5, 1]},
        "symbolic_actions": [{"kind": "text", "text": "after"}],
        "compiled_operations": ["tap USB_KEY_A"],
        "package_id": "readline-emacs-ascii",
        "package_version": "2.0.0",
        "package_source_hash": "sha256:package-source",
        "host_abi": 1,
        "layout_id": "us-qwerty:2026-07",
        "cost_metric_id": "low-level-ops:v1",
        "policy_id": "CONFORMANCE",
        "transaction_outcome": "COMMITTED",
        "fixture_result": {"buffer": "wrong", "contract_version": 1},
        "barrier_consumed": True,
        "match": False,
        "classification": "semantic",
        "failure_detail": "observed != desired",
        "duration_sec": 0.01,
    }
    step.update(overrides)
    return step


class TestPureAbiDiagnosticEvidence:
    def test_adb_diagnostic_round_trips_complete_pure_abi_evidence(self, runner_module):
        """ADB parsing preserves the complete replayable ABI boundary."""
        expected = _abi_evidence()
        response = json.dumps(
            {
                "seq": expected["seq"],
                "adb_out": "Broadcast completed: result=0, data=" + json.dumps(expected),
            }
        )

        diagnostic = runner_module.EditorDiagnostic.from_adb_response(response)
        actual = diagnostic.to_dict()

        assert {field: actual[field] for field in expected} == expected

    def test_malformed_adb_output_is_a_transport_failure_before_any_plan(self, runner_module):
        """Unparseable command output cannot be mistaken for a package plan."""
        diagnostic = runner_module.EditorDiagnostic.from_adb_response("not-json")

        assert diagnostic.transport_status == "parse_error"
        assert diagnostic.is_planning_failure() is False


class TestConformanceExecution:
    def test_step_retains_symbolic_compiled_identity_and_commit_evidence(
        self, runner_module, monkeypatch
    ):
        """A completed request retains symbolic and compiled ABI evidence."""
        runner = _runner(runner_module)
        diagnostic = runner_module.EditorDiagnostic(_abi_evidence())
        calls = []
        monkeypatch.setattr(runner_module, "adb_set_text_encoded", lambda *_, **__: diagnostic)
        monkeypatch.setattr(
            runner_module,
            "adb_inject_f24",
            lambda *_: calls.append("inject-f24"),
        )
        monkeypatch.setattr(
            runner_module,
            "fixture_await_barrier",
            lambda *_: calls.append("await-barrier") or True,
        )
        monkeypatch.setattr(
            runner_module,
            "fixture_snapshot",
            lambda *_: calls.append("snapshot")
            or {"buffer": "after", "contract_version": 1},
        )

        step = runner.execute_step(17, "after")

        assert {
            field: step[field]
            for field in EVIDENCE_FIELDS
            - {"fixture_result", "barrier_consumed", "match", "classification", "failure_detail", "duration_sec"}
        } == {
            field: _abi_evidence()[field]
            for field in EVIDENCE_FIELDS
            - {"fixture_result", "barrier_consumed", "match", "classification", "failure_detail", "duration_sec"}
        }
        assert step["transaction_outcome"] == "COMMITTED"
        assert step["classification"] == "pass"
        assert step["match"] is True
        assert calls == ["inject-f24", "await-barrier", "snapshot"]

    def test_planning_rejection_never_injects_the_fixture_barrier(
        self, runner_module, monkeypatch
    ):
        """A rejected symbolic plan is classified before the external F24 barrier."""
        runner = _runner(runner_module)
        attempted = []
        monkeypatch.setattr(
            runner_module,
            "adb_set_text_encoded",
            lambda *_, **__: runner_module.EditorDiagnostic(
                _abi_evidence(
                    ok=False,
                    failure="reserved symbolic F24",
                    failure_class="planning",
                    symbolic_actions=[{"kind": "tap", "usage": "USB_KEY_F24"}],
                    compiled_operations=[],
                    opaque_output_state=None,
                    transaction_outcome="FAILED",
                )
            ),
        )
        monkeypatch.setattr(
            runner_module, "adb_inject_f24", lambda *_: attempted.append("f24")
        )

        step = runner.execute_step(17, "after")

        assert step["classification"] == "planning"
        assert step["transaction_outcome"] == "FAILED"
        assert step["symbolic_actions"] == [{"kind": "tap", "usage": "USB_KEY_F24"}]
        assert attempted == []

    def test_oracle_compares_authoritative_rendered_text_not_opaque_state(
        self, runner_module, monkeypatch
    ):
        """Opaque cursor-like values cannot affect a rendered-text pass verdict."""
        runner = _runner(runner_module)
        monkeypatch.setattr(
            runner_module,
            "adb_set_text_encoded",
            lambda *_, **__: runner_module.EditorDiagnostic(
                _abi_evidence(
                    opaque_output_state={"cursor": -999, "mode": "private"},
                )
            ),
        )
        monkeypatch.setattr(runner_module, "adb_inject_f24", lambda *_: None)
        monkeypatch.setattr(runner_module, "fixture_await_barrier", lambda *_: True)
        monkeypatch.setattr(
            runner_module,
            "fixture_snapshot",
            lambda *_: {"buffer": "after", "point": 0, "contract_version": 1},
        )

        step = runner.execute_step(17, "after")

        assert step["match"] is True
        assert step["classification"] == "pass"

    def test_fixture_snapshot_is_not_taken_before_barrier_consumption(
        self, runner_module, monkeypatch
    ):
        """The fixture barrier, rather than a delay, authorizes the snapshot."""
        runner = _runner(runner_module)
        monkeypatch.setattr(
            runner_module,
            "adb_set_text_encoded",
            lambda *_, **__: runner_module.EditorDiagnostic(_abi_evidence()),
        )
        monkeypatch.setattr(runner_module, "adb_inject_f24", lambda *_: None)
        monkeypatch.setattr(runner_module, "fixture_await_barrier", lambda *_: False)
        monkeypatch.setattr(
            runner_module,
            "fixture_snapshot",
            lambda *_: pytest.fail("snapshot ran before fixture consumed F24"),
        )

        step = runner.execute_step(17, "after")

        assert step["classification"] == "sync"
        assert step["barrier_consumed"] is False


class TestConformanceSetupPolicy:
    @pytest.mark.parametrize(
        ("policy_id", "expected_ready"),
        [("CONFORMANCE", True), ("PRODUCTION", False)],
        ids=["reserved-for-conformance", "production-policy-rejected"],
    )
    def test_setup_requires_explicit_conformance_f24_reservation(
        self, runner_module, monkeypatch, tmp_path, policy_id, expected_ready
    ):
        """Conformance cannot begin unless its configured policy reserves F24."""
        bench = tmp_path / "bench.toml"
        bench.write_text(
            """[hid_observer]\nssh_target = \"observer\"\n\n[android]\nadb_serial = \"android\"\n\n[esp]\nuart_port = \"/dev/null\"\n"""
        )
        healthy = {
            "ok": True,
            "alive": True,
            "responsive": True,
            "baseline": True,
            "f24_bound": True,
            "keymap_ok": True,
            "keymap": "emacs",
            "keymap_pin": "emacs",
            "vt": "/dev/tty42",
            "console_f24_keyseq_ok": True,
        }
        identity = {
            "control_abi_version": 1,
            "identity": {
                "fixture": "sleepwalker-readline-fixture",
                "readline_version": "gnu-readline 8.2",
                "keymap": "emacs",
                "input_mode": "ascii-printable",
                "line_mode": "single-line",
            },
        }
        runner = runner_module.ConformanceRunner(
            {"bench_toml": str(bench), "out_dir": str(tmp_path / "out")}
        )
        monkeypatch.setattr(runner_module, "run_cmd", lambda *_: None)
        monkeypatch.setattr(runner_module.subprocess, "Popen", lambda *_: SimpleNamespace(pid=42))
        monkeypatch.setattr(runner_module, "fixture_start", lambda *_: None)
        monkeypatch.setattr(runner_module, "fixture_describe", lambda *_: identity)
        monkeypatch.setattr(runner_module, "fixture_health", lambda *_: healthy)
        monkeypatch.setattr(runner_module, "adb_connect", lambda *_: None)
        monkeypatch.setattr(runner_module, "adb_arm", lambda *_: None)
        monkeypatch.setattr(runner, "_wait_for_android_event", lambda *_: {})
        monkeypatch.setattr(
            runner_module,
            "adb_reset_editor",
            lambda *_: runner_module.EditorDiagnostic(_abi_evidence(policy_id=policy_id)),
        )

        assert runner.setup_session() is expected_ready


class TestReplayArtifacts:
    def test_failing_artifact_preserves_exact_opaque_actions_and_identities(
        self, runner_module, tmp_path
    ):
        """A failing step writes the exact pure-ABI input/output needed for replay."""
        writer = runner_module.ArtifactWriter(str(tmp_path))
        original = _replay_step()

        writer.record_step(original)

        replay_path = next(writer.replay_dir.glob("*/replay.json"))
        replay = json.loads(replay_path.read_text())
        expected = {
            field: original[field]
            for field in EVIDENCE_FIELDS
            - {"match", "failure_detail", "duration_sec"}
        }
        assert {field: replay[field] for field in expected} == expected

    def test_shrunk_context_preserves_step_order_and_exact_abi_values(
        self, runner_module, tmp_path
    ):
        """Shrinking records ordered ABI boundaries, not regenerated desired strings."""
        writer = runner_module.ArtifactWriter(str(tmp_path))
        first = _replay_step(
            seq=1,
            current_text="",
            desired_text="first",
            opaque_input_state={"epoch": 1},
            opaque_output_state={"epoch": 2},
            match=True,
            classification="pass",
        )
        second = _replay_step(
            seq=2,
            current_text="first",
            desired_text="second",
            opaque_input_state={"epoch": 2},
            opaque_output_state={"epoch": 3},
        )

        writer.write_replay_context(
            full_sequence=["first", "second"],
            failing_prefix=["first", "second"],
            ordered_steps=[first, second],
            seed=991,
            profile="focused",
            max_len=12,
            max_steps=2,
            hypothesis_failure="semantic mismatch",
        )

        context = json.loads((tmp_path / "replay_context.json").read_text())
        assert context["ordered_steps"] == [first, second]
        assert context["failing_prefix"] == ["first", "second"]

    @pytest.mark.parametrize(
        "identity_field",
        [
            "package_id",
            "package_version",
            "package_source_hash",
            "host_abi",
            "layout_id",
            "cost_metric_id",
            "policy_id",
        ],
    )
    def test_replay_rejects_each_identity_mismatch_before_execution(
        self, runner_module, monkeypatch, tmp_path, capsys, identity_field
    ):
        """Replay rejects package, source, ABI, layout, metric, and policy drift."""
        replay = _replay_step()
        replay[identity_field] = f"recorded-{identity_field}"
        replay_path = tmp_path / "replay.json"
        replay_path.write_text(json.dumps(replay))

        class ReplayRunner:
            session_package_id = _replay_step()["package_id"]
            session_package_version = _replay_step()["package_version"]
            session_package_source_hash = _replay_step()["package_source_hash"]
            session_host_abi = _replay_step()["host_abi"]
            session_layout_id = _replay_step()["layout_id"]
            session_cost_metric_id = _replay_step()["cost_metric_id"]
            session_policy_id = _replay_step()["policy_id"]

            def setup_session(self):
                return True
            def setup_sequence(self):
                pytest.fail("identity-mismatched replay reached sequence setup")

            def execute_step(self, *args, **kwargs):
                pytest.fail("identity-mismatched replay executed")

            def cleanup(self):
                pass

        monkeypatch.setattr(runner_module, "ConformanceRunner", lambda _: ReplayRunner())

        assert runner_module.run_replay(
            {"replay": str(replay_path), "single_step": True, "bench_toml": "bench.toml"}
        ) is False
        assert identity_field in capsys.readouterr().err

    def test_replay_restores_each_ordered_current_text_and_opaque_input(
        self, runner_module, monkeypatch, tmp_path
    ):
        """Hardware replay sends recorded ABI inputs in their original sequence."""
        first = _replay_step(
            seq=1,
            current_text="",
            desired_text="first",
            opaque_input_state={"epoch": 1},
            opaque_output_state={"epoch": 2},
            match=True,
            classification="pass",
        )
        second = _replay_step(
            seq=2,
            current_text="first",
            desired_text="second",
            opaque_input_state={"epoch": 2},
            opaque_output_state={"epoch": 3},
        )
        replay_path = tmp_path / "replay_context.json"
        replay_path.write_text(
            json.dumps(
                {
                    "full_sequence": ["first", "second"],
                    "failing_prefix": ["first", "second"],
                    "ordered_steps": [first, second],
                    "seed": 991,
                    "profile": "focused",
                }
            )
        )
        calls = []

        class ReplayRunner:
            session_package_id = first["package_id"]
            session_package_version = first["package_version"]
            session_package_source_hash = first["package_source_hash"]
            session_host_abi = first["host_abi"]
            session_layout_id = first["layout_id"]
            session_cost_metric_id = first["cost_metric_id"]
            session_policy_id = first["policy_id"]

            def setup_session(self):
                return True

            def setup_sequence(self):
                return True

            def next_seq(self):
                return len(calls) + 1

            def execute_step(self, seq, desired, timeout_mult, **kwargs):
                calls.append(
                    (
                        seq,
                        desired,
                        json.loads(
                            base64.urlsafe_b64decode(
                                kwargs["opaque_input_state_encoded"]
                            ).decode("utf-8")
                        ),
                        base64.urlsafe_b64decode(
                            kwargs["current_text_encoded"]
                        ).decode("utf-8"),
                    )
                )
                return {"match": desired != "second", "classification": "semantic"}

            def cleanup(self):
                pass

        monkeypatch.setattr(runner_module, "ConformanceRunner", lambda _: ReplayRunner())

        assert runner_module.run_replay(
            {"replay": str(replay_path), "single_step": True, "bench_toml": "bench.toml"}
        ) is True
        assert calls == [
            (1, "first", {"epoch": 1}, ""),
            (2, "second", {"epoch": 2}, "first"),
        ]


class TestFailureClassification:
    @pytest.mark.parametrize(
        "classification",
        [
            "semantic",
            "planning",
            "fixture",
            "sync",
            "transport",
            "environment",
            "non_reproducible",
            "completion_timeout",
        ],
    )
    def test_dry_run_preserves_requested_failure_classification(
        self, runner_module, tmp_path, classification
    ):
        """Every externally reported failure class survives pure-ABI artifact generation."""
        out_dir = tmp_path / classification

        assert runner_module.run_dry_run(
            {"out_dir": str(out_dir), "profile": "focused", "seed": 991, "classify": classification}
        ) is True

        summary = json.loads((out_dir / "summary.json").read_text())
        step = summary["per_step"][0]
        assert step["classification"] == classification
        assert step["match"] is False
        assert EVIDENCE_FIELDS <= set(step)
