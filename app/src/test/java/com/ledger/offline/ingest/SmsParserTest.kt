package com.ledger.offline.ingest

import com.ledger.offline.domain.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SmsParser] 的纯 JVM 单元测试：不依赖 Android / Robolectric。
 *
 * 覆盖 CONTRACT 3.2 列出的四类中文银行短信句式、
 * 千分位 / 货币前缀、验证码短信、收入 / 支出方向判断。
 */
class SmsParserTest {

    /** 固定时间戳，只用于验证 occurredAt 原样回填。 */
    private val smsTime = 1_729_332_000_000L

    @Test
    fun parseCmbExpenseSms() {
        val body = "您尾号1234的储蓄卡10月19日18:01消费支出人民币56.00元，余额1,234.56元。【招商银行】"
        val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("招商银行消费短信应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
        assertEquals(123456L, checkNotNull(parsed.balanceCents))
        assertEquals("1234", parsed.cardTail)
        assertEquals(smsTime, parsed.occurredAt)
        assertEquals(body, parsed.rawText)
        assertEquals("", parsed.merchant)
    }

    @Test
    fun parseIcbcIncomeSms() {
        val body = "【工商银行】您尾号1234账户10月19日收入(工资)人民币8,000.00元，余额9,234.56元。"
        val parsed = SmsParser.parse(body, "95588", smsTime) ?: error("工商银行工资短信应解析成功")
        assertEquals(800000L, parsed.amountCents)
        assertEquals(TxType.INCOME, parsed.type)
        assertEquals(923456L, checkNotNull(parsed.balanceCents))
        assertEquals("1234", parsed.cardTail)
    }

    @Test
    fun parseOnlineExpenseSmsWithMerchant() {
        val body = "您账户1234于10月19日18:01在美团消费56.00元，余额1234.56元。"
        val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("“在美团消费”句式应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
        assertEquals(123456L, checkNotNull(parsed.balanceCents))
        assertEquals("1234", parsed.cardTail)
        assertEquals("美团", parsed.merchant)
    }

    @Test
    fun parseAmountWithThousandsSeparator() {
        val body = "【建设银行】您尾号5678的储蓄卡10月19日消费支出人民币12,345.67元，余额100,000.00元。"
        val parsed = SmsParser.parse(body, "95533", smsTime) ?: error("千分位金额应解析成功")
        assertEquals(1234567L, parsed.amountCents)
        assertEquals(10000000L, checkNotNull(parsed.balanceCents))
        assertEquals(TxType.EXPENSE, parsed.type)
    }

    @Test
    fun parseCurrencyPrefixVariants() {
        val variants = listOf(
            "您尾号1234账户消费支出￥56.00元，余额1,000.00元。",
            "您尾号1234账户消费支出人民币56.00元，余额1,000.00元。",
            "您尾号1234账户消费支出RMB56.00，余额1,000.00元。",
            "您尾号1234账户消费支出CNY 56.00元，余额1,000.00元。"
        )
        for (body in variants) {
            val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("金额前缀应兼容：$body")
            assertEquals(5600L, parsed.amountCents)
            assertEquals(TxType.EXPENSE, parsed.type)
            assertEquals(100000L, checkNotNull(parsed.balanceCents))
        }
    }

    @Test
    fun parseVerificationCodeSmsReturnsNull() {
        assertNull(
            SmsParser.parse(
                "【招商银行】验证码123456，您正在登录手机银行，请勿泄露。",
                "95555",
                smsTime
            )
        )
        assertNull(
            SmsParser.parse(
                "【支付宝】您的校验码为888888，5分钟内有效。",
                "95188",
                smsTime
            )
        )
    }

    @Test
    fun parseSmsWithoutAmountReturnsNull() {
        assertNull(
            SmsParser.parse(
                "【招商银行】您的信用卡账单已生成，请及时还款。",
                "95555",
                smsTime
            )
        )
        assertNull(SmsParser.parse("", "95555", smsTime))
    }

    @Test
    fun parseIncomeByTransferIn() {
        val body = "您尾号1234账户转入人民币500.00元，余额1,000.00元。"
        val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("转入应解析成功")
        assertEquals(50000L, parsed.amountCents)
        assertEquals(TxType.INCOME, parsed.type)
    }

    @Test
    fun parseIncomeBySalary() {
        val body = "您尾号1234账户工资入账人民币8,000.00元，余额9,000.00元。"
        val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("工资入账应解析成功")
        assertEquals(800000L, parsed.amountCents)
        assertEquals(TxType.INCOME, parsed.type)
    }

    @Test
    fun parseExpenseByPayment() {
        val body = "您尾号1234账户支付人民币50.00元，余额1,000.00元。"
        val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("支付应解析成功")
        assertEquals(5000L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
    }

    @Test
    fun parseDirectionUsesEarlierKeyword() {
        // “消费”在前、“退款 / 到账”在后，应按出现位置更靠前的“消费”判为支出。
        val body = "您尾号1234账户消费后退款到账56.00元。"
        val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("同时命中收支关键词时应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals(TxType.EXPENSE, parsed.type)
        assertNull(parsed.balanceCents)
    }

    @Test
    fun parseSmsWithoutDirectionReturnsNull() {
        // 有金额但看不出收支方向的短信不导入，避免把方向记错。
        assertNull(
            SmsParser.parse(
                "您尾号1234账户发生人民币56.00元交易。",
                "95555",
                smsTime
            )
        )
    }

    @Test
    fun parseSmsWithoutBalanceKeepsBalanceNull() {
        val body = "您尾号1234的储蓄卡10月19日18:01消费支出人民币56.00元。"
        val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("无余额短信也应解析成功")
        assertEquals(5600L, parsed.amountCents)
        assertEquals("1234", parsed.cardTail)
        assertNull(parsed.balanceCents)
    }

    /** 用户提供的工商银行标准格式：支出 2 元、余额 5.12 元（余额修正依赖该余额）。 */
    @Test
    fun parseIcbcExampleWithBalance() {
        val body = "尾号0000卡10月29日21:08支出(消费支付宝-哈尔滨市城市通智能卡有限责任)2元，余额5.12元。【工商银行】"
        val parsed = SmsParser.parse(body, "95588", smsTime) ?: error("工商银行示例短信应解析成功")
        assertEquals(200L, parsed.amountCents)
        assertEquals(512L, checkNotNull(parsed.balanceCents))
        assertEquals(TxType.EXPENSE, parsed.type)
        assertEquals("0000", parsed.cardTail)
    }

    /** 不同银行的余额写法：余额为人民币 / 当前余额 / 账户余额约 / 可用余额。 */
    @Test
    fun parseOtherBankBalanceVariants() {
        val variants = listOf(
            "【建设银行】您尾号1234的储蓄卡10月19日18:01消费支出人民币56.00元，余额为人民币1,234.56元。" to 123456L,
            "【农业银行】您尾号1234账户10月19日18:01完成一笔消费交易人民币56.00元，当前余额1,234.56元。" to 123456L,
            "【中国银行】您尾号1234的卡10月19日18:01消费人民币56.00元，账户余额约1,234.56元。" to 123456L,
            "【交通银行】您尾号1234账户10月19日18:01消费人民币56.00元，可用余额1,234.56元。" to 123456L,
            "【邮储银行】您尾号1234账户10月19日18:01支出人民币56.00元，余额（人民币）1,234.56元。" to 123456L
        )
        for ((body, expectedBalance) in variants) {
            val parsed = SmsParser.parse(body, "95555", smsTime) ?: error("余额写法应兼容：$body")
            assertEquals(5600L, parsed.amountCents)
            assertEquals(expectedBalance, checkNotNull(parsed.balanceCents))
            assertEquals(TxType.EXPENSE, parsed.type)
        }
    }
}
