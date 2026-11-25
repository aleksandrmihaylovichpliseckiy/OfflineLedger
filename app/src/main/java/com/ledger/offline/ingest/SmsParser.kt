package com.ledger.offline.ingest

import com.ledger.offline.domain.model.TxType
import kotlin.math.abs

/**
 * 一条银行短信解析出的结构化数据。
 *
 * @param amountCents 交易金额（分），恒为正数，方向由 [type] 表示。
 * @param balanceCents 短信里带了“余额”时非空，否则为 null。
 * @param cardTail 银行卡尾号四位，解析不到时为空串。
 * @param merchant 短信里“商户 / 对方 / 收款方”字段，解析不到时为空串。
 * @param occurredAt 短信时间戳（毫秒），直接沿用系统收件箱的 date。
 * @param rawText 原始短信正文，入库后用于核对。
 * @param isFundPurchase 基金 / 理财申购短信的标记；是否按基金处理由入库层按设置决定。
 * @param isFundRedemption 基金 / 理财赎回短信的标记；是否按基金处理由入库层按设置决定。
 */
data class ParsedSms(
    val amountCents: Long,
    val type: TxType,
    val balanceCents: Long?,
    val cardTail: String,
    val merchant: String,
    val occurredAt: Long,
    val rawText: String,
    val isFundPurchase: Boolean = false,
    val isFundRedemption: Boolean = false
)

/**
 * 中文银行短信解析器。
 *
 * 纯 JVM 实现（不 import android.*），可在单元测试里直接运行。
 * 以下情况返回 null，由调用方计为“跳过”：
 * 1. 空短信；
 * 2. 验证码 / 校验码类短信；
 * 3. 找不到交易金额（如营销、账单提醒短信）；
 * 4. 看不出收支方向。
 *
 * 金额与余额的区分方式：先定位“余额”并记录其位置，再收集所有“人民币/¥/￥/RMB/CNY 前缀”、
 * “元 后缀”、“金额 前缀”的金额候选，剔除与余额重叠的候选后取正文中最靠前的一个。
 * 这样“消费57.00元，余额1,234.56元”不会把余额误当成本次交易金额。
 */
object SmsParser {

    /** 支出方向关键词（CONTRACT 3.2）。 */
    private val expenseKeywords = listOf(
        "消费", "支出", "付款", "支付", "转出", "取现", "扣款", "还款", "缴费"
    )

    /** 收入方向关键词（CONTRACT 3.2）。 */
    private val incomeKeywords = listOf(
        "收入", "入账", "存入", "转入", "退款", "工资", "奖金", "收款", "到账", "红包"
    )

    /** 验证码类短信不构成交易，直接忽略。 */
    private val verifyWords = listOf("验证码", "校验码", "动态密码", "动态码", "授权码", "激活码")

    /** 基金 / 理财短信的领域词：申购、赎回都要求原文同时出现其中之一（CONTRACT v1.2.1 §3.1）。 */
    private val fundWords = listOf("基金", "理财")

    /** 申购方向词：与 [fundWords] 同时命中才算基金申购。 */
    private val fundPurchaseWords = listOf("申购", "认购", "购买", "买入", "定投")

    /** 赎回方向词：与 [fundWords] 同时命中才算基金赎回。 */
    private val fundRedemptionWords = listOf("赎回", "卖出", "转出")

    /** 余额：兼容“可用余额 / 账户余额 / 当前余额 / 余额为人民币 / 余额约 / 余额（人民币）”等常见句式。 */
    private val balanceRegex = Regex(
        """(?:可用余额|账户余额|存款余额|当前余额|余额)(?:为|是|：|:|约|约为|大约)?\s*[（(]?\s*(?:人民币|RMB|CNY|¥|￥)?\s*[）)]?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    /** 带货币前缀的金额，例如“人民币56.00”“￥56.00”“RMB 56.00”。 */
    private val currencyAmountRegex = Regex(
        """(?:人民币|RMB|CNY|¥|￥)\s*(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    /** 带“元”后缀的金额，例如“56.00元”“8,000.00元”。 */
    private val yuanAmountRegex = Regex(
        """(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元"""
    )

    /** 带“金额”标签的金额，例如“交易金额 56.00”。 */
    private val labeledAmountRegex = Regex(
        """(?:交易金额|消费金额|转账金额|付款金额|入账金额|收款金额|金额)(?:为|是|：|:)?\s*(?:人民币|RMB|CNY|¥|￥)?\s*(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    /** 尾号：契约要求的两种写法。 */
    private val tailAfterWordRegex = Regex("""尾号\s*[:：]?\s*(\d{4})""")
    private val tailAfterAccountRegex = Regex("""(?:卡号|账号|账户)(?:末四位|后四位)?\s*[:：]?\s*(\d{4})""")

    /** “商户：xx / 对方：xx / 收款方：xx”字段（要求冒号，避免把普通短语当商户）。 */
    private val merchantFieldRegex = Regex(
        """(?:商户名称|收款账户|收款方|收款人|对方户名|对方账户|商户|对方)\s*[：:]\s*([^\s，,。；;：:]{1,30})"""
    )

    /** “在美团消费”这种句式里的商户名。 */
    private val merchantAfterZaiRegex = Regex(
        """在\s*([^\s，,。；;：:（）()]{2,20}?)\s*(?:消费|支付|付款|扣款)"""
    )

    /**
     * 解析一条银行短信。
     *
     * @param body 短信正文。
     * @param address 发送方号码（解析本身不需要，保留是为了与契约签名一致）。
     * @param dateMillis 短信时间戳（毫秒）。
     * @return 解析结果；不是交易短信时返回 null。
     */
    @Suppress("UNUSED_PARAMETER")
    fun parse(body: String, address: String, dateMillis: Long): ParsedSms? {
        val text = body.trim()
        if (text.isEmpty()) return null
        if (verifyWords.any { text.contains(it) }) return null

        val balanceMatch = balanceRegex.find(text)
        val balanceRange = balanceMatch?.groups?.get(1)?.range
        val balanceCents = balanceMatch?.groupValues?.getOrNull(1)
            ?.let { ParserHelpers.toCents(it) }

        val amountCandidate = findTransactionAmount(text, balanceRange) ?: return null
        val signedCents = ParserHelpers.toCents(amountCandidate.first) ?: return null
        val amountCents = abs(signedCents)
        if (amountCents <= 0L) return null

        // 部分银行用负数表示支出：有方向关键词时以关键词为准，没有且为负数时按支出处理。
        // 基金申购 / 赎回短信经常没有“消费 / 到账”等方向词，用基金方向兜底，避免整条短信被丢弃。
        val fundPurchase = isFundPurchase(text)
        val fundRedemption = isFundRedemption(text)
        val type = ParserHelpers.detectType(text, expenseKeywords, incomeKeywords)
            ?: when {
                signedCents < 0L -> TxType.EXPENSE
                fundPurchase -> TxType.EXPENSE
                fundRedemption -> TxType.INCOME
                else -> return null
            }

        return ParsedSms(
            amountCents = amountCents,
            type = type,
            balanceCents = balanceCents,
            cardTail = findCardTail(text),
            merchant = findMerchant(text),
            occurredAt = dateMillis,
            rawText = body,
            isFundPurchase = fundPurchase,
            isFundRedemption = fundRedemption
        )
    }

    /** 申购：原文同时包含“基金 / 理财”与“申购 / 认购 / 购买 / 买入 / 定投”。 */
    private fun isFundPurchase(text: String): Boolean =
        fundWords.any { text.contains(it) } && fundPurchaseWords.any { text.contains(it) }

    /** 赎回：原文同时包含“基金 / 理财”与“赎回 / 卖出 / 转出”。 */
    private fun isFundRedemption(text: String): Boolean =
        fundWords.any { text.contains(it) } && fundRedemptionWords.any { text.contains(it) }

    /** “可用额度 / 信用额度 / 手续费 / 服务费 / 利息”等金额不是本次交易金额，需要排除。 */
    private val nonTransactionAmountWords = listOf("额度", "手续费", "服务费", "年费", "利息", "积分")

    /** 收集正文里的金额候选，剔除与“余额”重叠、以及额度 / 手续费等非交易金额，返回最靠前的一个。 */
    private fun findTransactionAmount(text: String, balanceRange: IntRange?): Pair<String, IntRange>? {
        val candidates = ArrayList<Pair<String, IntRange>>()
        val regexes = listOf(currencyAmountRegex, yuanAmountRegex, labeledAmountRegex)
        for (regex in regexes) {
            for (match in regex.findAll(text)) {
                val group = match.groups[1] ?: continue
                val raw = group.value
                val range = group.range
                if (raw.isBlank()) continue
                if (balanceRange != null && rangesOverlap(range, balanceRange)) continue
                if (isNonTransactionAmount(text, range)) continue
                candidates.add(raw to range)
            }
        }
        return candidates.minWithOrNull(
            compareBy<Pair<String, IntRange>>({ it.second.first }, { it.second.last })
        )
    }

    /** 金额前面一小段文字里出现“额度 / 手续费”等词时，说明这不是交易金额。 */
    private fun isNonTransactionAmount(text: String, range: IntRange): Boolean {
        val start = (range.first - 8).coerceAtLeast(0)
        val prefix = text.substring(start, range.first)
        return nonTransactionAmountWords.any { prefix.contains(it) }
    }

    private fun findCardTail(text: String): String {
        tailAfterWordRegex.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        tailAfterAccountRegex.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return ""
    }

    private fun findMerchant(text: String): String {
        merchantFieldRegex.find(text)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        merchantAfterZaiRegex.find(text)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return ""
    }

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
        first.first <= second.last && second.first <= first.last
}
