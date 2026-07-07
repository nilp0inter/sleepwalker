package io.sleepwalker.core.text

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.KeymapDatabase
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.Usages

/**
 * Structured text rendering failure.
 *
 * The library reports structured failures when text cannot be
 * represented by the selected host profile rather than silently emitting
 * approximate or incorrect keystrokes.
 */
sealed class TextRenderingFailure {
    /** The host profile is absent from the bundled keymap database. */
    data class MissingLayout(val profile: HostProfile) : TextRenderingFailure()

    /** A specific glyph cannot be represented by the host profile. */
    data class UnrepresentableGlyph(val ch: Char, val profile: HostProfile) :
        TextRenderingFailure()
}

/**
 * The result of high-level text planning.
 *
 * On success, [plan] holds an inspectable sequence of low-level
 * keyboard operations. On failure, [failure] holds the structured
 * rendering failure and no HID operations are emitted.
 */
data class TextPlan(
    val plan: List<LowLevelOp>?,
    val failure: TextRenderingFailure? = null,
) {
    val ok: Boolean get() = plan != null && failure == null
}

/**
 * High-level text planning API.
 *
 * Translates requested text into an inspectable execution plan of
 * low-level keyboard operations for the selected host profile. The
 * high-level API composes low-level primitives rather than bypassing
 * them: each planned operation is produced by [LowLevelHid].
 *
 * @property database  the bundled keymap database (defaults to the seed).
 * @property hid       the low-level HID primitive API.
 */
class TextPlanner(
    private val database: KeymapDatabase = SeedKeymapDatabase,
    private val hid: LowLevelHid,
) {
    private fun getModifierUsages(modifiers: Int): List<HidUsage> {
        val list = ArrayList<HidUsage>(8)
        if ((modifiers and 0x01) != 0) list.add(Usages.USB_KEY_LEFTCTRL)
        if ((modifiers and 0x02) != 0) list.add(Usages.USB_KEY_LEFTSHIFT)
        if ((modifiers and 0x04) != 0) list.add(Usages.USB_KEY_LEFTALT)
        if ((modifiers and 0x08) != 0) list.add(Usages.USB_KEY_LEFTMETA)
        if ((modifiers and 0x10) != 0) list.add(Usages.USB_KEY_RIGHTCTRL)
        if ((modifiers and 0x20) != 0) list.add(Usages.USB_KEY_RIGHTSHIFT)
        if ((modifiers and 0x40) != 0) list.add(Usages.USB_KEY_RIGHTALT)
        if ((modifiers and 0x80) != 0) list.add(Usages.USB_KEY_RIGHTMETA)
        return list
    }

    /**
     * Plan text input for the given host profile.
     *
     * Returns a [TextPlan] with an inspectable list of low-level
     * keyboard operations (key down/up/tap). If the text contains a
     * glyph that cannot be represented, returns a structured
     * [TextRenderingFailure.UnrepresentableGlyph] and emits no HID
     * operations for that request.
     */
    fun plan(text: String, profile: HostProfile): TextPlan {
        val entries = database.lookup(profile)
            ?: return TextPlan(plan = null, failure = TextRenderingFailure.MissingLayout(profile))

        val entryMap = entries.associateBy { it.ch }
        val ops = ArrayList<LowLevelOp>(text.length * 3)
        var currentModifiers = 0

        for (ch in text) {
            val entry = entryMap[ch]
                ?: return TextPlan(plan = null, failure = TextRenderingFailure.UnrepresentableGlyph(ch, profile))

            for (tap in entry.taps) {
                val requiredModifiers = tap.modifiers
                if (currentModifiers != requiredModifiers) {
                    val toRelease = currentModifiers and requiredModifiers.inv()
                    val toPress = requiredModifiers and currentModifiers.inv()

                    // Release modifiers in reverse order (e.g. RightMeta before LeftCtrl)
                    val releaseUsages = getModifierUsages(toRelease).asReversed()
                    for (mod in releaseUsages) {
                        ops.add(hid.keyUp(mod))
                    }

                    // Press modifiers in forward order
                    val pressUsages = getModifierUsages(toPress)
                    for (mod in pressUsages) {
                        ops.add(hid.keyDown(mod))
                    }

                    currentModifiers = requiredModifiers
                }

                val usage = Usages.byUsb(tap.usage) ?: HidUsage(
                    name = "USB_USAGE_0x${"%02x".format(tap.usage)}",
                    usbUsage = tap.usage,
                    evdevCode = 0,
                )
                ops.add(hid.keyTap(usage))
            }
        }

        // Final transition block: Release any remaining active modifiers to return to neutral state
        if (currentModifiers != 0) {
            val releaseUsages = getModifierUsages(currentModifiers).asReversed()
            for (mod in releaseUsages) {
                ops.add(hid.keyUp(mod))
            }
        }

        return TextPlan(plan = ops, failure = null)
    }
}
