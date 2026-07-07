# sleepwalker-apk-build: build the sleepwalker Android APK via Gradle.
#
# Uses the Nix-pinned JDK17 and Android SDK from the flake. The Nix SDK is
# read-only, so we create a writable overlay (symlinks + writable license
# dir) at build time. No hardware is touched; this is the no-hardware
# Android build check used by task 7.3.
{ lib, writeShellScriptBin, jdk17, androidSdk, gradle, coreutils, git, keymapDb ? null }:
let
  sdkRoot = "${androidSdk}/share/android-sdk";
in
writeShellScriptBin "sleepwalker-apk-build" ''
  set -euo pipefail
  ANDROID_DIR="''${1:-$(git rev-parse --show-toplevel 2>/dev/null || echo .)/android}"
  if [ ! -d "$ANDROID_DIR" ]; then
    printf '{"ok":false,"reason":"android dir not found","dir":"%s"}\n' "$ANDROID_DIR" >&2
    exit 2
  fi
  export JAVA_HOME="${jdk17}"
  # Create a writable SDK overlay so Gradle can write license files.
  WRITABLE_SDK="''${SLEEPWALKER_SDK_ROOT:-$(mktemp -d)}"
  if [ ! -d "$WRITABLE_SDK/build-tools" ]; then
    for item in "${sdkRoot}"/*; do
      name="$(basename "$item")"
      [ "$name" = "licenses" ] && continue
      ln -sf "$item" "$WRITABLE_SDK/$name"
    done
    mkdir -p "$WRITABLE_SDK/licenses"
    printf "\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n" > "$WRITABLE_SDK/licenses/android-sdk-license"
    printf "\n84831b9409646a918e30573bab4c9c91346d8abd\n" > "$WRITABLE_SDK/licenses/android-sdk-preview-license"
  fi
  export ANDROID_SDK_ROOT="$WRITABLE_SDK"
  export ANDROID_HOME="$WRITABLE_SDK"
  # Create a debug keystore if it doesn't exist (the Android plugin normally
  # auto-creates this at ~/.android but that may not be writable).
  KEYSTORE="''${SLEEPWALKER_KEYSTORE:-/tmp/sleepwalker-debug.keystore}"
  export SLEEPWALKER_KEYSTORE="$KEYSTORE"
  if [ ! -f "$KEYSTORE" ]; then
    "${jdk17}/bin/keytool" -genkeypair -v -keystore "$KEYSTORE" \
      -storepass android -alias androiddebugkey -keypass android \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || true
  fi
  # Copy OmniKeymap JSON database into res/raw as flat keymap_<platform>_<stem>.json resources.
  # Android res/raw does not support subdirectories; the database is flattened with a
  # platform-prefixed name so the runtime parser can recover (platform, layout, variant).
  if [ -n "${keymapDb}" ]; then
    KEYMAP_DEST="$ANDROID_DIR/sleepwalker-core/src/main/res/raw"
    rm -f "$KEYMAP_DEST"/keymap_*.json
    for platform_dir in "${keymapDb}/database"/*; do
      [ -d "$platform_dir" ] || continue
      platform="$(basename "$platform_dir")"
      platform_lower="$(printf '%s' "$platform" | tr 'A-Z' 'a-z')"
      for json_file in "$platform_dir"/*.json; do
        [ -f "$json_file" ] || continue
        stem="$(basename "$json_file" .json)"
        safe_stem="$(printf '%s' "$stem" | tr '+-' '__' | tr 'A-Z' 'a-z')"
        cp -f "$json_file" "$KEYMAP_DEST/keymap_''${platform_lower}_''${safe_stem}.json"
      done
    done
  fi
  cd "$ANDROID_DIR"
  ${gradle}/bin/gradle --no-daemon --stacktrace :sleepwalker-app:assembleDebug \
    >/tmp/sleepwalker-apk-build.log 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    APK="$ANDROID_DIR/sleepwalker-app/build/outputs/apk/debug/sleepwalker-app-debug.apk"
    printf '{"ok":true,"dir":"%s","apk":"%s","log":"/tmp/sleepwalker-apk-build.log"}\n' \
      "$ANDROID_DIR" "$APK"
  else
    printf '{"ok":false,"reason":"gradle assembleDebug failed","rc":%d,"log":"/tmp/sleepwalker-apk-build.log"}\n' $rc >&2
    cat /tmp/sleepwalker-apk-build.log >&2
    exit $rc
  fi
''