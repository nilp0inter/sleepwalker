package io.sleepwalker.core.editor

/**
 * A bundled trusted target behavior package.
 *
 * Loaded from app-bundled read-only assets by [TargetPackageLoader].
 * Contains the package identity, versioning metadata, and the Lua
 * source code for the target program.
 *
 * @property id          stable lookup key (e.g. "readline-emacs-ascii").
 * @property version     semver of the package itself.
 * @property hostAbi     required host ABI version; must match [HOST_ABI_VERSION].
 * @property target      target environment identifier (e.g. "gnu-readline").
 * @property targetPin   pinned target implementation (e.g. "8.2").
 * @property mode        target editing mode (e.g. "emacs").
 * @property charset     supported character set (e.g. "ascii-printable").
 * @property lineModel   line model (e.g. "single-line").
 * @property description human-readable target description fields.
 * @property mainLua     the Lua source of the target planning program.
 * @property modules     additional bundled Lua modules keyed by module name.
 */
internal data class TargetPackage(
    val id: String,
    val version: String,
    val hostAbi: Int,
    val target: String,
    val targetPin: String,
    val mode: String,
    val charset: String,
    val lineModel: String,
    val description: Map<String, String> = emptyMap(),
    val mainLua: String,
    val modules: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Current host ABI version supported by this runtime. */
        const val HOST_ABI_VERSION: Int = 1

        /** Allowlisted Lua library names opened by the constrained host. */
        val ALLOWED_LUA_LIBRARIES: Set<String> = setOf(
            "base", "table", "string", "math",
        )

        /** Base function names removed from the constrained environment. */
        val REMOVED_BASE_FUNCTIONS: Set<String> = setOf(
            "load", "dofile", "loadfile", "print", "warn",
        )

        /** Globals removed even when LuaJava or a runtime opens them implicitly. */
        val REMOVED_GLOBALS: Set<String> = setOf(
            "java", "luajava", "io", "os", "debug", "package", "coroutine",
        )

        /** Math members excluded to leave only deterministic integer-safe helpers. */
        val REMOVED_MATH_MEMBERS: Set<String> = setOf(
            "acos", "asin", "atan", "cos", "deg", "exp", "fmod", "huge",
            "log", "modf", "pi", "rad", "random", "randomseed", "sin", "sqrt", "tan",
        )
    }
}
