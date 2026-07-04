// ble_uart.h: BLE-only custom UART-style GATT service.
//
// Service UUID (128-bit, custom): sleepwalker NUS-style.
//   RX char: Android -> ESP writes (command frames).
//   TX char: ESP -> Android notifications (status events, correlated by seq id).
//
// The RX handler is bounded: it validates the frame, enqueues into
// hid_bridge, sends an immediate parse/queue status notification, and
// returns without performing any TinyUSB call.
#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Custom 128-bit service/characteristic UUIDs (sleepwalker-specific).
// These are randomly-generated, version 4, non-reserved UUIDs.
//   Service:        0f1e2d3c-4b5a-6987-8765-4321fedcba98
//   RX (write):     0f1e2d3c-4b5a-6987-8765-4321fedcba99
//   TX (notify):    0f1e2d3c-4b5a-6987-8765-4321fedcba9a
extern const char *SW_BLE_SERVICE_UUID;
extern const char *SW_BLE_RX_CHAR_UUID;
extern const char *SW_BLE_TX_CHAR_UUID;

// Initialize BLE stack, advertise the custom UART service, register the
// RX/TX characteristics, and start advertising. Called once from app_main.
void sw_ble_uart_init(void);

// Send a status notification on the TX characteristic.
// `seq_id` correlates the status to the originating command frame.
// `status` is a SW_STATUS_* value. Additional context bytes (optional)
// are appended after the seq_id and status byte.
//
// Notification payload layout (little-endian):
//   seq_id (uint16) + status (uint8) + ctx_len (uint8) + ctx[ctx_len]
void sw_ble_uart_notify_status(uint16_t seq_id, uint8_t status,
                               const uint8_t *ctx, uint8_t ctx_len);

// True if a bonded central is currently connected.
bool sw_ble_uart_connected(void);

// Register a callback invoked when the central disconnects (so safety
// can force-disarm and release-all).
typedef void (*sw_ble_disconnect_cb_t)(void);
void sw_ble_uart_on_disconnect(sw_ble_disconnect_cb_t cb);

#ifdef __cplusplus
}
#endif