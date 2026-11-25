package com.ledger.offline.domain.model

import com.ledger.offline.i18n.I18n

/** 一次导入（短信 / 通知）的结果，用于在首页提示用户。 */
data class IngestStats(
    val scanned: Int = 0,
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val permissionDenied: Boolean = false,
    val message: String = ""
) {
    fun summary(): String = when {
        permissionDenied -> I18n.t("缺少短信权限，无法读取银行短信")
        message.isNotBlank() -> message
        scanned == 0 -> I18n.t("没有可读取的银行短信")
        else -> I18n.t("扫描 %1\$d 条，新增 %2\$d 笔，跳过 %3\$d 条", scanned, imported, skipped)
    }
}
