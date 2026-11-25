package com.ledger.offline.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType

/*
 * Room 实体：仅用于本机 SQLite 存储，字段与 domain 层模型一一对应。
 * 约定：枚举统一以 name 字符串存库（见 LedgerConverters），金额单位为“分”（Long），
 * 时间单位为毫秒（Long）。
 */

/** 流水表。[sourceRef] 建唯一索引，重复导入同一笔短信 / 通知时插入冲突返回 -1。 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["sourceRef"], unique = true),
        Index(value = ["occurredAt"]),
        Index(value = ["categoryId"]),
        Index(value = ["accountId"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val amountCents: Long,
    val type: TxType,
    val source: TxSource,
    val categoryId: Long,
    val accountId: Long,
    val merchant: String,
    val note: String,
    val occurredAt: Long,
    val createdAt: Long,
    val balanceAfterCents: Long?,
    val rawText: String,
    val sourceRef: String,
    /** 手动记账是否已把金额同步到账户余额；旧版本手动流水默认 false，删除时不会回退余额。 */
    val balanceApplied: Boolean = false
)

/** 账户表：银行卡余额来自银行短信，微信 / 支付宝余额未知时为 null。 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val type: AccountType,
    val cardTail: String,
    val balanceCents: Long?,
    val balanceUpdatedAt: Long,
    val sortOrder: Int
)

/** 分类表：[keywords] 为英文 / 中文逗号分隔的关键词，用于短信自动归类。 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val type: TxType,
    val icon: String,
    val keywords: String,
    val sortOrder: Int,
    val isDefault: Boolean
)

/** 短信来源配置表：[accountId] 为 0 表示按银行名 / 尾号自动建卡。 */
@Entity(tableName = "sms_sources")
data class SmsSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val label: String,
    val addressPattern: String,
    val bankName: String,
    val cardTail: String,
    val accountId: Long,
    val enabled: Boolean
)

/** 报表存档表：(period, rangeStart) 唯一，同一周期只保留一份。 */
@Entity(
    tableName = "reports",
    indices = [Index(value = ["period", "rangeStart"], unique = true)]
)
data class ReportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val period: ReportPeriod,
    val title: String,
    val rangeStart: Long,
    val rangeEnd: Long,
    val text: String,
    val totalExpenseCents: Long,
    val totalIncomeCents: Long,
    val txCount: Int,
    val generatedAt: Long
)
