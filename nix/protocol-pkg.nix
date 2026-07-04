# sleepwalker-protocol: shared Python package for the sleepwalker HID protocol.
#
# Owns the versioned command frame layout, symbolic HID usages, opcodes,
# ACK/status values, CRC-32 logic, and golden-frame fixtures. Consumed by
# tests, the no-hardware verification command, and (later) the Android and
# firmware layers via generated constants.
{ lib
, python3Packages
}:
python3Packages.buildPythonPackage {
  pname = "sleepwalker-protocol";
  version = "0.1.0";
  pyproject = false;

  src = ../protocol;

  format = "setuptools";

  nativeBuildInputs = with python3Packages; [ ];

  propagatedBuildInputs = with python3Packages; [ ];

  # No external deps in the foundation pass; stdlib only (zlib for crc32).
  dontCheck = false;

  pythonImportsCheck = [ "sleepwalker_protocol" ];

  meta = with lib; {
    description = "Shared sleepwalker HID protocol: frames, opcodes, golden fixtures";
    license = licenses.mit;
    platforms = platforms.linux;
  };
}