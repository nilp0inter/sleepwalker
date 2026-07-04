// ble_uart.c: NimBLE BLE-only custom UART GATT service.
//
// RX handler contract:
//   1. Bounded copy of the received bytes.
//   2. sw_proto_decode (CRC + version + length + shape).
//   3. If decode fails, notify STATUS_MALFORMED/BAD_CRC/UNSUPPORTED_VERSION.
//   4. If opcode unknown, notify STATUS_UNSUPPORTED_OPCODE.
//   5. Else enqueue into hid_bridge (bounded, non-blocking).
//   6. Notify STATUS_RECEIVED then STATUS_QUEUED (or STATUS_QUEUE_FULL).
//   7. Return. No TinyUSB call happens here.
#include "ble_uart.h"
#include "sleepwalker_protocol.h"
#include "hid_bridge.h"
#include "safety.h"
#include "sw_log.h"
#include "host/ble_gap.h"
#include "host/ble_uuid.h"
#include "os/os_mbuf.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_att.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "host/ble_gap.h"
#include "os/os_mbuf.h"

#include <string.h>
#include <stdio.h>
int sw_gap_event(struct ble_gap_event *event, void *arg);
const char *SW_BLE_SERVICE_UUID = "0f1e2d3c-4b5a-6987-8765-4321fedcba98";
const char *SW_BLE_RX_CHAR_UUID  = "0f1e2d3c-4b5a-6987-8765-4321fedcba99";
const char *SW_BLE_TX_CHAR_UUID  = "0f1e2d3c-4b5a-6987-8765-4321fedcba9a";

static const char *TAG = "sw.ble";

static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_tx_val_handle = 0;
static sw_ble_disconnect_cb_t s_disc_cb = NULL;
static uint8_t s_own_addr_type = 0;

// ---- UUIDs (128-bit) ----
// Parse a 16-byte hex UUID string into a ble_uuid128_t.
static ble_uuid128_t s_svc_uuid;
static ble_uuid128_t s_rx_uuid;
static ble_uuid128_t s_tx_uuid;

static void init_uuid(ble_uuid128_t *u, const char *hex)
{
    u->u.type = BLE_UUID_TYPE_128;
    const uint8_t *p = (const uint8_t *)hex;
    for (int i = 0; i < 16; i++) {
        uint8_t hi, lo;
        while (*p == '-') { p++; } hi = *p++;
        while (*p == '-') { p++; } lo = *p++;
        uint8_t val = 0;
        if      (hi >= '0' && hi <= '9') val = (uint8_t)((hi - '0') << 4);
        else if (hi >= 'a' && hi <= 'f') val = (uint8_t)((hi - 'a' + 10) << 4);
        else if (hi >= 'A' && hi <= 'F') val = (uint8_t)((hi - 'A' + 10) << 4);
        if      (lo >= '0' && lo <= '9') val |= (uint8_t)(lo - '0');
        else if (lo >= 'a' && lo <= 'f') val |= (uint8_t)(lo - 'a' + 10);
        else if (lo >= 'A' && lo <= 'F') val |= (uint8_t)(lo - 'A' + 10);
        u->value[15 - i] = val;   /* NimBLE UUID bytes are little-endian */
    }
}

// ---- Status notification ----
void sw_ble_uart_notify_status(uint16_t seq_id, uint8_t status,
                               const uint8_t *ctx, uint8_t ctx_len)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_tx_val_handle == 0) {
        return;
    }
    if (ctx_len > 32u) {
        ctx_len = 32u;
    }
    uint8_t buf[2 + 1 + 1 + 32];
    buf[0] = (uint8_t)(seq_id & 0xFFu);
    buf[1] = (uint8_t)((seq_id >> 8) & 0xFFu);
    buf[2] = status;
    buf[3] = ctx_len;
    if (ctx_len != 0 && ctx != NULL) {
        memcpy(&buf[4], ctx, ctx_len);
    }
    size_t total = 4u + ctx_len;
    struct os_mbuf *om = os_msys_get(total, 0);
    if (om == NULL) {
        ESP_LOGW(TAG, "notify: os_msys_get failed");
        return;
    }
    if (os_mbuf_append(om, buf, total) != 0) {
        os_mbuf_free(om);
        return;
    }
    int rc = ble_gatts_notify_custom(s_conn_handle, s_tx_val_handle, om);
    if (rc != 0) {
        ESP_LOGW(TAG, "notify rc=%d", rc);
    }
}

bool sw_ble_uart_connected(void)
{
    return s_conn_handle != BLE_HS_CONN_HANDLE_NONE;
}

void sw_ble_uart_on_disconnect(sw_ble_disconnect_cb_t cb)
{
    s_disc_cb = cb;
}

// ---- RX write callback ----
static int sw_rx_write_cb(uint16_t conn_handle, uint16_t attr_handle,
                          struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle; (void)attr_handle; (void)arg;
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_REQ_NOT_SUPPORTED;
    }
    // Bounded read from os_mbuf chain.
    uint8_t buf[SW_PROTO_MAX_FRAME_SIZE];
    size_t total = 0;
    struct os_mbuf *om = ctxt->om;
    while (om != NULL && total < sizeof(buf)) {
        size_t chunk = om->om_len;
        if (chunk > sizeof(buf) - total) {
            chunk = sizeof(buf) - total;
        }
        memcpy(&buf[total], om->om_data, chunk);
        total += chunk;
        om = SLIST_NEXT(om, om_next);
    }
    if (total == 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    // 1. Decode.
    sw_frame_t frame;
    sw_decode_result_t dr = sw_proto_decode(buf, total, &frame);
    uint16_t seq = (dr == SW_DECODE_OK) ? frame.seq_id : 0u;
    if (dr != SW_DECODE_OK) {
        uint8_t status = (dr == SW_DECODE_BAD_CRC) ? SW_STATUS_BAD_CRC
                                                    : SW_STATUS_MALFORMED;
        char fj[64];
        snprintf(fj, sizeof(fj), "\"len\":%u,\"status\":\"%s\"",
                 (unsigned)total,
                 dr == SW_DECODE_BAD_CRC ? "bad_crc" : "malformed");
        sw_log_ble("rx", seq, fj);
        sw_ble_uart_notify_status(seq, status, NULL, 0);
        return 0;
    }

    // 2. Opcode known?
    if (!sw_proto_is_known_opcode(frame.opcode)) {
        char fj[64];
        snprintf(fj, sizeof(fj), "\"opcode\":\"0x%04X\",\"status\":\"unsupported\"",
                 frame.opcode);
        sw_log_ble("rx", seq, fj);
        sw_ble_uart_notify_status(seq, SW_STATUS_UNSUPPORTED_OPCODE, NULL, 0);
        return 0;
    }

    // 3. Received OK.
    char rxj[64];
    snprintf(rxj, sizeof(rxj), "\"opcode\":\"0x%04X\",\"len\":%u",
             frame.opcode, (unsigned)frame.payload_len);
    sw_log_ble("rx", seq, rxj);
    sw_ble_uart_notify_status(seq, SW_STATUS_RECEIVED, NULL, 0);

    // 4. Enqueue into hid_bridge (bounded, non-blocking). No TinyUSB here.
    bool ok = sw_hid_bridge_enqueue(frame.seq_id, frame.opcode,
                                    frame.payload, frame.payload_len);
    if (!ok) {
        sw_log_ble("rx", seq, "\"status\":\"queue_full\"");
        sw_ble_uart_notify_status(seq, SW_STATUS_QUEUE_FULL, NULL, 0);
        return 0;
    }
    sw_log_ble("rx", seq, "\"status\":\"queued\"");
    sw_ble_uart_notify_status(seq, SW_STATUS_QUEUED, NULL, 0);
    return 0;
}

// TX characteristic is notify-only; reads are not permitted.
static int sw_tx_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle; (void)attr_handle; (void)ctxt; (void)arg;
    /* TX char is notify-only; reads not supported. */
    return BLE_ATT_ERR_READ_NOT_PERMITTED;
}

// ---- GATT service table ----
static struct ble_gatt_svc_def s_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &s_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &s_rx_uuid.u,
                .access_cb = sw_rx_write_cb,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
                .val_handle = NULL,
            },
            {
                .uuid = &s_tx_uuid.u,
                .access_cb = sw_tx_access_cb,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_tx_val_handle,
            },
            { 0, }, // sentinel
        },
    },
    { 0, }, // sentinel
};

// ---- Advertising ----
static void start_advertising(void)
{
    struct ble_hs_adv_fields adv_fields = {0};
    struct ble_gap_adv_params adv_params = {0};
    const char *name = ble_svc_gap_device_name();

    adv_fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    adv_fields.name = (const uint8_t *)name;
    adv_fields.name_len = strlen(name);
    adv_fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&adv_fields);
    if (rc != 0) {
        ESP_LOGW(TAG, "adv_set_fields rc=%d", rc);
        return;
    }

    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER,
                           &adv_params, sw_gap_event, NULL);
    if (rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGW(TAG, "adv_start rc=%d", rc);
    }
}

// ---- GAP event handler ----
int sw_gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            sw_log_ble("connect", 0u, NULL);
        } else {
            s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        sw_log_ble("disconnect", 0u, NULL);
        if (s_disc_cb != NULL) {
            s_disc_cb();
        }
        start_advertising();
        return 0;
    case BLE_GAP_EVENT_SUBSCRIBE:
        sw_log_ble("subscribe", 0u, NULL);
        return 0;
    default:
        return 0;
    }
}

// ---- Sync callback ----
static void sw_on_sync(void)
{
    int rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) {
        ESP_LOGW(TAG, "id_infer_auto rc=%d", rc);
    }
    start_advertising();
}

static void sw_on_reset(int reason)
{
    ESP_LOGW(TAG, "nimble reset reason=%d", reason);
}

// ---- NimBLE host task ----
static void sw_host_task(void *arg)
{
    (void)arg;
    nimble_port_run();
}

void sw_ble_uart_init(void)
{
    // Initialize UUIDs.
    init_uuid(&s_svc_uuid, SW_BLE_SERVICE_UUID);
    init_uuid(&s_rx_uuid, SW_BLE_RX_CHAR_UUID);
    init_uuid(&s_tx_uuid, SW_BLE_TX_CHAR_UUID);

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init rc=%d", err);
        return;
    }

    ble_hs_cfg.sync_cb = sw_on_sync;
    ble_hs_cfg.reset_cb = sw_on_reset;

    int rc = ble_gatts_count_cfg(s_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "count_cfg rc=%d", rc);
        return;
    }
    rc = ble_gatts_add_svcs(s_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "add_svcs rc=%d", rc);
        return;
    }

    ble_svc_gap_init();
    ble_svc_gatt_init();

    rc = ble_svc_gap_device_name_set("sleepwalker");
    if (rc != 0) {
        ESP_LOGW(TAG, "device_name_set rc=%d", rc);
    }

    nimble_port_freertos_init(sw_host_task);
    sw_log_boot("ble_init", NULL);
}