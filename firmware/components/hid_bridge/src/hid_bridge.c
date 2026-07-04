// hid_bridge.c: FreeRTOS queue backing the BLE->HID bridge.
#include "hid_bridge.h"
#include <string.h>

static StaticQueue_t s_queue_storage;
static uint8_t s_queue_buf[SW_HID_BRIDGE_QUEUE_LEN * sizeof(sw_hid_bridge_item_t)];
static QueueHandle_t s_queue = NULL;

void sw_hid_bridge_init(void)
{
    if (s_queue != NULL) {
        return;
    }
    s_queue = xQueueCreateStatic(SW_HID_BRIDGE_QUEUE_LEN,
                                 sizeof(sw_hid_bridge_item_t),
                                 s_queue_buf, &s_queue_storage);
}

bool sw_hid_bridge_enqueue(uint16_t seq_id, uint16_t opcode,
                           const uint8_t *payload, uint16_t payload_len)
{
    if (s_queue == NULL) {
        return false;
    }
    if (payload_len > SW_HID_BRIDGE_PAYLOAD_MAX) {
        return false;
    }
    sw_hid_bridge_item_t item;
    item.seq_id = seq_id;
    item.opcode = opcode;
    item.payload_len = payload_len;
    if (payload_len != 0 && payload != NULL) {
        memcpy(item.payload, payload, payload_len);
    }
    // Non-blocking enqueue from BLE context: must never block the stack.
    return xQueueSend(s_queue, &item, 0u) == pdTRUE;
}

bool sw_hid_bridge_dequeue(sw_hid_bridge_item_t *out, uint32_t timeout_ms)
{
    if (s_queue == NULL || out == NULL) {
        return false;
    }
    return xQueueReceive(s_queue, out, pdMS_TO_TICKS(timeout_ms)) == pdTRUE;
}

uint32_t sw_hid_bridge_pending(void)
{
    if (s_queue == NULL) {
        return 0u;
    }
    return (uint32_t)uxQueueMessagesWaiting(s_queue);
}