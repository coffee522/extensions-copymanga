package eu.kanade.tachiyomi.extension.zh.copymanga

internal val LATEST_MODE_OPTIONS = arrayOf(
    "一般最新" to "",
    "日榜（上升最快）" to "day",
    "週榜（最近 7 天）" to "week",
    "月榜（最近 30 天）" to "month",
    "總榜" to "total",
)

internal data class CopyMangaLatestSortOption(
    val label: String,
    val value: String,
    val sort: String,
    val direction: String,
) {
    val ordering: String
        get() = if (direction == SORT_ASCENDING) sort else "-$sort"
}

internal val LATEST_SORT_OPTIONS = arrayOf(
    CopyMangaLatestSortOption("更新時間 ↓", "datetime_updated_descending", "datetime_updated", SORT_DESCENDING),
    CopyMangaLatestSortOption("更新時間 ↑", "datetime_updated_ascending", "datetime_updated", SORT_ASCENDING),
    CopyMangaLatestSortOption("熱門 ↓", "popular_descending", "popular", SORT_DESCENDING),
    CopyMangaLatestSortOption("熱門 ↑", "popular_ascending", "popular", SORT_ASCENDING),
)

/** Ordered response from CopyManga theme/comic/count?limit=500, fetched on 2026-09-08. */
internal val LATEST_THEME_OPTIONS = arrayOf(
    "全部" to "",
    "愛情" to "aiqing",
    "歡樂向" to "huanlexiang",
    "冒險" to "maoxian",
    "奇幻" to "qihuan",
    "百合" to "baihe",
    "校园" to "xiaoyuan",
    "科幻" to "kehuan",
    "東方" to "dongfang",
    "耽美" to "danmei",
    "生活" to "shenghuo",
    "格鬥" to "gedou",
    "轻小说" to "qingxiaoshuo",
    "其他" to "qita",
    "悬疑" to "xuanyi",
    "TL" to "teenslove",
    "萌系" to "mengxi",
    "神鬼" to "shengui",
    "职场" to "zhichang",
    "治愈" to "zhiyu",
    "节操" to "jiecao",
    "四格" to "sige",
    "長條" to "changtiao",
    "舰娘" to "jianniang",
    "搞笑" to "gaoxiao",
    "竞技" to "jingji",
    "伪娘" to "weiniang",
    "魔幻" to "mohuan",
    "热血" to "rexue",
    "性转换" to "xingzhuanhuan",
    "美食" to "meishi",
    "励志" to "lizhi",
    "彩色" to "COLOR",
    "後宮" to "hougong",
    "侦探" to "zhentan",
    "惊悚" to "jingsong",
    "AA" to "aa",
    "音乐舞蹈" to "yinyuewudao",
    "异世界" to "yishijie",
    "战争" to "zhanzheng",
    "历史" to "lishi",
    "机战" to "jizhan",
    "都市" to "dushi",
    "穿越" to "chuanyue",
    "C102" to "comiket102",
    "重生" to "chongsheng",
    "恐怖" to "kongbu",
    "C103" to "comiket103",
    "生存" to "shengcun",
    "C100" to "comiket100",
    "C104" to "comiket104",
    "C101" to "comiket101",
    "C99" to "comiket99",
    "C97" to "comiket97",
    "武侠" to "wuxia",
    "宅系" to "zhaixi",
    "C96" to "comiket96",
    "C105" to "comiket105",
    "C98" to "C98",
    "C95" to "comiket95",
    "转生" to "zhuansheng",
    "FATE" to "fate",
    "無修正" to "Uncensored",
    "仙侠" to "xianxia",
    "LoveLive" to "loveLive",
    "雜誌附贈寫真集" to "zazhifuzengxiezhenji",
    "玄幻" to "xuanhuan",
    "異能" to "yineng",
    "遊戲" to "youxi",
    "真人" to "zhenren",
)

internal data class CopyMangaLatestVisibility(
    val rankingChannelVisible: Boolean,
    val generalPreferencesVisible: Boolean,
)

internal fun copyMangaLatestVisibility(ranking: String): CopyMangaLatestVisibility {
    val rankingEnabled = ranking.isNotEmpty()
    return CopyMangaLatestVisibility(
        rankingChannelVisible = rankingEnabled,
        generalPreferencesVisible = !rankingEnabled,
    )
}

internal fun copyMangaLatestSortOption(
    sort: String?,
    direction: String?,
): CopyMangaLatestSortOption {
    val normalizedSort = sort.orEmpty()
        .takeIf { value -> SORT_OPTIONS.any { it.second == value } }
        ?: DEFAULT_SORT
    val normalizedDirection = if (direction == SORT_ASCENDING) SORT_ASCENDING else SORT_DESCENDING
    return LATEST_SORT_OPTIONS.first { it.sort == normalizedSort && it.direction == normalizedDirection }
}
