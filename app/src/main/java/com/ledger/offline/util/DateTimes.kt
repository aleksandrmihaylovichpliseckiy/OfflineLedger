package com.ledger.offline.util

import com.ledger.offline.domain.model.AppLanguage
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.i18n.I18n
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 时间工具：所有报表边界都在“本机时区”的自然日 00:00 切分，
 * 区间统一为左闭右开 [startMillis, endMillisExclusive)。
 *
 * 日期格式与星期名称跟随当前界面语言（[I18n]），所以英文 / 日文 / 俄文界面不会出现中文日期。
 */
object DateTimes {

    private val weekdayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    private val shortDateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

    private val secondDateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)

    private val shortDayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d", Locale.CHINA)

    private val dayNumberFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d", Locale.CHINA)

    private fun language(): AppLanguage = I18n.resolve(I18n.current)

    private fun monthDayFormatter(): DateTimeFormatter = when (language()) {
        AppLanguage.EN -> DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        AppLanguage.RU -> DateTimeFormatter.ofPattern("d MMM", Locale("ru"))
        AppLanguage.JA, AppLanguage.ZH, AppLanguage.SYSTEM ->
            DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    }

    private fun monthTitleFormatter(): DateTimeFormatter = when (language()) {
        AppLanguage.EN -> DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
        AppLanguage.RU -> DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))
        AppLanguage.JA, AppLanguage.ZH, AppLanguage.SYSTEM ->
            DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
    }

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun localDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    fun startOfDay(millis: Long): Long =
        startOfDay(localDate(millis))

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone()).toInstant().toEpochMilli()

    fun startOfWeek(millis: Long): Long {
        val monday = localDate(millis).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return startOfDay(monday)
    }

    fun startOfMonth(millis: Long): Long {
        val first = localDate(millis).withDayOfMonth(1)
        return startOfDay(first)
    }

    fun startOfYear(millis: Long): Long {
        val first = localDate(millis).withDayOfYear(1)
        return startOfDay(first)
    }

    fun dayRange(millis: Long): TimeRange {
        val start = startOfDay(millis)
        val end = startOfDay(localDate(millis).plusDays(1))
        return TimeRange(start, end)
    }

    fun weekRange(millis: Long): TimeRange {
        val start = startOfWeek(millis)
        val end = startOfDay(localDate(start).plusWeeks(1))
        return TimeRange(start, end)
    }

    fun monthRange(millis: Long): TimeRange {
        val start = startOfMonth(millis)
        val end = startOfDay(localDate(start).plusMonths(1))
        return TimeRange(start, end)
    }

    fun yearRange(millis: Long): TimeRange {
        val start = startOfYear(millis)
        val end = startOfDay(localDate(start).plusYears(1))
        return TimeRange(start, end)
    }

    /** 当前时间点所在的日 / 周 / 月 / 年区间。 */
    fun rangeOf(period: ReportPeriod, atMillis: Long): TimeRange = when (period) {
        ReportPeriod.DAILY -> dayRange(atMillis)
        ReportPeriod.WEEKLY -> weekRange(atMillis)
        ReportPeriod.MONTHLY -> monthRange(atMillis)
        ReportPeriod.YEARLY -> yearRange(atMillis)
    }

    /** 上一个完整的日 / 周 / 月 / 年区间（报表补发用）。 */
    fun previousRange(period: ReportPeriod, atMillis: Long): TimeRange {
        val current = rangeOf(period, atMillis)
        val anchor = current.startMillis - 1L
        return rangeOf(period, anchor)
    }

    /** 最近 [days] 天（含今天）的区间。 */
    fun lastDaysRange(days: Int, atMillis: Long = System.currentTimeMillis()): TimeRange {
        val safeDays = days.coerceAtLeast(1)
        val start = startOfDay(localDate(atMillis).minusDays((safeDays - 1).toLong()))
        val end = startOfDay(localDate(atMillis).plusDays(1))
        return TimeRange(start, end)
    }

    fun todayRange(atMillis: Long = System.currentTimeMillis()): TimeRange = dayRange(atMillis)

    fun thisWeekRange(atMillis: Long = System.currentTimeMillis()): TimeRange = weekRange(atMillis)

    fun thisMonthRange(atMillis: Long = System.currentTimeMillis()): TimeRange = monthRange(atMillis)

    fun formatDate(millis: Long): String = dateFormatter.format(localDate(millis))

    fun formatMonthDay(millis: Long): String = monthDayFormatter().format(localDate(millis))

    fun formatTime(millis: Long): String =
        timeFormatter.format(Instant.ofEpochMilli(millis).atZone(zone()))

    fun formatDateTime(millis: Long): String =
        shortDateTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(zone()))

    /** 精确到秒的时间，用于展示通知 / 短信原始时间与去重核验。 */
    fun formatDateTimeSeconds(millis: Long): String =
        secondDateTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(zone()))

    /** 简短日期，如 10/16；用于近 7 天趋势等窄列标签。 */
    fun formatShortDay(millis: Long): String =
        shortDayFormatter.format(Instant.ofEpochMilli(millis).atZone(zone()))

    /** 只取“日”，如 16；大字号 / 小屏时趋势图的横轴只显示日，避免被截断。 */
    fun formatDayNumber(millis: Long): String =
        dayNumberFormatter.format(Instant.ofEpochMilli(millis).atZone(zone()))

    fun formatMonthTitle(millis: Long): String =
        monthTitleFormatter().format(localDate(millis))

    /** 年份标题：中文 / 日文为“2026年”，英文 / 俄文为“2026”。 */
    fun formatYearTitle(millis: Long): String = when (language()) {
        AppLanguage.EN, AppLanguage.RU -> localDate(millis).year.toString()
        else -> "${localDate(millis).year}年"
    }

    fun weekdayCn(millis: Long): String {
        val day = localDate(millis).dayOfWeek.value
        return I18n.t(weekdayNames[(day - 1).coerceIn(0, 6)])
    }

    /** 首页友好的日期：今天 / 昨天 / 10月19日。 */
    fun humanDay(millis: Long): String {
        val date = localDate(millis)
        val today = LocalDate.now(zone())
        return when {
            date == today -> I18n.t("今天")
            date == today.minusDays(1) -> I18n.t("昨天")
            else -> formatMonthDay(millis)
        }
    }

    /** 报表标题：日报 · 10月19日 / 周报 · 10月13日–10月19日 / 月报 · 2026年10月 / 年报 · 2026年。 */
    fun reportTitle(period: ReportPeriod, range: TimeRange): String {
        val start = localDate(range.startMillis)
        val lastDay = localDate(range.endMillisExclusive).minusDays(1)
        val dateText = when (period) {
            ReportPeriod.DAILY -> formatMonthDay(range.startMillis)
            ReportPeriod.WEEKLY ->
                "${monthDayFormatter().format(start)}–${monthDayFormatter().format(lastDay)}"
            ReportPeriod.MONTHLY -> monthTitleFormatter().format(start)
            ReportPeriod.YEARLY -> formatYearTitle(range.startMillis)
        }
        return I18n.t(period.label) + " · " + dateText
    }
}
