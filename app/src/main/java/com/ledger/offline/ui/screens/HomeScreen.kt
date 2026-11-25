@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ledger.offline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.offline.ax.AutoUpdateState
import com.ledger.offline.dedupe.DedupeChoice
import com.ledger.offline.di.AppContainer
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountSortMode
import com.ledger.offline.domain.model.DailyTotal
import com.ledger.offline.domain.model.FlowTotals
import com.ledger.offline.domain.model.HomeSection
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.tr
import com.ledger.offline.ui.HomeScrollBus
import com.ledger.offline.ui.components.AmountText
import com.ledger.offline.ui.components.EmptyHint
import com.ledger.offline.ui.components.SectionCard
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.LayoutPrefs
import com.ledger.offline.util.Money
import kotlinx.coroutines.launch

/**
 * 首页 v2。
 *
 * 结构：
 * 1. 顶部“本月结余”汇总卡固定展示，不可隐藏；
 * 2. 其余区块按 [LayoutPrefs.parseHomeSections] 的顺序渲染，可排序 / 可隐藏；
 * 3. 简易模式隐藏“收支趋势”和“最近流水”，只保留汇总卡、账户与快捷操作；
 * 4. 快捷操作里的“自动更新”仅在信任模式开启时出现，调用 ax 模块的无障碍能力；
 * 5. v1.2.0：“一键核验”调用 DedupeService；待询问的重复组用对话框逐组确认；
 * 6. v1.2.1：趋势图横轴改用“日”标签并适配大字号 / 窄屏，底部“首页”可滚动回顶。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onAddTransaction: () -> Unit,
    onOpenReports: (ReportPeriod) -> Unit,
    onOpenTransactions: () -> Unit,
    onOpenAnalysis: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val viewModel: HomeViewModel = viewModel(
        factory = remember(container) { HomeViewModel.factory(context, container.repos) }
    )
    val totals by viewModel.monthTotals.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val last7Days by viewModel.last7Days.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val simpleMode by viewModel.simpleMode.collectAsStateWithLifecycle()
    val trustMode by viewModel.trustMode.collectAsStateWithLifecycle()
    val autoUpdateState by viewModel.autoUpdateState.collectAsStateWithLifecycle()
    val duplicateGroups by viewModel.duplicateGroups.collectAsStateWithLifecycle()
    val resolvingDuplicate by viewModel.resolvingDuplicate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 底部“首页”回顶：滚动位置保存在 LazyListState 里；
    // HomeScrollBus 的计数变化时滚动到第 0 项，覆盖“从其它页点首页”和“已在首页再点首页”。
    val listState = rememberLazyListState()
    val scrollRequestId by HomeScrollBus.requestId.collectAsStateWithLifecycle()
    var handledScrollRequestId by rememberSaveable { mutableStateOf(Int.MIN_VALUE) }
    LaunchedEffect(scrollRequestId) {
        if (scrollRequestId > 0 && scrollRequestId != handledScrollRequestId) {
            listState.scrollToItem(0)
            handledScrollRequestId = scrollRequestId
        }
    }

    // 可排序 / 可隐藏的首页区块；简易模式再筛掉趋势与最近流水
    val sections = remember(settings.homeSectionOrder, settings.hiddenHomeSections, simpleMode) {
        LayoutPrefs.parseHomeSections(settings.homeSectionOrder, settings.hiddenHomeSections)
            .filter { section ->
                !(simpleMode && (section == HomeSection.TREND || section == HomeSection.RECENT))
            }
    }
    val autoRunning = autoUpdateState is AutoUpdateState.Running
    val accessibilityHint = tr("请先在系统设置中开启“自动记账”的无障碍服务，再点“自动更新”")

    // 隐藏区块后给一个可撤销的 Snackbar；撤销只恢复当前区块，不影响其它隐藏项
    val hideSection: (HomeSection) -> Unit = { section ->
        viewModel.hideHomeSection(section)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = tr("已隐藏“%1\$s”", tr(section.label)),
                actionLabel = tr("撤销"),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreHomeSection(section)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // 自动更新的进度 / 结果由 ax 模块的 state 驱动，Idle 不提示
    LaunchedEffect(viewModel) {
        viewModel.autoUpdateState.collect { state ->
            val message = when (state) {
                is AutoUpdateState.Running -> state.message
                is AutoUpdateState.Success -> state.message
                is AutoUpdateState.Error -> state.message
                else -> null
            }
            if (!message.isNullOrBlank()) {
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("自动记账")) },
                actions = {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = viewModel::scanNow) {
                        Icon(Icons.Filled.Refresh, contentDescription = tr("立即扫描短信"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 本月汇总卡固定展示，不参与排序 / 隐藏
            item(key = "month-summary") { MonthSummaryCard(totals) }
            sections.forEach { section ->
                item(key = section.name) {
                    val onHide = { hideSection(section) }
                    when (section) {
                        HomeSection.ACCOUNTS -> AccountsSection(
                            accounts = accounts,
                            sortMode = settings.accountSortMode,
                            onSortModeChange = viewModel::setAccountSortMode,
                            onHide = onHide
                        )
                        HomeSection.TREND -> TrendSection(
                            days = last7Days,
                            onOpenAnalysis = onOpenAnalysis,
                            onHide = onHide
                        )
                        HomeSection.RECENT -> RecentSection(
                            recent = recent,
                            onOpenTransactions = onOpenTransactions,
                            onHide = onHide
                        )
                        HomeSection.QUICK -> QuickActionsCard(
                            scanning = scanning,
                            verifying = verifying,
                            trustMode = trustMode,
                            autoRunning = autoRunning,
                            onAddTransaction = onAddTransaction,
                            onScan = viewModel::scanNow,
                            onVerify = viewModel::verifyNow,
                            onOpenReports = onOpenReports,
                            onAutoUpdate = {
                                if (viewModel.isAutoUpdateServiceEnabled()) {
                                    viewModel.startAutoUpdate()
                                } else {
                                    viewModel.openAccessibilitySettings()
                                    scope.launch { snackbarHostState.showSnackbar(accessibilityHint) }
                                }
                            },
                            onAlipayUpdate = {
                                if (viewModel.isAutoUpdateServiceEnabled()) {
                                    viewModel.startAlipayAutoUpdate()
                                } else {
                                    viewModel.openAccessibilitySettings()
                                    scope.launch { snackbarHostState.showSnackbar(accessibilityHint) }
                                }
                            },
                            onReadCurrentPage = viewModel::readCurrentPage,
                            onHide = onHide
                        )
                    }
                }
            }
        }
    }

    // 待询问的疑似重复：一次只显示一组，处理完自动切换到下一组
    val currentDuplicate = duplicateGroups.firstOrNull()
    if (currentDuplicate != null) {
        DuplicateDialog(
            group = currentDuplicate,
            remaining = duplicateGroups.size,
            resolving = resolvingDuplicate,
            onMerge = { viewModel.resolveDuplicate(currentDuplicate, DedupeChoice.MERGE) },
            onKeepBoth = { viewModel.resolveDuplicate(currentDuplicate, DedupeChoice.KEEP_BOTH) },
            onDismiss = viewModel::dismissDuplicateDialog
        )
    }
}

/** 本月收入 / 支出 / 结余汇总卡（固定展示，不参与排序与隐藏）。 */
@Composable
private fun MonthSummaryCard(totals: FlowTotals) {
    val monthTitle = DateTimes.formatMonthTitle(System.currentTimeMillis())
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = tr("%1\$s 结余", monthTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Money.format(totals.netCents),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SummaryItem(tr("收入"), Money.format(totals.incomeCents))
                SummaryItem(tr("支出"), Money.format(totals.expenseCents))
                SummaryItem(tr("笔数"), tr("%1\$d 笔", totals.count))
            }
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** 区块标题右侧的关闭按钮：隐藏该区块（汇总卡不提供此按钮）。 */
@Composable
private fun HideSectionButton(onHide: () -> Unit) {
    IconButton(onClick = onHide, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = tr("隐藏此区块"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 区块标题右侧操作区：保留原有入口，并追加关闭按钮。 */
@Composable
private fun SectionHeaderActions(onHide: () -> Unit, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        action()
        HideSectionButton(onHide)
    }
}

/** 账户余额区块：排序模式 FilterChip + 横向账户卡片。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccountsSection(
    accounts: List<Account>,
    sortMode: AccountSortMode,
    onSortModeChange: (AccountSortMode) -> Unit,
    onHide: () -> Unit
) {
    SectionCard(
        title = tr("账户余额"),
        action = {
            SectionHeaderActions(onHide = onHide) {
                Text(
                    text = tr(sortMode.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AccountSortMode.values().forEach { mode ->
                FilterChip(
                    selected = mode == sortMode,
                    onClick = { onSortModeChange(mode) },
                    label = { Text(tr(mode.label)) }
                )
            }
        }
        if (accounts.isEmpty()) {
            EmptyHint(tr("还没有账户，可在“设置 → 账户管理”里添加"))
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(accounts, key = { it.id }) { account -> AccountCard(account) }
            }
        }
    }
}

/** 单个账户卡片：名称 + 类型 + 余额（未同步时显示占位）。 */
@Composable
private fun AccountCard(account: Account) {
    Card(modifier = Modifier.width(158.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr(account.type.label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = account.balanceCents?.let { Money.format(it) } ?: tr("¥ --（未同步）"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 近 7 天收支柱状趋势；“查看趋势”进入分析页。
 *
 * 标题行没有复用 [SectionCard]：大字号下“标题 + 查看趋势 + 关闭”放在同一个固定 Row 里，
 * 标题会被挤压成逐字换行。这里改成 FlowRow，放不下时操作区整体换到下一行，标题始终可读。
 */
@Composable
private fun TrendSection(days: List<DailyTotal>, onOpenAnalysis: () -> Unit, onHide: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = tr("近 7 天收支趋势"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onOpenAnalysis,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(tr("查看趋势"))
                    }
                    HideSectionButton(onHide)
                }
            }
            if (days.isEmpty()) {
                EmptyHint(tr("还没有流水，导入短信或点“记一笔”后这里会显示趋势"))
            } else {
                val maxCents = days.flatMap { listOf(it.incomeCents, it.expenseCents) }.maxOrNull() ?: 0L
                TrendBars(days = days, maxCents = maxCents)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LegendDot(MaterialTheme.colorScheme.primary, tr("收入"))
                    LegendDot(MaterialTheme.colorScheme.error, tr("支出"))
                }
            }
        }
    }
}

/**
 * 横轴：7 天等宽列（Row + weight），标签只显示“日”（如 16），
 * 不会在窄列里被截成重复的“10月”；标签行不放进固定总高度，字体放大也不会被裁切。
 * 柱区高度随字体缩放略微放大，视觉比例与放大的标签保持一致。
 */
@Composable
private fun TrendBars(days: List<DailyTotal>, maxCents: Long) {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    val barAreaHeight = (110f * fontScale).dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEach { day ->
            TrendColumn(
                day = day,
                maxCents = maxCents,
                barAreaHeight = barAreaHeight,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TrendColumn(
    day: DailyTotal,
    maxCents: Long,
    barAreaHeight: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barAreaHeight),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                TrendBar(
                    value = day.incomeCents,
                    maxValue = maxCents,
                    color = MaterialTheme.colorScheme.primary,
                    maxHeight = barAreaHeight
                )
                Spacer(Modifier.width(2.dp))
                TrendBar(
                    value = day.expenseCents,
                    maxValue = maxCents,
                    color = MaterialTheme.colorScheme.error,
                    maxHeight = barAreaHeight
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = DateTimes.formatDayNumber(day.dayStartMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TrendBar(value: Long, maxValue: Long, color: Color, maxHeight: Dp) {
    val ratio = if (maxValue <= 0L) 0f else (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
    val barHeight = (maxHeight * ratio).coerceAtLeast(if (value > 0L) 3.dp else 0.dp)
    Box(
        modifier = Modifier
            .width(7.dp)
            .height(barHeight)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .background(color)
    )
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 最近流水：非简易模式显示最近 8 条，可跳转明细页。 */
@Composable
private fun RecentSection(
    recent: List<TransactionRecord>,
    onOpenTransactions: () -> Unit,
    onHide: () -> Unit
) {
    SectionCard(
        title = tr("最近流水"),
        action = {
            SectionHeaderActions(onHide = onHide) {
                TextButton(onClick = onOpenTransactions) { Text(tr("查看全部")) }
            }
        }
    ) {
        if (recent.isEmpty()) {
            EmptyHint(tr("还没有流水，点“记一笔”开始记账"))
        } else {
            recent.forEach { record -> RecentRow(record) }
        }
    }
}

/** 快捷操作：记一笔 / 一键核验 / 生成报表 / 扫描短信；信任模式额外提供自动更新入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActionsCard(
    scanning: Boolean,
    verifying: Boolean,
    trustMode: Boolean,
    autoRunning: Boolean,
    onAddTransaction: () -> Unit,
    onScan: () -> Unit,
    onVerify: () -> Unit,
    onOpenReports: (ReportPeriod) -> Unit,
    onAutoUpdate: () -> Unit,
    onAlipayUpdate: () -> Unit,
    onReadCurrentPage: () -> Unit,
    onHide: () -> Unit
) {
    val scanningLabel = if (scanning) tr("扫描中…") else tr("立即扫描短信")
    SectionCard(
        title = tr("快捷操作"),
        action = { SectionHeaderActions(onHide = onHide) {} }
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onAddTransaction) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("记一笔"))
            }
            OutlinedButton(onClick = onVerify, enabled = !verifying) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (verifying) tr("核验中…") else tr("一键核验"))
            }
            ReportPeriod.values().forEach { period ->
                OutlinedButton(onClick = { onOpenReports(period) }) {
                    Text(tr("生成%1\$s", tr(period.label)))
                }
            }
            OutlinedButton(onClick = onScan, enabled = !scanning) {
                Text(scanningLabel)
            }
        }
        if (trustMode) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onAutoUpdate, enabled = !autoRunning) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (autoRunning) tr("自动更新中…") else tr("自动更新"))
                }
                OutlinedButton(onClick = onAlipayUpdate, enabled = !autoRunning) {
                    Text(tr("支付宝余额"))
                }
                OutlinedButton(onClick = onReadCurrentPage, enabled = !autoRunning) {
                    Text(tr("读取当前页"))
                }
            }
            EmptyHint(
                tr("自动更新会打开微信 / 支付宝账单页读取收支与余额，仅在系统已授权无障碍时生效")
            )
        }
    }
}

/** 最近流水单行。 */
@Composable
private fun RecentRow(record: TransactionRecord) {
    val tx = record.tx
    val sourceLabel = tr(tx.source.label)
    val timeText = DateTimes.formatDateTime(tx.occurredAt)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(record.categoryIcon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(record.categoryName)
                    if (tx.merchant.isNotBlank()) {
                        append(" · ")
                        append(tx.merchant)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$timeText · $sourceLabel · ${record.accountName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        AmountText(
            cents = tx.amountCents,
            expense = tx.type == TxType.EXPENSE,
            neutral = tx.type == TxType.TRANSFER
        )
    }
}

/**
 * 疑似重复确认对话框：同时列出两条流水（时间到秒、金额、商户、来源），
 * 用户选择“合并”（保留 keepId、删除 dropId）或“保留两笔”。
 */
@Composable
private fun DuplicateDialog(
    group: DuplicateGroupUi,
    remaining: Int,
    resolving: Boolean,
    onMerge: () -> Unit,
    onKeepBoth: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!resolving) onDismiss() },
        title = { Text(tr("疑似重复流水")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (remaining > 1) {
                        tr("两条记录金额相同、时间接近，共 %1\$d 组待处理：", remaining)
                    } else {
                        tr("两条记录金额相同、时间接近，请选择如何处理：")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DuplicateRecordCard(tr("记录 1（合并后保留）"), group.keep)
                DuplicateRecordCard(tr("记录 2（合并后删除）"), group.drop)
            }
        },
        confirmButton = {
            TextButton(onClick = onMerge, enabled = !resolving) { Text(tr("合并")) }
        },
        dismissButton = {
            TextButton(onClick = onKeepBoth, enabled = !resolving) { Text(tr("保留两笔")) }
        }
    )
}

@Composable
private fun DuplicateRecordCard(title: String, record: DuplicateRecordUi) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = tr("时间：%1\$s", DateTimes.formatDateTimeSeconds(record.timeMillis)),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = tr("金额：%1\$s", Money.format(record.amountCents)),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = tr("商户：%1\$s", record.merchant.ifBlank { tr("未提供") }),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = tr("来源：%1\$s", tr(record.sourceLabel)),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
