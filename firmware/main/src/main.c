// main.c: sleepwalker ESP32-S3 firmware entry point.
//
// Component wiring order:
//   1. sw_log (auxiliary UART)
//   2. hid_bridge (queue)
//   3. safety (DISARMED)
//   4. usb_hid (TinyUSB composite keyboard + mouse)
//   5. ble_uart (NimBLE custom UART service)
//   6. HID worker task (owns TinyUSB report emission + safety checks)
//
// The HID worker is the ONLY component that calls usb_hid press/release.
#include "sw_log.h"
#include "hid_bridge.h"
#include "safety.h"
#include "ble_uart.h"
#include "usb_hid.h"
#include "sleepwalker_protocol.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include <string.h>
#include <stdio.h>

static const char *TAG = "sw.main";

// ARMED watchdog: return to DISARMED after this many ms of inactivity.
#define SW_ARMED_TIMEOUT_MS  30000u
#define SW_WORKER_STACK      4096u
#define SW_WORKER_PRIO       5u

// Keyboard tap scripts must hold every report state longer than the HID
// endpoint poll interval (10 ms) or Linux may miss release states and coalesce
// consecutive taps. 15 ms keeps throughput high while making each press and
// release state observable by the host.
#define SW_TAP_SCRIPT_PRESS_MS 15u
#define SW_TAP_SCRIPT_GAP_MS   15u

// Emit a release-all for both keyboard and mouse.
static void sw_release_all(void)
{
    sw_usb_hid_keyboard_release();
    sw_usb_hid_mouse_release();
}

// HID worker: dequeues from hid_bridge, applies safety, emits USB reports,
// and notifies command lifecycle status on BLE TX.
static void sw_hid_worker_task(void *arg)
{
    (void)arg;
    sw_log_cmd("worker_start", 0u, NULL);
    while (1) {
        sw_hid_bridge_item_t item;
        if (!sw_hid_bridge_dequeue(&item, 100u)) {
            // Idle: check ARMED timeout.
            uint32_t now = xTaskGetTickCount() * portTICK_PERIOD_MS;
            if (sw_safety_check_timeout(now, SW_ARMED_TIMEOUT_MS)) {
                sw_log_safety("timeout", NULL);
                sw_release_all();
                sw_ble_uart_notify_status(0u, SW_STATUS_DISARMED, NULL, 0);
            }
            continue;
        }

        // Apply safety transition for non-injection opcodes.
        sw_safety_state_t st = sw_safety_apply(item.opcode);

        switch (item.opcode) {
        case SW_OPCODE_ARM:
            sw_log_cmd("arm", item.seq_id, NULL);
            sw_ble_uart_notify_status(item.seq_id, SW_STATUS_QUEUED, NULL, 0);
            break;
        case SW_OPCODE_DISARM:
            sw_log_cmd("disarm", item.seq_id, NULL);
            sw_release_all();
            sw_ble_uart_notify_status(item.seq_id, SW_STATUS_SENT_TO_USB, NULL, 0);
            break;
        case SW_OPCODE_KILL:
            sw_log_cmd("kill", item.seq_id, NULL);
            sw_release_all();
            sw_ble_uart_notify_status(item.seq_id, SW_STATUS_SENT_TO_USB, NULL, 0);
            break;
        case SW_OPCODE_RELEASE_ALL:
            sw_log_cmd("release_all", item.seq_id, NULL);
            if (sw_safety_injection_allowed()) {
                sw_release_all();
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_SENT_TO_USB, NULL, 0);
            } else {
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_DISARMED, NULL, 0);
            }
            break;
        case SW_OPCODE_KEYBOARD_TAP_SCRIPT: {
            if (!sw_safety_injection_allowed()) {
                uint8_t status = (st == SW_SAFETY_KILLED) ? SW_STATUS_KILLED
                                                          : SW_STATUS_DISARMED;
                char fj[48];
                snprintf(fj, sizeof(fj), "\"status\":\"%s\"",
                         status == SW_STATUS_KILLED ? "killed" : "disarmed");
                sw_log_cmd("reject", item.seq_id, fj);
                sw_ble_uart_notify_status(item.seq_id, status, NULL, 0);
                break;
            }
            if (!sw_usb_hid_ready()) {
                sw_log_cmd("reject", item.seq_id, "\"status\":\"usb_not_mounted\"");
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_USB_NOT_MOUNTED, NULL, 0);
                break;
            }

            uint8_t count = (item.payload_len >= 1u) ? item.payload[0] : 0u;
            bool aborted = false;

            for (uint8_t i = 0; i < count; i++) {
                uint32_t now = xTaskGetTickCount() * portTICK_PERIOD_MS;
                sw_hid_bridge_item_t next_item;
                bool has_abort_op = false;
                if (sw_hid_bridge_peek(&next_item)) {
                    if (next_item.opcode == SW_OPCODE_DISARM || next_item.opcode == SW_OPCODE_KILL) {
                        has_abort_op = true;
                    }
                }

                if (!sw_safety_injection_allowed() ||
                    sw_safety_check_timeout(now, SW_ARMED_TIMEOUT_MS) ||
                    has_abort_op) {
                    aborted = true;
                    break;
                }

                sw_safety_refresh();

                uint8_t modifiers = item.payload[1 + 2 * i];
                uint8_t usage = item.payload[2 + 2 * i];

                sw_usb_hid_keyboard_report(modifiers, usage);
                vTaskDelay(pdMS_TO_TICKS(SW_TAP_SCRIPT_PRESS_MS));
                sw_usb_hid_keyboard_release();
                vTaskDelay(pdMS_TO_TICKS(SW_TAP_SCRIPT_GAP_MS));
            }

            if (aborted) {
                sw_release_all();
                sw_log_cmd("abort", item.seq_id, NULL);
            } else {
                sw_log_cmd("sent_to_usb", item.seq_id, NULL);
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_SENT_TO_USB, NULL, 0);
            }
            break;
        }
        case SW_OPCODE_KEY_TAP:
        case SW_OPCODE_KEY_DOWN:
        case SW_OPCODE_KEY_UP: {
            if (!sw_safety_injection_allowed()) {
                uint8_t status = (st == SW_SAFETY_KILLED) ? SW_STATUS_KILLED
                                                          : SW_STATUS_DISARMED;
                char fj[48];
                snprintf(fj, sizeof(fj), "\"status\":\"%s\"",
                         status == SW_STATUS_KILLED ? "killed" : "disarmed");
                sw_log_cmd("reject", item.seq_id, fj);
                sw_ble_uart_notify_status(item.seq_id, status, NULL, 0);
                break;
            }
            if (!sw_usb_hid_ready()) {
                sw_log_cmd("reject", item.seq_id, "\"status\":\"usb_not_mounted\"");
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_USB_NOT_MOUNTED, NULL, 0);
                break;
            }
            // Payload: one byte USB usage (or empty for KEY_UP release-all).
            uint8_t usage = (item.payload_len >= 1u) ? item.payload[0] : 0u;
            if (item.opcode == SW_OPCODE_KEY_UP) {
                if (item.payload_len == 0u) {
                    sw_usb_hid_keyboard_release();
                } else {
                    // KEY_UP of a specific usage: we can only release all
                    // in the boot report model, so emit a release.
                    sw_usb_hid_keyboard_release();
                }
            } else {
                // KEY_DOWN or KEY_TAP: press.
                sw_usb_hid_keyboard_press(usage);
                if (item.opcode == SW_OPCODE_KEY_TAP) {
                    // Small delay then release for a clean tap.
                    vTaskDelay(pdMS_TO_TICKS(10));
                    sw_usb_hid_keyboard_release();
                }
            }
            sw_log_cmd("sent_to_usb", item.seq_id, NULL);
            sw_ble_uart_notify_status(item.seq_id, SW_STATUS_SENT_TO_USB, NULL, 0);
            break;
        }
        case SW_OPCODE_MOUSE_REL_REPORT: {
            // Validate payload length before HID dispatch.
            uint8_t buttons = 0;
            int8_t dx = 0, dy = 0, wheel = 0, pan = 0;
            bool ok = sw_proto_decode_mouse_rel(
                item.payload, item.payload_len,
                &buttons, &dx, &dy, &wheel, &pan);
            if (!ok) {
                char fj[64];
                snprintf(fj, sizeof(fj),
                         "\"len\":%u,\"status\":\"malformed\"",
                         (unsigned)item.payload_len);
                sw_log_cmd("mouse_reject", item.seq_id, fj);
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_MALFORMED,
                                          NULL, 0);
                break;
            }
            if (!sw_safety_injection_allowed()) {
                uint8_t status = (st == SW_SAFETY_KILLED) ? SW_STATUS_KILLED
                                                          : SW_STATUS_DISARMED;
                char fj[64];
                snprintf(fj, sizeof(fj),
                         "\"buttons\":%u,\"status\":\"%s\"",
                         buttons,
                         status == SW_STATUS_KILLED ? "killed" : "disarmed");
                sw_log_cmd("mouse_reject", item.seq_id, fj);
                sw_ble_uart_notify_status(item.seq_id, status, NULL, 0);
                break;
            }
            if (!sw_usb_hid_ready()) {
                sw_log_cmd("mouse_reject", item.seq_id,
                           "\"status\":\"usb_not_mounted\"");
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_USB_NOT_MOUNTED,
                                          NULL, 0);
                break;
            }
            sw_log_cmd("mouse_rx", item.seq_id, NULL);
            bool emitted = sw_usb_hid_mouse_rel_report(buttons, dx, dy, wheel, pan);
            if (emitted) {
                char fj[96];
                snprintf(fj, sizeof(fj),
                         "\"buttons\":%u,\"dx\":%d,\"dy\":%d,\"wheel\":%d,\"pan\":%d",
                         buttons, (int)dx, (int)dy, (int)wheel, (int)pan);
                sw_log_usb("mouse_sent", item.seq_id, fj);
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_SENT_TO_USB,
                                          NULL, 0);
            } else {
                sw_log_usb("mouse_send_failed", item.seq_id, NULL);
                sw_ble_uart_notify_status(item.seq_id, SW_STATUS_USB_NOT_MOUNTED,
                                          NULL, 0);
            }
            break;
        }
        default:
            sw_log_cmd("unsupported", item.seq_id, NULL);
            sw_ble_uart_notify_status(item.seq_id, SW_STATUS_UNSUPPORTED_OPCODE, NULL, 0);
            break;
        }
    }
}

// BLE disconnect callback: force disarm + release-all (keyboard + mouse).
static void sw_on_ble_disconnect(void)
{
    sw_safety_force_disarm();
    sw_release_all();
    sw_log_safety("force_disarm", "\"reason\":\"ble_disconnect\"");
}

void app_main(void)
{
    esp_err_t nerr = nvs_flash_init();
    if (nerr == ESP_ERR_NVS_NO_FREE_PAGES || nerr == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        nerr = nvs_flash_init();
    }
    ESP_ERROR_CHECK(nerr);
    sw_log_init();
    sw_log_boot("boot", NULL);

    sw_hid_bridge_init();
    sw_safety_init();
    sw_usb_hid_init();

    sw_ble_uart_on_disconnect(sw_on_ble_disconnect);
    sw_ble_uart_init();

    // Spawn the HID worker task that owns TinyUSB report emission.
    xTaskCreate(sw_hid_worker_task, "sw_hid_worker", SW_WORKER_STACK,
                NULL, SW_WORKER_PRIO, NULL);

    sw_log_boot("ready", NULL);
    ESP_LOGI(TAG, "sleepwalker firmware ready");
}
