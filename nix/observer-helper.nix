# sleepwalker-hid-observer helper: emits JSONL evdev events with device
# identity, role classification, helper version/path, and timestamps.
# Supports composite keyboard+mouse discovery by capability probing
# and exclusive input grab for active smoke tests.
# sleepwalker-text-sink: raw-mode Linux console text capture for HIL identity tests.
#
# Both written in C for minimal runtime deps on the sacrificial host.
#
# Written in C for minimal runtime deps on the sacrificial host. Reads
# one or more /dev/input/by-id/sleepwalker-hid-* paths (or path args)
# and emits one JSON object per evdev event on stdout.
#
# Usage:
#   sleepwalker-hid-observer <device>... [--grab] [--timeout sec]
#
# Output (one JSON object per line):
#   {"ts_ms":1234,"device":"...","type":"EV_KEY","code":"KEY_SPACE",
#    "value":1,"type_code":1,"code_code":57}
#
# sleepwalker-text-sink usage:
#   sleepwalker-text-sink <artifact-file>
#
# Control via SSH signals:
#   SIGUSR1: reset the capture buffer
#   SIGUSR2: stop and flush to artifact file
{ lib, stdenv, linuxHeaders, patchelf, glibc }:
stdenv.mkDerivation {
  pname = "sleepwalker-hid-observer";
  version = "0.2.0";

  src = ./observer-helper-src;

  nativeBuildInputs = [ patchelf ];
  buildInputs = [ linuxHeaders ];

  makeFlags = [ "CC=${stdenv.cc.targetPrefix}cc" "PREFIX=$(out)" ];

  # Defensive: `make clean` before building so a stale pre-built binary
  # accidentally included in the source copy cannot cause `make` to skip
  # recompilation (all files in the Nix store share epoch mtime, so `make`
  # would see the binary as up-to-date and install the stale artifact).
  preBuild = ''
    make clean
  '';

  # Build the unit test binary alongside the helper. The test creates
  # uinput keyboard+mouse devices, emits a known event sequence, and
  # verifies symbolic decoding, capability classification, helper-version
  # reporting, and exclusive grab. Skipped gracefully (exit 77) when
  # /dev/uinput is not writable, e.g. inside a Nix build sandbox without
  # uinput access, so the check is safe to run in CI and on hosts.
  doCheck = stdenv.hostPlatform == stdenv.buildPlatform;
  nativeCheckInputs = [ stdenv.cc ];
  checkPhase = ''
    runHook preCheck
    ${stdenv.cc}/bin/cc -O2 -Wall -o observer-helper-test observer-helper-test.c
    ./observer-helper-test ./sleepwalker-hid-observer || rc=$?
    # rc=77 means /dev/uinput unavailable in sandbox -> skip, not fail.
    if [ "''${rc:-0}" = "77" ]; then
      echo "observer-helper-test: SKIP (no /dev/uinput in sandbox)"
    elif [ "''${rc:-0}" != "0" ]; then
      exit 1
    fi
    runHook postCheck
  '';

  # Patch the ELF interpreter to use the system glibc (not stdenv's).
  # stdenv.cc may link against a different glibc than pkgs.glibc; this
  # ensures the binary's interpreter matches what the ISO provides.
  # Apply to both binaries.
  postInstall = ''
    patchelf --set-interpreter ${glibc}/lib/ld-linux-x86-64.so.2 \
      $out/bin/sleepwalker-hid-observer
    patchelf --set-interpreter ${glibc}/lib/ld-linux-x86-64.so.2 \
      $out/bin/sleepwalker-text-sink
  '';

  meta = with lib; {
    description = "HIL observer tools for sleepwalker: JSONL evdev observer and raw-mode text sink";
    mainProgram = "sleepwalker-hid-observer";
    platforms = platforms.linux;
    license = licenses.mit;
  };
}