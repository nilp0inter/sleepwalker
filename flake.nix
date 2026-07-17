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

    # OmniKeymap keyboard layout database (JSON source for runtime keymap loading).
    # Fetched as a non-flake input so --override-input works for local dev.
    omni-keymap = {
      url = "github:nilp0inter/OmniKeymap/main";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, flake-utils, nixpkgs-esp-dev, android-nixpkgs, omni-keymap }:
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
        sleepwalker-editor-conformance-check =
          final.callPackage ./nix/editor-conformance-check.nix { };

        sleepwalker-bench-validate = final.callPackage ./nix/bench-validate.nix { };
        sleepwalker-fw-build = final.callPackage ./nix/fw-build.nix {
          sleepwalker-esp-idf = final.sleepwalker-esp-idf;
        };
        sleepwalker-fw-flash = final.callPackage ./nix/fw-flash.nix {
          sleepwalker-esp-idf = final.sleepwalker-esp-idf;
        };
        sleepwalker-fw-flash-usb = final.callPackage ./nix/fw-flash-usb.nix { };
        sleepwalker-fw-uart = final.callPackage ./nix/fw-uart.nix { };

        sleepwalker-apk-build = final.callPackage ./nix/apk-build.nix {
          jdk17 = final.jdk17;
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0
            cmdline-tools-11-0
            platform-tools
            platforms-android-34
          ]);
          keymapDb = omni-keymap;
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
        sleepwalker-adb-mouse-click = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-mouse-click;
        sleepwalker-adb-mouse-move = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-mouse-move;
        sleepwalker-adb-mouse-scroll = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-mouse-scroll;
        sleepwalker-adb-mouse-release = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-mouse-release;
        sleepwalker-adb-type-text = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-type-text;
        sleepwalker-adb-type-text-encoded = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-type-text-encoded;
        sleepwalker-adb-set-text-encoded = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-set-text-encoded;
        sleepwalker-adb-reset-editor = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-reset-editor;
        sleepwalker-adb-launch-readline = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-launch-readline;
        sleepwalker-adb-input-text = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-input-text;
        sleepwalker-adb-keyevent = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-keyevent;
        sleepwalker-adb-keycombination = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-keycombination;
        sleepwalker-adb-dismiss-keyguard = (final.callPackage ./nix/adb-ops.nix {
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        }).sleepwalker-adb-dismiss-keyguard;
        sleepwalker-hid-observe = final.callPackage ./nix/hid-observe.nix { };
        sleepwalker-text-sink-start = final.callPackage ./nix/text-sink-start.nix { };
        sleepwalker-text-sink-ctl = final.callPackage ./nix/text-sink-ctl.nix { };
        sleepwalker-text-sink-read = final.callPackage ./nix/text-sink-read.nix { };
        sleepwalker-observer-prepare = final.callPackage ./nix/observer-prepare.nix { };
        sleepwalker-readline-fixture = final.callPackage ./nix/readline-fixture.nix {
          readline82 = final.callPackage ./nix/readline-8.2.nix { };
        };
        sleepwalker-readline-keymap = final.callPackage ./nix/readline-keymap.nix { };
        sleepwalker-readline-fixture-start = final.callPackage ./nix/readline-fixture-start.nix { };
        sleepwalker-readline-fixture-ctl = final.callPackage ./nix/readline-fixture-ctl.nix { };
        sleepwalker-human-gate = final.callPackage ./nix/human-gate.nix { };
        sleepwalker-esp-reset = final.callPackage ./nix/esp-reset.nix { };
        sleepwalker-artifacts = final.callPackage ./nix/artifacts.nix { };
        sleepwalker-smoke-keyboard = final.callPackage ./nix/smoke-keyboard.nix {
          inherit (final) sleepwalker-bench-validate sleepwalker-fw-uart
            sleepwalker-adb-logcat sleepwalker-hid-observe
            sleepwalker-adb-connect sleepwalker-adb-arm
            sleepwalker-adb-inject-key sleepwalker-adb-release-all
            sleepwalker-adb-kill sleepwalker-esp-reset;
        };
        sleepwalker-smoke-text = final.callPackage ./nix/smoke-text.nix {
          inherit (final) sleepwalker-bench-validate sleepwalker-fw-uart
            sleepwalker-adb-logcat sleepwalker-hid-observe
            sleepwalker-adb-connect sleepwalker-adb-arm
            sleepwalker-adb-type-text sleepwalker-adb-release-all
            sleepwalker-adb-kill sleepwalker-esp-reset;
          androidSdk = final.sleepwalker-android-sdk (sdkPkgs: with sdkPkgs; [
            build-tools-34-0-0 cmdline-tools-11-0 platform-tools platforms-android-34
          ]);
        };
        sleepwalker-smoke-text-identity = final.callPackage ./nix/smoke-text-identity.nix {
          inherit (final) sleepwalker-bench-validate sleepwalker-fw-uart
            sleepwalker-adb-logcat sleepwalker-hid-observe
            sleepwalker-adb-connect sleepwalker-adb-arm
            sleepwalker-adb-type-text-encoded sleepwalker-adb-release-all
            sleepwalker-adb-kill sleepwalker-esp-reset
            sleepwalker-observer-prepare sleepwalker-text-sink-start
            sleepwalker-text-sink-read sleepwalker-text-sink-ctl;
          python3 = final.python3.withPackages (ps: with ps; [ hypothesis tomli ]);
        };
        sleepwalker-smoke-mouse = final.callPackage ./nix/smoke-mouse.nix {
          inherit (final) sleepwalker-bench-validate sleepwalker-fw-uart
            sleepwalker-adb-logcat sleepwalker-hid-observe
            sleepwalker-adb-connect sleepwalker-adb-arm
            sleepwalker-adb-mouse-click sleepwalker-adb-mouse-move
            sleepwalker-adb-mouse-release sleepwalker-adb-kill
            sleepwalker-esp-reset;
        };
        sleepwalker-smoke-composite = final.callPackage ./nix/smoke-composite.nix {
          inherit (final) sleepwalker-bench-validate sleepwalker-fw-uart
            sleepwalker-adb-logcat sleepwalker-hid-observe
            sleepwalker-adb-connect sleepwalker-adb-arm
            sleepwalker-adb-inject-key sleepwalker-adb-release-all
            sleepwalker-adb-mouse-click sleepwalker-adb-mouse-move
            sleepwalker-adb-mouse-release sleepwalker-adb-kill
            sleepwalker-esp-reset;
        };
        sleepwalker-smoke-editor-conformance = final.callPackage ./nix/smoke-editor-conformance.nix {
          inherit (final) sleepwalker-bench-validate sleepwalker-esp-reset
            sleepwalker-fw-uart sleepwalker-adb-logcat sleepwalker-hid-observe
            sleepwalker-adb-connect sleepwalker-adb-arm
            sleepwalker-adb-set-text-encoded sleepwalker-adb-reset-editor
            sleepwalker-adb-inject-key
            sleepwalker-adb-release-all sleepwalker-adb-kill
            sleepwalker-adb-launch-readline
            sleepwalker-adb-input-text sleepwalker-adb-keyevent
            sleepwalker-adb-keycombination sleepwalker-adb-dismiss-keyguard
            sleepwalker-readline-fixture-start sleepwalker-readline-fixture-ctl;
          python3 = final.python3.withPackages (ps: with ps; [ hypothesis ]);
        };
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
            sleepwalker-editor-conformance-check =
              pkgs.sleepwalker-editor-conformance-check;
            sleepwalker-bench-validate = pkgs.sleepwalker-bench-validate;
            sleepwalker-protocol = pkgs.sleepwalker-protocol;
            sleepwalker-fw-build = pkgs.sleepwalker-fw-build;
            sleepwalker-fw-flash = pkgs.sleepwalker-fw-flash;
            sleepwalker-fw-flash-usb = pkgs.sleepwalker-fw-flash-usb;
            sleepwalker-fw-uart = pkgs.sleepwalker-fw-uart;
            sleepwalker-apk-build = pkgs.sleepwalker-apk-build;
            sleepwalker-apk-install = pkgs.sleepwalker-apk-install;
            sleepwalker-adb-logcat = pkgs.sleepwalker-adb-logcat;
            sleepwalker-adb-status = pkgs.sleepwalker-adb-status;
            sleepwalker-adb-connect = pkgs.sleepwalker-adb-connect;
            sleepwalker-adb-arm = pkgs.sleepwalker-adb-arm;
            sleepwalker-adb-inject-key = pkgs.sleepwalker-adb-inject-key;
            sleepwalker-adb-release-all = pkgs.sleepwalker-adb-release-all;
            sleepwalker-adb-mouse-move = pkgs.sleepwalker-adb-mouse-move;
            sleepwalker-adb-mouse-scroll = pkgs.sleepwalker-adb-mouse-scroll;
            sleepwalker-adb-mouse-release = pkgs.sleepwalker-adb-mouse-release;
            sleepwalker-adb-type-text = pkgs.sleepwalker-adb-type-text;
            sleepwalker-adb-type-text-encoded = pkgs.sleepwalker-adb-type-text-encoded;
            sleepwalker-adb-set-text-encoded = pkgs.sleepwalker-adb-set-text-encoded;
            sleepwalker-adb-reset-editor = pkgs.sleepwalker-adb-reset-editor;
            sleepwalker-adb-launch-readline = pkgs.sleepwalker-adb-launch-readline;
            sleepwalker-adb-input-text = pkgs.sleepwalker-adb-input-text;
            sleepwalker-adb-keyevent = pkgs.sleepwalker-adb-keyevent;
            sleepwalker-adb-keycombination = pkgs.sleepwalker-adb-keycombination;
            sleepwalker-adb-dismiss-keyguard = pkgs.sleepwalker-adb-dismiss-keyguard;
            sleepwalker-hid-observe = pkgs.sleepwalker-hid-observe;
            sleepwalker-text-sink-start = pkgs.sleepwalker-text-sink-start;
            sleepwalker-text-sink-ctl = pkgs.sleepwalker-text-sink-ctl;
            sleepwalker-text-sink-read = pkgs.sleepwalker-text-sink-read;
            sleepwalker-observer-prepare = pkgs.sleepwalker-observer-prepare;
            sleepwalker-readline-fixture = pkgs.sleepwalker-readline-fixture;
            sleepwalker-readline-fixture-start = pkgs.sleepwalker-readline-fixture-start;
            sleepwalker-readline-fixture-ctl = pkgs.sleepwalker-readline-fixture-ctl;
            sleepwalker-human-gate = pkgs.sleepwalker-human-gate;
            sleepwalker-esp-reset = pkgs.sleepwalker-esp-reset;
            sleepwalker-artifacts = pkgs.sleepwalker-artifacts;
            sleepwalker-smoke-keyboard = pkgs.sleepwalker-smoke-keyboard;
            sleepwalker-smoke-text = pkgs.sleepwalker-smoke-text;
            sleepwalker-smoke-text-identity = pkgs.sleepwalker-smoke-text-identity;
            sleepwalker-smoke-mouse = pkgs.smoke-mouse or pkgs.sleepwalker-smoke-mouse;
            sleepwalker-smoke-composite = pkgs.sleepwalker-smoke-composite;
            sleepwalker-smoke-editor-conformance = pkgs.sleepwalker-smoke-editor-conformance;
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
            # Side-effectful: flash firmware to ESP32-S3 over native USB.
            sleepwalker-fw-flash-usb = {
              type = "app";
              program = "${pkgs.sleepwalker-fw-flash-usb}/bin/sleepwalker-fw-flash-usb";
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
            sleepwalker-adb-mouse-click = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-mouse-click}/bin/sleepwalker-adb-mouse-click";
            };
            sleepwalker-adb-mouse-move = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-mouse-move}/bin/sleepwalker-adb-mouse-move";
            };
            sleepwalker-adb-mouse-scroll = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-mouse-scroll}/bin/sleepwalker-adb-mouse-scroll";
            };
            sleepwalker-adb-mouse-release = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-mouse-release}/bin/sleepwalker-adb-mouse-release";
            };
            sleepwalker-adb-kill = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-kill}/bin/sleepwalker-adb-kill";
            };

            sleepwalker-adb-set-text-encoded = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-set-text-encoded}/bin/sleepwalker-adb-set-text-encoded";
            };
            sleepwalker-adb-reset-editor = {
              type = "app";
              program = "${pkgs.sleepwalker-adb-reset-editor}/bin/sleepwalker-adb-reset-editor";
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

            # Readline fixture: start on observer host over SSH.
            sleepwalker-readline-fixture-start = {
              type = "app";
              program = "${pkgs.sleepwalker-readline-fixture-start}/bin/sleepwalker-readline-fixture-start";
            };

            # Readline fixture: control operations over SSH.
            sleepwalker-readline-fixture-ctl = {
              type = "app";
              program = "${pkgs.sleepwalker-readline-fixture-ctl}/bin/sleepwalker-readline-fixture-ctl";
            };

            # Composed keyboard-only smoke scenario (task 6.8).
            sleepwalker-smoke-keyboard = {
              type = "app";
              program = "${pkgs.sleepwalker-smoke-keyboard}/bin/sleepwalker-smoke-keyboard";
            };
            # Composed relative mouse smoke scenario (task 5.2).
            sleepwalker-smoke-mouse = {
              type = "app";
              program = "${pkgs.sleepwalker-smoke-mouse}/bin/sleepwalker-smoke-mouse";
            };
            # Composed composite keyboard+mouse smoke scenario.
            sleepwalker-smoke-composite = {
              type = "app";
              program = "${pkgs.sleepwalker-smoke-composite}/bin/sleepwalker-smoke-composite";
            };
            sleepwalker-smoke-editor-conformance = {
              type = "app";
              program = "${pkgs.sleepwalker-smoke-editor-conformance}/bin/sleepwalker-smoke-editor-conformance";
            };

            # ESP32-S3 hardware reset via UART RTS pulse.
            sleepwalker-esp-reset = {
              type = "app";
              program = "${pkgs.sleepwalker-esp-reset}/bin/sleepwalker-esp-reset";
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
              pkgs.sleepwalker-fw-flash-usb
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