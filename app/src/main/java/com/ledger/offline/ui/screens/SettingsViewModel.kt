package com.ledger.offline.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledger.offline.ax.AutoUpdateController
import com.ledger.offline.domain.BalanceCorrectionResult
import com.ledger.offline.domain.BalanceCorrectionService
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountSortMode
import com.ledger.offline.domain.model.AppLanguage
import com.ledger.offline.domain.model.BottomTab
import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.DarkMode
import com.ledger.offline.domain.model.DedupeMode
import com.ledger.offline.domain.model.HomeSection
import com.ledger.offline.domain.model.IconColorMode
import com.ledger.offline.domain.model.LedgerSettings
import com.ledger.offline.domain.model.NotificationKind
import com.ledger.offline.domain.model.ReportMode
import com.ledger.offline.domain.model.SmsSource
import com.ledger.offline.domain.model.ThemeTone
import com.ledger.offline.domain.model.UiDensity
import com.ledger.offline.i18n.I18n
import com.ledger.offline.report.ReportScheduler
import com.ledger.offline.util.FilterPrefs
import com.ledger.offline.util.LayoutPrefs
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页用到的权限状态快照。
 *
 * [accessibilityEnabled] 对应“信任模式”依赖的无障碍服务：只有服务在系统设置里被启用时，
 * 首页的自动更新按钮才能读取微信 / 支付宝页面。
 */
data class PermissionState(
    val smsGranted: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val notificationPermissionGranted: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val accessibilityEnabled: Boolean = false
)

/**
 * 账户删除前的检查结果，由 [SettingsViewModel.requestDeleteAccount] 计算后交给设置页弹窗。
 *
 * [txCount] 为该账户的流水笔数：大于 0 时禁止删除（避免账目残缺），等于 0 时弹确认框。
 */
data class AccountDeleteState(
    val account: Account,
    val txCount: Int
) {
    /** 没有任何流水的账户才允许删除。 */
    val canDelete: Boolean
        get() = txCount == 0
}

/**
 * “需要解析支付通知的应用”列表项：已安装启动器应用的包名与显示名。
 *
 * 勾选结果以 CSV 写进 `settings.notifyPackages`，通知监听只解析这些包名的支付通知。
 */
data class NotifyAppOption(
    val packageName: String,
    val label: String
)

/**
 * 设置页：权限检测、使用场景、外观与语言、页面布局、报表日期、短信来源 / 分类 / 账户管理。
 *
 * 报表相关开关、日期或模式变化后会重新排期 [ReportScheduler]；
 * 账户排序与删除会直接写回数据层，主页立即生效。
 * v1.2.3 起账户管理里还有“余额修正”，由 [correctBalances] 调用 domain 层的
 * [BalanceCorrectionService]，不改变账户的新增 / 编辑 / 删除 / 排序逻辑。
 */
class SettingsViewModel(
    private val context: Context,
    private val repos: LedgerRepos
) : ViewModel() {

    val settings: StateFlow<LedgerSettings> = repos.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LedgerSettings())

    val smsSources: StateFlow<List<SmsSource>> = repos.smsSources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val categories: StateFlow<List<Category>> = repos.categories.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val accounts: StateFlow<List<Account>> = repos.accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _accountDeleteState = MutableStateFlow<AccountDeleteState?>(null)

    /** 删除账户前的检查结果；非空时设置页展示“不可删除”或“确认删除”弹窗。 */
    val accountDeleteState: StateFlow<AccountDeleteState?> = _accountDeleteState.asStateFlow()

    private val _correctingBalances = MutableStateFlow(false)

    /** 余额修正是否执行中：按钮据此置灰，避免重复触发。 */
    val correctingBalances: StateFlow<Boolean> = _correctingBalances.asStateFlow()

    private val _lastBalanceCorrection = MutableStateFlow<BalanceCorrectionResult?>(null)

    /** 最近一次**成功**修正的结果；null 表示本次进程还没成功执行过（设置页不显示结果行）。 */
    val lastBalanceCorrection: StateFlow<BalanceCorrectionResult?> = _lastBalanceCorrection.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _notifyApps = MutableStateFlow<List<NotifyAppOption>>(emptyList())

    /** 已安装的启动器应用（打开“需要解析支付通知的应用”时读取，排除本应用）。 */
    val notifyApps: StateFlow<List<NotifyAppOption>> = _notifyApps.asStateFlow()

    init {
        refreshPermissions()
    }

    /**
     * 重新检测短信权限、通知使用权、Android 13+ 通知权限、系统通知总开关与无障碍服务状态。
     * 从系统设置页返回时（ON_RESUME）也会调用，保证信任模式的状态显示是最新的。
     */
    fun refreshPermissions() {
        val smsGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val listenerEnabled = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)
        val notificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notificationsEnabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)
        // 无障碍服务是否启用由 ax 模块检测，这里包一层兜底，避免系统接口异常导致设置页崩溃。
        val accessibilityEnabled = runCatching {
            AutoUpdateController.isServiceEnabled(context)
        }.getOrDefault(false)
        _permissions.value = PermissionState(
            smsGranted = smsGranted,
            notificationListenerEnabled = listenerEnabled,
            notificationPermissionGranted = notificationPermissionGranted,
            notificationsEnabled = notificationsEnabled,
            accessibilityEnabled = accessibilityEnabled
        )
    }

    // ------------------------------------------------------------------ 使用场景

    /** 简易模式：只保留收支汇总、账户与快捷操作。 */
    fun setSimpleMode(enabled: Boolean) = updateSettings { it.copy(simpleMode = enabled) }

    /** 信任模式：允许首页自动更新读取微信 / 支付宝页面。 */
    fun setTrustMode(enabled: Boolean) = updateSettings { it.copy(trustMode = enabled) }

    /** 隐私模式：禁止截屏与录屏。 */
    fun setPrivacyMode(enabled: Boolean) = updateSettings { it.copy(privacyMode = enabled) }

    // ------------------------------------------------------------------ 外观与语言

    fun setLanguage(language: AppLanguage) = updateSettings { it.copy(language = language) }

    fun setThemeTone(tone: ThemeTone) = updateSettings { it.copy(themeTone = tone) }

    fun setDensity(density: UiDensity) = updateSettings { it.copy(density = density) }

    /** 日间 / 夜间模式：跟随系统、日间、夜间。 */
    fun setDarkMode(mode: DarkMode) = updateSettings { it.copy(darkMode = mode) }

    /** 图标配色方式：跟随主题或手动设置。 */
    fun setIconColorMode(mode: IconColorMode) = updateSettings { it.copy(iconColorMode = mode) }

    /**
     * 手动图标配色（仅在 [IconColorMode.MANUAL] 时生效）。
     * 实际切换桌面图标由 LedgerApp 里的 AppIconController 完成，这里只保存设置。
     */
    fun setIconTone(tone: ThemeTone) = updateSettings { it.copy(iconTone = tone) }

    // ------------------------------------------------------------------ 首页账户排序

    /** 首页账户余额的排序方式（设置页与首页共用同一个设置项）。 */
    fun setAccountSortMode(mode: AccountSortMode) = updateSettings { it.copy(accountSortMode = mode) }

    // ------------------------------------------------------------------ 动账信源 / 自动去重

    fun setSmsAutoScan(enabled: Boolean) = updateSettings { it.copy(smsAutoScan = enabled) }

    /** 回溯天数：手输 1..365，越界自动收敛。 */
    fun setSmsLookbackDays(days: Int) = updateSettings { it.copy(smsLookbackDays = days.coerceIn(1, 365)) }

    /**
     * 需要解析支付通知的应用（CSV 写入 `notifyPackages`）。
     *
     * 保持界面上的勾选顺序，去重后保存；空列表表示不解析任何应用通知。
     */
    fun setNotifyPackages(packages: List<String>) = updateSettings {
        val cleaned = packages.map { name -> name.trim() }.filter { name -> name.isNotEmpty() }.distinct()
        it.copy(notifyPackages = LayoutPrefs.joinStrings(cleaned))
    }

    /** 单个应用的勾选 / 取消勾选（列表逐项勾选时使用）。 */
    fun setNotifyPackageEnabled(packageName: String, enabled: Boolean) = updateSettings { settings ->
        val current = LayoutPrefs.splitCsv(settings.notifyPackages).toMutableList()
        if (enabled) {
            if (packageName !in current) current.add(packageName)
        } else {
            current.remove(packageName)
        }
        settings.copy(notifyPackages = LayoutPrefs.joinStrings(current))
    }

    // ------------------------------------------------------------------ 基金 / 屏蔽词 / 通知类型

    /**
     * 基金购买不计入支出：开启后短信里的基金申购 / 赎回只调整“基金余额”账户，
     * 不再插入支出 / 收入流水（由 ingest 模块读取该开关）。
     */
    fun setFundTrackingEnabled(enabled: Boolean) = updateSettings {
        it.copy(fundTrackingEnabled = enabled)
    }

    /**
     * 保存“屏蔽词清单”：换行 / 中英文逗号 / 分号分隔都接受，统一规整成每行一个词。
     * 与 ingest 模块的 [FilterPrefs.parseBlockWords] 使用同一套规则，避免两处解析不一致。
     */
    fun setBlockWords(words: String) = updateSettings {
        it.copy(blockWords = FilterPrefs.encodeBlockWords(FilterPrefs.parseBlockWords(words)))
    }

    /**
     * 单个应用的通知类型开关：把 [kind] 加入 / 移出该应用的允许集合。
     *
     * 允许集合为空时保持原值（FilterPrefs 无法表达“一个类型都不允许”，界面也会禁止取消最后一种）；
     * 恢复成全部类型时移除该包配置，等价于“全部类型都解析、不写该包配置”。
     */
    fun setNotifyKind(packageName: String, kind: NotificationKind, enabled: Boolean) = updateSettings { settings ->
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return@updateSettings settings
        val filters = FilterPrefs.parseNotifyKindFilters(settings.notifyKindFilters).toMutableMap()
        val allKinds = NotificationKind.values().toSet()
        val current = filters[pkg] ?: allKinds
        val updated = if (enabled) current + kind else current - kind
        when {
            updated.isEmpty() -> settings
            updated == allKinds -> {
                filters.remove(pkg)
                settings.copy(notifyKindFilters = FilterPrefs.encodeNotifyKindFilters(filters))
            }
            else -> {
                filters[pkg] = updated
                settings.copy(notifyKindFilters = FilterPrefs.encodeNotifyKindFilters(filters))
            }
        }
    }

    /** 自动去重：自动托管（beta）/ 每次询问 / 关闭。 */
    fun setDedupeMode(mode: DedupeMode) = updateSettings { it.copy(dedupeMode = mode) }

    /**
     * 读取已安装的启动器应用，供“需要解析支付通知的应用”二级菜单展示。
     *
     * 排除本应用；微信 / 支付宝排在前面，其余按显示名排序。
     */
    fun loadNotifyApps() {
        viewModelScope.launch {
            _notifyApps.value = queryLauncherApps()
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLauncherApps(): List<NotifyAppOption> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                packageManager.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())
        val apps = resolved.asSequence()
            .mapNotNull { info -> info.activityInfo?.applicationInfo }
            .filter { info -> info.packageName != context.packageName }
            .distinctBy { info -> info.packageName }
            .map { info ->
                val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName)
                NotifyAppOption(packageName = info.packageName, label = label.ifBlank { info.packageName })
            }
            .toList()
        return apps.sortedWith(
            compareBy<NotifyAppOption>(
                { option -> notifyAppRank(option.packageName) },
                { option -> option.label.lowercase(Locale.getDefault()) }
            )
        )
    }

    /** 微信 / 支付宝固定排在最前面，方便确认默认勾选项。 */
    private fun notifyAppRank(packageName: String): Int = when (packageName) {
        WECHAT_PACKAGE -> 0
        ALIPAY_PACKAGE -> 1
        else -> 2
    }

    // ------------------------------------------------------------------ 信任模式

    /** 自动更新读取延迟：等待页面加载完成后再读屏，默认 5 秒，范围 1..30。 */
    fun setAutoUpdateDelay(seconds: Int) =
        updateSettings { it.copy(autoUpdateDelaySeconds = seconds.coerceIn(1, 30)) }

    // ------------------------------------------------------------------ 报表通知

    fun setReportNotifications(enabled: Boolean) =
        updateSettings(reschedule = true) { it.copy(reportNotifications = enabled) }

    fun setDailyReportEnabled(enabled: Boolean) =
        updateSettings(reschedule = true) { it.copy(dailyReportEnabled = enabled) }

    fun setWeeklyReportEnabled(enabled: Boolean) =
        updateSettings(reschedule = true) { it.copy(weeklyReportEnabled = enabled) }

    fun setMonthlyReportEnabled(enabled: Boolean) =
        updateSettings(reschedule = true) { it.copy(monthlyReportEnabled = enabled) }

    fun setYearlyReportEnabled(enabled: Boolean) =
        updateSettings(reschedule = true) { it.copy(yearlyReportEnabled = enabled) }

    fun setReportTime(hour: Int, minute: Int) = updateSettings(reschedule = true) {
        it.copy(reportHour = hour.coerceIn(0, 23), reportMinute = minute.coerceIn(0, 59))
    }

    /** 周报日期：1=周一 … 7=周日（与 java.time.DayOfWeek.value 一致）。 */
    fun setWeeklyDay(day: Int) = updateSettings(reschedule = true) {
        it.copy(weeklyDayOfWeek = day.coerceIn(1, 7))
    }

    /** 月报日期：1..28，避免 29/30/31 日在部分月份不触发。 */
    fun setMonthlyDay(day: Int) = updateSettings(reschedule = true) {
        it.copy(monthlyDay = day.coerceIn(1, 28))
    }

    /** 年报日期：月份 1..12，日 1..28。 */
    fun setYearlyDate(month: Int, day: Int) = updateSettings(reschedule = true) {
        it.copy(yearlyMonth = month.coerceIn(1, 12), yearlyDay = day.coerceIn(1, 28))
    }

    /** 报表模式：收支默认模式 / 月结模式。 */
    fun setReportMode(mode: ReportMode) = updateSettings(reschedule = true) { it.copy(reportMode = mode) }

    fun setAutoCategorize(enabled: Boolean) = updateSettings { it.copy(autoCategorize = enabled) }

    // ------------------------------------------------------------------ 页面布局

    /** 直接保存首页卡片的完整顺序（逗号分隔的枚举名）。 */
    fun updateHomeOrder(order: List<HomeSection>) = updateSettings {
        it.copy(homeSectionOrder = LayoutPrefs.join(order))
    }

    /** 首页卡片上移 / 下移一格。 */
    fun moveHomeSection(section: HomeSection, up: Boolean) = updateSettings { settings ->
        settings.copy(homeSectionOrder = LayoutPrefs.join(homeOrderOf(settings).moveItem(section, up)))
    }

    /** 首页卡片显示 / 隐藏；隐藏项只写 hiddenHomeSections，顺序保持不变。 */
    fun toggleHomeSection(section: HomeSection, visible: Boolean) = updateSettings { settings ->
        val hidden = LayoutPrefs.splitCsv(settings.hiddenHomeSections).toMutableSet()
        if (visible) hidden.remove(section.name) else hidden.add(section.name)
        settings.copy(hiddenHomeSections = LayoutPrefs.joinStrings(hidden.toList()))
    }

    /** 直接保存底部导航的完整顺序。 */
    fun setBottomNavOrder(order: List<BottomTab>) = updateSettings {
        it.copy(bottomNavOrder = LayoutPrefs.join(order))
    }

    /** 底部导航上移 / 下移一格。 */
    fun moveBottomNav(tab: BottomTab, up: Boolean) = updateSettings { settings ->
        settings.copy(bottomNavOrder = LayoutPrefs.join(bottomNavOrderOf(settings).moveItem(tab, up)))
    }

    /**
     * 底部导航显示 / 隐藏。
     * 首页与设置必须常驻（否则用户无法进入设置恢复布局），这两项调用时直接忽略。
     */
    fun toggleBottomNav(tab: BottomTab, visible: Boolean) = updateSettings { settings ->
        if (tab == BottomTab.HOME || tab == BottomTab.SETTINGS) {
            settings
        } else {
            val hidden = LayoutPrefs.splitCsv(settings.hiddenBottomNav).toMutableSet()
            if (visible) hidden.remove(tab.name) else hidden.add(tab.name)
            settings.copy(hiddenBottomNav = LayoutPrefs.joinStrings(hidden.toList()))
        }
    }

    /** 恢复默认布局：首页卡片与底部导航回到默认顺序并全部显示。 */
    fun resetLayout() = updateSettings {
        it.copy(
            homeSectionOrder = LayoutPrefs.DEFAULT_HOME_ORDER,
            hiddenHomeSections = "",
            bottomNavOrder = LayoutPrefs.DEFAULT_NAV_ORDER,
            hiddenBottomNav = "",
            accountSortMode = AccountSortMode.CUSTOM
        )
    }

    // ------------------------------------------------------------------ 账户管理

    /** 账户上移 / 下移：按当前 sortOrder 交换相邻两项后重写 0..n-1。 */
    fun reorderAccount(account: Account, up: Boolean) {
        viewModelScope.launch {
            try {
                val ordered = repos.accounts.getAll().sortedBy { it.sortOrder }.toMutableList()
                val index = ordered.indexOfFirst { it.id == account.id }
                if (index < 0) return@launch
                val target = if (up) index - 1 else index + 1
                if (target !in ordered.indices) return@launch
                val moved = ordered.removeAt(index)
                ordered.add(target, moved)
                repos.accounts.updateSortOrders(ordered)
            } catch (e: Exception) {
                _messages.emit(I18n.t("调整账户顺序失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    /** 删除账户入口（契约命名）：先查流水笔数，有流水提示不可删，无流水弹确认框。 */
    fun deleteAccount(account: Account) = requestDeleteAccount(account)

    /** 点删除时先查流水笔数：有流水提示不可删，无流水弹确认框。 */
    fun requestDeleteAccount(account: Account) {
        viewModelScope.launch {
            val count = runCatching { repos.accounts.countTransactions(account.id) }.getOrDefault(0)
            _accountDeleteState.value = AccountDeleteState(account = account, txCount = count)
        }
    }

    /** 确认删除无流水的账户。 */
    fun confirmDeleteAccount() {
        val state = _accountDeleteState.value ?: return
        if (!state.canDelete) {
            _accountDeleteState.value = null
            return
        }
        viewModelScope.launch {
            try {
                repos.accounts.delete(state.account.id)
                _messages.emit(I18n.t("已删除账户：%1\$s", state.account.name))
            } catch (e: Exception) {
                _messages.emit(I18n.t("删除账户失败：%1\$s", e.message ?: I18n.t("未知错误")))
            } finally {
                _accountDeleteState.value = null
            }
        }
    }

    /** 关闭账户删除弹窗。 */
    fun dismissAccountDelete() {
        _accountDeleteState.value = null
    }

    /**
     * 余额修正（v1.2.3）：读取每个账户最后一条带余额的流水，把账户余额修正为短信中记录的余额，
     * 没有余额信息的账户由 [BalanceCorrectionService] 跳过。
     *
     * 防重复点击分两层：这里先用 [_correctingBalances] 做同步守卫（Compose 点击回调都在主线程，
     * 置位后再启动协程，连续点击不会重复执行）；设置页同时按该状态把按钮置灰。
     * 成功 / 失败都走现有的 [_messages] 提示机制（设置页统一收集后显示 Snackbar）。
     */
    fun correctBalances() {
        if (_correctingBalances.value) return
        _correctingBalances.value = true
        viewModelScope.launch {
            try {
                val result = BalanceCorrectionService.correctAll(repos)
                _lastBalanceCorrection.value = result
                _messages.emit(
                    I18n.t(
                        "已修正 %1\$d 个账户余额，跳过 %2\$d 个",
                        result.updated,
                        result.skipped
                    )
                )
            } catch (e: Exception) {
                _messages.emit(I18n.t("余额修正失败：%1\$s", e.message ?: I18n.t("未知错误")))
            } finally {
                _correctingBalances.value = false
            }
        }
    }

    // ------------------------------------------------------------------ 短信来源 / 分类

    /** 新增或更新短信来源。 */
    fun saveSmsSource(source: SmsSource) {
        viewModelScope.launch {
            try {
                if (source.id == 0L) {
                    repos.smsSources.insert(source)
                    _messages.emit(I18n.t("已添加短信来源：%1\$s", source.label))
                } else {
                    repos.smsSources.update(source)
                    _messages.emit(I18n.t("已保存短信来源：%1\$s", source.label))
                }
            } catch (e: Exception) {
                _messages.emit(I18n.t("保存短信来源失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    fun deleteSmsSource(source: SmsSource) {
        viewModelScope.launch {
            try {
                repos.smsSources.delete(source.id)
                _messages.emit(I18n.t("已删除短信来源：%1\$s", source.label))
            } catch (e: Exception) {
                _messages.emit(I18n.t("删除短信来源失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    fun toggleSmsSource(source: SmsSource) {
        viewModelScope.launch {
            try {
                repos.smsSources.update(source.copy(enabled = !source.enabled))
            } catch (e: Exception) {
                _messages.emit(I18n.t("切换开关失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    /** 新增或更新分类。 */
    fun saveCategory(category: Category) {
        viewModelScope.launch {
            try {
                if (category.id == 0L) {
                    repos.categories.insert(category)
                    _messages.emit(I18n.t("已添加分类：%1\$s", category.name))
                } else {
                    repos.categories.update(category)
                    _messages.emit(I18n.t("已保存分类：%1\$s", category.name))
                }
            } catch (e: Exception) {
                _messages.emit(I18n.t("保存分类失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            try {
                repos.categories.delete(category.id)
                _messages.emit(I18n.t("已删除分类：%1\$s，历史流水请手动调整", category.name))
            } catch (e: Exception) {
                _messages.emit(I18n.t("删除分类失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    /** 新增或更新账户（银行卡可手填余额）。 */
    fun saveAccount(account: Account) {
        viewModelScope.launch {
            try {
                if (account.id == 0L) {
                    repos.accounts.insert(account)
                    _messages.emit(I18n.t("已添加账户：%1\$s", account.name))
                } else {
                    repos.accounts.update(account)
                    _messages.emit(I18n.t("已保存账户：%1\$s", account.name))
                }
            } catch (e: Exception) {
                _messages.emit(I18n.t("保存账户失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    // ------------------------------------------------------------------ 内部工具

    /** 当前完整的首页卡片顺序（缺失项按声明顺序补在末尾，隐藏项也包含在内）。 */
    private fun homeOrderOf(settings: LedgerSettings): List<HomeSection> =
        orderedEnums(settings.homeSectionOrder, HomeSection.values().toList())

    /** 当前完整的底部导航顺序。 */
    private fun bottomNavOrderOf(settings: LedgerSettings): List<BottomTab> =
        orderedEnums(settings.bottomNavOrder, BottomTab.values().toList())

    /** 保存设置；[reschedule] 为 true 时同时按最新设置重排报表任务。 */
    private fun updateSettings(
        reschedule: Boolean = false,
        transform: (LedgerSettings) -> LedgerSettings
    ) {
        viewModelScope.launch {
            try {
                repos.settings.update(transform)
                if (reschedule) {
                    runCatching { ReportScheduler.schedule(context) }
                }
            } catch (e: Exception) {
                _messages.emit(I18n.t("设置保存失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    companion object {
        /** 微信包名（默认勾选解析支付通知）。 */
        const val WECHAT_PACKAGE = "com.tencent.mm"

        /** 支付宝包名（默认勾选解析支付通知）。 */
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

        fun factory(context: Context, repos: LedgerRepos): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(context, repos) }
        }
    }
}

/**
 * 把逗号分隔的枚举名解析成完整顺序：已保存的项按保存顺序在前，
 * 未保存过的新枚举值按声明顺序补在末尾，保证升级后不丢新增区块。
 */
internal fun <T : Enum<T>> orderedEnums(raw: String, all: List<T>): List<T> {
    val parsed = LayoutPrefs.splitCsv(raw).mapNotNull { name ->
        all.firstOrNull { it.name == name }
    }
    return parsed.distinct() + all.filter { it !in parsed }
}

/** 把列表中的某一项上移 / 下移一格；越界时原样返回。 */
internal fun <T> List<T>.moveItem(item: T, up: Boolean): List<T> {
    val index = indexOf(item)
    if (index < 0) return this
    val target = if (up) index - 1 else index + 1
    if (target !in indices) return this
    val result = toMutableList()
    result.removeAt(index)
    result.add(target, item)
    return result
}
