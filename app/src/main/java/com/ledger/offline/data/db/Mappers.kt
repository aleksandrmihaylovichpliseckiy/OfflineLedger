package com.ledger.offline.data.db

import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountBalanceSnapshot
import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.CategoryTotal
import com.ledger.offline.domain.model.FlowTotals
import com.ledger.offline.domain.model.LedgerReport
import com.ledger.offline.domain.model.SmsSource
import com.ledger.offline.domain.model.SourceTotal
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n

/*
 * 实体 / 投影行 与 domain 模型之间的映射。
 * 只允许 domain 层依赖数据层，反向不成立：仓库对外只返回 domain 模型。
 */

/** 分类被删除后的兜底名称。 */
private const val FALLBACK_EXPENSE_NAME = "其他支出"
private const val FALLBACK_INCOME_NAME = "其他收入"
private const val FALLBACK_ICON = "📦"

/**
 * 分类被删除后按流水类型兜底。
 *
 * `TxType.TRANSFER` 既不是收入也不是支出，不能沿用“其他收入”，
 * 否则基金申购 / 赎回的转账记录会在列表里显示成收入分类。
 */
private fun fallbackCategoryName(type: TxType): String = when (type) {
    TxType.EXPENSE -> FALLBACK_EXPENSE_NAME
    TxType.INCOME -> FALLBACK_INCOME_NAME
    TxType.TRANSFER -> I18n.t("转账")
}

internal fun TransactionEntity.toDomain(): Transaction = Transaction(
    id = id,
    amountCents = amountCents,
    type = type,
    source = source,
    categoryId = categoryId,
    accountId = accountId,
    merchant = merchant,
    note = note,
    occurredAt = occurredAt,
    createdAt = createdAt,
    balanceAfterCents = balanceAfterCents,
    rawText = rawText,
    sourceRef = sourceRef,
    balanceApplied = balanceApplied
)

internal fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    amountCents = amountCents,
    type = type,
    source = source,
    categoryId = categoryId,
    accountId = accountId,
    merchant = merchant,
    note = note,
    occurredAt = occurredAt,
    createdAt = createdAt,
    balanceAfterCents = balanceAfterCents,
    rawText = rawText,
    sourceRef = sourceRef,
    balanceApplied = balanceApplied
)

internal fun TransactionRecordRow.toDomain(): TransactionRecord {
    val resolvedCategoryName = categoryName ?: fallbackCategoryName(tx.type)
    val resolvedCategoryIcon = categoryIcon ?: FALLBACK_ICON
    val resolvedAccountName = when {
        accountName.isNullOrBlank() -> I18n.t("未知账户")
        accountCardTail.isNullOrBlank() -> accountName
        else -> I18n.t("%1\$s（尾号%2\$s）", accountName, accountCardTail)
    }
    return TransactionRecord(
        tx = tx.toDomain(),
        categoryName = resolvedCategoryName,
        categoryIcon = resolvedCategoryIcon,
        accountName = resolvedAccountName
    )
}

internal fun TotalsRow.toDomain(): FlowTotals = FlowTotals(
    incomeCents = incomeCents,
    expenseCents = expenseCents,
    count = count
)

internal fun CategoryTotalsRow.toDomain(): CategoryTotal {
    return CategoryTotal(
        categoryId = categoryId,
        name = categoryName ?: fallbackCategoryName(type),
        icon = categoryIcon ?: FALLBACK_ICON,
        type = type,
        totalCents = totalCents,
        count = count
    )
}

internal fun SourceTotalsRow.toDomain(): SourceTotal = SourceTotal(
    source = source,
    totalCents = totalCents,
    count = count
)

internal fun AccountBalanceRow.toDomain(): AccountBalanceSnapshot = AccountBalanceSnapshot(
    accountId = accountId,
    balanceCents = balanceCents,
    occurredAt = occurredAt
)

internal fun AccountEntity.toDomain(): Account = Account(
    id = id,
    name = name,
    type = type,
    cardTail = cardTail,
    balanceCents = balanceCents,
    balanceUpdatedAt = balanceUpdatedAt,
    sortOrder = sortOrder
)

internal fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    type = type,
    cardTail = cardTail,
    balanceCents = balanceCents,
    balanceUpdatedAt = balanceUpdatedAt,
    sortOrder = sortOrder
)

internal fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    type = type,
    icon = icon,
    keywords = keywords,
    sortOrder = sortOrder,
    isDefault = isDefault
)

internal fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    type = type,
    icon = icon,
    keywords = keywords,
    sortOrder = sortOrder,
    isDefault = isDefault
)

internal fun SmsSourceEntity.toDomain(): SmsSource = SmsSource(
    id = id,
    label = label,
    addressPattern = addressPattern,
    bankName = bankName,
    cardTail = cardTail,
    accountId = accountId,
    enabled = enabled
)

internal fun SmsSource.toEntity(): SmsSourceEntity = SmsSourceEntity(
    id = id,
    label = label,
    addressPattern = addressPattern,
    bankName = bankName,
    cardTail = cardTail,
    accountId = accountId,
    enabled = enabled
)

internal fun ReportEntity.toDomain(): LedgerReport = LedgerReport(
    id = id,
    period = period,
    title = title,
    range = TimeRange(startMillis = rangeStart, endMillisExclusive = rangeEnd),
    text = text,
    totalExpenseCents = totalExpenseCents,
    totalIncomeCents = totalIncomeCents,
    txCount = txCount,
    generatedAt = generatedAt
)

internal fun LedgerReport.toEntity(): ReportEntity = ReportEntity(
    id = id,
    period = period,
    title = title,
    rangeStart = range.startMillis,
    rangeEnd = range.endMillisExclusive,
    text = text,
    totalExpenseCents = totalExpenseCents,
    totalIncomeCents = totalIncomeCents,
    txCount = txCount,
    generatedAt = generatedAt
)
