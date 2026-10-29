package com.miptv

import com.miptv.JsonStreamReader.Token
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStreamReaderTest {
    private fun reader(text: String) = JsonStreamReader(StringReader(text))

    private fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        reader(text).use { json ->
            while (true) {
                when (val token = json.peek()) {
                    Token.BEGIN_ARRAY -> { json.beginArray(); out += "[" }
                    Token.END_ARRAY -> { json.endArray(); out += "]" }
                    Token.BEGIN_OBJECT -> { json.beginObject(); out += "{" }
                    Token.END_OBJECT -> { json.endObject(); out += "}" }
                    Token.NAME -> out += json.nextName() + ":"
                    Token.STRING -> out += "\"" + json.nextString() + "\""
                    Token.NUMBER -> out += json.nextString()
                    Token.BOOLEAN -> out += json.nextBoolean().toString()
                    Token.NULL -> { json.nextNull(); out += "null" }
                    Token.END_DOCUMENT -> return out
                }
            }
        }
    }

    @Test fun walksNestedStructuresTokenByToken() {
        val text = """ {"a": [1, -2.5e3, "x", true, false, null, {}], "b": {"c": []}, "d": ""} """
        assertEquals(listOf("{", "a:", "[", "1", "-2.5e3", "\"x\"", "true", "false", "null", "{", "}", "]", "b:", "{", "c:", "[", "]", "}", "d:", "\"\"", "}"), tokens(text))
    }

    @Test fun decodesEscapesIncludingUnicodeAndSurrogatePairs() {
        val text = """["café \"quot\" back\\slash \/ tab\t nl\n 🎬 fin"]"""
        assertEquals(listOf("[", "\"café \"quot\" back\\slash / tab\t nl\n 🎬 fin\"", "]"), tokens(text))
    }

    @Test fun acceptsLeadingBomAndWhitespaceAndScalarDocuments() {
        assertEquals(listOf("[", "]"), tokens("﻿  \r\n[ ]\n"))
        assertEquals(listOf("42"), tokens("42"))
        assertEquals(listOf("\"seul\""), tokens("\"seul\""))
        assertEquals(listOf("null"), tokens("null"))
    }

    @Test fun stringsLongerThanTheBufferSurviveRefills() {
        val long = "é".repeat(20_000) + "\\n" + "x".repeat(10_000)
        val expected = "é".repeat(20_000) + "\n" + "x".repeat(10_000)
        reader("[\"$long\"]").use { json ->
            json.beginArray()
            assertEquals(expected, json.nextString())
            json.endArray()
            assertEquals(Token.END_DOCUMENT, json.peek())
        }
    }

    @Test fun skipValueSkipsScalarsNestedValuesAndNamesWithTheirValues() {
        reader("""{"skip":{"deep":[1,{"x":[true]}]},"keep":"yes","n":[1,2,3],"last":null}""").use { json ->
            json.beginObject()
            assertEquals("skip", json.nextName()); json.skipValue()
            assertEquals("keep", json.nextName()); assertEquals("yes", json.nextString())
            json.skipValue() // "n" and its array
            assertEquals("last", json.nextName()); json.nextNull()
            json.endObject()
            assertEquals(Token.END_DOCUMENT, json.peek())
        }
    }

    @Test fun rejectsNonJsonAndTruncatedDocuments() {
        assertThrows(JsonSyntaxException::class.java) { tokens(XtreamFixtures.HTML_LOGIN_PAGE) }
        assertThrows(JsonSyntaxException::class.java) { tokens("") }
        assertThrows(JsonSyntaxException::class.java) { tokens("""{"a":1""") }
        assertThrows(JsonSyntaxException::class.java) { tokens("""{"a" 1}""") }
        assertThrows(JsonSyntaxException::class.java) { tokens("""[1 2]""") }
        assertThrows(JsonSyntaxException::class.java) { tokens("""{"a":tru}""") }
        assertThrows(JsonSyntaxException::class.java) { tokens("""["\q"]""") }
        assertThrows(JsonSyntaxException::class.java) { tokens("""[1] [2]""") }
        assertThrows(JsonSyntaxException::class.java) { tokens("""{a:1}""") }
    }

    @Test fun wrongTokenRequestsFail() {
        reader("""{"a":1}""").use { json ->
            assertThrows(JsonSyntaxException::class.java) { json.beginArray() }
            json.beginObject()
            assertThrows(JsonSyntaxException::class.java) { json.nextString() }
            assertEquals("a", json.nextName())
            assertThrows(JsonSyntaxException::class.java) { json.nextBoolean() }
            assertEquals("1", json.nextString())
        }
    }

    @Test fun lenientAccessorsCoerceWhatPanelsSend() {
        reader("""["7", 7, "7.5", "7,5", true, "", null, [1], {"k":1}, "1", 0, "abc"]""").use { json ->
            json.beginArray()
            assertEquals(7L, json.nextLongOrNull())
            assertEquals(7, json.nextIntOrNull())
            assertEquals(7.5, json.nextDoubleOrNull()!!, 0.0)
            assertEquals(7.5, json.nextDoubleOrNull()!!, 0.0)
            assertEquals(true, json.nextBooleanOrNull())
            assertEquals("", json.nextStringOrNull())
            assertNull(json.nextStringOrNull())
            assertNull(json.nextLongOrNull()) // nested array skipped
            assertNull(json.nextStringOrNull()) // nested object skipped
            assertEquals(true, json.nextBooleanOrNull())
            assertEquals(false, json.nextBooleanOrNull())
            assertNull(json.nextBooleanOrNull())
            assertFalse(json.hasNext())
            json.endArray()
        }
    }

    @Test fun forEachElementAcceptsArraysObjectsAndScalars() {
        var count = 0
        reader("""[{"a":1},{"a":2}]""").use { json -> json.forEachElement { json.forEachField { json.skipValue() }; count++ } }
        assertEquals(2, count)
        count = 0
        reader("""{"1":{"a":1},"2":{"a":2},"3":{"a":3}}""").use { json -> json.forEachElement { json.forEachField { json.skipValue() }; count++ } }
        assertEquals(3, count)
        count = 0
        reader("{}").use { json -> json.forEachElement { count++ }; assertEquals(Token.END_DOCUMENT, json.peek()) }
        assertEquals(0, count)
        reader("\"rien\"").use { json -> json.forEachElement { count++ }; assertEquals(Token.END_DOCUMENT, json.peek()) }
        assertEquals(0, count)
        assertEquals(listOf("m3u8", "1", "true"), reader("""["m3u8", 1, true, null, {"x":1}]""").use { it.nextStringList() })
        assertTrue(reader("{}").use { it.nextStringList() }.isEmpty())
    }
}
