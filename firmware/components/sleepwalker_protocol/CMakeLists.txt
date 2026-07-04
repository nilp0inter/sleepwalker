# sleepwalker_protocol: C mirror of the shared Python protocol package.
#
# Owns the versioned command frame layout, opcodes, status values, and
# symbolic HID usages. Constants MUST agree byte-for-byte with the
# canonical fixtures checked into protocol/src/sleepwalker_protocol/fixtures
# (cross-layer fixture compatibility is enforced by the HIL verification
# pass).
idf_component_register(
  SRCS "src/sleepwalker_protocol.c"
  INCLUDE_DIRS "include"
)