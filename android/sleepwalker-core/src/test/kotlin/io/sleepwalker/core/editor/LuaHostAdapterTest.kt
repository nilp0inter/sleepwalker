package io.sleepwalker.core.editor

import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.Usages
import io.sleepwalker.core.text.TextPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LuaHostAdapterTest {
    @Test fun allowlisted_lua_libraries_can_compute_a_deterministic_transition() {
        val source = """
            function plan(current, desired, lcp, oldMid, newMid, state)
                local rendered = table.concat({string.upper("o"), string.upper("k")})
                if rendered ~= "OK" or math.abs(-1) ~= 1 or type(assert) ~= "function" then
                    error("allowlisted library unavailable")
                end
                return { buffer = desired, point = #desired, revision = state.revision + 1 }
            end
        """.trimIndent()
        val hid = RecordingHid()

        LuaHostAdapter(hid, TextPlanner(SeedKeymapDatabase, hid)).use { adapter ->
            adapter.initialize(EditorTestFixtures.target(mainLua = source))
            val result = adapter.planTransition("", "OK", 0, "", "OK", ReadlineProgramState())

            assertTrue(result.ok)
            assertEquals("OK", result.nextState!!.buffer)
        }
    }

    @Test fun forbidden_lua_capabilities_fail_before_emitting_hid_operations() {
        val forbiddenExpressions = listOf(
            "java",
            "io",
            "os",
            "debug",
            "package",
            "load",
            "dofile",
            "loadfile",
            "math.random",
        )

        for (expression in forbiddenExpressions) {
            val source = """
                function plan(current, desired, lcp, oldMid, newMid, state)
                    if $expression ~= nil then
                        error("forbidden capability was reachable: $expression")
                    end
                    local inaccessible = ($expression).unavailable
                    host.key_tap("USB_KEY_A")
                    return { buffer = desired, point = #desired, revision = state.revision + 1 }
                end
            """.trimIndent()
            val hid = RecordingHid()

            LuaHostAdapter(hid, TextPlanner(SeedKeymapDatabase, hid)).use { adapter ->
                adapter.initialize(EditorTestFixtures.target(mainLua = source))
                val result = adapter.planTransition("", "safe", 0, "", "safe", ReadlineProgramState())

                assertFalse(expression, result.ok)
                assertTrue(expression, result.failure is FailureClassification.PlanningError)
                val failure = result.failure as FailureClassification.PlanningError
                assertFalse(expression, failure.reason.contains("forbidden capability was reachable"))
                assertTrue(expression, hid.calls.isEmpty())
            }
        }
    }

    @Test fun require_resolves_declared_modules_only_and_refuses_parent_paths() {
        val bundledSource = """
            local formatter = require("formatter")
            function plan(current, desired, lcp, oldMid, newMid, state)
                return {
                    buffer = formatter.render(desired),
                    point = #desired,
                    revision = state.revision + 1,
                }
            end
        """.trimIndent()
        val bundledModule = """
            return { render = function(value) return string.upper(value) end }
        """.trimIndent()
        val hid = RecordingHid()

        LuaHostAdapter(hid, TextPlanner(SeedKeymapDatabase, hid)).use { adapter ->
            adapter.initialize(
                EditorTestFixtures.target(
                    mainLua = bundledSource,
                    modules = mapOf("formatter" to bundledModule),
                ),
            )
            val result = adapter.planTransition("", "SAFE", 0, "", "SAFE", ReadlineProgramState())
            assertTrue(result.ok)
            assertEquals("SAFE", result.nextState!!.buffer)
        }

        for (unbundledName in listOf("not-bundled", "../outside")) {
            val source = "require(\"$unbundledName\")"
            val unbundledHid = RecordingHid()
            val adapter = LuaHostAdapter(
                unbundledHid,
                TextPlanner(SeedKeymapDatabase, unbundledHid),
            )
            try {
                adapter.initialize(EditorTestFixtures.target(mainLua = source))
                fail("$unbundledName must not resolve outside declared package modules")
            } catch (_: LuaLoadException) {
                // The custom require rejects the unresolved module during target initialization.
            } finally {
                adapter.close()
            }
        }
    }

    @Test fun incompatible_host_abi_is_refused_before_lua_program_loads() {
        val hid = RecordingHid()
        val adapter = LuaHostAdapter(
            hid,
            TextPlanner(SeedKeymapDatabase, hid),
        )
        try {
            adapter.initialize(
                EditorTestFixtures.target(hostAbi = TargetPackage.HOST_ABI_VERSION + 1),
            )
            fail("an incompatible target ABI must be refused")
        } catch (_: IllegalStateException) {
            // The adapter's ABI guard is the JVM-reachable package-loading seam.
        } finally {
            adapter.close()
        }
    }

    @Test fun invocation_state_is_deep_copied_and_replay_does_not_leak_mutation() {
        val source = """
            function plan(current, desired, lcp, oldMid, newMid, state)
                state.buffer = state.buffer .. "!"
                state.point = state.point + 1
                state.revision = state.revision + 1
                return state
            end
        """.trimIndent()
        val input = ReadlineProgramState(buffer = "seed", point = 2, revision = 7)
        val hid = RecordingHid()

        LuaHostAdapter(hid, TextPlanner(SeedKeymapDatabase, hid)).use { adapter ->
            adapter.initialize(EditorTestFixtures.target(mainLua = source))
            val first = adapter.planTransition("seed", "seed!", 4, "", "!", input)
            val second = adapter.planTransition("seed", "seed!", 4, "", "!", input)

            val expected = ReadlineProgramState(buffer = "seed!", point = 3, revision = 8)
            assertEquals(expected, first.nextState)
            assertEquals(expected, second.nextState)
            assertEquals(ReadlineProgramState(buffer = "seed", point = 2, revision = 7), input)
            assertTrue(hid.calls.isEmpty())
        }
    }

    @Test fun invocation_recreates_lua_environment_before_replaying_identical_explicit_state() {
        val source = """
            local invocation = 0
            local function nextInvocation()
                invocation = invocation + 1
                return invocation
            end

            function plan(current, desired, lcp, oldMid, newMid, state)
                local count = nextInvocation()
                state.buffer = state.buffer .. "#" .. count
                state.point = state.point + count
                state.revision = state.revision + count
                return state
            end
        """.trimIndent()
        val input = ReadlineProgramState(buffer = "seed", point = 2, revision = 7)
        val hid = RecordingHid()

        LuaHostAdapter(hid, TextPlanner(SeedKeymapDatabase, hid)).use { adapter ->
            adapter.initialize(EditorTestFixtures.target(mainLua = source))
            val first = adapter.planTransition("seed", "seed#1", 4, "", "#1", input)
            val second = adapter.planTransition("seed", "seed#1", 4, "", "#1", input)

            val expected = ReadlineProgramState(buffer = "seed#1", point = 3, revision = 8)
            assertEquals(expected, first.nextState)
            assertEquals(expected, second.nextState)
            assertEquals(ReadlineProgramState(buffer = "seed", point = 2, revision = 7), input)
            assertTrue(hid.calls.isEmpty())
        }
    }

    @Test fun readline_chords_follow_home_then_forward_and_delegate_printable_text() {
        val source = """
            function plan(current, desired, lcp, oldMid, newMid, state)
                local s = { buffer = state.buffer, point = state.point, revision = state.revision }
                host.ctrl("A")
                s.point = 0
                for _ = 1, lcp do
                    host.ctrl("F")
                    s.point = s.point + 1
                end
                for _ = 1, #oldMid do
                    host.ctrl("D")
                    s.buffer = string.sub(s.buffer, 1, s.point) .. string.sub(s.buffer, s.point + 2)
                end
                if #newMid > 0 then
                    host.text_plan(newMid)
                    s.buffer = string.sub(s.buffer, 1, s.point) .. newMid .. string.sub(s.buffer, s.point + 1)
                    s.point = s.point + #newMid
                end
                s.revision = s.revision + 1
                return s
            end
        """.trimIndent()
        val hid = RecordingHid()

        LuaHostAdapter(hid, TextPlanner(SeedKeymapDatabase, hid)).use { adapter ->
            adapter.initialize(EditorTestFixtures.target(mainLua = source))
            val result = adapter.planTransition(
                current = "abcdef",
                desired = "abXYef",
                lcp = 2,
                oldMid = "cd",
                newMid = "XY",
                state = ReadlineProgramState(buffer = "abcdef", point = 6, revision = 4),
            )

            assertTrue(result.ok)
            assertEquals(ReadlineProgramState("abXYef", 4, 5), result.nextState)
            val replay = adapter.planTransition(
                current = "abcdef",
                desired = "abXYef",
                lcp = 2,
                oldMid = "cd",
                newMid = "XY",
                state = ReadlineProgramState(buffer = "abcdef", point = 6, revision = 4),
            )
            assertEquals(result.nextState, replay.nextState)
            assertEquals(
                result.ops.map { op -> op.opcode to op.payload.toList() },
                replay.ops.map { op -> op.opcode to op.payload.toList() },
            )
            assertEquals(
                listOf(
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_A.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_F.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_F.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_D.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                    Usages.USB_KEY_D.usbUsage,
                    Usages.USB_KEY_LEFTCTRL.usbUsage,
                ),
                result.ops.take(15).map { it.payload.single().toInt() and 0xFF },
            )
            assertFalse(result.ops.any { op ->
                op.payload.singleOrNull()?.toInt()?.and(0xFF) == Usages.USB_KEY_F24.usbUsage
            })
        }
    }

    @Test fun approved_backward_delete_chord_is_available_but_f24_is_reserved() {
        val hid = RecordingHid()
        val builder = PlanBuilder(hid, TextPlanner(SeedKeymapDatabase, hid), HostProfile.LINUX_US)

        builder.ctrl("H")
        assertEquals(
            listOf(
                Usages.USB_KEY_LEFTCTRL.usbUsage,
                Usages.USB_KEY_H.usbUsage,
                Usages.USB_KEY_LEFTCTRL.usbUsage,
            ),
            hid.calls.map { it.payload.single().toInt() and 0xFF },
        )

        val f24Operations: List<Pair<String, (PlanBuilder) -> Unit>> = listOf(
            "tap" to { it.keyTap("USB_KEY_F24") },
            "down" to { it.keyDown("USB_KEY_F24") },
            "up" to { it.keyUp("USB_KEY_F24") },
            "ctrl" to { it.ctrl("F24") },
        )
        for ((name, operation) in f24Operations) {
            val f24Hid = RecordingHid()
            try {
                operation(
                    PlanBuilder(
                        f24Hid,
                        TextPlanner(SeedKeymapDatabase, f24Hid),
                        HostProfile.LINUX_US,
                    ),
                )
                fail("F24 $name must never enter a production editor plan")
            } catch (_: IllegalArgumentException) {
                assertTrue(f24Hid.calls.isEmpty())
            }
        }
    }
}
