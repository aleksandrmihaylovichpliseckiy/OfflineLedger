package com.ledger.offline.domain.model

/**
 * 某个账户最后一条带余额信息的流水快照。
 *
 * 供“余额修正”使用：用银行短信 / 通知里的最新 `balanceAfterCents`
 * 与账户表当前余额比对，判断是否需要修正。
 *
 * @param accountId 账户主键
 * @param balanceCents 该笔流水发生后的账户余额（分）
 * @param occurredAt 该笔流水的发生时间（毫秒）
 */
data class AccountBalanceSnapshot(
    val accountId: Long,
    val balanceCents: Long,
    val occurredAt: Long
)
