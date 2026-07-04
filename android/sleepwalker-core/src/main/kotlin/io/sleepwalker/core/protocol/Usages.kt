package io.sleepwalker.core.protocol

/**
 * Symbolic USB HID usages for the sleepwalker HID protocol.
 *
 * Mirror of firmware usages.h and protocol/src/sleepwalker_protocol/usages.py.
 * Symbolic names are used at external command boundaries instead of
 * platform-specific numeric keycodes.
 */
data class HidUsage(
    val name: String,
    val usbUsage: Int,
    val evdevCode: Int,
)

object Usages {
    val USB_KEY_SPACE: HidUsage = HidUsage(
        name = "USB_KEY_SPACE",
        usbUsage = 0x2c,
        evdevCode = 57, // Linux KEY_SPACE
    )

    val USB_KEY_NONE: HidUsage = HidUsage(
        name = "USB_KEY_NONE",
        usbUsage = 0x00,
        evdevCode = 0,
    )

    val REGISTRY: Map<String, HidUsage> = mapOf(
        USB_KEY_SPACE.name to USB_KEY_SPACE,
        USB_KEY_NONE.name to USB_KEY_NONE,
    )

    val BY_USB: Map<Int, HidUsage> =
        REGISTRY.values.filter { it.usbUsage != 0 }.associateBy { it.usbUsage }

    val BY_EVDEV: Map<Int, HidUsage> =
        REGISTRY.values.filter { it.evdevCode != 0 }.associateBy { it.evdevCode }

    fun byName(name: String): HidUsage =
        REGISTRY[name] ?: throw NoSuchElementException("unknown usage: $name")

    fun byUsb(usbUsage: Int): HidUsage? = BY_USB[usbUsage]
    fun byEvdev(evdevCode: Int): HidUsage? = BY_EVDEV[evdevCode]
}