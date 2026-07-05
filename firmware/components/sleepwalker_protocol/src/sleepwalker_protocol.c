// sleepwalker_protocol.c: C mirror of the shared Python protocol package.
//
// CRC-32 uses zlib.crc32 semantics (IEEE 802.3 polynomial, reflected,
// init 0xFFFFFFFF, final xor 0xFFFFFFFF) so firmware, Android, and the
// Python fixtures agree byte-for-byte.
#include "sleepwalker_protocol.h"
#include <string.h>

// zlib.crc32 table (IEEE 802.3, reflected). Generated at runtime on first
// use to avoid a 1KiB const table; cost is negligible on ESP32-S3.
static uint32_t crc_table[256];
static bool crc_table_ready = false;

static void crc_table_init(void)
{
    if (crc_table_ready) {
        return;
    }
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) {
            c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        }
        crc_table[i] = c;
    }
    crc_table_ready = true;
}

uint32_t sw_proto_compute_crc(uint8_t version, uint16_t seq_id,
                              uint16_t opcode,
                              const uint8_t *payload, uint16_t payload_len)
{
    crc_table_init();
    uint32_t crc = 0xFFFFFFFFu;
    // Header: ver, seq_id (LE), opcode (LE), payload_len (LE).
    uint8_t hdr[SW_PROTO_HEADER_SIZE];
    hdr[0] = version;
    hdr[1] = (uint8_t)(seq_id & 0xFFu);
    hdr[2] = (uint8_t)((seq_id >> 8) & 0xFFu);
    hdr[3] = (uint8_t)(opcode & 0xFFu);
    hdr[4] = (uint8_t)((opcode >> 8) & 0xFFu);
    hdr[5] = (uint8_t)(payload_len & 0xFFu);
    hdr[6] = (uint8_t)((payload_len >> 8) & 0xFFu);
    for (size_t i = 0; i < sizeof(hdr); i++) {
        crc = crc_table[(crc ^ hdr[i]) & 0xFFu] ^ (crc >> 8);
    }
    for (uint16_t i = 0; i < payload_len; i++) {
        crc = crc_table[(crc ^ payload[i]) & 0xFFu] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFFu;
}

size_t sw_proto_encode(uint16_t seq_id, uint16_t opcode,
                       const uint8_t *payload, uint16_t payload_len,
                       uint8_t *out, size_t out_cap)
{
    if (payload_len > SW_PROTO_MAX_PAYLOAD_LEN) {
        return 0;
    }
    size_t need = SW_PROTO_HEADER_SIZE + payload_len + SW_PROTO_CRC_SIZE;
    if (out_cap < need) {
        return 0;
    }
    uint32_t crc = sw_proto_compute_crc(SW_PROTO_VERSION, seq_id, opcode,
                                        payload, payload_len);
    out[0] = SW_PROTO_VERSION;
    out[1] = (uint8_t)(seq_id & 0xFFu);
    out[2] = (uint8_t)((seq_id >> 8) & 0xFFu);
    out[3] = (uint8_t)(opcode & 0xFFu);
    out[4] = (uint8_t)((opcode >> 8) & 0xFFu);
    out[5] = (uint8_t)(payload_len & 0xFFu);
    out[6] = (uint8_t)((payload_len >> 8) & 0xFFu);
    if (payload_len != 0 && payload != NULL) {
        memcpy(&out[SW_PROTO_HEADER_SIZE], payload, payload_len);
    }
    out[SW_PROTO_HEADER_SIZE + payload_len + 0] = (uint8_t)(crc & 0xFFu);
    out[SW_PROTO_HEADER_SIZE + payload_len + 1] = (uint8_t)((crc >> 8) & 0xFFu);
    out[SW_PROTO_HEADER_SIZE + payload_len + 2] = (uint8_t)((crc >> 16) & 0xFFu);
    out[SW_PROTO_HEADER_SIZE + payload_len + 3] = (uint8_t)((crc >> 24) & 0xFFu);
    return need;
}

sw_decode_result_t sw_proto_decode(const uint8_t *data, size_t len,
                                   sw_frame_t *out)
{
    if (len < SW_PROTO_HEADER_SIZE + SW_PROTO_CRC_SIZE) {
        return SW_DECODE_MALFORMED;
    }
    uint8_t version = data[0];
    uint16_t seq_id = (uint16_t)(data[1] | (data[2] << 8));
    uint16_t opcode = (uint16_t)(data[3] | (data[4] << 8));
    uint16_t payload_len = (uint16_t)(data[5] | (data[6] << 8));
    size_t expected_total = SW_PROTO_HEADER_SIZE + payload_len + SW_PROTO_CRC_SIZE;
    if (len != expected_total) {
        return SW_DECODE_MALFORMED;
    }
    if (payload_len > SW_PROTO_MAX_PAYLOAD_LEN) {
        return SW_DECODE_MALFORMED;
    }
    if (version != SW_PROTO_VERSION) {
        return SW_DECODE_UNSUPPORTED_VERSION;
    }
    const uint8_t *payload = &data[SW_PROTO_HEADER_SIZE];
    uint32_t crc_carried = (uint32_t)data[SW_PROTO_HEADER_SIZE + payload_len + 0]
        | ((uint32_t)data[SW_PROTO_HEADER_SIZE + payload_len + 1] << 8)
        | ((uint32_t)data[SW_PROTO_HEADER_SIZE + payload_len + 2] << 16)
        | ((uint32_t)data[SW_PROTO_HEADER_SIZE + payload_len + 3] << 24);
    uint32_t crc_computed = sw_proto_compute_crc(version, seq_id, opcode,
                                                 payload, payload_len);
    if (crc_carried != crc_computed) {
        return SW_DECODE_BAD_CRC;
    }
    out->version = version;
    out->seq_id = seq_id;
    out->opcode = opcode;
    out->payload_len = payload_len;
    if (payload_len != 0) {
        memcpy(out->payload, payload, payload_len);
    }
    out->crc32 = crc_carried;
    return SW_DECODE_OK;
}

bool sw_proto_is_known_opcode(uint16_t opcode)
{
    switch (opcode) {
    case SW_OPCODE_ARM:
    case SW_OPCODE_DISARM:
    case SW_OPCODE_KILL:
    case SW_OPCODE_RELEASE_ALL:
    case SW_OPCODE_KEY_TAP:
    case SW_OPCODE_KEY_DOWN:
    case SW_OPCODE_KEY_UP:
    case SW_OPCODE_MOUSE_REL_REPORT:
        return true;
    default:
        return false;
    }
}

const char *sw_proto_namespace_for(uint16_t opcode)
{
    if (opcode == SW_OPCODE_RESERVED) {
        return NULL;
    }
    if (opcode >= SW_NS_SAFETY_LO && opcode <= SW_NS_SAFETY_HI) {
        return "safety";
    }
    if (opcode >= SW_NS_KEYBOARD_LO && opcode <= SW_NS_KEYBOARD_HI) {
        return "keyboard";
    }
    if (opcode >= SW_NS_REL_MOUSE_LO && opcode <= SW_NS_REL_MOUSE_HI) {
        return "relative_mouse";
    }
    if (opcode >= SW_NS_ABS_POINTER_LO && opcode <= SW_NS_ABS_POINTER_HI) {
        return "absolute_pointer_reserved";
    }
    if (opcode >= SW_NS_SERIAL_LO && opcode <= SW_NS_SERIAL_HI) {
        return "serial_reserved";
    }
    if (opcode >= SW_NS_CAPABILITY_LO && opcode <= SW_NS_CAPABILITY_HI) {
        return "capability_reserved";
    }
    if (opcode >= SW_NS_PRIVATE_LO && opcode <= SW_NS_PRIVATE_HI) {
        return "private_fixture";
    }
    return NULL;
}

bool sw_proto_is_reserved_future(uint16_t opcode)
{
    const char *ns = sw_proto_namespace_for(opcode);
    return ns != NULL &&
           (strcmp(ns, "absolute_pointer_reserved") == 0 ||
            strcmp(ns, "serial_reserved") == 0 ||
            strcmp(ns, "capability_reserved") == 0);
}

bool sw_proto_decode_mouse_rel(const uint8_t *payload, uint16_t payload_len,
                               uint8_t *out_buttons,
                               int8_t *out_dx, int8_t *out_dy,
                               int8_t *out_wheel, int8_t *out_pan)
{
    if (payload == NULL || payload_len != SW_MOUSE_REL_PAYLOAD_LEN) {
        return false;
    }
    if (out_buttons == NULL || out_dx == NULL || out_dy == NULL ||
        out_wheel == NULL || out_pan == NULL) {
        return false;
    }
    *out_buttons = payload[0];
    *out_dx = (int8_t)payload[1];
    *out_dy = (int8_t)payload[2];
    *out_wheel = (int8_t)payload[3];
    *out_pan = (int8_t)payload[4];
    return true;
}