package io.sleepwalker.core.editor

import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.text.TextPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedLuaLibraryTest {
    @Test fun sequence_composes_predicted_text_state_actions_and_cost_in_data_only_form() {
        val target = EditorTestFixtures.target(
            mainLua = """
                local actions = require("sleepwalker.actions")
                local plan = require("sleepwalker.plan")
                return {
                    abi_version = 1,
                    initialize = function(_) return { step = 0 } end,
                    plan = function(current, desired, state)
                        local first = function(text, goal, input)
                            return { text = "intermediate", state = { step = input.step + 1 }, actions = { actions.tap("USB_KEY_A") }, cost = 1 }
                        end
                        local second = function(text, goal, input)
                            if text ~= "intermediate" or input.step ~= 1 then return nil, "composition lost predicted data" end
                            return { text = goal, state = { step = input.step + 1 }, actions = { actions.tap("USB_KEY_B") }, cost = 2 }
                        end
                        local candidate, err = plan.sequence({ first, second })(current, desired, state)
                        if not candidate then return { error = err } end
                        return { actions = candidate.actions, next_state = candidate.state }
                    end,
                }
            """.trimIndent(),
        )

        val result = adapter(sharedModules()).runPlan(
            target,
            "original",
            "goal",
            AbiValue.Obj(mapOf("step" to AbiValue.Int64(0))),
            HostProfile.LINUX_US,
        ).success()

        assertEquals(listOf(SymbolicAction.Tap("USB_KEY_A"), SymbolicAction.Tap("USB_KEY_B")), result.actions)
        assertEquals(AbiValue.Obj(mapOf("step" to AbiValue.Int64(2))), result.nextState)
    }

    @Test fun equal_cost_candidates_use_stable_lexicographic_action_tiebreaker() {
        val target = EditorTestFixtures.target(
            mainLua = """
                local actions = require("sleepwalker.actions")
                local plan = require("sleepwalker.plan")
                return {
                    abi_version = 1,
                    initialize = function(_) return {} end,
                    plan = function(current, desired, state)
                        local later = function(_, _, input)
                            return { text = desired, state = input, actions = { actions.tap("USB_KEY_B") }, cost = 1 }
                        end
                        local earlier = function(_, _, input)
                            return { text = desired, state = input, actions = { actions.tap("USB_KEY_A") }, cost = 1 }
                        end
                        local selected = plan.choose({ later, earlier })(current, desired, state)
                        return { actions = selected.actions, next_state = selected.state }
                    end,
                }
            """.trimIndent(),
        )

        val result = adapter(sharedModules()).runPlan(
            target,
            "same",
            "same",
            AbiValue.Obj(emptyMap()),
            HostProfile.LINUX_US,
        ).success()

        assertEquals(listOf(SymbolicAction.Tap("USB_KEY_A")), result.actions)
    }

    @Test fun readline_style_opaque_state_reconciliation_is_deterministic_across_fresh_vms() {
        val target = EditorTestFixtures.target(
            mainLua = """
                local actions = require("sleepwalker.actions")
                local state = require("sleepwalker.state")
                return {
                    abi_version = 1,
                    initialize = function(current) return { buffer = current, point = #current, revision = 0 } end,
                    plan = function(current, desired, prior)
                        if current == desired then return { actions = {}, next_state = prior } end
                        local next = state.update(prior, { buffer = desired, point = #desired, revision = prior.revision + 1 })
                        return { actions = { actions.text(desired) }, next_state = next }
                    end,
                }
            """.trimIndent(),
        )
        val adapter = adapter(sharedModules())
        val initial = adapter.runInitializer(target, "")

        val first = adapter.runPlan(target, "", "a", initial, HostProfile.LINUX_US).success()
        val replay = adapter.runPlan(target, "", "a", initial, HostProfile.LINUX_US).success()

        assertEquals(first, replay)
        assertEquals(listOf(SymbolicAction.Text("a")), first.actions)
        assertEquals(
            AbiValue.Obj(
                mapOf(
                    "buffer" to AbiValue.Str("a"),
                    "point" to AbiValue.Int64(1),
                    "revision" to AbiValue.Int64(1),
                ),
            ),
            first.nextState,
        )
    }

    private fun sharedModules(): Map<String, String> = mapOf(
        "sleepwalker.actions" to """
            return {
                tap = function(usage) return { kind = "tap", usage = usage } end,
                text = function(text) return { kind = "text", text = text } end,
                concat = function(...)
                    local out = {}
                    for _, actions in ipairs({...}) do for _, action in ipairs(actions) do table.insert(out, action) end end
                    return out
                end,
            }
        """.trimIndent(),
        "sleepwalker.state" to """
            local function copy(value)
                if type(value) ~= "table" then return value end
                local result = {}; for key, item in pairs(value) do result[copy(key)] = copy(item) end; return result
            end
            return {
                copy = copy,
                update = function(state, changes)
                    local result = copy(state); for key, item in pairs(changes) do result[key] = copy(item) end; return result
                end,
            }
        """.trimIndent(),
        "sleepwalker.cost" to """
            return {
                compare = function(left, right)
                    if left.cost ~= right.cost then return left.cost < right.cost end
                    return left.actions[1].usage < right.actions[1].usage
                end,
            }
        """.trimIndent(),
        "sleepwalker.plan" to """
            local actions = require("sleepwalker.actions")
            local state = require("sleepwalker.state")
            local cost = require("sleepwalker.cost")
            return {
                sequence = function(planners)
                    return function(current, desired, prior)
                        local candidate = { text = current, state = state.copy(prior), actions = {}, cost = 0 }
                        for _, planner in ipairs(planners) do
                            local next, err = planner(candidate.text, desired, candidate.state)
                            if not next then return nil, err end
                            candidate.text = next.text; candidate.state = next.state
                            candidate.actions = actions.concat(candidate.actions, next.actions)
                            candidate.cost = candidate.cost + next.cost
                        end
                        return candidate
                    end
                end,
                choose = function(planners)
                    return function(current, desired, prior)
                        local selected = nil
                        for _, planner in ipairs(planners) do
                            local candidate = planner(current, desired, prior)
                            if candidate and (not selected or cost.compare(candidate, selected)) then selected = candidate end
                        end
                        return selected
                    end
                end,
            }
        """.trimIndent(),
    )

    private fun adapter(sharedModules: Map<String, String>): LuaHostAdapter {
        val hid = RecordingHid()
        return LuaHostAdapter(hid, TextPlanner(hid = hid), sharedModules)
    }

    private fun LuaInvocationResult.success(): LuaInvocationResult.Success =
        this as? LuaInvocationResult.Success ?: error("expected successful Lua invocation, got $this")
}
