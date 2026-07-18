package io.sleepwalker.core.editor

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import party.iroiro.luajava.lua54.Lua54
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AbiValueTest {
    @Test fun canonical_values_round_trip_without_field_interpretation() {
        val values = listOf(
            AbiValue.Null,
            AbiValue.Bool(true),
            AbiValue.Bool(false),
            AbiValue.Int64(Long.MIN_VALUE),
            AbiValue.Int64(Long.MAX_VALUE),
            AbiValue.Str("héllo\u0000世界"),
            AbiValue.Array(listOf(AbiValue.Int64(7), AbiValue.Obj(mapOf("nested" to AbiValue.Bool(true))))),
            AbiValue.Obj(mapOf("buffer" to AbiValue.Str("opaque"), "point" to AbiValue.Int64(-12))),
        )

        values.forEachIndexed { index, value ->
            val decoded = Lua54().let { lua ->
                try {
                    AbiCodec.encode(lua, value)
                    AbiCodec.decode(lua, -1)
                } finally {
                    lua.close()
                }
            }
            assertEquals("value case $index", value, decoded)
        }

        assertEquals(
            AbiValue.Array(listOf(AbiValue.Str("first"), AbiValue.Str("second"))),
            decode("return { [2] = 'second', [1] = 'first' }"),
        )
    }

    @Test fun decoder_rejects_every_noncanonical_lua_shape() {
        data class Case(val name: String, val source: String, val expectedMessage: String)

        val cases = listOf(
            Case("function", "return function() end", "Unsupported Lua type"),
            Case("userdata", "return io.stdout", "Unsupported Lua type"),
            Case("thread", "return coroutine.create(function() end)", "Unsupported Lua type"),
            Case("fractional number", "return 1.25", "Fractional numbers"),
            Case("infinite number", "return math.huge", "Fractional numbers"),
            Case("not-a-number", "return 0 / 0", "Fractional numbers"),
            Case("metatable", "return setmetatable({}, {})", "metatables"),
            Case("cycle", "local value = {}; value.self = value; return value", "Cyclic table"),
            Case("sparse array", "return { [1] = 'one', [3] = 'three' }", "Sparse arrays"),
            Case("mixed table", "return { [1] = 'one', label = 'two' }", "Mixed table shapes"),
            Case("non-string record key", "return { [true] = 'not a record' }", "Table key"),
        )

        cases.forEach { case ->
            assertDecodeFails(case.name, case.source, case.expectedMessage)
        }
    }

    @Test fun decoder_enforces_all_graph_and_encoded_resource_bounds() {
        data class Case(val name: String, val source: String, val expectedMessage: String)

        val cases = listOf(
            Case(
                "depth",
                "local root = {}; local p = root; for i = 1, 17 do p.child = {}; p = p.child end; return root",
                "Max depth",
            ),
            Case(
                "collection size",
                "local value = {}; for i = 1, 501 do value[i] = i end; return value",
                "Collection size",
            ),
            Case(
                "node count",
                "local value = {}; for i = 1, 4 do value[i] = {}; for j = 1, 500 do value[i][j] = j end end; return value",
                "Max nodes",
            ),
            Case("string bytes", "return string.rep('x', 10001)", "String bytes"),
            Case(
                "total encoded bytes",
                "return { string.rep('x', 10000), string.rep('x', 10000), string.rep('x', 10000), string.rep('x', 10000), string.rep('x', 10000), string.rep('x', 10000), string.rep('x', 10000) }",
                "Max encoded size",
            ),
        )

        cases.forEach { case ->
            assertDecodeFails(case.name, case.source, case.expectedMessage)
        }
    }

    private fun decode(source: String): AbiValue {
        val lua = Lua54()
        try {
            lua.openLibrary("base")
            lua.openLibrary("string")
            lua.openLibrary("math")
            lua.openLibrary("io")
            lua.openLibrary("coroutine")
            lua.load(directBuffer(source), "@abi-value-test")
            lua.pCall(0, 1)
            return AbiCodec.decode(lua, -1)
        } finally {
            lua.close()
        }
    }

    private fun assertDecodeFails(name: String, source: String, expectedMessage: String) {
        try {
            decode(source)
            fail("$name must be rejected at the ABI boundary")
        } catch (failure: IllegalArgumentException) {
            if (!failure.message.orEmpty().contains(expectedMessage)) {
                throw AssertionError("$name rejected for the wrong reason: ${failure.message}", failure)
            }
        }
    }

    private fun directBuffer(source: String): ByteBuffer =
        source.toByteArray(StandardCharsets.UTF_8).let { bytes ->
            ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
        }
}
