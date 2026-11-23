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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import org.jsoup.nodes.Document
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

internal class CopyMangaHttpException(val code: Int, host: String, message: String? = null) : IllegalStateException("CopyManga API HTTP $code ($host)${message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")

internal fun Response.copyMangaApiPayload(): CopyMangaApiResponse {
    if (code == 210) {
        val message = try {
            parseAs<CopyMangaApiResponse>().message
        } catch (_: SerializationException) {
            null
        }
        throw CopyMangaHttpException(code, request.url.host, message)
    }
    if (code != 200) throw CopyMangaHttpException(code, request.url.host)
    return parseAs()
}

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
        if (request.transport == CopyMangaRequestTransport.HTML) return htmlMangaPage(request.parameters)
        val domain = selectedDomain
        return try {
            apiMangaPage(request, "${domain.apiBaseUrl}/api/v3")
        } catch (error: CopyMangaHttpException) {
            if (error.code != 521) throw error
            when (request.path) {
                "recs" -> htmlRecommendationPage(domain, request.parameters)
                "comics" -> htmlMangaPage(request.parameters, domain)
                "ranks" -> htmlRankingPage(domain, request.parameters)
                "search/comic" -> apiMangaPage(
                    request = request.copy(
                        path = "searchcl/comics",
                        parameters = request.parameters - "_update" + ("platform" to "1"),
                    ),
                    baseUrl = "${domain.webBaseUrl}/api/kb/web",
                )
                else -> throw error
            }
        }
    }

    private suspend fun apiMangaPage(request: CopyMangaRequest, baseUrl: String): MangasPage {
        val path = request.path
        val parameters = request.parameters
        val page = if (path == "recs" || path == "ranks") {
            val recommendations = apiResults<CopyMangaList<CopyMangaRecommendation>>(path, parameters, baseUrl)
            CopyMangaList(recommendations.total, recommendations.offset, recommendations.list.map { it.comic })
        } else {
            apiResults<CopyMangaList<CopyMangaComic>>(path, parameters, baseUrl)
        }
        return mangaPageFromTotal(page.list, page.offset, page.total, parameters)
    }

    private fun mangaPageFromTotal(
        comics: List<CopyMangaComic>,
        offset: Int,
        total: Int,
        parameters: Map<String, String>,
    ): MangasPage = MangasPage(
        mangas = comics.map { it.toSManga(transformText = ::displayText) },
        hasNextPage = copyMangaHasNextPage(
            offset = offset,
            total = total,
            returnedSize = comics.size,
            requestedPageSize = parameters["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: PAGE_SIZE,
        ),
    )

    private suspend fun htmlMangaPage(parameters: Map<String, String>, domain: CopyMangaDomain = selectedDomain): MangasPage {
        val urlBuilder = "${domain.webBaseUrl}/comics".toHttpUrl().newBuilder()
        parameters
            .filterKeys { key -> key != "_update" && key != "top" }
            .forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }
        if (parameters["top"] == TOP_FINISH) urlBuilder.addQueryParameter("status", "1")

        val page = client.get(
            url = urlBuilder.build(),
            headers = headers.copyMangaHtmlHeaders(),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).asJsoup().parseCopyMangaHtmlPage()

        return mangaPageFromTotal(
            comics = page.comics,
            offset = parameters["offset"]?.toIntOrNull() ?: 0,
            total = page.total,
            parameters = parameters,
        )
    }

    private suspend fun htmlRecommendationPage(domain: CopyMangaDomain, parameters: Map<String, String>): MangasPage {
        val offset = parameters.getValue("offset")
        val limit = parameters.getValue("limit")
        val url = "${domain.webBaseUrl}/recommend".toHttpUrl().newBuilder()
            .addQueryParameter("type", parameters.getValue("pos"))
            .addQueryParameter("offset", offset)
            .addQueryParameter("limit", limit)
            .build()
        val page = client.get(
            url = url,
            headers = headers.copyMangaHtmlHeaders(),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).asJsoup().parseCopyMangaRecommendations(offset.toInt() + limit.toInt())
        return MangasPage(
            mangas = page.comics.map { it.toSManga(transformText = ::displayText) },
            hasNextPage = page.hasNextPage,
        )
    }

    private suspend fun htmlRankingPage(domain: CopyMangaDomain, parameters: Map<String, String>): MangasPage {
        val url = "${domain.webBaseUrl}/rank".toHttpUrl().newBuilder()
            .addQueryParameter("type", parameters.getValue("audience_type"))
            .addQueryParameter("table", parameters.getValue("date_type"))
            .build()
        val rankings = client.get(
            url = url,
            headers = headers.copyMangaHtmlHeaders(),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).asJsoup().parseCopyMangaRankings()
        val offset = parameters.getValue("offset").toInt()
        val limit = parameters.getValue("limit").toInt()
        return mangaPageFromTotal(rankings.drop(offset).take(limit), offset, rankings.size, parameters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in SITE_HOSTS) return null

        val segments = url.pathSegments
        val comicIndex = segments.indexOfFirst { it == "comic" || it == "comic2" }
        if (comicIndex < 0) return null
        val comicPath = segments.getOrNull(comicIndex + 1)?.takeIf { it.isNotBlank() }
            ?: return null

        val comic = try {
            apiResults<CopyMangaDetails>("comic2/$comicPath").comic
        } catch (error: CopyMangaHttpException) {
            if (error.code != 521) throw error
            webMangaDetails(comicPath).parseCopyMangaDetails(comicPath)
        }
        return comic.toSManga(mangaUrl(comicPath), ::displayText)
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
        return try {
            fetchApiMangaUpdate(manga, comicPathValue, chapters, fetchDetails, fetchChapters)
        } catch (error: CopyMangaHttpException) {
            if (error.code != 521) throw error
            fetchWebMangaUpdate(manga, comicPathValue, chapters, fetchDetails, fetchChapters)
        }
    }

    private suspend fun fetchApiMangaUpdate(
        manga: SManga,
        comicPathValue: String,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
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

    private suspend fun webMangaDetails(comicPath: String): Document {
        val url = CHAPTER_WEB_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("comic")
            .addPathSegment(comicPath)
            .build()
        return client.get(
            url = url,
            headers = headers.copyMangaHtmlHeaders(),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).asJsoup()
    }

    private suspend fun fetchWebMangaUpdate(
        manga: SManga,
        comicPath: String,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = webMangaDetails(comicPath)
        val updatedManga = if (fetchDetails) {
            document.parseCopyMangaDetails(comicPath).toSManga(mangaUrl(comicPath), ::displayText)
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            val format = document.copyMangaWebChapterFormat()
            val payload = apiResults<String>(
                path = "chapters",
                baseUrl = CHAPTER_WEB_BASE_URL.toHttpUrl().newBuilder()
                    .addPathSegment("comicdetail")
                    .addPathSegment(comicPath)
                    .build()
                    .toString(),
                requestHeaders = headers.newBuilder().set("dnts", format.header).build(),
            )
            format.decodeChapterCatalog(payload, comicPath).toSChapters(comicPath, chapters, ::displayText)
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
        val results = try {
            apiResults<CopyMangaPageListWrapper>(
                path = "comic/$comicPath/chapter2/$chapterId",
                parameters = mapOf("platform" to "1"),
            )
        } catch (error: CopyMangaHttpException) {
            if (error.code != 521) throw error
            return webPageList(comicPath, chapterId)
        }
        return results.chapter.words.zip(results.chapter.contents)
            .sortedBy { (word, _) -> word }
            .mapIndexedNotNull { index, (_, content) -> content.toPage(index, IMAGE_RESOLUTION_REGEX) }
    }

    private suspend fun webPageList(comicPath: String, chapterId: String): List<Page> {
        val url = CHAPTER_WEB_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("comic")
            .addPathSegment(comicPath)
            .addPathSegment("chapter")
            .addPathSegment(chapterId)
            .build()
        val document = client.get(
            url = url,
            headers = headers.copyMangaHtmlHeaders(),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).asJsoup()
        return document.parseCopyMangaWebImageUrls().mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
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
        baseUrl: String = "${selectedDomain.apiBaseUrl}/api/v3",
        requestHeaders: Headers = headers,
    ): T {
        val urlBuilder = "$baseUrl/${path.trimStart('/')}".toHttpUrl().newBuilder()
        parameters.forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }

        return apiClient.get(
            url = urlBuilder.build(),
            headers = requestHeaders,
            cacheControl = apiCacheControl,
            ensureSuccess = false,
        ).use { response ->
            val payload = response.copyMangaApiPayload()
            if (payload.code != 200) {
                val message = payload.message.orEmpty()
                error("CopyManga API code ${payload.code}${message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            payload.results?.parseAs<T>()
                ?: error("CopyManga API response is missing results")
        }
    }

    private fun pageOffset(page: Int): Int = page.minus(1).coerceAtLeast(0) * PAGE_SIZE

    companion object {
        private const val CHAPTER_WEB_BASE_URL = "https://www.copy20.com"
        private val SITE_HOSTS = CopyMangaDomain.entries
            .flatMap { domain ->
                val host = domain.webBaseUrl.toHttpUrl().host
                listOf(host, host.removePrefix("www."))
            }
            .toSet()
        private const val PAGE_SIZE = 21
        private const val THEME_PAGE_SIZE = 500
        private const val CHAPTER_PAGE_SIZE = 500
        private const val RECOMMENDATION_POSITION = 3200102
        private val IMAGE_RESOLUTION_REGEX = Regex(
            "\\d+(?=x\\.(?:jpe?g|png|webp)(?:\\?|$))",
            RegexOption.IGNORE_CASE,
        )
    }
}
