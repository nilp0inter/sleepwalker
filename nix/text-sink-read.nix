# sleepwalker-text-sink-read: retrieve the captured text artifact via SSH.
#
# Reads the text sink artifact file from the observer host and
# outputs it to stdout. The sink must have already been stopped
# (via SIGUSR2) so the artifact file is complete.
#
# Usage:
#   sleepwalker-text-sink-read <ssh_target> <remote_artifact> [identity] [known_hosts]
#
# Outputs the captured text to stdout. Returns 1 if the file
# does not exist (sink may not have been stopped).
{ lib, writeShellScriptBin, openssh, coreutils }:
let
  primPath = lib.makeBinPath [ openssh coreutils ];
in
writeShellScriptBin "sleepwalker-text-sink-read" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  SSH_TARGET="''${1:?}"
  REMOTE_ARTIFACT="''${2:?}"
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

  # Check if file exists on remote host
  if ! ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" "[ -f '$REMOTE_ARTIFACT' ]"; then
    echo "Artifact file does not exist (sink may not have been stopped)" >&2
    exit 1
  fi

  # Cat the remote file to stdout
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" "cat '$REMOTE_ARTIFACT'"
''
