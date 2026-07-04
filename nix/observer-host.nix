# sleepwalker-hid-observer: sacrificial NixOS HID observer host config.
#
# Builds a bootable NixOS ISO that:
#   - enables SSH for a dedicated observer user with key-based access
#   - configures udev/input permissions for stable ESP32-S3 HID discovery
#   - installs the sleepwalker-hid-observer helper (JSONL evdev events)
#   - uses a collision-resistant hostname
#
# Consumed by the flake output sleepwalker-hid-observer-iso (task 5.2).
{ config, pkgs, lib, ... }:

let
  # The HID observer helper is built from the local nix/observer-helper.nix.
  observerHelper = pkgs.callPackage ./observer-helper.nix { };
in
{
  # Collision-resistant hostname.
  networking.hostName = "sleepwalker-hid-observer";

  # Minimal sacrificial host: no GUI, no desktop, network via DHCP.
  services.xserver.enable = false;

  # ---- SSH (noninteractive key-based access) ----
  services.openssh = {
    enable = true;
    settings = {
      PermitRootLogin = "no";
      PasswordAuthentication = false;
      KbdInteractiveAuthentication = false;
    };
  };

  # Dedicated observer user. The harness SSHes in as this user to run
  # the HID observer helper. Authorized keys are injected via the ISO
  # build (see observer-iso.nix) from a file in the repo or an env var.
  users.users.observer = {
    isNormalUser = true;
    description = "Sleepwalker HID observer";
    # Member of the input group so evdev reads work without root.
    extraGroups = [ "input" "dialout" ];
    # Authorized keys are populated by the ISO build (observer-iso.nix).
    openssh.authorizedKeys.keyFiles =
      lib.optionals (builtins.pathExists ./observer-authorized_keys)
        [ ./observer-authorized_keys ];
  };

  # ---- Input device permissions and stable discovery ----
  # udev rule: tag ESP32-S3 HID keyboards with a stable symlink under
  # /dev/input/by-id so the observer helper can match by descriptor
  # information (VID/PID/product) rather than unstable /dev/input/eventX.
  # VID 303A is Espressif's USB vendor ID; PID 4001 is the sleepwalker
  # keyboard product id (matches bench.example.toml defaults).
  services.udev.extraRules = ''
    # ESP32-S3 sleepwalker HID keyboard -> stable symlink + group input.
    KERNEL=="event*", SUBSYSTEM=="input", \
      ATTRS{idVendor}=="303a", ATTRS{idProduct}=="4001", \
      GROUP="input", MODE="0660", \
      SYMLINK+="input/by-id/sleepwalker-hid-keyboard"
  '';

  # ---- HID observer helper ----
  environment.systemPackages = [ observerHelper ];

  # The helper is invoked over SSH by sleepwalker-hid-observe; no daemon.

  # ---- Boot / ISO-friendly settings ----
  # No initrd SSH needed; the observer host boots fully then SSH works.
  boot.initrd.network.enable = lib.mkForce false;
  boot.initrd.network.ssh.enable = lib.mkForce false;

  # Allow the observer user to read /dev/input/by-id/* without sudo.
  # The udev rule above already sets group=input and mode 0660.
  users.groups.input = { };

  # ---- Networking (DHCP) ----
  networking.useDHCP = true;
  networking.firewall.enable = false;

  # ---- State version ----
  system.stateVersion = "25.05";
}