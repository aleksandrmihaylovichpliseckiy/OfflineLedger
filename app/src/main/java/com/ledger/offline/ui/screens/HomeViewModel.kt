package com.ledger.offline.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledger.offline.ax.AutoUpdateController
import com.ledger.offline.ax.AutoUpdateState
import com.ledger.offline.ax.AutoUpdateTarget
import com.ledger.offline.dedupe.DedupeChoice
import com.ledger.offline.dedupe.DedupeService
import com.ledger.offline.dedupe.DuplicatePair
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountSortMode
import com.ledger.offline.domain.model.DailyTotal
import com.ledger.offline.domain.model.FlowTotals
import com.ledger.offline.domain.model.HomeSection
import com.ledger.offline.domain.model.LedgerSettings
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n
import com.ledger.offline.ingest.LedgerIngest
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.LayoutPrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 去重对话框里单条流水的展示数据（时间 / 金额 / 商户 / 来源）。 */
data class DuplicateRecordUi(
    val timeMillis: Long,
    val amountCents: Long,
    val merchant: String,
    /** 中文来源名，展示前由界面统一 `tr(...)`。 */
    val sourceLabel: String
)

/** 去重对话框里的一组疑似重复：保留项 + 待合并项。 */
data class DuplicateGroupUi(
    val pair: DuplicatePair,
    val keep: DuplicateRecordUi,
    val drop: DuplicateRecordUi
)

/**
 * 首页数据：本月汇总、账户（含流水排序）、近 7 天趋势、最近流水、
 * 启动自动扫描短信，以及信任模式下的自动更新状态。
 *
 * 账户排序规则：
 * - CUSTOM：按账户自身的 sortOrder（设置页账户管理里的顺序）；
 * - BALANCE_DESC：按余额降序，未同步余额的账户排在最后；
 * - FLOW_DESC：按 `repos.accounts.flowTotals()` 的累计流水金额降序。
 *
 * v1.2.0 新增：首页区块隐藏 / 撤销、一键核验（DedupeService）与待询问重复的逐组处理。
 */
class HomeViewModel(
    private val context: Context,
    private val repos: LedgerRepos
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 扫描结果、核验结果等一次性提示，由首页用 Snackbar 展示。 */
    val messages: SharedFlow<String> = _messages

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** 一键核验进行中，用于禁用按钮并显示进度文案。 */
    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying

    /** 待询问的重复组；非空时首页弹出逐组处理对话框（第一项即当前组）。 */
    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroupUi>>(emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroupUi>> = _duplicateGroups

    /** 正在执行合并 / 保留，防止对话框按钮被连点导致同一组重复处理。 */
    private val _resolvingDuplicate = MutableStateFlow(false)
    val resolvingDuplicate: StateFlow<Boolean> = _resolvingDuplicate

    /** 已提示过的 pendingDuplicate 原文，避免用户关掉对话框后又被同一条设置反复弹出。 */
    private var promptedPending: String? = null

    /** 每个账户的累计流水金额（收入 + 支出绝对值之和），用于“按流水降序”。 */
    private val _flowTotals = MutableStateFlow<Map<Long, Long>>(emptyMap())

    val settings: StateFlow<LedgerSettings> = repos.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LedgerSettings())

    /** 简易模式：隐藏趋势、最近流水与分类统计，只保留汇总卡 / 账户 / 快捷操作。 */
    val simpleMode: StateFlow<Boolean> = settings
        .map { it.simpleMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** 信任模式：开启后才展示“自动更新”入口，并允许调用无障碍服务。 */
    val trustMode: StateFlow<Boolean> = settings
        .map { it.trustMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** 自动更新的实时状态（Idle / Running / Success / Error），由 ax 模块维护。 */
    val autoUpdateState: StateFlow<AutoUpdateState> = AutoUpdateController.state

    /** 本月收支汇总。 */
    val monthTotals: StateFlow<FlowTotals> = repos.transactions.observeTotals(DateTimes.thisMonthRange())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), FlowTotals())

    /** 账户列表：账户表 + 当前排序模式 + 累计流水，三者合并后排序。 */
    val accounts: StateFlow<List<Account>> = combine(
        repos.accounts.observeAll(),
        settings,
        _flowTotals
    ) { accountList, currentSettings, flowTotals ->
        sortAccounts(accountList, currentSettings.accountSortMode, flowTotals)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val recent: StateFlow<List<TransactionRecord>> = repos.transactions.observeRecent(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * 近 7 天每日收支。
     * 直接由流水 Flow 在内存里聚合，短信导入 / 手动记账后趋势图会自动刷新。
     */
    val last7Days: StateFlow<List<DailyTotal>> = repos.transactions.observeBetween(DateTimes.lastDaysRange(7))
        .map { records -> buildLast7Days(records) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    init {
        viewModelScope.launch { refreshFlowTotals() }
        // 流水表任何增删改都会让 Room 重新查询，这里顺便刷新账户累计流水，保证排序实时生效
        viewModelScope.launch {
            repos.transactions.observeRecent(1).collect { refreshFlowTotals() }
        }
        viewModelScope.launch {
            val current = runCatching { repos.settings.current() }.getOrDefault(LedgerSettings())
            if (current.smsAutoScan && LedgerIngest.hasSmsPermission(context)) {
                scanSms()
            }
        }
        // 启动 / 导入 / 核验后只要 pendingDuplicate 非空，就弹出逐组处理对话框
        viewModelScope.launch {
            repos.settings.settings
                .map { it.pendingDuplicate }
                .distinctUntilChanged()
                .collect { pending -> onPendingDuplicateChanged(pending) }
        }
    }

    /** 切换首页账户余额的排序方式，写入设置后账户列表自动重排。 */
    fun setAccountSortMode(mode: AccountSortMode) {
        viewModelScope.launch {
            repos.settings.update { it.copy(accountSortMode = mode) }
        }
    }

    /** 手动扫描短信。 */
    fun scanNow() {
        if (_scanning.value) return
        viewModelScope.launch { scanSms() }
    }

    /**
     * 一键核验：扫描最近 90 天的银行卡短信 / 支付通知疑似重复。
     * 完成后用 Snackbar 汇总结果；若还有待询问记录，则加载并逐组弹出对话框。
     */
    fun verifyNow() {
        if (_verifying.value) return
        _verifying.value = true
        viewModelScope.launch {
            try {
                val result = DedupeService.verifyNow(repos)
                refreshFlowTotals()
                _messages.emit(
                    I18n.t("发现 %1\$d 组疑似重复，已合并 %2\$d 组", result.found, result.merged)
                )
                val pending = runCatching { repos.settings.current().pendingDuplicate }
                    .getOrDefault("")
                if (result.pending > 0 || pending.isNotBlank()) {
                    loadPendingDuplicates()
                }
            } catch (e: Exception) {
                _messages.emit(I18n.t("核验失败：%1\$s", e.message ?: I18n.t("未知错误")))
            } finally {
                _verifying.value = false
            }
        }
    }

    /** 用户对当前组的选择：合并（保留短信那笔）或保留两笔。 */
    fun resolveDuplicate(group: DuplicateGroupUi, choice: DedupeChoice) {
        if (_resolvingDuplicate.value) return
        _resolvingDuplicate.value = true
        viewModelScope.launch {
            try {
                val before = runCatching { repos.settings.current().pendingDuplicate }.getOrDefault("")
                DedupeService.resolve(repos, group.pair, choice)
                val after = runCatching { repos.settings.current().pendingDuplicate }.getOrDefault(before)
                if (after == before) {
                    // 删除失败等情况下该对仍留在待询问列表：重新读取并保留，允许用户重试
                    val remaining = runCatching {
                        DedupeService.pendingPairs(repos).map { pair -> toGroupUi(pair) }
                    }.getOrElse { emptyList() }
                    _duplicateGroups.value = remaining
                    if (remaining.any { it.pair == group.pair }) {
                        _messages.emit(I18n.t("处理失败：%1\$s", I18n.t("请稍后重试")))
                    }
                } else {
                    _duplicateGroups.update { groups ->
                        groups.filterNot { it.pair == group.pair }
                    }
                    refreshFlowTotals()
                }
            } catch (e: Exception) {
                _messages.emit(I18n.t("处理失败：%1\$s", e.message ?: I18n.t("未知错误")))
            } finally {
                _resolvingDuplicate.value = false
            }
        }
    }

    /** 关闭对话框（不处理剩余待询问项，下次启动或新重复出现时再提示）。 */
    fun dismissDuplicateDialog() {
        _duplicateGroups.value = emptyList()
    }

    /** 从首页隐藏一个区块；顶部本月汇总卡不可隐藏。 */
    fun hideHomeSection(section: HomeSection) {
        viewModelScope.launch {
            repos.settings.update { current ->
                val hidden = LayoutPrefs.splitCsv(current.hiddenHomeSections)
                    .filterNot { it == section.name } + section.name
                current.copy(hiddenHomeSections = LayoutPrefs.joinStrings(hidden))
            }
        }
    }

    /** 撤销隐藏：只把该区块从 hiddenHomeSections 移除，不影响其它区块。 */
    fun restoreHomeSection(section: HomeSection) {
        viewModelScope.launch {
            repos.settings.update { current ->
                val hidden = LayoutPrefs.splitCsv(current.hiddenHomeSections)
                    .filterNot { it == section.name }
                current.copy(hiddenHomeSections = LayoutPrefs.joinStrings(hidden))
            }
        }
    }

    /** 自动更新（信任模式）：校验信任模式与无障碍授权后拉起微信账单页。 */
    fun startAutoUpdate() {
        AutoUpdateController.start(context, repos)
    }

    /** 自动更新支付宝：拉起支付宝并读取余额 / 账单（信任模式）。 */
    fun startAlipayAutoUpdate() {
        AutoUpdateController.start(context, repos, AutoUpdateTarget.ALIPAY)
    }

    /** 手动兜底：只解析当前已打开的微信 / 支付宝页面。 */
    fun readCurrentPage() {
        AutoUpdateController.readCurrentPage(context, repos)
    }

    /** 无障碍服务是否已在系统设置里授权。 */
    fun isAutoUpdateServiceEnabled(): Boolean = AutoUpdateController.isServiceEnabled(context)

    /** 打开系统无障碍设置，让用户手动授权。 */
    fun openAccessibilitySettings() {
        AutoUpdateController.openAccessibilitySettings(context)
    }

    private suspend fun scanSms() {
        _scanning.value = true
        val text = try {
            LedgerIngest.scanSmsNow(context, repos).summary()
        } catch (e: Exception) {
            I18n.t("扫描失败：%1\$s", e.message ?: I18n.t("未知错误"))
        }
        _scanning.value = false
        refreshFlowTotals()
        _messages.emit(text)
    }

    /** pendingDuplicate 变化：清空后复位提示状态；出现新值且当前没有对话框时加载待询问组。 */
    private suspend fun onPendingDuplicateChanged(pending: String) {
        if (pending.isBlank()) {
            promptedPending = null
            if (_duplicateGroups.value.isNotEmpty()) {
                _duplicateGroups.value = emptyList()
            }
            return
        }
        if (pending != promptedPending && _duplicateGroups.value.isEmpty()) {
            promptedPending = pending
            loadPendingDuplicates()
        }
    }

    /** 读取并还原待询问列表，补全两条流水的秒级时间 / 商户 / 来源。 */
    private suspend fun loadPendingDuplicates() {
        val groups = try {
            DedupeService.pendingPairs(repos).map { pair -> toGroupUi(pair) }
        } catch (e: Exception) {
            emptyList()
        }
        _duplicateGroups.value = groups
    }

    private suspend fun toGroupUi(pair: DuplicatePair): DuplicateGroupUi {
        val keepTx = runCatching { repos.transactions.getById(pair.keepId) }.getOrNull()
        val dropTx = runCatching { repos.transactions.getById(pair.dropId) }.getOrNull()
        return DuplicateGroupUi(
            pair = pair,
            keep = DuplicateRecordUi(
                timeMillis = keepTx?.occurredAt ?: pair.occurredAt,
                amountCents = pair.amountCents,
                merchant = keepTx?.merchant?.takeIf { it.isNotBlank() } ?: pair.keepMerchant,
                sourceLabel = sourceLabel(pair.keepSource)
            ),
            drop = DuplicateRecordUi(
                timeMillis = dropTx?.occurredAt ?: pair.occurredAt,
                amountCents = pair.amountCents,
                merchant = dropTx?.merchant?.takeIf { it.isNotBlank() } ?: pair.dropMerchant,
                sourceLabel = sourceLabel(pair.dropSource)
            )
        )
    }

    /** DedupeService 的来源字符串可能是枚举名（SMS）或中文标签（银行短信），两种都兼容。 */
    private fun sourceLabel(raw: String): String {
        val trimmed = raw.trim()
        val byName = TxSource.values().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        return byName?.label ?: trimmed.ifBlank { I18n.t("未知来源") }
    }

    /** 重新读取账户累计流水；失败时保留上一次结果，不影响首页展示。 */
    private suspend fun refreshFlowTotals() {
        val totals = runCatching { repos.accounts.flowTotals() }.getOrNull() ?: return
        _flowTotals.value = totals
    }

    /** 按设置里的排序模式重排账户；同值时用 sortOrder / id 兜底，保证顺序稳定。 */
    private fun sortAccounts(
        accounts: List<Account>,
        mode: AccountSortMode,
        flowTotals: Map<Long, Long>
    ): List<Account> = when (mode) {
        AccountSortMode.CUSTOM -> accounts.sortedWith(
            compareBy<Account>({ it.sortOrder }, { it.id })
        )
        AccountSortMode.BALANCE_DESC -> accounts.sortedWith(
            compareByDescending<Account> { it.balanceCents ?: Long.MIN_VALUE }
                .thenBy { it.sortOrder }
                .thenBy { it.id }
        )
        AccountSortMode.FLOW_DESC -> accounts.sortedWith(
            compareByDescending<Account> { flowTotals[it.id] ?: 0L }
                .thenBy { it.sortOrder }
                .thenBy { it.id }
        )
    }

    /** 把最近 7 天流水汇总成按天列表，缺失的日期补 0。 */
    private fun buildLast7Days(records: List<TransactionRecord>): List<DailyTotal> {
        val grouped = records.groupBy { DateTimes.startOfDay(it.tx.occurredAt) }
        val today = DateTimes.localDate(System.currentTimeMillis())
        return (6 downTo 0).map { offset ->
            val dayStart = DateTimes.startOfDay(today.minusDays(offset.toLong()))
            val dayRecords = grouped[dayStart].orEmpty()
            DailyTotal(
                dayStartMillis = dayStart,
                incomeCents = dayRecords.filter { it.tx.type == TxType.INCOME }.sumOf { it.tx.amountCents },
                expenseCents = dayRecords.filter { it.tx.type == TxType.EXPENSE }.sumOf { it.tx.amountCents }
            )
        }
    }

    companion object {
        fun factory(context: Context, repos: LedgerRepos): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(context, repos) }
        }
    }
}
