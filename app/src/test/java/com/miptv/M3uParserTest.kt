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
        val playlist = "﻿\n\n#EXTINF:-1 group-title=\"A\",One\n\n   \nhttp://a.example/1.ts\n# commentaire\n#EXT-X-SOMETHING:1\nhttp://a.example/orphan.ts\n"
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
}
