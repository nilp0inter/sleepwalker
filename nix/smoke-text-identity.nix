# sleepwalker-smoke-text-identity: End-to-end text identity hypothesis test.
#
# Hypothesis: The text planning and HID generation pipeline produces
# rendered output that matches the original input when using the
# Linux console US keymap backend.
#
# This test:
# 1. Prepares the observer host (sets console keymap to us)
# 2. Starts the text sink on the observer console
# 3. Connects Android to ESP32-S3
# 4. Arms the firmware safety state
# 5. Types a test string using encoded ADB text input
# 6. Observes HID events (without exclusive grab)
# 7. Reads captured console text from text sink
# 8. Compares captured text with original input
# 9. Stops the text sink and emits results
#
# Usage:
#   sleepwalker-smoke-text-identity <bench.toml>
{ lib, writeShellScriptBin, coreutils, jq, sleepwalker-bench-validate
, sleepwalker-fw-uart, sleepwalker-adb-logcat, sleepwalker-hid-observe
, sleepwalker-adb-connect, sleepwalker-adb-arm
, sleepwalker-adb-type-text-encoded, sleepwalker-adb-release-all
, sleepwalker-adb-kill, sleepwalker-esp-reset
, sleepwalker-observer-prepare, sleepwalker-text-sink-start
, sleepwalker-text-sink-read, sleepwalker-text-sink-ctl, python3 }:
let
  primPath = lib.makeBinPath [
    sleepwalker-bench-validate sleepwalker-fw-uart sleepwalker-adb-logcat
    sleepwalker-hid-observe sleepwalker-adb-connect sleepwalker-adb-arm
    sleepwalker-adb-type-text-encoded sleepwalker-adb-release-all sleepwalker-adb-kill
    sleepwalker-esp-reset sleepwalker-observer-prepare jq coreutils
    sleepwalker-text-sink-start sleepwalker-text-sink-read sleepwalker-text-sink-ctl
  ];
in
writeShellScriptBin "sleepwalker-smoke-text-identity" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  BENCH="''${1:?usage: sleepwalker-smoke-text-identity <bench.toml>}"
  if [ ! -f "$BENCH" ]; then
    printf '{"ok":false,"reason":"bench config not found","bench":"%s"}\n' "$BENCH" >&2
    exit 2
  fi
  if ! sleepwalker-bench-validate "$BENCH" >/dev/null 2>&1; then
    printf '{"ok":false,"reason":"bench config invalid","bench":"%s"}\n' "$BENCH" >&2
    exit 2
  fi

  # Load bench configuration using Python
  eval "$(python3 - "$BENCH" <<'PYEOF'
import sys, json
try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib
with open(sys.argv[1], "rb") as f:
    cfg = tomllib.load(f)
def q(name, val):
    print(f'{name}={json.dumps(str(val))}')
q("OBSERVER_TARGET", cfg["hid_observer"]["ssh_target"])
q("ANDROID_SERIAL", cfg["android"]["adb_serial"])
q("OBSERVER_IDENTITY", cfg["hid_observer"].get("identity_file", ""))
q("OBSERVER_KNOWN_HOSTS", cfg["hid_observer"].get("known_hosts", ""))
PYEOF
  )"

  # Test string: shell-sensitive characters to verify encoding
  TEST_STRING='The quick brown fox jumps over the lazy dog! @#$%^&*()_+-=[]{}|;:,.<>?/~`'

  # Encode test string as base64url
  ENCODED_TEXT=$(printf '%s' "$TEST_STRING" | base64 -w0 | tr '+/' '-_')

  printf '{"phase":"validate","bench":"%s","observer":"%s","android":"%s"}\n' \
    "$BENCH" "$OBSERVER_TARGET" "$ANDROID_SERIAL"

  # Create output directory
  OUT_DIR="artifacts/run_text_identity_$(date +%s)"
  mkdir -p "$OUT_DIR"

  # Create temporary known_hosts file for this run
  KNOWN_HOSTS_TMP="$OUT_DIR/known_hosts"
  cp /tmp/sleepwalker_known_hosts "$KNOWN_HOSTS_TMP"

  # Track start time for duration calculation
  START_TIME=$(date +%s)

  # Cleanup handler
  cleanup() {
    local exit_code=$?
    printf '{"phase":"cleanup","exit_code":%s}\n' "$exit_code"
    # Stop text sink, release HID, disarm, kill, reset ESP
    sleepwalker-text-sink-ctl "$OBSERVER_TARGET" stop "$OBSERVER_IDENTITY" "$KNOWN_HOSTS_TMP" 2>/dev/null || true
    sleepwalker-adb-release-all "$ANDROID_SERIAL" 0 2>/dev/null || true
    sleepwalker-adb-kill "$ANDROID_SERIAL" 0 2>/dev/null || true
    sleepwalker-esp-reset "$BENCH" 2>/dev/null || true
    exit $exit_code
  }
  trap cleanup EXIT INT TERM

  # 1. Prepare observer (set console keymap to us)
  printf '{"phase":"prepare_observer"}\n'
  sleepwalker-observer-prepare "$OBSERVER_TARGET" "linux:us" "$OBSERVER_IDENTITY" "$KNOWN_HOSTS_TMP"

  # 2. Start text sink
  printf '{"phase":"start_text_sink"}\n'
  TEXT_SINK_ID="text-sink-$$"
  sleepwalker-text-sink-start "$OBSERVER_TARGET" "/tmp/sleepwalker_textSink_$TEXT_SINK_ID.txt" "$OBSERVER_IDENTITY" "$KNOWN_HOSTS_TMP"

  # Wait for text sink to initialize
  sleep 2

  # 3. Start observer in non-exclusive grab mode (hypothesis tests observe, don't block)
  printf '{"phase":"start_observer"}\n'
  HID_LOG="$OUT_DIR/hid.jsonl"
  sleepwalker-hid-observe "$OBSERVER_TARGET" "$HID_LOG" 30 "$OBSERVER_IDENTITY" "$KNOWN_HOSTS_TMP" &
  HID_PID=$!

  # Wait for observer to initialize
  sleep 1

  # 4. Connect Android to ESP32-S3
  printf '{"phase":"connect"}\n'
  sleepwalker-adb-connect "$ANDROID_SERIAL" 0

  # Wait for BLE connection
  sleep 3

  # 5. Arm firmware
  printf '{"phase":"arm"}\n'
  sleepwalker-adb-arm "$ANDROID_SERIAL" 0

  # Wait for arm to complete
  sleep 1

  # 6. Type test string using encoded input
  printf '{"phase":"type_text","length":%s}\n' "$(printf '%s' "$TEST_STRING" | wc -c)"
  ADB_RESPONSE=$(sleepwalker-adb-type-text-encoded "$ANDROID_SERIAL" "$ENCODED_TEXT" 1)
  echo "$ADB_RESPONSE"
  # Wait for text to be transmitted and rendered
  sleep 10

  # 7. Stop text sink to flush buffer to artifact file
  printf '{"phase":"stop_text_sink"}\n'
  sleepwalker-text-sink-ctl "$OBSERVER_TARGET" stop "$OBSERVER_IDENTITY" "$KNOWN_HOSTS_TMP"

  # 8. Read captured console text
  printf '{"phase":"read_captured_text"}\n'
  TEXT_SINK_LOG="$OUT_DIR/text_sink.txt"
  sleepwalker-text-sink-read "$OBSERVER_TARGET" "/tmp/sleepwalker_textSink_$TEXT_SINK_ID.txt" "$OBSERVER_IDENTITY" "$KNOWN_HOSTS_TMP" > "$TEXT_SINK_LOG"
  # Wait for observer to finish
  wait $HID_PID

  # 9. Compare captured text with original
  printf '{"phase":"compare"}\n'
  END_TIME=$(date +%s)
  DURATION=$((END_TIME - START_TIME))

  if [ -f "$TEXT_SINK_LOG" ]; then
    CAPTURED_TEXT=$(cat "$TEXT_SINK_LOG")
    CAPTURED_LENGTH=$(printf '%s' "$CAPTURED_TEXT" | wc -c)
    EXPECTED_LENGTH=$(printf '%s' "$TEST_STRING" | wc -c)

    if [ "$CAPTURED_TEXT" = "$TEST_STRING" ]; then
      printf '{"phase":"result","status":"pass","captured_length":%s,"expected_length":%s,"duration":%s}\n' \
        "$CAPTURED_LENGTH" "$EXPECTED_LENGTH" "$DURATION"

      # Write detailed results
      printf 'PASS: Captured text matches input\n' > "$OUT_DIR/result.txt"
      printf 'Duration: %s seconds\n' "$DURATION" >> "$OUT_DIR/result.txt"
      printf 'Input (%s bytes): %s\n' "$EXPECTED_LENGTH" "$TEST_STRING" >> "$OUT_DIR/result.txt"
      printf 'Encoded input (%s bytes): %s\n' "$(printf '%s' "$ENCODED_TEXT" | wc -c)" "$ENCODED_TEXT" >> "$OUT_DIR/result.txt"
      printf 'Captured (%s bytes): %s\n' "$CAPTURED_LENGTH" "$CAPTURED_TEXT" >> "$OUT_DIR/result.txt"
      printf 'ADB response: %s\n' "$ADB_RESPONSE" >> "$OUT_DIR/result.txt"

      # Write summary.json
      cat > "$OUT_DIR/summary.json" <<EOF
{
  "status": "pass",
  "test": "text_identity",
  "bench_config": "$BENCH",
  "generated_input": "$TEST_STRING",
  "encoded_input": "$ENCODED_TEXT",
  "encoded_length": $(printf '%s' "$ENCODED_TEXT" | wc -c),
  "android_metadata": {
    "serial": "$ANDROID_SERIAL",
    "adb_response": $(printf '%s' "$ADB_RESPONSE" | jq -R -s -c 2>/dev/null || echo '"parse_error"')
  },
  "target_output": "$CAPTURED_TEXT",
  "input_length": $EXPECTED_LENGTH,
  "captured_length": $CAPTURED_LENGTH,
  "failure_classification": null,
  "replay_data": {
    "test_string": "$TEST_STRING",
    "encoded_text": "$ENCODED_TEXT",
    "observer_target": "$OBSERVER_TARGET",
    "android_serial": "$ANDROID_SERIAL"
  },
  "log_paths": {
    "hid_log": "$HID_LOG",
    "text_sink_log": "$TEXT_SINK_LOG",
    "result_txt": "$OUT_DIR/result.txt",
    "out_dir": "$OUT_DIR"
  },
  "duration": $DURATION,
  "timestamp": $(date +%s)
}
EOF
      exit 0
    else
      printf '{"phase":"result","status":"fail","reason":"text_mismatch","captured_length":%s,"expected_length":%s,"duration":%s}\n' \
        "$CAPTURED_LENGTH" "$EXPECTED_LENGTH" "$DURATION"

      # Write detailed results
      printf 'FAIL: Captured text does not match input\n' > "$OUT_DIR/result.txt"
      printf 'Duration: %s seconds\n' "$DURATION" >> "$OUT_DIR/result.txt"
      printf 'Input (%s bytes): %s\n' "$EXPECTED_LENGTH" "$TEST_STRING" >> "$OUT_DIR/result.txt"
      printf 'Encoded input (%s bytes): %s\n' "$(printf '%s' "$ENCODED_TEXT" | wc -c)" "$ENCODED_TEXT" >> "$OUT_DIR/result.txt"
      printf 'Captured (%s bytes): %s\n' "$CAPTURED_LENGTH" "$CAPTURED_TEXT" >> "$OUT_DIR/result.txt"
      printf 'ADB response: %s\n' "$ADB_RESPONSE" >> "$OUT_DIR/result.txt"

      # Write summary.json
      cat > "$OUT_DIR/summary.json" <<EOF
{
  "status": "fail",
  "test": "text_identity",
  "failure_classification": {
    "category": "text_mismatch",
    "severity": "high",
    "details": "captured_text_does_not_match_input"
  },
  "bench_config": "$BENCH",
  "generated_input": "$TEST_STRING",
  "encoded_input": "$ENCODED_TEXT",
  "encoded_length": $(printf '%s' "$ENCODED_TEXT" | wc -c),
  "android_metadata": {
    "serial": "$ANDROID_SERIAL",
    "adb_response": $(printf '%s' "$ADB_RESPONSE" | jq -R -s -c 2>/dev/null || echo '"parse_error"')
  },
  "target_output": "$CAPTURED_TEXT",
  "input_length": $EXPECTED_LENGTH,
  "captured_length": $CAPTURED_LENGTH,
  "replay_data": {
    "test_string": "$TEST_STRING",
    "encoded_text": "$ENCODED_TEXT",
    "observer_target": "$OBSERVER_TARGET",
    "android_serial": "$ANDROID_SERIAL"
  },
  "log_paths": {
    "hid_log": "$HID_LOG",
    "text_sink_log": "$TEXT_SINK_LOG",
    "result_txt": "$OUT_DIR/result.txt",
    "out_dir": "$OUT_DIR"
  },
  "duration": $DURATION,
  "timestamp": $(date +%s)
}
EOF
      exit 1
    fi
  else
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    printf '{"phase":"result","status":"fail","reason":"text_sink_log_not_found","path":"%s","duration":%s}\n' "$TEXT_SINK_LOG" "$DURATION"

    # Write detailed results
    printf 'FAIL: Text sink log not found\n' > "$OUT_DIR/result.txt"
    printf 'Duration: %s seconds\n' "$DURATION" >> "$OUT_DIR/result.txt"
    printf 'Input (%s bytes): %s\n' "$EXPECTED_LENGTH" "$TEST_STRING" >> "$OUT_DIR/result.txt"
    printf 'Encoded input (%s bytes): %s\n' "$(printf '%s' "$ENCODED_TEXT" | wc -c)" "$ENCODED_TEXT" >> "$OUT_DIR/result.txt"
    printf 'ADB response: %s\n' "$ADB_RESPONSE" >> "$OUT_DIR/result.txt"

    # Write summary.json
    cat > "$OUT_DIR/summary.json" <<EOF
{
  "status": "fail",
  "test": "text_identity",
  "failure_classification": {
    "category": "observer_infrastructure",
    "severity": "critical",
    "details": "text_sink_log_not_found"
  },
  "bench_config": "$BENCH",
  "generated_input": "$TEST_STRING",
  "encoded_input": "$ENCODED_TEXT",
  "encoded_length": $(printf '%s' "$ENCODED_TEXT" | wc -c),
  "android_metadata": {
    "serial": "$ANDROID_SERIAL",
    "adb_response": $(printf '%s' "$ADB_RESPONSE" | jq -R -s -c 2>/dev/null || echo '"parse_error"')
  },
  "target_output": null,
  "input_length": $EXPECTED_LENGTH,
  "captured_length": 0,
  "replay_data": {
    "test_string": "$TEST_STRING",
    "encoded_text": "$ENCODED_TEXT",
    "observer_target": "$OBSERVER_TARGET",
    "android_serial": "$ANDROID_SERIAL"
  },
  "log_paths": {
    "hid_log": "$HID_LOG",
    "text_sink_log": "$TEXT_SINK_LOG",
    "result_txt": "$OUT_DIR/result.txt",
    "out_dir": "$OUT_DIR"
  },
  "duration": $DURATION,
  "timestamp": $(date +%s)
}
EOF

    exit 1
  fi
''
