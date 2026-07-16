package io.sleepwalker.core.editor

import android.content.res.AssetManager
import io.sleepwalker.core.hid.LowLevelHid
import java.io.IOException

/**
 * Loader for bundled trusted target packages from Android app assets.
 *
 * Reads `targets/<id>/manifest.lua` and `targets/<id>/main.lua` from
 * the APK's bundled assets. Validates the manifest `host_abi` field
 * against [TargetPackage.HOST_ABI_VERSION] and returns a populated
 * [TargetPackage].
 *
 * Manifest parsing uses a simple line-oriented Lua literal parser
 * sufficient for the constrained declarative format (trusted input).
 *
 * @property assetManager the Android [AssetManager] for reading
 *   bundled resources.
 */
class TargetPackageLoader(private val assetManager: AssetManager) {
    companion object {
        val BUNDLED_PACKAGE_IDS: Set<String> = setOf("readline-emacs-ascii")
    }


    /**
     * List available package IDs.
     *
     * Returns the names of subdirectories under `assets/targets/`.
     */
    fun availablePackages(): Set<String> {
        val packaged = try {
            assetManager.list("targets")?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        return BUNDLED_PACKAGE_IDS.intersect(packaged)
    }

    /** Create an Editor from a package proven to be in the bundled registry. */
    fun createEditor(id: String, executor: EditorExecutor, hid: LowLevelHid): Editor =
        Editor(load(id), executor, hid)

    /**
     * Load a target package by ID.
     *
     * @throws IOException if the package directory or its files are not
     *   present in the bundled assets.
     * @throws IllegalArgumentException if the manifest is malformed or
     *   the ABI version does not match.
     */
    internal fun load(id: String): TargetPackage {
        require(id in BUNDLED_PACKAGE_IDS) {
            "Target package '$id' is not in the bundled package registry"
        }

        val basePath = "targets/$id"

        val manifestText = try {
            readAsset("$basePath/manifest.lua")
        } catch (e: IOException) {
            throw IOException("Target package '$id' manifest not found", e)
        }

        val manifest = parseManifest(manifestText)
        require(manifest["id"] == id) {
            "Target package manifest id '${manifest["id"]}' does not match '$id'"
        }


        val hostAbi = manifest["host_abi"] as? Int
            ?: throw IllegalArgumentException("Missing 'host_abi' in $basePath/manifest.lua")


        val mainLua = try {
            readAsset("$basePath/main.lua")
        } catch (e: IOException) {
            throw IOException("Target package '$id' main.lua not found", e)
        }

        return TargetPackage(
            id = manifest.getValue("id") as String,
            version = manifest.getValue("version") as String,
            hostAbi = hostAbi,
            target = manifest.getValue("target") as String,
            targetPin = manifest.getValue("target_pin") as String,
            mode = manifest.getValue("mode") as String,
            charset = manifest.getValue("charset") as String,
            lineModel = manifest.getValue("line_model") as String,
            description = extractDescription(manifest),
            mainLua = mainLua,
        )
    }

    // ── Asset I/O ──

    private fun readAsset(path: String): String =
        assetManager.open(path).bufferedReader().use { it.readText() }

    // ── Manifest parser (Lua literal subset) ──

    /**
     * Parse a Lua `return { ... }` manifest into a key-value map.
     *
     * Handles string values (double or single quoted), integer values,
     * bareword values, and nested `{ ... }` tables for the `describe`
     * field. Lua line comments (`--`) are stripped.
     */
    internal fun parseManifest(text: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val body = text
            .trim()
            .removePrefix("return")
            .trim()
            .removeSurrounding("{", "}")
            .trim()

        // Split on top-level commas (not inside nested {})
        var depth = 0
        val pairs = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in body) {
            when {
                ch == '{' -> { depth++; current.append(ch) }
                ch == '}' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> {
                    pairs.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) pairs.add(last)

        for (pair in pairs) {
            if (pair.startsWith("--") || pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) continue
            val key = pair.substring(0, eqIdx).trim()
            val rawValue = pair.substring(eqIdx + 1).trim()
            result[key] = parseManifestValue(rawValue)
        }

        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseManifestValue(raw: String): Any {
        val value = raw.trimEnd(',')
        return when {
            value.startsWith('"') || value.startsWith('\'') ->
                value.substring(1, value.length - 1)
            value.startsWith('{') -> {
                val inner = value.removeSurrounding("{", "}").trim()
                val map = mutableMapOf<String, Any>()
                if (inner.isNotEmpty()) {
                    for (entry in inner.split(',')) {
                        val trimmed = entry.trim()
                        if (trimmed.isEmpty()) continue
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx < 0) continue
                        val k = trimmed.substring(0, eqIdx).trim()
                        val v = trimmed.substring(eqIdx + 1).trim().trimEnd(',')
                        map[k] = when {
                            v.startsWith('"') || v.startsWith('\'') ->
                                v.substring(1, v.length - 1)
                            v.toIntOrNull() != null -> v.toInt()
                            else -> v
                        }
                    }
                }
                map as Map<String, Any>
            }
            value.toIntOrNull() != null -> value.toInt()
            else -> value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractDescription(manifest: Map<String, Any>): Map<String, String> {
        val desc = manifest["describe"] as? Map<String, Any> ?: return emptyMap()
        return desc.entries.associate { (k, v) -> k to v.toString() }
    }
}

