package eu.kanade.tachiyomi.extension.zh.copymanga

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
internal class CopyMangaApiResponse(
    val code: Int,
    val message: String? = null,
    val results: JsonElement? = null,
)

@Serializable
internal class CopyMangaList<T>(
    val total: Int,
    val offset: Int,
    val list: List<T>,
)

@Serializable
internal class CopyMangaRecommendation(
    val comic: CopyMangaComic,
)

@Serializable
internal class CopyMangaComic(
    val name: String,
    @SerialName("path_word") val pathWord: String,
    val author: List<CopyMangaTag>,
    val cover: String,
    val region: CopyMangaValue? = null,
    val status: CopyMangaValue? = null,
    val theme: List<CopyMangaTag>? = null,
    val brief: String? = null,
)

@Serializable
internal class CopyMangaTag(
    val name: String,
    @SerialName("path_word") val pathWord: String,
)

@Serializable
internal class CopyMangaValue(
    val value: Int,
    val display: String,
)

@Serializable
internal class CopyMangaDetails(
    val comic: CopyMangaComic,
    val groups: Map<String, CopyMangaTag>? = null,
)

@Serializable
internal class CopyMangaChapter(
    val uuid: String,
    val name: String,
    @SerialName("datetime_created") val datetimeCreated: String,
    val index: Int? = null,
    @SerialName("chapter_index") val chapterIndex: Int? = null,
)

@Serializable
internal class CopyMangaPageListWrapper(
    val chapter: CopyMangaPageList,
)

@Serializable
internal class CopyMangaPageList(
    val contents: List<CopyMangaPage>,
    val words: List<Int>,
)

@Serializable
internal class CopyMangaPage(
    val url: String? = null,
)

internal fun CopyMangaComic.toSManga(
    urlOverride: String? = null,
    transformText: (String) -> String = { it },
): SManga {
    val statusDisplay = status?.display
    val statusValue = status?.value

    return SManga.create().apply {
        url = urlOverride ?: "/comic/$pathWord"
        title = transformText(name)
        thumbnail_url = cover
        author = this@toSManga.author.joinToString { transformText(it.name) }
        artist = author
        description = brief?.let(transformText)
        genre = listOfNotNull(
            region?.display?.let(transformText),
            theme?.joinToString { transformText(it.name) },
        ).joinToString().takeIf { it.isNotBlank() }
        status = when {
            statusDisplay?.contains("完结") == true || statusDisplay?.contains("完結") == true -> SManga.COMPLETED
            statusDisplay?.contains("连载") == true || statusDisplay?.contains("連載") == true -> SManga.ONGOING
            statusValue == 0 -> SManga.ONGOING
            statusValue == 1 || statusValue == 2 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

internal fun CopyMangaChapter.toSChapter(
    comicPath: String,
    groupName: String,
    transformText: (String) -> String = { it },
): SChapter = SChapter.create().apply {
    url = "/comic/$comicPath/chapter/$uuid"
    val displayName = transformText(this@toSChapter.name)
    val displayGroup = groupName.takeIf { it.isNotBlank() }?.let(transformText)
    name = displayGroup?.let { "$it：$displayName" } ?: displayName
    date_upload = DateTimeFormatter.ISO_LOCAL_DATE.tryParseDate(
        datetimeCreated.take(10),
        ZoneOffset.ofHours(8),
    )
}

internal fun CopyMangaPage.toPage(index: Int, resolutionRegex: Regex): Page? = url
    ?.takeIf { it.isNotBlank() }
    ?.replace(resolutionRegex, "1500")
    ?.let { Page(index, imageUrl = it) }
