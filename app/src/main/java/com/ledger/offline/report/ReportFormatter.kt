package com.ledger.offline.report

import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.CategoryTotal
import com.ledger.offline.domain.model.FlowTotals
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.Money
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 报表正文格式化器。
 *
 * 刻意不 import 任何 android.*：输入都是纯 Kotlin 的领域模型，
 * 因此可以在普通 JVM 单元测试里直接断言正文，不需要 Robolectric。
 *
 * 正文格式（CONTRACT_V2 2.2 节）：
 * 标题 → 日期 → 收支合计 → 支出分类（占比）→ 收入分类（有收入分类才输出）
 * → 支出明细（日报 20 / 周报 30 / 月报 40 / 年报 60 条，最新在前）→ 账户余额 → 页脚。
 *
 * 简易模式（[format] 的 `simpleMode = true`）只保留标题 / 日期 / 支出 / 收入 / 净额，
 * 不输出分类、明细与账户余额（对应“简易模式只显示收支情况”）。
 *
 * 所有静态文案都走 [I18n.t]，带参数文案统一使用 `%1$s` / `%1$d` 占位符，
 * 这样后续只维护 `Translations.kt` 即可完成多语言，不需要再改报表代码。
 */
object ReportFormatter {

    /** 明细最多保留的条数：日报 20、周报 30、月报 40、年报 60。 */
    fun detailLimit(period: ReportPeriod): Int = when (period) {
        ReportPeriod.DAILY -> 20
        ReportPeriod.WEEKLY -> 30
        ReportPeriod.MONTHLY -> 40
        ReportPeriod.YEARLY -> 60
    }

    /**
     * 生成报表纯文本。
     *
     * @param period 报表周期。
     * @param range 报表覆盖的时间区间（左闭右开，本机时区自然日切分）。
     * @param totals 区间收支合计（金额单位：分）。
     * @param expenseCategories 支出分类汇总；调用方应按金额降序传入，这里仍会再排一次。
     * @param incomeCategories 收入分类汇总；为空时正文不输出【收入分类】段落。
     * @param records 区间内全部流水（用于笔数统计与支出明细），不要求有序。
     * @param accounts 全部账户（null 余额显示“未同步”）。
     * @param generatedAt 报表生成时间（毫秒），用于页脚。
     * @param simpleMode 简易模式：只输出标题 / 日期 / 支出 / 收入 / 净额。
     */
    fun format(
        period: ReportPeriod,
        range: TimeRange,
        totals: FlowTotals,
        expenseCategories: List<CategoryTotal>,
        incomeCategories: List<CategoryTotal>,
        records: List<TransactionRecord>,
        accounts: List<Account>,
        generatedAt: Long,
        simpleMode: Boolean = false
    ): String {
        val expenseCount = records.count { it.tx.type == TxType.EXPENSE }
        val incomeCount = records.count { it.tx.type == TxType.INCOME }
        val incomeCats = incomeCategories.filter { it.type == TxType.INCOME }

        val lines = mutableListOf<String>()
        lines += I18n.t("📊 自动记账 · %1\$s", I18n.t(period.label))
        lines += dateLine(period, range)
        lines += ""
        lines += I18n.t("支出  %1\$s  （%2\$d 笔）", yuan(totals.expenseCents), expenseCount)
        lines += I18n.t("收入  %1\$s  （%2\$d 笔）", yuan(totals.incomeCents), incomeCount)
        lines += netLine(totals)

        // 简易模式到这里就结束：不输出分类、明细、账户余额与页脚。
        if (simpleMode) return lines.joinToString("\n")

        lines += ""
        lines += I18n.t("【支出分类】")
        lines += categoryLines(
            categories = expenseCategories.filter { it.type == TxType.EXPENSE },
            totalCents = totals.expenseCents,
            emptyText = I18n.t("（暂无支出）")
        )
        lines += ""
        if (incomeCats.isNotEmpty()) {
            lines += I18n.t("【收入分类】")
            lines += categoryLines(
                categories = incomeCats,
                totalCents = totals.incomeCents,
                emptyText = I18n.t("（暂无收入）")
            )
            lines += ""
        }
        lines += I18n.t("【支出明细】")
        lines += detailLines(records, detailLimit(period))
        lines += ""
        lines += I18n.t("【账户余额】")
        lines += accountLines(accounts)
        lines += ""
        lines += I18n.t("—— 数据仅存本机 · 生成于 %1\$s", DateTimes.formatTime(generatedAt))
        return lines.joinToString("\n")
    }

    /**
     * 第二行日期：
     * 日报“2026-10-19 周一”、周报“2026-10-13 ~ 2026-10-19”、
     * 月报“2026年10月”、年报“2026年”。
     */
    private fun dateLine(period: ReportPeriod, range: TimeRange): String = when (period) {
        ReportPeriod.DAILY -> I18n.t(
            "%1\$s %2\$s",
            DateTimes.formatDate(range.startMillis),
            I18n.t(DateTimes.weekdayCn(range.startMillis))
        )

        ReportPeriod.WEEKLY -> {
            val lastDay = DateTimes.localDate(range.endMillisExclusive).minusDays(1)
            val lastDayMillis = DateTimes.startOfDay(lastDay)
            I18n.t(
                "%1\$s ~ %2\$s",
                DateTimes.formatDate(range.startMillis),
                DateTimes.formatDate(lastDayMillis)
            )
        }

        ReportPeriod.MONTHLY -> {
            val start = DateTimes.localDate(range.startMillis)
            I18n.t("%1\$d年%2\$d月", start.year, start.monthValue)
        }

        ReportPeriod.YEARLY ->
            I18n.t("%1\$d年", DateTimes.localDate(range.startMillis).year)
    }

    /** 净额行：支出多于收入显示“净流出”，收入多于支出显示“净流入”，刚好平衡显示“净额”。 */
    private fun netLine(totals: FlowTotals): String {
        val net = totals.netCents
        return when {
            net < 0L -> I18n.t("净流出  %1\$s", yuan(-net))
            net > 0L -> I18n.t("净流入  %1\$s", yuan(net))
            else -> I18n.t("净额  %1\$s", yuan(0L))
        }
    }

    /** 分类行：金额降序；总额为 0 时按契约省略百分比。 */
    private fun categoryLines(
        categories: List<CategoryTotal>,
        totalCents: Long,
        emptyText: String
    ): List<String> {
        if (categories.isEmpty()) return listOf(emptyText)
        return categories
            .sortedWith(compareByDescending<CategoryTotal> { it.totalCents }.thenBy { it.name })
            .map { category ->
                val base = "${category.name}  ${yuan(category.totalCents)}"
                val percent = percentText(category.totalCents, totalCents)
                if (percent == null) base else "$base  $percent"
            }
    }

    /** 百分比：四舍五入到整数；总额 <= 0 时返回 null（正文省略）。 */
    private fun percentText(partCents: Long, totalCents: Long): String? {
        if (totalCents <= 0L) return null
        val value = (partCents.toDouble() * 100.0 / totalCents.toDouble()).roundToInt()
        return "$value%"
    }

    /** 支出明细：只列支出，最新在前，最多 limit 条；商户为空时省略该列。 */
    private fun detailLines(records: List<TransactionRecord>, limit: Int): List<String> {
        val expenses = records
            .filter { it.tx.type == TxType.EXPENSE }
            .sortedByDescending { it.tx.occurredAt }
            .take(limit)
        if (expenses.isEmpty()) return listOf(I18n.t("（暂无支出明细）"))
        return expenses.map { record ->
            val parts = mutableListOf(
                DateTimes.formatTime(record.tx.occurredAt),
                record.categoryName.ifBlank { I18n.t("其他支出") }
            )
            if (record.tx.merchant.isNotBlank()) parts += record.tx.merchant
            parts += yuan(record.tx.amountCents)
            parts.joinToString("  ")
        }
    }

    /** 账户余额：无账户时写“暂无可显示余额”，余额未知的账户写“¥--（未同步）”。 */
    private fun accountLines(accounts: List<Account>): List<String> {
        if (accounts.isEmpty()) return listOf(I18n.t("暂无可显示余额"))
        return accounts
            .sortedWith(compareBy<Account>({ it.sortOrder }, { it.id }))
            .map { account ->
                val balance = account.balanceCents
                val value = if (balance == null) I18n.t("¥--（未同步）") else Money.format(balance)
                "${account.displayName}  $value"
            }
    }

    /** 正文金额：¥ + 千分位，负数取绝对值后展示（“净流出”等方向由文字表达）。 */
    private fun yuan(cents: Long): String = "¥" + Money.plain(abs(cents))
}
