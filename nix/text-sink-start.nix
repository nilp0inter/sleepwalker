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

  # Ensure artifact directory exists
  ARTIFACT_DIR=$(dirname "$ARTIFACT_FILE")
  mkdir -p "$ARTIFACT_DIR"

  # Start the sink remotely; it will run until stopped
  # Use full path to ensure it's found on NixOS observer host
  # Start the sink remotely; it will run until stopped
  # Use ~/.local/bin path for manually deployed text-sink
  # Start the sink remotely on the console VT; it will run until stopped
  # Use openvt to run on the active VT (tty1) with stdin from the console
  # Start the sink remotely on the console VT; it will run until stopped
  # Use sudo openvt to run on the active VT (tty1) with stdin from the console
  # Start the sink remotely on the console VT; it will run until stopped
  # Use sudo openvt with -f to force and -l to specify the VT number
  # The artifact file path is expanded on the harness host, then passed as an argument
  # Start the sink remotely on the console VT; it will run until stopped
  # Use sudo openvt with -f to force and -l to specify the VT number
  # Use full path and explicit quotes to prevent shell concatenation
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo openvt -f -l -s -- /home/observer/.local/bin/text-sink '$ARTIFACT_FILE'" &

  SINK_PID=$!
  echo "$SINK_PID"
''
