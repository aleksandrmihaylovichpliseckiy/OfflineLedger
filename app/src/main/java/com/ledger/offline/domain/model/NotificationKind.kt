package com.ledger.offline.domain.model

/**
 * 支付通知的语义类型，用于“需要解析支付通知的应用”里按类型筛选。
 *
 * 这只是对通知文本的分类，不代表最终收支方向。
 */
enum class NotificationKind(val label: String) {
    PAYMENT("支出"),
    INCOME("收入"),
    REFUND("退款"),
    TRANSFER("转账"),
    OTHER("其他")
}
