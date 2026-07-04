# sleepwalker-fw-build: build the ESP32-S3 firmware without flashing.
#
# Drives `idf.py build` inside the firmware/ tree using the
# nixpkgs-esp-dev toolchain. The Nix ESP-IDF package provides a setup-hook
# that sets IDF_PATH, IDF_PYTHON_ENV_PATH, and PATH correctly — but that
# hook only fires when the package is a nativeBuildInput in a Nix
# derivation. For a writeShellScriptBin wrapper we replicate the key
# pieces: IDF_PATH, python-env, and the propagated toolchain bin dirs
# (Xtensa cross-compiler, gdb, openocd, esp-clang, etc.).
#
# No hardware is touched at build time; this is the no-hardware firmware
# build check used by task 7.2.
{ lib, writeShellScriptBin, sleepwalker-esp-idf, coreutils, git, cmake, ninja, python3 }:
writeShellScriptBin "sleepwalker-fw-build" ''
  set -euo pipefail
  FIRMWARE_DIR="''${1:-$(git rev-parse --show-toplevel 2>/dev/null || echo .)/firmware}"
  if [ ! -d "$FIRMWARE_DIR" ]; then
    printf '{"ok":false,"reason":"firmware dir not found","dir":"%s"}\n' "$FIRMWARE_DIR" >&2
    exit 2
  fi
  # Activate the Nix ESP-IDF environment.
  export IDF_PATH="${sleepwalker-esp-idf}"
  export IDF_TOOLS_PATH="$IDF_PATH/tools"
  export IDF_PYTHON_CHECK_CONSTRAINTS=no
  export IDF_PYTHON_ENV_PATH="$(readlink "$IDF_PATH/python-env")"
  # Add propagated toolchain bin dirs (Xtensa cross-compiler, gdb, openocd,
  # esp-clang, rom-elfs, etc.) by reading propagated-build-inputs.
  PROPAGATED=""
  if [ -f "$IDF_PATH/nix-support/propagated-build-inputs" ]; then
    PROPAGATED="$(cat "$IDF_PATH/nix-support/propagated-build-inputs")"
  fi
  EXTRA_PATH=""
  for p in $PROPAGATED; do
    if [ -d "$p/bin" ]; then
      EXTRA_PATH="$p/bin:$EXTRA_PATH"
    fi
  done
  export PATH="${cmake}/bin:${ninja}/bin:${python3}/bin:$EXTRA_PATH$IDF_TOOLS_PATH:$IDF_PATH/components/espcoredump:$IDF_PATH/components/partition_table:$IDF_PATH/components/app_update:$PATH"
  [ -e "$IDF_PATH/.tool-env" ] && . "$IDF_PATH/.tool-env" || true
  if [ -e "$IDF_PATH/etc/gitconfig" ]; then
    export GIT_CONFIG_SYSTEM="$IDF_PATH/etc/gitconfig"
  fi
  cd "$FIRMWARE_DIR"
  idf.py build >/tmp/sleepwalker-fw-build.log 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    printf '{"ok":true,"dir":"%s","log":"/tmp/sleepwalker-fw-build.log"}\n' "$FIRMWARE_DIR"
  else
    printf '{"ok":false,"reason":"idf.py build failed","rc":%d,"log":"/tmp/sleepwalker-fw-build.log"}\n' $rc >&2
    cat /tmp/sleepwalker-fw-build.log >&2
    exit $rc
  fi
''