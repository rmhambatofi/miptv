package com.miptv

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    private val parser = M3uParser(sourceId = 7)

    @Test fun parsesExtinfAttributesGroupsAndLogos() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="fr.tf1" tvg-name="TF1 HD" tvg-logo="https://logos.example/tf1.png" group-title="Généralistes",TF1
            https://stream.example/tf1/index.m3u8
            #EXTINF:-1 tvg-id="fr.f2" tvg-logo="https://logos.example/f2.png" group-title="Généralistes",France 2
            https://stream.example/f2/index.m3u8
            #EXTINF:-1 group-title="Sport",Sport 1
            http://stream.example/sport1.ts
        """.trimIndent()
        val result = parser.parse(playlist)
        assertEquals(listOf("Généralistes", "Sport"), result.categories.map { it.name })
        assertEquals(3, result.channels.size)
        val tf1 = result.channels[0]
        assertEquals("TF1", tf1.name)
        assertEquals("fr.tf1", tf1.tvgId)
        assertEquals("https://logos.example/tf1.png", tf1.logoUrl)
        assertEquals("https://stream.example/tf1/index.m3u8", tf1.streamUrl)
        assertEquals(result.categories[0].id, tf1.categoryId)
        assertEquals(7L, tf1.sourceId)
        assertEquals(result.categories[1].id, result.channels[2].categoryId)
        assertNull(result.channels[2].logoUrl)
    }

    @Test fun headerGuideAddressIsKeptWhenPresent() {
        val withGuide = """
            #EXTM3U url-tvg="http://epg.example/guide.xml.gz" x-tvg-url="http://other.example/guide.xml"
            #EXTINF:-1 tvg-id="fr.tf1",TF1
            http://stream.example/tf1.ts
        """.trimIndent()
        assertEquals("http://epg.example/guide.xml.gz", parser.parse(withGuide).epgUrl)
        val several = "#EXTM3U x-tvg-url=\"ftp://no.example/x, https://a.example/1.xml ,https://b.example/2.xml\"\n#EXTINF:-1,A\nhttp://a/1.ts\n"
        assertEquals("https://a.example/1.xml", parser.parse(several).epgUrl)
        assertNull(parser.parse("#EXTM3U\n#EXTINF:-1,A\nhttp://a/1.ts\n").epgUrl)
        assertNull(parser.parse("#EXTINF:-1,A\nhttp://a/1.ts\n").epgUrl)
        assertNull(parser.parse("#EXTM3U url-tvg=\"\"\n#EXTINF:-1,A\nhttp://a/1.ts\n").epgUrl)
        // Only the first header counts, and it never becomes a channel.
        val twoHeaders = "#EXTM3U tvg-url=\"http://first.example/g.xml\"\n#EXTM3U url-tvg=\"http://second.example/g.xml\"\n#EXTINF:-1,A\nhttp://a/1.ts\n"
        val parsed = parser.parse(twoHeaders)
        assertEquals("http://first.example/g.xml", parsed.epgUrl)
        assertEquals(1, parsed.channels.size)
    }

    @Test fun missingAttributesFallBackToTitleTvgNameOrUrl() {
        val playlist = """
            #EXTINF:-1,Sans attributs
            http://a.example/1.ts
            #EXTINF:-1 tvg-name="Nom tvg"
            http://a.example/2.ts
            #EXTINF:-1,
            http://a.example/video-3.ts?token=abc
        """.trimIndent()
        val result = parser.parse(playlist)
        assertEquals(listOf("Sans attributs", "Nom tvg", "video-3.ts"), result.channels.map { it.name })
        assertEquals(listOf(M3uParser.DEFAULT_GROUP), result.categories.map { it.name })
        assertTrue(result.channels.all { it.categoryId == result.categories[0].id })
    }

    @Test fun ignoresBlankLinesCommentsBomAndMissingHeader() {
        val playlist = "\uFEFF\n\n#EXTINF:-1 group-title=\"A\",One\n\n   \nhttp://a.example/1.ts\n# commentaire\n#EXT-X-SOMETHING:1\nhttp://a.example/orphan.ts\n"
        val result = parser.parse(playlist)
        assertEquals(listOf("One", "orphan.ts"), result.channels.map { it.name })
    }

    @Test fun extVlcOptHintsAreAttachedToTheNextChannelOnly() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="X",With UA
            #EXTVLCOPT:http-user-agent=VLC/3.0.18
            #EXTVLCOPT:http-referrer=https://ref.example/
            #EXTVLCOPT:network-caching=1000
            http://a.example/ua.ts
            #EXTINF:-1 group-title="X",Without UA
            http://a.example/plain.ts
        """.trimIndent()
        val result = parser.parse(playlist)
        assertEquals("VLC/3.0.18", result.channels[0].userAgent)
        assertEquals("https://ref.example/", result.channels[0].referer)
        assertNull(result.channels[1].userAgent)
        assertNull(result.channels[1].referer)
    }

    @Test fun windowsLineEndingsAndUnquotedAttributes() {
        val playlist = "#EXTM3U\r\n#EXTINF:0 tvg-id=id1 group-title=\"Groupe, avec virgule\",Titre, avec virgule\r\nhttp://a.example/1.ts\r\n"
        val result = parser.parse(playlist)
        assertEquals(1, result.channels.size)
        assertEquals("Titre, avec virgule", result.channels[0].name)
        assertEquals("Groupe, avec virgule", result.categories[0].name)
        assertEquals("id1", result.channels[0].tvgId)
        assertEquals("http://a.example/1.ts", result.channels[0].streamUrl)
    }

    @Test fun identifiersAreStableAcrossRefreshAndUniqueWithinPlaylist() {
        val first = parser.parse("#EXTINF:-1 tvg-id=\"x\" group-title=\"G\",A\nhttp://a/1\n#EXTINF:-1 group-title=\"G\",B\nhttp://a/2\n#EXTINF:-1 group-title=\"G\",B\nhttp://a/3\n")
        val second = parser.parse("#EXTINF:-1 tvg-id=\"x\" group-title=\"G\",A renommée\nhttp://b/1?t=2\n#EXTINF:-1 group-title=\"G\",B\nhttp://b/2\n")
        assertEquals(first.channels[0].id, second.channels[0].id)
        assertEquals(first.channels[1].id, second.channels[1].id)
        assertEquals(3, first.channels.map { it.id }.toSet().size)
        assertNotEquals(first.channels[1].id, first.channels[2].id)
        assertEquals(first.categories[0].id, second.categories[0].id)
    }

    @Test fun groupsAreMergedCaseInsensitivelyAndKeepFirstAppearanceOrder() {
        val result = parser.parse("#EXTINF:-1 group-title=\"Sport\",a\nhttp://a/1\n#EXTINF:-1 group-title=\"News\",b\nhttp://a/2\n#EXTINF:-1 group-title=\"SPORT\",c\nhttp://a/3\n")
        assertEquals(listOf("Sport", "News"), result.categories.map { it.name })
        assertEquals(result.channels[0].categoryId, result.channels[2].categoryId)
    }

    @Test fun streamsLargePlaylistFromReader() {
        val builder = StringBuilder("#EXTM3U\n")
        repeat(25_000) { i -> builder.append("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"G${i % 50}\",Chaîne $i\r\nhttp://a.example/$i.ts\r\n") }
        val result = parser.parse(builder.toString().reader())
        assertEquals(25_000, result.channels.size)
        assertEquals(50, result.categories.size)
        assertEquals("Chaîne 24999", result.channels.last().name)
    }

    /** The hand-written attribute scanner must read exactly what the former regex read. */
    @Test fun attributeScannerMatchesTheFormerRegex() {
        val regex = Regex("""([A-Za-z0-9_-]+)=(?:"([^"]*)"|([^\s"]+))""")
        fun expected(attributes: String): Map<String, String> {
            val found = mutableMapOf<String, String>()
            for (match in regex.findAll(attributes)) {
                val value = (match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()).trim()
                val key = match.groupValues[1].lowercase()
                if (key in setOf("tvg-id", "tvg-name", "tvg-logo", "group-title")) found[key] = value
            }
            return found
        }
        val cases = listOf(
            """-1 tvg-id="a" tvg-name="A B" tvg-logo="http://l/a.png" group-title="G 1"""",
            """-1 TVG-ID=a GROUP-TITLE=Sport tvg-logo=http://l/x.png?w=1&h=2""",
            """-1 tvg-id="" group-title=  tvg-name="x"""",
            """-1 tvg-logo="http://l/a.png tvg-id="b" group-title="c"""",
            """-1 tvg-id="un" tvg-id="deux" group-title=" espaces """",
            """0 x-tvg-id="no" tvg-id=v"quoted" group-title="é à ü"""",
            """-1 tvg-id=a\tb group-title="t\tab"""",
            """-1 tvg-id="a"group-title="b"tvg-name="c"""",
            """-1 =x tvg-id== group-title="=" tvg-name=a=b""",
            "-1 tvg-id=\u00a0nbsp\u00a0 group-title=\"x\""
        )
        for (attributes in cases) {
            val channel = parser.parse("#EXTINF:$attributes,Nom\nhttp://s/1\n").channels.single()
            val want = expected(attributes)
            assertEquals(attributes, want["tvg-id"]?.ifEmpty { null }, channel.tvgId)
            assertEquals(attributes, want["tvg-logo"]?.ifEmpty { null }, channel.logoUrl)
            assertEquals(attributes, want["group-title"]?.ifEmpty { null } ?: M3uParser.DEFAULT_GROUP, parser.parse("#EXTINF:$attributes,Nom\nhttp://s/1\n").categories.single().name)
        }
    }
}
