package com.ledger.offline.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * 金额工具：内部一律用“分”（Long）表示，只在显示时格式化。
 */
object Money {

    /** 带货币符号与千分位，例如 “¥1,234.56”；负数显示为 “-¥1,234.56”。 */
    fun format(cents: Long): String {
        val negative = cents < 0
        val body = withSymbol(number(absCents(cents)))
        return if (negative) "-$body" else body
    }

    /** 带符号的收支金额，例如支出 “-¥12.00”、收入 “+¥12.00”。 */
    fun signed(cents: Long, expense: Boolean): String {
        val body = format(absCents(cents))
        return if (expense) "-$body" else "+$body"
    }

    /** 仅数字与千分位，例如 “1,234.56”，用于报表正文。 */
    fun plain(cents: Long): String = number(cents)

    /** 编辑框用：无千分位，例如 “1234.56”。 */
    fun editable(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** 把用户输入的 “12.5” / “12.50” / “12” 解析为分；非法输入返回 null。 */
    fun parseToCents(input: String): Long? {
        val cleaned = input.trim()
            .replace(",", "")
            .replace("，", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (!Regex("^\\d+(\\.\\d{1,2})?$").matches(cleaned)) return null
        return try {
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun absCents(cents: Long): Long = if (cents < 0) -cents else cents

    private fun withSymbol(number: String): String = "¥$number"

    private fun number(cents: Long): String {
        val bd = BigDecimal.valueOf(cents, 2)
        val nf = NumberFormat.getNumberInstance(Locale.CHINA)
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        return nf.format(bd)
    }
}
