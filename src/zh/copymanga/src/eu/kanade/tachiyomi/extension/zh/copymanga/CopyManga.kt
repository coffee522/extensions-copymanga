package eu.kanade.tachiyomi.extension.zh.copymanga

import androidx.preference.PreferenceScreen
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

internal fun copyMangaHasNextPage(
    offset: Int,
    total: Int,
    returnedSize: Int,
    requestedPageSize: Int,
): Boolean = requestedPageSize > 0 && returnedSize >= requestedPageSize && offset + returnedSize < total

internal fun Headers.copyMangaHtmlHeaders(): Headers = newBuilder()
    .removeAll("Version")
    .removeAll("Webp")
    .removeAll("platform")
    .set("Accept", "text/html")
    .build()

@Source
abstract class CopyManga :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val selectedDomain: CopyMangaDomain
        get() = preferences.copyMangaDomain()

    private val useSimplifiedChinese: Boolean
        get() = preferences.copyMangaLanguage() == CopyMangaLanguage.SIMPLIFIED

    private fun displayText(value: String): String = if (useSimplifiedChinese) {
        ChineseUtils.toSimplified(value)
    } else {
        value
    }

    /**
     * The API and image traffic intentionally use different clients. The API client has no idle
     * connection pool, which makes a completed API call unable to reuse its previous idle TCP/TLS
     * connection. This is a transport choice, not a fixed delay or a retry policy.
     */
    private val apiClient: OkHttpClient by lazy {
        client.newBuilder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val apiCacheControl = CacheControl.FORCE_NETWORK

    override fun Headers.Builder.configureHeaders() = set(
        "Accept",
        "application/json",
    ).set(
        "User-Agent",
        preferences.copyMangaUserAgent(),
    ).set(
        "Version",
        "2025.11.21",
    ).set(
        "Webp",
        "1",
    ).set(
        "platform",
        "1",
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addCopyMangaPreferences(
            preferences = preferences,
            transformText = ::displayText,
        )
    }

    override fun getHomeUrl(): String = selectedDomain.webBaseUrl

    override suspend fun getPopularManga(page: Int): MangasPage = mangaPage(
        request = CopyMangaRequest(
            path = "recs",
            parameters = mapOf(
                "pos" to RECOMMENDATION_POSITION.toString(),
                "limit" to PAGE_SIZE.toString(),
                "offset" to pageOffset(page).toString(),
            ),
        ),
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildCopyMangaBrowseRequest(
            options = preferences.copyMangaLatestOptions(),
            limit = PAGE_SIZE,
            offset = pageOffset(page),
        )
        return mangaPage(request)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val request = buildCopyMangaSearchRequest(
            query = query,
            filters = filters,
            limit = PAGE_SIZE,
            offset = pageOffset(page),
        )
        return mangaPage(request)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val data = apiResults<JsonElement>(
            path = "theme/comic/count",
            parameters = mapOf("limit" to THEME_PAGE_SIZE.toString()),
        )
        val themes = parseThemes(data)
        preferences.saveCopyMangaCachedThemes(themes)
        return data
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val themes = data?.let(::parseThemes).orEmpty()
        if (preferences.copyMangaCachedThemes().isEmpty()) {
            preferences.saveCopyMangaCachedThemes(themes)
        }
        return buildCopyMangaFilterList(themes, ::displayText)
    }

    private fun parseThemes(data: JsonElement): List<CopyMangaTag> = runCatching {
        data.parseAs<CopyMangaList<CopyMangaTag>>().list
    }.getOrDefault(emptyList())

    private suspend fun mangaPage(request: CopyMangaRequest): MangasPage {
        val path = request.path
        val parameters = request.parameters
        val requestedPageSize = parameters["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: PAGE_SIZE
        val results = when {
            request.transport == CopyMangaRequestTransport.HTML -> htmlMangaPage(parameters)
            path == "recs" || path == "ranks" -> apiResults<CopyMangaList<CopyMangaRecommendation>>(path, parameters).let { page ->
                MangaPageResult(
                    mangas = page.list.map { it.comic.toSManga(transformText = ::displayText) },
                    offset = page.offset,
                    total = page.total,
                )
            }
            else -> apiResults<CopyMangaList<CopyMangaComic>>(path, parameters).let { page ->
                MangaPageResult(
                    mangas = page.list.map { it.toSManga(transformText = ::displayText) },
                    offset = page.offset,
                    total = page.total,
                )
            }
        }

        return MangasPage(
            mangas = results.mangas,
            hasNextPage = copyMangaHasNextPage(
                offset = results.offset,
                total = results.total,
                returnedSize = results.mangas.size,
                requestedPageSize = requestedPageSize,
            ),
        )
    }

    private suspend fun htmlMangaPage(parameters: Map<String, String>): MangaPageResult {
        val urlBuilder = "${selectedDomain.webBaseUrl}/comics".toHttpUrl().newBuilder()
        parameters
            .filterKeys { key -> key != "_update" && key != "top" }
            .forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }

        val page = client.get(
            url = urlBuilder.build(),
            headers = headers.copyMangaHtmlHeaders(),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).asJsoup().parseCopyMangaHtmlPage()

        return MangaPageResult(
            mangas = page.comics.map { it.toSManga(transformText = ::displayText) },
            offset = parameters["offset"]?.toIntOrNull() ?: 0,
            total = page.total,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in SITE_HOSTS) return null

        val segments = url.pathSegments
        val comicIndex = segments.indexOfFirst { it == "comic" || it == "comic2" }
        if (comicIndex < 0) return null
        val comicPath = segments.getOrNull(comicIndex + 1)?.takeIf { it.isNotBlank() }
            ?: return null

        val results = apiResults<CopyMangaDetails>("comic2/$comicPath")
        return results.comic.toSManga(mangaUrl(comicPath), ::displayText)
    }

    override fun getMangaUrl(manga: SManga): String = "${selectedDomain.webBaseUrl}${mangaUrl(comicPath(manga.url))}"

    override fun getChapterUrl(chapter: SChapter): String {
        val coordinates = runCatching { chapterCoordinates(chapter.url) }.getOrNull()
            ?: return selectedDomain.webBaseUrl
        return "${selectedDomain.webBaseUrl}${chapterUrl(coordinates.first, coordinates.second)}"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val comicPathValue = comicPath(manga.url)
        val results = apiResults<CopyMangaDetails>("comic2/$comicPathValue")

        val updatedManga = if (fetchDetails) {
            results.comic.toSManga(mangaUrl(comicPathValue), ::displayText)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val groups = results.groups
                ?: error("CopyManga API response is missing results.groups")
            val chapterList = mutableListOf<SChapter>()
            chapterList += fetchGroupChapters(
                comicPath = comicPathValue,
                groupPath = DEFAULT_CHAPTER_GROUP,
                groupName = "",
            )
            groups.values
                .filterNot { it.pathWord == DEFAULT_CHAPTER_GROUP }
                .forEach { group ->
                    chapterList += fetchGroupChapters(
                        comicPath = comicPathValue,
                        groupPath = group.pathWord,
                        groupName = group.name,
                    )
                }
            chapterList
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchGroupChapters(
        comicPath: String,
        groupPath: String,
        groupName: String,
    ): List<SChapter> {
        if (groupPath.isBlank()) return emptyList()
        val entries = mutableListOf<SChapter>()
        var offset = 0

        while (true) {
            val results = apiResults<CopyMangaList<CopyMangaChapter>>(
                path = "comic/$comicPath/group/$groupPath/chapters",
                parameters = mapOf(
                    "limit" to CHAPTER_PAGE_SIZE.toString(),
                    "offset" to offset.toString(),
                    "_update" to "true",
                ),
            )
            val page = results.list
            if (page.isEmpty()) break

            page.forEach { chapter ->
                entries += chapter.toSChapter(comicPath, groupName, ::displayText)
            }

            offset += page.size
            if (page.size < CHAPTER_PAGE_SIZE || offset >= results.total) break
        }

        return entries.asReversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (comicPath, chapterId) = chapterCoordinates(chapter.url)
        val results = apiResults<CopyMangaPageListWrapper>(
            path = "comic/$comicPath/chapter2/$chapterId",
            parameters = mapOf("platform" to "1"),
        )
        return results.chapter.words.zip(results.chapter.contents)
            .sortedBy { (word, _) -> word }
            .mapIndexedNotNull { index, (_, content) -> content.toPage(index, IMAGE_RESOLUTION_REGEX) }
    }

    private fun comicPath(url: String): String {
        val segments = url.trim('/').split('/')
        val markerIndex = segments.indexOfFirst { it == "comic" || it == "comic2" }
        return segments.getOrNull(if (markerIndex >= 0) markerIndex + 1 else 0)
            ?.takeIf { it.isNotBlank() }
            ?: error("Invalid CopyManga manga URL")
    }

    private fun chapterCoordinates(url: String): Pair<String, String> {
        val segments = url.trim('/').split('/')
        val comicIndex = segments.indexOfFirst { it == "comic" }
        val chapterIndex = segments.indexOfFirst { it == "chapter" }
        if (comicIndex >= 0 && chapterIndex == comicIndex + 2) {
            val comicPath = segments.getOrNull(comicIndex + 1)
            val chapterId = segments.getOrNull(chapterIndex + 1)
            if (!comicPath.isNullOrBlank() && !chapterId.isNullOrBlank()) {
                return comicPath to chapterId
            }
        }

        if (segments.size >= 2) {
            return segments.dropLast(1).joinToString("/") to segments.last()
        }
        error("Invalid CopyManga chapter URL")
    }

    private fun mangaUrl(comicPath: String): String = "/comic/$comicPath"

    private fun chapterUrl(comicPath: String, chapterId: String): String = "/comic/$comicPath/chapter/$chapterId"

    private suspend inline fun <reified T> apiResults(
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): T {
        val urlBuilder = "${selectedDomain.apiBaseUrl}/api/v3/${path.trimStart('/')}".toHttpUrl().newBuilder()
        parameters.forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }

        return apiClient.get(
            url = urlBuilder.build(),
            headers = headers,
            cacheControl = apiCacheControl,
            ensureSuccess = false,
        ).use { response ->
            val payload = response.parseAs<CopyMangaApiResponse>()
            if (response.code == 210) {
                val message = payload.message.orEmpty()
                error("CopyManga API HTTP 210${message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            if (response.code != 200) {
                error("CopyManga API HTTP ${response.code}")
            }
            if (payload.code != 200) {
                val message = payload.message.orEmpty()
                error("CopyManga API code ${payload.code}${message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            payload.results?.parseAs<T>()
                ?: error("CopyManga API response is missing results")
        }
    }

    private class MangaPageResult(
        val mangas: List<SManga>,
        val offset: Int,
        val total: Int,
    )

    private fun pageOffset(page: Int): Int = page.minus(1).coerceAtLeast(0) * PAGE_SIZE

    companion object {
        private val SITE_HOSTS = CopyMangaDomain.entries
            .flatMap { domain ->
                val host = domain.webBaseUrl.toHttpUrl().host
                listOf(host, host.removePrefix("www."))
            }
            .toSet()
        private const val PAGE_SIZE = 21
        private const val THEME_PAGE_SIZE = 500
        private const val CHAPTER_PAGE_SIZE = 500
        private const val DEFAULT_CHAPTER_GROUP = "default"
        private const val RECOMMENDATION_POSITION = 3200102
        private val IMAGE_RESOLUTION_REGEX = Regex(
            "\\d+(?=x\\.(?:jpe?g|png|webp)(?:\\?|$))",
            RegexOption.IGNORE_CASE,
        )
    }
}
