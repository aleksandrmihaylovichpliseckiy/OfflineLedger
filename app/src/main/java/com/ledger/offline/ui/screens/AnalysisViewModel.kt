package com.ledger.offline.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.CategoryTotal
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.ui.components.ChartBar
import com.ledger.offline.ui.components.ChartType
import com.ledger.offline.util.DateTimes
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 收支分析的四种趋势模式：
 * - [LAST_7_DAYS] 含今天在内的近 7 天逐日收支（**默认选中**，固定窗口，不能前后翻页）；
 * - [MONTH] 选定月的逐日收支；
 * - [YEAR] 选定年的逐月收支；
 * - [INTER_YEAR] 以选定年为结束年的近 5 年逐年收支。
 *
 * [label] 是中文原文，界面侧用 `tr(...)` 翻译。
 */
enum class TrendMode(val label: String) {
    LAST_7_DAYS("近7天"),
    MONTH("月趋势"),
    YEAR("年趋势"),
    INTER_YEAR("年际趋势")
}

/**
 * 收支分析页的完整 UI 状态。
 *
 * @param year 月趋势 / 年趋势是锚点年；年际趋势是窗口的**最后一年**
 * @param month 锚点月（1..12），年趋势与年际趋势不用
 * @param bars 与区间等长的时间桶（逐日 / 逐月 / 逐年），顺序即时间顺序
 * @param dayStarts 近 7 天模式每个桶的自然日 00:00（升序，末位是今天）；其它模式为空
 * @param simpleMode 简易模式下不统计分类结构（不画饼图）
 * @param canGoNext 是否允许切换到更晚的周期（不允许超过当前时间）
 * @param loading 首次聚合尚未完成
 * @param error 聚合抛异常，界面只提示不崩溃
 */
data class AnalysisUiState(
    val year: Int,
    val month: Int,
    val mode: TrendMode = TrendMode.LAST_7_DAYS,
    val chartType: ChartType = ChartType.BAR,
    val bars: List<ChartBar> = emptyList(),
    val dayStarts: List<Long> = emptyList(),
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L,
    val count: Int = 0,
    val expenseCategories: List<CategoryTotal> = emptyList(),
    val incomeCategories: List<CategoryTotal> = emptyList(),
    val simpleMode: Boolean = false,
    val canGoNext: Boolean = true,
    val loading: Boolean = true,
    val error: Boolean = false
) {
    /** 结余 = 收入 - 支出。 */
    val netCents: Long
        get() = incomeCents - expenseCents

    /** 区间内是否有流水。 */
    val hasData: Boolean
        get() = count > 0

    /** 年际趋势窗口的第一年（其它模式就是 [year] 本身）。 */
    val windowStartYear: Int
        get() = if (mode == TrendMode.INTER_YEAR) year - AnalysisViewModel.WINDOW_YEARS + 1 else year
}

/** 一次聚合的输入：模式 + 锚点年月 + 图表类型 + 简易模式。 */
private data class AnalysisQuery(
    val mode: TrendMode,
    val year: Int,
    val month: Int,
    val chartType: ChartType,
    val simpleMode: Boolean
)

/** 锚点年月；年 / 月一起改，避免切换月份时先出一次“年新月亮旧”的中间态。 */
private data class Anchor(val year: Int, val month: Int)

/**
 * 收支分析页的 ViewModel。
 *
 * 数据获取：用 `repos.transactions.recordsBetween(range)` 取区间内的原始流水，
 * 在内存里按自然日 / 自然月 / 自然年分桶（数据量小，比多条聚合 SQL 更灵活）；
 * 分类结构直接复用 `repos.transactions.categoryTotals(range, type)`。
 *
 * 模式 / 年月 / 图表类型 / 简易模式任一变化都会重新聚合，结果合并成一个 [AnalysisUiState]，
 * 避免界面出现半新半旧的中间态。
 */
class AnalysisViewModel(private val repos: LedgerRepos) : ViewModel() {

    private val today: LocalDate = LocalDate.now(DateTimes.zone())

    private val mode = MutableStateFlow(TrendMode.LAST_7_DAYS)
    private val chartType = MutableStateFlow(ChartType.BAR)
    private val anchor = MutableStateFlow(Anchor(year = today.year, month = today.monthValue))

    /** 简易模式直接跟随设置；开启时不查分类合计。 */
    private val simpleMode: Flow<Boolean> = repos.settings.settings
        .map { settings -> settings.simpleMode }
        .distinctUntilChanged()

    val state: StateFlow<AnalysisUiState> = combine(
        mode,
        anchor,
        chartType,
        simpleMode
    ) { currentMode, currentAnchor, currentChartType, currentSimpleMode ->
        AnalysisQuery(
            mode = currentMode,
            year = currentAnchor.year,
            month = currentAnchor.month,
            chartType = currentChartType,
            simpleMode = currentSimpleMode
        )
    }.map { query -> load(query) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AnalysisUiState(year = today.year, month = today.monthValue)
        )

    /** 切换趋势模式（近 7 天 / 月 / 年 / 年际）。 */
    fun setMode(value: TrendMode) {
        // 近 7 天默认柱状图；从其它模式进入时复位，避免沿用上一种模式的折线选择
        if (value == TrendMode.LAST_7_DAYS && mode.value != TrendMode.LAST_7_DAYS) {
            chartType.value = ChartType.BAR
        }
        mode.value = value
    }

    /** 切换图表类型（柱状图 / 折线图）。 */
    fun setChartType(value: ChartType) {
        chartType.value = value
    }

    /** 上一周期：上一月 / 上一年 / 前五年；近 7 天是固定窗口，不翻页。 */
    fun previousPeriod() {
        val current = anchor.value
        when (mode.value) {
            TrendMode.LAST_7_DAYS -> Unit

            TrendMode.MONTH -> {
                val previous = LocalDate.of(current.year, current.month, 1).minusMonths(1)
                if (previous.year < MIN_YEAR) return
                anchor.value = Anchor(year = previous.year, month = previous.monthValue)
            }

            TrendMode.YEAR -> {
                anchor.value = current.copy(year = (current.year - 1).coerceAtLeast(MIN_YEAR))
            }

            TrendMode.INTER_YEAR -> {
                anchor.value = current.copy(
                    year = (current.year - WINDOW_YEARS).coerceAtLeast(MIN_YEAR + WINDOW_YEARS - 1)
                )
            }
        }
    }

    /** 下一周期：下一月 / 下一年 / 后五年；近 7 天固定到“今天”为止，不翻页。 */
    fun nextPeriod() {
        val now = LocalDate.now(DateTimes.zone())
        val current = anchor.value
        when (mode.value) {
            TrendMode.LAST_7_DAYS -> Unit

            TrendMode.MONTH -> {
                val currentMonth = LocalDate.of(current.year, current.month, 1)
                val limit = LocalDate.of(now.year, now.monthValue, 1)
                if (!currentMonth.isBefore(limit)) return
                val next = currentMonth.plusMonths(1)
                anchor.value = Anchor(year = next.year, month = next.monthValue)
            }

            TrendMode.YEAR -> {
                if (current.year < now.year) {
                    anchor.value = current.copy(year = current.year + 1)
                }
            }

            TrendMode.INTER_YEAR -> {
                if (current.year < now.year) {
                    anchor.value = current.copy(year = (current.year + WINDOW_YEARS).coerceAtMost(now.year))
                }
            }
        }
    }

    /** 聚合一次查询；任何异常都收敛成 [AnalysisUiState.error]，界面只提示不崩溃。 */
    private suspend fun load(query: AnalysisQuery): AnalysisUiState {
        val now = LocalDate.now(DateTimes.zone())
        val safeYear = query.year.coerceAtLeast(MIN_YEAR)
        val safeMonth = query.month.coerceIn(1, 12)
        return try {
            val range = rangeOf(query.mode, safeYear, safeMonth)
            val bucketCount = when (query.mode) {
                TrendMode.LAST_7_DAYS -> LAST_DAYS
                TrendMode.MONTH -> LocalDate.of(safeYear, safeMonth, 1).lengthOfMonth()
                TrendMode.YEAR -> 12
                TrendMode.INTER_YEAR -> WINDOW_YEARS
            }
            val windowStartYear = safeYear - WINDOW_YEARS + 1
            // 近 7 天的区间起点就是第一天 00:00，按自然日逐天分桶
            val firstDay = DateTimes.localDate(range.startMillis)
            val dayStarts: List<Long> = if (query.mode == TrendMode.LAST_7_DAYS) {
                List(bucketCount) { offset ->
                    DateTimes.startOfDay(firstDay.plusDays(offset.toLong()))
                }
            } else {
                emptyList()
            }
            val incomes = LongArray(bucketCount)
            val expenses = LongArray(bucketCount)
            var count = 0
            for (record in repos.transactions.recordsBetween(range)) {
                val tx = record.tx
                val date = DateTimes.localDate(tx.occurredAt)
                val index = when (query.mode) {
                    TrendMode.LAST_7_DAYS -> ChronoUnit.DAYS.between(firstDay, date).toInt()
                    TrendMode.MONTH -> date.dayOfMonth - 1
                    TrendMode.YEAR -> date.monthValue - 1
                    TrendMode.INTER_YEAR -> date.year - windowStartYear
                }
                if (index !in 0 until bucketCount) continue
                // 转账 / 基金申赎不计入收入、支出，也不计入笔数
                when (tx.type) {
                    TxType.EXPENSE -> {
                        expenses[index] += tx.amountCents
                        count++
                    }

                    TxType.INCOME -> {
                        incomes[index] += tx.amountCents
                        count++
                    }

                    TxType.TRANSFER -> Unit
                }
            }
            val bars = List(bucketCount) { index ->
                ChartBar(incomeCents = incomes[index], expenseCents = expenses[index])
            }
            // 简易模式不统计分类结构，省掉两次聚合查询
            val expenseCategories: List<CategoryTotal> = if (query.simpleMode) {
                emptyList()
            } else {
                repos.transactions.categoryTotals(range, TxType.EXPENSE)
            }
            val incomeCategories: List<CategoryTotal> = if (query.simpleMode) {
                emptyList()
            } else {
                repos.transactions.categoryTotals(range, TxType.INCOME)
            }
            AnalysisUiState(
                year = safeYear,
                month = safeMonth,
                mode = query.mode,
                chartType = query.chartType,
                bars = bars,
                dayStarts = dayStarts,
                incomeCents = incomes.sum(),
                expenseCents = expenses.sum(),
                count = count,
                expenseCategories = expenseCategories,
                incomeCategories = incomeCategories,
                simpleMode = query.simpleMode,
                canGoNext = canGoNext(query.mode, safeYear, safeMonth, now),
                loading = false,
                error = false
            )
        } catch (e: Exception) {
            AnalysisUiState(
                year = safeYear,
                month = safeMonth,
                mode = query.mode,
                chartType = query.chartType,
                simpleMode = query.simpleMode,
                canGoNext = false,
                loading = false,
                error = true
            )
        }
    }

    /** 当前模式 + 锚点对应的查询区间（左闭右开）；近 7 天固定为含今天的 7 个自然日。 */
    private fun rangeOf(mode: TrendMode, year: Int, month: Int): TimeRange = when (mode) {
        TrendMode.LAST_7_DAYS -> DateTimes.lastDaysRange(LAST_DAYS)

        TrendMode.MONTH -> DateTimes.monthRange(DateTimes.startOfDay(LocalDate.of(year, month, 1)))

        TrendMode.YEAR -> DateTimes.yearRange(DateTimes.startOfDay(LocalDate.of(year, 1, 1)))

        TrendMode.INTER_YEAR -> TimeRange(
            startMillis = DateTimes.startOfDay(
                LocalDate.of(year - WINDOW_YEARS + 1, 1, 1)
            ),
            endMillisExclusive = DateTimes.startOfDay(LocalDate.of(year + 1, 1, 1))
        )
    }

    /** 是否还能往更晚的周期走：近 7 天固定窗口不能走，其它模式不能超过“今天”所在的月 / 年 / 年际窗口。 */
    private fun canGoNext(mode: TrendMode, year: Int, month: Int, now: LocalDate): Boolean =
        when (mode) {
            TrendMode.LAST_7_DAYS -> false
            TrendMode.MONTH -> year < now.year || (year == now.year && month < now.monthValue)
            TrendMode.YEAR -> year < now.year
            TrendMode.INTER_YEAR -> year < now.year
        }

    companion object {
        /** 年际趋势固定展示的年数。 */
        const val WINDOW_YEARS: Int = 5

        /** 近 7 天趋势固定展示的天数（含今天）。 */
        private const val LAST_DAYS: Int = 7

        /** 允许回溯的最早年份，防止连续点“上一周期”点出非法日期。 */
        private const val MIN_YEAR: Int = 1970

        fun factory(repos: LedgerRepos): ViewModelProvider.Factory = viewModelFactory {
            initializer { AnalysisViewModel(repos) }
        }
    }
}
