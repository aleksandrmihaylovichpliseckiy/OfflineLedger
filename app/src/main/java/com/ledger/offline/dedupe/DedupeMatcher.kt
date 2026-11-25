package com.ledger.offline.dedupe

import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import java.util.Locale
import kotlin.math.abs

/**
 * 去重匹配规则（CONTRACT v4 §2）：纯函数，不碰仓库、不写库，方便人工核对与单元测试。
 *
 * v4 起不再要求“一条必须是银行短信”：任意两条流水，只要满足下面全部条件，就视为疑似同一笔：
 * 1. 不是同一条记录（id 不同）；
 * 2. 金额（分）完全相等且为正数；
 * 3. 收支方向相同，且只允许 [TxType.EXPENSE] / [TxType.INCOME]；
 *    [TxType.TRANSFER]（基金申购 / 赎回等账户间划转）不参与去重；
 * 4. 时间差绝对值不超过 [TIME_TOLERANCE_MILLIS]（180 秒）；
 * 5. “支出去向”核对：两边 merchant 都非空时，规范化后要求互相包含，
 *    或最长公共汉字片段不少于 2 个字；只有一边有 merchant（或规范化后只剩品牌噪声词、
 *    没有可比信息）时直接允许匹配；
 * 6. 两条不能都是手工记账（[TxSource.MANUAL]）：用户自己重复记两笔时由用户自己处理。
 *    一条手动 + 一条自动来源是允许配对的（交给“每次询问”让用户确认）。
 *
 * 同来源也允许配对（例如两个不同应用通知、或同一应用的两条不同 sourceRef）。
 *
 * 保留优先级见 [keepRank]：SMS > WECHAT > ALIPAY > APP > MANUAL；同优先级保留 id 较小（先入库）那条。
 */
internal object DedupeMatcher {

    /** 时间容差：±180 秒（通知已精确到秒）。 */
    const val TIME_TOLERANCE_MILLIS = 180_000L

    /** 判断“支出去向”相似时，要求的最长公共汉字片段长度。 */
    private const val MIN_COMMON_HAN_LENGTH = 2

    /** 空白（含全角空格）。 */
    private val whitespaceRegex = Regex("[\\s\\u3000]+")

    /**
     * 规范化商户名时剔除的噪声词，按“长词优先”排列，避免“微信支付”先被“支付”拆坏。
     * 这些词只表示支付渠道 / 动作，不表示“支出去向”，留着会让无关的两笔账看起来相似。
     */
    private val noiseWords = listOf(
        "微信支付",
        "支付宝",
        "财付通",
        "转账",
        "转帐",
        "付款",
        "收款",
        "支付",
        "微信",
        "消费",
        "退款",
        "提现"
    )

    /** 参与去重的收支方向：只有支出 / 收入；TRANSFER 不计入收支，也不参与配对。 */
    fun isDedupeType(type: TxType): Boolean =
        type == TxType.EXPENSE || type == TxType.INCOME

    /** 是否手工记账。 */
    fun isManual(source: TxSource): Boolean = source == TxSource.MANUAL

    /**
     * 保留优先级：数值越小越优先保留。
     * 契约顺序 SMS > WECHAT > ALIPAY > APP > MANUAL。
     */
    fun keepRank(source: TxSource): Int = when (source) {
        TxSource.SMS -> 0
        TxSource.WECHAT -> 1
        TxSource.ALIPAY -> 2
        TxSource.APP -> 3
        TxSource.MANUAL -> 4
    }

    /** 这一对里是否有手工记账（自动模式遇到它只能转“待询问”，不能删记录）。 */
    fun involvesManual(first: Transaction, second: Transaction): Boolean =
        isManual(first.source) || isManual(second.source)

    /** 时间差是否在容差内。 */
    fun withinTimeWindow(first: Transaction, second: Transaction): Boolean =
        abs(first.occurredAt - second.occurredAt) <= TIME_TOLERANCE_MILLIS

    /**
     * 两条流水是否构成“疑似同一笔”。顺序无关：哪一条先入库都能命中。
     *
     * 只做纯规则判断，不关心谁保留谁删除（保留侧由 [orderedPair] 决定）。
     */
    fun isCandidatePair(first: Transaction, second: Transaction): Boolean {
        if (first.id > 0L && second.id > 0L && first.id == second.id) return false
        // 0 / 负金额不是真实收支，不参与配对，避免把异常记录互相合并。
        if (first.amountCents <= 0L || second.amountCents <= 0L) return false
        if (first.amountCents != second.amountCents) return false
        if (first.type != second.type) return false
        if (!isDedupeType(first.type)) return false
        if (!withinTimeWindow(first, second)) return false
        // 手动记账之间不自动去重。
        if (isManual(first.source) && isManual(second.source)) return false
        return merchantsMatch(first.merchant, second.merchant)
    }

    /**
     * 把一对疑似重复统一成 (保留, 删除)，顺序无关。
     *
     * 先按 [keepRank] 选来源优先的那条（SMS > WECHAT > ALIPAY > APP > MANUAL），
     * 来源相同（优先级相同）时保留 id 较小、也就是先入库的那条。
     * 任一侧没有有效 id（还没入库）时返回 null：无法安全删除，宁可不处理。
     */
    fun orderedPair(first: Transaction, second: Transaction): Pair<Transaction, Transaction>? {
        if (first.id <= 0L || second.id <= 0L || first.id == second.id) return null
        val firstRank = keepRank(first.source)
        val secondRank = keepRank(second.source)
        return when {
            firstRank < secondRank -> first to second
            secondRank < firstRank -> second to first
            first.id < second.id -> first to second
            else -> second to first
        }
    }

    /**
     * “支出去向”是否可视为同一笔。
     *
     * 只有一边有商户时允许匹配；两边都有商户时要求规范化后互相包含，
     * 或最长公共汉字片段 ≥ 2 个字（例如“美团”与“美团外卖”命中“美团”）。
     */
    fun merchantsMatch(first: String, second: String): Boolean {
        val left = normalizeMerchant(first)
        val right = normalizeMerchant(second)
        if (left.isEmpty() || right.isEmpty()) return true
        if (left.contains(right) || right.contains(left)) return true
        return longestCommonHanLength(left, right) >= MIN_COMMON_HAN_LENGTH
    }

    /**
     * 规范化商户名：转小写 → 去空白 → 去“微信支付 / 支付宝 / 财付通 / 转账 / 付款”等渠道噪声词
     * → 只保留文字与数字（去掉括号、标点、货币符号、表情等）。
     *
     * 结果为空表示这条记录没有可比的“支出去向”信息（常见于通知只有“微信支付”品牌词），
     * 调用方应退化成“只有一边有商户”的宽松规则。
     */
    fun normalizeMerchant(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.lowercase(Locale.ROOT)
        text = whitespaceRegex.replace(text, "")
        for (word in noiseWords) {
            if (text.contains(word)) text = text.replace(word, "")
        }
        // 银行短信在没有“商户”字段时会把银行名 / 卡类型当兜底商户，
        // 这不代表支出去向，视为“未知商户”，让匹配退化为金额 + 时间。
        if (text.contains("银行") || text.contains("储蓄卡") || text.contains("信用卡") ||
            text.contains("尾号")
        ) {
            return ""
        }
        return text.filter { it.isLetterOrDigit() }
    }

    /**
     * 两个字符串“最长公共汉字片段”的长度：先把非汉字过滤掉，再对汉字序列求最长公共子串。
     * 商户名都很短，这里用滚动数组的 O(n·m) 实现即可。
     */
    fun longestCommonHanLength(first: String, second: String): Int {
        val left = first.filter { isHan(it) }
        val right = second.filter { isHan(it) }
        if (left.length < MIN_COMMON_HAN_LENGTH || right.length < MIN_COMMON_HAN_LENGTH) return 0
        var best = 0
        val row = IntArray(right.length + 1)
        for (i in 1..left.length) {
            var diagonal = 0
            for (j in 1..right.length) {
                val previous = row[j]
                row[j] = if (left[i - 1] == right[j - 1]) diagonal + 1 else 0
                if (row[j] > best) best = row[j]
                diagonal = previous
            }
        }
        return best
    }

    /** 汉字判定：CJK 基本区 + 扩展 A + 兼容区。 */
    private fun isHan(char: Char): Boolean =
        char in '\u4E00'..'\u9FFF' ||
            char in '\u3400'..'\u4DBF' ||
            char in '\uF900'..'\uFAFF'
}
