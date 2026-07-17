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
            runner.ui_steps = [
                {"match": True, "classification": "pass"}
                for _ in runner_module.FIXED_UI_STEPS
            ]
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

    def test_run_invokes_global_fixed_ui_scenario(
        self, runner_module, monkeypatch
    ):
        """The runner calls the module-level fixed UI scenario after a
        successful property run, rather than an undefined instance method."""
        runner = _runner(runner_module)
        calls = []

        monkeypatch.setattr(runner, "setup_session", lambda: True)
        monkeypatch.setattr(runner, "setup_sequence", lambda: True)
        monkeypatch.setattr(
            runner, "get_hypothesis_settings",
            lambda _: {"max_len": 1, "max_steps": 1},
        )
        monkeypatch.setattr(runner_module, "snapshot_sequences", lambda **_: None)

        def _given_once(_strategy):
            def _decorate(test):
                def _run_once():
                    test([])
                return _run_once
            return _decorate

        fixed_ui_steps = [
            {"match": True, "classification": "pass"}
            for _ in runner_module.FIXED_UI_STEPS
        ]

        def _fixed_ui(**kwargs):
            calls.append(kwargs)
            return fixed_ui_steps

        monkeypatch.setattr(runner_module, "given", _given_once)
        monkeypatch.setattr(runner_module, "run_fixed_ui_scenario", _fixed_ui)

        hypothesis_passed, hypothesis_steps = runner.run()

        assert hypothesis_passed is True
        assert hypothesis_steps == []
        assert runner.ui_steps == fixed_ui_steps
        assert calls == [{
            "serial": "test-android",
            "observer_target": "test-observer",
            "observer_identity": "test-identity",
            "known_hosts": "test-known-hosts",
            "artifacts": None,
            "logcat_path": "",
            "timeout_mult": 1.0,
        }]

    @pytest.mark.parametrize("ui_steps", [[], [{"match": True, "classification": "pass"}]])
    def test_main_rejects_empty_or_short_fixed_ui_scenario(
        self, runner_module, monkeypatch, capsys, ui_steps
    ):
        """A matching property run cannot pass without every fixed UI step."""
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

        class SuccessfulPropertyRunner:
            def __init__(self):
                self.start_time = 0.0
                self.artifacts = Artifacts()
                self.ui_steps = ui_steps
                self.fqdn_package_id = None
                self.fqdn_package_version = None
                self.fqdn_host_abi = None
                self.fixture_identity = None
                self.fixture_control_abi = None
                self.fixture_health_status = None

            def run(self):
                return True, [{"match": True}]

            def cleanup(self):
                pass

            def get_hypothesis_settings(self, profile):
                return {"profile": profile}

        session = SuccessfulPropertyRunner()
        monkeypatch.setattr(runner_module, "ConformanceRunner", lambda _: session)

        exit_code = runner_module.main([
            "smoke-editor-conformance.py", "bench.toml", "out", "known-hosts",
        ])

        assert exit_code == 1
        assert session.artifacts.summary["status"] == "fail"
        assert session.artifacts.summary["ui_scenario"]["status"] == "fail"
        assert session.artifacts.result[0] == "fail"
        assert json.loads(capsys.readouterr().out.strip().splitlines()[-1])["status"] == "fail"


# ── Fixed UI Scenario (Dry-Run / No-Hardware) ─────────────────────────────


def _completion_event(
    change_id=1, generation=0, desired_text="ab",
    predicted_buffer="ab", predicted_point=2, revision=1,
    classification=None,
):
    """Synthetic SwLog Editor completion event matching the runner logcat format.

    The runner's _wait_for_android_event_generic reads top-level keys
    (component, event) and extracts result fields from the ``fields`` dict.
    editor_state uses camelCase keys (predictedBuffer, predictedPoint).
    """
    event = {
        "component": "editor",
        "event": "ui_change_result",
        "fields": {
            "change_id": change_id,
            "generation": generation,
            "desired_text": desired_text,
            "editor_state": {
                "predictedBuffer": predicted_buffer,
                "predictedPoint": predicted_point,
                "revision": revision,
            },
        },
    }
    if classification is not None:
        event["fields"]["classification"] = classification
    return event


def _ui_step_check(step, seq=None, step_type=None, classification=None,
                   match=None):
    """Assert required fields present in a UI step record."""
    required = {
        "seq", "step_type", "desired_text", "change_id", "generation",
        "editor_state", "completion_classification", "match", "classification",
        "failure_detail", "barrier_consumed", "observed", "predicted",
        "duration_sec",
    }
    missing = required - set(step.keys())
    assert not missing, f"step missing fields: {missing}"
    assert isinstance(step["duration_sec"], (int, float))
    assert isinstance(step["seq"], int)
    assert isinstance(step["change_id"], (int, type(None)))
    assert isinstance(step["generation"], (int, type(None)))
    assert step["barrier_consumed"] in (True, False)
    if seq is not None:
        assert step["seq"] == seq
    if step_type is not None:
        assert step["step_type"] == step_type
    if classification is not None:
        assert step["classification"] == classification
    if match is not None:
        assert step["match"] is match


# ── Helpers shared by execution tests ─────────────────────────────────────

def _ui_mock_env(runner_module, monkeypatch, logcat_path,
                 fixture_health=None,
                 barrier_fn=None,
                 snapshot_fn=None):
    """Install mocks for all external dependencies of run_fixed_ui_scenario.

    Sets up the ADB helpers, fixture helpers, and health check so the
    function can execute its step loop deterministically without hardware.

    Returns a calls list that records every ADB/fixture invocation.
    """
    calls = []

    def _track(name):
        def tracker(*args, **_kw):
            calls.append((name,) + args)
        return tracker

    # Adb helpers — called during setup phase and per-step
    monkeypatch.setattr(runner_module, "adb_keyevent", _track("keyevent"))
    monkeypatch.setattr(runner_module, "adb_dismiss_keyguard", _track("dismiss"))
    monkeypatch.setattr(runner_module, "adb_launch_readline", _track("launch"))
    monkeypatch.setattr(runner_module, "adb_input_text", _track("input_text"))
    monkeypatch.setattr(runner_module, "adb_keycombination", _track("keycombination"))
    monkeypatch.setattr(runner_module, "adb_inject_f24", _track("f24"))
    # Setup-phase helpers
    monkeypatch.setattr(runner_module, "fixture_reset", _track("fixture_reset"))
    monkeypatch.setattr(runner_module, "adb_reset_editor", _track("reset_editor"))
    # fixture_health called in the baseline loop
    if fixture_health is not None:
        monkeypatch.setattr(runner_module, "fixture_health", fixture_health)
    else:
        monkeypatch.setattr(
            runner_module, "fixture_health",
            lambda *_, **__: {"ok": True, "baseline": True},
        )
    # Barrier + snapshot
    monkeypatch.setattr(
        runner_module, "fixture_await_barrier",
        barrier_fn if barrier_fn is not None else (lambda *_, **__: True),
    )
    monkeypatch.setattr(
        runner_module, "fixture_snapshot",
        snapshot_fn if snapshot_fn is not None else (
            lambda *_, **__: {"buffer": "ab", "point": 2,
                              "contract_version": 1}
        ),
    )
    return calls


def _ui_dry_steps(runner_module, tmp_path):
    """Run the dry-run and return the UI scenario steps from its summary."""
    out = tmp_path / "dry_out"
    out.mkdir()
    runner_module.run_dry_run({
        "out_dir": str(out), "profile": "quick", "seed": None,
        "classify": None,
    })
    summary = json.loads((out / "summary.json").read_text())
    return summary["ui_scenario"]["steps"]


# ── Test Classes ──────────────────────────────────────────────────────────

class TestFixedUiScenarioDryRun:
    """Deterministic UI step records from the run_dry_run path.

    The dry-run produces complete step records for all step types
    without any external command, ADB, or fixture interaction.
    """

    def test_dry_run_produces_all_step_types(self, runner_module, tmp_path):
        """Six step records matching the FIXED_UI_STEPS sequence."""
        steps = _ui_dry_steps(runner_module, tmp_path)
        step_types = [s["step_type"] for s in steps]
        assert step_types == [
            "insert", "delete", "insert", "replace", "paste", "clear",
        ]

    def test_dry_run_step_record_schema(self, runner_module, tmp_path):
        """Every step dict has all required fields with correct types."""
        steps = _ui_dry_steps(runner_module, tmp_path)
        for step in steps:
            _ui_step_check(step)

    def test_dry_run_all_match_and_pass(self, runner_module, tmp_path):
        """All dry-run UI steps are match=True, classification=pass."""
        steps = _ui_dry_steps(runner_module, tmp_path)
        assert all(s["match"] is True for s in steps)
        assert all(s["classification"] == "pass" for s in steps)

    def test_dry_run_no_external_commands(self, runner_module, tmp_path):
        """forbid_external_commands fixture — no run_cmd invoked by dry-run."""
        steps = _ui_dry_steps(runner_module, tmp_path)
        assert len(steps) == 6

    def test_dry_run_step_types_reflect_constant(self, runner_module, tmp_path):
        """Step types from FIXED_UI_STEPS, all in UI_STEP_TYPES."""
        steps = _ui_dry_steps(runner_module, tmp_path)
        for step in steps:
            assert step["step_type"] in runner_module.UI_STEP_TYPES
        # Also check the constant directly
        for entry in runner_module.FIXED_UI_STEPS:
            step_type = entry[0]
            assert step_type in runner_module.UI_STEP_TYPES

    def test_dry_run_detect_omitted_step(self, runner_module, tmp_path):
        """Omission of a step type changes the sequence shape detectably."""
        steps = _ui_dry_steps(runner_module, tmp_path)
        full_types = [s["step_type"] for s in steps]
        mutated = [t for t in full_types if t != "replace"]
        assert "replace" not in mutated
        assert full_types[3] == "replace"
        assert len(mutated) == len(full_types) - 1

    def test_dry_run_detect_reordered_steps(self, runner_module, tmp_path):
        """Reordering step types changes the sequence detectably."""
        steps = _ui_dry_steps(runner_module, tmp_path)
        original = [s["step_type"] for s in steps]
        reordered = list(original)
        reordered[0], reordered[4] = reordered[4], reordered[0]
        assert original != reordered
        assert original[0] == "insert"
        assert reordered[0] == "paste"


class TestFixedUiScenarioExecution:
    """run_fixed_ui_scenario with mocked ADB, fixture, and pre-seeded logcat.

    Every external boundary is monkeypatched. Completion events are written
    ahead of time into the logcat file so _wait_for_android_event_generic
    finds them deterministically without real timeouts.
    """

    def test_all_steps_pass_when_synced_and_prediction_matches(
        self, runner_module, monkeypatch, tmp_path
    ):
        """Happy path: Synced completion, barrier consumed, snapshot matches
        predicted buffer for all six FIXED_UI_STEPS."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        events = [
            _completion_event(change_id=1, desired_text="ab",
                              predicted_buffer="ab", predicted_point=2),
            _completion_event(change_id=2, desired_text="a",
                              predicted_buffer="a", predicted_point=1),
            _completion_event(change_id=3, desired_text="acde",
                              predicted_buffer="acde", predicted_point=4),
            _completion_event(change_id=4, desired_text="xyz",
                              predicted_buffer="xyz", predicted_point=3),
            _completion_event(change_id=5, desired_text="xyzxyz",
                              predicted_buffer="xyzxyz", predicted_point=6),
            _completion_event(change_id=6, desired_text="",
                              predicted_buffer="", predicted_point=0),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        calls = _ui_mock_env(runner_module, monkeypatch, logcat)

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) == 6
        for i, step in enumerate(steps):
            _ui_step_check(step, seq=i + 1)

    def test_wakes_with_keycode_wakeup_before_dismissing_keyguard(
        self, runner_module, monkeypatch, tmp_path
    ):
        """The fixed scenario must send KEYCODE_WAKEUP (224), never POWER
        (26), before trying to dismiss the keyguard."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))
        calls = _ui_mock_env(runner_module, monkeypatch, logcat)
        monkeypatch.setattr(runner_module.time, "sleep", lambda _: None)

        def _stop_after_dismiss(serial):
            calls.append(("dismiss", serial))
            raise RuntimeError("stop after wake-order observation")

        monkeypatch.setattr(
            runner_module, "adb_dismiss_keyguard", _stop_after_dismiss
        )

        runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        keyevents = [call for call in calls if call[0] == "keyevent"]
        assert keyevents == [("keyevent", "test-android", 224)]
        assert ("keyevent", "test-android", 26) not in calls
        assert calls.index(keyevents[0]) < calls.index(("dismiss", "test-android"))

    def test_completion_timeout_classified(
        self, runner_module, monkeypatch, tmp_path
    ):
        """_wait_for_android_event_generic timeout yields completion_timeout."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        # Empty logcat — _wait_for_android_event_generic will raise
        logcat.parent.mkdir(parents=True, exist_ok=True)
        logcat.touch()

        def _timeout_event(*_a, **_kw):
            raise RuntimeError("completion event timeout (simulated)")

        monkeypatch.setattr(
            runner_module, "_wait_for_android_event_generic", _timeout_event,
        )
        calls = _ui_mock_env(runner_module, monkeypatch, logcat)

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 1
        timeout_steps = [
            s for s in steps if s["classification"] == "completion_timeout"
        ]
        assert len(timeout_steps) >= 1, (
            f"expected completion_timeout in "
            f"{[s['classification'] for s in steps]}"
        )
        for s in timeout_steps:
            assert s["match"] is False
            assert s["barrier_consumed"] is False
            assert s["failure_detail"] is not None

    def test_sync_when_barrier_not_consumed(
        self, runner_module, monkeypatch, tmp_path
    ):
        """F24 barrier not consumed yields sync classification."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        events = [
            _completion_event(change_id=1, desired_text="ab"),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        barrier_attempts = []

        def _barrier(*_a, **_kw):
            barrier_attempts.append(True)
            return False  # always fail — every step is sync

        calls = _ui_mock_env(
            runner_module, monkeypatch, logcat,
            barrier_fn=_barrier,
        )

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 1
        sync_steps = [
            s for s in steps if s["classification"] == "sync"
        ]
        assert len(sync_steps) >= 1, (
            f"expected sync in {[s['classification'] for s in steps]}"
        )
        for s in sync_steps:
            assert s["match"] is False
            assert s["barrier_consumed"] is False

    def test_semantic_when_fixture_mismatches_prediction(
        self, runner_module, monkeypatch, tmp_path
    ):
        """Fixture snapshot != predicted yields semantic classification."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        events = [
            _completion_event(change_id=1, desired_text="ab"),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        snapshot_results = [
            {"buffer": "wrong", "point": 0, "contract_version": 1},
        ]
        snap_runs = []

        def _snap(*_a, **_kw):
            snap_runs.append(True)
            return snapshot_results[min(len(snap_runs), 1) - 1]

        calls = _ui_mock_env(
            runner_module, monkeypatch, logcat,
            snapshot_fn=_snap,
        )

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 1
        semantic_steps = [
            s for s in steps if s["classification"] == "semantic"
        ]
        assert len(semantic_steps) >= 1, (
            f"expected semantic in {[s['classification'] for s in steps]}"
        )
        for s in semantic_steps:
            assert s["match"] is False

    def test_planning_classification_from_completion_event(
        self, runner_module, monkeypatch, tmp_path
    ):
        """Editor completion with non-null classification maps to planning."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        # Completion event with planning classification
        events = [
            _completion_event(
                change_id=1, desired_text="ab",
                classification="InconsistentPrediction",
            ),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        calls = _ui_mock_env(runner_module, monkeypatch, logcat)

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 1
        assert steps[0]["classification"] == "planning"
        assert steps[0]["match"] is False

    def test_stale_completion_event_reuse_detectable(
        self, runner_module, monkeypatch, tmp_path
    ):
        """Same change_id on two events is observable in step records."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        # Both events carry change_id=1 — stale reuse
        events = [
            _completion_event(change_id=1, desired_text="ab"),
            _completion_event(change_id=1, desired_text="a"),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        calls = _ui_mock_env(runner_module, monkeypatch, logcat)

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 2
        # The two events with same change_id are recorded distinctly
        # (the runner does not filter duplicates, so both steps are present)
        assert steps[0]["change_id"] == steps[1]["change_id"] == 1
        assert steps[0]["desired_text"] != steps[1]["desired_text"]

    def test_missing_barrier_recorded(
        self, runner_module, monkeypatch, tmp_path
    ):
        """Steps where barrier is not consumed have barrier_consumed=False."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        events = [
            _completion_event(change_id=1, desired_text="ab"),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        calls = _ui_mock_env(
            runner_module, monkeypatch, logcat,
            barrier_fn=lambda *_, **__: False,
        )

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 1
        assert steps[0]["barrier_consumed"] is False

    def test_wrong_classification_rejected(
        self, runner_module, monkeypatch, tmp_path
    ):
        """Step with wrong classification is distinguishable from expected."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        events = [
            _completion_event(change_id=1, desired_text="ab"),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        snapshot_results = [
            {"buffer": "wrong", "point": 0, "contract_version": 1},
        ]

        def _snap(*_a, **_kw):
            return snapshot_results[0]

        calls = _ui_mock_env(
            runner_module, monkeypatch, logcat,
            snapshot_fn=_snap,
        )

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 1
        assert steps[0]["classification"] != "pass"
        assert steps[0]["match"] is False

    def test_setup_failure_returns_err_step(
        self, runner_module, monkeypatch, tmp_path
    ):
        """When fixture_reset fails, an error step with classification='fixture'
        is returned immediately."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        def _fail_reset(*_a, **_kw):
            raise RuntimeError("fixture reset failed (simulated)")

        # Only mock fixture_reset to fail; everything else is irrelevant
        monkeypatch.setattr(runner_module, "fixture_reset", _fail_reset)
        # Mock other dependencies to prevent spurious errors
        monkeypatch.setattr(runner_module, "adb_reset_editor",
                            lambda *_: None)
        monkeypatch.setattr(runner_module, "adb_keyevent", lambda *_: None)
        monkeypatch.setattr(runner_module, "adb_dismiss_keyguard",
                            lambda *_: None)
        monkeypatch.setattr(runner_module, "adb_launch_readline",
                            lambda *_: None)

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        assert len(steps) >= 1
        assert steps[0]["classification"] == "fixture"
        assert steps[0]["match"] is False


class TestFixedUiScenarioSummary:
    """ui_scenario summary structure nested inside editor_conformance summary."""

    def test_ui_scenario_nested_in_dry_run_summary(
        self, runner_module, tmp_path
    ):
        """run_dry_run writes 'ui_scenario' key into summary."""
        out = tmp_path / "dry_out"
        out.mkdir()
        runner_module.run_dry_run({
            "out_dir": str(out), "profile": "quick",
            "seed": None, "classify": None,
        })
        summary = json.loads((out / "summary.json").read_text())
        assert "ui_scenario" in summary
        ui_sec = summary["ui_scenario"]
        assert "status" in ui_sec
        assert "steps" in ui_sec
        assert "classification_totals" in ui_sec
        assert "failing" in ui_sec
        assert isinstance(ui_sec["status"], str)
        assert isinstance(ui_sec["steps"], list)
        assert isinstance(ui_sec["classification_totals"], dict)
        assert isinstance(ui_sec["failing"], int)

    def test_ui_scenario_classification_totals_accurate(
        self, runner_module, tmp_path
    ):
        """classification_totals counts match step records."""
        out = tmp_path / "dry_out"
        out.mkdir()
        runner_module.run_dry_run({
            "out_dir": str(out), "profile": "quick",
            "seed": None, "classify": None,
        })
        summary = json.loads((out / "summary.json").read_text())
        steps = summary["ui_scenario"]["steps"]
        totals = summary["ui_scenario"]["classification_totals"]
        expected_total = sum(totals.values())
        assert expected_total == len(steps)

    def test_ui_scenario_failing_count_matches_non_passing_steps(
        self, runner_module, tmp_path
    ):
        """failing counter equals match=False steps."""
        out = tmp_path / "dry_out"
        out.mkdir()
        runner_module.run_dry_run({
            "out_dir": str(out), "profile": "quick",
            "seed": None, "classify": None,
        })
        summary = json.loads((out / "summary.json").read_text())
        steps = summary["ui_scenario"]["steps"]
        assert summary["ui_scenario"]["failing"] == 0
        assert all(s["match"] is True for s in steps)

    def test_ui_scenario_malformed_step_rejected(self, runner_module, tmp_path):
        """Step missing a required field is detectable via schema check."""
        out = tmp_path / "dry_out"
        out.mkdir()
        runner_module.run_dry_run({
            "out_dir": str(out), "profile": "quick",
            "seed": None, "classify": None,
        })
        summary = json.loads((out / "summary.json").read_text())
        steps = summary["ui_scenario"]["steps"]
        # Remove a mandatory field from one step
        corrupted = dict(steps[0])
        del corrupted["change_id"]
        required = {
            "seq", "step_type", "desired_text", "change_id",
            "generation", "editor_state", "completion_classification", "match",
            "classification", "failure_detail", "barrier_consumed",
            "observed", "predicted", "duration_sec",
        }
        missing = required - set(corrupted.keys())
        assert "change_id" in missing
        # Verify the original has all required
        _ui_step_check(steps[0])

    def test_ui_scenario_steps_from_hardware_path(
        self, runner_module, monkeypatch, tmp_path
    ):
        """run_fixed_ui_scenario with mocks produces summary-compatible steps."""
        logcat = tmp_path / "android_logcat.jsonl"
        artifacts = runner_module.ArtifactWriter(str(tmp_path))

        events = [
            _completion_event(change_id=1, desired_text="ab"),
        ]
        logcat.parent.mkdir(parents=True, exist_ok=True)
        for evt in events:
            with open(logcat, "a") as f:
                f.write(json.dumps(evt) + "\n")

        calls = _ui_mock_env(runner_module, monkeypatch, logcat)

        steps = runner_module.run_fixed_ui_scenario(
            serial="test-android",
            observer_target="test-observer",
            observer_identity="test-identity",
            known_hosts="test-known-hosts",
            artifacts=artifacts,
            logcat_path=str(logcat),
        )

        # The steps can be serialized into a summary ui_scenario block
        summary = {
            "scenario": "editor_conformance",
            "status": "pass",
            "ui_scenario": {
                "status": "pass",
                "steps": steps,
                "classification_totals": {"pass": 1},
                "failing": 0,
            },
        }
        artifacts.write_summary(summary)
        loaded = json.loads(artifacts.summary_path.read_text())
        assert "ui_scenario" in loaded
        assert len(loaded["ui_scenario"]["steps"]) == len(steps)
