{
  description = "sleepwalker - agent-operated autonomous HIL appliance for ESP32-S3 USB HID";

  inputs = {
    # Pinned nixpkgs for deterministic toolchains across the harness host.
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";

    # Flake-utils for multi-arch system enumeration.
    flake-utils.url = "github:numtide/flake-utils";

    # Deterministic ESP-IDF Xtensa toolchains, compilers, and flash tools.
    # Used by firmware build/flash apps; consumed via overlay in devShell.
    nixpkgs-esp-dev = {
      url = "github:mirrexagon/nixpkgs-esp-dev";
      # Do NOT follow our nixpkgs: esp-dev pins a compatible nixpkgs and
      # builds esp-idf-full under its own config (incl. insecure-package
      # allowances for esptool's ecdsa dependency).
    };

    # Android SDK + JDK tooling pinned for reproducible Gradle builds.
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs/stable";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, nixpkgs-esp-dev, android-nixpkgs }:
    let
      # Harness host is x86_64-linux; observer ISO is x86_64-linux.
      # Firmware/Android build tooling also targets x86_64-linux hosts.
      supportedSystems = [ "x86_64-linux" ];

      # Overlay that exposes ESP-IDF and Android SDK tooling under sleepwalker-* names.
      sleepwalkerOverlay = final: prev: {
        # ESP-IDF toolchain from nixpkgs-esp-dev (Xtensa + idf.py + esptool).
        # Consumed as a pre-built package from esp-dev's own pinned nixpkgs
        # so its insecure-package allowances apply locally.
        sleepwalker-esp-idf = nixpkgs-esp-dev.packages.${final.system}.esp-idf-full;

        # Android SDK package set pinned via android-nixpkgs.
        sleepwalker-android-sdk = android-nixpkgs.sdk.${final.system};

        # Shared protocol Python package (frames, opcodes, fixtures, tests).
        sleepwalker-protocol = final.callPackage ./nix/protocol-pkg.nix { };

        # Project helper: protocol no-hardware verification command.
        sleepwalker-protocol-check = final.callPackage ./nix/protocol-check.nix { };

        # Project helper: bench config validator (no hardware touched).
        sleepwalker-bench-validate = final.callPackage ./nix/bench-validate.nix { };
      };

      perSystem = system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ sleepwalkerOverlay ];
            # ESP-IDF's esptool pulls in python3.12-ecdsa (CVE-2024-23342).
            # Allow it explicitly; esptool is a flashing tool, not a runtime
            # crypto boundary. Track upstream esptool removal of ecdsa.
            config.permittedInsecurePackages = [
              "python3.12-ecdsa-0.19.1"
            ];
          };

          # JDK for Android Gradle builds.
          jdk = pkgs.jdk17;

          # Android SDK with the components needed by sleepwalker-app.
          androidSdk = pkgs.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0
            cmdline-tools-11-0
            platform-tools
            platforms-android-34
          ]);

          # Serial tooling for ESP UART over USB-to-TTL.
          serialTools = with pkgs; [ esptool screen picocom ];

          # SSH tooling for reaching the sacrificial HID observer host.
          sshTools = with pkgs; [ openssh ];

          # ADB is bundled in androidSdk platform-tools; also expose standalone.
          adbTools = [ androidSdk ];

          # ESP-IDF build tools.
          espTools = [ pkgs.sleepwalker-esp-idf ];

          # Python for protocol package and HIL helpers.
          pythonEnv = pkgs.python3.withPackages (ps: with ps; [
            pytest
            crcmod
            ps.tomli
          ]);
        in
        {
          # ---- Packages (collision-resistant sleepwalker-* names) ----
          packages = {
            sleepwalker-protocol-check = pkgs.sleepwalker-protocol-check;
            sleepwalker-bench-validate = pkgs.sleepwalker-bench-validate;
            sleepwalker-protocol = pkgs.sleepwalker-protocol;
            default = pkgs.sleepwalker-protocol-check;
          };

          # ---- Apps (collision-resistant sleepwalker-* names) ----
          apps = {
            # No-hardware protocol verification command.
            sleepwalker-protocol-check = {
              type = "app";
              program = "${pkgs.sleepwalker-protocol-check}/bin/sleepwalker-protocol-check";
            };

            # No-hardware bench config validation command.
            sleepwalker-bench-validate = {
              type = "app";
              program = "${pkgs.sleepwalker-bench-validate}/bin/sleepwalker-bench-validate";
            };

            # Placeholder apps declared as a contract surface for later passes.
            # They are intentionally stubs that fail loudly until their task is implemented,
            # so the agent never silently no-ops on a missing primitive.
            sleepwalker-fw-build = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-fw-build" ''
                echo "sleepwalker-fw-build: not implemented in this foundation pass" >&2
                exit 70  # EX_SOFTWARE
              '' }/bin/sleepwalker-fw-build";
            };

            sleepwalker-fw-flash = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-fw-flash" ''
                echo "sleepwalker-fw-flash: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-fw-flash";
            };

            sleepwalker-fw-uart = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-fw-uart" ''
                echo "sleepwalker-fw-uart: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-fw-uart";
            };

            sleepwalker-apk-build = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-apk-build" ''
                echo "sleepwalker-apk-build: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-apk-build";
            };

            sleepwalker-apk-install = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-apk-install" ''
                echo "sleepwalker-apk-install: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-apk-install";
            };

            sleepwalker-adb-status = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-adb-status" ''
                echo "sleepwalker-adb-status: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-adb-status";
            };

            sleepwalker-adb-connect = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-adb-connect" ''
                echo "sleepwalker-adb-connect: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-adb-connect";
            };

            sleepwalker-adb-arm = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-adb-arm" ''
                echo "sleepwalker-adb-arm: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-adb-arm";
            };

            sleepwalker-adb-inject-key = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-adb-inject-key" ''
                echo "sleepwalker-adb-inject-key: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-adb-inject-key";
            };

            sleepwalker-adb-release-all = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-adb-release-all" ''
                echo "sleepwalker-adb-release-all: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-adb-release-all";
            };

            sleepwalker-adb-kill = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-adb-kill" ''
                echo "sleepwalker-adb-kill: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-adb-kill";
            };

            sleepwalker-hid-observe = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-hid-observe" ''
                echo "sleepwalker-hid-observe: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-hid-observe";
            };

            sleepwalker-human-gate = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-human-gate" ''
                echo "sleepwalker-human-gate: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-human-gate";
            };

            sleepwalker-smoke-keyboard = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "sleepwalker-smoke-keyboard" ''
                echo "sleepwalker-smoke-keyboard: not implemented in this foundation pass" >&2
                exit 70
              '' }/bin/sleepwalker-smoke-keyboard";
            };
          };

          # ---- Default dev shell: all tooling for the harness host ----
          devShells.default = pkgs.mkShell {
            name = "sleepwalker-dev";
            packages = with pkgs; [
              # ESP-IDF build tools.
              sleepwalker-esp-idf
              # Android build tools.
              jdk
              gradle
              # ADB / platform-tools.
              androidSdk
              # Serial tooling for ESP UART over USB-to-TTL.
              esptool
              screen
              picocom
              # SSH tooling for the sacrificial HID observer host.
              openssh
              # noti for explicit human commissioning/recovery gates.
              noti
              # Python env for protocol package and HIL helpers.
              pythonEnv
              # Nix build tooling.
              nix
            ] ++ [
              # Expose project helper binaries in the dev shell.
              self.packages.${system}.sleepwalker-protocol-check
              self.packages.${system}.sleepwalker-bench-validate
            ];

            # Android SDK location expectations.
            ANDROID_SDK_ROOT = "${androidSdk}/share/android-sdk";
            ANDROID_HOME = "${androidSdk}/share/android-sdk";
            # ESP-IDF location.
            ESP_IDF_PATH = "${pkgs.sleepwalker-esp-idf}";
          };
        };
    in
    flake-utils.lib.eachSystem supportedSystems perSystem;
}