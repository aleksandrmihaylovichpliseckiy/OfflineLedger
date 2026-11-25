package com.ledger.offline.ingest

import com.ledger.offline.domain.model.NotificationKind
import com.ledger.offline.domain.model.TxType

/** 微信包名：沿用官方支付通知的标题校验规则。 */
internal const val WECHAT_PACKAGE = "com.tencent.mm"

/** 支付宝包名：沿用官方支付通知的标题校验规则。 */
internal const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

/**
 * 一条支付通知解析出的结构化数据。
 *
 * @param amountCents 支付金额（分），恒为正数，方向由 [type] 表示。
 * @param merchant 尽量取“收款方 / 商户 / 付款给 / 转账给”后面的名字，否则用“微信支付 / 支付宝”；
 *   微信 / 支付宝之外的包留空，由入库层按应用名 / 包名兜底。
 * @param rawText 标题 + 正文的原文，仅本地留存用于核对。
 * @param kind 通知文本的语义类型（支出 / 收入 / 退款 / 转账 / 其他），用于按类型过滤。
 */
data class ParsedNotification(
    val amountCents: Long,
    val type: TxType,
    val merchant: String,
    val rawText: String,
    val kind: NotificationKind = NotificationKind.OTHER
)

/**
 * 支付通知解析器：微信 / 支付宝走官方规则，其它应用走“金额 + 交易关键词”的通用规则。
 *
 * 纯 JVM 实现（不 import android.*），可在单元测试里直接运行。
 * 聊天消息、群消息、验证码、没有金额、或看不出交易语义的通知一律返回 null。
 *
 * 注意：通知标题里的“微信支付 / 支付宝 / 微信”是品牌词，本身不表示收支方向，
 * 因此判断方向前会先把品牌词屏蔽，避免把“收款到账”误判成支出。
 */
object NotificationParser {

    /** 支出方向关键词（短信方向的超集：“转给”用于转账通知）。 */
    private val expenseKeywords = listOf(
        "消费", "支出", "付款", "支付", "转出", "取现", "扣款", "还款", "缴费", "转给"
    )

    /** 收入方向关键词（短信方向的超集：补充“收到 / 领取 / 退回”）。 */
    private val incomeKeywords = listOf(
        "收入", "入账", "存入", "转入", "退款", "工资", "奖金", "收款", "到账", "红包",
        "收到", "领取", "退回"
    )

    /** 验证码类通知不是交易。 */
    private val verifyWords = listOf("验证码", "校验码", "动态密码", "动态码", "授权码", "激活码")

    /** 品牌词会在判断方向前被屏蔽。 */
    private val brandWords = listOf("微信支付", "支付宝", "微信")

    /**
     * 只信任“官方支付类”通知标题：微信支付 / 支付宝 / 服务通知 / 账单等。
     * 联系人昵称、群名作为标题的聊天通知直接忽略，避免“给我转账100元”被记账。
     * 该规则只用于微信 / 支付宝；其它应用不强制官方标题（见 [parse]）。
     */
    private val trustedTitleHints = listOf(
        "微信支付", "支付宝", "服务通知", "账单", "支付", "收款", "到账",
        "转账", "退款", "交易", "红包", "凭证"
    )

    /** “像不像交易通知”的关键词，用于挡掉普通聊天里的金额；任意应用都要求命中其一。 */
    private val transactionHints = listOf(
        "支付", "付款", "收款", "到账", "退款", "转账", "转帐", "红包", "消费", "支出",
        "收入", "扣款", "还款", "缴费", "入账", "存入", "转入", "转出", "交易", "账单", "领取",
        "凭证"
    )

    /** 转账通知里表示“钱进来了”的提示。 */
    private val incomingHints = listOf("收到", "向你", "向您", "给你", "给您", "转入", "到账", "收款")

    /** 通知语义：退款最具体，优先判定。 */
    private val refundKindWords = listOf("退款")

    /** 通知语义：收入类。 */
    private val incomeKindWords = listOf("收款", "到账", "收入", "红包", "入账")

    /** 通知语义：转账类（“转帐”是常见异体写法）。 */
    private val transferKindWords = listOf("转账", "转帐")

    /** 通知语义：支出类。 */
    private val paymentKindWords = listOf("支付", "付款", "消费", "扣款")

    /** 通知里也可能带余额（例如支付宝账单通知），解析金额时同样要排除。 */
    private val balanceRegex = Regex(
        """(?:可用余额|余额)(?:为|是|：|:)?\s*(?:人民币|RMB|CNY|¥|￥)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val currencyAmountRegex = Regex(
        """(?:人民币|RMB|CNY|¥|￥)\s*(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val yuanAmountRegex = Regex(
        """(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元"""
    )

    private val labeledAmountRegex = Regex(
        """(?:交易金额|支付金额|付款金额|转账金额|收款金额|到账金额|退款金额|金额)(?:为|是|：|:)?\s*(?:人民币|RMB|CNY|¥|￥)?\s*(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    /** 契约要求兼容“-56.00”这种裸负号金额；前面是数字时不算（避免匹配日期 2026-10）。 */
    private val negativeAmountRegex = Regex("""(?<![0-9])(-[0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    /** 商户：收款方 / 商户 / 付款给 / 转账给 后面的名字。 */
    private val merchantRegex = Regex(
        """(?:收款方|收款人|商户名称|付款给|转账给|对方户名|对方账户|商户|对方)[：:]?\s*([^\s，,。；;：:￥¥（）()]{1,20})"""
    )

    /**
     * 解析一条支付通知（任意应用）。
     *
     * 规则（CONTRACT v3 §3.2）：
     * - `com.tencent.mm` / `com.eg.android.AlipayGphone`：沿用官方规则，标题必须像官方支付通知；
     * - 其它包：不强制官方标题，只要“金额 + 交易关键词”同时成立就返回结果；
     * - 收支方向按关键词 / 正负号判断，都判断不出时默认支出；
     * - 空包名、验证码、聊天等非交易通知返回 null。
     *
     * @param packageName 通知所属应用包名。
     * @param title 通知标题（EXTRA_TITLE）。
     * @param text 通知正文（EXTRA_TEXT / EXTRA_BIG_TEXT / EXTRA_SUB_TEXT 合并）。
     * @param postTime 通知时间（解析本身不需要，保留是为了与契约签名一致）。
     * @return 解析结果；聊天 / 非交易通知返回 null。
     */
    @Suppress("UNUSED_PARAMETER")
    fun parse(packageName: String, title: String, text: String, postTime: Long): ParsedNotification? {
        if (packageName.isBlank()) return null

        val safeTitle = title.trim()
        val safeText = text.trim()
        if (safeTitle.isEmpty() && safeText.isEmpty()) return null
        val raw = listOf(safeTitle, safeText)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        if (verifyWords.any { raw.contains(it) }) return null

        // 屏蔽品牌词后再判断“是否交易通知”和收支方向，避免“微信支付 / 支付宝”干扰。
        val directionText = maskBrands(raw)

        if (packageName == WECHAT_PACKAGE || packageName == ALIPAY_PACKAGE) {
            // 官方包：标题不像官方支付通知（例如联系人昵称 / 群名）时直接忽略。
            if (!looksLikeTrustedTitle(safeTitle)) return null
        } else if (safeTitle.isNotEmpty() && maskBrands(safeTitle).isBlank()) {
            // 非官方包却把标题写成纯品牌词（常见于浏览器 / 网页通知），按伪造通知忽略，
            // 避免网页标题“支付宝”下的任意金额被误记成支付。
            return null
        }

        // 任意应用都要求“交易关键词”：挡住普通聊天 / 营销通知里的金额。
        if (!looksLikeTransaction(directionText)) return null

        val balanceRange = balanceRegex.find(directionText)?.groups?.get(1)?.range
        val amountCandidate = findAmount(directionText, balanceRange) ?: return null
        val signedCents = ParserHelpers.toCents(amountCandidate.first) ?: return null
        val negative = signedCents < 0L
        val amountCents = if (negative) -signedCents else signedCents
        if (amountCents <= 0L) return null

        val type = ParserHelpers.detectType(directionText, expenseKeywords, incomeKeywords)
            ?: fallbackDirection(raw, negative)

        val merchant = findMerchant(raw).ifBlank { defaultMerchant(packageName) }

        return ParsedNotification(
            amountCents = amountCents,
            type = type,
            merchant = merchant,
            rawText = raw,
            kind = classifyKind(maskBrands(raw))
        )
    }

    /**
     * 按通知原文（标题 + 正文）判定语义类型。
     *
     * 优先级即契约列出的顺序：退款 → 收入 → 转账 → 支出 → 其他。
     * “退款到账”归为退款、“支付宝收款”不会因为品牌词里的“支付”被误判成支出。
     */
    private fun classifyKind(raw: String): NotificationKind = when {
        refundKindWords.any { raw.contains(it) } -> NotificationKind.REFUND
        // “收款方：张三，支付 56 元”同时含“收款”和“支付”，应归为支出通知；
        // 品牌词已由调用方 maskBrands 去掉，不会把“支付宝收款”误判成支付。
        transferKindWords.any { raw.contains(it) } -> NotificationKind.TRANSFER
        paymentKindWords.any { raw.contains(it) } -> NotificationKind.PAYMENT
        incomeKindWords.any { raw.contains(it) } -> NotificationKind.INCOME
        else -> NotificationKind.OTHER
    }

    private fun maskBrands(text: String): String {
        var masked = text
        brandWords.forEach { word -> masked = masked.replace(word, " ") }
        return masked
    }

    /** 标题里必须出现官方支付类提示词，聊天 / 群消息标题会被排除。 */
    private fun looksLikeTrustedTitle(title: String): Boolean =
        title.isNotBlank() && trustedTitleHints.any { title.contains(it) }

    private fun looksLikeTransaction(directionText: String): Boolean =
        transactionHints.any { directionText.contains(it) }

    private fun findAmount(text: String, balanceRange: IntRange?): Pair<String, IntRange>? {
        val candidates = ArrayList<Pair<String, IntRange>>()
        val regexes = listOf(
            currencyAmountRegex,
            yuanAmountRegex,
            labeledAmountRegex,
            negativeAmountRegex
        )
        for (regex in regexes) {
            for (match in regex.findAll(text)) {
                val group = match.groups[1] ?: continue
                val raw = group.value
                val range = group.range
                if (raw.isBlank()) continue
                if (balanceRange != null && rangesOverlap(range, balanceRange)) continue
                candidates.add(raw to range)
            }
        }
        return candidates.minWithOrNull(
            compareBy<Pair<String, IntRange>>({ it.second.first }, { it.second.last })
        )
    }

    /** 方向关键词都没命中时：负号金额按支出；转账短信按是否“收到 / 转入”判断，其余按支出。 */
    private fun fallbackDirection(raw: String, negative: Boolean): TxType {
        if (negative) return TxType.EXPENSE
        val isTransfer = raw.contains("转账") || raw.contains("转帐")
        if (isTransfer) {
            return if (incomingHints.any { raw.contains(it) }) TxType.INCOME else TxType.EXPENSE
        }
        return TxType.EXPENSE
    }

    private fun findMerchant(raw: String): String =
        merchantRegex.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    /** 官方包给品牌默认商户；其它包留空，由入库层按应用名 / 包名兜底。 */
    private fun defaultMerchant(packageName: String): String = when (packageName) {
        WECHAT_PACKAGE -> "微信支付"
        ALIPAY_PACKAGE -> "支付宝"
        else -> ""
    }

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
        first.first <= second.last && second.first <= first.last
}
