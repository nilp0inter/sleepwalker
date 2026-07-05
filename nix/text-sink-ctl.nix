# sleepwalker-text-sink-ctl: control the remote text sink over SSH.
#
# Provides reset, status, and stop operations for the text sink
# running on the observer host. The sink must already be running
# (started via a separate mechanism).
#
# Operations:
#   reset: send SIGUSR1 to clear the capture buffer
#   stop: send SIGUSR2 to flush and stop the sink
#   status: check if the sink process is running
#
# Usage:
#   sleepwalker-text-sink-ctl <ssh_target> <operation> [identity] [known_hosts]
{ lib, writeShellScriptBin, openssh, coreutils }:
let
  primPath = lib.makeBinPath [ openssh coreutils ];
in
writeShellScriptBin "sleepwalker-text-sink-ctl" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  SSH_TARGET="''${1:?}"
  OPERATION="''${2:?}"
  IDENTITY="''${3:-}"
  KNOWN_HOSTS="''${4:-}"

  # Build SSH args
  if [ -n "$IDENTITY" ]; then
    SSH_ARGS+=(-i "$IDENTITY")
  fi
  if [ -n "$KNOWN_HOSTS" ]; then
    SSH_ARGS+=(-o "UserKnownHostsFile=$KNOWN_HOSTS")
    SSH_ARGS+=(-o "StrictHostKeyChecking=yes")
  fi
  SSH_ARGS+=(-o "BatchMode=yes")

  case "$OPERATION" in
    reset)
      # Send SIGUSR1 to reset the capture buffer
      ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "sudo pkill -USR1 -x 'sleepwalker-text-sink|text-sink'" || true
      ;;

    stop)
      # Send SIGUSR2 to flush and stop the sink
      ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "sudo pkill -USR2 -x 'sleepwalker-text-sink|text-sink'" || true
      ;;

    status)
      # Check if the sink is running
      ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "pgrep -x 'sleepwalker-text-sink|text-sink' >/dev/null && echo 'running' || echo 'stopped'"
      ;;

    *)
      echo "Usage: $0 <ssh_target> <reset|stop|status> [identity] [known_hosts]" >&2
      exit 1
      ;;
  esac
''
