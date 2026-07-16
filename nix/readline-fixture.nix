# sleepwalker-readline-fixture: real GNU Readline fixture for HIL text
# identity tests.  Links against a pinned GNU Readline 8.2p13
# (nix/readline-8.2.nix) and runs rl_callback_read_char on the active
# Linux VT while serving versioned JSON control operations on a Unix
# socket.
#
# Control ABI version 1 operations:
#   describe, reset, await_barrier, snapshot, health, shutdown
#
# Identity: gnu-readline 8.2, emacs, ascii-printable, single-line
#
# Socket: /tmp/sleepwalker-readline-fixture-v1.sock
# Protocol: newline-delimited JSON
#
# Usage:
#   sleepwalker-readline-fixture <vt_device>
#
# The fixture binds F24 as a no-insertion barrier.  F24 key sequence on
# Linux VT (default keymap): ESC [ 2 4 ~
{ lib, stdenv, readline82 }:

stdenv.mkDerivation {
  pname = "sleepwalker-readline-fixture";
  version = "0.1.0";

  src = ./observer-fixture-src;

  buildInputs = [ readline82 ];

  makeFlags = [ "CC=${stdenv.cc.targetPrefix}cc" "PREFIX=$(out)" ];

  preBuild = ''
    make clean
  '';

  meta = with lib; {
    description = "GNU Readline fixture for sleepwalker HIL text identity tests";
    longDescription = ''
      Interactive Readline fixture that uses rl_callback_read_char on the
      active Linux VT.  Binds F24 as a barrier trigger (no buffer mutation)
      and serves JSON control over a versioned Unix socket.  Designed for
      automated text identity regression tests in the sleepwalker HIL
      appliance.
    '';
    mainProgram = "sleepwalker-readline-fixture";
    platforms = platforms.linux;
    license = licenses.mit;
  };
}