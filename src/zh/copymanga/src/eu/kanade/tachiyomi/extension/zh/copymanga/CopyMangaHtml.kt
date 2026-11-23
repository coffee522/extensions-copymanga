package eu.kanade.tachiyomi.extension.zh.copymanga

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class CopyMangaHtmlPage(
    val total: Int,
    val comics: List<CopyMangaComic>,
)

internal data class CopyMangaRecommendationsPage(
    val comics: List<CopyMangaComic>,
    val hasNextPage: Boolean,
)

internal fun Document.parseCopyMangaRecommendations(nextOffset: Int): CopyMangaRecommendationsPage {
    val list = selectFirst("#comic") ?: error("CopyManga HTML response is missing the recommendations")
    val comics = list.select(".exemptComic_Item").mapNotNull { card ->
        card.parseCopyMangaCard(".exemptComicItem-txt", ".exemptComic_Item-img")
    }
    val next = selectFirst("li.next a[href]")?.absUrl("href")?.toHttpUrlOrNull()
    return CopyMangaRecommendationsPage(
        comics = comics,
        hasNextPage = comics.isNotEmpty() && next?.queryParameter("offset")?.toIntOrNull() == nextOffset,
    )
}

internal fun Document.parseCopyMangaRankings(): List<CopyMangaComic> {
    val list = selectFirst(".ranking-all") ?: error("CopyManga HTML response is missing the rankings")
    return list.select(".ranking-all-box").mapNotNull { card ->
        card.parseCopyMangaCard(".ranking-all-topThree-txt", ".ranking-all-topThree-img")
    }
}

internal fun Document.parseCopyMangaDetails(comicPath: String): CopyMangaComic {
    val details = selectFirst(".comicParticulars-title")
        ?: error("CopyManga HTML response is missing manga details")
    val name = details.selectFirst("h6")?.text()?.takeIf { it.isNotBlank() }
        ?: error("CopyManga HTML response is missing the manga title")
    val statusText = details.select(".comicParticulars-title-right li")
        .firstOrNull { it.selectFirst("span")?.text() in setOf("狀態：", "状态：") }
        ?.selectFirst(".comicParticulars-right-txt")
        ?.text()
    val status = when (statusText) {
        "連載中", "连载中" -> CopyMangaValue(0, statusText)
        "已完結", "已完结", "完結", "完结" -> CopyMangaValue(1, statusText)
        "短篇" -> CopyMangaValue(2, statusText)
        else -> null
    }
    val authors = details.select("a[href^=/author/]").mapNotNull { author ->
        author.absUrl("href").toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
            ?.let { CopyMangaTag(name = author.text(), pathWord = it) }
    }
    val themes = details.select(".comicParticulars-tag a[href]").mapNotNull { theme ->
        val path = theme.absUrl("href").toHttpUrlOrNull()?.queryParameter("theme") ?: return@mapNotNull null
        CopyMangaTag(name = theme.text().removePrefix("#"), pathWord = path)
    }
    return CopyMangaComic(
        name = name,
        pathWord = comicPath,
        author = authors,
        cover = details.selectFirst(".comicParticulars-left-img img[data-src]")?.absUrl("data-src").orEmpty(),
        status = status,
        theme = themes,
        brief = selectFirst(".comicParticulars-synopsis .intro")?.wholeText()?.trim(),
    )
}

private fun Element.parseCopyMangaCard(textSelector: String, imageSelector: String): CopyMangaComic? {
    val text = selectFirst(textSelector) ?: return null
    val title = text.selectFirst("a[href^=/comic/]") ?: return null
    val pathWord = title.absUrl("href").toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
        ?.takeIf { it.isNotBlank() } ?: return null
    val name = title.text().takeIf { it.isNotBlank() } ?: return null
    val cover = selectFirst("$imageSelector img[data-src]")?.absUrl("data-src").orEmpty()
    val authors = text.select("a[href^=/author/]").mapNotNull { author ->
        author.absUrl("href").toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
            ?.let { CopyMangaTag(name = author.text(), pathWord = it) }
    }
    return CopyMangaComic(name = name, pathWord = pathWord, author = authors, cover = cover)
}

/**
 * Parses the server-rendered list used by the public /comics page.
 *
 * The site puts the first page in an HTML attribute and lets browser JavaScript
 * call eval on it. It is a small JavaScript/Python-style literal rather than
 * JSON, so the extension parses only the literal values it needs instead of
 * executing remote code.
 */
internal fun Document.parseCopyMangaHtmlPage(): CopyMangaHtmlPage {
    val listBox = selectFirst(".exemptComicList .exemptComic-box")
        ?: error("CopyManga HTML response is missing the comic list")
    val total = listBox.attr("total").toIntOrNull()
        ?: error("CopyManga HTML response has an invalid comic total")
    val literal = listBox.attr("list")
    if (literal.isBlank()) {
        error("CopyManga HTML response is missing the comic list data")
    }

    return CopyMangaHtmlPage(
        total = total,
        comics = CopyMangaHtmlLiteralParser(literal).parseList().mapNotNull { it.toCopyMangaComic() },
    )
}

private fun Map<String, Any?>.toCopyMangaComic(): CopyMangaComic? {
    val pathWord = stringValue("path_word")?.takeIf { it.isNotBlank() } ?: return null
    val name = stringValue("name")?.takeIf { it.isNotBlank() } ?: return null
    val authors = (this["author"] as? List<*>)
        ?.mapNotNull { (it as? Map<*, *>)?.toCopyMangaTag() }
        .orEmpty()
    val status = intValue("status")?.let { value ->
        CopyMangaValue(value = value, display = copyMangaStatusDisplay(value))
    }

    return CopyMangaComic(
        name = name,
        pathWord = pathWord,
        author = authors,
        cover = stringValue("cover").orEmpty(),
        status = status,
    )
}

private fun Map<*, *>.toCopyMangaTag(): CopyMangaTag? {
    val name = this["name"] as? String ?: return null
    val pathWord = this["path_word"] as? String ?: ""
    return CopyMangaTag(name = name, pathWord = pathWord)
}

private fun Map<String, Any?>.stringValue(key: String): String? = this[key] as? String

private fun Map<String, Any?>.intValue(key: String): Int? = when (val value = this[key]) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

private fun copyMangaStatusDisplay(value: Int): String = when (value) {
    0 -> "連載中"
    1 -> "已完結"
    2 -> "短篇"
    else -> ""
}

private class CopyMangaHtmlLiteralParser(
    private val input: String,
) {
    private var index = 0

    fun parseList(): List<Map<String, Any?>> {
        val value = parseValue()
        skipWhitespace()
        if (index < input.length && input[index] == ';') {
            index++
            skipWhitespace()
        }
        check(index == input.length) { "Unexpected CopyManga list data at position $index" }
        return (value as? List<*>)
            ?.map { it as? Map<*, *> ?: error("Invalid CopyManga comic entry") }
            ?.map { entry ->
                entry.entries.associate { (key, item) ->
                    (key as? String ?: error("Invalid CopyManga comic key")) to item
                }
            }
            ?: error("CopyManga list data is not an array")
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        return when (val character = input.getOrNull(index)) {
            '[' -> parseArray()
            '{' -> parseObject()
            '\'', '"' -> parseString()
            null -> error("Unexpected end of CopyManga list data")
            else -> if (character == '-' || character.isDigit()) {
                parseNumber()
            } else {
                parseIdentifier()
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        if (consume(']')) return emptyList()

        val values = mutableListOf<Any?>()
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consume(']')) return values
            expect(',')
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        if (consume('}')) return emptyMap()

        val values = linkedMapOf<String, Any?>()
        while (true) {
            skipWhitespace()
            val key = if (input.getOrNull(index) == '\'' || input.getOrNull(index) == '"') {
                parseString()
            } else {
                parseIdentifierName()
            }
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return values
            expect(',')
        }
    }

    private fun parseNumber(): Number {
        val start = index
        if (consume('-')) {
            check(input.getOrNull(index)?.isDigit() == true) { "Invalid CopyManga number at position $index" }
        }
        while (input.getOrNull(index)?.isDigit() == true) index++
        if (consume('.')) {
            while (input.getOrNull(index)?.isDigit() == true) index++
        }
        val value = input.substring(start, index)
        return value.toIntOrNull() ?: value.toDoubleOrNull()
            ?: error("Invalid CopyManga number at position $start")
    }

    private fun parseIdentifier(): Any? = when (val value = parseIdentifierName()) {
        "null", "None", "undefined" -> null
        "true", "True" -> true
        "false", "False" -> false
        else -> error("Unexpected CopyManga literal '$value'")
    }

    private fun parseIdentifierName(): String {
        skipWhitespace()
        val start = index
        while (input.getOrNull(index)?.let { it.isLetterOrDigit() || it == '_' } == true) index++
        check(index > start) { "Expected CopyManga literal at position $index" }
        return input.substring(start, index)
    }

    private fun parseString(): String {
        val quote = input.getOrNull(index)
        check(quote == '\'' || quote == '"') { "Expected CopyManga string at position $index" }
        index++

        return buildString {
            while (true) {
                val character = input.getOrNull(index++) ?: error("Unterminated CopyManga string")
                when {
                    character == quote -> return@buildString
                    character != '\\' -> append(character)
                    else -> append(parseEscape())
                }
            }
        }
    }

    private fun parseEscape(): Char {
        val escaped = input.getOrNull(index++) ?: error("Unterminated CopyManga escape")
        return when (escaped) {
            '\\' -> '\\'
            '\'' -> '\''
            '"' -> '"'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseHexEscape(4)
            'x' -> parseHexEscape(2)
            else -> escaped
        }
    }

    private fun parseHexEscape(length: Int): Char {
        val end = index + length
        check(end <= input.length) { "Incomplete CopyManga unicode escape" }
        val value = input.substring(index, end).toIntOrNull(16)
            ?: error("Invalid CopyManga unicode escape")
        index = end
        return value.toChar()
    }

    private fun skipWhitespace() {
        while (input.getOrNull(index)?.isWhitespace() == true) index++
    }

    private fun expect(expected: Char) {
        check(consume(expected)) { "Expected '$expected' at position $index" }
    }

    private fun consume(expected: Char): Boolean = if (input.getOrNull(index) == expected) {
        index++
        true
    } else {
        false
    }
}
