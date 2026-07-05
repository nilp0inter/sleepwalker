# sleepwalker-hid-observer helper: emits JSONL evdev events with device
# identity and timestamps, and supports exclusive input grab for active
# smoke tests.
#
# Written in C for minimal runtime deps on the sacrificial host. Reads
# /dev/input/by-id/sleepwalker-hid-keyboard (or a path argument) and
# emits one JSON object per evdev event on stdout.
#
# Usage:
#   sleepwalker-hid-observer <device-path> [--grab] [--timeout sec]
#
# Output (one JSON object per line):
#   {"ts_ms":1234,"device":"...","type":"EV_KEY","code":"KEY_SPACE",
#    "value":1,"type_code":1,"code_code":57}
#
# NOTE: The binary's ELF interpreter is patched post-build to use the
# system's glibc (pkgs.glibc) rather than stdenv.cc's glibc, which may
# differ. This ensures the binary runs on the observer ISO's glibc.
{ lib, stdenv, linuxHeaders, patchelf, glibc }:
stdenv.mkDerivation {
  pname = "sleepwalker-hid-observer";
  version = "0.1.0";

  src = ./observer-helper-src;

  nativeBuildInputs = [ patchelf ];
  buildInputs = [ linuxHeaders ];

  makeFlags = [ "CC=${stdenv.cc.targetPrefix}cc" "PREFIX=$(out)" ];

  # Patch the ELF interpreter to use the system glibc (not stdenv's).
  # stdenv.cc may link against a different glibc than pkgs.glibc; this
  # ensures the binary's interpreter matches what the ISO provides.
  postInstall = ''
    patchelf --set-interpreter ${glibc}/lib/ld-linux-x86-64.so.2 \
      $out/bin/sleepwalker-hid-observer
  '';

  meta = with lib; {
    description = "JSONL evdev observer for sleepwalker HIL";
    mainProgram = "sleepwalker-hid-observer";
    platforms = platforms.linux;
    license = licenses.mit;
  };
}