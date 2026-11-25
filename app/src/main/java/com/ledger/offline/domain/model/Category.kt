package com.ledger.offline.domain.model

/**
 * 收支分类。
 *
 * @param keywords 逗号分隔的关键词，用于对短信 / 通知文本做自动归类，
 *                 例如“餐,饭,美团,外卖”。
 */
data class Category(
    val id: Long = 0L,
    val name: String,
    val type: TxType,
    val icon: String = "📦",
    val keywords: String = "",
    val sortOrder: Int = 0,
    val isDefault: Boolean = false
)
