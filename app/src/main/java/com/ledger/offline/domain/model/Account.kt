package com.ledger.offline.domain.model

import com.ledger.offline.i18n.I18n

/** 账户类型。银行卡余额来自银行短信，微信 / 支付宝余额解析不到时为 null。 */
enum class AccountType(val label: String) {
    BANK("银行卡"),
    WECHAT("微信"),
    ALIPAY("支付宝"),
    CASH("现金"),
    APP("应用通知"),
    FUND("基金")
}

data class Account(
    val id: Long = 0L,
    val name: String,
    val type: AccountType,
    val cardTail: String = "",
    val balanceCents: Long? = null,
    val balanceUpdatedAt: Long = 0L,
    val sortOrder: Int = 0
) {
    /** 显示名：银行卡带尾号，例如“招商银行（尾号1234）”；语言切换时自动翻译“尾号”。 */
    val displayName: String
        get() = if (cardTail.isBlank()) name
        else I18n.t("%1\$s（尾号%2\$s）", name, cardTail)
}
