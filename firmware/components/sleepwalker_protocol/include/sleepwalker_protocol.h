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

// ---- Opcode namespaces (mirror of opcodes.py) ----
//   0x0000          - reserved / invalid
//   0x0001..0x000F  - safety/control
//   0x0010..0x00FF  - keyboard HID
//   0x0100..0x01FF  - relative mouse HID
//   0x0200..0x02FF  - future absolute pointer HID (reserved)
//   0x0300..0x03FF  - future virtual serial / USB CDC (reserved)
//   0x0400..0x04FF  - future capabilities/configuration (reserved)
//   0xF000..0xFFFF  - private/test/invalid fixtures
#define SW_OPCODE_RESERVED             0x0000u

// ---- Safety/control namespace: 0x0001..0x000F ----
#define SW_OPCODE_ARM                  0x0001u
#define SW_OPCODE_DISARM               0x0002u
#define SW_OPCODE_KILL                 0x0003u
#define SW_OPCODE_RELEASE_ALL          0x0004u

// ---- Keyboard HID namespace: 0x0010..0x00FF ----
#define SW_OPCODE_KEY_TAP              0x0011u
#define SW_OPCODE_KEY_DOWN             0x0012u
#define SW_OPCODE_KEY_UP               0x0013u

// ---- Relative mouse HID namespace: 0x0100..0x01FF ----
// Raw relative mouse report. Payload is exactly five bytes:
// buttons:u8, dx:i8, dy:i8, wheel:i8, pan:i8.
#define SW_OPCODE_MOUSE_REL_REPORT     0x0100u

// ---- Reserved future namespace bases ----
#define SW_OPCODE_ABS_POINTER_BASE     0x0200u
#define SW_OPCODE_SERIAL_BASE          0x0300u
#define SW_OPCODE_CAPABILITY_BASE      0x0400u

// ---- Namespace range bounds ----
#define SW_NS_SAFETY_LO                0x0001u
#define SW_NS_SAFETY_HI                0x000Fu
#define SW_NS_KEYBOARD_LO              0x0010u
#define SW_NS_KEYBOARD_HI              0x00FFu
#define SW_NS_REL_MOUSE_LO             0x0100u
#define SW_NS_REL_MOUSE_HI             0x01FFu
#define SW_NS_ABS_POINTER_LO           0x0200u
#define SW_NS_ABS_POINTER_HI           0x02FFu
#define SW_NS_SERIAL_LO                0x0300u
#define SW_NS_SERIAL_HI                0x03FFu
#define SW_NS_CAPABILITY_LO            0x0400u
#define SW_NS_CAPABILITY_HI            0x04FFu
#define SW_NS_PRIVATE_LO               0xF000u
#define SW_NS_PRIVATE_HI               0xFFFFu

// Fixture-only opcode outside the known set (must be rejected).
#define SW_OPCODE_UNSUPPORTED_FIXTURE  0xFFFEu
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

// True if the opcode is recognized by the firmware (implemented dispatch).
bool sw_proto_is_known_opcode(uint16_t opcode);

// Classify an opcode into its device-class namespace. Returns a stable
// string name (e.g. "safety", "keyboard", "relative_mouse",
// "absolute_pointer_reserved", "serial_reserved", "capability_reserved",
// "private_fixture"), or NULL if the opcode is reserved/invalid (0x0000)
// or outside all defined ranges.
const char *sw_proto_namespace_for(uint16_t opcode);

// True if the opcode lies in a reserved-but-unimplemented namespace
// (absolute pointer, virtual serial, capabilities/configuration).
// Frames carrying these opcodes decode successfully but are rejected by
// current dispatch with STATUS_UNSUPPORTED_OPCODE.
bool sw_proto_is_reserved_future(uint16_t opcode);

// ---- Relative mouse report payload (mirror of opcodes.py / mouse.py) ----
// Raw relative mouse report payload is exactly five bytes:
//   buttons:u8, dx:i8, dy:i8, wheel:i8, pan:i8
#define SW_MOUSE_REL_PAYLOAD_LEN  5u

// Button mask bits for the relative mouse report buttons byte.
// Bit 0 = left, bit 1 = right, bit 2 = middle. Other bits reserved.
#define SW_MOUSE_BUTTON_LEFT      0x01u
#define SW_MOUSE_BUTTON_RIGHT     0x02u
#define SW_MOUSE_BUTTON_MIDDLE    0x04u

// Decode the five-byte raw relative mouse payload from a frame payload.
// Returns true on success (payload_len == 5), false otherwise. The dx,
// dy, wheel, and pan values are sign-extended to int8_t.
bool sw_proto_decode_mouse_rel(const uint8_t *payload, uint16_t payload_len,
                               uint8_t *out_buttons,
                               int8_t *out_dx, int8_t *out_dy,
                               int8_t *out_wheel, int8_t *out_pan);
#ifdef __cplusplus
}
#endif