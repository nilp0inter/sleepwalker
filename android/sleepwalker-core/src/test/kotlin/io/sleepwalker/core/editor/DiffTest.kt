package io.sleepwalker.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {
    @Test fun computes_one_capped_replacement_region_for_snapshot_boundaries() {
        data class Case(
            val name: String,
            val current: String,
            val desired: String,
            val expected: DiffResult,
        )

        val cases = listOf(
            Case("append", "cat", "cats", DiffResult(3, 0, "", "s")),
            Case("prefix replacement", "cat", "bat", DiffResult(0, 2, "c", "b")),
            Case("suffix deletion", "cats", "cat", DiffResult(3, 0, "s", "")),
            Case("middle replacement", "axc", "abc", DiffResult(1, 1, "x", "b")),
            Case("repeated overlap is capped", "aaaa", "aa", DiffResult(2, 0, "aa", "")),
            Case("repeated suffix survives middle edit", "abcabc", "abxabc", DiffResult(2, 3, "c", "x")),
            Case("empty to non-empty", "", "text", DiffResult(0, 0, "", "text")),
            Case("non-empty to empty", "text", "", DiffResult(0, 0, "text", "")),
            Case("both empty", "", "", DiffResult(0, 0, "", "")),
            Case("identical", "same", "same", DiffResult(4, 0, "", "")),
        )

        for (case in cases) {
            val actual = computeDiff(case.current, case.desired)
            assertEquals(case.name, case.expected, actual)
            assertEquals(
                case.name,
                case.desired,
                case.current.take(actual.lcp) + actual.newMid +
                    case.current.drop(case.current.length - actual.lcs),
            )
        }
    }

    @Test fun identifies_only_identical_snapshots_as_no_ops() {
        assertTrue(computeDiff("identical", "identical").isNoOp)
        assertFalse(computeDiff("identical", "identical!").isNoOp)
    }
}
