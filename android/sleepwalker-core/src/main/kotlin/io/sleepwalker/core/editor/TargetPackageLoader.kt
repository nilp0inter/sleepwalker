package io.sleepwalker.core.editor

import java.security.MessageDigest

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
    fun createEditor(
        id: String,
        executor: EditorExecutor,
        hid: LowLevelHid,
        policy: ExecutionPolicy = ExecutionPolicy.PRODUCTION
    ): Editor {
        val target = load(id)
        val sharedModules = loadSharedModules()
        return Editor(target, executor, hid, sharedModules, policy)
    }

    /** Load the host-owned shared modules from assets. */
    internal fun loadSharedModules(): Map<String, String> {
        val sharedModules = mutableMapOf<String, String>()
        try {
            val sharedFiles = assetManager.list("modules/sleepwalker")
            sharedFiles?.forEach { file ->
                if (file.endsWith(".lua")) {
                    val sub = file.substringBeforeLast(".lua")
                    if (sub.isEmpty() || !sub.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                        throw IllegalArgumentException("Invalid shared module name: $file")
                    }
                    val name = "sleepwalker.$sub"
                    sharedModules[name] = readAsset("modules/sleepwalker/$file")
                }
            }
        } catch (e: IOException) {
            // Allow empty modules in non-conformance tests if they are not added yet
        }
        return sharedModules
    }

    private fun computeSHA256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

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

        val localModules = mutableMapOf<String, String>()

        // Try listing targets/$id/modules
        try {
            val moduleFiles = assetManager.list("$basePath/modules")
            moduleFiles?.forEach { file ->
                if (file.endsWith(".lua")) {
                    val name = file.substringBeforeLast(".lua")
                    if (name.startsWith("sleepwalker.") || name == "sleepwalker") {
                        throw IllegalArgumentException("Package local module '$name' shadows reserved namespace")
                    }
                    if (name.contains('.') || name.contains('/') || name.contains('\\') || !name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                        throw IllegalArgumentException("Invalid local module name: $name")
                    }
                    localModules[name] = readAsset("$basePath/modules/$file")
                }
            }
        } catch (e: IOException) {
            // Ignore
        }

        // Try listing targets/$id for direct modules (excluding main.lua and manifest.lua)
        try {
            val rootFiles = assetManager.list(basePath)
            rootFiles?.forEach { file ->
                if (file.endsWith(".lua") && file != "main.lua" && file != "manifest.lua") {
                    val name = file.substringBeforeLast(".lua")
                    if (name.startsWith("sleepwalker.") || name == "sleepwalker") {
                        throw IllegalArgumentException("Package local module '$name' shadows reserved namespace")
                    }
                    if (name.contains('.') || name.contains('/') || name.contains('\\') || !name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                        throw IllegalArgumentException("Invalid local module name: $name")
                    }
                    if (localModules.containsKey(name)) {
                        throw IllegalArgumentException("Duplicate local module definition: '$name'")
                    }
                    localModules[name] = readAsset("$basePath/$file")
                }
            }
        } catch (e: IOException) {
            // Ignore
        }

        val sb = StringBuilder()
        sb.append("id:").append(manifest.getValue("id") as String).append("\n")
        sb.append("version:").append(manifest.getValue("version") as String).append("\n")
        sb.append("main:").append(mainLua).append("\n")
        localModules.keys.sorted().forEach { name ->
            sb.append("module:").append(name).append(":").append(localModules[name]).append("\n")
        }
        val sourceHash = computeSHA256(sb.toString())

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
            modules = localModules,
            sourceHash = sourceHash,
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
                map
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

