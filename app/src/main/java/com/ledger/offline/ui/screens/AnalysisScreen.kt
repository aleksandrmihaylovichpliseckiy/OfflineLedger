@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.ledger.offline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.offline.di.AppContainer
import com.ledger.offline.i18n.tr
import com.ledger.offline.ui.components.CategoryPieChart
import com.ledger.offline.ui.components.ChartType
import com.ledger.offline.ui.components.EmptyHint
import com.ledger.offline.ui.components.PieEntry
import com.ledger.offline.ui.components.SectionCard
import com.ledger.offline.ui.components.TrendChart
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.Money

/**
 * 收支分析页：趋势（近 7 天 / 月 / 年 / 年际）+ 柱状图 / 折线图 + 分类结构饼图。
 *
 * 数据全部来自本地 Room（`repos.transactions`），不联网；图形由
 * `ui/components/Charts.kt` 里的 Canvas 自绘组件完成。
 *
 * @param container 依赖容器（拿仓库）
 * @param snackbarHostState 宿主 Scaffold 的 Snackbar，聚合失败时提示
 * @param onBack 顶部返回按钮回调
 */
@Composable
fun AnalysisScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val viewModel: AnalysisViewModel = viewModel(
        factory = remember(container) { AnalysisViewModel.factory(container.repos) }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 标题由锚点年月拼出来，属于界面文案，统一走 tr
    val periodTitle = when (state.mode) {
        TrendMode.LAST_7_DAYS -> last7DaysTitle(state)
        TrendMode.MONTH -> tr("%1\$d年%2\$d月", state.year, state.month)
        TrendMode.YEAR -> tr("%1\$d年", state.year)
        TrendMode.INTER_YEAR -> tr("%1\$d–%2\$d 年", state.windowStartYear, state.year)
    }

    // X 轴标签：近 7 天只标“日”，逐月 / 逐年带上单位
    val labels: List<String> = when (state.mode) {
        TrendMode.LAST_7_DAYS -> {
            // 只取“日”（formatDayNumber），避免大字号 / 小屏把“M月d日”截断成重复月份
            List(state.bars.size) { index ->
                val dayStart = state.dayStarts.getOrNull(index)
                if (dayStart == null) "" else DateTimes.formatDayNumber(dayStart)
            }
        }

        TrendMode.MONTH -> {
            val result = ArrayList<String>(state.bars.size)
            for (index in state.bars.indices) {
                result.add((index + 1).toString())
            }
            result
        }

        TrendMode.YEAR -> {
            val result = ArrayList<String>(12)
            for (month in 1..12) {
                result.add(tr("%1\$d月", month))
            }
            result
        }

        TrendMode.INTER_YEAR -> {
            val result = ArrayList<String>(AnalysisViewModel.WINDOW_YEARS)
            for (offset in 0 until AnalysisViewModel.WINDOW_YEARS) {
                result.add(tr("%1\$d年", state.windowStartYear + offset))
            }
            result
        }
    }

    val errorText = tr("统计失败，请稍后重试")
    LaunchedEffect(state.error) {
        if (state.error) {
            snackbarHostState.showSnackbar(errorText)
        }
    }

    val expenseEntries = state.expenseCategories.map { category ->
        PieEntry(label = category.name, valueCents = category.totalCents, icon = category.icon)
    }
    val incomeEntries = state.incomeCategories.map { category ->
        PieEntry(label = category.name, valueCents = category.totalCents, icon = category.icon)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("收支分析")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("返回")
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AnalysisModeSelector(
                    mode = state.mode,
                    onSelect = viewModel::setMode
                )
            }
            // 近 7 天是“含今天”的固定窗口，没有上一 / 下一周期可切
            if (state.mode != TrendMode.LAST_7_DAYS) {
                item {
                    AnalysisPeriodSwitcher(
                        title = periodTitle,
                        mode = state.mode,
                        canGoNext = state.canGoNext,
                        onPrevious = viewModel::previousPeriod,
                        onNext = viewModel::nextPeriod
                    )
                }
            }
            item {
                AnalysisSummaryCard(title = periodTitle, state = state)
            }
            item {
                AnalysisTrendCard(
                    state = state,
                    labels = labels,
                    onSelectChartType = viewModel::setChartType
                )
            }
            if (state.simpleMode) {
                // 简易模式：只保留收支情况，不展示分类结构
                item {
                    SectionCard(title = tr("收支结构")) {
                        EmptyHint(tr("简易模式已关闭收支明细统计"))
                    }
                }
            } else {
                item {
                    AnalysisCategoryCard(
                        title = tr("支出结构"),
                        entries = expenseEntries,
                        otherLabel = tr("其他"),
                        emptyText = tr("当前区间没有支出")
                    )
                }
                item {
                    AnalysisCategoryCard(
                        title = tr("收入结构"),
                        entries = incomeEntries,
                        otherLabel = tr("其他"),
                        emptyText = tr("当前区间没有收入")
                    )
                }
            }
        }
    }
}

/** 近 7 天模式的标题：`近7天 · 10/16–10/22`；数据还没回来时退回纯模式名。 */
private fun last7DaysTitle(state: AnalysisUiState): String {
    val first = state.dayStarts.firstOrNull()
    val last = state.dayStarts.lastOrNull()
    if (first == null || last == null) return tr("近7天")
    return tr("近7天") + " · " + DateTimes.formatShortDay(first) + "–" + DateTimes.formatShortDay(last)
}

/** 趋势模式四选一：近 7 天 / 月趋势 / 年趋势 / 年际趋势（枚举顺序即 chip 顺序，近 7 天在最前）。 */
@Composable
private fun AnalysisModeSelector(mode: TrendMode, onSelect: (TrendMode) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TrendMode.values().forEach { item ->
            FilterChip(
                selected = item == mode,
                onClick = { onSelect(item) },
                label = { Text(tr(item.label)) }
            )
        }
    }
}

/** 周期切换：上一月 / 下一月、上一年 / 下一年、前五年 / 后五年。 */
@Composable
private fun AnalysisPeriodSwitcher(
    title: String,
    mode: TrendMode,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val previousLabel = when (mode) {
        // 近 7 天固定窗口不显示切换器，这里仅为 when 穷尽
        TrendMode.LAST_7_DAYS -> tr("近7天")
        TrendMode.MONTH -> tr("上一月")
        TrendMode.YEAR -> tr("上一年")
        TrendMode.INTER_YEAR -> tr("前五年")
    }
    val nextLabel = when (mode) {
        TrendMode.LAST_7_DAYS -> tr("近7天")
        TrendMode.MONTH -> tr("下一月")
        TrendMode.YEAR -> tr("下一年")
        TrendMode.INTER_YEAR -> tr("后五年")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onPrevious) {
            Icon(imageVector = Icons.Filled.ChevronLeft, contentDescription = previousLabel)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = nextLabel)
        }
    }
}

/** 区间汇总卡：结余 + 收入 / 支出 / 笔数。 */
@Composable
private fun AnalysisSummaryCard(title: String, state: AnalysisUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Money.format(state.netCents),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AnalysisSummaryItem(label = tr("收入"), value = Money.format(state.incomeCents))
                AnalysisSummaryItem(label = tr("支出"), value = Money.format(state.expenseCents))
                AnalysisSummaryItem(label = tr("笔数"), value = tr("%1\$d 笔", state.count))
            }
        }
    }
}

@Composable
private fun AnalysisSummaryItem(label: String, value: String) {
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

/** 趋势卡：图表类型切换 + Canvas 自绘趋势图。 */
@Composable
private fun AnalysisTrendCard(
    state: AnalysisUiState,
    labels: List<String>,
    onSelectChartType: (ChartType) -> Unit
) {
    SectionCard(title = tr("收支趋势")) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ChartType.values().forEach { type ->
                FilterChip(
                    selected = type == state.chartType,
                    onClick = { onSelectChartType(type) },
                    label = { Text(tr(type.label)) }
                )
            }
        }
        when {
            state.loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )

            state.error -> EmptyHint(tr("统计失败，请稍后重试"))

            !state.hasData -> EmptyHint(tr("当前区间还没有流水，记一笔后就能看到趋势"))

            else -> {
                TrendChart(bars = state.bars, labels = labels, type = state.chartType)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AnalysisLegendDot(
                        color = MaterialTheme.colorScheme.primary,
                        label = tr("收入")
                    )
                    AnalysisLegendDot(
                        color = MaterialTheme.colorScheme.error,
                        label = tr("支出")
                    )
                }
            }
        }
    }
}

/** 图表图例的小色块。 */
@Composable
private fun AnalysisLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 分类结构卡：饼图 + 图例（分类名 / 金额 / 百分比）。 */
@Composable
private fun AnalysisCategoryCard(
    title: String,
    entries: List<PieEntry>,
    otherLabel: String,
    emptyText: String
) {
    SectionCard(title = title) {
        if (entries.isEmpty()) {
            EmptyHint(emptyText)
        } else {
            CategoryPieChart(entries = entries, otherLabel = otherLabel)
        }
    }
}
