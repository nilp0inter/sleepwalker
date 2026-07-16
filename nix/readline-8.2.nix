# GNU Readline 8.2p13, pinned for the HIL text-identity fixture.
#
# nixpkgs (nixos-25.05) only ships readline 8.3 and readline 7.0; the
# fixture advertises "gnu-readline 8.2" as its identity, so it must
# link against the actual 8.2 release with all 13 upstream patches
# applied.  This derivation is a faithful reproduction of the
# (now-removed) nixpkgs readline 8.2 package: same tarball, same
# patch set (mirror://gnu/readline/readline-8.2-patches/readline82-NNN),
# same nixpkgs-local link-against-ncurses and no-arch_only patches.
#
# The fixture's JSON control ABI reports READLINE_IDENTITY="gnu-readline 8.2";
# the version reported here (8.2p13) is the evidence surfaced by the Nix
# build and by `ldd`/readelf on the linked binary.
{
  lib,
  stdenv,
  fetchurl,
  updateAutotoolsGnuConfigScriptsHook,
  ncurses,
  termcap,
  curses-library ? if stdenv.hostPlatform.isWindows then termcap else ncurses,
}:

stdenv.mkDerivation (finalAttrs: {
  pname = "readline";
  version = "8.2p${toString (builtins.length finalAttrs.upstreamPatches)}";

  src = fetchurl {
    url = "mirror://gnu/readline/readline-${finalAttrs.meta.branch}.tar.gz";
    hash = "sha256-P+txcfFqhO6CyhijbXub4QmlLAT0kqBTMx19EJUAfDU=";
  };

  outputs = [
    "out"
    "dev"
    "man"
    "doc"
    "info"
  ];

  strictDeps = true;
  propagatedBuildInputs = [ curses-library ];
  nativeBuildInputs = [ updateAutotoolsGnuConfigScriptsHook ];

  patchFlags = [ "-p0" ];

  # Upstream patch set: readline82-001 .. readline82-013.
  # Hashes captured from the nixpkgs readline 8.2p13 derivation that
  # was previously built in this store (nixos-25.05 predecessor).
  upstreamPatches =
    let
      patch =
        nr: hash:
        fetchurl {
          url = "mirror://gnu/readline/readline-${finalAttrs.meta.branch}-patches/readline82-${nr}";
          inherit hash;
        };
    in
    [
      (patch "001" "sha256-u/l/HsQKkp7ataqBmYweLvQ1Q2xZd1SRbmpYaPJzr/c=")
      (patch "002" "sha256-4GUDgixi97wNnzh9THjAngzlblOHIBE2PHR4bHzUwFM=")
      (patch "003" "sha256-JPWHuka0btKxhozK+ZR1BP66FUu4+qvUra6mPvfmrLA=")
      (patch "004" "sha256-eVcu6uuCr9xoadetTLqdT1GbEhgHDhf6kLvs1JvVJaw=")
      (patch "005" "sha256-Yiujh9rlwYWvtLmyBjSATl9sHG5eh+vufDWo8GURTJk=")
      (patch "006" "sha256-x7Rf+MDSTYFILm4Gd+gVY9E8dCQfe4bE3gDSObyB9aE=")
      (patch "007" "sha256-WRGluYDXkAqr2+5IP4batwVoUeZADvsAJ3agpKG6tvY=")
      (patch "008" "sha256-oXftydjJ+C6MGdBjCrNR8/0bIB1lWh3bXVHEzuGXsmo=")
      (patch "009" "sha256-PZiF5pLhmYUj/Vxh9VjOzSqv1noHvTv+HXrVoxd3oRY=")
      (patch "010" "sha256-dY4uxloMIUz+YWH1zePFr0N3xn2CDqAdE948oWX2e0w=")
      (patch "011" "sha256-4AE9kH86nmSCzAk03hvYLuPDxP0HqWRqqYma8jdUTdc=")
      (patch "012" "sha256-bIrfjtSiymKff9ETAe1ik6YkjJ2gxnT4YhffcV78y9M=")
      (patch "013" "sha256-HqQ0lX1uw6e2F2Px81UtrQ691nVNZYiLXNbYDbOniKg=")
    ];

  patches =
    lib.optionals (curses-library.pname == "ncurses") [
      # Reuse the nixpkgs-local patch for linking against ncurses.
      # It is identical across readline 6.3..8.3 and ships with nixpkgs.
      (fetchurl {
        name = "link-against-ncurses.patch";
        url = "https://raw.githubusercontent.com/NixOS/nixpkgs/nixos-25.05/pkgs/development/libraries/readline/link-against-ncurses.patch";
        hash = "sha256-4s5SPEsA8yPghcG4HQlDL6l6ZdPLEWtqDNp4e55iVCQ=";
      })
    ]
    ++ [
      (fetchurl {
        name = "no-arch_only-8.2.patch";
        url = "https://raw.githubusercontent.com/NixOS/nixpkgs/nixos-25.05/pkgs/development/libraries/readline/no-arch_only-8.2.patch";
        hash = "sha256-08h/UMpRXQwupHieQ3ixmR7FuSsNaCMIkeO4v3JZijo=";
      })
    ]
    ++ finalAttrs.upstreamPatches;

  # This install error is caused by a very old libtool.  We can't
  # autoreconfHook this package, so this is the best we've got.
  postInstall = lib.optionalString stdenv.hostPlatform.isOpenBSD ''
    ln -s $out/lib/libhistory.so* $out/lib/libhistory.so
    ln -s $out/lib/libreadline.so* $out/lib/libreadline.so
  '';

  meta = {
    description = "Library for interactive line editing (GNU Readline 8.2, pinned for sleepwalker HIL)";
    homepage = "https://savannah.gnu.org/projects/readline/";
    license = lib.licenses.gpl3Plus;
    maintainers = [ ];
    platforms = lib.platforms.unix ++ lib.platforms.windows;
    branch = "8.2";
  };
})