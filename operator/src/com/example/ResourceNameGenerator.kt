package com.example

import java.security.MessageDigest
import java.util.*

object ResourceNameGenerator {
    private const val MAX_LENGTH = 63
    private const val PREFIX = "agent-review-"

    fun baseName(requestName: String): String {
        require(requestName.isNotEmpty()) { "request name must not be empty" }

        val normalized =
            requestName
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
        require(normalized.isNotEmpty()) { "request name must contain a DNS-1123 character" }

        val readable = PREFIX + normalized
        if (readable.length <= MAX_LENGTH) return readable

        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(requestName.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(8)
        val suffix = "-$hash"
        val prefixLength = MAX_LENGTH - suffix.length
        return readable.take(prefixLength).trimEnd('-') + suffix
    }
}
