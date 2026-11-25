package com.ledger.offline.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledger.offline.domain.model.AccountSortMode
import com.ledger.offline.domain.model.AppLanguage
import com.ledger.offline.domain.model.DarkMode
import com.ledger.offline.domain.model.DedupeMode
import com.ledger.offline.domain.model.IconColorMode
import com.ledger.offline.domain.model.LedgerSettings
import com.ledger.offline.domain.model.ReportMode
import com.ledger.offline.domain.model.ThemeTone
import com.ledger.offline.domain.model.UiDensity
import com.ledger.offline.domain.repo.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 设置项 DataStore 文件名（契约 3.1 固定为 `ledger_settings`）。 */
private val Context.ledgerSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "ledger_settings")

/*
 * 设置键名与 LedgerSettings 字段一一对应（键名 = 字段名），便于人工核对 DataStore 文件内容。
 * 1.1.0 起映射 LedgerSettings 的全部字段；1.2.0 追加 notifyPackages / dedupeMode / pendingDuplicate /
 * darkMode / iconColorMode / iconTone / autoUpdateDelaySeconds；1.2.1 追加 notifyKindFilters /
 * blockWords / fundTrackingEnabled。枚举以 `name` 字符串存储，缺省 / 非法值回落到模型默认值。
 * 旧版本的 screenshotGuard 键不再读取（也没有任何业务消费它），但旧数据保留不删。
 */
private val KEY_SMS_AUTO_SCAN = booleanPreferencesKey("smsAutoScan")
private val KEY_SMS_LOOKBACK_DAYS = intPreferencesKey("smsLookbackDays")
private val KEY_WECHAT_NOTIFY = booleanPreferencesKey("wechatNotify")
private val KEY_ALIPAY_NOTIFY = booleanPreferencesKey("alipayNotify")
private val KEY_NOTIFY_PACKAGES = stringPreferencesKey("notifyPackages")
private val KEY_NOTIFY_KIND_FILTERS = stringPreferencesKey("notifyKindFilters")
private val KEY_BLOCK_WORDS = stringPreferencesKey("blockWords")
private val KEY_REPORT_NOTIFICATIONS = booleanPreferencesKey("reportNotifications")
private val KEY_DAILY_REPORT_ENABLED = booleanPreferencesKey("dailyReportEnabled")
private val KEY_WEEKLY_REPORT_ENABLED = booleanPreferencesKey("weeklyReportEnabled")
private val KEY_MONTHLY_REPORT_ENABLED = booleanPreferencesKey("monthlyReportEnabled")
private val KEY_YEARLY_REPORT_ENABLED = booleanPreferencesKey("yearlyReportEnabled")
private val KEY_REPORT_HOUR = intPreferencesKey("reportHour")
private val KEY_REPORT_MINUTE = intPreferencesKey("reportMinute")
private val KEY_WEEKLY_DAY_OF_WEEK = intPreferencesKey("weeklyDayOfWeek")
private val KEY_MONTHLY_DAY = intPreferencesKey("monthlyDay")
private val KEY_YEARLY_MONTH = intPreferencesKey("yearlyMonth")
private val KEY_YEARLY_DAY = intPreferencesKey("yearlyDay")
private val KEY_REPORT_MODE = stringPreferencesKey("reportMode")
private val KEY_AUTO_CATEGORIZE = booleanPreferencesKey("autoCategorize")
private val KEY_SIMPLE_MODE = booleanPreferencesKey("simpleMode")
private val KEY_TRUST_MODE = booleanPreferencesKey("trustMode")
private val KEY_PRIVACY_MODE = booleanPreferencesKey("privacyMode")
private val KEY_DEDUPE_MODE = stringPreferencesKey("dedupeMode")
private val KEY_PENDING_DUPLICATE = stringPreferencesKey("pendingDuplicate")
private val KEY_FUND_TRACKING_ENABLED = booleanPreferencesKey("fundTrackingEnabled")
private val KEY_LANGUAGE = stringPreferencesKey("language")
private val KEY_THEME_TONE = stringPreferencesKey("themeTone")
private val KEY_DARK_MODE = stringPreferencesKey("darkMode")
private val KEY_ICON_COLOR_MODE = stringPreferencesKey("iconColorMode")
private val KEY_ICON_TONE = stringPreferencesKey("iconTone")
private val KEY_DENSITY = stringPreferencesKey("density")
private val KEY_ACCOUNT_SORT_MODE = stringPreferencesKey("accountSortMode")
private val KEY_HOME_SECTION_ORDER = stringPreferencesKey("homeSectionOrder")
private val KEY_HIDDEN_HOME_SECTIONS = stringPreferencesKey("hiddenHomeSections")
private val KEY_BOTTOM_NAV_ORDER = stringPreferencesKey("bottomNavOrder")
private val KEY_HIDDEN_BOTTOM_NAV = stringPreferencesKey("hiddenBottomNav")
private val KEY_AUTO_UPDATE_DELAY_SECONDS = intPreferencesKey("autoUpdateDelaySeconds")
private val KEY_LAST_SEEN_VERSION = intPreferencesKey("lastSeenVersion")
private val KEY_SEED_VERSION = intPreferencesKey("seedVersion")

/**
 * [SettingsRepository] 的 DataStore 实现。
 *
 * 读取时缺失的键使用 [LedgerSettings] 的默认值（枚举按 `name` 反序列化，无法识别时同样回落默认值）；
 * 写入时在 `edit {}` 内先读旧值、应用 transform，再把全部字段写回，避免漏写。
 */
class DataStoreSettingsRepository(context: Context) : SettingsRepository {

    private val dataStore: DataStore<Preferences> =
        context.applicationContext.ledgerSettingsDataStore

    override val settings: Flow<LedgerSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences -> preferences.toLedgerSettings() }

    override suspend fun current(): LedgerSettings = settings.first()

    override suspend fun update(transform: (LedgerSettings) -> LedgerSettings) {
        dataStore.edit { preferences ->
            val updated = transform(preferences.toLedgerSettings())
            preferences.writeLedgerSettings(updated)
        }
    }
}

/** 把字符串形式的枚举值还原成枚举；为空 / 无法识别时返回 [default]。 */
private fun <T : Enum<T>> decodeEnum(raw: String?, values: Array<T>, default: T): T =
    values.firstOrNull { value -> value.name == raw } ?: default

/** Preferences -> [LedgerSettings]，缺失键回落到模型默认值。字段顺序与 [LedgerSettings] 声明顺序一致。 */
private fun Preferences.toLedgerSettings(): LedgerSettings {
    val defaults = LedgerSettings()
    return LedgerSettings(
        smsAutoScan = this[KEY_SMS_AUTO_SCAN] ?: defaults.smsAutoScan,
        smsLookbackDays = this[KEY_SMS_LOOKBACK_DAYS] ?: defaults.smsLookbackDays,
        wechatNotify = this[KEY_WECHAT_NOTIFY] ?: defaults.wechatNotify,
        alipayNotify = this[KEY_ALIPAY_NOTIFY] ?: defaults.alipayNotify,
        notifyPackages = this[KEY_NOTIFY_PACKAGES] ?: defaults.notifyPackages,
        notifyKindFilters = this[KEY_NOTIFY_KIND_FILTERS] ?: defaults.notifyKindFilters,
        blockWords = this[KEY_BLOCK_WORDS] ?: defaults.blockWords,
        reportNotifications = this[KEY_REPORT_NOTIFICATIONS] ?: defaults.reportNotifications,
        dailyReportEnabled = this[KEY_DAILY_REPORT_ENABLED] ?: defaults.dailyReportEnabled,
        weeklyReportEnabled = this[KEY_WEEKLY_REPORT_ENABLED] ?: defaults.weeklyReportEnabled,
        monthlyReportEnabled = this[KEY_MONTHLY_REPORT_ENABLED] ?: defaults.monthlyReportEnabled,
        yearlyReportEnabled = this[KEY_YEARLY_REPORT_ENABLED] ?: defaults.yearlyReportEnabled,
        reportHour = this[KEY_REPORT_HOUR] ?: defaults.reportHour,
        reportMinute = this[KEY_REPORT_MINUTE] ?: defaults.reportMinute,
        weeklyDayOfWeek = this[KEY_WEEKLY_DAY_OF_WEEK] ?: defaults.weeklyDayOfWeek,
        monthlyDay = this[KEY_MONTHLY_DAY] ?: defaults.monthlyDay,
        yearlyMonth = this[KEY_YEARLY_MONTH] ?: defaults.yearlyMonth,
        yearlyDay = this[KEY_YEARLY_DAY] ?: defaults.yearlyDay,
        reportMode = decodeEnum(this[KEY_REPORT_MODE], ReportMode.values(), defaults.reportMode),
        autoCategorize = this[KEY_AUTO_CATEGORIZE] ?: defaults.autoCategorize,
        simpleMode = this[KEY_SIMPLE_MODE] ?: defaults.simpleMode,
        trustMode = this[KEY_TRUST_MODE] ?: defaults.trustMode,
        privacyMode = this[KEY_PRIVACY_MODE] ?: defaults.privacyMode,
        dedupeMode = decodeEnum(this[KEY_DEDUPE_MODE], DedupeMode.values(), defaults.dedupeMode),
        pendingDuplicate = this[KEY_PENDING_DUPLICATE] ?: defaults.pendingDuplicate,
        fundTrackingEnabled = this[KEY_FUND_TRACKING_ENABLED] ?: defaults.fundTrackingEnabled,
        language = decodeEnum(this[KEY_LANGUAGE], AppLanguage.values(), defaults.language),
        themeTone = decodeEnum(this[KEY_THEME_TONE], ThemeTone.values(), defaults.themeTone),
        darkMode = decodeEnum(this[KEY_DARK_MODE], DarkMode.values(), defaults.darkMode),
        iconColorMode = decodeEnum(this[KEY_ICON_COLOR_MODE], IconColorMode.values(), defaults.iconColorMode),
        iconTone = decodeEnum(this[KEY_ICON_TONE], ThemeTone.values(), defaults.iconTone),
        density = decodeEnum(this[KEY_DENSITY], UiDensity.values(), defaults.density),
        accountSortMode = decodeEnum(this[KEY_ACCOUNT_SORT_MODE], AccountSortMode.values(), defaults.accountSortMode),
        homeSectionOrder = this[KEY_HOME_SECTION_ORDER] ?: defaults.homeSectionOrder,
        hiddenHomeSections = this[KEY_HIDDEN_HOME_SECTIONS] ?: defaults.hiddenHomeSections,
        bottomNavOrder = this[KEY_BOTTOM_NAV_ORDER] ?: defaults.bottomNavOrder,
        hiddenBottomNav = this[KEY_HIDDEN_BOTTOM_NAV] ?: defaults.hiddenBottomNav,
        autoUpdateDelaySeconds = this[KEY_AUTO_UPDATE_DELAY_SECONDS] ?: defaults.autoUpdateDelaySeconds,
        lastSeenVersion = this[KEY_LAST_SEEN_VERSION] ?: defaults.lastSeenVersion,
        seedVersion = this[KEY_SEED_VERSION] ?: defaults.seedVersion
    )
}

/** [LedgerSettings] -> Preferences，全部字段写回（枚举写 `name`）。顺序与读取端一致，便于逐条核对。 */
private fun MutablePreferences.writeLedgerSettings(settings: LedgerSettings) {
    this[KEY_SMS_AUTO_SCAN] = settings.smsAutoScan
    this[KEY_SMS_LOOKBACK_DAYS] = settings.smsLookbackDays
    this[KEY_WECHAT_NOTIFY] = settings.wechatNotify
    this[KEY_ALIPAY_NOTIFY] = settings.alipayNotify
    this[KEY_NOTIFY_PACKAGES] = settings.notifyPackages
    this[KEY_NOTIFY_KIND_FILTERS] = settings.notifyKindFilters
    this[KEY_BLOCK_WORDS] = settings.blockWords
    this[KEY_REPORT_NOTIFICATIONS] = settings.reportNotifications
    this[KEY_DAILY_REPORT_ENABLED] = settings.dailyReportEnabled
    this[KEY_WEEKLY_REPORT_ENABLED] = settings.weeklyReportEnabled
    this[KEY_MONTHLY_REPORT_ENABLED] = settings.monthlyReportEnabled
    this[KEY_YEARLY_REPORT_ENABLED] = settings.yearlyReportEnabled
    this[KEY_REPORT_HOUR] = settings.reportHour
    this[KEY_REPORT_MINUTE] = settings.reportMinute
    this[KEY_WEEKLY_DAY_OF_WEEK] = settings.weeklyDayOfWeek
    this[KEY_MONTHLY_DAY] = settings.monthlyDay
    this[KEY_YEARLY_MONTH] = settings.yearlyMonth
    this[KEY_YEARLY_DAY] = settings.yearlyDay
    this[KEY_REPORT_MODE] = settings.reportMode.name
    this[KEY_AUTO_CATEGORIZE] = settings.autoCategorize
    this[KEY_SIMPLE_MODE] = settings.simpleMode
    this[KEY_TRUST_MODE] = settings.trustMode
    this[KEY_PRIVACY_MODE] = settings.privacyMode
    this[KEY_DEDUPE_MODE] = settings.dedupeMode.name
    this[KEY_PENDING_DUPLICATE] = settings.pendingDuplicate
    this[KEY_FUND_TRACKING_ENABLED] = settings.fundTrackingEnabled
    this[KEY_LANGUAGE] = settings.language.name
    this[KEY_THEME_TONE] = settings.themeTone.name
    this[KEY_DARK_MODE] = settings.darkMode.name
    this[KEY_ICON_COLOR_MODE] = settings.iconColorMode.name
    this[KEY_ICON_TONE] = settings.iconTone.name
    this[KEY_DENSITY] = settings.density.name
    this[KEY_ACCOUNT_SORT_MODE] = settings.accountSortMode.name
    this[KEY_HOME_SECTION_ORDER] = settings.homeSectionOrder
    this[KEY_HIDDEN_HOME_SECTIONS] = settings.hiddenHomeSections
    this[KEY_BOTTOM_NAV_ORDER] = settings.bottomNavOrder
    this[KEY_HIDDEN_BOTTOM_NAV] = settings.hiddenBottomNav
    this[KEY_AUTO_UPDATE_DELAY_SECONDS] = settings.autoUpdateDelaySeconds
    this[KEY_LAST_SEEN_VERSION] = settings.lastSeenVersion
    this[KEY_SEED_VERSION] = settings.seedVersion
}
