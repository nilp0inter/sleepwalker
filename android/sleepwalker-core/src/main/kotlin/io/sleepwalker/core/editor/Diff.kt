package io.sleepwalker.core.editor

/**
 * Deterministic snapshot differencing result.
 *
 * Produces exactly one contiguous replacement region via longest-common-
 * prefix and longest-common-suffix. The oldMid length plus newMid length
 * may be zero (no-op case).
 *
 * @property lcp     longest common prefix length.
 * @property lcs     longest common suffix length, capped so
 *                   `lcp + lcs <= min(len(current), len(desired))`.
 * @property oldMid  characters to remove (may be empty).
 * @property newMid  characters to insert (may be empty).
 */
data class DiffResult(
    val lcp: Int,
    val lcs: Int,
    val oldMid: String,
    val newMid: String,
) {
    /** True when current and desired are identical (no-op). */
    val isNoOp: Boolean get() = oldMid.isEmpty() && newMid.isEmpty()
}

/**
 * Compute the LCP/LCS single contiguous replacement between [current]
 * and [desired].
 *
 * O(n) in the length of the shorter string, allocation-light (two
 * substring calls). The suffix scan caps lcs so the prefix and suffix
 * regions do not overlap.
 */
fun computeDiff(current: String, desired: String): DiffResult {
    val lcp = current.commonPrefixWith(desired).length
    val maxSuffix = minOf(current.length, desired.length) - lcp
    val lcs = (maxSuffix downTo 0).first { n ->
        current.regionMatches(current.length - n, desired, desired.length - n, n)
    }
    val oldMid = current.substring(lcp, current.length - lcs)
    val newMid = desired.substring(lcp, desired.length - lcs)
    return DiffResult(lcp, lcs, oldMid, newMid)
}
