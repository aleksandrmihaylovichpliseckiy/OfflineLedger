package com.ledger.offline.ingest

import com.ledger.offline.domain.model.TxType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 解析器的公共工具：金额文本换算与方向关键词定位。
 *
 * 这里刻意不依赖任何 Android API（不 import android.*），
 * 供 [SmsParser] 与 [NotificationParser] 复用，也保证可以直接在 JVM 单元测试里运行。
 */
internal object ParserHelpers {

    private val amountPattern = Regex("""^-?\d+(?:\.\d{1,2})?$""")

    /**
     * 把金额文本换算成“分”。
     *
     * 兼容：千分位逗号（“1,234.56”）、中文逗号、最多两位小数、可选负号（“-56.00”）。
     * 非法文本返回 null，不抛异常。
     */
    fun toCents(raw: String): Long? {
        val cleaned = raw.trim().replace(",", "").replace("，", "")
        if (cleaned.isEmpty() || !amountPattern.matches(cleaned)) return null
        return try {
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * 部分关键词出现在称谓里并不表示收支方向，例如“收款方”“付款人”“支付宝”，
     * 命中这些后缀时跳过该次出现，继续找下一次。
     */
    private val skipSuffixes: Map<String, List<String>> = mapOf(
        "收款" to listOf("方", "人", "账户"),
        "付款" to listOf("方", "人"),
        "支付" to listOf("宝")
    )

    /** 关键词在文本中第一次“有效”出现的下标；找不到返回 -1。 */
    fun firstKeywordIndex(text: String, keyword: String): Int {
        if (keyword.isEmpty()) return -1
        val suffixes = skipSuffixes[keyword].orEmpty()
        var from = 0
        while (from <= text.length - keyword.length) {
            val index = text.indexOf(keyword, from)
            if (index < 0) return -1
            val after = index + keyword.length
            val blocked = suffixes.any { text.startsWith(it, after) }
            if (!blocked) return index
            from = after
        }
        return -1
    }

    /** 多个关键词中最早出现的下标；都没有出现返回 -1。 */
    fun earliestKeywordIndex(text: String, keywords: List<String>): Int =
        keywords.map { firstKeywordIndex(text, it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1

    /**
     * 收支方向判断：支出 / 收入关键词都命中时，以出现位置更靠前的为准；
     * 两者都没命中返回 null，由调用方决定跳过该条。
     */
    fun detectType(text: String, expenseKeywords: List<String>, incomeKeywords: List<String>): TxType? {
        val expenseIndex = earliestKeywordIndex(text, expenseKeywords)
        val incomeIndex = earliestKeywordIndex(text, incomeKeywords)
        return when {
            expenseIndex < 0 && incomeIndex < 0 -> null
            incomeIndex < 0 -> TxType.EXPENSE
            expenseIndex < 0 -> TxType.INCOME
            expenseIndex <= incomeIndex -> TxType.EXPENSE
            else -> TxType.INCOME
        }
    }
}
