package eu.kanade.tachiyomi.extension.zh.copymanga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal data class CopyMangaRequest(
    val path: String,
    val parameters: Map<String, String>,
    val transport: CopyMangaRequestTransport = CopyMangaRequestTransport.API,
)

internal enum class CopyMangaRequestTransport {
    API,
    HTML,
}

internal data class CopyMangaBrowseOptions(
    val ranking: String = "",
    val rankingChannel: String = DEFAULT_RANKING_CHANNEL,
    val ordering: String = DEFAULT_ORDERING,
    val region: String = "",
    val status: String = "",
    val theme: String = "",
)

internal abstract class CopyMangaSelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
    defaultIndex: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), defaultIndex) {
    val selectedValue: String
        get() = options[state].second
}

internal class RankingPeriodFilter :
    CopyMangaSelectFilter(
        name = "排行榜",
        options = RANKING_PERIODS,
    )

internal class RankingChannelFilter :
    CopyMangaSelectFilter(
        name = "榜單頻道",
        options = RANKING_CHANNELS,
    )

internal class MangaSortFilter :
    Filter.Sort(
        name = "排序",
        values = SORT_OPTIONS.map { it.first }.toTypedArray(),
        state = Selection(index = 0, ascending = false),
    ) {
    val ordering: String
        get() {
            val selection = state ?: Selection(index = 0, ascending = false)
            val value = SORT_OPTIONS[selection.index].second
            return if (selection.ascending) value else "-$value"
        }
}

internal class RegionFilter :
    CopyMangaSelectFilter(
        name = "地區",
        options = REGION_OPTIONS,
    )

internal class StatusFilter :
    CopyMangaSelectFilter(
        name = "狀態",
        options = STATUS_OPTIONS,
    )

internal class ThemeFilter(
    themes: List<CopyMangaTag>,
    transformText: (String) -> String,
) : CopyMangaSelectFilter(
    name = "題材",
    options = arrayOf("全部" to "") + themes
        .distinctBy { it.pathWord }
        .map { transformText(it.name) to it.pathWord }
        .toTypedArray(),
)

internal fun buildCopyMangaFilterList(
    themes: List<CopyMangaTag>,
    transformText: (String) -> String,
): FilterList = FilterList(
    Filter.Header("排行榜啟用時，榜單頻道生效，並忽略下方一般篩選"),
    RankingPeriodFilter(),
    RankingChannelFilter(),
    Filter.Separator(),
    MangaSortFilter(),
    RegionFilter(),
    StatusFilter(),
    ThemeFilter(themes, transformText),
)

internal fun buildCopyMangaSearchRequest(
    query: String,
    filters: FilterList,
    limit: Int,
    offset: Int,
): CopyMangaRequest {
    if (query.isNotBlank()) {
        return CopyMangaRequest(
            path = "search/comic",
            parameters = linkedMapOf(
                "q" to query,
                "q_type" to "",
                "_update" to "true",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )
    }

    return buildCopyMangaBrowseRequest(
        options = CopyMangaBrowseOptions(
            ranking = filters.filterIsInstance<RankingPeriodFilter>().firstOrNull()?.selectedValue.orEmpty(),
            rankingChannel = filters.filterIsInstance<RankingChannelFilter>()
                .firstOrNull()
                ?.selectedValue
                .orEmpty()
                .ifEmpty { DEFAULT_RANKING_CHANNEL },
            ordering = filters.filterIsInstance<MangaSortFilter>().firstOrNull()?.ordering ?: DEFAULT_ORDERING,
            region = filters.filterIsInstance<RegionFilter>().firstOrNull()?.selectedValue.orEmpty(),
            status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue.orEmpty(),
            theme = filters.filterIsInstance<ThemeFilter>().firstOrNull()?.selectedValue.orEmpty(),
        ),
        limit = limit,
        offset = offset,
    )
}

internal fun buildCopyMangaBrowseRequest(
    options: CopyMangaBrowseOptions,
    limit: Int,
    offset: Int,
): CopyMangaRequest {
    if (options.ranking.isNotEmpty()) {
        return CopyMangaRequest(
            path = "ranks",
            parameters = linkedMapOf(
                "date_type" to options.ranking,
                "audience_type" to options.rankingChannel.ifEmpty { DEFAULT_RANKING_CHANNEL },
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )
    }

    val parameters = linkedMapOf(
        "ordering" to options.ordering,
        "_update" to "true",
        "limit" to limit.toString(),
        "offset" to offset.toString(),
    )

    val apiTop = copyMangaApiTop(options.region, options.status)
    val hasBrowseFilters = options.region.isNotEmpty() ||
        options.status.isNotEmpty() ||
        options.theme.isNotEmpty()
    val useHtml = apiTop == null && hasBrowseFilters
    if (useHtml) {
        // The public website owns the general comics filter semantics. Keep
        // the selected region, status, and theme in the HTML query string.
        options.region.takeIf { it.isNotEmpty() }?.let { parameters["region"] = it }
        options.status.takeIf { it.isNotEmpty() }?.let { parameters["status"] = it }
    } else {
        apiTop?.let { parameters["top"] = it }
    }
    options.theme.takeIf { it.isNotEmpty() }?.let { parameters["theme"] = it }

    return CopyMangaRequest(
        path = "comics",
        parameters = parameters,
        transport = if (useHtml) CopyMangaRequestTransport.HTML else CopyMangaRequestTransport.API,
    )
}

internal const val DEFAULT_RANKING_CHANNEL = "male"
internal const val DEFAULT_SORT = "datetime_updated"
internal const val DEFAULT_ORDERING = "-$DEFAULT_SORT"
internal const val TOP_JAPAN = "japan"
internal const val TOP_KOREA = "korea"
internal const val TOP_WEST = "west"
internal const val TOP_FINISH = "finish"

internal val REGION_OPTIONS = arrayOf(
    "全部" to "",
    "日漫" to "0",
    "韓漫" to "1",
    "美漫" to "2",
)

internal val STATUS_OPTIONS = arrayOf(
    "全部" to "",
    "連載中" to "0",
    "已完結" to "1",
    "短篇" to "2",
)

internal val RANKING_PERIODS = arrayOf(
    "不使用" to "",
    "日榜（上升最快）" to "day",
    "周榜（最近 7 天）" to "week",
    "月榜（最近 30 天）" to "month",
    "總榜單" to "total",
)

internal val RANKING_CHANNELS = arrayOf(
    "漫畫排行榜（男頻）" to "male",
    "漫畫排行榜（女頻）" to "female",
)

internal val SORT_OPTIONS = arrayOf(
    "更新時間" to "datetime_updated",
    "熱門" to "popular",
)

internal fun copyMangaApiTop(region: String, status: String): String? = TOP_FINISH.takeIf { region.isEmpty() && status == "1" }
