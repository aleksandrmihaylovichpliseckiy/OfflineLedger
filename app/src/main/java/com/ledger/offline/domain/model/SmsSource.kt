package com.ledger.offline.domain.model

/**
 * 用户配置的“指定短信来源”，例如招商银行 95555、工商银行 95588。
 *
 * @param addressPattern 发送方号码，支持包含匹配（例如 95555 或“招商银行”）。
 * @param accountId 该来源对应的账户，余额更新与流水归属都用它。
 */
data class SmsSource(
    val id: Long = 0L,
    val label: String,
    val addressPattern: String,
    val bankName: String,
    val cardTail: String = "",
    val accountId: Long,
    val enabled: Boolean = true
)
