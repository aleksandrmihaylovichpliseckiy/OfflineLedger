package com.ledger.offline.domain.model

/** 报表周期。 */
enum class ReportPeriod(val label: String) {
    DAILY("日报"),
    WEEKLY("周报"),
    MONTHLY("月报"),
    YEARLY("年报")
}

/** 左闭右开的时间区间，单位毫秒。 */
data class TimeRange(
    val startMillis: Long,
    val endMillisExclusive: Long
) {
    val durationMillis: Long
        get() = (endMillisExclusive - startMillis).coerceAtLeast(0L)
}

/** 区间汇总。 */
data class FlowTotals(
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L,
    val count: Int = 0
) {
    val netCents: Long
        get() = incomeCents - expenseCents
}

/** 按分类汇总。 */
data class CategoryTotal(
    val categoryId: Long,
    val name: String,
    val icon: String,
    val type: TxType,
    val totalCents: Long,
    val count: Int
)

/** 按来源汇总。 */
data class SourceTotal(
    val source: TxSource,
    val totalCents: Long,
    val count: Int
)

/** 按天汇总，用于近 7 天趋势图。 */
data class DailyTotal(
    val dayStartMillis: Long,
    val incomeCents: Long,
    val expenseCents: Long
)

/**
 * 生成好的报表。text 是可直接复制到剪贴板的纯文本。
 *
 * @param id 入库后由数据层回填，0 表示尚未入库。
 * @param range 报表覆盖的时间区间。
 * @param title 例如“日报 · 10月19日”。
 */
data class LedgerReport(
    val id: Long = 0L,
    val period: ReportPeriod,
    val title: String,
    val range: TimeRange,
    val text: String,
    val totalExpenseCents: Long,
    val totalIncomeCents: Long,
    val txCount: Int,
    val generatedAt: Long
)
