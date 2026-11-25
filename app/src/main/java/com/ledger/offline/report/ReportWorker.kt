package com.ledger.offline.report

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.ledger.offline.LedgerApplication
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.ReportMode
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.util.DateTimes
import java.time.LocalDate

/**
 * 定时报表 Worker（由 [ReportScheduler] 每 24 小时触发一次）。
 *
 * 一次执行里按设置做四件事，每个周期独立 `runCatching`，互不牵连：
 * 1. 日报：生成“最近一个已到点的日报时刻”对应的自然日（保留原有跨日补发逻辑）；
 * 2. 周报：
 *    - 收支默认模式：[com.ledger.offline.domain.model.ReportMode.STANDARD]
 *      在 `weeklyDayOfWeek` 对应的星期几生成上一自然周；
 *    - 月结模式：[com.ledger.offline.domain.model.ReportMode.MONTHLY_SETTLEMENT]
 *      每月 8 / 15 / 22 日生成上一个 7 天区块（1–7、8–14、15–21），
 *      次月 1 日生成“上月 22 日到月末”的合并周报（把月底不足 7 天的尾巴并入）；
 * 3. 月报：`dayOfMonth == monthlyDay` 时生成上一个月；
 * 4. 年报：`month == yearlyMonth && day == yearlyDay` 时生成上一年。
 *
 * “今天”取的是最近一个应生成时刻所在的自然日：若系统把任务拖到次日凌晨才跑，
 * 仍按前一天的日期判断，避免给新的一天生成空报表，也避免错过跨日的月结区块。
 *
 * 四个周期都按“同一区间已存在则跳过”（`reports.findByPeriodAndStart`）处理，
 * 避免 WorkManager 重复执行 / 用户手动生成后再次弹同一条通知。
 * 仅 `reportNotifications = true` 时才生成并通知；任何异常都吞掉并返回
 * [ListenableWorker.Result.success]，避免 WorkManager 因失败进入重试风暴，失败只写日志。
 */
class ReportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        // 兜底：理论上 applicationContext 一定是 LedgerApplication，极端情况下也不崩。
        val app = applicationContext as? LedgerApplication ?: return ListenableWorker.Result.success()
        runCatching {
            val repos = app.container.repos
            val settings = repos.settings.current()
            if (!settings.reportNotifications) {
                Log.i(TAG, "报表通知总开关已关闭，本轮不生成")
                return@runCatching
            }
            val now = System.currentTimeMillis()
            val hour = settings.reportHour.coerceIn(0, 23)
            val minute = settings.reportMinute.coerceIn(0, 59)
            // 最近一个已到点的应生成时刻（通常是今天；被拖到凌晨时则是昨天）。
            val dueMoment = latestReportMoment(now, hour, minute)
            val dueDay = DateTimes.localDate(dueMoment)

            // 日报：沿用原有跨日补发逻辑，按应生成时刻生成对应自然日。
            if (settings.dailyReportEnabled) {
                runCatching {
                    generateIfMissing(ReportPeriod.DAILY, DateTimes.dayRange(dueMoment), repos)
                }.onFailure { Log.w(TAG, "日报生成失败，本轮跳过", it) }
            }

            // 周报：默认模式按星期几生成上一自然周；月结模式按 7 天区块生成。
            if (settings.weeklyReportEnabled) {
                runCatching {
                    val range = weeklyRange(
                        mode = settings.reportMode,
                        weeklyDayOfWeek = settings.weeklyDayOfWeek,
                        dueDay = dueDay,
                        dueMoment = dueMoment
                    )
                    if (range != null) {
                        generateIfMissing(ReportPeriod.WEEKLY, range, repos)
                    }
                }.onFailure { Log.w(TAG, "周报生成失败，本轮跳过", it) }
            }

            // 月报：每月指定日生成上一个月（自定义日期 1..28）。
            if (settings.monthlyReportEnabled) {
                runCatching {
                    val day = settings.monthlyDay.coerceIn(1, 28)
                    if (dueDay.dayOfMonth == day) {
                        val range = DateTimes.previousRange(ReportPeriod.MONTHLY, dueMoment)
                        generateIfMissing(ReportPeriod.MONTHLY, range, repos)
                    }
                }.onFailure { Log.w(TAG, "月报生成失败，本轮跳过", it) }
            }

            // 年报：每年指定月日生成上一年（自定义日期：月 1..12、日 1..28）。
            if (settings.yearlyReportEnabled) {
                runCatching {
                    val month = settings.yearlyMonth.coerceIn(1, 12)
                    val day = settings.yearlyDay.coerceIn(1, 28)
                    if (dueDay.monthValue == month && dueDay.dayOfMonth == day) {
                        val range = DateTimes.previousRange(ReportPeriod.YEARLY, dueMoment)
                        generateIfMissing(ReportPeriod.YEARLY, range, repos)
                    }
                }.onFailure { Log.w(TAG, "年报生成失败，本轮跳过", it) }
            }
        }.onFailure { Log.w(TAG, "定时报表任务初始化失败，本轮跳过", it) }
        return ListenableWorker.Result.success()
    }

    /**
     * 计算本轮应生成的周报区间；不需要生成时返回 null。
     *
     * 默认模式：只有“应生成日”的星期几等于设置值时才生成上一自然周。
     * 月结模式：8 日 → 1–7、15 日 → 8–14、22 日 → 15–21、次月 1 日 → 上月 22–月末。
     */
    private fun weeklyRange(
        mode: ReportMode,
        weeklyDayOfWeek: Int,
        dueDay: LocalDate,
        dueMoment: Long
    ): TimeRange? = when (mode) {
        ReportMode.STANDARD -> {
            val expectedDay = weeklyDayOfWeek.coerceIn(1, 7)
            if (dueDay.dayOfWeek.value == expectedDay) {
                DateTimes.previousRange(ReportPeriod.WEEKLY, dueMoment)
            } else {
                null
            }
        }

        ReportMode.MONTHLY_SETTLEMENT -> monthlySettlementRange(dueDay)
    }

    /**
     * 月结模式的 7 天区块：
     * - 8 日生成本月 1 日 ~ 7 日；
     * - 15 日生成 8 日 ~ 14 日；
     * - 22 日生成 15 日 ~ 21 日；
     * - 次月 1 日生成上月 22 日 ~ 月末（月底不足 7 天的尾巴并入本次，因此可能与前面的区块有重叠，
     *   但区间起点不同，`findByPeriodAndStart` 仍能正确区分）。
     */
    private fun monthlySettlementRange(dueDay: LocalDate): TimeRange? = when (dueDay.dayOfMonth) {
        8 -> monthBlockRange(dueDay, startDay = 1, endDayExclusive = 8)
        15 -> monthBlockRange(dueDay, startDay = 8, endDayExclusive = 15)
        22 -> monthBlockRange(dueDay, startDay = 15, endDayExclusive = 22)
        1 -> {
            // 上月 22 日起，到本月 1 日 00:00 结束（左闭右开）。
            val lastMonthFirst = dueDay.minusMonths(1).withDayOfMonth(1)
            val start = lastMonthFirst.plusDays(21)
            TimeRange(DateTimes.startOfDay(start), DateTimes.startOfDay(dueDay))
        }
        else -> null
    }

    /** 生成本月第 [startDay] 日 00:00 到第 [endDayExclusive] 日 00:00 的区间。 */
    private fun monthBlockRange(dueDay: LocalDate, startDay: Int, endDayExclusive: Int): TimeRange {
        val first = dueDay.withDayOfMonth(1)
        val start = first.plusDays((startDay - 1).toLong())
        val end = first.plusDays((endDayExclusive - 1).toLong())
        return TimeRange(DateTimes.startOfDay(start), DateTimes.startOfDay(end))
    }

    /** 区间已存在则跳过；否则生成报表并发送通知。 */
    private suspend fun generateIfMissing(period: ReportPeriod, range: TimeRange, repos: LedgerRepos) {
        val existing = repos.reports.findByPeriodAndStart(period, range.startMillis)
        if (existing != null) {
            Log.i(TAG, "${period.label}区间已存在（start=${range.startMillis}），跳过")
            return
        }
        val report = ReportCenter.generateForRange(period, range, repos)
        ReportNotifier.notify(applicationContext, report)
    }

    /** 最近的 reportHour:reportMinute 时刻；今天还没到点就用昨天同一时刻。 */
    private fun latestReportMoment(nowMillis: Long, hour: Int, minute: Int): Long {
        val todayTarget = DateTimes.localDate(nowMillis)
            .atTime(hour, minute)
            .atZone(DateTimes.zone())
            .toInstant()
            .toEpochMilli()
        return if (todayTarget <= nowMillis) todayTarget else todayTarget - DAY_MILLIS
    }

    companion object {
        private const val TAG = "ReportWorker"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
