// safety.h: firmware safety state machine.
//
// Contract (mirror of opcodes.py safety notes):
//   - Boots DISARMED.
//   - HID injection is rejected with STATUS_DISARMED until ARM is accepted.
//   - KILL is always accepted from a bonded central and forces release-all
//     + return to DISARMED.
//   - A timeout returns to DISARMED.
//   - BLE disconnect forces release-all + return to DISARMED.
//   - DISARM releases all keys/buttons.
#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    SW_SAFETY_DISARMED = 0,
    SW_SAFETY_ARMED    = 1,
    SW_SAFETY_KILLED   = 2,
} sw_safety_state_t;

// Initialize the safety state. Boots DISARMED.
void sw_safety_init(void);

// Apply an opcode-driven transition. Returns the resulting state.
//   ARM       -> DISARMED/ARMED -> ARMED; KILLED -> KILLED (rejected).
//   DISARM    -> any -> DISARMED.
//   KILL      -> any -> KILLED then DISARMED (caller should release-all).
//   KEY_*     -> only valid in ARMED; otherwise returns DISARMED/KILLED
//                unchanged and the caller emits STATUS_DISARMED/KILLED.
sw_safety_state_t sw_safety_apply(uint16_t opcode);

// True if a HID injection opcode is currently permitted (state == ARMED).
bool sw_safety_injection_allowed(void);

// Current state snapshot.
sw_safety_state_t sw_safety_state(void);

// Force disarmed (BLE disconnect, timeout). Signals release-all to caller.
void sw_safety_force_disarm(void);

// Reset the ARMED timeout watchdog. Called when any valid activity occurs.
void sw_safety_refresh(void);

// Check and apply the ARMED timeout. Returns true if a timeout fired and
// the state transitioned ARMED -> DISARMED (caller should release-all).
bool sw_safety_check_timeout(uint32_t now_ms, uint32_t armed_timeout_ms);

#ifdef __cplusplus
}
#endif