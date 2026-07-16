package io.sleepwalker.core.editor

/**
 * Explicit host-owned program state for the GNU Readline Emacs target.
 *
 * Deep-copied into the Lua VM per planning invocation. The Editor
 * commits the returned [nextState] only after complete execution;
 * pre-execution failures discard it; partial execution moves to Unknown
 * and does not commit.
 *
 * @property buffer   predicted Readline line buffer content.
 * @property point    predicted Readline point (0-based index into [buffer]).
 * @property revision monotonic edit counter.
 */
data class ReadlineProgramState(
    val buffer: String = "",
    val point: Int = 0,
    val revision: Long = 0L,
)
