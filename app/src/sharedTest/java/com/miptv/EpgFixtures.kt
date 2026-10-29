package com.miptv

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** XMLTV guides as generators actually write them: DTD declaration, offsets, several languages, entities, CDATA, missing stops. */
object EpgFixtures {
    /** Times: 29 October 2026 evening, Paris (UTC+1). */
    const val JOURNAL_START = 1_793_300_400_000L // 20:00 +0100
    const val JOURNAL_STOP = 1_793_302_500_000L  // 20:35 +0100
    const val FILM_START = 1_793_302_800_000L    // 20:40 +0100
    const val FILM_STOP = 1_793_310_000_000L     // 22:40 +0100
    const val SANS_DECALAGE_START = 1_793_300_400_000L // 19:00 UTC
    const val SANS_DECALAGE_STOP = 1_793_304_000_000L  // 20:00 UTC
    const val SERIE_STOP = 1_793_304_000_000L    // 21:00 +0100
    const val ABSENTE_START = 1_793_336_400_000L // 30 October 06:00 +0100

    const val XMLTV = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="test" source-info-name="Guide de test">
  <channel id="tf1.fr"><display-name lang="fr">TF1</display-name><display-name>TF1 HD</display-name><icon src="http://img.example/tf1.png"/><icon src="http://img.example/tf1-2.png"/></channel>
  <channel id="f2.fr"><display-name>France 2</display-name></channel>
  <channel id=""><display-name>Sans identifiant</display-name></channel>
  <programme start="20261029200000 +0100" stop="20261029203500 +0100" channel="tf1.fr">
    <title lang="en">News</title>
    <title lang="fr">Journal de 20h</title>
    <desc lang="en">The news.</desc>
    <desc lang="fr">Le journal, la météo &amp; les sports.</desc>
    <category lang="fr">Information</category>
  </programme>
  <programme start="20261029203500 +0100" channel="tf1.fr"><title>Météo</title></programme>
  <programme start="20261029204000 +0100" stop="20261029224000 +0100" channel="tf1.fr">
    <title>Film : Le Voyage</title>
    <desc><![CDATA[Deux amis <traversent> un pays.]]></desc>
    <category>Film</category>
    <rating system="CSA"><value>Tous publics</value></rating>
  </programme>
  <programme start="20261029190000" stop="20261029200000" channel="f2.fr">
    <title>Sans décalage</title>
    <episode-num system="xmltv_ns">2 . 4 / 10 .</episode-num>
  </programme>
  <programme start="202610292000 +01:00" stop="20261029210000 +0100" channel="f2.fr">
    <title>Série</title>
    <episode-num system="onscreen">S03E05</episode-num>
  </programme>
  <programme start="20261030060000 +0100" stop="20261030070000 +0100" channel="inconnue.fr"><title>Chaîne absente</title></programme>
  <programme start="hier soir" stop="20261030070000 +0100" channel="f2.fr"><title>Date invalide</title></programme>
  <programme start="20261030080000 +0100" stop="20261030070000 +0100" channel="f2.fr"><title>Fin avant début</title></programme>
  <programme start="20261030090000 +0100" stop="20261030100000 +0100" channel=""><title>Sans chaîne</title></programme>
  <programme start="20261029230000 +0100" channel="f2.fr"><title>Jamais fermé</title></programme>
</tv>
"""

    fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    /** [channels] channels with [perChannel] consecutive one-hour programmes each, from [startMs]: a provider-sized guide. */
    fun manyProgrammes(channels: Int, perChannel: Int, startMs: Long = JOURNAL_START): String = buildString(channels * perChannel * 160) {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        for (c in 1..channels) append("<channel id=\"c").append(c).append(".test\"><display-name>Chaîne ").append(c).append("</display-name></channel>\n")
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC)
        for (c in 1..channels) for (p in 0 until perChannel) {
            val start = startMs + p * 3_600_000L
            append("<programme start=\"").append(format.format(java.time.Instant.ofEpochMilli(start))).append(" +0000\" stop=\"")
                .append(format.format(java.time.Instant.ofEpochMilli(start + 3_600_000L))).append(" +0000\" channel=\"c").append(c).append(".test\">")
                .append("<title lang=\"fr\">Programme ").append(p).append("</title><desc>Description du programme ").append(p).append(" de la chaîne ").append(c)
                .append(".</desc><category>Divers</category></programme>\n")
        }
        append("</tv>\n")
    }
}

/** Playlist and guide of the repository scenarios: 29 October 2026, Paris (UTC+1); the clock stands at 20:30. */
object GuideFixtures {
    const val NOW = 1_793_302_200_000L           // 20:30
    const val H19_30 = 1_793_298_600_000L
    const val H20_00 = 1_793_300_400_000L
    const val H20_15 = 1_793_301_300_000L
    const val H20_35 = 1_793_302_500_000L
    const val H20_40 = 1_793_302_800_000L
    const val H21_00 = 1_793_304_000_000L
    const val H21_30 = 1_793_305_800_000L
    const val H21_45 = 1_793_306_700_000L
    const val H22_00 = 1_793_307_600_000L
    const val H22_40 = 1_793_310_000_000L
    const val DAY_START = 1_793_228_400_000L     // 29 October 00:00
    const val NEXT_DAY = 1_793_314_800_000L      // 30 October 00:00
    const val H00_10_NEXT = 1_793_315_400_000L   // 30 October 00:10
    const val NOON_NEXT = 1_793_358_000_000L     // 30 October 12:00

    const val PLAYLIST_URL = "http://list.example/fr.m3u"
    const val GUIDE_URL = "http://epg.example/guide.xml"
    const val GUIDE2_URL = "http://epg.example/other.xml"
    const val DOWN_URL = "http://down.example/guide.xml"

    /** TF1 by tvg-id; France 2 by name; Arte by name despite a stale tvg-id; M6 by tvg-id without `<channel>`; a channel without any key. */
    const val PLAYLIST = """#EXTM3U url-tvg="$GUIDE_URL"
#EXTINF:-1 tvg-id="tf1.fr" group-title="Généralistes",TF1
http://stream.example/tf1.ts
#EXTINF:-1 group-title="Généralistes",France 2
http://stream.example/f2.ts
#EXTINF:-1 tvg-id="old.id" group-title="Culture",Arte
http://stream.example/arte.ts
#EXTINF:-1 tvg-id="m6.fr" group-title="Généralistes",M6
http://stream.example/m6.ts
#EXTINF:-1 group-title="Local",Chaîne locale
http://stream.example/locale.ts
"""

    const val PLAYLIST_WITHOUT_GUIDE = """#EXTM3U
#EXTINF:-1 tvg-id="tf1.fr",TF1 bis
http://stream.example/tf1-bis.ts
"""

    /** Ten programmes fall in the window (one of them for a channel absent from the playlist, dropped after matching); one is too old, one too far. */
    const val GUIDE = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="tf1.fr"><display-name>TF1</display-name></channel>
  <channel id="f2.fr"><display-name lang="fr">France 2</display-name><display-name>FR2</display-name></channel>
  <channel id="arte.fr"><display-name>ARTE</display-name></channel>
  <programme start="20261029193000 +0100" stop="20261029203500 +0100" channel="tf1.fr"><title>Journal de 20h</title><desc>Le journal.</desc><category>Information</category></programme>
  <programme start="20261029203500 +0100" stop="20261029204000 +0100" channel="tf1.fr"><title>Météo</title></programme>
  <programme start="20261029204000 +0100" stop="20261029224000 +0100" channel="tf1.fr"><title>Film : Le Voyage</title></programme>
  <programme start="20261029224000 +0100" stop="20261030001000 +0100" channel="tf1.fr"><title>Nuit blanche</title></programme>
  <programme start="20261029200000 +0100" stop="20261029210000 +0100" channel="f2.fr"><title>Série</title><episode-num system="xmltv_ns">2.4.</episode-num></programme>
  <programme start="20261029210000 +0100" stop="20261029220000 +0100" channel="f2.fr"><title>Documentaire : voyage en Islande</title></programme>
  <programme start="20261029201500 +0100" stop="20261029214500 +0100" channel="arte.fr"><title>Voyage au bout du monde</title></programme>
  <programme start="20261029200000 +0100" stop="20261029203000 +0100" channel="m6.fr"><title>Avant</title></programme>
  <programme start="20261029203000 +0100" stop="20261029213000 +0100" channel="m6.fr"><title>Capital</title></programme>
  <programme start="20261029060000 +0100" stop="20261029070000 +0100" channel="tf1.fr"><title>Trop vieux</title></programme>
  <programme start="20261102200000 +0100" stop="20261102210000 +0100" channel="tf1.fr"><title>Trop loin</title></programme>
  <programme start="20261029200000 +0100" stop="20261029210000 +0100" channel="unknown.fr"><title>Orphelin</title></programme>
</tv>
"""

    const val GUIDE_V2 = """<tv><programme start="20261029200000 +0100" stop="20261029210000 +0100" channel="tf1.fr"><title>Nouveau</title></programme></tv>"""
}

/** Playlists and guides served by location; a missing location fails like an unreachable host. Counts the opens. */
class GuideLoader : PlaylistLoader {
    private val files = HashMap<String, ByteArray>()
    val opened = HashMap<String, Int>()
    operator fun set(location: String, content: String) { files[location] = content.toByteArray(Charsets.UTF_8) }
    operator fun set(location: String, content: ByteArray) { files[location] = content }
    override suspend fun open(location: String, kind: SourceKind): java.io.InputStream {
        opened[location] = (opened[location] ?: 0) + 1
        return files[location]?.inputStream() ?: throw java.net.UnknownHostException("unreachable")
    }
}

class TestClock(var now: Long = GuideFixtures.NOW) : () -> Long {
    override fun invoke(): Long = now
}
