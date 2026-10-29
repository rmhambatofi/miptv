package com.miptv

import java.io.Closeable
import java.io.IOException
import java.io.Reader

/** Malformed JSON, or a document that is not JSON at all (an HTML login page, for instance). */
class JsonSyntaxException(message: String) : IOException(message)

/**
 * Pull parser over a [Reader]: the subset of `android.util.JsonReader` the Xtream parsers need, rewritten because
 * the framework class is a stub in local unit tests. Strict JSON (RFC 8259) plus a leading BOM. Numbers come back
 * as text so callers decide how to coerce them: Xtream panels send `"7.5"`, `7.5` or `""` for the same field.
 * Nothing but the current token is held in memory, so a response of tens of thousands of entries streams through.
 */
class JsonStreamReader(private val reader: Reader) : Closeable {
    enum class Token { BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

    private enum class Scope { EMPTY_DOCUMENT, NONEMPTY_DOCUMENT, EMPTY_ARRAY, NONEMPTY_ARRAY, EMPTY_OBJECT, DANGLING_NAME, NONEMPTY_OBJECT }

    private val buffer = CharArray(8192)
    private var pos = 0
    private var limit = 0
    private val stack = ArrayList<Scope>().apply { add(Scope.EMPTY_DOCUMENT) }
    private var peeked: Token? = null
    private var peekedText = ""
    private val text = StringBuilder()

    fun peek(): Token = peeked ?: doPeek().also { peeked = it }

    /** False at the end of the enclosing array or object (or of the document). */
    fun hasNext(): Boolean {
        val token = peek()
        return token != Token.END_ARRAY && token != Token.END_OBJECT && token != Token.END_DOCUMENT
    }

    fun beginArray() { consume(Token.BEGIN_ARRAY); stack.add(Scope.EMPTY_ARRAY) }
    fun endArray() { consume(Token.END_ARRAY); stack.removeAt(stack.size - 1) }
    fun beginObject() { consume(Token.BEGIN_OBJECT); stack.add(Scope.EMPTY_OBJECT) }
    fun endObject() { consume(Token.END_OBJECT); stack.removeAt(stack.size - 1) }

    fun nextName(): String { consume(Token.NAME); return peekedText }

    /** A string, or a number as written. */
    fun nextString(): String {
        val token = peek()
        if (token != Token.STRING && token != Token.NUMBER) throw unexpected(token, "une chaîne")
        peeked = null
        return peekedText
    }

    fun nextBoolean(): Boolean { consume(Token.BOOLEAN); return peekedText == "true" }
    fun nextNull() { consume(Token.NULL) }

    /** Skips one value (or one name and its value), however deeply nested. */
    fun skipValue() {
        if (peek() == Token.NAME) nextName()
        var depth = 0
        do {
            when (peek()) {
                Token.BEGIN_ARRAY -> { beginArray(); depth++ }
                Token.BEGIN_OBJECT -> { beginObject(); depth++ }
                Token.END_ARRAY -> { endArray(); depth-- }
                Token.END_OBJECT -> { endObject(); depth-- }
                Token.NAME -> nextName()
                Token.STRING, Token.NUMBER, Token.BOOLEAN, Token.NULL -> peeked = null
                Token.END_DOCUMENT -> throw JsonSyntaxException("Fin du document inattendue.")
            }
        } while (depth > 0)
    }

    override fun close() = reader.close()

    private fun consume(expected: Token) {
        val token = peek()
        if (token != expected) throw unexpected(token, expected.name)
        peeked = null
    }

    private fun unexpected(found: Token, expected: String) = JsonSyntaxException("$expected attendu, $found trouvé.")

    private fun doPeek(): Token {
        val top = stack.size - 1
        when (stack[top]) {
            Scope.EMPTY_DOCUMENT -> {
                stack[top] = Scope.NONEMPTY_DOCUMENT
                var c = nextNonWhitespace()
                if (c == 0xFEFF) c = nextNonWhitespace()
                if (c < 0) throw JsonSyntaxException("Document vide.")
                return readValue(c)
            }
            Scope.NONEMPTY_DOCUMENT -> {
                if (nextNonWhitespace() < 0) return Token.END_DOCUMENT
                throw JsonSyntaxException("Contenu après la fin du document.")
            }
            Scope.EMPTY_ARRAY -> {
                stack[top] = Scope.NONEMPTY_ARRAY
                val c = nextNonWhitespaceOrThrow()
                return if (c == ']'.code) Token.END_ARRAY else readValue(c)
            }
            Scope.NONEMPTY_ARRAY -> return when (nextNonWhitespaceOrThrow()) {
                ']'.code -> Token.END_ARRAY
                ','.code -> readValue(nextNonWhitespaceOrThrow())
                else -> throw JsonSyntaxException("Virgule ou crochet fermant attendu.")
            }
            Scope.EMPTY_OBJECT -> {
                val c = nextNonWhitespaceOrThrow()
                if (c == '}'.code) return Token.END_OBJECT
                if (c != '"'.code) throw JsonSyntaxException("Nom de champ attendu.")
                stack[top] = Scope.DANGLING_NAME
                peekedText = readString()
                return Token.NAME
            }
            Scope.NONEMPTY_OBJECT -> {
                when (nextNonWhitespaceOrThrow()) {
                    '}'.code -> return Token.END_OBJECT
                    ','.code -> Unit
                    else -> throw JsonSyntaxException("Virgule ou accolade fermante attendue.")
                }
                if (nextNonWhitespaceOrThrow() != '"'.code) throw JsonSyntaxException("Nom de champ attendu.")
                stack[top] = Scope.DANGLING_NAME
                peekedText = readString()
                return Token.NAME
            }
            Scope.DANGLING_NAME -> {
                stack[top] = Scope.NONEMPTY_OBJECT
                if (nextNonWhitespaceOrThrow() != ':'.code) throw JsonSyntaxException("Deux-points attendus.")
                return readValue(nextNonWhitespaceOrThrow())
            }
        }
    }

    private fun readValue(c: Int): Token = when (c) {
        '['.code -> Token.BEGIN_ARRAY
        '{'.code -> Token.BEGIN_OBJECT
        '"'.code -> { peekedText = readString(); Token.STRING }
        't'.code, 'f'.code, 'n'.code -> readLiteral(c)
        '-'.code, in '0'.code..'9'.code -> { peekedText = readNumber(c); Token.NUMBER }
        else -> throw JsonSyntaxException("Caractère inattendu.")
    }

    private fun readLiteral(first: Int): Token {
        text.setLength(0)
        text.append(first.toChar())
        while (true) {
            val c = nextChar()
            if (c in 'a'.code..'z'.code) text.append(c.toChar()) else { if (c >= 0) pos--; break }
        }
        return when (text.toString()) {
            "true" -> { peekedText = "true"; Token.BOOLEAN }
            "false" -> { peekedText = "false"; Token.BOOLEAN }
            "null" -> Token.NULL
            else -> throw JsonSyntaxException("Littéral inconnu.")
        }
    }

    private fun readNumber(first: Int): String {
        text.setLength(0)
        text.append(first.toChar())
        while (true) {
            val c = nextChar()
            if (c in '0'.code..'9'.code || c == '-'.code || c == '+'.code || c == '.'.code || c == 'e'.code || c == 'E'.code) text.append(c.toChar())
            else { if (c >= 0) pos--; break }
        }
        val number = text.toString()
        if (number.toDoubleOrNull() == null) throw JsonSyntaxException("Nombre invalide.")
        return number
    }

    /** Called after the opening quote. Copies unescaped runs straight from the buffer. */
    private fun readString(): String {
        text.setLength(0)
        while (true) {
            if (pos == limit && !fill()) throw JsonSyntaxException("Chaîne non terminée.")
            val start = pos
            var escaped = false
            while (pos < limit) {
                val ch = buffer[pos]
                if (ch == '"') {
                    text.append(buffer, start, pos - start)
                    pos++
                    return text.toString()
                }
                if (ch == '\\') {
                    text.append(buffer, start, pos - start)
                    pos++
                    text.append(readEscape())
                    escaped = true
                    break
                }
                pos++
            }
            // The run reached the end of the buffer without a quote or an escape: keep it and refill.
            if (!escaped) text.append(buffer, start, limit - start)
        }
    }

    private fun readEscape(): Char {
        val c = nextChar()
        if (c < 0) throw JsonSyntaxException("Chaîne non terminée.")
        return when (c.toChar()) {
            '"' -> '"'; '\\' -> '\\'; '/' -> '/'
            'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
            'u' -> {
                var code = 0
                repeat(4) {
                    val h = nextChar()
                    val digit = if (h < 0) -1 else Character.digit(h.toChar(), 16)
                    if (digit < 0) throw JsonSyntaxException("Séquence \\u invalide.")
                    code = code * 16 + digit
                }
                code.toChar()
            }
            else -> throw JsonSyntaxException("Séquence d'échappement invalide.")
        }
    }

    private fun nextChar(): Int {
        if (pos == limit && !fill()) return -1
        return buffer[pos++].code
    }

    private fun nextNonWhitespace(): Int {
        while (true) {
            val c = nextChar()
            if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c
        }
    }

    private fun nextNonWhitespaceOrThrow(): Int {
        val c = nextNonWhitespace()
        if (c < 0) throw JsonSyntaxException("Fin du document inattendue.")
        return c
    }

    private fun fill(): Boolean {
        val read = reader.read(buffer, 0, buffer.size)
        pos = 0
        limit = if (read > 0) read else 0
        return read > 0
    }
}

// Lenient accessors: Xtream panels disagree on types, so a field is read as whatever it is and coerced, or skipped.

/** A string, a number or a boolean as text; null for `null` or for a nested value, which is skipped. */
fun JsonStreamReader.nextStringOrNull(): String? = when (peek()) {
    JsonStreamReader.Token.STRING, JsonStreamReader.Token.NUMBER -> nextString()
    JsonStreamReader.Token.BOOLEAN -> nextBoolean().toString()
    JsonStreamReader.Token.NULL -> { nextNull(); null }
    else -> { skipValue(); null }
}

fun JsonStreamReader.nextLongOrNull(): Long? = nextStringOrNull()?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

fun JsonStreamReader.nextIntOrNull(): Int? = nextLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

fun JsonStreamReader.nextDoubleOrNull(): Double? = nextStringOrNull()?.trim()?.replace(',', '.')?.toDoubleOrNull()

/** `true`, `1`, `"1"` and `"true"` are true; `false`, `0`, `""` and `"0"` are false; anything else is null. */
fun JsonStreamReader.nextBooleanOrNull(): Boolean? = when (nextStringOrNull()?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0", "" -> false
    else -> null
}

/**
 * Runs [action] once per element of an array, or once per value of an object: PHP panels serialise an empty list
 * as `{}` and some key their lists by id. Any other value is skipped. [action] must consume exactly one value.
 */
inline fun JsonStreamReader.forEachElement(action: () -> Unit) {
    when (peek()) {
        JsonStreamReader.Token.BEGIN_ARRAY -> { beginArray(); while (hasNext()) action(); endArray() }
        JsonStreamReader.Token.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { nextName(); action() }; endObject() }
        else -> skipValue()
    }
}

/** Runs [action] with each field name of an object; [action] must consume the value. A non-object is skipped. */
inline fun JsonStreamReader.forEachField(action: (String) -> Unit) {
    if (peek() != JsonStreamReader.Token.BEGIN_OBJECT) { skipValue(); return }
    beginObject()
    while (hasNext()) action(nextName())
    endObject()
}

/** Scalars of an array (or object values) as text; nested values are dropped. */
fun JsonStreamReader.nextStringList(): List<String> {
    val out = ArrayList<String>()
    forEachElement { nextStringOrNull()?.let(out::add) }
    return out
}
