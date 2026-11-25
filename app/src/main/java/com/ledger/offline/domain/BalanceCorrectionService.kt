package com.ledger.offline.domain

/** 余额修正结果：成功修正的账户数 / 跳过的账户数（没有余额信息或更新失败）。 */
data class BalanceCorrectionResult(
    val updated: Int,
    val skipped: Int
)

/**
 * 余额修正：读取每个账户**最后一条带余额信息的流水**（银行短信里的 `余额AfterCents`），
 * 把账户余额修正为该值。用于手动记账 / 历史导入导致余额与最后一条短信不一致时。
 *
 * 只依赖本地数据库，不联网；没有余额信息的账户直接跳过。
 */
object BalanceCorrectionService {

    suspend fun correctAll(repos: LedgerRepos): BalanceCorrectionResult {
        val accounts = repos.accounts.getAll()
        val snapshots = repos.transactions.latestBalanceByAccount()
        var updated = 0
        var skipped = 0
        val byAccount = snapshots.associateBy { it.accountId }
        accounts.forEach { account ->
            val snapshot = byAccount[account.id]
            if (snapshot == null || snapshot.balanceCents < 0L) {
                skipped++
                return@forEach
            }
            val ok = runCatching {
                repos.accounts.updateBalance(account.id, snapshot.balanceCents, snapshot.occurredAt)
                true
            }.getOrDefault(false)
            if (ok) updated++ else skipped++
        }
        return BalanceCorrectionResult(updated = updated, skipped = skipped)
    }
}
