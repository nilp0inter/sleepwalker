package io.sleepwalker.core.protocol

/**
 * Command opcodes for the sleepwalker HID protocol.
 *
 * Mirror of firmware/components/sleepwalker_protocol/include/sleepwalker_protocol.h
 * and protocol/src/sleepwalker_protocol/opcodes.py. Must agree byte-for-byte.
 */
object Opcodes {
    const val RESERVED: Int = 0x0000
    const val ARM: Int = 0x0001
    const val DISARM: Int = 0x0002
    const val KILL: Int = 0x0003
    const val RELEASE_ALL: Int = 0x0004
    const val KEY_TAP: Int = 0x0011
    const val KEY_DOWN: Int = 0x0012
    const val KEY_UP: Int = 0x0013

    val ALL: Set<Int> = setOf(ARM, DISARM, KILL, RELEASE_ALL, KEY_TAP, KEY_DOWN, KEY_UP)

    fun isKnown(opcode: Int): Boolean = opcode in ALL
}