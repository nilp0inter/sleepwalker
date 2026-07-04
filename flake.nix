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

      # NixOS configuration for the sacrificial HID observer ISO (task 5.1).
      # Built as a bootable ISO via config.system.build.isoImage.
      observerIsoSystem = nixpkgs.lib.nixosSystem {
        system = "x86_64-linux";
        modules = [
          "${nixpkgs}/nixos/modules/installer/cd-dvd/installation-cd-minimal.nix"
          ./nix/observer-host.nix
          { environment.systemPackages = [ (nixpkgs.legacyPackages.x86_64-linux.callPackage ./nix/observer-helper.nix { }) ]; }
        ];
      };

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
        sleepwalker-fw-build = final.callPackage ./nix/fw-build.nix { cmake = final.cmake; ninja = final.ninja; python3 = final.python3; };
        sleepwalker-fw-flash = final.callPackage ./nix/fw-flash.nix { cmake = final.cmake; ninja = final.ninja; python3 = final.python3; };
        sleepwalker-fw-uart = final.callPackage ./nix/fw-uart.nix { };
        sleepwalker-apk-build = final.callPackage ./nix/apk-build.nix {
          jdk17 = final.jdk17;
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0
            cmdline-tools-11-0
            platform-tools
            platforms-android-34
          ]);
        };
        sleepwalker-apk-install = final.callPackage ./nix/apk-install.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0
            cmdline-tools-11-0
            platform-tools
            platforms-android-34
          ]);
        };
        sleepwalker-adb-logcat = final.callPackage ./nix/adb-logcat.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0
            cmdline-tools-11-0
            platform-tools
            platforms-android-34
          ]);
        };
        # ADB operations (task 6.4): one callPackage, six named binaries.
        sleepwalker-adb-status = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-status;
        sleepwalker-adb-connect = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-connect;
        sleepwalker-adb-arm = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-arm;
        sleepwalker-adb-inject-key = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-inject-key;
        sleepwalker-adb-release-all = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-release-all;
        sleepwalker-adb-kill = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-kill;
        sleepwalker-hid-observe = final.callPackage ./nix/hid-observe.nix { };
        sleepwalker-human-gate = final.callPackage ./nix/human-gate.nix { };
        sleepwalker-artifacts = final.callPackage ./nix/artifacts.nix { };
        sleepwalker-smoke-keyboard = final.callPackage ./nix/smoke-keyboard.nix { };
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
          packages = {
            sleepwalker-protocol-check = pkgs.sleepwalker-protocol-check;
            sleepwalker-bench-validate = pkgs.sleepwalker-bench-validate;
            sleepwalker-protocol = pkgs.sleepwalker-protocol;
            sleepwalker-fw-build = pkgs.sleepwalker-fw-build;
            sleepwalker-fw-flash = pkgs.sleepwalker-fw-flash;
            sleepwalker-fw-uart = pkgs.sleepwalker-fw-uart;
            sleepwalker-apk-build = pkgs.sleepwalker-apk-build;
            sleepwalker-apk-install = pkgs.sleepwalker-apk-install;
            sleepwalker-adb-logcat = pkgs.sleepwalker-adb-logcat;
            sleepwalker-adb-status = pkgs.sleepwalker-adb-status;
            sleepwalker-adb-connect = pkgs.sleepwalker-adb-connect;
            sleepwalker-adb-arm = pkgs.sleepwalker-adb-arm;
            sleepwalker-adb-inject-key = pkgs.sleepwalker-adb-inject-key;
            sleepwalker-adb-release-all = pkgs.sleepwalker-adb-release-all;
            sleepwalker-adb-kill = pkgs.sleepwalker-adb-kill;
            sleepwalker-hid-observe = pkgs.sleepwalker-hid-observe;
            sleepwalker-human-gate = pkgs.sleepwalker-human-gate;
            sleepwalker-artifacts = pkgs.sleepwalker-artifacts;
            sleepwalker-smoke-keyboard = pkgs.sleepwalker-smoke-keyboard;
            # Bootable observer ISO (task 5.2). Built via nixosSystem.
            sleepwalker-hid-observer-iso = observerIsoSystem.config.system.build.isoImage;
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
            # No-hardware firmware build check (task 3.9 / 7.2).
            sleepwalker-fw-build = {
              type = "app";
              program = "${pkgs.sleepwalker-fw-build}/bin/sleepwalker-fw-build";
            };

            # Side-effectful: flash firmware to ESP32-S3 over UART.
            sleepwalker-fw-flash = {
              type = "app";
              program = "${pkgs.sleepwalker-fw-flash}/bin/sleepwalker-fw-flash";
            };

            # Side-effectful: capture ESP auxiliary UART JSONL logs.
            sleepwalker-fw-uart = {
              type = "app";
              program = "${pkgs.sleepwalker-fw-uart}/bin/sleepwalker-fw-uart";
            };

            # No-hardware Android APK build check (task 4.8 / 7.3).
            sleepwalker-apk-build = {
              type = "app";
              program = "${pkgs.sleepwalker-apk-build}/bin/sleepwalker-apk-build";
            };

            # Side-effectful: install APK to the Android test device over ADB.
            sleepwalker-apk-install = {
              type = "app";
              program = "${pkgs.sleepwalker-apk-install}/bin/sleepwalker-apk-install";
            };

            # ADB operations (task 6.4): drive the companion over ADB broadcasts.
            sleepwalker-adb-status = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-status}/bin/sleepwalker-adb-status";
            };
            sleepwalker-adb-connect = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-connect}/bin/sleepwalker-adb-connect";
            };
            sleepwalker-adb-arm = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-arm}/bin/sleepwalker-adb-arm";
            };
            sleepwalker-adb-inject-key = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-inject-key}/bin/sleepwalker-adb-inject-key";
            };
            sleepwalker-adb-release-all = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-release-all}/bin/sleepwalker-adb-release-all";
            };
            sleepwalker-adb-kill = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-kill}/bin/sleepwalker-adb-kill";
            };

            # Start remote HID observer over SSH (task 6.5).
            sleepwalker-hid-observe = {
              type = "app";
              program = "${pkgs.sleepwalker-hid-observe}/bin/sleepwalker-hid-observe";
            };

            # Human gate: noti + poll observable condition (task 6.6).
            sleepwalker-human-gate = {
              type = "app";
              program = "${pkgs.sleepwalker-human-gate}/bin/sleepwalker-human-gate";
            };

            # Composed keyboard-only smoke scenario (task 6.8).
            sleepwalker-smoke-keyboard = {
              type = "app";
              program = "${pkgs.sleepwalker-smoke-keyboard}/bin/sleepwalker-smoke-keyboard";
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
              pkgs.sleepwalker-protocol-check
              pkgs.sleepwalker-bench-validate
              pkgs.sleepwalker-fw-build
              pkgs.sleepwalker-fw-flash
              pkgs.sleepwalker-fw-uart
              pkgs.sleepwalker-apk-build
              pkgs.sleepwalker-apk-install
              pkgs.sleepwalker-adb-logcat
            ];

            # Android SDK location expectations.
            ANDROID_SDK_ROOT = "${androidSdk}/share/android-sdk";
            ANDROID_HOME = "${androidSdk}/share/android-sdk";
            # ESP-IDF location.
            ESP_IDF_PATH = "${pkgs.sleepwalker-esp-idf}";
          };
        };
    in
    flake-utils.lib.eachSystem supportedSystems perSystem // {
      # NixOS configuration for the sacrificial HID observer host (task 5.1).
      # The bootable ISO image is exposed as packages.x86_64-linux.sleepwalker-hid-observer-iso
      # from inside perSystem (so it coexists with the other sleepwalker-* packages).
      nixosConfigurations.sleepwalker-hid-observer = observerIsoSystem;
    };
}