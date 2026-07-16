package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.protocol.Usages
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TextRenderingFailure
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.lua54.Lua54
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Constrained Lua 5.4 host adapter with the versioned `host` ABI.
 *
 * Manages a single [Lua54] instance per target package. For each
 * [planTransition] call the adapter deep-copies the Editor's committed
 * [ReadlineProgramState] into an invocation-local Lua table, calls the
 * target's `plan(...)` function, collects ops via [PlanBuilder], and
 * returns the predicted next program state.
 *
 * Between invocations the Lua VM retains **no** target-specific
 * mutable state beyond the registered planning function, the `host`
 * global table, and the custom `require` loader. Explicit program state
 * is passed in and out each call.
 *
 * @property hid        HID primitive factory.
 * @property textPlanner text planning instance (same profile as Editor).
 */
internal class LuaHostAdapter(
    private val hid: LowLevelHid,
    private val textPlanner: TextPlanner,
) : AutoCloseable {

    private var lua: Lua54 = Lua54()
    private val profile: HostProfile = HostProfile.LINUX_US
    private var currentBuilder: PlanBuilder? = null
    private var targetDescription: Map<String, String> = emptyMap()
    private var initialized = false
    private var loadedPackage: TargetPackage? = null
    private var hasPlanned = false

    /** Charset constant for string-to-ByteBuffer conversion. */
    private val charset = StandardCharsets.UTF_8

    private fun directBuffer(source: String): ByteBuffer {
        val bytes = source.toByteArray(charset)
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
    }

    /**
     * Initialize the Lua runtime and load the target package.
     *
     * Sets up the constrained environment, registers the `host` ABI
     * global table, and loads [TargetPackage.mainLua].
     */
    fun initialize(pkg: TargetPackage) {
        check(pkg.hostAbi == TargetPackage.HOST_ABI_VERSION) {
            "ABI mismatch: package requires ${pkg.hostAbi}, " +
                "host provides ${TargetPackage.HOST_ABI_VERSION}"
        }
        loadedPackage = pkg
        try {
            loadPackageProgram(pkg)
            initialized = true
        } catch (failure: LuaLoadException) {
            loadedPackage = null
            lua.close()
            throw failure
        }
    }

    private fun loadPackageProgram(pkg: TargetPackage) {
        targetDescription = pkg.description
        setupConstrainedEnvironment()
        registerHostFunctions()
        precompilePackageModules(pkg)

        val chunkName = "@${pkg.id}/main.lua"
        val buf = directBuffer(pkg.mainLua)
        try {
            lua.load(buf, chunkName)
        } catch (e: LuaException) {
            throw LuaLoadException("Failed to parse ${pkg.id}/main.lua: ${e.message}")
        }
        try {
            lua.pCall(0, 0)
        } catch (e: LuaException) {
            throw LuaLoadException("Failed to run ${pkg.id}/main.lua: ${e.message}")
        }
    }

    private fun resetInvocationEnvironment() {
        lua.close()
        lua = Lua54()
        currentBuilder = null
        loadPackageProgram(checkNotNull(loadedPackage))
    }

    // ── Constrained environment setup ──

    private fun setupConstrainedEnvironment() {
        // Open only allowlisted Lua libraries
        for (lib in TargetPackage.ALLOWED_LUA_LIBRARIES) {
            lua.openLibrary(lib)
        }

        // Remove dangerous base functions
        for (name in TargetPackage.REMOVED_BASE_FUNCTIONS) {
            lua.pushNil()
            lua.setGlobal(name)
        }

        for (name in TargetPackage.REMOVED_GLOBALS) {
            lua.pushNil()
            lua.setGlobal(name)
        }

        // Strip floating-point, transcendental, constant, and random members;
        // targets retain only deterministic integer-safe math helpers.
        lua.getGlobal("math")
        for (name in TargetPackage.REMOVED_MATH_MEMBERS) {
            lua.pushNil()
            lua.setField(-2, name)
        }
        lua.pop(1)

        // Create __modules table for custom require
        lua.newTable()
        lua.setGlobal("__modules")

        // Install custom require (replaces the disabled package library).
        // This require does NOT call global load() — each module has been
        // pre-compiled into a Lua closure by precompilePackageModules().
        val requireCode = """
function require(name)
    local mod = __modules[name]
    if mod == nil then
        error("module '" .. name .. "' not found (bundled only)")
    end
    if type(mod) == "table" then
        return mod
    end
    local result = mod()
    __modules[name] = result or true
    return result
end
""".trimIndent()
        val requireBuf = directBuffer(requireCode)
        try {
            lua.load(requireBuf, "@require_setup")
        } catch (e: LuaException) {
            throw LuaLoadException("Failed to parse require setup: ${e.message}")
        }
        try {
            lua.pCall(0, 0)
        } catch (e: LuaException) {
            throw LuaLoadException("Failed to run require setup: ${e.message}")
        }
    }

    /**
     * Pre-compile each bundled module and store the resulting closure
     * in the `__modules` global table. The Lua `require` function
     * invokes these closures directly — no global `load` call needed.
     */
    private fun precompilePackageModules(pkg: TargetPackage) {
        for ((name, source) in pkg.modules) {
            val buf = directBuffer(source)
            try {
                lua.load(buf, "@$name")
            } catch (e: LuaException) {
                throw LuaLoadException("Failed to compile module '$name': ${e.message}")
            }
            // Compiled function is at stack top; store in __modules[name]
            lua.getGlobal("__modules")
            // Stack: [fn, modules]
            lua.pushValue(-2) // copy fn to top
            // Stack: [fn, modules, fn_copy]
            lua.setField(-2, name) // modules[name] = fn_copy; pops fn_copy
            // Stack: [fn, modules]
            lua.pop(1) // pop modules
            // Stack: [fn]
            lua.pop(1) // pop fn
            // Stack: []
        }
    }

    // ── Host ABI table registration ──

    private fun registerHostFunctions() {
        lua.createTable(0, 7) // host table, pre-allocate 7 hash entries

        // host.abi_version() → int
        lua.push(JFunction { L ->
            L.push(TargetPackage.HOST_ABI_VERSION.toLong())
            1
        })
        lua.setField(-2, "abi_version")

        // host.describe() → { name, charset, line_model, mode, target, target_pin }
        val desc = targetDescription
        lua.push(JFunction { L ->
            L.newTable()
            L.push(desc["name"] ?: ""); L.setField(-2, "name")
            L.push(desc["charset"] ?: ""); L.setField(-2, "charset")
            L.push(desc["line_model"] ?: ""); L.setField(-2, "line_model")
            L.push(desc["mode"] ?: ""); L.setField(-2, "mode")
            L.push(desc["target"] ?: ""); L.setField(-2, "target")
            L.push(desc["target_pin"] ?: ""); L.setField(-2, "target_pin")
            1
        })
        lua.setField(-2, "describe")

        // host.ctrl(keyName) → int
        lua.push(JFunction { L ->
            val name = L.toString(1) ?: error("ctrl: missing argument")
            val builder = currentBuilder ?: error("no active plan builder")
            val count = builder.ctrl(name)
            L.push(count.toLong())
            1
        })
        lua.setField(-2, "ctrl")

        // host.key_tap(name) → int
        lua.push(JFunction { L ->
            val name = L.toString(1) ?: error("key_tap: missing argument")
            val builder = currentBuilder ?: error("no active plan builder")
            val count = builder.keyTap(name)
            L.push(count.toLong())
            1
        })
        lua.setField(-2, "key_tap")

        // host.key_down(name) → int
        lua.push(JFunction { L ->
            val name = L.toString(1) ?: error("key_down: missing argument")
            val builder = currentBuilder ?: error("no active plan builder")
            val count = builder.keyDown(name)
            L.push(count.toLong())
            1
        })
        lua.setField(-2, "key_down")

        // host.key_up(name) → int
        lua.push(JFunction { L ->
            val name = L.toString(1) ?: error("key_up: missing argument")
            val builder = currentBuilder ?: error("no active plan builder")
            val count = builder.keyUp(name)
            L.push(count.toLong())
            1
        })
        lua.setField(-2, "key_up")

        // host.text_plan(text) → int
        lua.push(JFunction { L ->
            val text = L.toString(1) ?: error("text_plan: missing argument")
            val builder = currentBuilder ?: error("no active plan builder")
            val count = builder.textPlan(text)
            L.push(count.toLong())
            1
        })
        lua.setField(-2, "text_plan")

        lua.setGlobal("host")
    }

    // ── Planning invocation ──

    /**
     * Compute a transition plan and predicted next state.
     *
     * Deep-copies [state] into an invocation-local Lua table, calls the
     * target's `plan` function, and collects ops emitted through host
     * ABI functions into [PlanBuilder].
     *
     *   plan(current, desired, lcp, oldMid, newMid, state, predictedPoint)
     *       → nextState | nil, structuredError
     *
     * Returns [TransitionResult.success] with ops and nextState, or
     * [TransitionResult.failure] with a [FailureClassification].
     */
    fun planTransition(
        current: String,
        desired: String,
        lcp: Int,
        oldMid: String,
        newMid: String,
        state: ReadlineProgramState,
    ): TransitionResult {
        if (!initialized) {
            return TransitionResult.failure(
                FailureClassification.PlanningError("Lua host not initialized")
            )
        }

        if (hasPlanned) {
            resetInvocationEnvironment()
        }
        hasPlanned = true

        val builder = PlanBuilder(hid, textPlanner, profile)
        currentBuilder = builder

        try {
            // Resolve the Lua plan function
            lua.getGlobal("plan")
            if (lua.isNil(-1)) {
                lua.pop(1)
                return TransitionResult.failure(
                    FailureClassification.PlanningError("plan function not found in target")
                )
            }

            // Push transition context, explicit state, and predicted point.
            lua.push(current)
            lua.push(desired)
            lua.push(lcp.toLong())
            lua.push(oldMid)
            lua.push(newMid)

            // Push state table (deep copy)
            lua.newTable()
            lua.push(state.buffer); lua.setField(-2, "buffer")
            lua.push(state.point.toLong()); lua.setField(-2, "point")
            lua.push(state.revision); lua.setField(-2, "revision")
            lua.push((lcp + newMid.length).toLong())

            // Request two results so a target rejection retains its reason.
            lua.pCall(7, 2)

            if (lua.isNil(-2)) {
                val message = lua.toString(-1) ?: "Target rejected the transition"
                lua.pop(2)
                return TransitionResult.failure(classifyTargetFailure(message, desired))
            }

            val nextState = readProgramState(lua, -2)
            lua.pop(2)
            return TransitionResult.success(builder.ops, nextState)
        } catch (e: LuaException) {
            return TransitionResult.failure(
                FailureClassification.PlanningError(e.message ?: e.toString())
            )
        } finally {
            currentBuilder = null
        }
    }

    // ── Lua state helpers ──

    private fun readProgramState(L: Lua54, index: Int): ReadlineProgramState {
        val absIdx = if (index < 0) L.getTop() + index + 1 else index

        L.getField(absIdx, "buffer")
        val buffer = if (L.isNil(-1)) "" else L.toString(-1) ?: ""
        L.pop(1)

        L.getField(absIdx, "point")
        val point = if (L.isNil(-1)) 0 else L.toInteger(-1).toInt()
        L.pop(1)

        L.getField(absIdx, "revision")
        val revision = if (L.isNil(-1)) 0L else L.toInteger(-1)
        L.pop(1)

        return ReadlineProgramState(buffer, point, revision)
    }

    private fun classifyTargetFailure(
        message: String,
        desired: String,
    ): FailureClassification = when {
        message.startsWith("UnsupportedBehavior:") ->
            FailureClassification.UnsupportedBehavior(
                message.substringAfter(':').trim(),
            )
        message.startsWith("InconsistentPrediction:") ->
            FailureClassification.InconsistentPrediction(
                expected = desired,
                predicted = message.substringAfter(':').trim(),
            )
        else -> FailureClassification.PlanningError(message)
    }

    override fun close() {
        if (initialized) {
            lua.close()
            loadedPackage = null
            hasPlanned = false
            initialized = false
        }
    }
}

// ── Internal types ──

/**
 * Accumulates [LowLevelOp]s emitted by Lua host ABI functions during
 * a single [LuaHostAdapter.planTransition] invocation.
 */
internal class PlanBuilder(
    private val hid: LowLevelHid,
    private val textPlanner: TextPlanner,
    private val profile: HostProfile = HostProfile.LINUX_US,
) {
    private val _ops = mutableListOf<LowLevelOp>()

    /** Immutable snapshot of accumulated ops. */
    val ops: List<LowLevelOp> get() = _ops.toList()

    /** Emit a modifier‑aware keyboard tap by symbolic usage name. */
    fun keyTap(name: String): Int {
        rejectF24(name)
        _ops.add(hid.keyTap(Usages.byName(name)))
        return _ops.size
    }

    /** Press a key down (no automatic release). */
    fun keyDown(name: String): Int {
        rejectF24(name)
        _ops.add(hid.keyDown(Usages.byName(name)))
        return _ops.size
    }

    /** Release a key. */
    fun keyUp(name: String): Int {
        rejectF24(name)
        _ops.add(hid.keyUp(Usages.byName(name)))
        return _ops.size
    }

    /**
     * Emit a control chord: ctrl-down, key_tap(keyName), ctrl-up.
     * [keyName] is a short key name ("A", "B", …) — not a USB usage
     * name. The adapter prepends `USB_KEY_` before lookup.
     */
    fun ctrl(keyName: String): Int {
        val usageName = "USB_KEY_$keyName"
        rejectF24(usageName)
        val usage = Usages.byName(usageName)
        _ops.add(hid.keyDown(Usages.USB_KEY_LEFTCTRL))
        _ops.add(hid.keyTap(usage))
        _ops.add(hid.keyUp(Usages.USB_KEY_LEFTCTRL))
        return _ops.size
    }

    /**
     * Delegate printable text to [TextPlanner].
     * Throws [IllegalArgumentException] on unrepresentable content.
     */
    fun textPlan(text: String): Int {
        val plan = textPlanner.plan(text, profile)
        if (!plan.ok) {
            val failure = plan.failure!!
            val msg = when (failure) {
                is TextRenderingFailure.MissingLayout ->
                    "missing layout: ${failure.profile}"
                is TextRenderingFailure.UnrepresentableGlyph ->
                    "unrepresentable glyph: '${failure.ch}'"
                else -> "text planning failed"
            }
            throw IllegalArgumentException(msg)
        }
        _ops.addAll(plan.plan!!)
        return _ops.size
    }

    private fun rejectF24(name: String) {
        if (name == "USB_KEY_F24" || name == "F24") {
            throw IllegalArgumentException(
                "F24 is reserved for synchronization and unavailable to target packages"
            )
        }
    }
}

/** Result of a single [LuaHostAdapter.planTransition] call. */
data class TransitionResult(
    val ops: List<LowLevelOp>,
    val nextState: ReadlineProgramState?,
    val failure: FailureClassification?,
) {
    val ok: Boolean get() = failure == null && nextState != null

    companion object {
        fun success(ops: List<LowLevelOp>, state: ReadlineProgramState) =
            TransitionResult(ops, state, null)

        fun failure(fc: FailureClassification) =
            TransitionResult(emptyList(), null, fc)
    }
}

/** Thrown when loading Lua source code fails. */
class LuaLoadException(message: String) : RuntimeException(message)
