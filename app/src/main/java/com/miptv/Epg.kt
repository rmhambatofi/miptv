package com.miptv

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Guide-side failure with a message safe to show: it never contains the guide address. */
class EpgException(message: String) : IOException(message)

/** Receives programmes as they are parsed, in batches, so a guide of millions of lines is never held whole. */
fun interface ProgrammeSink { fun accept(batch: List<Programme>) }

/**
 * Streaming XMLTV parser on SAX (`javax.xml.parsers`, real on the device and on the test JVM, unlike the framework's
 * `XmlPullParser` which is a stub in local tests). Reads a plain or gzip file, keeps the programmes that overlap
 * `[fromMs, toMs[` and whose channel passes [channelFilter], and hands them to the sink by batches of [batchSize].
 * Titles, descriptions and categories prefer [preferredLanguage]; a programme without `stop` ends when the next
 * programme of its channel starts, and is dropped when none follows. External entities and the DTD are never fetched.
 */
class XmltvParser(
    private val sourceId: Long,
    private val fromMs: Long = Long.MIN_VALUE,
    private val toMs: Long = Long.MAX_VALUE,
    private val channelFilter: ((String) -> Boolean)? = null,
    private val batchSize: Int = 500,
    private val preferredLanguage: String = "fr"
) {
    class Result(val channels: List<EpgChannel>, val programmeCount: Int)

    fun parse(input: InputStream, sink: ProgrammeSink): Result {
        val stream = unzipIfNeeded(input)
        val handler = Handler(sink)
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.newSAXParser().parse(InputSource(stream), handler)
        } catch (error: NotXmltv) {
            throw EpgException(UNEXPECTED)
        } catch (error: SAXException) {
            if (error.exception is NotXmltv) throw EpgException(UNEXPECTED)
            throw EpgException(if (handler.rootSeen) TRUNCATED else UNEXPECTED)
        }
        handler.finish()
        return Result(handler.channels, handler.count)
    }

    private fun setFeatureQuietly(factory: SAXParserFactory, feature: String, value: Boolean) {
        try { factory.setFeature(feature, value) } catch (_: Exception) { /* parser without this feature */ }
    }

    /** Thrown from the handler when the root element is not `<tv>`. */
    private class NotXmltv : SAXException(UNEXPECTED)

    private class Pending(val channel: String, val startMs: Long, var stopMs: Long?) {
        var title: String? = null
        var titleLang: String? = null
        var description: String? = null
        var descriptionLang: String? = null
        var category: String? = null
        var categoryLang: String? = null
        var episode: String? = null
    }

    private inner class Handler(private val sink: ProgrammeSink) : DefaultHandler() {
        val channels = ArrayList<EpgChannel>()
        var count = 0
        var rootSeen = false
        private var depth = 0
        private val batch = ArrayList<Programme>(batchSize)
        /** Programmes without `stop`, by channel, closed by the next programme of the channel. */
        private val open = HashMap<String, Pending>()
        private var channelId: String? = null
        private var channelNames = ArrayList<String>(2)
        private var channelIcon: String? = null
        private var programme: Pending? = null
        private var skipping = false
        private var text: StringBuilder? = null
        private var lang: String? = null
        private var system: String? = null

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (depth == 0) {
                if (qName != "tv") throw NotXmltv()
                rootSeen = true
            }
            depth++
            when (depth) {
                2 -> when (qName) {
                    "channel" -> { channelId = attributes.getValue("id")?.trim(); channelNames = ArrayList(2); channelIcon = null }
                    "programme" -> startProgramme(attributes)
                }
                3 -> when {
                    channelId != null && qName == "display-name" -> text = StringBuilder()
                    channelId != null && qName == "icon" -> if (channelIcon == null) channelIcon = attributes.getValue("src")?.trim()?.ifEmpty { null }
                    programme != null && (qName == "title" || qName == "desc" || qName == "category") -> { text = StringBuilder(); lang = attributes.getValue("lang")?.trim()?.lowercase() }
                    programme != null && qName == "episode-num" -> { text = StringBuilder(); system = attributes.getValue("system")?.trim()?.lowercase() }
                }
            }
        }

        private fun startProgramme(attributes: Attributes) {
            val channel = attributes.getValue("channel")?.trim().orEmpty()
            val start = parseXmltvTime(attributes.getValue("start"))
            val stop = attributes.getValue("stop")?.let { parseXmltvTime(it) }
            if (channel.isEmpty() || start == null) { skipping = true; return }
            open.remove(channel)?.let { previous ->
                if (start > previous.startMs) { previous.stopMs = start; emit(previous) }
            }
            programme = Pending(channel, start, stop)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text?.append(ch, start, length) }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val content = text?.toString()?.trim()
            text = null
            when (depth) {
                2 -> when (qName) {
                    "channel" -> {
                        val id = channelId
                        if (!id.isNullOrEmpty() && channelFilter?.invoke(id) != false) channels += EpgChannel(id, channelNames.toList(), channelIcon)
                        channelId = null
                    }
                    "programme" -> {
                        val current = programme
                        programme = null
                        skipping = false
                        if (current != null) {
                            if (current.stopMs == null) open[current.channel] = current else emit(current)
                        }
                    }
                }
                3 -> {
                    val current = programme
                    if (channelId != null && qName == "display-name") { if (!content.isNullOrEmpty()) channelNames += content }
                    else if (current != null && content != null) when (qName) {
                        "title" -> if (current.title == null || preferred(lang, current.titleLang)) { current.title = content; current.titleLang = lang }
                        "desc" -> if (current.description == null || preferred(lang, current.descriptionLang)) { current.description = content; current.descriptionLang = lang }
                        "category" -> if (current.category == null || preferred(lang, current.categoryLang)) { current.category = content; current.categoryLang = lang }
                        "episode-num" -> when (system) {
                            "xmltv_ns" -> formatXmltvNs(content)?.let { current.episode = it }
                            else -> if (current.episode == null && content.isNotEmpty()) current.episode = content
                        }
                    }
                    lang = null
                    system = null
                }
            }
            depth--
        }

        /** A new value wins only when it is in the preferred language and the kept one is not. */
        private fun preferred(newLang: String?, keptLang: String?) = newLang == preferredLanguage && keptLang != preferredLanguage

        private fun emit(pending: Pending) {
            val stop = pending.stopMs ?: return
            if (stop <= pending.startMs) return
            if (stop <= fromMs || pending.startMs >= toMs) return
            if (channelFilter?.invoke(pending.channel) == false) return
            batch += Programme(
                channelKey = pending.channel,
                startMs = pending.startMs,
                stopMs = stop,
                title = pending.title?.ifEmpty { null } ?: UNTITLED,
                description = pending.description?.ifEmpty { null },
                category = pending.category?.ifEmpty { null },
                episode = pending.episode,
                sourceId = sourceId
            )
            count++
            if (batch.size >= batchSize) flush()
        }

        private fun flush() {
            if (batch.isEmpty()) return
            sink.accept(batch.toList())
            batch.clear()
        }

        fun finish() {
            open.clear() // never closed: no stop and nothing after them
            flush()
        }
    }

    companion object {
        const val UNEXPECTED = "Ce fichier n'est pas un guide XMLTV."
        const val TRUNCATED = "Guide XMLTV incomplet ou illisible."
        const val UNTITLED = "Sans titre"

        /** gzip starts with `1f 8b`; anything else is read as is. */
        fun unzipIfNeeded(input: InputStream): InputStream {
            val buffered = input as? BufferedInputStream ?: BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        }

        /**
         * `yyyyMMddHHmmss ±hhmm`, as XMLTV writes times; trailing fields may be missing (`yyyyMMddHHmm`), the offset
         * may be absent (then UTC), `Z` or `±hh:mm`. Epoch milliseconds, or null when unreadable.
         */
        fun parseXmltvTime(text: String?): Long? {
            val value = text?.trim() ?: return null
            var i = 0
            while (i < value.length && i < 14 && value[i] in '0'..'9') i++
            if (i < 8) return null
            val digits = value.substring(0, i).padEnd(14, '0')
            val offset = parseOffset(value.substring(i).trim())
            return try {
                LocalDateTime.of(
                    digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt(),
                    digits.substring(8, 10).toInt(), digits.substring(10, 12).toInt(), digits.substring(12, 14).toInt()
                ).toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000
            } catch (_: DateTimeException) {
                null
            }
        }

        /** Seconds east of UTC for `+0100`, `-05:30`, `Z` or nothing (UTC); an unreadable offset counts as UTC. */
        private fun parseOffset(text: String): Int {
            if (text.isEmpty() || text.equals("Z", ignoreCase = true)) return 0
            val sign = when (text[0]) { '+' -> 1; '-' -> -1; else -> return 0 }
            val body = text.substring(1).replace(":", "")
            if (body.length != 4 || !body.all { it in '0'..'9' }) return 0
            val hours = body.substring(0, 2).toInt()
            val minutes = body.substring(2, 4).toInt()
            if (hours > 18 || minutes > 59) return 0
            return sign * (hours * 3600 + minutes * 60)
        }

        /** `xmltv_ns` numbering is zero-based `season.episode.part`, each part possibly `n/total`: `0.1.` is « S1 E2 ». */
        fun formatXmltvNs(text: String): String? {
            val parts = text.split('.')
            fun field(index: Int): Int? = parts.getOrNull(index)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            val season = field(0)
            val episode = field(1)
            return when {
                season != null && episode != null -> "S$season E$episode"
                episode != null -> "E$episode"
                season != null -> "S$season"
                else -> null
            }
        }

        /** `2026-10-29 20:00:00` in [zone], as `get_short_epg` writes `start`/`end`; null when unreadable. */
        fun parseLocalDateTime(text: String?, zone: ZoneId): Long? {
            val value = text?.trim()?.replace(' ', 'T') ?: return null
            return try { LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeException) { null }
        }

        /**
         * Xtream panels send EPG titles and descriptions base64-encoded; some send them in clear. The text is decoded
         * when it is valid base64 yielding valid, printable UTF-8, and kept as is otherwise.
         */
        fun decodeBase64Text(value: String?): String {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return ""
            val bytes = try { java.util.Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) { return text }
            val decoded = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { return text }
            if (decoded.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) return text
            return decoded.trim()
        }
    }
}

/** Display names of a guide's channels, as [searchKey], to the guide channel id; the first channel wins a shared name. */
fun guideNameIndex(channels: List<EpgChannel>): Map<String, String> {
    val out = HashMap<String, String>(channels.size * 2)
    for (channel in channels) for (name in channel.names) out.putIfAbsent(searchKey(name), channel.id)
    return out
}

/**
 * The guide key of a playlist channel: its `tvg-id` when the guide declares that channel, else the guide channel
 * sharing its display name, else the `tvg-id` as typed (a guide may omit its `<channel>` elements altogether).
 */
fun guideKey(tvgId: String?, name: String, guideIds: Set<String>, byName: Map<String, String>): String? {
    val id = tvgId?.trim()?.ifEmpty { null }
    if (id != null && id in guideIds) return id
    byName[searchKey(name)]?.let { return it }
    return id
}

/**
 * One timeline per guide channel: some providers list a schedule twice, shifted by an hour, so several programmes are
 * on air at once. In start order, a programme overlapping the one kept before it is dropped when it bears the same
 * title, starts with it or overlaps it for at least half its own length (a duplicate); a shorter overlap clips the
 * previous one, so guides whose programmes run a few minutes over keep them all. The earlier copy wins. The result
 * holds each channel's programmes in start order.
 */
fun resolveOverlaps(programmes: List<Programme>): List<Programme> {
    val out = ArrayList<Programme>(programmes.size)
    for ((_, list) in programmes.groupBy { it.channelKey }) {
        val kept = ArrayList<Programme>(list.size)
        for (programme in list.sortedBy { it.startMs }) {
            val previous = kept.lastOrNull()
            if (previous == null || programme.startMs >= previous.stopMs) { kept += programme; continue }
            val overlap = minOf(previous.stopMs, programme.stopMs) - programme.startMs
            val duplicate = programme.startMs == previous.startMs || searchKey(programme.title) == searchKey(previous.title)
            if (duplicate || overlap * 2 >= programme.durationMs) continue
            kept[kept.lastIndex] = previous.copy(stopMs = programme.startMs)
            kept += programme
        }
        out += kept
    }
    return out
}

/** French message for a failed guide download or parse. Never echoes the guide address. */
fun describeEpgError(error: Throwable): String = when (error) {
    is EpgException -> error.message ?: XmltvParser.UNEXPECTED
    is HttpStatusException -> "Le serveur du guide a répondu HTTP ${error.code}."
    is PlaylistException, is UnknownHostException, is SocketTimeoutException, is SSLException,
    is IllegalArgumentException, is MalformedURLException -> describeImportError(error)
    is IOException -> "Lecture du guide impossible."
    else -> "Importation du guide impossible."
}
