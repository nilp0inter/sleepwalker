package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelOp
import party.iroiro.luajava.Lua

/**
 * Execution policy for the stateful Editor.
 */
enum class ExecutionPolicy {
    PRODUCTION,   // Permits F24
    CONFORMANCE,  // Reserves F24
}

/**
 * Canonical opaque ABI value tree.
 */
sealed class AbiValue {
    object Null : AbiValue() {
        override fun toString(): String = "null"
    }
    data class Bool(val value: Boolean) : AbiValue() {
        override fun toString(): String = value.toString()
    }
    data class Int64(val value: Long) : AbiValue() {
        override fun toString(): String = value.toString()
    }
    data class Str(val value: String) : AbiValue() {
        override fun toString(): String = "\"$value\""
    }
    data class Array(val values: List<AbiValue>) : AbiValue() {
        override fun toString(): String = values.joinToString(prefix = "[", postfix = "]")
    }
    data class Obj(val fields: Map<String, AbiValue>) : AbiValue() {
        override fun toString(): String = fields.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}: ${it.value}" }
    }

    companion object {
        const val MAX_DEPTH = 16
        const val MAX_NODES = 2000
        const val MAX_COLLECTION_SIZE = 500
        const val MAX_STRING_BYTES = 10000
        const val MAX_TOTAL_ENCODED_SIZE = 65536
    }
}

/**
 * Codec for translating between Lua values and [AbiValue], and parsing [SymbolicAction]s.
 */
object AbiCodec {
    private class DecodeState {
        var nodes = 0
        var encodedSize = 0
    }

    /**
     * Decode the Lua value at [index] into an [AbiValue].
     * Enforces limits on depth, nodes, collection sizes, string bytes, and total encoded size.
     * Rejects cycles, metatables, functions, userdata, threads, fractional numbers, etc.
     */
    fun decode(L: Lua, index: Int): AbiValue {
        val state = DecodeState()
        return decodeValue(L, index, 0, state, emptyList())
    }

    private fun decodeValue(L: Lua, index: Int, depth: Int, state: DecodeState, parents: List<Int>): AbiValue {
        if (depth > AbiValue.MAX_DEPTH) {
            throw IllegalArgumentException("Max depth exceeded ($depth > ${AbiValue.MAX_DEPTH})")
        }
        state.nodes++
        if (state.nodes > AbiValue.MAX_NODES) {
            throw IllegalArgumentException("Max nodes exceeded (${state.nodes} > ${AbiValue.MAX_NODES})")
        }

        val absIndex = if (index < 0) L.getTop() + index + 1 else index

        if (L.isNil(absIndex)) {
            state.encodedSize += 8
            if (state.encodedSize > AbiValue.MAX_TOTAL_ENCODED_SIZE) {
                throw IllegalArgumentException("Max encoded size exceeded")
            }
            return AbiValue.Null
        }
        if (L.isBoolean(absIndex)) {
            state.encodedSize += 8
            if (state.encodedSize > AbiValue.MAX_TOTAL_ENCODED_SIZE) {
                throw IllegalArgumentException("Max encoded size exceeded")
            }
            return AbiValue.Bool(L.toBoolean(absIndex))
        }
        if (L.isInteger(absIndex)) {
            state.encodedSize += 8
            if (state.encodedSize > AbiValue.MAX_TOTAL_ENCODED_SIZE) {
                throw IllegalArgumentException("Max encoded size exceeded")
            }
            return AbiValue.Int64(L.toInteger(absIndex))
        }
        if (L.isNumber(absIndex)) {
            // Number is double but not integer, i.e., fractional
            throw IllegalArgumentException("Fractional numbers are not supported in ABI")
        }
        if (L.isString(absIndex)) {
            val s = L.toString(absIndex) ?: ""
            val bytes = s.toByteArray(Charsets.UTF_8).size
            if (bytes > AbiValue.MAX_STRING_BYTES) {
                throw IllegalArgumentException("String bytes limit exceeded ($bytes > ${AbiValue.MAX_STRING_BYTES})")
            }
            state.encodedSize += 8 + bytes
            if (state.encodedSize > AbiValue.MAX_TOTAL_ENCODED_SIZE) {
                throw IllegalArgumentException("Max encoded size exceeded")
            }
            return AbiValue.Str(s)
        }
        if (L.isTable(absIndex)) {
            // Reject metatables
            if (L.getMetatable(absIndex) != 0) {
                L.pop(1) // pop the metatable
                throw IllegalArgumentException("Tables with metatables are rejected")
            }

            // Reject cycles
            for (parentAbs in parents) {
                if (L.rawEqual(absIndex, parentAbs)) {
                    throw IllegalArgumentException("Cyclic table reference detected")
                }
            }

            val keys = mutableListOf<Any>()
            val values = mutableListOf<AbiValue>()

            L.pushNil() // first key
            val newParents = parents + absIndex
            try {
                while (L.next(absIndex) != 0) {
                    val keyTop = L.getTop() - 1
                    val valTop = L.getTop()

                    val key: Any = if (L.isInteger(keyTop)) {
                        L.toInteger(keyTop)
                    } else if (L.isString(keyTop)) {
                        L.toString(keyTop) ?: throw IllegalArgumentException("Invalid string key")
                    } else {
                        throw IllegalArgumentException("Table key must be integer or string")
                    }

                    val value = decodeValue(L, valTop, depth + 1, state, newParents)
                    keys.add(key)
                    values.add(value)

                    L.pop(1) // pop value, keep key
                }
            } catch (e: Exception) {
                throw e
            }

            if (keys.isEmpty()) {
                state.encodedSize += 8
                if (state.encodedSize > AbiValue.MAX_TOTAL_ENCODED_SIZE) {
                    throw IllegalArgumentException("Max encoded size exceeded")
                }
                return AbiValue.Array(emptyList())
            }

            if (keys.size > AbiValue.MAX_COLLECTION_SIZE) {
                throw IllegalArgumentException("Collection size limit exceeded (${keys.size} > ${AbiValue.MAX_COLLECTION_SIZE})")
            }

            val allInt = keys.all { it is Long }
            val allStr = keys.all { it is String }

            if (!allInt && !allStr) {
                throw IllegalArgumentException("Mixed table shapes (both integer and string keys) are rejected")
            }

            if (allInt) {
                val intKeys = keys.map { it as Long }
                val sortedKeys = intKeys.sorted()
                val n = keys.size.toLong()
                for (i in 1..n) {
                    if (sortedKeys[(i - 1).toInt()] != i) {
                        throw IllegalArgumentException("Sparse arrays are rejected")
                    }
                }
                val orderedValues = ArrayList<AbiValue>(keys.size)
                for (i in 1..n) {
                    val origIdx = intKeys.indexOf(i)
                    orderedValues.add(values[origIdx])
                }
                return AbiValue.Array(orderedValues)
            } else {
                val strKeys = keys.map { it as String }
                val uniqueKeys = strKeys.toSet()
                if (uniqueKeys.size != strKeys.size) {
                    throw IllegalArgumentException("Duplicate object keys are rejected")
                }
                val fields = mutableMapOf<String, AbiValue>()
                for (i in strKeys.indices) {
                    fields[strKeys[i]] = values[i]
                }
                return AbiValue.Obj(fields)
            }
        }

        throw IllegalArgumentException("Unsupported Lua type: functions, userdata, and threads are rejected")
    }

    /**
     * Encode an [AbiValue] back to a Lua representation in [L].
     */
    fun encode(L: Lua, value: AbiValue) {
        when (value) {
            is AbiValue.Null -> L.pushNil()
            is AbiValue.Bool -> L.push(value.value)
            is AbiValue.Int64 -> L.push(value.value)
            is AbiValue.Str -> L.push(value.value)
            is AbiValue.Array -> {
                L.newTable()
                value.values.forEachIndexed { idx, item ->
                    L.push((idx + 1).toLong())
                    encode(L, item)
                    L.setTable(-3)
                }
            }
            is AbiValue.Obj -> {
                L.newTable()
                value.fields.forEach { (k, v) ->
                    encode(L, v)
                    L.setField(-2, k)
                }
            }
        }
    }

    /**
     * Parse a symbolic action from its decoded [AbiValue].
     */
    fun parseSymbolicAction(value: AbiValue): SymbolicAction {
        if (value !is AbiValue.Obj) {
            throw IllegalArgumentException("Symbolic action must be an object")
        }
        val kindVal = value.fields["kind"] as? AbiValue.Str
            ?: throw IllegalArgumentException("Symbolic action must have a string field 'kind'")
        val kind = kindVal.value

        val allowedKeys = when (kind) {
            "tap", "down", "up" -> setOf("kind", "usage")
            "text" -> setOf("kind", "text")
            else -> throw IllegalArgumentException("Unsupported symbolic action kind: $kind")
        }
        val extraKeys = value.fields.keys - allowedKeys
        if (extraKeys.isNotEmpty()) {
            throw IllegalArgumentException("Symbolic action contains extra fields: $extraKeys")
        }

        return when (kind) {
            "tap" -> {
                val usage = (value.fields["usage"] as? AbiValue.Str)?.value
                    ?: throw IllegalArgumentException("Tap action missing 'usage'")
                SymbolicAction.Tap(usage)
            }
            "down" -> {
                val usage = (value.fields["usage"] as? AbiValue.Str)?.value
                    ?: throw IllegalArgumentException("Down action missing 'usage'")
                SymbolicAction.Down(usage)
            }
            "up" -> {
                val usage = (value.fields["usage"] as? AbiValue.Str)?.value
                    ?: throw IllegalArgumentException("Up action missing 'usage'")
                SymbolicAction.Up(usage)
            }
            "text" -> {
                val text = (value.fields["text"] as? AbiValue.Str)?.value
                    ?: throw IllegalArgumentException("Text action missing 'text'")
                SymbolicAction.Text(text)
            }
            else -> throw IllegalArgumentException("Unsupported symbolic action kind: $kind")
        }
    }
}

/**
 * Closed set of symbolic action values returned by target planner.
 */
sealed class SymbolicAction {
    data class Tap(val usage: String) : SymbolicAction()
    data class Down(val usage: String) : SymbolicAction()
    data class Up(val usage: String) : SymbolicAction()
    data class Text(val text: String) : SymbolicAction()
}

/**
 * Retained plan record for verification and diagnostics.
 */
data class RetainedPlan(
    val abiVersion: Int,
    val targetId: String,
    val targetVersion: String,
    val targetSourceHash: String,
    val currentText: String,
    val desiredText: String,
    val opaqueInputState: AbiValue,
    val opaqueOutputState: AbiValue?,
    val symbolicActions: List<SymbolicAction>?,
    val ops: List<LowLevelOp>,
    val layoutId: String,
    val costMetricId: String,
    val policyId: String,
    val outcome: String,
)

/**
 * A single verification record emitted after an Editor setText call completes.
 */
data class VerificationEntry(
    val abiVersion: Int,
    val targetId: String,
    val targetVersion: String,
    val targetSourceHash: String,
    val currentText: String,
    val desiredText: String,
    val opaqueInputState: AbiValue,
    val opaqueOutputState: AbiValue?,
    val symbolicActions: List<SymbolicAction>?,
    val ops: List<LowLevelOp>,
    val layoutId: String,
    val costMetricId: String,
    val policyId: String,
    val outcome: String,
    val classification: FailureClassification?,
)
