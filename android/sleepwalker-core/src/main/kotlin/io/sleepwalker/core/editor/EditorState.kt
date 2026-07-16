package io.sleepwalker.core.editor

/**
 * Public Editor state names.
 *
 * Exposes only the state name; no caret, selection, document, or
 * program-state fields appear in the public type.
 *
 * - [Uninitialized]: initial empty state, first `setText` advances to Planning.
 * - [Synced]: last plan delivered; assumed state matches desired.
 * - [Planning]: computing transition; not externally observable during
 *   synchronous `setText`.
 * - [Executing]: executing plan via the serialized executor; not externally
 *   observable during synchronous `setText`.
 * - [Failed]: pre-execution planning/validation failure; target untouched,
 *   old assumed state still valid; next `setText` may succeed (recoverable).
 * - [Unknown]: terminal; partial execution left the target in an unknown
 *   state; all subsequent `setText` calls are rejected until [Editor.reset].
 */
enum class EditorState {
    Uninitialized,
    Synced,
    Planning,
    Executing,
    Failed,
    Unknown,
}
