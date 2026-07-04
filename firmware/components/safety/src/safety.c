// safety.c: mutex-protected safety state machine.
#include "safety.h"
#include "sleepwalker_protocol.h"
#include <string.h>

static sw_safety_state_t s_state = SW_SAFETY_DISARMED;
static StaticSemaphore_t s_mutex_storage;
static SemaphoreHandle_t s_mutex = NULL;
static uint32_t s_last_activity_ms = 0;

static void lock(void)
{
    if (s_mutex != NULL) {
        xSemaphoreTake(s_mutex, portMAX_DELAY);
    }
}

static void unlock(void)
{
    if (s_mutex != NULL) {
        xSemaphoreGive(s_mutex);
    }
}

void sw_safety_init(void)
{
    if (s_mutex == NULL) {
        s_mutex = xSemaphoreCreateMutexStatic(&s_mutex_storage);
    }
    lock();
    s_state = SW_SAFETY_DISARMED;
    s_last_activity_ms = 0;
    unlock();
}

sw_safety_state_t sw_safety_apply(uint16_t opcode)
{
    lock();
    switch (opcode) {
    case SW_OPCODE_ARM:
        if (s_state == SW_SAFETY_DISARMED) {
            s_state = SW_SAFETY_ARMED;
            s_last_activity_ms = xTaskGetTickCount() * portTICK_PERIOD_MS;
        }
        // KILLED stays KILLED; ARMED stays ARMED.
        break;
    case SW_OPCODE_DISARM:
        s_state = SW_SAFETY_DISARMED;
        break;
    case SW_OPCODE_KILL:
        // Always accepted from bonded central. KILLED then DISARMED.
        s_state = SW_SAFETY_DISARMED;
        break;
    case SW_OPCODE_RELEASE_ALL:
        // Valid in ARMED; caller emits USB release report. State unchanged.
        if (s_state == SW_SAFETY_ARMED) {
            s_last_activity_ms = xTaskGetTickCount() * portTICK_PERIOD_MS;
        }
        break;
    case SW_OPCODE_KEY_TAP:
    case SW_OPCODE_KEY_DOWN:
    case SW_OPCODE_KEY_UP:
        // Only valid in ARMED. Caller checks sw_safety_injection_allowed().
        if (s_state == SW_SAFETY_ARMED) {
            s_last_activity_ms = xTaskGetTickCount() * portTICK_PERIOD_MS;
        }
        break;
    default:
        // Unknown opcode: no state change.
        break;
    }
    sw_safety_state_t result = s_state;
    unlock();
    return result;
}

bool sw_safety_injection_allowed(void)
{
    lock();
    bool ok = (s_state == SW_SAFETY_ARMED);
    unlock();
    return ok;
}

sw_safety_state_t sw_safety_state(void)
{
    lock();
    sw_safety_state_t s = s_state;
    unlock();
    return s;
}

void sw_safety_force_disarm(void)
{
    lock();
    s_state = SW_SAFETY_DISARMED;
    unlock();
}

void sw_safety_refresh(void)
{
    lock();
    s_last_activity_ms = xTaskGetTickCount() * portTICK_PERIOD_MS;
    unlock();
}

bool sw_safety_check_timeout(uint32_t now_ms, uint32_t armed_timeout_ms)
{
    lock();
    if (s_state != SW_SAFETY_ARMED) {
        unlock();
        return false;
    }
    uint32_t elapsed = now_ms - s_last_activity_ms;
    if (elapsed >= armed_timeout_ms) {
        s_state = SW_SAFETY_DISARMED;
        unlock();
        return true;
    }
    unlock();
    return false;
}