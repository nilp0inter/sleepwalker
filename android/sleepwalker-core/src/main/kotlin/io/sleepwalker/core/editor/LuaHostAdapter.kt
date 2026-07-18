package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TextPlan
import io.sleepwalker.core.text.TextRenderingFailure
import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.Opcodes
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.lua54.Lua54
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Thrown when loading Lua source code fails.
 */
class LuaLoadException(message: String) : RuntimeException(message)

/**
 * Result of a Lua planner invocation.
 */
sealed class LuaInvocationResult {
    data class Success(
        val actions: List<SymbolicAction>,
        val nextState: AbiValue,
        val compileCache: Map<String, List<LowLevelOp>>
    ) : LuaInvocationResult()

    data class Failure(val classification: FailureClassification) : LuaInvocationResult()
}

/**
 * Constrained Lua 5.4 host adapter.
 * Creates a fresh, isolated VM for every invocation to ensure planning is pure.
 */
internal class LuaHostAdapter(
    private val hid: LowLevelHid,
    private val textPlanner: TextPlanner,
    private val sharedModules: Map<String, String>,
) : AutoCloseable {

    private val costTextPlanner = TextPlanner(hid = NonAllocatingHid)

    private fun directBuffer(source: String): ByteBuffer {
        val bytes = source.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
    }

    /**
     * Run the package's pure state initializer on a fresh VM.
     */
    fun runInitializer(target: TargetPackage, currentText: String): AbiValue {
        val L = Lua54()
        try {
            setupVM(L, target, HostProfile.LINUX_US, "", "", null)

            val chunkName = "@${target.id}/main.lua"
            val buf = directBuffer(target.mainLua)
            L.load(buf, chunkName)
            L.pCall(0, 1)

            if (!L.isTable(-1)) {
                throw LuaLoadException("Package main.lua must return the package table")
            }

            L.getField(-1, "initialize")
            if (L.isNil(-1)) {
                throw LuaLoadException("initialize function not found in package table")
            }

            L.push(currentText)
            L.pCall(1, 1)

            return AbiCodec.decode(L, -1)
        } catch (e: Exception) {
            throw LuaLoadException("Failed to run initializer: ${e.message}")
        } finally {
            L.close()
        }
    }

    /**
     * Run the package's planning function on a fresh VM.
     */
    fun runPlan(
        target: TargetPackage,
        currentText: String,
        desiredText: String,
        opaqueState: AbiValue,
        profile: HostProfile
    ): LuaInvocationResult {
        val L = Lua54()
        val compileCache = mutableMapOf<String, List<LowLevelOp>>()
        val layoutId = profile.key
        val costMetricId = "op_count:1"
        try {
            setupVM(L, target, profile, layoutId, costMetricId, compileCache)

            val chunkName = "@${target.id}/main.lua"
            val buf = directBuffer(target.mainLua)
            L.load(buf, chunkName)
            L.pCall(0, 1)

            if (!L.isTable(-1)) {
                return LuaInvocationResult.Failure(
                    FailureClassification.PlanningError("Package main.lua must return the package table")
                )
            }

            L.getField(-1, "abi_version")
            val pkgAbi = if (L.isInteger(-1)) L.toInteger(-1).toInt() else -1
            L.pop(1)
            if (pkgAbi != TargetPackage.HOST_ABI_VERSION) {
                return LuaInvocationResult.Failure(
                    FailureClassification.AbiMismatch(
                        expected = TargetPackage.HOST_ABI_VERSION,
                        actual = pkgAbi
                    )
                )
            }

            L.getField(-1, "plan")
            if (L.isNil(-1)) {
                return LuaInvocationResult.Failure(
                    FailureClassification.PlanningError("plan function not found in package table")
                )
            }

            L.push(currentText)
            L.push(desiredText)
            AbiCodec.encode(L, opaqueState)

            L.pCall(3, 1)

            val decodedResult = try {
                AbiCodec.decode(L, -1)
            } catch (e: Exception) {
                return LuaInvocationResult.Failure(
                    FailureClassification.PlanningError("Failed to decode plan result: ${e.message}")
                )
            }

            if (decodedResult !is AbiValue.Obj) {
                return LuaInvocationResult.Failure(
                    FailureClassification.PlanningError("Plan result must be an object table")
                )
            }

            val errorVal = decodedResult.fields["error"]
            if (errorVal != null) {
                if (errorVal !is AbiValue.Str) {
                    return LuaInvocationResult.Failure(
                        FailureClassification.PlanningError("Plan error field must be a string")
                    )
                }
                return LuaInvocationResult.Failure(classifyTargetFailure(errorVal.value, desiredText))
            }

            val actionsVal = decodedResult.fields["actions"]
                ?: return LuaInvocationResult.Failure(
                    FailureClassification.PlanningError("Plan result missing 'actions'")
                )
            val nextStateVal = decodedResult.fields["next_state"]
                ?: return LuaInvocationResult.Failure(
                    FailureClassification.PlanningError("Plan result missing 'next_state'")
                )

            if (actionsVal !is AbiValue.Array) {
                return LuaInvocationResult.Failure(
                    FailureClassification.PlanningError("Plan actions must be an array")
                )
            }

            val actions = mutableListOf<SymbolicAction>()
            for (actionVal in actionsVal.values) {
                val action = try {
                    AbiCodec.parseSymbolicAction(actionVal)
                } catch (e: Exception) {
                    return LuaInvocationResult.Failure(
                        FailureClassification.PlanningError("Failed to parse symbolic action: ${e.message}")
                    )
                }
                actions.add(action)
            }

            return LuaInvocationResult.Success(actions, nextStateVal, compileCache)
        } catch (e: Exception) {
            return LuaInvocationResult.Failure(
                FailureClassification.PlanningError("Lua runtime error: ${e.message}")
            )
        } finally {
            L.close()
        }
    }

    private fun setupVM(
        L: Lua54,
        target: TargetPackage,
        profile: HostProfile,
        layoutId: String,
        costMetricId: String,
        compileCache: MutableMap<String, List<LowLevelOp>>?
    ) {
        // 1. Open allowed libraries
        for (lib in TargetPackage.ALLOWED_LUA_LIBRARIES) {
            L.openLibrary(lib)
        }

        // 2. Remove base functions and dangerous globals
        for (name in TargetPackage.REMOVED_BASE_FUNCTIONS) {
            L.pushNil()
            L.setGlobal(name)
        }
        for (name in TargetPackage.REMOVED_GLOBALS) {
            L.pushNil()
            L.setGlobal(name)
        }

        // Strip non-deterministic math members
        L.getGlobal("math")
        for (name in TargetPackage.REMOVED_MATH_MEMBERS) {
            L.pushNil()
            L.setField(-2, name)
        }
        L.pop(1)

        // 3. Create module loader, cache, loading state tables
        L.newTable()
        L.setGlobal("__loaders")
        L.newTable()
        L.setGlobal("__cache")
        L.newTable()
        L.setGlobal("__loading_state")

        // 4. Register shared and local module loaders
        fun registerLoader(name: String, source: String, chunkName: String) {
            val buf = directBuffer(source)
            try {
                L.load(buf, chunkName)
            } catch (e: LuaException) {
                throw LuaLoadException("Failed to compile module '$name': ${e.message}")
            }
            L.getGlobal("__loaders")
            L.pushValue(-2)
            L.setField(-2, name)
            L.pop(2)
        }

        for ((name, source) in sharedModules) {
            registerLoader(name, source, "@$name")
        }
        for ((name, source) in target.modules) {
            if (name.startsWith("sleepwalker.") || name == "sleepwalker") {
                throw IllegalArgumentException("Package local module '$name' shadows reserved namespace")
            }
            registerLoader(name, source, "@$name")
        }

        // Install require
        val requireCode = """
            function require(name)
                if __cache[name] ~= nil then
                    return __cache[name]
                end
                if __loading_state[name] then
                    error("circular dependency detected for module '" .. name .. "'")
                end
                local loader = __loaders[name]
                if loader == nil then
                    error("module '" .. name .. "' not found")
                end
                __loading_state[name] = true
                local ok, result = pcall(loader)
                __loading_state[name] = nil
                if not ok then
                    error("error loading module '" .. name .. "': " .. tostring(result))
                end
                if result == nil then
                    result = true
                end
                __cache[name] = result
                return result
            end
        """.trimIndent()
        val requireBuf = directBuffer(requireCode)
        try {
            L.load(requireBuf, "@require_setup")
        } catch (e: LuaException) {
            throw LuaLoadException("Failed to parse require setup: ${e.message}")
        }
        try {
            L.pCall(0, 0)
        } catch (e: LuaException) {
            throw LuaLoadException("Failed to run require setup: ${e.message}")
        }

        // 5. Expose sleepwalker.cost.text_cost
        L.newTable() // sleepwalker table
        L.newTable() // cost table
        L.push(JFunction { L_fn ->
            val text = L_fn.toString(1)
            if (text == null) {
                L_fn.pushNil()
                L_fn.push(layoutId)
                L_fn.push(costMetricId)
                return@JFunction 3
            }

            val plan = if (compileCache == null) {
                costTextPlanner.plan(text, profile)
            } else {
                val cached = compileCache[text]
                if (cached != null) {
                    TextPlan(plan = cached, failure = null)
                } else {
                    val p = costTextPlanner.plan(text, profile)
                    if (p.ok) {
                        compileCache[text] = p.plan!!
                    }
                    p
                }
            }

            if (plan.ok) {
                L_fn.push(plan.plan!!.size.toLong())
            } else {
                L_fn.pushNil()
            }
            L_fn.push(layoutId)
            L_fn.push(costMetricId)
            3
        })
        L.setField(-2, "text_cost")
        L.setField(-2, "cost")
        L.setGlobal("sleepwalker")
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
        message.startsWith("UnrepresentableContent:") -> {
            val content = message.substringAfter(':').trim()
            val ch = if (content.isNotEmpty()) content[0] else '?'
            FailureClassification.UnrepresentableContent(ch)
        }
        else -> FailureClassification.PlanningError(message)
    }

    override fun close() {
        // VMs are fresh and discarded per invocation
    }

    private object NonAllocatingHid : LowLevelHid {
        override fun nextSeqId(): Int = 0
        override fun arm(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.ARM, byteArrayOf(), seqId)
        override fun disarm(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.DISARM, byteArrayOf(), seqId)
        override fun kill(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KILL, byteArrayOf(), seqId)
        override fun releaseAll(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.RELEASE_ALL, byteArrayOf(), seqId)
        override fun keyTap(usage: HidUsage, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEY_TAP, byteArrayOf(usage.usbUsage.toByte()), seqId)
        override fun keyDown(usage: HidUsage, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEY_DOWN, byteArrayOf(usage.usbUsage.toByte()), seqId)
        override fun keyUp(usage: HidUsage, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEY_UP, byteArrayOf(usage.usbUsage.toByte()), seqId)
        override fun keyboardTapScript(taps: List<Pair<Byte, Byte>>, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEYBOARD_TAP_SCRIPT, byteArrayOf(), seqId)
        override fun mouseRelReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.MOUSE_REL_REPORT, byteArrayOf(), seqId)
    }
}
