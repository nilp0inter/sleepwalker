# sleepwalker-apk-install: install the built APK to the Android test device.
#
# Side-effectful: uses adb install. Requires the ADB device serial from
# bench config.
{ lib, writeShellScriptBin, androidSdk }:
let
  adb = "${androidSdk}/share/android-sdk/platform-tools/adb";
in
writeShellScriptBin "sleepwalker-apk-install" ''
  set -euo pipefail
  APK="''${1:?usage: sleepwalker-apk-install <apk> [serial]}"
  SERIAL="''${2:-}"
  if [ ! -f "$APK" ]; then
    printf '{"ok":false,"reason":"apk not found","apk":"%s"}\n' "$APK" >&2
    exit 2
  fi
  ADB_ARGS=()
  if [ -n "$SERIAL" ]; then
    ADB_ARGS+=(-s "$SERIAL")
  fi
  ${adb} "''${ADB_ARGS[@]}" install -r -g "$APK" >/tmp/sleepwalker-apk-install.log 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    printf '{"ok":true,"apk":"%s","serial":"%s","log":"/tmp/sleepwalker-apk-install.log"}\n' \
      "$APK" "$SERIAL"
  else
    printf '{"ok":false,"reason":"adb install failed","rc":%d,"log":"/tmp/sleepwalker-apk-install.log"}\n' $rc >&2
    cat /tmp/sleepwalker-apk-install.log >&2
    exit $rc
  fi
''