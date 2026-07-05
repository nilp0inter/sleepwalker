# sleepwalker-hid-observe: start the remote HID observer helper over SSH
# and collect JSONL HID events into an artifact file.
#
# Side-effectful: SSHes to the sacrificial observer host and runs
# sleepwalker-hid-observer with --timeout (and optionally --grab),
# collecting JSONL output. Uses a dedicated known_hosts file and
# identity to avoid polluting user SSH state.
#
# Device discovery is composite-capable: the caller may pass multiple
# device paths (keyboard and mouse symlinks, or a single combined node)
# as positional arguments after the standard options. The helper probes
# evdev capabilities and classifies each node as keyboard and/or mouse.
#
# Usage:
#   sleepwalker-hid-observe <ssh_target> <out.jsonl> [timeout_sec] \
#     [identity] [known_hosts] [device1] [device2] ... [--grab]
#
# The device list defaults to /dev/input/by-id/sleepwalker-hid-keyboard
# and /dev/input/by-id/sleepwalker-hid-mouse so a composite smoke
# observes both nodes by default. Pass --grab to acquire an exclusive
# grab on every matched node during the observation window.
{ lib, writeShellScriptBin, openssh }:
let
  ssh = "${openssh}/bin/ssh";
in
writeShellScriptBin "sleepwalker-hid-observe" ''
  set -euo pipefail
  TARGET="''${1:?usage: sleepwalker-hid-observe <ssh_target> <out.jsonl> [timeout_sec] [identity] [known_hosts] [device...] [--grab]}"
  OUT="''${2:?usage: sleepwalker-hid-observe <ssh_target> <out.jsonl> [timeout_sec] [identity] [known_hosts] [device...] [--grab]}"
  TIMEOUT="''${3:-30}"
  IDENTITY="''${4:-}"
  KNOWN_HOSTS="''${5:-}"
  # Shift only the fixed positional args (max 5) that were actually
  # provided so a call with fewer than 5 args does not reinterpret
  # target/out as device paths.
  SHIFT_N=$(( $# < 5 ? $# : 5 ))
  shift "$SHIFT_N"
  # Remaining positional args: device paths and an optional --grab flag.
  GRAB_FLAG=""
  DEVICES=()
  if [ "$#" -gt 0 ]; then
    for arg in "$@"; do
      if [ "$arg" = "--grab" ]; then
        GRAB_FLAG="--grab"
      else
        DEVICES+=("$arg")
      fi
    done
  fi
  if [ "''${#DEVICES[@]}" -eq 0 ]; then
    DEVICES+=(/dev/input/by-id/sleepwalker-hid-keyboard
              /dev/input/by-id/sleepwalker-hid-mouse)
  fi
  # Build a quoted remote command line. Each device path is shell-quoted
  # for the remote sh so paths with spaces (unlikely but defensive) are
  # safe. The remote helper dedupes by realpath, so passing both the
  # keyboard and mouse symlinks of a combined node is harmless.
  REMOTE_ARGS=()
  for d in "''${DEVICES[@]}"; do
    REMOTE_ARGS+=("$(printf '%q' "$d")")
  done
  if [ -n "$GRAB_FLAG" ]; then
    REMOTE_ARGS+=("$GRAB_FLAG")
  fi
  REMOTE_ARGS+=("--timeout" "$TIMEOUT")
  # Preflight: wait up to 10s for all device paths to appear on the
  # remote host before invoking the helper. This avoids a race where
  # the observer starts before USB HID enumeration completes.
  # Build the device list string (already shell-quoted via printf '%q')
  # for the remote for-loop. Use a remote shell script that checks all
  # paths in each iteration, exits 1 if they never appear.
  REMOTE_DEVLIST="''${REMOTE_ARGS[*]}"
  # Strip --grab/--timeout from the preflight device list: only check
  # the device path args (those not starting with --).
  PREFLIGHT_DEVS=""
  for d in "''${DEVICES[@]}"; do
    PREFLIGHT_DEVS+="$(printf '%q' "$d") "
  done
  REMOTE_CMD='for i in 1 2 3 4 5 6 7 8 9 10; do ok=1; for d in '"$PREFLIGHT_DEVS"' ; do [ -e "$d" ] || ok=0; done; [ "$ok" = 1 ] && break; sleep 1; done; [ "$ok" = 1 ] || { echo "{\"ok\":false,\"reason\":\"devices not present after preflight\"}" >&2; exit 1; }; exec sleepwalker-hid-observer '"$REMOTE_DEVLIST"
  mkdir -p "$(dirname "$OUT")"
  SSH_ARGS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new)
  if [ -n "$IDENTITY" ]; then
    SSH_ARGS+=(-i "$IDENTITY")
  fi
  if [ -n "$KNOWN_HOSTS" ]; then
    SSH_ARGS+=(-o UserKnownHostsFile="$KNOWN_HOSTS")
  fi
  # Run the observer helper on the remote host. --grab is opt-in: the
  # observer host's input layer may hold the device, causing EBUSY when
  # grab is requested on nodes held by other consumers.
  ${ssh} "''${SSH_ARGS[@]}" "$TARGET" "$REMOTE_CMD" > "$OUT" 2>&1
  rc=$?
  DEVICES_JSON=$(printf '%s' "''${DEVICES[*]}" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read().split()))' 2>/dev/null || echo '[]')
  if [ $rc -eq 0 ]; then
    printf '{"ok":true,"target":"%s","out":"%s","timeout":%s,"devices":%s,"grab":%s}\n' \
      "$TARGET" "$OUT" "$TIMEOUT" "$DEVICES_JSON" "$([ -n "$GRAB_FLAG" ] && echo true || echo false)"
  else
    printf '{"ok":false,"reason":"ssh observer failed","rc":%d,"out":"%s","devices":%s,"grab":%s}\n' \
      $rc "$OUT" "$DEVICES_JSON" "$([ -n "$GRAB_FLAG" ] && echo true || echo false)" >&2
    exit $rc
  fi
''
