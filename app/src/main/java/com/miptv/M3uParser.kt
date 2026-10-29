package com.miptv

import java.io.InputStream
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streaming M3U/M3U8 parser. Tolerant: `#EXTM3U` is optional, blank lines, Windows line endings,
 * a UTF-8 BOM and unknown directives are ignored. `#EXTVLCOPT` user-agent/referrer hints are kept.
 * Identifiers are stable across refreshes (tvg-id, else group + name) so favourites survive a re-import.
 */
class M3uParser(private val sourceId: Long = 0L) : M3uAdapter {

    suspend fun parse(stream: InputStream): ParsedPlaylist = withContext(Dispatchers.IO) {
        stream.bufferedReader(Charsets.UTF_8).use { parse(it) }
    }

    fun parse(reader: Reader): ParsedPlaylist = parseLines(reader.buffered().lineSequence())

    override fun parseLines(lines: Sequence<String>): ParsedPlaylist {
        val categories = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>()
        val usedIds = HashSet<String>()
        var pending: Entry? = null
        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseExtInf(line)
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> pending?.let { applyVlcOption(it, line) }
                line.startsWith("#EXTGRP", ignoreCase = true) -> pending?.let { entry ->
                    if (entry.group.isEmpty()) entry.group = line.substringAfter(':', "").trim()
                }
                line.startsWith("#") -> Unit // #EXTM3U, comments and unknown directives
                else -> {
                    val entry = pending ?: Entry()
                    pending = null
                    val name = entry.name.ifEmpty { entry.tvgName.ifEmpty { fallbackName(line) } }
                    val groupName = entry.group.ifEmpty { DEFAULT_GROUP }
                    val categoryId = categoryId(groupName)
                    val category = categories.getOrPut(categoryId) { Category(categoryId, groupName) }
                    val key = entry.tvgId.ifEmpty { "$groupName/$name" }
                    var id = channelId(key)
                    var suffix = 1
                    while (!usedIds.add(id)) id = channelId(key) + "-" + (++suffix)
                    channels += Channel(
                        id = id,
                        name = name,
                        categoryId = category.id,
                        streamUrl = line,
                        logoUrl = entry.logo.takeIf { it.isNotEmpty() },
                        tvgId = entry.tvgId.takeIf { it.isNotEmpty() },
                        sourceId = sourceId,
                        userAgent = entry.userAgent,
                        referer = entry.referer
                    )
                }
            }
        }
        return ParsedPlaylist(categories.values.toList(), channels)
    }

    private class Entry {
        var name = ""
        var tvgId = ""
        var tvgName = ""
        var logo = ""
        var group = ""
        var userAgent: String? = null
        var referer: String? = null
    }

    private fun parseExtInf(line: String): Entry {
        val entry = Entry()
        val body = line.substringAfter(':', "")
        // The title starts at the first comma outside quotes; it may itself contain commas.
        var inQuotes = false
        var titleStart = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { titleStart = i; break }
        }
        val attributes = if (titleStart >= 0) body.substring(0, titleStart) else body
        entry.name = if (titleStart >= 0) body.substring(titleStart + 1).trim() else ""
        forEachAttribute(attributes) { key, value ->
            when (key.lowercase()) {
                "tvg-id" -> entry.tvgId = value
                "tvg-name" -> entry.tvgName = value
                "tvg-logo" -> entry.logo = value
                "group-title" -> entry.group = value
            }
        }
        return entry
    }

    /**
     * `key="value"` or `key=value` pairs, keys made of letters, digits, `_` and `-`. Hand-written: a regex per
     * line dominated the import time of large playlists on Android. A pair with an unclosed quote is skipped.
     */
    private inline fun forEachAttribute(text: String, action: (String, String) -> Unit) {
        var i = 0
        val n = text.length
        while (i < n) {
            if (!isKeyChar(text[i])) { i++; continue }
            val keyStart = i
            while (i < n && isKeyChar(text[i])) i++
            if (i >= n || text[i] != '=') continue
            val key = text.substring(keyStart, i)
            i++ // '='
            if (i < n && text[i] == '"') {
                val close = text.indexOf('"', i + 1)
                if (close < 0) { i++; continue }
                action(key, text.substring(i + 1, close).trim())
                i = close + 1
            } else {
                val valueStart = i
                while (i < n && !isAsciiSpace(text[i]) && text[i] != '"') i++
                if (i > valueStart) action(key, text.substring(valueStart, i).trim())
            }
        }
    }

    /** Same set as the regex `\s` the scanner replaced. */
    private fun isAsciiSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    private fun isKeyChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private fun applyVlcOption(entry: Entry, line: String) {
        val option = line.substringAfter(':', "")
        val key = option.substringBefore('=').trim().lowercase()
        val value = option.substringAfter('=', "").trim()
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> entry.userAgent = value
            "http-referrer" -> entry.referer = value
        }
    }

    private fun fallbackName(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { "Chaîne sans nom" }

    private fun channelId(key: String) = "s$sourceId-" + fnv(key)

    private fun categoryId(group: String) = "g-" + fnv(group.lowercase())

    private fun fnv(text: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toUnsignedString(hash, 36)
    }

    companion object {
        const val DEFAULT_GROUP = "Sans catégorie"
    }
}
