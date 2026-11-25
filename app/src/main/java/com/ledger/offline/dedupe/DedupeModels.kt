package com.ledger.offline.dedupe

/** 用户在“疑似重复”提示里的选择（CONTRACT v4 §2）。 */
enum class DedupeChoice {
    /** 合并：按保留优先级保留一条（SMS > WECHAT > ALIPAY > APP > MANUAL），删除另一条。 */
    MERGE,

    /** 保留两笔：两条流水都不删除，只把这一对从待询问列表里去掉。 */
    KEEP_BOTH
}

/**
 * 一对疑似重复的流水（CONTRACT v4 §2：任意两种来源都可能配对，含同来源）。
 *
 * - [keepId] 是“合并时保留”的那条（按 SMS > WECHAT > ALIPAY > APP > MANUAL，同来源取先入库的），
 *   [dropId] 是“合并时删除”的那条，与 `settings.pendingDuplicate` 里编码的 `keepId|dropId`
 *   顺序一致；
 * - [amountCents] / [occurredAt] 取保留记录的值：金额用于展示，时间已精确到秒，供 UI 直接格式化；
 * - [keepMerchant] / [dropMerchant] 是两条流水各自的“支出去向”，可能为空串；
 * - [keepSource] / [dropSource] 是 [com.ledger.offline.domain.model.TxSource.label] 的展示名
 *   （例如“银行短信”“手动记账”），UI 可直接 `tr(...)` 后显示，不需要再做枚举转换。
 *
 * 含 [com.ledger.offline.domain.model.TxSource.MANUAL] 的对只会进入待询问列表，
 * 自动托管不会替用户删除手工记录。
 */
data class DuplicatePair(
    val keepId: Long,
    val dropId: Long,
    val amountCents: Long,
    val occurredAt: Long,
    val keepMerchant: String,
    val dropMerchant: String,
    val keepSource: String,
    val dropSource: String
)

/**
 * 一键核验的统计结果（CONTRACT v4 §2）。
 *
 * @param found 本次新发现、且尚未在待询问列表里的疑似重复对数（关闭模式下只统计，不做任何写操作）。
 * @param merged 本次真正完成合并（删除被保留优先级判为“该删”的那条）的对数；
 *   含手工记账的对不会计入（它们只加入待询问）。
 * @param pending 本次结束时仍在待询问列表里的对数（含历史遗留），需要 UI 逐组询问。
 */
data class VerifyResult(val found: Int, val merged: Int, val pending: Int)
