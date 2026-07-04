# sleepwalker-protocol-check: no-hardware protocol verification command.
#
# Wraps the shared protocol test suite (golden-frame fixtures + frame
# round-trip checks) so the coding agent can run it without touching any
# hardware. This is the foundation-pass verification primitive.
{ lib, writeShellScriptBin, sleepwalker-protocol, python3 }:
let
  pytestEnv = python3.withPackages (ps: [ ps.pytest ]);
in
writeShellScriptBin "sleepwalker-protocol-check" ''
  exec ${pytestEnv}/bin/python3 -m pytest \
    ${sleepwalker-protocol}/${python3.sitePackages}/sleepwalker_protocol/tests \
    -q -p no:cacheprovider \
    --rootdir=${sleepwalker-protocol}/${python3.sitePackages}/sleepwalker_protocol/tests
''