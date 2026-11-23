package eu.kanade.tachiyomi.extension.zh.copymanga

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.nodes.Document

internal data class CopyMangaWebChapterFormat(val key: String, val header: String)

internal fun Document.copyMangaWebChapterFormat(): CopyMangaWebChapterFormat {
    val key = copyMangaScriptValue("ccz")
    val header = selectFirst("#dnt")?.attr("value")?.takeIf { it.isNotBlank() }
        ?: error("CopyManga HTML response is missing the chapter catalog header")
    return CopyMangaWebChapterFormat(key, header)
}

internal fun CopyMangaWebChapterFormat.decodeChapterCatalog(payload: String, comicPath: String): CopyMangaWebChapterCatalog {
    val catalog = copyMangaWebJson.decodeFromString<CopyMangaWebChapterCatalog>(decodeCopyMangaWebPayload(key, payload))

    check(catalog.build.pathWord == comicPath) { "CopyManga chapter catalog belongs to a different manga" }
    check(catalog.groups.values.any { it.chapters.isNotEmpty() }) { "CopyManga chapter catalog is empty" }
    check(catalog.groups.values.all { it.count == it.chapters.size }) { "CopyManga chapter catalog is incomplete" }
    val chapterIds = catalog.groups.values.flatMap { group -> group.chapters.map { it.id } }
    check(chapterIds.size == chapterIds.toSet().size && chapterIds.all(chapterIdPattern::matches)) {
        "CopyManga chapter catalog contains invalid or duplicate chapter IDs"
    }
    return catalog
}

@Serializable
internal class CopyMangaWebChapterCatalog(
    val build: CopyMangaWebChapterBuild,
    val groups: Map<String, CopyMangaWebChapterGroup>,
)

@Serializable
internal class CopyMangaWebChapterBuild(@SerialName("path_word") val pathWord: String)

@Serializable
internal class CopyMangaWebChapterGroup(
    @SerialName("path_word") val pathWord: String,
    val name: String,
    val count: Int,
    val chapters: List<CopyMangaWebChapter>,
    @SerialName("last_chapter") val lastChapter: CopyMangaWebLastChapter? = null,
)

@Serializable
internal class CopyMangaWebChapter(val id: String, val name: String)

@Serializable
internal class CopyMangaWebLastChapter(
    val uuid: String,
    @SerialName("datetime_created") val datetimeCreated: String? = null,
)

internal const val DEFAULT_CHAPTER_GROUP = "default"

internal fun CopyMangaWebChapterCatalog.toSChapters(
    comicPath: String,
    existingChapters: List<SChapter>,
    transformText: (String) -> String,
): List<SChapter> {
    val existingById = existingChapters.associateBy { it.url.trimEnd('/').substringAfterLast('/') }
    return groups.values.sortedBy { it.pathWord != DEFAULT_CHAPTER_GROUP }.flatMap { group ->
        val groupName = group.name.takeUnless { group.pathWord == DEFAULT_CHAPTER_GROUP }.orEmpty()
        group.chapters.asReversed().map { chapter ->
            val last = group.lastChapter?.takeIf { it.uuid == chapter.id }
            CopyMangaChapter(chapter.id, chapter.name, last?.datetimeCreated.orEmpty())
                .toSChapter(comicPath, groupName, transformText).apply {
                    existingById[chapter.id]?.let { existing ->
                        url = existing.url
                        if (date_upload == 0L) date_upload = existing.date_upload
                    }
                }
        }
    }
}

private val chapterIdPattern = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
