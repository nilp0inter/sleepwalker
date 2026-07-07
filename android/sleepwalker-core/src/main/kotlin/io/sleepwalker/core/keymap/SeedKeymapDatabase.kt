package io.sleepwalker.core.keymap

/**
 * Seed/conformance keymap database.
 *
 * Contains a small US QWERTY dataset sufficient for unit tests and the
 * high-level text planning conformance scenario. This is NOT a complete
 * global keymap corpus — the [KeymapDatabase] contract allows a future
 * data-ingestion change to ship the full corpus without changing the
 * text rendering API.
 *
 * USB HID keyboard usage ids (usage page 0x07):
 *   a..z = 0x04..0x1d, 1..9 = 0x1e..0x26, 0 = 0x27
 *   space = 0x2c, enter = 0x28, escape = 0x29
 *
 * Modifier mask bits (USB HID keyboard report modifier byte):
 *   bit 0 = left ctrl, bit 1 = left shift, bit 2 = left alt,
 *   bit 3 = left gui, bit 4 = right ctrl, bit 5 = right shift,
 *   bit 6 = right alt, bit 7 = right gui
 */
object SeedKeymapDatabase : KeymapDatabase {
    private const val SHIFT = 0x02

    // USB HID keyboard usage ids.
    private const val A = 0x04
    private const val B = 0x05
    private const val C = 0x06
    private const val D = 0x07
    private const val E = 0x08
    private const val F = 0x09
    private const val G = 0x0A
    private const val H = 0x0B
    private const val I = 0x0C
    private const val J = 0x0D
    private const val K = 0x0E
    private const val L = 0x0F
    private const val M = 0x10
    private const val N = 0x11
    private const val O = 0x12
    private const val P = 0x13
    private const val Q = 0x14
    private const val R = 0x15
    private const val S = 0x16
    private const val T = 0x17
    private const val U = 0x18
    private const val V = 0x19
    private const val W = 0x1A
    private const val X = 0x1B
    private const val Y = 0x1C
    private const val Z = 0x1D
    private const val NUM_1 = 0x1E
    private const val NUM_2 = 0x1F
    private const val NUM_3 = 0x20
    private const val NUM_4 = 0x21
    private const val NUM_5 = 0x22
    private const val NUM_6 = 0x23
    private const val NUM_7 = 0x24
    private const val NUM_8 = 0x25
    private const val NUM_9 = 0x26
    private const val NUM_0 = 0x27
    private const val ENTER = 0x28
    private const val ESCAPE = 0x29
    private const val SPACE = 0x2C
    private const val MINUS = 0x2D
    private const val EQUAL = 0x2E
    private const val LEFTBRACE = 0x2F
    private const val RIGHTBRACE = 0x30
    private const val BACKSLASH = 0x31
    private const val SEMICOLON = 0x33
    private const val APOSTROPHE = 0x34
    private const val GRAVE = 0x35
    private const val COMMA = 0x36
    private const val DOT = 0x37
    private const val SLASH = 0x38

    private fun entry(ch: Char, usage: Int, modifiers: Int = 0): KeymapEntry =
        KeymapEntry(ch, listOf(KeymapTap(usage, modifiers)))

    private val US_ENTRIES: List<KeymapEntry> = listOf(
        // Lowercase letters.
        entry('a', A, 0), entry('b', B, 0),
        entry('c', C, 0), entry('d', D, 0),
        entry('e', E, 0), entry('f', F, 0),
        entry('g', G, 0), entry('h', H, 0),
        entry('i', I, 0), entry('j', J, 0),
        entry('k', K, 0), entry('l', L, 0),
        entry('m', M, 0), entry('n', N, 0),
        entry('o', O, 0), entry('p', P, 0),
        entry('q', Q, 0), entry('r', R, 0),
        entry('s', S, 0), entry('t', T, 0),
        entry('u', U, 0), entry('v', V, 0),
        entry('w', W, 0), entry('x', X, 0),
        entry('y', Y, 0), entry('z', Z, 0),
        // Uppercase letters (shift).
        entry('A', A, SHIFT), entry('B', B, SHIFT),
        entry('C', C, SHIFT), entry('D', D, SHIFT),
        entry('E', E, SHIFT), entry('F', F, SHIFT),
        entry('G', G, SHIFT), entry('H', H, SHIFT),
        entry('I', I, SHIFT), entry('J', J, SHIFT),
        entry('K', K, SHIFT), entry('L', L, SHIFT),
        entry('M', M, SHIFT), entry('N', N, SHIFT),
        entry('O', O, SHIFT), entry('P', P, SHIFT),
        entry('Q', Q, SHIFT), entry('R', R, SHIFT),
        entry('S', S, SHIFT), entry('T', T, SHIFT),
        entry('U', U, SHIFT), entry('V', V, SHIFT),
        entry('W', W, SHIFT), entry('X', X, SHIFT),
        entry('Y', Y, SHIFT), entry('Z', Z, SHIFT),
        // Digits.
        entry('0', NUM_0, 0), entry('1', NUM_1, 0),
        entry('2', NUM_2, 0), entry('3', NUM_3, 0),
        entry('4', NUM_4, 0), entry('5', NUM_5, 0),
        entry('6', NUM_6, 0), entry('7', NUM_7, 0),
        entry('8', NUM_8, 0), entry('9', NUM_9, 0),
        // Shifted digits / symbols.
        entry('!', NUM_1, SHIFT), entry('@', NUM_2, SHIFT),
        entry('#', NUM_3, SHIFT), entry('$', NUM_4, SHIFT),
        entry('%', NUM_5, SHIFT), entry('^', NUM_6, SHIFT),
        entry('&', NUM_7, SHIFT), entry('*', NUM_8, SHIFT),
        entry('(', NUM_9, SHIFT), entry(')', NUM_0, SHIFT),
        // Punctuation keys.
        entry('-', MINUS, 0), entry('_', MINUS, SHIFT),
        entry('=', EQUAL, 0), entry('+', EQUAL, SHIFT),
        entry('[', LEFTBRACE, 0), entry('{', LEFTBRACE, SHIFT),
        entry(']', RIGHTBRACE, 0), entry('}', RIGHTBRACE, SHIFT),
        entry('\\', BACKSLASH, 0), entry('|', BACKSLASH, SHIFT),
        entry(';', SEMICOLON, 0), entry(':', SEMICOLON, SHIFT),
        entry('\'', APOSTROPHE, 0), entry('"', APOSTROPHE, SHIFT),
        entry('`', GRAVE, 0), entry('~', GRAVE, SHIFT),
        entry(',', COMMA, 0), entry('<', COMMA, SHIFT),
        entry('.', DOT, 0), entry('>', DOT, SHIFT),
        entry('/', SLASH, 0), entry('?', SLASH, SHIFT),
        // Control keys.
        entry(' ', SPACE, 0),
        entry('\n', ENTER, 0),
        entry('\u001b', ESCAPE, 0),
    )

    private val BY_KEY: Map<String, List<KeymapEntry>> = mapOf(
        HostProfile.LINUX_US.key to US_ENTRIES,
    )

    private val BY_CH: Map<Char, KeymapEntry> =
        US_ENTRIES.associateBy { it.ch }

    override fun lookup(profile: HostProfile): List<KeymapEntry>? =
        BY_KEY[profile.key]

    override val profiles: Collection<HostProfile> = listOf(HostProfile.LINUX_US)

    /** Resolve a single character to its keymap entry, or null. */
    fun entryFor(ch: Char): KeymapEntry? = BY_CH[ch]
}
