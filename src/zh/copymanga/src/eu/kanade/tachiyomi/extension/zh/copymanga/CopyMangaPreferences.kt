package eu.kanade.tachiyomi.extension.zh.copymanga

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString

internal enum class CopyMangaDomain(
    val apiBaseUrl: String,
    val webBaseUrl: String,
) {
    MANGA_COPY(
        apiBaseUrl = "https://api.mangacopy.com",
        webBaseUrl = "https://www.mangacopy.com",
    ),
    COPY_3000(
        apiBaseUrl = "https://api.copy3000.com",
        webBaseUrl = "https://www.copy3000.com",
    ),
    COPY_4000(
        apiBaseUrl = "https://api.copy4000.com",
        webBaseUrl = "https://www.copy4000.com",
    ),
    ;

    companion object {
        fun fromPreference(value: String?): CopyMangaDomain = entries.firstOrNull {
            it.apiBaseUrl == value
        } ?: MANGA_COPY
    }
}

internal enum class CopyMangaLanguage(val preferenceValue: String) {
    TRADITIONAL("traditional"),
    SIMPLIFIED("simplified"),
    ;

    companion object {
        fun fromPreference(value: String?): CopyMangaLanguage = entries.firstOrNull {
            it.preferenceValue == value
        } ?: TRADITIONAL
    }
}

internal fun SharedPreferences.copyMangaDomain(): CopyMangaDomain = CopyMangaDomain.fromPreference(
    getString(PREF_DOMAIN, CopyMangaDomain.MANGA_COPY.apiBaseUrl),
)

internal fun SharedPreferences.copyMangaLanguage(): CopyMangaLanguage = CopyMangaLanguage.fromPreference(
    getString(PREF_LANGUAGE, CopyMangaLanguage.TRADITIONAL.preferenceValue),
)

internal fun SharedPreferences.copyMangaUserAgent(): String = normalizeCopyMangaUserAgent(
    getString(PREF_USER_AGENT, DEFAULT_USER_AGENT),
)

internal fun normalizeCopyMangaUserAgent(value: String?): String = value
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: DEFAULT_USER_AGENT

internal fun SharedPreferences.copyMangaLatestOptions(): CopyMangaBrowseOptions {
    val (region, status) = copyMangaLatestFilters()
    return copyMangaLatestOptions(
        ranking = getString(PREF_LATEST_RANKING, null),
        rankingChannel = getString(PREF_LATEST_RANKING_CHANNEL, null),
        sort = getString(PREF_LATEST_SORT, null),
        direction = getString(PREF_LATEST_SORT_DIRECTION, null),
        region = region,
        status = status,
        theme = getString(PREF_LATEST_THEME, null),
    )
}

internal fun SharedPreferences.copyMangaLatestFilters(): Pair<String, String> {
    val (storedRegion, storedStatus) = getString(PREF_LATEST_TOP, null)
        ?.let(::migrateCopyMangaTopToFilters)
        ?: (getString(PREF_LATEST_REGION, null) to getString(PREF_LATEST_STATUS, null))
    val region = storedRegion.allowedValue(REGION_OPTIONS, "")
    val status = storedStatus.allowedValue(STATUS_OPTIONS, "")

    val editor = edit()
    var changed = false
    if (contains(PREF_LATEST_TOP)) {
        editor.remove(PREF_LATEST_TOP)
        changed = true
    }
    if (getString(PREF_LATEST_REGION, null) != region) {
        editor.putString(PREF_LATEST_REGION, region)
        changed = true
    }
    if (getString(PREF_LATEST_STATUS, null) != status) {
        editor.putString(PREF_LATEST_STATUS, status)
        changed = true
    }
    if (changed) editor.apply()

    return region to status
}

internal fun migrateCopyMangaLegacyTop(region: String?, status: String?): String = when {
    status.isNullOrEmpty() -> LEGACY_REGION_TO_TOP[region].orEmpty()
    region.isNullOrEmpty() && status == "1" -> TOP_FINISH
    else -> ""
}

internal fun copyMangaLatestOptions(
    ranking: String?,
    rankingChannel: String?,
    sort: String?,
    direction: String?,
    region: String?,
    status: String?,
    theme: String?,
): CopyMangaBrowseOptions {
    val normalizedRanking = ranking.allowedValue(LATEST_MODE_OPTIONS, "")
    val normalizedRankingChannel = rankingChannel.allowedValue(RANKING_CHANNELS, DEFAULT_RANKING_CHANNEL)
    val sortOption = copyMangaLatestSortOption(sort, direction)

    return CopyMangaBrowseOptions(
        ranking = normalizedRanking,
        rankingChannel = normalizedRankingChannel,
        ordering = sortOption.ordering,
        region = region.allowedValue(REGION_OPTIONS, ""),
        status = status.allowedValue(STATUS_OPTIONS, ""),
        theme = theme.allowedValue(LATEST_THEME_OPTIONS, ""),
    )
}

internal fun SharedPreferences.copyMangaCachedThemes(): List<CopyMangaTag> = getString(PREF_THEME_CACHE, null)
    ?.let { runCatching { it.parseAs<List<CopyMangaTag>>() }.getOrNull() }
    .orEmpty()

internal fun SharedPreferences.saveCopyMangaCachedThemes(themes: List<CopyMangaTag>) {
    if (themes.isEmpty()) return
    val encoded = themes.distinctBy { it.pathWord }.toJsonString()
    if (getString(PREF_THEME_CACHE, null) != encoded) {
        edit().putString(PREF_THEME_CACHE, encoded).apply()
    }
}

internal fun PreferenceScreen.addCopyMangaPreferences(
    preferences: SharedPreferences,
    transformText: (String) -> String,
) {
    val (latestRegion, latestStatus) = preferences.copyMangaLatestFilters()

    addListPreference(
        key = PREF_DOMAIN,
        title = "域名",
        options = CopyMangaDomain.entries.map {
            it.apiBaseUrl.removePrefix("https://") to it.apiBaseUrl
        }.toTypedArray(),
        defaultValue = CopyMangaDomain.MANGA_COPY.apiBaseUrl,
    )

    addListPreference(
        key = PREF_LANGUAGE,
        title = "繁簡設定",
        options = CopyMangaLanguage.entries.map { language ->
            (if (language == CopyMangaLanguage.TRADITIONAL) "繁體" else "簡體") to language.preferenceValue
        }.toTypedArray(),
        defaultValue = CopyMangaLanguage.TRADITIONAL.preferenceValue,
    )

    EditTextPreference(context).apply {
        key = PREF_USER_AGENT
        title = "User-Agent"
        setDefaultValue(DEFAULT_USER_AGENT)
        summary = preferences.copyMangaUserAgent()
        setOnPreferenceChangeListener { _, newValue ->
            val value = (newValue as? String)?.trim().orEmpty()
            if (value.isEmpty()) {
                false
            } else {
                summary = value
                true
            }
        }
    }.also(::addPreference)

    val latestModePreference = addListPreference(
        key = PREF_LATEST_RANKING,
        title = "最新模式",
        options = LATEST_MODE_OPTIONS,
        defaultValue = "",
    )
    val rankingChannelPreference = addListPreference(
        key = PREF_LATEST_RANKING_CHANNEL,
        title = "榜單頻道",
        options = RANKING_CHANNELS,
        defaultValue = DEFAULT_RANKING_CHANNEL,
    )
    // Keep the UI key separate because its four values map to two legacy keys.
    val sortPreference = ListPreference(context).apply {
        key = PREF_LATEST_SORT_UI
        title = "排序"
        entries = LATEST_SORT_OPTIONS.map { it.label }.toTypedArray()
        entryValues = LATEST_SORT_OPTIONS.map { it.value }.toTypedArray()
        summary = "%s"
        setOnPreferenceChangeListener { _, newValue ->
            val option = LATEST_SORT_OPTIONS.firstOrNull { it.value == newValue }
                ?: return@setOnPreferenceChangeListener false
            preferences.edit()
                .putString(PREF_LATEST_SORT, option.sort)
                .putString(PREF_LATEST_SORT_DIRECTION, option.direction)
                .apply()
            true
        }
    }.also(::addPreference)
    sortPreference.value = copyMangaLatestSortOption(
        sort = preferences.getString(PREF_LATEST_SORT, null),
        direction = preferences.getString(PREF_LATEST_SORT_DIRECTION, null),
    ).value
    val regionPreference = addListPreference(
        key = PREF_LATEST_REGION,
        title = "地區",
        options = REGION_OPTIONS,
        defaultValue = "",
    )
    regionPreference.value = latestRegion
    val statusPreference = addListPreference(
        key = PREF_LATEST_STATUS,
        title = "狀態",
        options = STATUS_OPTIONS,
        defaultValue = "",
    )
    statusPreference.value = latestStatus
    val themePreference = addListPreference(
        key = PREF_LATEST_THEME,
        title = "題材",
        options = LATEST_THEME_OPTIONS.map { (name, value) -> transformText(name) to value }.toTypedArray(),
        defaultValue = "",
    )

    fun updateLatestVisibility(ranking: String) {
        val visibility = copyMangaLatestVisibility(ranking)
        rankingChannelPreference.setVisible(visibility.rankingChannelVisible)
        sortPreference.setVisible(visibility.generalPreferencesVisible)
        regionPreference.setVisible(visibility.generalPreferencesVisible)
        statusPreference.setVisible(visibility.generalPreferencesVisible)
        themePreference.setVisible(visibility.generalPreferencesVisible)
    }

    latestModePreference.setOnPreferenceChangeListener { _, newValue ->
        updateLatestVisibility((newValue as? String).orEmpty())
        true
    }
    updateLatestVisibility(preferences.copyMangaLatestOptions().ranking)
}

private fun PreferenceScreen.addListPreference(
    key: String,
    title: String,
    options: Array<Pair<String, String>>,
    defaultValue: String,
): ListPreference = ListPreference(context).apply {
    this.key = key
    this.title = title
    entries = options.map { it.first }.toTypedArray()
    entryValues = options.map { it.second }.toTypedArray()
    setDefaultValue(defaultValue)
    summary = "%s"
}.also(::addPreference)

private fun String?.allowedValue(options: Array<Pair<String, String>>, fallback: String): String = orEmpty()
    .takeIf { value -> options.any { it.second == value } }
    ?: fallback

private val LEGACY_REGION_TO_TOP = mapOf(
    "0" to TOP_JAPAN,
    "1" to TOP_KOREA,
    "2" to TOP_WEST,
)

internal fun migrateCopyMangaTopToFilters(top: String?): Pair<String, String> = when (top) {
    TOP_JAPAN -> "0" to ""
    TOP_KOREA -> "1" to ""
    TOP_WEST -> "2" to ""
    TOP_FINISH -> "" to "1"
    else -> "" to ""
}

internal const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"

private const val PREF_DOMAIN = "domain"
private const val PREF_LANGUAGE = "display_language"
private const val PREF_USER_AGENT = "user_agent"
internal const val PREF_LATEST_RANKING = "latest_ranking"
internal const val PREF_LATEST_RANKING_CHANNEL = "latest_ranking_channel"
private const val PREF_LATEST_SORT_UI = "latest_sort_ui"
internal const val PREF_LATEST_SORT = "latest_sort"
internal const val PREF_LATEST_SORT_DIRECTION = "latest_sort_direction"
internal const val PREF_LATEST_REGION = "latest_region"
internal const val PREF_LATEST_STATUS = "latest_status"
internal const val PREF_LATEST_TOP = "latest_top"
internal const val PREF_LATEST_THEME = "latest_theme"
private const val PREF_THEME_CACHE = "theme_cache"
internal const val SORT_DESCENDING = "descending"
internal const val SORT_ASCENDING = "ascending"
