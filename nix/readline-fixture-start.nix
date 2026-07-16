# sleepwalker-readline-fixture-start: start the readline fixture on the
# observer host over SSH.
#
# Starts the fixture on the active Linux VT (tty1) so Readline receives
# HID keyboard input from the Linux console input path.  The fixture
# also binds F24 as a barrier trigger and serves JSON control operations
# on its Unix socket.
#
# Usage:
#   sleepwalker-readline-fixture-start <ssh_target> [identity] [known_hosts]
#
# The fixture process runs fully detached on the observer host.  This
# wrapper waits for the control socket to appear before returning, so
# subsequent sleepwalker-readline-fixture-ctl calls can connect immediately.
{ lib, writeShellScriptBin, openssh, coreutils
, sleepwalker-readline-fixture, sleepwalker-readline-keymap
}:

let
  primPath = lib.makeBinPath [ openssh coreutils ];
  socketPath = "/tmp/sleepwalker-readline-fixture-v1.sock";
  remoteFixturePath = "/tmp/sleepwalker-readline-fixture";
  remoteKeymapPath = "/tmp/sleepwalker.map";
in
writeShellScriptBin "sleepwalker-readline-fixture-start" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  SSH_TARGET="''${1:?}"
  IDENTITY="''${2:-}"
  KNOWN_HOSTS="''${3:-}"

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

  # Stop a prior fixture before removing its socket. Keep these as separate
  # remote commands: placing the unbracketed socket path on the pkill shell's
  # command line would make `pkill -f` kill that shell and drop SSH with 255.
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo pkill -f '^${remoteFixturePath} /dev/tty' 2>/dev/null || true"
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo rm -f ${socketPath} ${remoteFixturePath} ${remoteKeymapPath}"

  # Hot-patch the running observer for this iteration. The same fixture and
  # keymap derivations are also part of the final ISO configuration.
  scp "''${SSH_ARGS[@]}" \
    "${sleepwalker-readline-fixture}/bin/sleepwalker-readline-fixture" \
    "${sleepwalker-readline-keymap}/share/keymaps/sleepwalker.map" \
    "$SSH_TARGET:/tmp/"
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "chmod 0755 ${remoteFixturePath}; sudo loadkeys ${remoteKeymapPath}"

  # Determine the active virtual console number
  ACTIVE_VT=$(ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo fgconsole 2>/dev/null || echo 1")

  # The target fixture must be the sole reader of its VT. A getty racing the
  # fixture consumes arbitrary bytes from HID key sequences and text input.
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo systemctl stop getty@tty$ACTIVE_VT.service autovt@tty$ACTIVE_VT.service"

  # openvt launches the fixture on the active VT and returns without waiting
  # unless `-w` is supplied. Do not add a second shell-level backgrounding
  # layer: that child is terminated when openvt's command shell exits.
  ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
    "sudo setsid openvt -c $ACTIVE_VT -f -- \
       ${remoteFixturePath} /dev/tty$ACTIVE_VT"

  # Wait for the control socket to appear on the observer host.
  # The fixture creates ${socketPath} during setup_socket(); if it
  # doesn't appear within the timeout, we report failure.
  SOCKET_TIMEOUT=30
  SOCKET_WAITED=0
  while [ "$SOCKET_WAITED" -lt "$SOCKET_TIMEOUT" ]; do
    if ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "test -S ${socketPath}" 2>/dev/null; then
      break
    fi
    SOCKET_WAITED=$((SOCKET_WAITED + 1))
    sleep 1
  done

  if [ "$SOCKET_WAITED" -ge "$SOCKET_TIMEOUT" ]; then
    echo "readline-fixture-start: control socket ${socketPath} not ready after ''${SOCKET_TIMEOUT}s" >&2
    printf '{"ok":false,"vt":%s,"socket_ready":false,"error":"socket_timeout"}\n' "$ACTIVE_VT"
    exit 1
  fi

  printf '{"ok":true,"vt":%s,"socket_ready":true}\n' "$ACTIVE_VT"
''