package io.sleepwalker.core.editor

import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.text.TextPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LuaHostAdapterTest {
    @Test fun fresh_vm_discards_global_and_module_mutation_between_identical_plans() {
        val target = EditorTestFixtures.target(
            mainLua = """
                local module_state = require("stateful")
                return {
                    abi_version = 1,
                    initialize = function(_) return { revision = 0 } end,
                    plan = function(current, desired, state)
                        global_count = (global_count or 0) + 1
                        module_state.count = module_state.count + 1
                        return {
                            actions = {},
                            next_state = { global_count = global_count, module_count = module_state.count },
                        }
                    end,
                }
            """.trimIndent(),
            modules = mapOf("stateful" to "return { count = 0 }"),
        )
        val hid = RecordingHid()
        val adapter = adapter(hid)

        val first = adapter.runPlan(target, "same", "same", AbiValue.Obj(emptyMap()), HostProfile.LINUX_US)
        val second = adapter.runPlan(target, "same", "same", AbiValue.Obj(emptyMap()), HostProfile.LINUX_US)

        assertEquals(first, second)
        assertEquals(
            AbiValue.Obj(mapOf("global_count" to AbiValue.Int64(1), "module_count" to AbiValue.Int64(1))),
            first.success().nextState,
        )
        assertTrue("planning must not construct HID operations", hid.calls.isEmpty())
    }

    @Test fun require_caches_table_and_scalar_exports_within_one_invocation() {
        val target = EditorTestFixtures.target(
            mainLua = """
                local table_first = require("table_export")
                local table_second = require("table_export")
                local number_first = require("number_export")
                local number_second = require("number_export")
                local string_first = require("string_export")
                local string_second = require("string_export")
                local true_first = require("true_export")
                local true_second = require("true_export")
                local false_first = require("false_export")
                local false_second = require("false_export")
                return {
                    abi_version = 1,
                    initialize = function(_) return {} end,
                    plan = function(current, desired, state)
                        if table_first ~= table_second or number_first ~= number_second or
                           string_first ~= string_second or true_first ~= true_second or
                           false_first ~= false_second then
                            error("module cache changed an export")
                        end
                        return { actions = {}, next_state = { false_loads = false_loads } }
                    end,
                }
            """.trimIndent(),
            modules = mapOf(
                "table_export" to "return {}",
                "number_export" to "return 7",
                "string_export" to "return 'cached'",
                "true_export" to "return true",
                "false_export" to "false_loads = (false_loads or 0) + 1; return false",
            ),
        )

        val result = adapter(RecordingHid()).runPlan(
            target,
            "same",
            "same",
            AbiValue.Obj(emptyMap()),
            HostProfile.LINUX_US,
        ).success()

        assertEquals(AbiValue.Int64(1), (result.nextState as AbiValue.Obj).fields.getValue("false_loads"))
    }

    @Test fun module_loader_rejects_missing_reserved_shadow_and_cyclic_dependencies() {
        data class Case(val name: String, val target: TargetPackage, val expectedMessage: String)

        val cases = listOf(
            Case(
                "missing module",
                EditorTestFixtures.target(
                    mainLua = moduleRequiringProgram("missing"),
                ),
                "not found",
            ),
            Case(
                "reserved namespace shadow",
                EditorTestFixtures.target(
                    modules = mapOf("sleepwalker.cost" to "return {}"),
                ),
                "shadows reserved namespace",
            ),
            Case(
                "cycle",
                EditorTestFixtures.target(
                    mainLua = moduleRequiringProgram("first"),
                    modules = mapOf(
                        "first" to "local second = require('second'); return second",
                        "second" to "local first = require('first'); return first",
                    ),
                ),
                "circular dependency",
            ),
        )

        cases.forEach { case ->
            val failure = adapter(RecordingHid()).runPlan(
                case.target,
                "same",
                "same",
                AbiValue.Obj(emptyMap()),
                HostProfile.LINUX_US,
            ).failure()
            assertTrue(case.name, failure is FailureClassification.PlanningError)
            assertTrue(case.name, (failure as FailureClassification.PlanningError).reason.contains(case.expectedMessage))
        }
    }

    @Test fun planner_returns_inert_data_for_identical_inputs_without_host_action_effects() {
        val target = EditorTestFixtures.target(
            mainLua = EditorTestFixtures.packageProgram(
                initializer = "{ revision = 0 }",
                planner = """
                    return {
                        actions = { { kind = "tap", usage = "USB_KEY_A" } },
                        next_state = { revision = state.revision + 1 },
                    }
                """.trimIndent(),
            ),
        )
        val hid = RecordingHid()
        val adapter = adapter(hid)

        val first = adapter.runPlan(target, "a", "b", AbiValue.Obj(mapOf("revision" to AbiValue.Int64(3))), HostProfile.LINUX_US)
        val second = adapter.runPlan(target, "a", "b", AbiValue.Obj(mapOf("revision" to AbiValue.Int64(3))), HostProfile.LINUX_US)

        assertEquals(first, second)
        assertEquals(listOf(SymbolicAction.Tap("USB_KEY_A")), first.success().actions)
        assertTrue("decoding symbolic actions must not allocate low-level operations", hid.calls.isEmpty())
        assertTrue("planning must not allocate sequence IDs", hid.issuedSequenceIds.isEmpty())
    }

    @Test fun text_cost_is_layout_pinned_unrepresentable_is_inert_and_reuses_one_cached_compilation() {
        val target = EditorTestFixtures.target(
            mainLua = """
                local cost = require("sleepwalker.cost")
                return {
                    abi_version = 1,
                    initialize = function(_) return { revision = 0 } end,
                    plan = function(current, desired, state)
                        local first, layout_one, metric_one = cost.text_cost(desired)
                        local second, layout_two, metric_two = cost.text_cost(desired)
                        if first ~= second or layout_one ~= layout_two or metric_one ~= metric_two then
                            error("text cost is not deterministic")
                        end
                        return {
                            actions = {},
                            next_state = {
                                cost = first,
                                representable = first ~= nil,
                                layout = layout_one,
                                metric = metric_one,
                            },
                        }
                    end,
                }
            """.trimIndent(),
        )
        val hid = RecordingHid()

        val represented = adapter(hid, sharedModules = costModule()).runPlan(
            target,
            "same",
            "same",
            AbiValue.Obj(emptyMap()),
            HostProfile.LINUX_US,
        ).success()

        assertTrue((represented.nextState as AbiValue.Obj).fields.getValue("cost") is AbiValue.Int64)
        assertEquals(AbiValue.Str(HostProfile.LINUX_US.key), (represented.nextState as AbiValue.Obj).fields.getValue("layout"))
        assertEquals(AbiValue.Str("op_count:1"), (represented.nextState as AbiValue.Obj).fields.getValue("metric"))
        assertEquals(setOf("same"), represented.compileCache.keys)
        assertTrue("text_cost must not emit actions", hid.calls.isEmpty())
        assertTrue("text_cost must not allocate sequence IDs", hid.issuedSequenceIds.isEmpty())

        val unrepresentable = adapter(RecordingHid(), sharedModules = costModule()).runPlan(
            target,
            "same",
            "☃",
            AbiValue.Obj(emptyMap()),
            HostProfile.LINUX_US,
        ).success().nextState as AbiValue.Obj
        assertEquals(AbiValue.Bool(false), unrepresentable.fields.getValue("representable"))
        assertEquals(AbiValue.Str(HostProfile.LINUX_US.key), unrepresentable.fields.getValue("layout"))
        assertEquals(AbiValue.Str("op_count:1"), unrepresentable.fields.getValue("metric"))
    }

    private fun moduleRequiringProgram(module: String): String = """
        local dependency = require("$module")
        return {
            abi_version = 1,
            initialize = function(_) return {} end,
            plan = function(current, desired, state)
                return { actions = {}, next_state = state }
            end,
        }
    """.trimIndent()

    private fun costModule(): Map<String, String> = mapOf(
        "sleepwalker.cost" to """
            return {
                text_cost = function(text)
                    return sleepwalker.cost.text_cost(text)
                end,
            }
        """.trimIndent(),
    )

    private fun adapter(
        hid: RecordingHid,
        sharedModules: Map<String, String> = emptyMap(),
    ): LuaHostAdapter = LuaHostAdapter(hid, TextPlanner(hid = hid), sharedModules)

    private fun LuaInvocationResult.success(): LuaInvocationResult.Success =
        this as? LuaInvocationResult.Success ?: fail("expected successful Lua invocation, got $this").let { error("unreachable") }

    private fun LuaInvocationResult.failure(): FailureClassification =
        (this as? LuaInvocationResult.Failure)?.classification
            ?: fail("expected Lua planning failure, got $this").let { error("unreachable") }
}
