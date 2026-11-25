package com.ledger.offline.report

import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.AppLanguage
import com.ledger.offline.domain.model.CategoryTotal
import com.ledger.offline.domain.model.FlowTotals
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n
import com.ledger.offline.util.DateTimes
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 报表正文格式单测：纯 JVM（JUnit4），不依赖 Android / Robolectric。
 *
 * 覆盖 CONTRACT_V2 2.2 节的关键行：标题、日期、收支/净额、分类占比、
 * 明细条数与排序、账户余额、页脚，以及“无收入 / 支出为 0 / 简易模式 / 年报 / i18n 回退中文”等边界。
 */
class ReportFormatterTest {

    @Before
    fun setUp() {
        // 用例统一锁定“简体中文”，避免系统语言 / 翻译表状态影响正文断言。
        I18n.set(AppLanguage.ZH)
    }

    @After
    fun tearDown() {
        I18n.set(AppLanguage.SYSTEM)
    }

    // ---------- 构造测试数据 ----------

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun category(
        id: Long,
        name: String,
        type: TxType,
        totalCents: Long,
        count: Int
    ): CategoryTotal = CategoryTotal(
        categoryId = id,
        name = name,
        icon = "📦",
        type = type,
        totalCents = totalCents,
        count = count
    )

    private fun expenseRecord(
        amountCents: Long,
        occurredAt: Long,
        merchant: String,
        categoryName: String = "餐饮"
    ): TransactionRecord = TransactionRecord(
        tx = Transaction(
            id = 0L,
            amountCents = amountCents,
            type = TxType.EXPENSE,
            source = TxSource.MANUAL,
            categoryId = 1L,
            accountId = 1L,
            merchant = merchant,
            occurredAt = occurredAt
        ),
        categoryName = categoryName,
        categoryIcon = "🍜",
        accountName = "现金"
    )

    private fun incomeRecord(
        amountCents: Long,
        occurredAt: Long,
        merchant: String,
        categoryName: String = "工资"
    ): TransactionRecord = TransactionRecord(
        tx = Transaction(
            id = 0L,
            amountCents = amountCents,
            type = TxType.INCOME,
            source = TxSource.MANUAL,
            categoryId = 11L,
            accountId = 1L,
            merchant = merchant,
            occurredAt = occurredAt
        ),
        categoryName = categoryName,
        categoryIcon = "💰",
        accountName = "招商银行"
    )

    private val accounts = listOf(
        Account(
            id = 1L,
            name = "招商银行",
            type = AccountType.BANK,
            cardTail = "1234",
            balanceCents = 123_456L,
            sortOrder = 0
        ),
        Account(
            id = 2L,
            name = "微信",
            type = AccountType.WECHAT,
            balanceCents = null,
            sortOrder = 1
        )
    )

    // ---------- 日报：关键行 ----------

    @Test
    fun dailyReport_containsSummaryCategoryDetailsAccountsAndFooter() {
        val dayRange = DateTimes.dayRange(at(2026, 10, 19, 12, 0))
        val text = ReportFormatter.format(
            period = ReportPeriod.DAILY,
            range = dayRange,
            totals = FlowTotals(incomeCents = 0L, expenseCents = 6_000L, count = 2),
            expenseCategories = listOf(
                category(1L, "餐饮", TxType.EXPENSE, 3_200L, 1),
                category(2L, "交通", TxType.EXPENSE, 2_800L, 1)
            ),
            incomeCategories = emptyList(),
            records = listOf(
                expenseRecord(3_200L, at(2026, 10, 19, 9, 12), "美团"),
                expenseRecord(2_800L, at(2026, 10, 19, 18, 30), "地铁", categoryName = "交通")
            ),
            accounts = accounts,
            generatedAt = at(2026, 10, 19, 21, 0)
        )

        val lines = text.lines()
        assertEquals("📊 自动记账 · 日报", lines[0])
        assertEquals("2026-10-19 周一", lines[1])
        assertTrue(text.contains("支出  ¥60.00  （2 笔）"))
        assertTrue(text.contains("收入  ¥0.00  （0 笔）"))
        assertTrue(text.contains("净流出  ¥60.00"))
        assertTrue(text.contains("【支出分类】"))
        assertTrue(text.contains("餐饮  ¥32.00  53%"))
        assertTrue(text.contains("交通  ¥28.00  47%"))
        assertFalse(text.contains("【收入分类】"))
        assertTrue(text.contains("【支出明细】"))
        assertTrue(text.contains("09:12  餐饮  美团  ¥32.00"))
        assertTrue(text.contains("18:30  交通  地铁  ¥28.00"))
        assertTrue("明细应最新在前", text.indexOf("18:30") < text.indexOf("09:12"))
        assertTrue(text.contains("【账户余额】"))
        assertTrue(text.contains("招商银行（尾号1234）  ¥1,234.56"))
        assertTrue(text.contains("微信  ¥--（未同步）"))
        assertTrue(text.endsWith("—— 数据仅存本机 · 生成于 21:00"))
    }

    // ---------- 日报：有收入 ----------

    @Test
    fun dailyReport_withIncome_showsIncomeSectionAndNetInflow() {
        val dayRange = DateTimes.dayRange(at(2026, 10, 19, 12, 0))
        val text = ReportFormatter.format(
            period = ReportPeriod.DAILY,
            range = dayRange,
            totals = FlowTotals(incomeCents = 800_000L, expenseCents = 0L, count = 1),
            expenseCategories = emptyList(),
            incomeCategories = listOf(category(11L, "工资", TxType.INCOME, 800_000L, 1)),
            records = listOf(incomeRecord(800_000L, at(2026, 10, 19, 10, 0), "公司")),
            accounts = emptyList(),
            generatedAt = at(2026, 10, 19, 21, 0)
        )

        assertTrue(text.contains("支出  ¥0.00  （0 笔）"))
        assertTrue(text.contains("收入  ¥8,000.00  （1 笔）"))
        assertTrue(text.contains("净流入  ¥8,000.00"))
        assertTrue(text.contains("（暂无支出）"))
        assertTrue(text.contains("【收入分类】"))
        assertTrue(text.contains("工资  ¥8,000.00  100%"))
        assertTrue(text.contains("（暂无支出明细）"))
        assertTrue(text.contains("暂无可显示余额"))
    }

    // ---------- 日报：明细最多 20 条、最新在前 ----------

    @Test
    fun dailyReport_limitsDetailsToTwentyNewestFirst() {
        val dayRange = DateTimes.dayRange(at(2026, 10, 19, 12, 0))
        val records = (1..25).map { index ->
            expenseRecord(
                amountCents = 100L,
                occurredAt = at(2026, 10, 19, 0, index),
                merchant = "商户$index"
            )
        }
        val text = ReportFormatter.format(
            period = ReportPeriod.DAILY,
            range = dayRange,
            totals = FlowTotals(incomeCents = 0L, expenseCents = 2_500L, count = 25),
            expenseCategories = listOf(category(1L, "餐饮", TxType.EXPENSE, 2_500L, 25)),
            incomeCategories = emptyList(),
            records = records,
            accounts = emptyList(),
            generatedAt = at(2026, 10, 19, 21, 0)
        )

        val detailBlock = text
            .substringAfter("【支出明细】\n")
            .substringBefore("\n\n【账户余额】")
        val detailLines = detailBlock.lines().filter { it.isNotBlank() }
        assertEquals(20, detailLines.size)
        assertEquals("00:25  餐饮  商户25  ¥1.00", detailLines.first())
    }

    // ---------- 周报 / 月报 / 年报日期行 ----------

    @Test
    fun weeklyMonthlyYearlyReports_useExpectedDateLines() {
        val weekRange = DateTimes.weekRange(at(2026, 10, 21, 12, 0))
        val weekly = ReportFormatter.format(
            period = ReportPeriod.WEEKLY,
            range = weekRange,
            totals = FlowTotals(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            records = emptyList(),
            accounts = emptyList(),
            generatedAt = at(2026, 10, 21, 21, 0)
        )
        assertEquals("📊 自动记账 · 周报", weekly.lines()[0])
        assertEquals("2026-10-19 ~ 2026-10-25", weekly.lines()[1])

        val monthRange = DateTimes.monthRange(at(2026, 10, 21, 12, 0))
        val monthly = ReportFormatter.format(
            period = ReportPeriod.MONTHLY,
            range = monthRange,
            totals = FlowTotals(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            records = emptyList(),
            accounts = emptyList(),
            generatedAt = at(2026, 10, 21, 21, 0)
        )
        assertEquals("📊 自动记账 · 月报", monthly.lines()[0])
        assertEquals("2026年10月", monthly.lines()[1])

        val yearRange = DateTimes.yearRange(at(2026, 6, 1, 12, 0))
        val yearly = ReportFormatter.format(
            period = ReportPeriod.YEARLY,
            range = yearRange,
            totals = FlowTotals(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            records = emptyList(),
            accounts = emptyList(),
            generatedAt = at(2026, 12, 31, 21, 0)
        )
        assertEquals("📊 自动记账 · 年报", yearly.lines()[0])
        assertEquals("2026年", yearly.lines()[1])
    }

    // ---------- 年报：明细上限 60 ----------

    @Test
    fun yearlyReport_limitsDetailsToSixty() {
        val yearRange = DateTimes.yearRange(at(2026, 6, 1, 12, 0))
        val records = (1..61).map { index ->
            expenseRecord(
                amountCents = 100L,
                occurredAt = at(2026, 1, 1, index / 60, index % 60),
                merchant = "商户$index"
            )
        }
        val text = ReportFormatter.format(
            period = ReportPeriod.YEARLY,
            range = yearRange,
            totals = FlowTotals(incomeCents = 0L, expenseCents = 6_100L, count = 61),
            expenseCategories = listOf(category(1L, "餐饮", TxType.EXPENSE, 6_100L, 61)),
            incomeCategories = emptyList(),
            records = records,
            accounts = emptyList(),
            generatedAt = at(2026, 12, 31, 21, 0)
        )

        val detailBlock = text
            .substringAfter("【支出明细】\n")
            .substringBefore("\n\n【账户余额】")
        val detailLines = detailBlock.lines().filter { it.isNotBlank() }
        assertEquals(60, detailLines.size)
        assertEquals("01:01  餐饮  商户61  ¥1.00", detailLines.first())
    }

    // ---------- 简易模式：只保留标题 / 日期 / 支出 / 收入 / 净额 ----------

    @Test
    fun simpleMode_keepsOnlySummaryAndHidesCategoriesDetailsAccountsAndFooter() {
        val dayRange = DateTimes.dayRange(at(2026, 10, 19, 12, 0))
        val text = ReportFormatter.format(
            period = ReportPeriod.DAILY,
            range = dayRange,
            totals = FlowTotals(incomeCents = 100_000L, expenseCents = 6_000L, count = 3),
            expenseCategories = listOf(category(1L, "餐饮", TxType.EXPENSE, 6_000L, 2)),
            incomeCategories = listOf(category(11L, "工资", TxType.INCOME, 100_000L, 1)),
            records = listOf(
                expenseRecord(6_000L, at(2026, 10, 19, 12, 0), "美团"),
                incomeRecord(100_000L, at(2026, 10, 19, 10, 0), "公司")
            ),
            accounts = accounts,
            generatedAt = at(2026, 10, 19, 21, 0),
            simpleMode = true
        )

        val lines = text.lines()
        assertEquals(6, lines.size)
        assertEquals("📊 自动记账 · 日报", lines[0])
        assertEquals("2026-10-19 周一", lines[1])
        assertEquals("", lines[2])
        assertTrue(lines[3].startsWith("支出  ¥60.00  （1 笔）"))
        assertTrue(lines[4].startsWith("收入  ¥1,000.00  （1 笔）"))
        assertTrue(lines[5].startsWith("净流入  ¥940.00"))
        assertFalse("简易模式不输出分类", text.contains("【支出分类】"))
        assertFalse("简易模式不输出明细", text.contains("【支出明细】"))
        assertFalse("简易模式不输出账户余额", text.contains("账户余额"))
        assertFalse("简易模式不输出页脚", text.contains("生成于"))
    }

    // ---------- 明细条数上限 ----------

    @Test
    fun detailLimit_matchesContract() {
        assertEquals(20, ReportFormatter.detailLimit(ReportPeriod.DAILY))
        assertEquals(30, ReportFormatter.detailLimit(ReportPeriod.WEEKLY))
        assertEquals(40, ReportFormatter.detailLimit(ReportPeriod.MONTHLY))
        assertEquals(60, ReportFormatter.detailLimit(ReportPeriod.YEARLY))
    }

    // ---------- 支出为 0：不显示百分比 ----------

    @Test
    fun expenseReport_withZeroExpense_omitsPercentages() {
        val dayRange = DateTimes.dayRange(at(2026, 10, 19, 12, 0))
        val text = ReportFormatter.format(
            period = ReportPeriod.DAILY,
            range = dayRange,
            totals = FlowTotals(incomeCents = 0L, expenseCents = 0L, count = 0),
            expenseCategories = listOf(category(1L, "其他支出", TxType.EXPENSE, 0L, 0)),
            incomeCategories = emptyList(),
            records = emptyList(),
            accounts = emptyList(),
            generatedAt = at(2026, 10, 19, 21, 0)
        )

        assertTrue(text.lines().contains("其他支出  ¥0.00"))
        assertFalse("支出为 0 时不应出现百分比", text.contains("其他支出  ¥0.00  "))
    }

    // ---------- i18n：缺翻译时回退中文 ----------

    @Test
    fun i18n_missingTranslationFallsBackToChinese() {
        // 报表层所有文案都以中文原文作为 I18n key；翻译表里没有对应条目时原样回退中文。
        val uniqueKey = "报表层未翻译校验键"
        assertEquals(uniqueKey, I18n.t(AppLanguage.EN, uniqueKey))
        assertEquals("报表层未翻译校验键 %1\$s".format("值"), I18n.t(AppLanguage.EN, "$uniqueKey %1\$s", "值"))
    }
}
