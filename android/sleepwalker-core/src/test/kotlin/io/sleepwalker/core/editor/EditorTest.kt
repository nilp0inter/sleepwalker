package io.sleepwalker.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    @Test fun delivered_failure_partial_unknown_and_reset_follow_the_state_contract() {
        val hid = RecordingHid()
        val executor = RecordingEditorExecutor(
            ArrayDeque(
                listOf(
                    ExecutionOutcome.Delivered(emptyList()),
                    ExecutionOutcome.Partial(
                        delivered = emptyList(),
                        reason = FailureClassification.TransportFailure("link lost"),
                    ),
                    ExecutionOutcome.Delivered(emptyList()),
                ),
            ),
        )
        val editor = Editor(EditorTestFixtures.target(), executor, hid)
        val statesObservedAtExecution = mutableListOf<EditorState>()
        executor.onExecute = { statesObservedAtExecution += editor.state() }

        assertEquals(EditorState.Uninitialized, editor.state())

        val initial = editor.setText("alpha")
        assertTrue(initial is EditorResult.Synced)
        assertEquals(EditorState.Synced, editor.state())
        assertEquals(listOf(EditorState.Executing), statesObservedAtExecution)

        val rejected = editor.setText("two\nlines")
        assertTrue(rejected is EditorResult.EditorFailure)
        assertTrue((rejected as EditorResult.EditorFailure).classification is FailureClassification.UnsupportedBehavior)
        assertEquals(EditorState.Failed, editor.state())
        assertEquals(1, executor.submittedPlans.size)

        val partial = editor.setText("beta")
        assertTrue(partial is EditorResult.EditorFailure)
        assertTrue((partial as EditorResult.EditorFailure).classification is FailureClassification.TransportFailure)
        assertEquals(EditorState.Unknown, editor.state())

        val rejectedWhileUnknown = editor.setText("must not execute") as EditorResult.EditorFailure
        assertTrue(rejectedWhileUnknown.classification is FailureClassification.EnvironmentFailure)
        assertNull(rejectedWhileUnknown.plan)
        assertEquals(2, executor.submittedPlans.size)

        editor.reset()
        assertEquals(EditorState.Uninitialized, editor.state())

        val recovered = editor.setText("fresh")
        assertTrue(recovered is EditorResult.Synced)
        assertEquals(EditorState.Synced, editor.state())
        assertEquals(
            listOf(EditorState.Executing, EditorState.Executing, EditorState.Executing),
            statesObservedAtExecution,
        )
    }

    @Test fun planning_and_executing_are_entered_at_their_respective_boundaries() {
        val planningProgram = """
            function plan(current, desired, lcp, oldMid, newMid, state)
                host.ctrl("A")
                host.text_plan(newMid)
                return { buffer = desired, point = #desired, revision = state.revision + 1 }
            end
        """.trimIndent()
        val hid = RecordingHid()
        val executor = RecordingEditorExecutor(
            ArrayDeque(listOf(ExecutionOutcome.Delivered(emptyList()))),
        )
        lateinit var editor: Editor
        var stateWhenLuaStartedPlanning: EditorState? = null
        var stateWhenExecutionStarted: EditorState? = null
        hid.onOperation = { op ->
            if (op.payload.singleOrNull()?.toInt()?.and(0xFF) ==
                io.sleepwalker.core.protocol.Usages.USB_KEY_LEFTCTRL.usbUsage
            ) {
                stateWhenLuaStartedPlanning = editor.state()
            }
        }
        executor.onExecute = { stateWhenExecutionStarted = editor.state() }
        editor = Editor(EditorTestFixtures.target(mainLua = planningProgram), executor, hid)

        assertTrue(editor.setText("value") is EditorResult.Synced)

        assertEquals(EditorState.Planning, stateWhenLuaStartedPlanning)
        assertEquals(EditorState.Executing, stateWhenExecutionStarted)
        assertEquals(EditorState.Synced, editor.state())
    }

    @Test fun non_ascii_and_non_single_line_snapshots_are_rejected_before_execution() {
        data class Case(val requested: String, val expected: Class<out FailureClassification>)

        val cases = listOf(
            Case("café", FailureClassification.UnrepresentableContent::class.java),
            Case("first\nsecond", FailureClassification.UnsupportedBehavior::class.java),
            Case("first\rsecond", FailureClassification.UnsupportedBehavior::class.java),
        )
        for (case in cases) {
            val executor = RecordingEditorExecutor(ArrayDeque())
            val editor = Editor(EditorTestFixtures.target(), executor, RecordingHid())

            val failure = editor.setText(case.requested) as EditorResult.EditorFailure

            assertTrue(case.requested, case.expected.isInstance(failure.classification))
            assertNull(case.requested, failure.plan)
            assertTrue(case.requested, executor.submittedPlans.isEmpty())
            assertEquals(case.requested, EditorState.Failed, editor.state())
        }
    }

    @Test fun no_op_returns_an_empty_plan_without_transport_execution() {
        val executor = RecordingEditorExecutor(
            ArrayDeque(listOf(ExecutionOutcome.Delivered(emptyList()))),
        )
        val editor = Editor(EditorTestFixtures.target(), executor, RecordingHid())

        assertTrue(editor.setText("stable") is EditorResult.Synced)
        val noOp = editor.setText("stable") as EditorResult.Synced

        assertTrue(noOp.plan.isEmpty())
        assertEquals(1, executor.submittedPlans.size)
        assertEquals(EditorState.Synced, editor.state())
    }

    @Test fun only_delivered_execution_commits_predicted_program_state() {
        val executor = RecordingEditorExecutor(
            ArrayDeque(
                listOf(
                    ExecutionOutcome.Delivered(emptyList()),
                    ExecutionOutcome.Partial(
                        delivered = emptyList(),
                        reason = FailureClassification.TransportFailure("disarmed"),
                    ),
                ),
            ),
        )
        val editor = Editor(EditorTestFixtures.target(), executor, RecordingHid())

        assertTrue(editor.setText("committed") is EditorResult.Synced)
        val committed = editor.verificationState
        assertEquals("committed", committed.assumedDocument)
        assertEquals("committed", committed.programState.buffer)
        assertEquals("committed", committed.lastPlan!!.desiredText)

        val invalid = editor.setText("invalid\ncontent") as EditorResult.EditorFailure
        assertNull(invalid.plan)
        val afterPlanningFailure = editor.verificationState
        assertEquals(committed.assumedDocument, afterPlanningFailure.assumedDocument)
        assertEquals(committed.programState, afterPlanningFailure.programState)
        assertEquals("invalid\ncontent", afterPlanningFailure.lastPlan!!.desiredText)
        assertTrue(afterPlanningFailure.lastPlan!!.ops.isEmpty())

        val partial = editor.setText("not-committed") as EditorResult.EditorFailure
        assertTrue(partial.plan!!.isNotEmpty())
        val afterPartialExecution = editor.verificationState
        assertEquals(EditorState.Unknown, afterPartialExecution.state)
        assertEquals(committed.assumedDocument, afterPartialExecution.assumedDocument)
        assertEquals(committed.programState, afterPartialExecution.programState)
        assertEquals("not-committed", afterPartialExecution.lastPlan!!.desiredText)
        assertEquals(partial.plan, afterPartialExecution.lastPlan!!.ops)
    }
    @Test fun multiline_rejection_traces_and_replaces_retained_plan_evidence() {
        val target = EditorTestFixtures.target()
        val executor = RecordingEditorExecutor(ArrayDeque())
        val editor = Editor(target, executor, RecordingHid())
        val entries = mutableListOf<VerificationEntry>()
        val originalSink = EditorTrace.sink
        EditorTrace.sink = object : VerificationSink {
            override fun record(entry: VerificationEntry) { entries += entry }
        }

        try {
            val failure = editor.setText("first\nsecond") as EditorResult.EditorFailure
            val entry = entries.single()
            val retained = editor.verificationState.lastPlan!!

            assertTrue(failure.classification is FailureClassification.UnsupportedBehavior)
            assertNull(failure.plan)
            assertEquals(failure.classification, entry.classification)
            assertEquals(target.version, entry.targetVersion)
            assertEquals(entry.targetVersion, retained.targetVersion)
            assertEquals("first\nsecond", entry.desiredText)
            assertTrue(entry.ops.isEmpty())
            assertEquals(entry.desiredText, retained.desiredText)
            assertEquals(entry.assumedState, retained.assumedState)
            assertEquals(entry.predictedState, retained.predictedState)
            assertEquals(entry.ops, retained.ops)
            assertTrue(executor.submittedPlans.isEmpty())
        } finally {
            EditorTrace.sink = originalSink
        }
    }

    @Test fun partial_execution_traces_and_retains_the_uncompleted_plan() {
        val target = EditorTestFixtures.target()
        val executor = RecordingEditorExecutor(
            ArrayDeque(
                listOf(
                    ExecutionOutcome.Partial(
                        delivered = emptyList(),
                        reason = FailureClassification.TransportFailure("queue full"),
                    ),
                ),
            ),
        )
        val editor = Editor(target, executor, RecordingHid())
        val entries = mutableListOf<VerificationEntry>()
        val originalSink = EditorTrace.sink
        EditorTrace.sink = object : VerificationSink {
            override fun record(entry: VerificationEntry) { entries += entry }
        }

        try {
            val failure = editor.setText("partial") as EditorResult.EditorFailure
            val entry = entries.single()
            val retained = editor.verificationState.lastPlan!!

            assertEquals(EditorState.Unknown, editor.state())
            assertTrue(failure.classification is FailureClassification.TransportFailure)
            assertEquals(failure.classification, entry.classification)
            assertEquals(failure.plan, entry.ops)
            assertEquals(target.version, entry.targetVersion)
            assertEquals(entry.targetVersion, retained.targetVersion)
            assertEquals("partial", retained.desiredText)
            assertEquals(entry.assumedState, retained.assumedState)
            assertEquals(entry.predictedState, retained.predictedState)
            assertEquals(entry.ops, retained.ops)
        } finally {
            EditorTrace.sink = originalSink
        }
    }

    @Test fun unknown_guard_traces_the_rejected_request_and_replaces_partial_evidence() {
        val executor = RecordingEditorExecutor(
            ArrayDeque(
                listOf(
                    ExecutionOutcome.Partial(
                        delivered = emptyList(),
                        reason = FailureClassification.TransportFailure("link lost"),
                    ),
                ),
            ),
        )
        val target = EditorTestFixtures.target()
        val editor = Editor(target, executor, RecordingHid())
        val entries = mutableListOf<VerificationEntry>()
        val originalSink = EditorTrace.sink
        EditorTrace.sink = object : VerificationSink {
            override fun record(entry: VerificationEntry) { entries += entry }
        }

        try {
            val partial = editor.setText("partially-delivered") as EditorResult.EditorFailure
            val rejected = editor.setText("blocked-after-unknown") as EditorResult.EditorFailure
            val retained = editor.verificationState.lastPlan!!

            assertEquals(EditorState.Unknown, editor.state())
            assertTrue(partial.plan!!.isNotEmpty())
            assertTrue(rejected.classification is FailureClassification.EnvironmentFailure)
            assertNull(rejected.plan)
            assertEquals(2, entries.size)
            assertEquals(rejected.classification, entries.last().classification)
            assertEquals(target.version, entries.last().targetVersion)
            assertEquals(entries.last().targetVersion, retained.targetVersion)
            assertEquals("blocked-after-unknown", entries.last().desiredText)
            assertTrue(entries.last().ops.isEmpty())
            assertEquals("blocked-after-unknown", retained.desiredText)
            assertTrue(retained.ops.isEmpty())
            assertEquals(1, executor.submittedPlans.size)
        } finally {
            EditorTrace.sink = originalSink
        }
    }

    @Test fun incompatible_target_abi_is_structured_and_emits_no_hid() {
        val target = EditorTestFixtures.target(hostAbi = TargetPackage.HOST_ABI_VERSION + 1)
        val hid = RecordingHid()
        val executor = RecordingEditorExecutor(ArrayDeque())
        val editor = Editor(target, executor, hid)
        val entries = mutableListOf<VerificationEntry>()
        val originalSink = EditorTrace.sink
        EditorTrace.sink = object : VerificationSink {
            override fun record(entry: VerificationEntry) { entries += entry }
        }

        try {
            val failure = editor.setText("valid") as EditorResult.EditorFailure
            val classification = failure.classification as FailureClassification.AbiMismatch
            val entry = entries.single()
            val retained = editor.verificationState.lastPlan!!

            assertEquals(TargetPackage.HOST_ABI_VERSION, classification.expected)
            assertEquals(target.hostAbi, classification.actual)
            assertEquals(EditorState.Failed, editor.state())
            assertNull(failure.plan)
            assertEquals(classification, entry.classification)
            assertEquals(target.version, entry.targetVersion)
            assertEquals(entry.targetVersion, retained.targetVersion)
            assertEquals("valid", retained.desiredText)
            assertTrue(retained.ops.isEmpty())
            assertTrue(hid.calls.isEmpty())
            assertTrue(executor.submittedPlans.isEmpty())
        } finally {
            EditorTrace.sink = originalSink
        }
    }


    @Test fun public_result_surface_omits_target_program_state() {
        val forbiddenTerms = listOf(
            "caret",
            "selection",
            "programstate",
            "assumeddocument",
            "point",
            "revision",
        )
        val publicAccessors = listOf(
            EditorResult::class.java,
            EditorResult.Synced::class.java,
            EditorResult.EditorFailure::class.java,
            EditorState::class.java,
        ).flatMap { type ->
            type.methods.filter { method -> method.declaringClass == type }.map { it.name }
        }.map(String::lowercase)

        assertFalse(publicAccessors.any { accessor ->
            forbiddenTerms.any(accessor::contains)
        })
    }

    @Test fun verification_sink_and_retained_plan_preserve_full_delivered_evidence() {
        val executor = RecordingEditorExecutor(
            ArrayDeque(
                listOf(
                    ExecutionOutcome.Delivered(emptyList()),
                    ExecutionOutcome.Delivered(emptyList()),
                ),
            ),
        )
        val target = EditorTestFixtures.target()
        val editor = Editor(target, executor, RecordingHid())
        val entries = mutableListOf<VerificationEntry>()
        val originalSink = EditorTrace.sink
        EditorTrace.sink = object : VerificationSink {
            override fun record(entry: VerificationEntry) {
                entries += entry
            }
        }

        try {
            assertTrue(editor.setText("known") is EditorResult.Synced)
            val result = editor.setText("known!") as EditorResult.Synced
            assertEquals(2, entries.size)
            val entry = entries.last()
            val retained = editor.verificationState.lastPlan!!

            assertEquals(TargetPackage.HOST_ABI_VERSION, entry.abiVersion)
            assertEquals(target.id, entry.targetId)
            assertEquals(target.version, entry.targetVersion)
            assertEquals("known", entry.assumedState.buffer)
            assertEquals("known!", entry.desiredText)
            assertEquals("known!", entry.predictedState.buffer)
            assertEquals(result.plan, entry.ops)
            assertNull(entry.classification)

            assertEquals(entry.abiVersion, retained.abiVersion)
            assertEquals(entry.targetId, retained.targetId)
            assertEquals(entry.targetVersion, retained.targetVersion)
            assertEquals(entry.assumedState, retained.assumedState)
            assertEquals(entry.desiredText, retained.desiredText)
            assertEquals(entry.predictedState, retained.predictedState)
            assertEquals(entry.ops, retained.ops)
        } finally {
            EditorTrace.sink = originalSink
        }
    }
}
