// hid_bridge.h: thread-safe queue mediating BLE RX -> HID worker.
//
// NimBLE GATT write handlers perform bounded work (validate, copy, enqueue)
// and return without calling TinyUSB. A dedicated HID worker task owns
// TinyUSB report emission. This prevents BLE stack stalls, TinyUSB
// reentrancy bugs, and unsafe cross-context HID writes.
#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "sleepwalker_protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

// Fixed-size queue item. Payload is bounded by the protocol max payload,
// but keyboard/mouse commands are tiny (1 byte usage). We keep a small
// inline buffer to avoid per-item allocation.
#define SW_HID_BRIDGE_PAYLOAD_MAX 240u
#define SW_HID_BRIDGE_QUEUE_LEN   16u

typedef struct {
    uint16_t seq_id;
    uint16_t opcode;
    uint16_t payload_len;
    uint8_t  payload[SW_HID_BRIDGE_PAYLOAD_MAX];
} sw_hid_bridge_item_t;

// Initialize the bridge queue. Must be called once before any enqueue/dequeue.
void sw_hid_bridge_init(void);

// Try to enqueue a command. Returns true on success, false if the queue is
// full (caller should emit STATUS_QUEUE_FULL). Copies up to
// SW_HID_BRIDGE_PAYLOAD_MAX bytes of payload; longer payloads are rejected.
bool sw_hid_bridge_enqueue(uint16_t seq_id, uint16_t opcode,
                           const uint8_t *payload, uint16_t payload_len);

// Blocking dequeue into `*out`. Returns true on success. Called by the HID
// worker task.
bool sw_hid_bridge_dequeue(sw_hid_bridge_item_t *out, uint32_t timeout_ms);

// Current number of items in the queue (diagnostics).
uint32_t sw_hid_bridge_pending(void);

// Peek at the next item in the queue without removing it. Returns true on success.
bool sw_hid_bridge_peek(sw_hid_bridge_item_t *out);

#ifdef __cplusplus
}
#endif