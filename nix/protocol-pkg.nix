# sleepwalker-protocol: shared Python package for the sleepwalker HID protocol.
#
# Owns the versioned command frame layout, symbolic HID usages, opcodes,
# ACK/status values, CRC-32 logic, and golden-frame fixtures. Consumed by
# tests, the no-hardware verification command, and (later) the Android and
# firmware layers via generated constants.
{ lib
, python3
}:
let
  inherit (python3.pkgs) buildPythonPackage pytestCheckHook setuptools wheel;
in
buildPythonPackage {
  pname = "sleepwalker-protocol";
  version = "0.1.0";

  src = ../protocol;

  # The package ships a pyproject.toml (PEP 621 metadata) and uses the
  # setuptools build backend.
  format = "pyproject";

  # Build backend dependencies (setuptools + wheel) for the wheel build.
  nativeBuildInputs = [ setuptools wheel ];

  # No external runtime deps in the foundation pass; stdlib only
  # (zlib for crc32, json, struct, pathlib).
  propagatedBuildInputs = [ ];

  # Tests run via pytest during the check phase using the in-tree suite.
  nativeCheckInputs = [ pytestCheckHook ];

  # Restrict test discovery to the package test directory.
  pytestFlags = [ "src/sleepwalker_protocol/tests" ];

  pythonImportsCheck = [ "sleepwalker_protocol" ];

  meta = with lib; {
    description = "Shared sleepwalker HID protocol: frames, opcodes, golden fixtures";
    license = licenses.mit;
    platforms = platforms.linux;
  };
}