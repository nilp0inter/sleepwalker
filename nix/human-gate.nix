# sleepwalker-human-gate: call noti with exact physical instructions and
# wait for an observable condition before continuing.
#
# This is the explicit human commissioning/recovery gate. It rings the
# AFK human through the harness host `noti` command, prints the exact
# physical instruction, then polls an observable condition (a command
# whose exit code indicates the condition is met) until it succeeds or
# the timeout elapses.
#
# Usage:
#   sleepwalker-human-gate <message> <poll-command> [timeout_sec] [poll_interval_sec]
{ lib, writeShellScriptBin, noti }:
let
  notiBin = "${noti}/bin/noti";
in
writeShellScriptBin "sleepwalker-human-gate" ''
  set -uo pipefail
  MSG="''${1:?usage: sleepwalker-human-gate <message> <poll-command> [timeout_sec] [poll_interval_sec]}"
  POLL="''${2:?usage: sleepwalker-human-gate <message> <poll-command> ...}"
  TIMEOUT="''${3:-300}"
  INTERVAL="''${4:-5}"
  # Ring the human with the exact physical instruction.
  ${notiBin} "sleepwalker: $MSG" >/dev/null 2>&1 || true
  printf '{"ok":false,"event":"human_gate","message":"%s","status":"waiting"}\n' "$MSG" >&2
  ELAPSED=0
  while [ "$ELAPSED" -lt "$TIMEOUT" ]; do
    if sh -c "$POLL" >/dev/null 2>&1; then
      printf '{"ok":true,"event":"human_gate","message":"%s","elapsed":%s}\n' "$MSG" "$ELAPSED"
      exit 0
    fi
    sleep "$INTERVAL"
    ELAPSED=$((ELAPSED + INTERVAL))
  done
  printf '{"ok":false,"event":"human_gate","message":"%s","reason":"timeout","elapsed":%s}\n' "$MSG" "$ELAPSED" >&2
  ${notiBin} "sleepwalker: GATE TIMEOUT - $MSG" >/dev/null 2>&1 || true
  exit 3
''