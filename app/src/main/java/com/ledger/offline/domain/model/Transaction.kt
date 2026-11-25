package com.ledger.offline.domain.model

import java.util.UUID

/** 收支方向。TRANSFER 是账户间划转（如基金申购 / 赎回），不计入收入、支出。 */
enum class TxType {
    EXPENSE,
    INCOME,
    TRANSFER;

    val label: String
        get() = when (this) {
            EXPENSE -> "支出"
            INCOME -> "收入"
            TRANSFER -> "转账"
        }
}

/** 一笔账的数据来源。 */
enum class TxSource(val label: String) {
    SMS("银行短信"),
    WECHAT("微信"),
    ALIPAY("支付宝"),
    MANUAL("手动记账"),
    APP("应用通知")
}

/**
 * 一笔收支流水。金额统一用“分”存储，避免浮点误差。
 *
 * @param sourceRef 去重键：入库时唯一，重复导入同一笔会返回 -1。
 * @param rawText 解析该笔账的原始短信 / 通知文本，仅本地留存，用于核对。
 */
data class Transaction(
    val id: Long = 0L,
    val amountCents: Long,
    val type: TxType,
    val source: TxSource,
    val categoryId: Long,
    val accountId: Long,
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val balanceAfterCents: Long? = null,
    val rawText: String = "",
    val sourceRef: String = "",
    /** 是否已经把本笔流水的手动记账金额同步到账户余额（仅手动记账使用）。 */
    val balanceApplied: Boolean = false
) {
    companion object {
        /** 手动记账的工厂方法，自动生成唯一去重键。 */
        fun manual(
            amountCents: Long,
            type: TxType,
            categoryId: Long,
            accountId: Long,
            merchant: String = "",
            note: String = "",
            occurredAt: Long = System.currentTimeMillis()
        ): Transaction = Transaction(
            amountCents = amountCents,
            type = type,
            source = TxSource.MANUAL,
            categoryId = categoryId,
            accountId = accountId,
            merchant = merchant,
            note = note,
            occurredAt = occurredAt,
            sourceRef = "manual:" + UUID.randomUUID().toString()
        )
    }
}

/** 列表展示用的流水（附带分类 / 账户名称，避免 UI 层反复查表）。 */
data class TransactionRecord(
    val tx: Transaction,
    val categoryName: String,
    val categoryIcon: String,
    val accountName: String
)
