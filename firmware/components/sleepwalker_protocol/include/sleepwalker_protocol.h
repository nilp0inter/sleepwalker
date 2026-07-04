// sleepwalker_protocol.h: C mirror of the shared sleepwalker HID protocol.
//
// Constants in this file MUST agree byte-for-byte with the canonical Python
// protocol package under protocol/src/sleepwalker_protocol. The golden-frame
// fixtures (protocol/src/sleepwalker_protocol/fixtures) are the
// cross-language conformance target; any change here must keep
// decode_frame() accepting valid_*.bin and rejecting bad_crc.bin.
//
// Frame layout (little-endian):
//   +-----+-----+-----+-----+-----+-----+-----+-----+-----+----------+
//   | ver | seq_id      | opcode      | payload_len | payload ...     |
//   +-----+-----+-----+-----+-----+-----+-----+-----+-----+----------+
//   | crc32 (over ver..payload, little-endian)                        |
//   +-----+-----+-----+-----+-----+-----+-----+-----+-----+----------+
//
// CRC-32 is corruption detection only. It is NOT authorization or
// authentication. The initial authorization boundary is BLE bonding plus
// explicit firmware safety state.
#pragma once

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ---- Frame layout ----
#define SW_PROTO_VERSION          1u
#define SW_PROTO_HEADER_SIZE      7u   // ver(1)+seq(2)+op(2)+len(2)
#define SW_PROTO_CRC_SIZE         4u
#define SW_PROTO_MAX_PAYLOAD_LEN  240u
#define SW_PROTO_MAX_FRAME_SIZE   (SW_PROTO_HEADER_SIZE + SW_PROTO_MAX_PAYLOAD_LEN + SW_PROTO_CRC_SIZE)

// ---- Opcodes (mirror of opcodes.py) ----
#define SW_OPCODE_RESERVED        0x0000u
#define SW_OPCODE_ARM             0x0001u
#define SW_OPCODE_DISARM          0x0002u
#define SW_OPCODE_KILL            0x0003u
#define SW_OPCODE_RELEASE_ALL     0x0004u
#define SW_OPCODE_KEY_TAP         0x0011u
#define SW_OPCODE_KEY_DOWN        0x0012u
#define SW_OPCODE_KEY_UP          0x0013u
// Fixture-only opcode outside the known set (must be rejected).
#define SW_OPCODE_UNSUPPORTED_FIXTURE 0xFFFEu

// ---- Status / ACK values (mirror of status.py) ----
#define SW_STATUS_RECEIVED           0x01u
#define SW_STATUS_QUEUED             0x02u
#define SW_STATUS_SENT_TO_USB        0x03u
#define SW_STATUS_MALFORMED          0x10u
#define SW_STATUS_BAD_CRC            0x11u
#define SW_STATUS_DISARMED           0x12u
#define SW_STATUS_QUEUE_FULL         0x13u
#define SW_STATUS_USB_NOT_MOUNTED    0x14u
#define SW_STATUS_UNSUPPORTED_OPCODE 0x15u
#define SW_STATUS_KILLED             0x16u

// ---- Symbolic USB HID usages (mirror of usages.py) ----
// USB keyboard usage page 0x07; space key usage id 0x2c.
// Linux evdev KEY_SPACE == 57 (0x39). Used only by the observer host.
#define SW_USB_USAGE_KEY_SPACE   0x2cu
#define SW_EVDEV_KEY_SPACE       57

// ---- Decode result ----
typedef enum {
    SW_DECODE_OK = 0,
    SW_DECODE_MALFORMED,
    SW_DECODE_UNSUPPORTED_VERSION,
    SW_DECODE_BAD_CRC,
} sw_decode_result_t;

// Decoded frame. Payload is copied into the caller-provided buffer.
typedef struct {
    uint8_t  version;
    uint16_t seq_id;
    uint16_t opcode;
    uint16_t payload_len;
    uint8_t  payload[SW_PROTO_MAX_PAYLOAD_LEN];
    uint32_t crc32;
} sw_frame_t;

// Compute the CRC-32 over the header + payload (zlib/IEEE 802.3, little-endian
// trailer), matching Python zlib.crc32(...) & 0xFFFFFFFF.
uint32_t sw_proto_compute_crc(uint8_t version, uint16_t seq_id,
                              uint16_t opcode,
                              const uint8_t *payload, uint16_t payload_len);

// Encode a frame into `out`. Returns total bytes written, or 0 on overflow.
// `out_cap` must be >= SW_PROTO_HEADER_SIZE + payload_len + SW_PROTO_CRC_SIZE.
size_t sw_proto_encode(uint16_t seq_id, uint16_t opcode,
                       const uint8_t *payload, uint16_t payload_len,
                       uint8_t *out, size_t out_cap);

// Decode and validate a frame, verifying its CRC-32. On success fills `*out`
// and returns SW_DECODE_OK. On failure returns the specific error and does
// NOT touch TinyUSB / hid_bridge.
sw_decode_result_t sw_proto_decode(const uint8_t *data, size_t len,
                                   sw_frame_t *out);

// True if the opcode is recognized by the firmware.
bool sw_proto_is_known_opcode(uint16_t opcode);

#ifdef __cplusplus
}
#endif