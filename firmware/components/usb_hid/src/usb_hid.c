// usb_hid.c: TinyUSB HID keyboard device.
//
// Descriptor: standard USB HID keyboard (usage page 0x07, 6-key rollover).
// The HID worker task is the only caller of press/release.
//
// ESP-IDF 5.5 removed in-tree tinyusb.h; the HID device stack is provided
// by the esp_tinyusb component (declared in idf_component.yml).
#include "usb_hid.h"
#include "sw_log.h"
#include "sleepwalker_protocol.h"

#include "tinyusb.h"
#include "tinyusb_default_config.h"
#include "class/hid/hid_device.h"

#include <string.h>

// HID report ID for the keyboard interface.
#define SW_HID_REPORT_ID_KEYBOARD 1u

// Standard HID keyboard report: modifier(1) + reserved(1) + keys(6).
#define SW_HID_KBD_REPORT_LEN 8u

// HID report descriptor: boot + report-mode keyboard.
static const uint8_t s_hid_report_desc[] = {
    TUD_HID_REPORT_DESC_KEYBOARD(HID_REPORT_ID(SW_HID_REPORT_ID_KEYBOARD)),
};

// String descriptors.
static const char *s_hid_string_desc[5] = {
    (char[]){0x09, 0x04},          // 0: English
    "Sleepwalker",                 // 1: Manufacturer
    "Sleepwalker HID Keyboard",    // 2: Product
    "000001",                      // 3: Serial
    "Sleepwalker HID interface",   // 4: HID
};

#define TUSB_DESC_TOTAL_LEN  (TUD_CONFIG_DESC_LEN + CFG_TUD_HID * TUD_HID_DESC_LEN)

// Configuration descriptor: 1 config, 1 HID interface.
static const uint8_t s_hid_config_desc[] = {
    TUD_CONFIG_DESCRIPTOR(1, 1, 0, TUSB_DESC_TOTAL_LEN,
                          TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP, 100),
    TUD_HID_DESCRIPTOR(0, 4, false, sizeof(s_hid_report_desc),
                       0x81, 16, 10),
};

void sw_usb_hid_init(void)
{
    tinyusb_config_t tusb_cfg = TINYUSB_DEFAULT_CONFIG();
    tusb_cfg.descriptor.device = NULL;
    tusb_cfg.descriptor.full_speed_config = s_hid_config_desc;
    tusb_cfg.descriptor.string = s_hid_string_desc;
    tusb_cfg.descriptor.string_count =
        sizeof(s_hid_string_desc) / sizeof(s_hid_string_desc[0]);
#if (TUD_OPT_HIGH_SPEED)
    tusb_cfg.descriptor.high_speed_config = s_hid_config_desc;
#endif
    ESP_ERROR_CHECK(tinyusb_driver_install(&tusb_cfg));
    sw_log_boot("usb_init", NULL);
}

bool sw_usb_hid_ready(void)
{
    return tud_mounted();
}

bool sw_usb_hid_keyboard_press(uint8_t usb_usage)
{
    if (!sw_usb_hid_ready()) {
        return false;
    }
    // Standard 8-byte boot keyboard report: modifier=0, reserved=0, 1 key.
    uint8_t report[SW_HID_KBD_REPORT_LEN];
    memset(report, 0, sizeof(report));
    report[2] = usb_usage; // first key slot
    return tud_hid_report(SW_HID_REPORT_ID_KEYBOARD, report, sizeof(report));
}

bool sw_usb_hid_keyboard_release(void)
{
    if (!sw_usb_hid_ready()) {
        return false;
    }
    uint8_t report[SW_HID_KBD_REPORT_LEN];
    memset(report, 0, sizeof(report));
    return tud_hid_report(SW_HID_REPORT_ID_KEYBOARD, report, sizeof(report));
}

// ---- Required TinyUSB HID callbacks ----

// Return the HID report descriptor for the given instance.
uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance)
{
    (void)instance;
    return s_hid_report_desc;
}

// GET_REPORT control request — return 0 to stall (not needed for boot keyboard).
uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t report_id,
                               hid_report_type_t report_type, uint8_t *buffer,
                               uint16_t reqlen)
{
    (void)instance; (void)report_id; (void)report_type;
    (void)buffer; (void)reqlen;
    return 0;
}

// SET_REPORT control request — no-op for boot keyboard.
void tud_hid_set_report_cb(uint8_t instance, uint8_t report_id,
                           hid_report_type_t report_type,
                           uint8_t const *buffer, uint16_t bufsize)
{
    (void)instance; (void)report_id; (void)report_type;
    (void)buffer; (void)bufsize;
}