package com.ledger.offline.ax

import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.Transaction

/**
 * 自动更新结果入库。
 *
 * 复用短信 / 通知导入的思路：
 * - 账户用 `ensureAccount("微信"/"支付宝", ...)`，不存在时自动创建；
 * - 分类用 `findMatching(text, type) ?: defaultFor(type)`；
 * - 去重键 `sourceRef = "ax:<package>:<行文本 hashCode>"`，insert 返回 -1 视为重复；
 * - 余额用 `updateBalance` 覆盖，时间取本次读取时间。
 *
 * 本对象只做数据库写入，不判断页面是否可信；是否写流水由 Controller 决定。
 */
internal object AutoUpdateWriter {

    /** 一次入库的统计结果。 */
    data class Result(
        val imported: Int,
        val rowsOnPage: Int,
        val balanceUpdated: Boolean,
        val looksLikeBillPage: Boolean
    )

    /**
     * 把一页解析结果写入数据库。
     *
     * @param insertRows 是否插入流水；页面上只有余额时传 false。
     * @param now 本次读取时间，用于余额更新与 createdAt。
     */
    suspend fun apply(
        repos: LedgerRepos,
        target: AutoUpdateTarget,
        page: AutoUpdatePageParser.PageResult,
        insertRows: Boolean,
        now: Long
    ): Result {
        val account = repos.accounts.ensureAccount(target.accountName, target.accountType)
        var imported = 0
        if (insertRows) {
            for (row in page.rows) {
                if (insertRow(repos, target, account.id, row, now)) imported++
            }
        }

        var balanceUpdated = false
        val balance = page.balanceCents
        if (balance != null) {
            repos.accounts.updateBalance(account.id, balance, now)
            balanceUpdated = true
        }
        return Result(
            imported = imported,
            rowsOnPage = page.rows.size,
            balanceUpdated = balanceUpdated,
            looksLikeBillPage = page.looksLikeBillPage
        )
    }

    /** 写入单笔流水；sourceRef 重复（insert 返回 -1）时返回 false。 */
    private suspend fun insertRow(
        repos: LedgerRepos,
        target: AutoUpdateTarget,
        accountId: Long,
        row: AutoUpdatePageParser.ParsedRow,
        now: Long
    ): Boolean {
        val category = repos.categories.findMatching(row.rawText, row.type)
            ?: repos.categories.defaultFor(row.type)
        val transaction = Transaction(
            amountCents = row.amountCents,
            type = row.type,
            source = target.source,
            categoryId = category.id,
            accountId = accountId,
            merchant = row.merchant.ifBlank { target.defaultMerchant },
            note = "",
            occurredAt = row.occurredAt,
            createdAt = now,
            balanceAfterCents = null,
            rawText = row.rawText,
            sourceRef = sourceRef(target, row)
        )
        return repos.transactions.insert(transaction) != -1L
    }

    /** 契约要求：sourceRef = "ax:" + packageName + ":" + 行文本 hashCode。 */
    private fun sourceRef(target: AutoUpdateTarget, row: AutoUpdatePageParser.ParsedRow): String =
        "ax:" + target.packageName + ":" + row.rawText.hashCode()
}
