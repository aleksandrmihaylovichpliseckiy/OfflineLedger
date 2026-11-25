package com.ledger.offline.ingest

import com.ledger.offline.domain.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotificationParser] 的纯 JVM 单元测试：不依赖 Android / Robolectric。
 *
 * 覆盖微信 / 支付宝支付、红包、转账、退款、负号金额，
 * 以及聊天、验证码、其他包名、无交易语义等必须返回 null 的场景。
 */
class NotificationParserTest {

    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val postTime = 1_729_332_000_000L

    @Test
    fun parseWechatPayExpense() {
        val parsed = NotificationParser.parse(
            wechat,
            "微信支付",
            "微信支付凭证：支付金额￥56.00",
            postTime
        ) ?: error("微信支付通知应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
        assertEquals("微信支付", parsed.merchant)
        assertTrue(parsed.rawText.contains("支付金额"))
    }

    @Test
    fun parseWechatPayReceiptWithoutVerb() {
        // 只有“微信支付凭证”+金额时，也要能识别为支出，而不是当作聊天忽略。
        val parsed = NotificationParser.parse(wechat, "微信支付", "微信支付凭证：￥56.00", postTime)
            ?: error("支付凭证通知应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
    }

    @Test
    fun parseAlipayPayment() {
        val parsed = NotificationParser.parse(alipay, "支付宝", "成功付款56.00元", postTime)
            ?: error("支付宝付款通知应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
        assertEquals("支付宝", parsed.merchant)
    }

    @Test
    fun parseWechatPaymentDone() {
        val parsed = NotificationParser.parse(wechat, "微信支付", "你已付款 ￥12.50", postTime)
            ?: error("“你已付款”通知应解析成功")
        assertEquals(1250L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
    }

    @Test
    fun parseRedPacketIncome() {
        val parsed = NotificationParser.parse(wechat, "微信支付", "你收到一个红包 ￥8.88", postTime)
            ?: error("红包通知应解析成功")
        assertEquals(888L, parsed.amountCents)
        assertEquals(TxType.INCOME, parsed.type)
        assertEquals("微信支付", parsed.merchant)
    }

    @Test
    fun parseTransferIncome() {
        val parsed = NotificationParser.parse(alipay, "支付宝", "收到一笔转账 ￥100.00", postTime)
            ?: error("收款转账通知应解析成功")
        assertEquals(10000L, parsed.amountCents)
        assertEquals(TxType.INCOME, parsed.type)
        assertEquals("支付宝", parsed.merchant)
    }

    @Test
    fun parseTransferExpenseWithMerchant() {
        val parsed = NotificationParser.parse(alipay, "支付宝", "你已转账给张三 ￥52.00", postTime)
            ?: error("转账通知应解析成功")
        assertEquals(5200L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
        assertEquals("张三", parsed.merchant)
    }

    @Test
    fun parseRefundIncome() {
        val parsed = NotificationParser.parse(wechat, "微信支付", "退款到账 ￥30.00", postTime)
            ?: error("退款到账通知应解析成功")
        assertEquals(3000L, parsed.amountCents)
        assertEquals(TxType.INCOME, parsed.type)
    }

    @Test
    fun parseNegativeAmountAsExpense() {
        val parsed = NotificationParser.parse(alipay, "支付宝", "支付成功 -56.00", postTime)
            ?: error("负号金额通知应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
    }

    @Test
    fun parseLabeledAmount() {
        val parsed = NotificationParser.parse(wechat, "微信支付", "支付金额56.00", postTime)
            ?: error("“支付金额56.00”应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
    }

    @Test
    fun parseCurrencySymbolAndYuanVariants() {
        val first = NotificationParser.parse(wechat, "微信支付", "支付成功 ¥56.00", postTime)
            ?: error("¥ 前缀应解析成功")
        assertEquals(5600L, first.amountCents)

        val second = NotificationParser.parse(wechat, "微信支付", "支付成功 56.00元", postTime)
            ?: error("元 后缀应解析成功")
        assertEquals(5600L, second.amountCents)
    }

    @Test
    fun parseChatNotificationReturnsNull() {
        assertNull(NotificationParser.parse(wechat, "张三", "晚上一起吃饭吗？", postTime))
        // 聊天里出现金额，但没有支付 / 转账语义，也不能当成交易。
        assertNull(NotificationParser.parse(wechat, "李四", "还你100元", postTime))
    }

    @Test
    fun parseNonTransactionNotificationWithAmountReturnsNull() {
        assertNull(NotificationParser.parse(alipay, "支付宝", "今日天气不错 ￥56.00", postTime))
    }

    @Test
    fun parseVerificationCodeNotificationReturnsNull() {
        assertNull(
            NotificationParser.parse(
                wechat,
                "微信支付",
                "验证码123456，请勿告诉他人",
                postTime
            )
        )
    }

    @Test
    fun parseOtherPackageReturnsNull() {
        assertNull(NotificationParser.parse("com.android.chrome", "支付宝", "支付成功 ￥56.00", postTime))
    }

    @Test
    fun parseEmptyNotificationReturnsNull() {
        assertNull(NotificationParser.parse(wechat, "", "", postTime))
        assertNull(NotificationParser.parse("", "微信支付", "支付成功 ￥56.00", postTime))
    }
}
