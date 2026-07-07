package io.sleepwalker.core.keymap

import android.content.res.Resources
import org.json.JSONObject

/**
 * Runtime keymap database backed by bundled JSON resources.
 *
 * OmniKeymap layout JSON files are shipped as Android raw resources under
 * `res/raw/` with the naming scheme `keymap_<platform>_<stem>.json`, where
 * `<stem>` is the original OmniKeymap filename stem (with `+` replaced by
 * `_`). At first access the parser scans the available `keymap_*` raw
 * resources, parses each file, and builds a `Map<HostProfile, List<KeymapEntry>>`
 * that is cached for the lifetime of this instance.
 *
 * The X11/Web key names used by OmniKeymap are translated to USB HID usage
 * ids via [X11_TO_USB] and [MODIFIERS].
 *
 * @property resources the Android resources used to open raw JSON files.
 */
class JsonKeymapDatabase(private val resources: Resources) : KeymapDatabase {

    /**
     * Android resource name prefix used by the build step when copying the
     * OmniKeymap database into `res/raw/`. All bundled keymap JSON files
     * are named `keymap_<platform>_<stem>.json`.
     */
    private val keymapPrefix = "keymap_"

    @Volatile private var cached: Map<HostProfile, List<KeymapEntry>>? = null

    override fun lookup(profile: HostProfile): List<KeymapEntry>? =
        ensureLoaded()[profile]

    override val profiles: Collection<HostProfile>
        get() = ensureLoaded().keys

    private fun ensureLoaded(): Map<HostProfile, List<KeymapEntry>> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val built = buildMap()
            cached = built
            return built
        }
    }

    private fun buildMap(): Map<HostProfile, List<KeymapEntry>> {
        val result = LinkedHashMap<HostProfile, List<KeymapEntry>>()
        try {
            val rawClass = Class.forName("io.sleepwalker.core.R\$raw")
            val fields = rawClass.fields
            for (field in fields) {
                val name = field.name
                if (name.startsWith(keymapPrefix)) {
                    val rawId = field.getInt(null)
                    val parsed = parseFile(rawId) ?: continue
                    if (parsed.entries.isNotEmpty()) {
                        val stemPrefix = "${keymapPrefix}${parsed.platform.lowercase()}_${parsed.layout.lowercase()}_"
                        val variant = if (name.startsWith(stemPrefix)) {
                            name.removePrefix(stemPrefix)
                        } else {
                            null
                        }
                        val profile = HostProfile(
                            parsed.platform,
                            parsed.layout,
                            if (variant.isNullOrEmpty()) null else variant
                        )
                        result[profile] = parsed.entries
                    }
                }
            }
        } catch (e: ClassNotFoundException) {
            // Expected in non-Android environments (like JVM unit tests)
        } catch (e: Exception) {
            android.util.Log.e("JsonKeymapDatabase", "Failed to build keymap database", e)
        }
        return result
    }

    private data class ParsedKeymap(
        val platform: String,
        val layout: String,
        val entries: List<KeymapEntry>
    )

    private fun parseFile(rawId: Int): ParsedKeymap? {
        val text = resources.openRawResource(rawId).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val metadata = root.optJSONObject("metadata") ?: return null
        val platform = metadata.optString("platform", "")
        val layoutName = metadata.optString("layout_name", "")
        if (platform.isEmpty() || layoutName.isEmpty()) return null

        val mappings = root.optJSONObject("mappings") ?: return null
        val entries = ArrayList<KeymapEntry>(mappings.length())
        val keys = mappings.keys()
        while (keys.hasNext()) {
            val chStr = keys.next()
            // Task 3.6: skip multi-character entries that cannot fit in a Char.
            if (chStr.length != 1) continue
            val ch = chStr[0]
            val sequences = mappings.optJSONArray(chStr) ?: continue
            
            var resolvedTaps: List<KeymapTap>? = null
            for (i in 0 until sequences.length()) {
                val seq = sequences.optJSONObject(i) ?: continue
                val keyArr = seq.optJSONArray("sequence") ?: continue
                val seqTaps = ArrayList<KeymapTap>(keyArr.length())
                var allKeysResolved = true
                for (j in 0 until keyArr.length()) {
                    val keyPress = keyArr.optJSONObject(j) ?: continue
                    val keyName = keyPress.optString("key")
                    val usage = X11_TO_USB[keyName]
                    if (usage == null) {
                        allKeysResolved = false
                        break
                    }
                    val mods = parseModifiers(keyPress.optJSONArray("modifiers"))
                    seqTaps.add(KeymapTap(usage, mods))
                }
                if (allKeysResolved && seqTaps.isNotEmpty()) {
                    resolvedTaps = seqTaps
                    break // Choose the first fully resolvable alternative sequence
                }
            }
            
            if (resolvedTaps != null) {
                entries.add(KeymapEntry(ch, resolvedTaps))
            }
        }
        return ParsedKeymap(platform, layoutName, entries)
    }

    companion object {
        /**
         * X11/Web key name to USB HID keyboard usage id (usage page 0x07).
         * Mirrors the mapping previously implemented in `generator.py`.
         */
        private val X11_TO_USB: Map<String, Int> = mapOf(
            // Letters A..Z -> 0x04..0x1D
            "KeyA" to 0x04, "KeyB" to 0x05, "KeyC" to 0x06, "KeyD" to 0x07,
            "KeyE" to 0x08, "KeyF" to 0x09, "KeyG" to 0x0A, "KeyH" to 0x0B,
            "KeyI" to 0x0C, "KeyJ" to 0x0D, "KeyK" to 0x0E, "KeyL" to 0x0F,
            "KeyM" to 0x10, "KeyN" to 0x11, "KeyO" to 0x12, "KeyP" to 0x13,
            "KeyQ" to 0x14, "KeyR" to 0x15, "KeyS" to 0x16, "KeyT" to 0x17,
            "KeyU" to 0x18, "KeyV" to 0x19, "KeyW" to 0x1A, "KeyX" to 0x1B,
            "KeyY" to 0x1C, "KeyZ" to 0x1D,
            // Digits 0..9 -> 0x27, 0x1E..0x26
            "Digit0" to 0x27, "Digit1" to 0x1E, "Digit2" to 0x1F, "Digit3" to 0x20,
            "Digit4" to 0x21, "Digit5" to 0x22, "Digit6" to 0x23, "Digit7" to 0x24,
            "Digit8" to 0x25, "Digit9" to 0x26,
            // Whitespace / control
            "Space" to 0x2C, "Enter" to 0x28, "Escape" to 0x29,
            // Punctuation
            "Minus" to 0x2D, "Equal" to 0x2E,
            "BracketLeft" to 0x2F, "BracketRight" to 0x30,
            "Backslash" to 0x31, "NonUSBackslash" to 0x32,
            "Semicolon" to 0x33, "Quote" to 0x34, "Backquote" to 0x35,
            "Comma" to 0x36, "Period" to 0x37, "Slash" to 0x38,
            // International keys
            "IntlBackslash" to 0x32, "IntlRo" to 0x87, "IntlYen" to 0x87
        )

        /**
         * X11/Web modifier name to USB HID modifier bit flag.
         *  bit 0 = left ctrl, bit 1 = left shift, bit 2 = left alt,
         *  bit 3 = left gui, bit 4 = right ctrl, bit 5 = right shift,
         *  bit 6 = right alt, bit 7 = right gui
         */
        private val MODIFIERS: Map<String, Int> = mapOf(
            "Shift" to 0x02,
            "Control" to 0x01, "Ctrl" to 0x01,
            "Alt" to 0x04, "AltLeft" to 0x04, "AltRight" to 0x40,
            "AltGraph" to 0x40, "AltGr" to 0x40,
            "Meta" to 0x08, "Super" to 0x08, "GUI" to 0x08,
            "MetaLeft" to 0x08, "MetaRight" to 0x80,
            "ShiftLeft" to 0x02, "ShiftRight" to 0x20,
            "ControlLeft" to 0x01, "ControlRight" to 0x10
        )

        /**
         * Resolve a list of modifier names to a combined bitmask.
         */
        internal fun parseModifiers(mods: org.json.JSONArray?): Int {
            if (mods == null) return 0
            var mask = 0
            for (i in 0 until mods.length()) {
                val name = mods.optString(i)
                mask = mask or (MODIFIERS[name] ?: 0)
            }
            return mask
        }
    }
}