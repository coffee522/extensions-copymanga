package eu.kanade.tachiyomi.extension.zh.copymanga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyMangaLatestOptionsTest {

    @Test
    fun `default latest request keeps original ordering and update flag`() {
        val request = buildCopyMangaBrowseRequest(
            options = copyMangaLatestOptions(
                ranking = null,
                rankingChannel = null,
                sort = null,
                direction = null,
                region = null,
                status = null,
                theme = null,
            ),
            limit = 21,
            offset = 0,
        )

        assertEquals("comics", request.path)
        assertEquals(
            mapOf(
                "ordering" to "-datetime_updated",
                "_update" to "true",
                "limit" to "21",
                "offset" to "0",
            ),
            request.parameters,
        )
        assertEquals(CopyMangaRequestTransport.API, request.transport)
    }

    @Test
    fun `all ranking modes use only period and channel`() {
        for (period in listOf("day", "week", "month", "total")) {
            for (channel in listOf("male", "female")) {
                val request = buildCopyMangaBrowseRequest(
                    options = copyMangaLatestOptions(
                        ranking = period,
                        rankingChannel = channel,
                        sort = "popular",
                        direction = SORT_ASCENDING,
                        region = "2",
                        status = "1",
                        theme = "aiqing",
                    ),
                    limit = 21,
                    offset = 42,
                )

                assertEquals("ranks", request.path)
                assertEquals(
                    mapOf(
                        "date_type" to period,
                        "audience_type" to channel,
                        "limit" to "21",
                        "offset" to "42",
                    ),
                    request.parameters,
                )
            }
        }
    }

    @Test
    fun `sort options preserve legacy sort and direction keys`() {
        val cases = listOf(
            Triple("datetime_updated", SORT_DESCENDING, "-datetime_updated"),
            Triple("datetime_updated", SORT_ASCENDING, "datetime_updated"),
            Triple("popular", SORT_DESCENDING, "-popular"),
            Triple("popular", SORT_ASCENDING, "popular"),
        )

        for ((sort, direction, ordering) in cases) {
            val option = copyMangaLatestSortOption(sort, direction)
            assertEquals(sort, option.sort)
            assertEquals(direction, option.direction)
            assertEquals(ordering, option.ordering)
        }
    }

    @Test
    fun `general latest filters map to comics request`() {
        val options = copyMangaLatestOptions(
            ranking = "",
            rankingChannel = "female",
            sort = "popular",
            direction = SORT_DESCENDING,
            region = "2",
            status = "0",
            theme = "aiqing",
        )

        assertEquals(
            CopyMangaRequest(
                path = "comics",
                parameters = mapOf(
                    "ordering" to "-popular",
                    "_update" to "true",
                    "limit" to "21",
                    "offset" to "42",
                    "region" to "2",
                    "status" to "0",
                    "theme" to "aiqing",
                ),
                transport = CopyMangaRequestTransport.HTML,
            ),
            buildCopyMangaBrowseRequest(options, limit = 21, offset = 42),
        )
    }

    @Test
    fun `invalid stored values fall back without changing latest contract`() {
        val options = copyMangaLatestOptions(
            ranking = "not-a-ranking",
            rankingChannel = "not-a-channel",
            sort = "not-a-sort",
            direction = "not-a-direction",
            region = "not-a-region",
            status = "not-a-status",
            theme = "not-a-theme",
        )

        assertEquals(CopyMangaBrowseOptions(), options)
    }

    @Test
    fun `browse filter and latest use the shared region and status mapping`() {
        assertEquals(
            listOf(
                "全部" to "",
                "日漫" to "0",
                "韓漫" to "1",
                "美漫" to "2",
            ),
            REGION_OPTIONS.toList(),
        )
        assertEquals(
            listOf(
                "全部" to "",
                "連載中" to "0",
                "已完結" to "1",
                "短篇" to "2",
            ),
            STATUS_OPTIONS.toList(),
        )

        val filters = buildCopyMangaFilterList(
            themes = listOf(CopyMangaTag(name = "愛情", pathWord = "aiqing")),
            transformText = { it },
        )
        val regionFilter = filters.filterIsInstance<RegionFilter>().single()
        val statusFilter = filters.filterIsInstance<StatusFilter>().single()
        val themeFilter = filters.filterIsInstance<ThemeFilter>().single()

        regionFilter.state = 1
        statusFilter.state = 0
        val regionOnly = buildCopyMangaSearchRequest(
            query = "",
            filters = filters,
            limit = 21,
            offset = 42,
        )
        assertEquals(CopyMangaRequestTransport.HTML, regionOnly.transport)
        assertEquals("0", regionOnly.parameters["region"])
        assertFalse(regionOnly.parameters.containsKey("top"))
        assertFalse(regionOnly.parameters.containsKey("status"))

        assertNull(copyMangaApiTop("0", ""))
        assertNull(copyMangaApiTop("1", ""))
        assertNull(copyMangaApiTop("2", ""))

        regionFilter.state = 0
        statusFilter.state = 2
        val completedAllRegions = buildCopyMangaSearchRequest(
            query = "",
            filters = filters,
            limit = 21,
            offset = 42,
        )
        assertEquals(CopyMangaRequestTransport.API, completedAllRegions.transport)
        assertEquals(TOP_FINISH, completedAllRegions.parameters["top"])

        regionFilter.state = 0
        statusFilter.state = 0
        themeFilter.state = 1
        val themeOnly = buildCopyMangaSearchRequest(
            query = "",
            filters = filters,
            limit = 21,
            offset = 42,
        )
        assertEquals(CopyMangaRequestTransport.HTML, themeOnly.transport)
        assertEquals("aiqing", themeOnly.parameters["theme"])
        assertFalse(themeOnly.parameters.containsKey("top"))

        regionFilter.state = 2
        statusFilter.state = 3
        themeFilter.state = 0
        val html = buildCopyMangaSearchRequest(
            query = "",
            filters = filters,
            limit = 21,
            offset = 42,
        )
        assertEquals(CopyMangaRequestTransport.HTML, html.transport)
        assertEquals("1", html.parameters["region"])
        assertEquals("2", html.parameters["status"])
        assertFalse(html.parameters.containsKey("theme"))
        assertFalse(html.parameters.containsKey("top"))
    }

    @Test
    fun `nonblank search keeps the search endpoint without browse filters`() {
        val request = buildCopyMangaSearchRequest(
            query = "one piece",
            filters = buildCopyMangaFilterList(emptyList(), transformText = { it }),
            limit = 21,
            offset = 42,
        )

        assertEquals("search/comic", request.path)
        assertEquals(CopyMangaRequestTransport.API, request.transport)
        assertFalse(request.parameters.containsKey("top"))
        assertFalse(request.parameters.containsKey("theme"))
    }

    @Test
    fun `legacy region and status values migrate to supported top classifications`() {
        assertEquals(TOP_JAPAN, migrateCopyMangaLegacyTop("0", null))
        assertEquals(TOP_KOREA, migrateCopyMangaLegacyTop("1", ""))
        assertEquals(TOP_WEST, migrateCopyMangaLegacyTop("2", null))
        assertEquals(TOP_FINISH, migrateCopyMangaLegacyTop(null, "1"))

        assertEquals("", migrateCopyMangaLegacyTop(null, null))
        assertEquals("", migrateCopyMangaLegacyTop("", ""))
        assertEquals("", migrateCopyMangaLegacyTop("0", "1"))
        assertEquals("", migrateCopyMangaLegacyTop("1", "0"))
        assertEquals("", migrateCopyMangaLegacyTop("2", "2"))
        assertEquals("", migrateCopyMangaLegacyTop("invalid", null))
        assertEquals("", migrateCopyMangaLegacyTop(null, "invalid"))
    }

    @Test
    fun `previous top preference values migrate back to independent filters`() {
        assertEquals("0" to "", migrateCopyMangaTopToFilters(TOP_JAPAN))
        assertEquals("1" to "", migrateCopyMangaTopToFilters(TOP_KOREA))
        assertEquals("2" to "", migrateCopyMangaTopToFilters(TOP_WEST))
        assertEquals("" to "1", migrateCopyMangaTopToFilters(TOP_FINISH))
        assertEquals("" to "", migrateCopyMangaTopToFilters("invalid"))
    }

    @Test
    fun `visibility exposes only the relevant mode preferences`() {
        val general = copyMangaLatestVisibility("")
        assertFalse(general.rankingChannelVisible)
        assertTrue(general.generalPreferencesVisible)

        for (period in listOf("day", "week", "month", "total")) {
            val ranking = copyMangaLatestVisibility(period)
            assertTrue(ranking.rankingChannelVisible)
            assertFalse(ranking.generalPreferencesVisible)
        }
    }

    @Test
    fun `user agent normalization keeps requests non-empty`() {
        assertEquals(DEFAULT_USER_AGENT, normalizeCopyMangaUserAgent(null))
        assertEquals(DEFAULT_USER_AGENT, normalizeCopyMangaUserAgent("   "))
        assertEquals("custom-agent", normalizeCopyMangaUserAgent("  custom-agent  "))
    }

    @Test
    fun `static theme list is ordered and has unique path words`() {
        assertEquals("全部", LATEST_THEME_OPTIONS.first().first)
        assertEquals("", LATEST_THEME_OPTIONS.first().second)
        assertEquals(70, LATEST_THEME_OPTIONS.size)
        assertEquals(
            LATEST_THEME_OPTIONS.size,
            LATEST_THEME_OPTIONS.map { it.second }.toSet().size,
        )
        assertEquals("aiqing", LATEST_THEME_OPTIONS[1].second)
        assertEquals("COLOR", LATEST_THEME_OPTIONS.first { it.first == "彩色" }.second)
        assertEquals("C98", LATEST_THEME_OPTIONS.first { it.first == "C98" }.second)
        assertEquals("zhenren", LATEST_THEME_OPTIONS.last().second)
        assertNotNull(LATEST_THEME_OPTIONS.firstOrNull { it.first == "LoveLive" && it.second == "loveLive" })
    }
}
