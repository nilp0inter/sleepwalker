# sleepwalker-protocol-check: no-hardware protocol verification command.
#
# Runs the shared protocol test suite (golden-frame fixtures + frame
# round-trip checks) without touching any hardware. This is the
# foundation-pass verification primitive.
{ lib, python3, python3Packages, stdenvNoCC }:
let
  protocolPkg = python3Packages.callPackage ./protocol-pkg.nix { };
in
stdenvNoCC.mkDerivation {
  pname = "sleepwalker-protocol-check";
  version = "0.1.0";

  dontUnpack = true;
  dontBuild = true;

  buildInputs = [ python3 protocolPkg ];

  installPhase = ''
    runHook preInstall
    mkdir -p $out/bin
    cat > $out/bin/sleepwalker-protocol-check <<EOF
    #!${python3}/bin/python3
    import sys
    import pytest
    sys.exit(pytest.main([
      "${protocolPkg}/${python3.sitePackages}/sleepwalker_protocol/tests",
      "-q",
      "--rootdir=${protocolPkg}/${python3.sitePackages}/sleepwalker_protocol/tests",
    ]))
    EOF
    chmod +x $out/bin/sleepwalker-protocol-check
    runHook postInstall
  '';

  meta = with lib; {
    description = "No-hardware sleepwalker shared-protocol verification command";
    mainProgram = "sleepwalker-protocol-check";
    platforms = platforms.linux;
  };
}