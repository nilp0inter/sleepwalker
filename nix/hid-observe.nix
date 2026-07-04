# sleepwalker-hid-observe: start the remote HID observer helper over SSH
# and collect JSONL HID events into an artifact file.
#
# Side-effectful: SSHes to the sacrificial observer host and runs
# sleepwalker-hid-observer with --grab and --timeout, collecting JSONL
# output. Uses a dedicated known_hosts file and identity to avoid
# polluting user SSH state.
{ lib, writeShellScriptBin, openssh }:
let
  ssh = "${openssh}/bin/ssh";
in
writeShellScriptBin "sleepwalker-hid-observe" ''
  set -euo pipefail
  TARGET="''${1:?usage: sleepwalker-hid-observe <ssh_target> <out.jsonl> [timeout_sec] [identity] [known_hosts]}"
  OUT="''${2:?usage: sleepwalker-hid-observe <ssh_target> <out.jsonl> [timeout_sec] [identity] [known_hosts]}"
  TIMEOUT="''${3:-30}"
  IDENTITY="''${4:-}"
  KNOWN_HOSTS="''${5:-}"
  mkdir -p "$(dirname "$OUT")"
  SSH_ARGS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new)
  if [ -n "$IDENTITY" ]; then
    SSH_ARGS+=(-i "$IDENTITY")
  fi
  if [ -n "$KNOWN_HOSTS" ]; then
    SSH_ARGS+=(-o UserKnownHostsFile="$KNOWN_HOSTS")
  fi
  # Run the observer helper on the remote host with grab + timeout.
  ${ssh} "''${SSH_ARGS[@]}" "$TARGET" \
    "sleepwalker-hid-observer /dev/input/by-id/sleepwalker-hid-keyboard --grab --timeout $TIMEOUT" \
    > "$OUT" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    printf '{"ok":true,"target":"%s","out":"%s","timeout":%s}\n' "$TARGET" "$OUT" "$TIMEOUT"
  else
    printf '{"ok":false,"reason":"ssh observer failed","rc":%d,"out":"%s"}\n' $rc "$OUT" >&2
    exit $rc
  fi
''