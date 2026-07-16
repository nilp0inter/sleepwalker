"""No-hardware contracts for the Editor conformance runner.

The runner is loaded by path so these tests exercise its public local behavior
without importing its CLI entry point or invoking any bench-facing helper.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path

import pytest


@pytest.fixture(scope="module")
def runner_module():
    """Load the runner under a non-main module name without invoking main()."""
    runner_path = Path(
        os.environ.get(
            "SLEEPWALKER_EDITOR_RUNNER",
            Path(__file__).resolve().parents[1]
            / "smoke-editor-conformance.py",
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
    """Fail closed if a local contract test reaches the command boundary."""
    def blocked_run_cmd(*args, **kwargs):
        raise AssertionError(f"unexpected external command: {args[0]!r}")

    monkeypatch.setattr(runner_module, "run_cmd", blocked_run_cmd)


def _runner(module):
    runner = module.ConformanceRunner({})
    runner.android_serial = "test-android"
    runner.observer_target = "test-observer"
    runner.observer_identity = "test-identity"
    runner.known_hosts = "test-known-hosts"
    return runner


def _diagnostic(module, **overrides):
    raw = {
        "seq": 17,
        "ok": True,
        "plan_ops": ["replace"],
        "predicted": {"buffer": "expected", "point": 8, "revision": 4},
    }
    raw.update(overrides)
    return module.EditorDiagnostic(raw)


def _healthy_fixture():
    return {
        "ok": True,
        "alive": True,
        "responsive": True,
        "baseline": True,
        "f24_bound": True,
        "keymap_ok": True,
        "keymap": "emacs",
        "keymap_pin": "emacs",
        "vt": "/dev/tty42",
    }


class TestEditorDiagnosticParsing:
    @pytest.mark.parametrize(
        ("raw_stdout", "expected_status"),
        [
            ("not-json", "parse_error"),
            (json.dumps({"seq": 29, "adb_out": "broadcast ended"}), "no_diagnostic"),
        ],
        ids=["invalid-adb-wrapper", "wrapper-without-editor-diagnostic"],
    )
    def test_unparseable_adb_responses_surface_transport_status(
        self, runner_module, raw_stdout, expected_status
    ):
        diagnostic = runner_module.EditorDiagnostic.from_adb_response(raw_stdout)

        assert diagnostic.ok is False
        assert diagnostic.transport_status == expected_status

    def test_embedded_broadcast_diagnostic_preserves_editor_prediction(
        self, runner_module
    ):
        expected = {
            "seq": 61,
            "ok": True,
            "plan_ops": ["ctrl-a", "type café"],
            "predicted": {"buffer": "caf\u00e9", "point": 4, "revision": 9},
        }
        raw_stdout = json.dumps({
            "seq": 61,
            "adb_out": (
                "Broadcast completed: result=0, data=" + json.dumps(expected)
            ),
        })

        diagnostic = runner_module.EditorDiagnostic.from_adb_response(raw_stdout)

        assert diagnostic.seq == 61
        assert diagnostic.ok is True
        assert diagnostic.plan_ops == ["ctrl-a", "type caf\u00e9"]
        assert diagnostic.predicted_buffer == "caf\u00e9"
        assert diagnostic.predicted_point == 4
        assert diagnostic.predicted_revision == 9

    def test_quoted_broadcast_diagnostic_preserves_escaped_text_fields(
        self, runner_module
    ):
        expected = {
            "seq": 62,
            "ok": True,
            "old_mid": 'old "quoted" \\ segment',
            "new_mid": 'new "quoted" \\ segment',
            "predicted": {
                "buffer": 'prefix "quoted" \\ suffix',
                "point": 25,
                "revision": 10,
            },
        }
        raw_stdout = json.dumps({
            "seq": 62,
            "adb_out": (
                "Broadcast completed: result=0, data="
                + json.dumps(json.dumps(expected))
            ),
        })

        diagnostic = runner_module.EditorDiagnostic.from_adb_response(raw_stdout)

        assert diagnostic.ok is True
        assert diagnostic.transport_status != "no_diagnostic"
        assert diagnostic.old_mid == 'old "quoted" \\ segment'
        assert diagnostic.new_mid == 'new "quoted" \\ segment'
        assert diagnostic.predicted_buffer == 'prefix "quoted" \\ suffix'
        assert diagnostic.predicted_point == 25
        assert diagnostic.predicted_revision == 10


class TestStepClassification:
    @pytest.mark.parametrize(
        ("diagnostic_raw", "expected_classification"),
        [
            (
                {
                    "ok": False,
                    "failure": "InconsistentPrediction",
                    "failure_class": "planning",
                    "plan_ops": [],
                },
                "planning",
            ),
            (
                {
                    "ok": False,
                    "failure": "firmware disarmed",
                    "failure_class": "transport",
                    "transport_status": "disarmed",
                },
                "transport",
            ),
        ],
        ids=["editor-rejection", "firmware-transport-failure"],
    )
    def test_pre_execution_failures_do_not_inject_f24(
        self, runner_module, monkeypatch, diagnostic_raw, expected_classification
    ):
        runner = _runner(runner_module)
        injected = []
        monkeypatch.setattr(
            runner_module,
            "adb_set_text_encoded",
            lambda *_: runner_module.EditorDiagnostic(diagnostic_raw),
        )
        monkeypatch.setattr(
            runner_module,
            "adb_inject_f24",
            lambda *args: injected.append(args),
        )

        step = runner.execute_step(17, "expected")

        assert step["classification"] == expected_classification
        assert step["match"] is False
        assert injected == []

    def test_sync_failure_requires_f24_but_never_snapshots(self, runner_module, monkeypatch):
        runner = _runner(runner_module)
        calls = []
        monkeypatch.setattr(
            runner_module, "adb_set_text_encoded", lambda *_: _diagnostic(runner_module)
        )
        monkeypatch.setattr(
            runner_module, "adb_inject_f24", lambda *args: calls.append("f24")
        )
        monkeypatch.setattr(
            runner_module, "fixture_await_barrier", lambda *_: False
        )
        monkeypatch.setattr(
            runner_module,
            "fixture_snapshot",
            lambda *_: pytest.fail("fixture snapshot must wait for F24 barrier"),
        )

        step = runner.execute_step(17, "expected")

        assert step["classification"] == "sync"
        assert step["match"] is False
        assert step["barrier_consumed"] is False
        assert calls == ["f24"]

    def test_semantic_mismatch_is_reported_after_authoritative_snapshot(
        self, runner_module, monkeypatch
    ):
        runner = _runner(runner_module)
        monkeypatch.setattr(
            runner_module, "adb_set_text_encoded", lambda *_: _diagnostic(runner_module)
        )
        monkeypatch.setattr(runner_module, "adb_inject_f24", lambda *_: None)
        monkeypatch.setattr(runner_module, "fixture_await_barrier", lambda *_: True)
        monkeypatch.setattr(
            runner_module,
            "fixture_snapshot",
            lambda *_: {"buffer": "wrong", "point": 5, "contract_version": 1},
        )

        step = runner.execute_step(17, "expected")

        assert step["classification"] == "semantic"
        assert step["match"] is False
        assert "observed != predicted" in step["failure_detail"]

    @pytest.mark.parametrize(
        ("failure", "expected_classification"),
        [
            (RuntimeError("fixture rejected snapshot"), "fixture"),
            (OSError("observer unavailable"), "environment"),
        ],
        ids=["fixture-failure", "environment-failure"],
    )
    def test_snapshot_failures_keep_fixture_and_environment_distinct(
        self, runner_module, monkeypatch, failure, expected_classification
    ):
        runner = _runner(runner_module)
        monkeypatch.setattr(
            runner_module, "adb_set_text_encoded", lambda *_: _diagnostic(runner_module)
        )
        monkeypatch.setattr(runner_module, "adb_inject_f24", lambda *_: None)
        monkeypatch.setattr(runner_module, "fixture_await_barrier", lambda *_: True)

        def fail_snapshot(*_):
            raise failure

        monkeypatch.setattr(runner_module, "fixture_snapshot", fail_snapshot)

        step = runner.execute_step(17, "expected")

        assert step["classification"] == expected_classification
        assert step["match"] is False

    def test_matching_step_is_not_counted_as_a_failure(self, runner_module, monkeypatch, tmp_path):
        runner = _runner(runner_module)
        monkeypatch.setattr(
            runner_module, "adb_set_text_encoded", lambda *_: _diagnostic(runner_module)
        )
        monkeypatch.setattr(runner_module, "adb_inject_f24", lambda *_: None)
        monkeypatch.setattr(runner_module, "fixture_await_barrier", lambda *_: True)
        monkeypatch.setattr(
            runner_module,
            "fixture_snapshot",
            lambda *_: {"buffer": "expected", "point": 8, "contract_version": 1},
        )

        step = runner.execute_step(17, "expected")
        artifacts = runner_module.ArtifactWriter(str(tmp_path))
        artifacts.record_step(step)

        assert step["classification"] == "pass"
        assert step["match"] is True
        assert sum(artifacts.classification_totals.values()) == 0
        assert list(artifacts.replay_dir.iterdir()) == []


class TestReplayAndSequenceIsolation:
    def test_exact_prefix_replay_reclassifies_and_corrects_totals(
        self, runner_module, monkeypatch, tmp_path
    ):
        runner = _runner(runner_module)
        artifacts = runner_module.ArtifactWriter(str(tmp_path))
        runner.artifacts = artifacts
        original = {
            "seq": 31,
            "desired": "second",
            "match": False,
            "classification": "semantic",
            "failure_detail": "observed != predicted",
        }
        artifacts.record_step(original)
        calls = []
        monkeypatch.setattr(
            runner,
            "setup_sequence",
            lambda: calls.append(("setup_sequence",)) is None,
        )

        def replay_step(seq, desired, timeout_mult):
            calls.append(("execute_step", seq, desired, timeout_mult))
            return {"match": True, "classification": "pass"}

        monkeypatch.setattr(runner, "execute_step", replay_step)

        runner._replay_and_reclassify(
            ["first", "second"], [original], timeout_mult=2.5
        )

        assert calls == [
            ("setup_sequence",),
            ("execute_step", 1, "first", 2.5),
            ("execute_step", 2, "second", 2.5),
        ]
        assert original["classification"] == "non_reproducible"
        assert artifacts.classification_totals["semantic"] == 0
        assert artifacts.classification_totals["non_reproducible"] == 1

    def test_setup_sequence_resets_fixture_and_editor_before_health(
        self, runner_module, monkeypatch
    ):
        runner = _runner(runner_module)
        calls = []
        monkeypatch.setattr(
            runner_module,
            "fixture_reset",
            lambda target, identity, known_hosts: calls.append(
                ("fixture_reset", target, identity, known_hosts)
            ),
        )
        monkeypatch.setattr(
            runner_module,
            "adb_reset_editor",
            lambda serial, seq: calls.append(("adb_reset_editor", serial, seq)),
        )
        monkeypatch.setattr(
            runner_module,
            "fixture_health",
            lambda target, identity, known_hosts: calls.append(
                ("fixture_health", target, identity, known_hosts)
            ) or _healthy_fixture(),
        )
        monkeypatch.setattr(
            runner,
            "_validate_fixture_health",
            lambda health, phase: calls.append(("validate_health", phase, health)),
        )

        assert runner.setup_sequence() is True
        assert calls == [
            ("fixture_reset", "test-observer", "test-identity", "test-known-hosts"),
            ("adb_reset_editor", "test-android", 1),
            ("fixture_health", "test-observer", "test-identity", "test-known-hosts"),
            ("validate_health", "sequence_setup", _healthy_fixture()),
        ]


class TestReplayCliAndDeterminism:
    def test_replay_single_step_parses_explicit_bench_path(self, runner_module):
        parsed = runner_module.parse_args([
            "smoke-editor-conformance.py",
            "--replay",
            "replay_context.json",
            "--single-step",
            "--bench-toml",
            "bench.toml",
        ])

        assert parsed["replay"] == "replay_context.json"
        assert parsed["single_step"] is True
        assert parsed["bench_toml"] == "bench.toml"

    def test_dry_run_accepts_pass_without_counting_a_failure(
        self, runner_module, tmp_path
    ):
        out_dir = tmp_path / "pass"

        assert runner_module.run_dry_run({
            "out_dir": str(out_dir),
            "profile": "quick",
            "seed": 314159,
            "classify": "pass",
        }) is True

        summary = json.loads((out_dir / "summary.json").read_text())
        assert summary["per_step"][0]["classification"] == "pass"
        assert summary["per_step"][0]["match"] is True
        assert sum(summary["classification_totals"].values()) == 0

    def test_same_seed_produces_recoverable_identical_replay_contexts(
        self, runner_module, tmp_path
    ):
        first_out = tmp_path / "first"
        second_out = tmp_path / "second"
        args = {"profile": "quick", "seed": 481516, "classify": None}

        assert runner_module.run_dry_run({**args, "out_dir": str(first_out)}) is True
        assert runner_module.run_dry_run({**args, "out_dir": str(second_out)}) is True

        first = json.loads((first_out / "replay_context.json").read_text())
        second = json.loads((second_out / "replay_context.json").read_text())
        assert first["full_sequence"] == second["full_sequence"]
        assert first["failing_prefix"] == second["failing_prefix"] == []
        assert first["seed"] == second["seed"] == 481516
        assert first["profile"] == second["profile"] == "quick"
        assert first["max_len"] == second["max_len"]
        assert first["max_steps"] == second["max_steps"]



class TestHardwareReplay:
    def test_per_step_replay_resets_before_execution_and_reproduces_expected_failure(
        self, runner_module, monkeypatch, tmp_path
    ):
        replay_path = tmp_path / "replay.json"
        replay_path.write_text(json.dumps({
            "desired": "replacement",
            "lcp": 0,
            "old_mid": "",
            "new_mid": "replacement",
            "plan_ops": ["replace"],
            "predicted": {},
            "classification": "semantic",
        }))
        calls = []

        class ReplayRunner:
            def setup_session(self):
                calls.append("setup_session")
                return True

            def setup_sequence(self):
                calls.append("setup_sequence")
                return True

            def next_seq(self):
                return 73

            def execute_step(self, seq, desired, timeout_mult):
                calls.append(("execute_step", seq, desired))
                return {"classification": "semantic", "match": False}

            def cleanup(self):
                calls.append("cleanup")

        monkeypatch.setattr(
            runner_module, "ConformanceRunner", lambda _: ReplayRunner()
        )

        assert runner_module.run_replay({
            "replay": str(replay_path),
            "single_step": True,
            "bench_toml": "bench.toml",
        }) is True
        assert calls == [
            "setup_session",
            "setup_sequence",
            ("execute_step", 73, "replacement"),
            "cleanup",
        ]

    @pytest.mark.parametrize(
        ("failing_prefix", "step_matches", "expected_success"),
        [
            (["first"], [False], True),
            (["first"], [True], False),
            ([], [True, True], True),
        ],
        ids=[
            "failing-prefix-reproduces",
            "failing-prefix-disappears",
            "empty-prefix-all-match",
        ],
    )
    def test_context_replay_reports_success_only_for_recorded_outcome(
        self,
        runner_module,
        monkeypatch,
        tmp_path,
        failing_prefix,
        step_matches,
        expected_success,
    ):
        replay_path = tmp_path / "replay_context.json"
        replay_path.write_text(json.dumps({
            "full_sequence": ["first", "second"],
            "failing_prefix": failing_prefix,
            "seed": 11,
            "profile": "quick",
        }))
        executed = []

        class ReplayRunner:
            def setup_session(self):
                return True

            def setup_sequence(self):
                return True

            def next_seq(self):
                return len(executed) + 1

            def execute_step(self, seq, desired, timeout_mult):
                executed.append(desired)
                return {"match": step_matches[len(executed) - 1]}

            def cleanup(self):
                pass

        monkeypatch.setattr(
            runner_module, "ConformanceRunner", lambda _: ReplayRunner()
        )

        assert runner_module.run_replay({
            "replay": str(replay_path),
            "single_step": True,
            "bench_toml": "bench.toml",
        }) is expected_success
        expected_sequence = failing_prefix or ["first", "second"]
        assert executed == expected_sequence


class TestTargetPackageArtifacts:
    def test_successful_editor_package_identity_flows_to_complete_summary(
        self, runner_module, monkeypatch, tmp_path
    ):
        runner = _runner(runner_module)
        runner.artifacts = runner_module.ArtifactWriter(str(tmp_path))
        package = {
            "id": "readline-emacs-ascii-conformance",
            "version": "8.2.17",
            "host_abi": 42,
        }
        monkeypatch.setattr(
            runner_module,
            "adb_set_text_encoded",
            lambda *_: _diagnostic(runner_module, package=package),
        )
        monkeypatch.setattr(runner_module, "adb_inject_f24", lambda *_: None)
        monkeypatch.setattr(
            runner_module, "fixture_await_barrier", lambda *_: True
        )
        monkeypatch.setattr(
            runner_module,
            "fixture_snapshot",
            lambda *_: {"buffer": "expected", "point": 8, "contract_version": 1},
        )

        def run_successful_step():
            runner.start_time = runner_module.time.monotonic()
            step = runner.execute_step(17, "expected")
            runner.artifacts.record_step(step)
            return step["match"], [step]

        monkeypatch.setattr(runner, "run", run_successful_step)
        monkeypatch.setattr(runner, "cleanup", lambda: None)
        monkeypatch.setattr(
            runner,
            "get_hypothesis_settings",
            lambda profile: {"profile": profile},
        )
        monkeypatch.setattr(runner_module, "ConformanceRunner", lambda _: runner)

        assert runner_module.main([
            "smoke-editor-conformance.py", "bench.toml", str(tmp_path), "known-hosts",
        ]) == 0

        summary = json.loads((tmp_path / "summary.json").read_text())
        assert summary["target_package"] == {
            **package,
            "target_pin": "8.2",
            "mode": "emacs",
            "charset": "ascii-printable",
        }

class TestFinalResult:
    def test_failed_session_without_steps_returns_failure_result(
        self, runner_module, monkeypatch, capsys
    ):
        class Artifacts:
            classification_totals = {}
            summary = None
            result = None
            android_logcat_path = Path("android.jsonl")
            esp_uart_path = Path("esp.jsonl")
            hid_observer_path = Path("observer.jsonl")
            fixture_snapshot_path = Path("fixture.jsonl")
            summary_path = Path("summary.json")

            def write_summary(self, summary):
                self.summary = summary

            def write_result_txt(self, status, detail):
                self.result = (status, detail)

        class FailedSessionRunner:
            def __init__(self):
                self.start_time = 0.0
                self.artifacts = Artifacts()
                self.fqdn_package_id = None
                self.fqdn_package_version = None
                self.fqdn_host_abi = None
                self.fixture_identity = None
                self.fixture_control_abi = None
                self.fixture_health_status = None
                self.cleaned_up = False

            def run(self):
                return False, []

            def cleanup(self):
                self.cleaned_up = True

            def get_hypothesis_settings(self, profile):
                return {"profile": profile}

        failed_session = FailedSessionRunner()
        monkeypatch.setattr(
            runner_module, "ConformanceRunner", lambda _: failed_session
        )

        exit_code = runner_module.main([
            "smoke-editor-conformance.py", "bench.toml", "out", "known-hosts",
        ])
        final_result = json.loads(capsys.readouterr().out.strip().splitlines()[-1])

        assert exit_code == 1
        assert failed_session.cleaned_up is True
        assert failed_session.artifacts.summary["status"] == "fail"
        assert failed_session.artifacts.result == ("fail", "")
        assert final_result["status"] == "fail"
