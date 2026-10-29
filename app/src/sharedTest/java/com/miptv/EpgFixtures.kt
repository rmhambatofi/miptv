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
