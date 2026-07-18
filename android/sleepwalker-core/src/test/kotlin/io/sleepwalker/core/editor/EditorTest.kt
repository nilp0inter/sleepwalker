package io.sleepwalker.core.editor

import io.sleepwalker.core.protocol.Opcodes
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    @Test fun initializes_package_state_and_commits_text_and_opaque_state_only_after_delivery() {
        val executor = RecordingEditorExecutor(ArrayDeque(listOf(ExecutionOutcome.Delivered(emptyList()))))
        val editor = editor(
            executor = executor,
            mainLua = EditorTestFixtures.packageProgram(
                initializer = "{ revision = 10, package_owned = 'opaque' }",
                planner = """
                    return {
                        actions = { { kind = "tap", usage = "USB_KEY_A" } },
                        next_state = { revision = state.revision + 1, package_owned = state.package_owned },
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(
            AbiValue.Obj(mapOf("revision" to AbiValue.Int64(10), "package_owned" to AbiValue.Str("opaque"))),
            editor.verificationState.opaqueState,
        )

        val result = editor.setText("first")

        assertTrue(result is EditorResult.Synced)
        assertEquals(EditorState.Synced, editor.state())
        assertEquals("first", editor.verificationState.assumedDocument)
        assertEquals(
            AbiValue.Obj(mapOf("revision" to AbiValue.Int64(11), "package_owned" to AbiValue.Str("opaque"))),
            editor.verificationState.opaqueState,
        )
        assertEquals("", editor.verificationState.lastPlan!!.currentText)
        assertEquals("first", editor.verificationState.lastPlan!!.desiredText)
        assertEquals(1, executor.submittedPlans.size)
    }

    @Test fun pre_execution_failure_retains_the_last_committed_text_and_opaque_state() {
        val executor = RecordingEditorExecutor(
            ArrayDeque(listOf(ExecutionOutcome.Delivered(emptyList()), ExecutionOutcome.Delivered(emptyList()))),
        )
        val editor = editor(
            executor = executor,
            mainLua = EditorTestFixtures.packageProgram(
                initializer = "{ revision = 0 }",
                planner = """
                    if desired == "reject" then
                        return {
                            actions = { { kind = "unknown", usage = "USB_KEY_A" } },
                            next_state = { revision = 999 },
                        }
                    end
                    return {
                        actions = { { kind = "tap", usage = "USB_KEY_A" } },
                        next_state = { revision = state.revision + 1 },
                    }
                """.trimIndent(),
            ),
        )

        assertTrue(editor.setText("committed") is EditorResult.Synced)
        val failed = editor.setText("reject") as EditorResult.EditorFailure
        assertTrue(failed.classification is FailureClassification.PlanningError)
        assertEquals(EditorState.Failed, editor.state())
        assertEquals("committed", editor.verificationState.assumedDocument)
        assertEquals(AbiValue.Obj(mapOf("revision" to AbiValue.Int64(1))), editor.verificationState.opaqueState)
        assertEquals(1, executor.submittedPlans.size)

        assertTrue(editor.setText("next") is EditorResult.Synced)
        assertEquals("committed", editor.verificationState.lastPlan!!.currentText)
        assertEquals(AbiValue.Obj(mapOf("revision" to AbiValue.Int64(1))), editor.verificationState.lastPlan!!.opaqueInputState)
    }

    @Test fun no_action_plan_cannot_claim_text_or_opaque_state_transition() {
        data class Case(val name: String, val current: String, val planner: String)

        val cases = listOf(
            Case(
                "rendered text transition",
                "before",
                "return { actions = {}, next_state = state }",
            ),
            Case(
                "opaque state transition",
                "same",
                "return { actions = {}, next_state = { revision = state.revision + 1 } }",
            ),
        )

        cases.forEach { case ->
            val executor = RecordingEditorExecutor(ArrayDeque())
            val editor = editor(
                executor = executor,
                mainLua = EditorTestFixtures.packageProgram("{ revision = 4 }", case.planner),
            )
            editor.restore("same", AbiValue.Obj(mapOf("revision" to AbiValue.Int64(4))))

            val result = editor.setText(case.current) as EditorResult.EditorFailure

            assertTrue(case.name, result.classification is FailureClassification.PlanningError)
            assertEquals(case.name, "same", editor.verificationState.assumedDocument)
            assertEquals(case.name, AbiValue.Obj(mapOf("revision" to AbiValue.Int64(4))), editor.verificationState.opaqueState)
            assertTrue(case.name, executor.submittedPlans.isEmpty())
        }
    }

    @Test fun reset_reinitializes_opaque_state_from_the_empty_known_document() {
        val executor = RecordingEditorExecutor(ArrayDeque(listOf(ExecutionOutcome.Delivered(emptyList()))))
        val editor = editor(
            executor = executor,
            mainLua = EditorTestFixtures.packageProgram(
                initializer = "{ initialized_from = current }",
                planner = """
                    return {
                        actions = { { kind = "tap", usage = "USB_KEY_A" } },
                        next_state = { initialized_from = state.initialized_from, last = desired },
                    }
                """.trimIndent(),
            ),
        )

        editor.restore("stale", AbiValue.Obj(mapOf("initialized_from" to AbiValue.Str("stale"))))
        editor.reset()

        assertEquals(EditorState.Uninitialized, editor.state())
        assertEquals(AbiValue.Obj(mapOf("initialized_from" to AbiValue.Str(""))), editor.verificationState.opaqueState)
        assertTrue(editor.setText("fresh") is EditorResult.Synced)
        assertEquals(AbiValue.Obj(mapOf("initialized_from" to AbiValue.Str(""), "last" to AbiValue.Str("fresh"))), editor.verificationState.opaqueState)
    }

    @Test fun partial_delivery_discards_prediction_marks_unknown_and_blocks_until_reset() {
        val executor = RecordingEditorExecutor(
            ArrayDeque(
                listOf(
                    ExecutionOutcome.Partial(emptyList(), FailureClassification.TransportFailure("link lost")),
                    ExecutionOutcome.Delivered(emptyList()),
                ),
            ),
        )
        val editor = editor(
            executor = executor,
            mainLua = EditorTestFixtures.packageProgram(
                initializer = "{ revision = 0 }",
                planner = "return { actions = { { kind = 'tap', usage = 'USB_KEY_A' } }, next_state = { revision = 1 } }",
            ),
        )

        val partial = editor.setText("first") as EditorResult.EditorFailure
        assertTrue(partial.classification is FailureClassification.TransportFailure)
        assertEquals(EditorState.Unknown, editor.state())
        assertEquals("", editor.verificationState.assumedDocument)
        assertEquals(AbiValue.Obj(mapOf("revision" to AbiValue.Int64(0))), editor.verificationState.opaqueState)
        assertEquals("UNKNOWN", editor.verificationState.lastPlan!!.outcome)

        val rejected = editor.setText("blocked") as EditorResult.EditorFailure
        assertTrue(rejected.classification is FailureClassification.EnvironmentFailure)
        assertNull(rejected.plan)
        assertEquals(1, executor.submittedPlans.size)

        editor.reset()
        assertTrue(editor.setText("recovered") is EditorResult.Synced)
        assertEquals(EditorState.Synced, editor.state())
    }

    @Test fun serialized_requests_plan_the_second_snapshot_from_the_first_committed_snapshot() {
        val executor = GateExecutor()
        val editor = editor(
            executor = executor,
            mainLua = EditorTestFixtures.packageProgram(
                initializer = "{ revision = 0 }",
                planner = """
                    return {
                        actions = { { kind = "tap", usage = "USB_KEY_A" } },
                        next_state = { previous = current, revision = state.revision + 1 },
                    }
                """.trimIndent(),
            ),
        )
        val firstResult = arrayOfNulls<EditorResult>(1)
        val secondResult = arrayOfNulls<EditorResult>(1)

        val first = Thread { firstResult[0] = editor.setText("first") }
        first.start()
        executor.entered.await()
        val second = Thread { secondResult[0] = editor.setText("second") }
        second.start()
        executor.release.countDown()
        first.join()
        second.join()

        assertTrue(firstResult[0] is EditorResult.Synced)
        assertTrue(secondResult[0] is EditorResult.Synced)
        assertEquals(2, executor.submittedPlans.size)
        assertEquals("first", editor.verificationState.lastPlan!!.currentText)
        assertEquals("second", editor.verificationState.lastPlan!!.desiredText)
        assertEquals(AbiValue.Str("first"), (editor.verificationState.opaqueState as AbiValue.Obj).fields.getValue("previous"))
    }

    @Test fun valid_symbolic_actions_compile_in_order_and_invalid_or_reserved_actions_never_execute() {
        val validExecutor = RecordingEditorExecutor(ArrayDeque(listOf(ExecutionOutcome.Delivered(emptyList()))))
        val valid = editor(
            executor = validExecutor,
            mainLua = EditorTestFixtures.packageProgram(
                initializer = "{}",
                planner = """
                    return {
                        actions = {
                            { kind = "tap", usage = "USB_KEY_A" },
                            { kind = "down", usage = "USB_KEY_LEFTSHIFT" },
                            { kind = "up", usage = "USB_KEY_LEFTSHIFT" },
                            { kind = "text", text = "a" },
                        },
                        next_state = state,
                    }
                """.trimIndent(),
            ),
        )

        val validResult = valid.setText("target") as EditorResult.Synced
        assertEquals(listOf(Opcodes.KEY_TAP, Opcodes.KEY_DOWN, Opcodes.KEY_UP), validResult.plan.take(3).map { it.opcode })
        assertTrue(validResult.plan.size > 3)

        data class Case(val name: String, val actions: String, val policy: ExecutionPolicy)
        val rejectedCases = listOf(
            Case("unknown kind", "{ { kind = 'rotate', usage = 'USB_KEY_A' } }", ExecutionPolicy.PRODUCTION),
            Case("extra field", "{ { kind = 'tap', usage = 'USB_KEY_A', frame = 'raw' } }", ExecutionPolicy.PRODUCTION),
            Case("missing usage", "{ { kind = 'down' } }", ExecutionPolicy.PRODUCTION),
            Case("reserved F24", "{ { kind = 'tap', usage = 'USB_KEY_F24' } }", ExecutionPolicy.CONFORMANCE),
        )
        rejectedCases.forEach { case ->
            val hid = RecordingHid()
            val executor = RecordingEditorExecutor(ArrayDeque())
            val candidate = editor(
                executor = executor,
                hid = hid,
                policy = case.policy,
                mainLua = EditorTestFixtures.packageProgram(
                    initializer = "{}",
                    planner = "return { actions = ${case.actions}, next_state = state }",
                ),
            )

            val failure = candidate.setText("target") as EditorResult.EditorFailure
            assertTrue(case.name, failure.classification is FailureClassification.PlanningError)
            assertTrue(case.name, executor.submittedPlans.isEmpty())
            assertTrue(case.name, hid.calls.isEmpty())
        }
    }

    @Test fun malformed_later_text_and_oversized_plan_fail_atomically_before_execution() {
        data class Case(val name: String, val planner: String)
        val cases = listOf(
            Case(
                "unrepresentable later text action",
                """
                    return {
                        actions = {
                            { kind = "tap", usage = "USB_KEY_A" },
                            { kind = "text", text = "☃" },
                        },
                        next_state = state,
                    }
                """.trimIndent(),
            ),
            Case(
                "oversized plan",
                """
                    local actions = {}
                    for i = 1, 1001 do actions[i] = { kind = "tap", usage = "USB_KEY_A" } end
                    return { actions = actions, next_state = state }
                """.trimIndent(),
            ),
        )
        cases.forEach { case ->
            val hid = RecordingHid()
            val executor = RecordingEditorExecutor(ArrayDeque())
            val candidate = editor(
                executor = executor,
                hid = hid,
                mainLua = EditorTestFixtures.packageProgram("{}", case.planner),
            )

            val failure = candidate.setText("target") as EditorResult.EditorFailure

            assertTrue(case.name, failure.classification is FailureClassification.PlanningError)
            assertTrue(case.name, executor.submittedPlans.isEmpty())
            assertTrue(case.name, hid.calls.isEmpty())
            assertTrue(case.name, hid.issuedSequenceIds.isEmpty())
        }
    }

    private fun editor(
        executor: EditorExecutor,
        hid: RecordingHid = RecordingHid(),
        policy: ExecutionPolicy = ExecutionPolicy.PRODUCTION,
        mainLua: String,
    ): Editor = Editor(
        target = EditorTestFixtures.target(mainLua),
        executor = executor,
        hid = hid,
        sharedModules = emptyMap(),
        policy = policy,
    )

    private class GateExecutor : EditorExecutor {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val submittedPlans = mutableListOf<List<io.sleepwalker.core.hid.LowLevelOp>>()

        override fun execute(plan: List<io.sleepwalker.core.hid.LowLevelOp>): ExecutionOutcome {
            submittedPlans += plan
            entered.countDown()
            release.await()
            return ExecutionOutcome.Delivered(emptyList())
        }
    }
}
