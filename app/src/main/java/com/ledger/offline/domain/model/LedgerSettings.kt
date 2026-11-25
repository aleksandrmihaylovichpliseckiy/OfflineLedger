package com.ledger.offline.domain.model

/**
 * 应用设置（DataStore 持久化）。
 *
 * 说明：
 * - 语言 / 色调 / 密度 / 布局等偏好都由 [com.ledger.offline.ui.LedgerApp] 统一消费；
 * - 报表时间：日报用 [reportHour]:[reportMinute]；周报 / 月报 / 年报只自定义日期，时间沿用日报时间；
 * - [dedupeMode] 控制银行卡短信与支付通知的重复记账处理（自动托管 / 每次询问 / 关闭）；
 * - [notifyPackages] 是需要解析支付通知的应用包名（逗号分隔），默认微信 + 支付宝；
 * - [pendingDuplicate] 存“每次询问”模式下的待处理重复记录，格式为 `keepId|dropId;...` 纯文本；
 * - [iconColorMode] / [iconTone] 控制系统启动图标配色（跟随主题或手动指定）；
 * - [autoUpdateDelaySeconds] 是“信任模式自动更新”读取屏幕前的等待秒数，默认 5 秒；
 * - [seedVersion] 用于数据种子迁移；[lastSeenVersion] 记录已读更新日志版本号。
 */
data class LedgerSettings(
    val smsAutoScan: Boolean = true,
    val smsLookbackDays: Int = 30,
    val wechatNotify: Boolean = true,
    val alipayNotify: Boolean = true,
    val notifyPackages: String = "com.tencent.mm,com.eg.android.AlipayGphone",
    /** 每个应用允许的通知类型，格式 `pkg=PAYMENT,INCOME;...`；空串表示全部允许。 */
    val notifyKindFilters: String = "",
    /** 屏蔽词清单，换行 / 逗号分隔；命中这些词的短信或通知直接不统计。 */
    val blockWords: String = "",
    val reportNotifications: Boolean = true,
    val dailyReportEnabled: Boolean = true,
    val weeklyReportEnabled: Boolean = true,
    val monthlyReportEnabled: Boolean = true,
    val yearlyReportEnabled: Boolean = true,
    val reportHour: Int = 21,
    val reportMinute: Int = 0,
    val weeklyDayOfWeek: Int = 1,
    val monthlyDay: Int = 1,
    val yearlyMonth: Int = 1,
    val yearlyDay: Int = 1,
    val reportMode: ReportMode = ReportMode.STANDARD,
    val autoCategorize: Boolean = true,
    val simpleMode: Boolean = false,
    val trustMode: Boolean = false,
    val privacyMode: Boolean = false,
    val dedupeMode: DedupeMode = DedupeMode.ASK,
    val pendingDuplicate: String = "",
    /** 基金申购 / 赎回不计入收支，而是调整“基金余额”账户。 */
    val fundTrackingEnabled: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeTone: ThemeTone = ThemeTone.GREEN,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val iconColorMode: IconColorMode = IconColorMode.FOLLOW_THEME,
    val iconTone: ThemeTone = ThemeTone.GREEN,
    val density: UiDensity = UiDensity.STANDARD,
    val accountSortMode: AccountSortMode = AccountSortMode.CUSTOM,
    val homeSectionOrder: String = "ACCOUNTS,TREND,RECENT,QUICK",
    val hiddenHomeSections: String = "",
    val bottomNavOrder: String = "HOME,TRANSACTIONS,REPORTS,SETTINGS",
    val hiddenBottomNav: String = "",
    val autoUpdateDelaySeconds: Int = 5,
    val lastSeenVersion: Int = 0,
    val seedVersion: Int = 0
)
