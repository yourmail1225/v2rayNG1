package com.v2ray.ang.util

/**
 * Minimal RFC 4648 Base64 codec used only for the optional panel-configuration field
 * of the activation screen. Kept dependency-free so the codec is JVM-testable; the
 * panel generates the same encoding for its setup code.
 */
object ActivationCodec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Encodes bytes with standard padding, exactly like the panel's base64 output. */
    fun encode(raw: ByteArray): String {
        val out = StringBuilder((raw.size + 2) / 3 * 4)
        var i = 0
        while (i < raw.size) {
            val chunk = ((raw[i].toInt() and 0xFF) shl 16) or
                ((if (i + 1 < raw.size) raw[i + 1].toInt() and 0xFF else 0) shl 8) or
                (if (i + 2 < raw.size) raw[i + 2].toInt() and 0xFF else 0)
            out.append(ALPHABET[(chunk ushr 18) and 0x3F])
            out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            out.append(if (i + 1 < raw.size) ALPHABET[(chunk ushr 6) and 0x3F] else '=')
            out.append(if (i + 2 < raw.size) ALPHABET[chunk and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Decodes a standard Base64 string; throws [IllegalArgumentException] if invalid. */
    fun decode(encoded: String): ByteArray {
        val clean = encoded.filter { it != ' ' && it != '\t' && it != '\n' && it != '\r' }
        if (clean.isEmpty()) return ByteArray(0)
        var padding = 0
        if (clean.endsWith("==")) padding = 2 else if (clean.endsWith("=")) padding = 1
        val body = clean.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(body.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in body) {
            val index = ALPHABET.indexOf(c)
            require(index >= 0) { "invalid base64 character: $c" }
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        require(out.size() * 8 >= body.length * 6 - padding * 8) { "invalid base64 padding" }
        return out.toByteArray()
    }
}