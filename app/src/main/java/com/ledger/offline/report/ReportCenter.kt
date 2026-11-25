package com.ledger.offline.report

import android.content.Context
import android.util.Log
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.LedgerReport
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n
import com.ledger.offline.util.DateTimes

/**
 * 报表中心：取数 → 格式化 → 入库（同一周期只留一份）→ 按需发本地通知。
 *
 * 只使用契约第 2 节列出的仓库方法；仓库的挂起函数内部已经切线程，
 * 这里不再手动 withContext，避免调用方（ViewModel / Worker / Application）嵌套调度。
 */
object ReportCenter {

    private const val TAG = "ReportCenter"

    /**
     * 生成 [period] 在 [atMillis] 所属自然周期的报表并入库。
     * 同一 (period, rangeStart) 已存在时先删除旧记录再保存，保证报表历史不重复。
     */
    suspend fun generate(period: ReportPeriod, atMillis: Long, repos: LedgerRepos): LedgerReport =
        generateForRange(period, DateTimes.rangeOf(period, atMillis), repos)

    /**
     * 按显式区间生成报表。
     *
     * 用于周报 / 月报补发：`previousRange` 算出的区间起点再传回 [DateTimes.rangeOf] 结果不变，
     * 但用显式区间更直观、也不会受“运行到第二天凌晨”的边界影响。
     */
    internal suspend fun generateForRange(
        period: ReportPeriod,
        range: TimeRange,
        repos: LedgerRepos
    ): LedgerReport {
        // 报表正文与通知文案都走 I18n.t，生成前必须先按设置刷新当前语言；
        // 同一份设置同时决定是否使用简易模式（只输出收支概况）。
        val settings = repos.settings.current()
        I18n.set(settings.language)
        val totals = repos.transactions.totals(range)
        val expenseCategories = repos.transactions.categoryTotals(range, TxType.EXPENSE)
        val incomeCategories = repos.transactions.categoryTotals(range, TxType.INCOME)
        val records = repos.transactions.recordsBetween(range)
        val accounts = repos.accounts.getAll()
        val generatedAt = System.currentTimeMillis()
        val text = ReportFormatter.format(
            period = period,
            range = range,
            totals = totals,
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            records = records,
            accounts = accounts,
            generatedAt = generatedAt,
            simpleMode = settings.simpleMode
        )
        val draft = LedgerReport(
            id = 0L,
            period = period,
            title = DateTimes.reportTitle(period, range),
            range = range,
            text = text,
            totalExpenseCents = totals.expenseCents,
            totalIncomeCents = totals.incomeCents,
            // 笔数只统计收入 / 支出；基金申赎等 TRANSFER 不计入收支笔数
            txCount = records.count { it.tx.type == TxType.EXPENSE || it.tx.type == TxType.INCOME },
            generatedAt = generatedAt
        )
        return persist(repos, draft)
    }

    /** 生成当前周期的报表并立即发本地通知（通知权限不足时静默跳过，不影响返回值）。 */
    suspend fun generateAndNotify(
        context: Context,
        period: ReportPeriod,
        repos: LedgerRepos
    ): LedgerReport {
        val report = generate(period, System.currentTimeMillis(), repos)
        ReportNotifier.notify(context, report)
        return report
    }

    /**
     * 启动补发：已过设置里的“生成时间”，且今天还没有日报存档时，补生成并通知今天的日报。
     * 其他周期不补发；整体 runCatching，启动流程永远不会因此抛异常。
     */
    suspend fun catchUpOnLaunch(context: Context, repos: LedgerRepos) {
        runCatching {
            val settings = repos.settings.current()
            // 报表通知总开关关闭时既不入库也不弹通知，避免打扰与隐私暴露。
            if (!settings.reportNotifications || !settings.dailyReportEnabled) return@runCatching
            val now = System.currentTimeMillis()
            val todayStart = DateTimes.startOfDay(now)
            val hour = settings.reportHour.coerceIn(0, 23)
            val minute = settings.reportMinute.coerceIn(0, 59)
            val deadline = DateTimes.localDate(now)
                .atTime(hour, minute)
                .atZone(DateTimes.zone())
                .toInstant()
                .toEpochMilli()
            if (now < deadline) return@runCatching
            if (repos.reports.findByPeriodAndStart(ReportPeriod.DAILY, todayStart) != null) {
                return@runCatching
            }
            generateAndNotify(context, ReportPeriod.DAILY, repos)
        }.onFailure { Log.w(TAG, "启动补发当天日报失败", it) }
    }

    /** 删除同周期旧报表再入库；即使删除失败，数据层的 REPLACE 也会覆盖旧行。 */
    private suspend fun persist(repos: LedgerRepos, draft: LedgerReport): LedgerReport {
        val existing = repos.reports.findByPeriodAndStart(draft.period, draft.range.startMillis)
        if (existing != null && existing.id > 0L) {
            runCatching { repos.reports.delete(existing.id) }
                .onFailure { Log.w(TAG, "删除旧报表失败：id=${existing.id}", it) }
        }
        val savedId = repos.reports.save(draft)
        val finalId = if (savedId > 0L) {
            savedId
        } else {
            repos.reports.findByPeriodAndStart(draft.period, draft.range.startMillis)?.id ?: 0L
        }
        return draft.copy(id = finalId)
    }
}
