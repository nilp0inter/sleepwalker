# sleepwalker-text-sink-start: start the remote text sink over SSH.
#
# Starts the text sink on the observer host via SSH, capturing
# rendered input bytes to a local artifact file. The sink runs
# in raw mode on the active VT and resets via SIGUSR1, flushes
# via SIGUSR2.
#
# Usage:
#   sleepwalker-text-sink-start <ssh_target> <artifact_file> [identity] [known_hosts]
#
# The sink runs remotely until stopped via SIGUSR2 (use
# sleepwalker-text-sink-ctl stop). Captured bytes are flushed
# to the artifact file on stop.
{ lib, writeShellScriptBin, openssh, coreutils }:
let
  primPath = lib.makeBinPath [ openssh coreutils ];
in
writeShellScriptBin "sleepwalker-text-sink-start" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  SSH_TARGET="''${1:?}"
  ARTIFACT_FILE="''${2:?}"
  IDENTITY="''${3:-}"
  KNOWN_HOSTS="''${4:-}"

  # Build SSH args
  SSH_ARGS=()
  if [ -n "$IDENTITY" ]; then
    SSH_ARGS+=(-i "$IDENTITY")
  fi
  if [ -n "$KNOWN_HOSTS" ]; then
    SSH_ARGS+=(-o "UserKnownHostsFile=$KNOWN_HOSTS")
    SSH_ARGS+=(-o "StrictHostKeyChecking=no")
  fi
  SSH_ARGS+=(-o "BatchMode=yes")

  ARTIFACT_DIR=$(dirname "$ARTIFACT_FILE")
  mkdir -p "$ARTIFACT_DIR"

  ACTIVE_VT=$(ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo fgconsole 2>/dev/null || echo 1")

  # Own one fixed VT and one process. Cycling through free VTs and returning
  # before the process is live makes per-example stop/read/start race.
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo pkill -f '^/run/current-system/sw/bin/sleepwalker-text-sink( |$)' \
       2>/dev/null || true; \
     sudo rm -f '$ARTIFACT_FILE'; \
     sudo systemctl stop getty@tty$ACTIVE_VT.service autovt@tty$ACTIVE_VT.service; \
     sudo setsid openvt -c $ACTIVE_VT -f -s -- \
       /run/current-system/sw/bin/sleepwalker-text-sink '$ARTIFACT_FILE'"

  for _ in $(seq 1 50); do
    if ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
      "pgrep -f '^/run/current-system/sw/bin/sleepwalker-text-sink( |$)' \
       >/dev/null"; then
      printf '{"ok":true,"vt":%s}\n' "$ACTIVE_VT"
      exit 0
    fi
    sleep 0.1
  done

  printf '{"ok":false,"error":"sink_start_timeout","vt":%s}\n' \
    "$ACTIVE_VT" >&2
  exit 1
''
