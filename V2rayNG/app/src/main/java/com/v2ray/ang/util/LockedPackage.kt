package com.v2ray.ang.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Serialization for locked-profile packages.
 *
 * The package is a text blob with a header/footer marker and JSON in between, so
 * multi-line raw profiles (OpenVPN) survive a round trip. Two payload layouts exist:
 * the legacy array form (per-entry config text plus lock conditions) and the object
 * form that carries one shared quota for the whole package plus an optional password:
 * `{"password": "...", "expiryEpochMinute": ..., "dataLimitBytes": ...,
 * "entries": [{"content": "..."}]}`. Package-level values override the per-entry ones,
 * so every profile imported from one package inherits the same expiry and data limit.
 * Import detects the markers; any surrounding text outside the markers is imported
 * normally with no lock applied.
 */
object LockedPackage {
    const val HEADER = "#V2RAYNG-LOCK-PACKAGE-BEGIN#"
    const val FOOTER = "#V2RAYNG-LOCK-PACKAGE-END#"

    data class LockedEntry(
        val content: String,
        val expiryEpochMinute: Long = 0L,
        val dataLimitBytes: Long = 0L,
    )

    data class Parsed(
        val entries: List<LockedEntry>,
        val remaining: String,
        val password: String = "",
    )

    fun encode(entries: List<LockedEntry>): String {
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine(HEADER)
            appendLine(JsonUtil.toJson(entries))
            appendLine(FOOTER)
        }
    }

    fun parse(text: String): Parsed {
        val inBlock = StringBuilder()
        val remaining = StringBuilder()
        var inside = false

        text.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed == HEADER || trimmed == FOOTER) {
                inside = trimmed == HEADER
            } else if (inside) {
                inBlock.appendLine(line)
            } else if (trimmed.isNotBlank()) {
                remaining.appendLine(line)
            }
        }

        val (entries, password) = parseBlock(inBlock.toString())
        return Parsed(entries = entries, remaining = remaining.toString(), password = password)
    }

    /**
     * Reads a locked-package payload. Older releases stored a plain array of config
     * strings; newer array releases store entry objects carrying their own lock
     * conditions; the current object form adds a single shared quota and a password.
     * The layout is detected from parsed JSON without logging, because a block that is
     * absent, legacy-layout, or not JSON at all is the ordinary "pass it through as
     * surrounding text" case rather than a fault, and plain JVM unit tests have no
     * android.util.Log to report to.
     */
    private fun parseBlock(json: String): Pair<List<LockedEntry>, String> {
        if (json.isBlank()) return emptyList<LockedEntry>() to ""
        return try {
            val element = JsonParser.parseString(json)
            when {
                element.isJsonArray -> parseEntries(element.asJsonArray) to ""
                element.isJsonObject -> parseObject(element.asJsonObject)
                else -> emptyList<LockedEntry>() to ""
            }
        } catch (e: Exception) {
            emptyList<LockedEntry>() to ""
        }
    }

    private fun parseObject(obj: JsonObject): Pair<List<LockedEntry>, String> {
        val password = obj.lockString("password")
        val sharedExpiry = obj.lockLong("expiryEpochMinute")
        val sharedLimit = obj.lockLong("dataLimitBytes")
        val entriesElement = obj.get("entries")
        val entries = if (entriesElement != null && entriesElement.isJsonArray) {
            parseEntries(entriesElement.asJsonArray).map { entry ->
                entry.copy(
                    expiryEpochMinute = if (sharedExpiry != 0L) sharedExpiry else entry.expiryEpochMinute,
                    dataLimitBytes = if (sharedLimit != 0L) sharedLimit else entry.dataLimitBytes,
                )
            }
        } else {
            emptyList()
        }
        return entries to password
    }

    private fun parseEntries(array: JsonArray): List<LockedEntry> =
        if (array.any { it.isJsonObject }) {
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val content = obj.lockString("content")
                if (content.isBlank()) null else LockedEntry(
                    content,
                    expiryEpochMinute = obj.lockLong("expiryEpochMinute"),
                    dataLimitBytes = obj.lockLong("dataLimitBytes"),
                )
            }
        } else {
            array.mapNotNull { element ->
                val content = element.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (content.isBlank()) null else LockedEntry(content)
            }
        }

    private fun JsonObject.lockString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.lockLong(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
}