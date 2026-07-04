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
{ lib, stdenv, linuxHeaders }:
stdenv.mkDerivation {
  pname = "sleepwalker-hid-observer";
  version = "0.1.0";

  src = ./observer-helper-src;

  buildInputs = [ linuxHeaders ];

  makeFlags = [ "CC=${stdenv.cc.targetPrefix}cc" "PREFIX=$(out)" ];

  meta = with lib; {
    description = "JSONL evdev observer for sleepwalker HIL";
    mainProgram = "sleepwalker-hid-observer";
    platforms = platforms.linux;
    license = licenses.mit;
  };
}