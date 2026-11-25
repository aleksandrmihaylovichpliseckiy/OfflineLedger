package com.ledger.offline.domain

import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType

/**
 * 手动记账与账户余额的同步。
 *
 * 规则：
 * - 只有 `source == MANUAL` 的支出 / 收入会增减对应账户余额；
 * - 支出减余额、收入加余额；`TRANSFER`（基金划转等自动记录）不参与；
 * - `balanceApplied` 标记该笔手动流水是否已经同步，编辑 / 删除时据此回退，避免重复加减；
 * - 银行短信余额是权威值：`IngestWriter` 收到短信后会直接用短信余额覆盖账户余额，
 *   因此手动记账与短信冲突时以短信为准。
 */
object ManualBalanceSync {

    /** 一笔手动流水对账户余额的影响（分）；非手动 / 转账 / 非正金额返回 0。 */
    fun deltaOf(tx: Transaction): Long {
        if (tx.source != TxSource.MANUAL) return 0L
        if (tx.amountCents <= 0L) return 0L
        return when (tx.type) {
            TxType.EXPENSE -> -tx.amountCents
            TxType.INCOME -> tx.amountCents
            TxType.TRANSFER -> 0L
        }
    }

    /** 把一笔手动流水同步到账户余额；成功返回 true。 */
    suspend fun apply(repos: LedgerRepos, tx: Transaction): Boolean {
        val delta = deltaOf(tx)
        if (delta == 0L) return false
        return runCatching {
            repos.accounts.adjustBalance(tx.accountId, delta, System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }

    /** 回退一笔已经同步过的手动流水（删除 / 取消编辑时使用）。 */
    suspend fun revert(repos: LedgerRepos, tx: Transaction): Boolean {
        if (!tx.balanceApplied) return false
        val delta = deltaOf(tx)
        if (delta == 0L) return false
        return runCatching {
            repos.accounts.adjustBalance(tx.accountId, -delta, System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }
}
