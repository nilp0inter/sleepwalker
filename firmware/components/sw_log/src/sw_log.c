// sw_log.c: structured JSONL over the auxiliary UART.
//
// Uses ESP-IDF uart driver to write one JSON object per line to UART0.
// Kept minimal: no dynamic allocation, bounded field length.
#include "sw_log.h"
#include "driver/uart.h"
#include "esp_log.h"
#include <stdio.h>
#include <string.h>
#include <stdarg.h>

#define SW_LOG_UART_NUM   UART_NUM_0
#define SW_LOG_BUF_LEN    256

static bool s_ready = false;

void sw_log_init(void)
{
    if (s_ready) {
        return;
    }
    uart_config_t cfg = {
        .baud_rate  = 115200,
        .data_bits  = UART_DATA_8_BITS,
        .parity     = UART_PARITY_DISABLE,
        .stop_bits  = UART_STOP_BITS_1,
        .flow_ctrl  = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    // UART0 is already used by the IDF console; just configure it.
    uart_param_config(SW_LOG_UART_NUM, &cfg);
    esp_err_t uerr = uart_driver_install(SW_LOG_UART_NUM, 256, 0, 0, NULL, 0);
    if (uerr != ESP_OK && uerr != ESP_ERR_INVALID_STATE) {
        ESP_LOGE("sw_log", "uart_driver_install rc=%d", uerr);
        return;  /* leave s_ready false */
    }
    s_ready = true;
}

static void emit(const char *component, const char *event,
                 uint16_t seq_id, const char *fields_json)
{
    if (!s_ready) {
        sw_log_init();
    }
    char buf[SW_LOG_BUF_LEN];
    uint32_t ts = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
    int n;
    if (fields_json != NULL && fields_json[0] != '\0') {
        n = snprintf(buf, sizeof(buf),
                     "{\"ts_ms\":%lu,\"component\":\"%s\",\"event\":\"%s\","
                     "\"seq\":%u,%s}\n",
                     (unsigned long)ts, component, event, seq_id, fields_json);
    } else {
        n = snprintf(buf, sizeof(buf),
                     "{\"ts_ms\":%lu,\"component\":\"%s\",\"event\":\"%s\","
                     "\"seq\":%u}\n",
                     (unsigned long)ts, component, event, seq_id);
    }
    if (n < 0) {
        return;
    }
    if ((size_t)n > sizeof(buf) - 1u) {
        n = sizeof(buf) - 1u;
    }
    uart_write_bytes(SW_LOG_UART_NUM, buf, (size_t)n);
}

void sw_log_event(const char *component, const char *event,
                  uint16_t seq_id, const char *fields_json)
{
    emit(component, event, seq_id, fields_json);
}

void sw_log_boot(const char *event, const char *fields_json)
{
    emit("boot", event, 0u, fields_json);
}

void sw_log_ble(const char *event, uint16_t seq_id, const char *fields_json)
{
    emit("ble", event, seq_id, fields_json);
}

void sw_log_safety(const char *event, const char *fields_json)
{
    emit("safety", event, 0u, fields_json);
}

void sw_log_bridge(const char *event, uint16_t seq_id, const char *fields_json)
{
    emit("bridge", event, seq_id, fields_json);
}

void sw_log_usb(const char *event, uint16_t seq_id, const char *fields_json)
{
    emit("usb", event, seq_id, fields_json);
}

void sw_log_cmd(const char *event, uint16_t seq_id, const char *fields_json)
{
    emit("cmd", event, seq_id, fields_json);
}