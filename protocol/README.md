# sleepwalker-protocol

Shared binary contract for the sleepwalker hardware-in-the-loop system.

Defines:
- the versioned command frame layout (version, sequence id, opcode,
  payload length, payload, CRC-32);
- symbolic USB HID usages and the canonical `USB_KEY_SPACE` mapping to
  USB keyboard usage `0x2c` and Linux evdev `KEY_SPACE`;
- opcode constants for arm, disarm, kill, release-all, and key injection;
- ACK/status values for received, queued, sent-to-USB, malformed frame,
  bad CRC, disarmed, queue full, USB not mounted, and unsupported opcode;
- golden-frame fixtures for valid `USB_KEY_SPACE`, bad CRC, unsupported
  opcode, arm, disarm, kill, and release-all cases;
- no-hardware tests that decode/encode frames and verify fixtures.

This package is the single source of truth consumed by Android, firmware,
and the HIL harness. CRC-32 is corruption detection only and is NOT
authorization or authentication.