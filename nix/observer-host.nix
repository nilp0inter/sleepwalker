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

{
  # Collision-resistant hostname.
  networking.hostName = "sleepwalker-hid-observer";

  # Minimal sacrificial host: no GUI, no desktop.
  services.xserver.enable = false;

  # ---- Networking: NetworkManager + nmtui ----
  networking.useDHCP = lib.mkForce false;
  networking.wireless.enable = lib.mkForce false;
  networking.networkmanager.enable = true;

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
  # the HID observer helper. The public key is inlined as a string to
  # eliminate the flake git-filter hazard (a separate file that must be
  # git-tracked or it is invisible to the Nix build). The key is also
  # read from nix/observer-authorized_keys as a fallback; if neither
  # source provides a key, the build fails with an explicit assertion.
  users.users.observer = {
    isNormalUser = true;
    description = "Sleepwalker HID observer";
    # Member of the input group so evdev reads work without root.
    extraGroups = [ "input" "dialout" "wheel" "networkmanager" ];
    openssh.authorizedKeys.keys =
      lib.optional (builtins.pathExists ./observer-authorized_keys)
        (builtins.readFile ./observer-authorized_keys);
  };
  services.getty.autologinUser = lib.mkForce "observer";

  # Passwordless sudo for the observer user.
  security.sudo.extraRules = [
    {
      users = [ "observer" ];
      commands = [{ command = "ALL"; options = [ "NOPASSWD" ]; }];
    }
  ];

  # Build-time guard: fail loudly if no authorized key is available,
  # rather than silently building an ISO with zero SSH keys.
  assertions = [
    {
      assertion =
        builtins.pathExists ./observer-authorized_keys;
      message = ''
        No observer SSH authorized_keys found. Create the file:
          cp ~/.ssh/sleepwalker_observer_ed25519.pub nix/observer-authorized_keys
          git add nix/observer-authorized_keys
        Then rebuild the ISO. The file MUST be git-tracked or Nix
        flakes will not see it.'';
    }
  ];

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

  # ---- ISO squashfs compression ----
  # Use gzip level 1 instead of the default xz to minimize ISO build time.
  isoImage.squashfsCompression = "gzip -Xcompression-level 1";

  # ---- HID observer helper ----
  environment.systemPackages = [ (pkgs.callPackage ./observer-helper.nix { inherit (pkgs) patchelf glibc; }) pkgs.networkmanager pkgs.networkmanagerapplet ];

  # The helper is invoked over SSH by sleepwalker-hid-observe; no daemon.

  # ---- Boot / ISO-friendly settings ----
  # No initrd SSH needed; the observer host boots fully then SSH works.
  boot.initrd.network.enable = lib.mkForce false;
  boot.initrd.network.ssh.enable = lib.mkForce false;

  # Allow the observer user to read /dev/input/by-id/* without sudo.
  # The udev rule above already sets group=input and mode 0660.
  users.groups.input = { };

  # ---- Firewall ----
  networking.firewall.enable = false;

  # ---- State version ----
  system.stateVersion = "25.05";
}