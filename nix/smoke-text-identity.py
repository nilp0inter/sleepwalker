#!/usr/bin/env python3
import sys
import os
import time
import base64
import json
import subprocess
import string
from hypothesis import given, settings, Phase, HealthCheck
from hypothesis.strategies import text

# Load configuration and arguments
if len(sys.argv) < 4:
    print("Usage: smoke-text-identity.py <bench_toml> <out_dir> <known_hosts_tmp> [--profile quick|deep]", file=sys.stderr)
    sys.exit(2)

BENCH_TOML = sys.argv[1]
OUT_DIR = sys.argv[2]
KNOWN_HOSTS_TMP = sys.argv[3]

PROFILE = "quick"
if "--profile" in sys.argv:
    idx = sys.argv.index("--profile")
    if idx + 1 < len(sys.argv):
        PROFILE = sys.argv[idx + 1]

# Load bench config via Python toml
try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib

with open(BENCH_TOML, "rb") as f:
    cfg = tomllib.load(f)

OBSERVER_TARGET = cfg["hid_observer"]["ssh_target"]
ANDROID_SERIAL = cfg["android"]["adb_serial"]
OBSERVER_IDENTITY = cfg["hid_observer"].get("identity_file", "")

# Print validation info
print(json.dumps({
    "phase": "validate",
    "bench": BENCH_TOML,
    "observer": OBSERVER_TARGET,
    "android": ANDROID_SERIAL,
    "profile": PROFILE
}))

# Define alphabet based on US QWERTY seed database (excluding control characters \n and \u001b)
# This includes letters, digits, punctuation, and spaces
US_PRINTABLE_ALPHABET = (
    string.ascii_letters + string.digits +
    "!@#$%^&*()-_=+[{]}|;:'\",<.>/?~` "
)

# Hypothesis settings
max_len = 20 if PROFILE == "quick" else 50
max_examples = 10 if PROFILE == "quick" else 100

settings.register_profile("quick", max_examples=max_examples, deadline=None, suppress_health_check=[HealthCheck.too_slow])
settings.register_profile("deep", max_examples=max_examples, deadline=None, suppress_health_check=[HealthCheck.too_slow])
settings.load_profile(PROFILE)

# Shared states
TEXT_SINK_ID = f"text-sink-{os.getpid()}"
REMOTE_SINK_FILE = f"/tmp/sleepwalker_textSink_{TEXT_SINK_ID}.txt"
EXAMPLES_RUN = 0
TEST_ERRORS = []
LAST_ADB_RESPONSE = ""
DURATION = 0
START_TIME = time.time()

# Helper to run shell commands
def run_cmd(args, capture_output=True, check=True):
    # Ensure known hosts and identity are added to commands that require them
    cmd_args = list(args)
    res = subprocess.run(cmd_args, capture_output=capture_output, text=True)
    if check and res.returncode != 0:
        raise subprocess.CalledProcessError(res.returncode, cmd_args, res.stdout, res.stderr)
    return res

# Start setup
setup_ok = False
HID_PROC = None

try:
    # 1. Prepare observer (console US keymap)
    print(json.dumps({"phase": "prepare_observer"}))
    run_cmd(["sleepwalker-observer-prepare", OBSERVER_TARGET, "linux:us", OBSERVER_IDENTITY, KNOWN_HOSTS_TMP])

    # 2. Start text sink on active VT
    print(json.dumps({"phase": "start_text_sink"}))
    run_cmd(["sleepwalker-text-sink-start", OBSERVER_TARGET, REMOTE_SINK_FILE, OBSERVER_IDENTITY, KNOWN_HOSTS_TMP])
    time.sleep(2)

    # 3. Start observer in non-exclusive grab mode (non-grabbing diagnostic channel)
    print(json.dumps({"phase": "start_observer"}))
    HID_LOG = os.path.join(OUT_DIR, "hid.jsonl")
    # Start in background using Popen
    HID_PROC = subprocess.Popen([
        "sleepwalker-hid-observe", OBSERVER_TARGET, HID_LOG, "300", OBSERVER_IDENTITY, KNOWN_HOSTS_TMP
    ])
    time.sleep(1)

    # 4. Connect Android to ESP32-S3
    print(json.dumps({"phase": "connect"}))
    run_cmd(["sleepwalker-adb-connect", ANDROID_SERIAL, "0"])
    time.sleep(3)

    # 5. Arm safety state
    print(json.dumps({"phase": "arm"}))
    run_cmd(["sleepwalker-adb-arm", ANDROID_SERIAL, "0"])
    time.sleep(1)

    setup_ok = True

except Exception as e:
    print(json.dumps({"phase": "setup_failed", "error": str(e)}), file=sys.stderr)
    setup_ok = False

if setup_ok:
    # Define property-based test
    @given(text(alphabet=US_PRINTABLE_ALPHABET, min_size=1, max_size=max_len))
    def test_text_identity(test_string):
        global EXAMPLES_RUN, LAST_ADB_RESPONSE
        EXAMPLES_RUN += 1

        print(json.dumps({
            "phase": "hypothesis_example",
            "example_index": EXAMPLES_RUN,
            "length": len(test_string),
            "preview": test_string[:30]
        }))

        # A. Reset remote text sink capture buffer
        run_cmd(["sleepwalker-text-sink-ctl", OBSERVER_TARGET, "reset", OBSERVER_IDENTITY, KNOWN_HOSTS_TMP])

        # B. Encode test string
        encoded_bytes = base64.urlsafe_b64encode(test_string.encode('utf-8'))
        encoded_str = encoded_bytes.decode('utf-8')

        # C. Type text using encoded input
        res = run_cmd(["sleepwalker-adb-type-text-encoded", ANDROID_SERIAL, encoded_str, str(EXAMPLES_RUN)])
        LAST_ADB_RESPONSE = res.stdout.strip()

        # D. Wait for transmission and rendering (about 1.5 seconds)
        # Note: we use character-length based scaling to minimize test duration
        time.sleep(1.0 + (len(test_string) * 0.05))

        # E. Stop the sink temporarily or send SIGUSR2 to flush?
        # Wait, instead of stopping it completely (which terminates the process),
        # we can just send SIGUSR2 to flush and stop the sink, but then we would need
        # to restart the sink for the next example!
        # Wait! Does SIGUSR2 stop the sink? Yes:
        # "SIGUSR2: stop and flush (write captured bytes to artifact file, then exit)"
        # So we MUST restart the sink for each example if we flush it this way!
        # Wait! Is that what the design said?
        # "Each generated example isolated: HIL resets the observer text sink before sending the example and compares only the output captured for that example"
        # Wait, if we send SIGUSR2, the sink writes the file and EXITS.
        # So we must restart it for each example! This is fast enough (takes ~0.5s).
        # Let's do that:
        # 1. Stop the sink (sends SIGUSR2, writes file, exits).
        run_cmd(["sleepwalker-text-sink-ctl", OBSERVER_TARGET, "stop", OBSERVER_IDENTITY, KNOWN_HOSTS_TMP])

        # 2. Read the captured text.
        res_read = run_cmd(["sleepwalker-text-sink-read", OBSERVER_TARGET, REMOTE_SINK_FILE, OBSERVER_IDENTITY, KNOWN_HOSTS_TMP])
        captured_text = res_read.stdout

        # 3. Start the sink again for the next example.
        run_cmd(["sleepwalker-text-sink-start", OBSERVER_TARGET, REMOTE_SINK_FILE, OBSERVER_IDENTITY, KNOWN_HOSTS_TMP])
        time.sleep(1)

        # F. Assert equality
        if captured_text != test_string:
            # Let's raise an AssertionError with details so Hypothesis can shrink it
            err_msg = f"Text mismatch! Expected: {repr(test_string)}, Got: {repr(captured_text)}"
            print(json.dumps({"phase": "example_failed", "error": err_msg}))
            raise AssertionError(err_msg)

    # Run the Hypothesis test
    print(json.dumps({"phase": "running_properties"}))
    try:
        test_text_identity()
        test_passed = True
        failure_reason = None
    except Exception as e:
        test_passed = False
        failure_reason = str(e)
        TEST_ERRORS.append(failure_reason)

# Perform cleanup
print(json.dumps({"phase": "cleanup"}))
if HID_PROC:
    HID_PROC.terminate()
    HID_PROC.wait()

# Stop text sink (final stop)
run_cmd(["sleepwalker-text-sink-ctl", OBSERVER_TARGET, "stop", OBSERVER_IDENTITY, KNOWN_HOSTS_TMP], check=False)
# Release all, kill safety, reset ESP
run_cmd(["sleepwalker-adb-release-all", ANDROID_SERIAL, "0"], check=False)
run_cmd(["sleepwalker-adb-kill", ANDROID_SERIAL, "0"], check=False)
run_cmd(["sleepwalker-esp-reset", BENCH_TOML], check=False)

# Collect evidence
DURATION = int(time.time() - START_TIME)

# Read final captured log if exists (just for reference)
TEXT_SINK_LOG = os.path.join(OUT_DIR, "text_sink.txt")
try:
    res_read = run_cmd(["sleepwalker-text-sink-read", OBSERVER_TARGET, REMOTE_SINK_FILE, OBSERVER_IDENTITY, KNOWN_HOSTS_TMP])
    with open(TEXT_SINK_LOG, "w") as f:
        f.write(res_read.stdout)
    captured_text = res_read.stdout
except Exception:
    captured_text = ""

# Write results
result_txt_path = os.path.join(OUT_DIR, "result.txt")
summary_json_path = os.path.join(OUT_DIR, "summary.json")

status_str = "pass" if (setup_ok and test_passed) else "fail"

with open(result_txt_path, "w") as f:
    if status_str == "pass":
        f.write("PASS: Hypothesis text identity tests completed successfully\n")
    else:
        f.write(f"FAIL: Hypothesis text identity tests failed: {failure_reason}\n")
    f.write(f"Duration: {DURATION} seconds\n")
    f.write(f"Examples run: {EXAMPLES_RUN}\n")
    if failure_reason:
        f.write(f"Error: {failure_reason}\n")

# Parse adb response JSON safely
try:
    adb_resp_parsed = json.loads(LAST_ADB_RESPONSE)
except Exception:
    adb_resp_parsed = LAST_ADB_RESPONSE

summary = {
    "status": status_str,
    "test": "text_identity",
    "bench_config": BENCH_TOML,
    "profile": PROFILE,
    "hypothesis_settings": {
        "max_examples": max_examples,
        "max_len": max_len,
        "alphabet_size": len(US_PRINTABLE_ALPHABET)
    },
    "examples_run": EXAMPLES_RUN,
    "android_metadata": {
        "serial": ANDROID_SERIAL,
        "adb_response": adb_resp_parsed
    },
    "failure_classification": {
        "category": "text_mismatch" if failure_reason and "AssertionError" in failure_reason else ("setup" if not setup_ok else "infrastructure"),
        "severity": "high" if failure_reason and "AssertionError" in failure_reason else "critical",
        "details": failure_reason
    } if failure_reason else None,
    "log_paths": {
        "hid_log": os.path.join(OUT_DIR, "hid.jsonl"),
        "text_sink_log": TEXT_SINK_LOG,
        "result_txt": result_txt_path,
        "out_dir": OUT_DIR
    },
    "duration": DURATION,
    "timestamp": int(time.time())
}

with open(summary_json_path, "w") as f:
    json.dump(summary, f, indent=2)

print(json.dumps({
    "phase": "result",
    "status": status_str,
    "examples_run": EXAMPLES_RUN,
    "duration": DURATION,
    "summary_json": summary_json_path
}))

if status_str == "pass":
    sys.exit(0)
else:
    sys.exit(1)
