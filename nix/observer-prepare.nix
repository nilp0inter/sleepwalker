# sleepwalker-observer-prepare: prepare the observer host for identity tests.
#
# Sets up the observer host environment for text identity tests,
# including configuring the Linux console keymap.
#
# Usage:
#   sleepwalker-observer-prepare <ssh_target> <backend> [identity] [known_hosts]
#
# Supported backends:
#   linux:us: configure Linux console US keymap
#
# The backend determines the console keymap used by the text sink
# for capturing rendered input. This must match the keymap that
# the ESP32-S3 firmware assumes when generating HID reports.
{ lib, writeShellScriptBin, openssh }:
let
  primPath = lib.makeBinPath [ openssh ];
in
writeShellScriptBin "sleepwalker-observer-prepare" ''
  set -eo pipefail
  export PATH="${primPath}:$PATH"

  SSH_TARGET="''${1:?}"
  BACKEND="''${2:?}"
  IDENTITY="''${3:-}"
  KNOWN_HOSTS="''${4:-}"

  # Build SSH args
  SSH_ARGS=()
  if [ -n "$IDENTITY" ]; then
    SSH_ARGS+=(-i "$IDENTITY")
  fi
  if [ -n "$KNOWN_HOSTS" ]; then
    SSH_ARGS+=(-o "UserKnownHostsFile=$KNOWN_HOSTS")
    SSH_ARGS+=(-o "StrictHostKeyChecking=yes")
  fi
  SSH_ARGS+=(-o "BatchMode=yes")

  case "$BACKEND" in
    linux:us)
      # Configure the Linux console to use the US keymap
      ssh ''${SSH_ARGS[@]} "$SSH_TARGET" \
        "sudo loadkeys us" || {
        echo "Failed to set console keymap to us" >&2
        exit 1
      }
      echo "Observer prepared: console keymap set to us"
      ;;

    *)
      echo "Unsupported backend: $BACKEND" >&2
      echo "Supported backends: linux:us" >&2
      exit 1
      ;;
  esac
''
