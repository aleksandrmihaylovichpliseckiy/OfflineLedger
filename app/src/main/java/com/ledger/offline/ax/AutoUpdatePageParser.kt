package com.ledger.offline.ax

import com.ledger.offline.domain.model.TxType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 微信 / 支付宝页面文本解析器。
 *
 * 输入是无障碍服务收集到的“当前窗口可见文本行”，输出可入库的流水行与余额。
 * 纯 JVM 实现（不 import android.*），方便在 JVM 单元测试里直接构造文本验证。
 *
 * 设计上刻意保守：
 * 1. 只把“独立成行的金额”当作交易金额，避免把说明文字里的数字误判；
 * 2. 金额行附近必须有时间文本（或账单页 + 明确的“收入/支出”标签），否则不解析；
 * 3. 余额标签（零钱 / 余额）附近的行会被排除，不当作交易；
 * 4. 页面不像账单页时，Controller 不会把结果写库，避免聊天列表里的金额被误记。
 */
internal object AutoUpdatePageParser {

    /** 从页面里解析出的一笔流水。 */
    data class ParsedRow(
        val amountCents: Long,
        val type: TxType,
        val merchant: String,
        val occurredAt: Long,
        /** 用于 sourceRef 去重的“行文本”（商户 | 金额 | 时间）。 */
        val rawText: String
    )

    /** 一页的解析结果。 */
    data class PageResult(
        val rows: List<ParsedRow>,
        val balanceCents: Long?,
        val looksLikeBillPage: Boolean
    )

    private data class BalanceHit(val cents: Long, val indexes: Set<Int>)

    private const val ONE_HOUR_MS = 3_600_000L

    /** 收入方向关键词。 */
    private val incomeKeywords = listOf(
        "收入", "收款", "退款", "红包", "转入", "到账", "入账", "收到", "领取", "退回", "奖金", "报销"
    )

    /** 支出方向关键词。 */
    private val expenseKeywords = listOf(
        "转出", "支付", "消费", "付款", "支出", "扣款", "还款", "缴费", "转给", "提现", "购买"
    )

    /** 品牌词不是收支方向，判断前先屏蔽，避免“微信支付 / 支付宝”干扰。 */
    private val brandWords = listOf("微信支付", "支付宝", "微信")

    /** 明显不是商户名的 UI 文案（精确匹配）。 */
    private val genericLineWords = setOf(
        "全部", "全部账单", "账单", "账单详情", "交易记录", "账单明细", "收支明细", "收支统计",
        "筛选", "统计", "收入", "支出", "不计收支", "其他", "交易成功", "支付成功",
        "退款成功", "已完成", "已关闭", "等待付款", "更多", "查看全部", "本月", "上月",
        "今天", "昨天", "前天", "微信支付", "支付宝", "微信", "商户", "金额", "时间",
        "当前余额", "账户余额", "余额", "零钱", "零钱通", "银行卡", "搜索",
        "本月支出", "本月收入", "近30天", "合计", "总计", "账单汇总"
    )

    /** 汇总 / 统计类文案，附近的金额不是单笔流水。 */
    private val summaryWords = listOf(
        "本月支出", "本月收入", "近30天", "收支统计", "合计", "总计", "笔数", "账单汇总"
    )

    /** 独立成行的金额：-56.00 / ¥56.00 / 56.00 / +8.88 / 1,234.56 / 56.00元。 */
    private val amountLineRegex = Regex("""[+\-]?\d+(?:,\d{3})*(?:\.\d{1,2})?""")

    /** 去掉符号与逗号后的纯数字。 */
    private val plainAmountRegex = Regex("""\d+(?:\.\d{1,2})?""")

    /** 余额行里“标签 + 金额”的匹配。 */
    private val inlineNumberRegex = Regex("""([+\-]?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    /** 2026年10月19日 12:30 / 2026-10-19 12:30。 */
    private val fullDateTimeRegex =
        Regex("""(\d{4})[年\-/.](\d{1,2})[月\-/.](\d{1,2})日?\s*(\d{1,2}):(\d{2})""")

    /** 今天 12:30 / 昨天 09:05。 */
    private val relativeDateTimeRegex = Regex("""(今天|昨天|前天)\s*(\d{1,2}):(\d{2})""")

    /** 10月19日 12:30。 */
    private val monthDayTimeRegex = Regex("""(?<!\d)(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2})""")

    /** 10-19 12:30（页面省略年份）。 */
    private val shortDateTimeRegex = Regex("""(?<!\d)(\d{1,2})[-/](\d{1,2})\s+(\d{1,2}):(\d{2})""")

    /** 只有时间 12:30。 */
    private val clockRegex = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)""")

    /** 只有“今天 / 昨天 / 前天”。 */
    private val relativeDayOnlyRegex = Regex("""^(今天|昨天|前天)$""")

    /** 2026年10月19日（没有时间）。 */
    private val fullDateOnlyRegex = Regex("""(\d{4})[年\-/.](\d{1,2})[月\-/.](\d{1,2})日?""")

    /** 10月19日（没有时间）。 */
    private val monthDayOnlyRegex = Regex("""(?<!\d)(\d{1,2})月(\d{1,2})日""")

    /**
     * 解析一页文本。
     *
     * @param target 当前页面所属应用（决定余额标签是“零钱”还是“余额”）。
     * @param lines 无障碍服务读到的文本行，顺序即页面显示顺序。
     * @param now 当前时间，用于把“今天 HH:mm”换算成时间戳；测试可注入。
     */
    fun parse(
        target: AutoUpdateTarget,
        lines: List<String>,
        now: Long = System.currentTimeMillis()
    ): PageResult {
        val cleaned = normalize(lines)
        val balanceHit = findBalanceHit(target, cleaned)
        val billPage = looksLikeBillPage(cleaned, now)
        val rows = ArrayList<ParsedRow>()

        for (index in cleaned.indices) {
            if (balanceHit?.indexes?.contains(index) == true) continue
            val amountLine = cleaned[index]
            // 年份标题（如“2026”）不是金额。
            val possibleYear = amountLine.toIntOrNull()
            if (amountLine.length == 4 && possibleYear != null && possibleYear in 1900..2100) {
                continue
            }
            val signedCents = parseAmountLine(amountLine) ?: continue
            if (signedCents == 0L) continue
            if (isSummaryAmount(cleaned, index)) continue

            val timeIndex = findTimeIndex(cleaned, index)
            val merchantIndex = findMerchantIndex(cleaned, index, timeIndex)
            val merchant = merchantIndex?.let { cleaned[it] }.orEmpty()
            if (timeIndex == null && !(billPage && hasDirectionLabelNear(cleaned, index))) continue

            val contextStart = when {
                merchantIndex == null -> index
                merchantIndex > index -> index
                else -> merchantIndex
            }
            val contextEnd = maxOf(index, timeIndex ?: index)
            val context = cleaned.subList(contextStart, contextEnd + 1).joinToString(" ")
            val type = detectDirection(amountLine, signedCents, context) ?: continue

            val timeText = timeIndex?.let { cleaned[it] }.orEmpty()
            val occurredAt = timeIndex?.let { parseTimeText(cleaned[it], now) } ?: now
            val rawText = buildRawText(merchant, amountLine, timeText)
            rows.add(
                ParsedRow(
                    amountCents = absCents(signedCents),
                    type = type,
                    merchant = merchant,
                    occurredAt = occurredAt,
                    rawText = rawText
                )
            )
        }
        return PageResult(rows = rows, balanceCents = balanceHit?.cents, looksLikeBillPage = billPage)
    }

    /** 只读取余额：微信钱包页“零钱”附近、支付宝“我的”页“余额”附近。 */
    fun findBalance(target: AutoUpdateTarget, lines: List<String>): Long? =
        findBalanceHit(target, normalize(lines))?.cents

    /**
     * 页面是否像账单页。
     *
     * 要求同时满足“有账单标题”与“至少一行时间文本”，
     * 这样微信钱包页虽然也有“账单”入口，但因为没有交易时间，不会被误认成账单页。
     */
    fun looksLikeBillPage(lines: List<String>, now: Long = System.currentTimeMillis()): Boolean {
        val cleaned = normalize(lines)
        val hasTitle = cleaned.any { line ->
            val compact = line.replace(" ", "")
            compact == "账单" || compact == "账单详情" || compact == "交易记录" ||
                compact == "账单明细" || compact == "收支明细" || compact == "交易详情" ||
                compact.contains("全部账单")
        }
        if (!hasTitle) return false
        if (cleaned.any { parseTimeText(it, now) != null }) return true
        // 空账单页没有交易时间，但会显示“暂无账单”之类的空状态。
        val emptyHints = listOf("暂无账单", "没有账单", "无交易记录", "暂无交易", "还没有账单", "暂无流水")
        return cleaned.any { line -> emptyHints.any { line.contains(it) } }
    }

    /** 把“-56.00 / ¥56.00 / 56.00 / +8.88 / 1,234.56 / 56.00元”换算成分（带符号）。 */
    fun parseAmountLine(text: String): Long? {
        val compact = text.trim()
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace("−", "-")
            .replace("－", "-")
            .replace("＋", "+")
            .removeSuffix("元")
        if (compact.isEmpty()) return null
        if (!amountLineRegex.matches(compact)) return null
        return parseAmountToken(compact)
    }

    /**
     * 把页面上的时间文本换算成毫秒时间戳。
     *
     * 支持“今天 12:30 / 昨天 12:30 / 10月19日 12:30 / 12:30 / 2026-10-19 12:30”，
     * 只有日期没有时间时按当天 00:00（整日）或中午处理；完全识别不了返回 null。
     */
    fun parseTimeText(text: String, now: Long = System.currentTimeMillis()): Long? {
        if (text.isBlank()) return null
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val fullDateTime = fullDateTimeRegex.find(text)
        if (fullDateTime != null) {
            val date = safeDate(
                fullDateTime.groupValues[1],
                fullDateTime.groupValues[2],
                fullDateTime.groupValues[3]
            )
            val time = safeTime(fullDateTime.groupValues[4], fullDateTime.groupValues[5])
            if (date != null && time != null) return toMillis(date, time, zone)
        }

        val relative = relativeDateTimeRegex.find(text)
        if (relative != null) {
            val date = relativeDate(today, relative.groupValues[1])
            val time = safeTime(relative.groupValues[2], relative.groupValues[3])
            if (date != null && time != null) return toMillis(date, time, zone)
        }

        val monthDayTime = monthDayTimeRegex.find(text)
        if (monthDayTime != null) {
            val date = fitYear(today, monthDayTime.groupValues[1], monthDayTime.groupValues[2])
            val time = safeTime(monthDayTime.groupValues[3], monthDayTime.groupValues[4])
            if (date != null && time != null) return toMillis(date, time, zone)
        }

        val shortDateTime = shortDateTimeRegex.find(text)
        if (shortDateTime != null) {
            val date = fitYear(today, shortDateTime.groupValues[1], shortDateTime.groupValues[2])
            val time = safeTime(shortDateTime.groupValues[3], shortDateTime.groupValues[4])
            if (date != null && time != null) return toMillis(date, time, zone)
        }

        val clock = clockRegex.find(text)
        if (clock != null) {
            val time = safeTime(clock.groupValues[1], clock.groupValues[2])
            if (time != null) {
                val candidate = LocalDateTime.of(today, time)
                // 没有日期时，晚于当前时间一小时以上的时间按昨天处理（例如凌晨看 23:00 的账）。
                val adjusted = if (candidate.atZone(zone).toInstant().toEpochMilli() > now + ONE_HOUR_MS) {
                    candidate.minusDays(1)
                } else {
                    candidate
                }
                return adjusted.atZone(zone).toInstant().toEpochMilli()
            }
        }

        val relativeDayOnly = relativeDayOnlyRegex.find(text)
        if (relativeDayOnly != null) {
            val date = relativeDate(today, relativeDayOnly.groupValues[1])
            if (date != null) {
                val time = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
                return toMillis(date, time, zone)
            }
        }

        val fullDateOnly = fullDateOnlyRegex.find(text)
        if (fullDateOnly != null) {
            val date = safeDate(
                fullDateOnly.groupValues[1],
                fullDateOnly.groupValues[2],
                fullDateOnly.groupValues[3]
            )
            if (date != null) return toMillis(date, LocalTime.MIDNIGHT, zone)
        }

        val monthDayOnly = monthDayOnlyRegex.find(text)
        if (monthDayOnly != null) {
            val date = fitYear(today, monthDayOnly.groupValues[1], monthDayOnly.groupValues[2])
            if (date != null) return toMillis(date, LocalTime.NOON, zone)
        }
        return null
    }

    // ------------------------------------------------------------------ 余额

    private fun findBalanceHit(target: AutoUpdateTarget, lines: List<String>): BalanceHit? {
        for (index in lines.indices) {
            if (!isBalanceLabel(lines[index], target)) continue
            val label = if (target == AutoUpdateTarget.WECHAT) "零钱" else "余额"
            val inline = extractInlineAmount(lines[index], label)
            if (inline != null) return BalanceHit(inline, setOf(index))

            // 标签与金额分处两行：向后最多看 3 行，遇到新的余额标签 / 长说明文字就停。
            val nextEnd = minOf(index + 3, lines.lastIndex)
            for (next in index + 1..nextEnd) {
                val value = parseAmountLine(lines[next])
                if (value != null) return BalanceHit(absCents(value), setOf(index, next))
                if (isBalanceLabel(lines[next], target)) break
                if (lines[next].length > 16) break
            }

            // 少数版本金额在标签上方。
            val prevStart = maxOf(index - 2, 0)
            for (prev in index - 1 downTo prevStart) {
                val value = parseAmountLine(lines[prev]) ?: continue
                return BalanceHit(absCents(value), setOf(index, prev))
            }
        }
        return null
    }

    /** 微信只认“零钱”，排除“零钱通”；支付宝只认“余额”，排除“余额宝”。 */
    private fun isBalanceLabel(line: String, target: AutoUpdateTarget): Boolean {
        val compact = line.replace(" ", "")
        if (compact.isEmpty() || compact.length > 12) return false
        return when (target) {
            AutoUpdateTarget.WECHAT ->
                compact == "零钱" || (compact.startsWith("零钱") && !compact.contains("零钱通"))
            AutoUpdateTarget.ALIPAY ->
                compact.contains("余额") && !compact.contains("余额宝")
        }
    }

    /** 同一行里“零钱 1,234.56 / 余额：￥100.00”这种写法。 */
    private fun extractInlineAmount(line: String, label: String): Long? {
        val index = line.indexOf(label)
        if (index < 0) return null
        val after = line.substring(index + label.length)
        val match = inlineNumberRegex.find(after) ?: return null
        val sign = match.groupValues[1]
        val number = match.groupValues[2]
        if (number.count { it.isDigit() } > 12) return null
        val hasSeparator = number.contains('.') || number.contains(',')
        // 四位数且无小数点 / 千分位时多半是年份，不当余额。
        if (!hasSeparator && number.length == 4) return null
        val signed = parseAmountToken(sign + number) ?: return null
        return absCents(signed)
    }

    // ------------------------------------------------------------------ 流水行

    /**
     * 金额行附近找时间：先看距离 1 的行（下方优先），再看距离 2、3，
     * 取“最近”的时间行。
     *
     * 必须按距离取最近：支付宝部分账单行是“商户 / 时间 / 金额”，
     * 如果无脑向后找，会把下一笔的时间安到当前金额上；同理微信的
     * “商户 / 金额 / 时间”需要优先取下方邻行。
     */
    private fun findTimeIndex(lines: List<String>, amountIndex: Int): Int? {
        for (offset in 1..3) {
            val after = amountIndex + offset
            if (after <= lines.lastIndex && parseTimeText(lines[after]) != null) return after
            val before = amountIndex - offset
            if (before >= 0 && parseTimeText(lines[before]) != null) return before
        }
        return null
    }

    /** 商户：金额上方最近的一条非噪声文本；部分布局在金额下方（时间前后）。 */
    private fun findMerchantIndex(
        lines: List<String>,
        amountIndex: Int,
        timeIndex: Int?
    ): Int? {
        // 先向上找；遇到金额 / 时间行说明已经跨到上一笔，立即停止，避免把上一笔的商户安到这一笔上。
        val beforeStart = maxOf(amountIndex - 3, 0)
        for (index in amountIndex - 1 downTo beforeStart) {
            val line = lines[index]
            if (parseAmountLine(line) != null) break
            // 时间通常属于这一笔（例如“商户 / 时间 / 金额”布局），跳过它继续向上找商户；
            // 其它时间行则说明已经跨到上一笔，立即停止。
            if (index != timeIndex && parseTimeText(line) != null) break
            if (isMerchantCandidate(lines, index, timeIndex)) return index
        }

        if (timeIndex != null) {
            // 少数布局把商户放在金额与时间之间（金额在上、商户居中、时间在下）。
            if (timeIndex > amountIndex) {
                for (index in amountIndex + 1 until timeIndex) {
                    if (isMerchantCandidate(lines, index, timeIndex)) return index
                }
            }
            // 还有“金额 → 时间 → 商户”的布局。
            val afterEnd = minOf(timeIndex + 2, lines.lastIndex)
            for (index in timeIndex + 1..afterEnd) {
                if (isMerchantCandidate(lines, index, timeIndex)) return index
            }
        }
        return null
    }

    private fun isMerchantCandidate(lines: List<String>, index: Int, timeIndex: Int?): Boolean {
        if (index == timeIndex) return false
        val line = lines[index]
        if (parseTimeText(line) != null) return false
        if (parseAmountLine(line) != null) return false
        if (isNoiseLine(line)) return false
        return true
    }

    private fun isNoiseLine(line: String): Boolean {
        val compact = line.replace(" ", "")
        if (compact.isEmpty()) return true
        if (compact.length > 30) return true
        if (compact in genericLineWords) return true
        if (amountLineRegex.matches(compact)) return true
        if (compact.startsWith("共") && compact.endsWith("笔")) return true
        return false
    }

    /** 金额附近有“收入 / 支出”标签时，允许在没有时间文本的账单页里兜底用当前时间。 */
    private fun hasDirectionLabelNear(lines: List<String>, index: Int): Boolean {
        val start = maxOf(index - 2, 0)
        val end = minOf(index + 2, lines.lastIndex)
        for (current in start..end) {
            val compact = lines[current].replace(" ", "")
            if (compact == "支出" || compact == "收入") return true
        }
        return false
    }

    /** 汇总 / 统计区附近的金额不是单笔流水；余额金额由 [findBalanceHit] 单独标记并跳过。 */
    private fun isSummaryAmount(lines: List<String>, index: Int): Boolean {
        val start = maxOf(index - 2, 0)
        val end = minOf(index + 2, lines.lastIndex)
        for (current in start..end) {
            if (summaryWords.any { lines[current].contains(it) }) return true
        }
        return false
    }

    /**
     * 收支方向：负号优先判支出，显式“+”判收入；
     * 都没有时再看上下文里的收入 / 支出关键词，两者都命中的取出现更早的。
     */
    private fun detectDirection(amountLine: String, signedCents: Long, context: String): TxType? {
        if (signedCents < 0L) return TxType.EXPENSE
        val compactAmount = amountLine.replace(" ", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace("−", "-")
            .replace("－", "-")
            .replace("＋", "+")
        if (compactAmount.startsWith("+")) return TxType.INCOME

        val masked = maskBrands(context)
        val incomeIndex = earliestKeywordIndex(masked, incomeKeywords)
        val expenseIndex = earliestKeywordIndex(masked, expenseKeywords)
        return when {
            incomeIndex < 0 && expenseIndex < 0 -> null
            incomeIndex < 0 -> TxType.EXPENSE
            expenseIndex < 0 -> TxType.INCOME
            expenseIndex <= incomeIndex -> TxType.EXPENSE
            else -> TxType.INCOME
        }
    }

    private fun maskBrands(text: String): String {
        var masked = text
        brandWords.forEach { word -> masked = masked.replace(word, " ") }
        return masked
    }

    private fun earliestKeywordIndex(text: String, keywords: List<String>): Int =
        keywords.map { firstKeywordIndex(text, it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1

    /** “收款方 / 付款人 / 支付宝”里的关键词不算方向。 */
    private fun firstKeywordIndex(text: String, keyword: String): Int {
        var from = 0
        while (from <= text.length - keyword.length) {
            val index = text.indexOf(keyword, from)
            if (index < 0) return -1
            val after = index + keyword.length
            val blocked = when (keyword) {
                "收款" -> text.startsWith("方", after) ||
                    text.startsWith("人", after) ||
                    text.startsWith("账户", after)
                "付款" -> text.startsWith("方", after) || text.startsWith("人", after)
                "支付" -> text.startsWith("宝", after)
                else -> false
            }
            if (!blocked) return index
            from = after
        }
        return -1
    }

    private fun buildRawText(merchant: String, amountLine: String, timeLine: String): String =
        listOf(merchant, amountLine, timeLine)
            .filter { it.isNotBlank() }
            .joinToString(" | ")

    // ------------------------------------------------------------------ 工具

    private fun normalize(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseAmountToken(token: String): Long? {
        val cleaned = token.trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        val negative = cleaned.startsWith("-")
        val body = cleaned.removePrefix("+").removePrefix("-")
        if (body.isEmpty() || !plainAmountRegex.matches(body)) return null
        val cents = try {
            BigDecimal(body).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            return null
        } catch (e: NumberFormatException) {
            return null
        }
        if (cents == 0L) return 0L
        return if (negative) -cents else cents
    }

    private fun absCents(cents: Long): Long = if (cents < 0L) -cents else cents

    private fun safeDate(year: String, month: String, day: String): LocalDate? {
        val safeYear = year.toIntOrNull() ?: return null
        val safeMonth = month.toIntOrNull() ?: return null
        val safeDay = day.toIntOrNull() ?: return null
        return try {
            LocalDate.of(safeYear, safeMonth, safeDay)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun safeTime(hour: String, minute: String): LocalTime? {
        val safeHour = hour.toIntOrNull() ?: return null
        val safeMinute = minute.toIntOrNull() ?: return null
        return try {
            LocalTime.of(safeHour, safeMinute)
        } catch (e: RuntimeException) {
            null
        }
    }

    /** 页面省略年份时用当前年；得到的日期明显在未来时退回上一年。 */
    private fun fitYear(today: LocalDate, month: String, day: String): LocalDate? {
        val safeMonth = month.toIntOrNull() ?: return null
        val safeDay = day.toIntOrNull() ?: return null
        val date = try {
            LocalDate.of(today.year, safeMonth, safeDay)
        } catch (e: RuntimeException) {
            return null
        }
        return if (date.isAfter(today.plusDays(1))) date.minusYears(1) else date
    }

    private fun relativeDate(today: LocalDate, label: String): LocalDate? = when (label) {
        "今天" -> today
        "昨天" -> today.minusDays(1)
        "前天" -> today.minusDays(2)
        else -> null
    }

    private fun toMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
}
