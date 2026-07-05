package io.sleepwalker.core.text

import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.KeymapEntry
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.8: keymap lookup, text planning, rendering failures.
 */
class TextPlannerTest {

    @Test fun linux_us_profile_present_in_seed() {
        assertTrue(SeedKeymapDatabase.contains(HostProfile.LINUX_US))
    }

    @Test fun missing_layout_returns_structured_failure() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        val unknown = HostProfile("plan9", "dvorak")
        val result = planner.plan("a", unknown)
        assertFalse(result.ok)
        assertNull(result.plan)
        assertTrue(result.failure is TextRenderingFailure.MissingLayout)
        val f = result.failure as TextRenderingFailure.MissingLayout
        assertEquals(unknown, f.profile)
    }

    @Test fun representable_text_planned_as_key_taps() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        val result = planner.plan("hi", HostProfile.LINUX_US)
        assertTrue(result.ok)
        assertNotNull(result.plan)
        val ops = result.plan!!
        assertEquals(2, ops.size)
        assertEquals(Opcodes.KEY_TAP, ops[0].opcode)
        assertEquals(Opcodes.KEY_TAP, ops[1].opcode)
        // 'h' = USB usage 0x0B, 'i' = 0x0C
        assertEquals(0x0B, ops[0].payload[0].toInt() and 0xFF)
        assertEquals(0x0C, ops[1].payload[0].toInt() and 0xFF)
    }

    @Test fun unrepresentable_glyph_rejected() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        // '€' is not in the seed US keymap.
        val result = planner.plan("€", HostProfile.LINUX_US)
        assertFalse(result.ok)
        assertNull(result.plan)
        assertTrue(result.failure is TextRenderingFailure.UnrepresentableGlyph)
        val f = result.failure as TextRenderingFailure.UnrepresentableGlyph
        assertEquals('€', f.ch)
    }

    @Test fun mixed_representable_and_unrepresentable_emits_no_ops() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        // 'a' is representable, '€' is not; whole request must fail.
        val result = planner.plan("a€", HostProfile.LINUX_US)
        assertFalse(result.ok)
        assertNull(result.plan)
    }

    @Test fun seed_keymap_resolves_lowercase_letters() {
        val entry = SeedKeymapDatabase.entryFor('a')
        assertNotNull(entry)
        assertEquals(0x04, entry!!.usage)
        assertEquals(0, entry.modifiers)
    }

    @Test fun seed_keymap_resolves_uppercase_with_shift() {
        val entry = SeedKeymapDatabase.entryFor('A')
        assertNotNull(entry)
        assertEquals(0x04, entry!!.usage)
        // Shift modifier bit 1 = 0x02
        assertEquals(0x02, entry.modifiers)
    }
}
