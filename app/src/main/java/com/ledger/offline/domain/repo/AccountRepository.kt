package com.ledger.offline.domain.repo

import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountType
import kotlinx.coroutines.flow.Flow

/** 账户（银行卡 / 微信 / 支付宝 / 现金）读写。 */
interface AccountRepository {

    fun observeAll(): Flow<List<Account>>

    suspend fun getAll(): List<Account>

    suspend fun getById(id: Long): Account?

    suspend fun getByType(type: AccountType): List<Account>

    /** 按银行名 + 尾号找卡；找不到就创建一张余额未知的卡并返回。 */
    suspend fun ensureBankAccount(bankName: String, cardTail: String): Account

    /** 按名称 + 类型找账户；找不到就创建。用于微信 / 支付宝 / 现金。 */
    suspend fun ensureAccount(name: String, type: AccountType, cardTail: String = ""): Account

    /** 用短信里的余额刷新账户余额。 */
    suspend fun updateBalance(accountId: Long, balanceCents: Long, atMillis: Long)

    /**
     * 在当前余额上增减（可为负），用于基金申购 / 赎回调整“基金余额”。
     * 余额未知（null）时按 0 起算，结果不会低于 0。
     */
    suspend fun adjustBalance(accountId: Long, deltaCents: Long, atMillis: Long): Long

    suspend fun insert(account: Account): Long

    suspend fun update(account: Account)

    suspend fun delete(id: Long)

    /** 该账户下的流水笔数（删除账户前用它做保护判断）。 */
    suspend fun countTransactions(accountId: Long): Int

    /**
     * 每个账户的累计流水金额（收入 + 支出的绝对值之和），用于主页“按流水降序”。
     * 没有任何流水的账户不出现在结果里。
     */
    suspend fun flowTotals(): Map<Long, Long>

    /** 批量更新 sortOrder：按传入顺序重新编号（0..n-1），用于账户管理里的上移 / 下移。 */
    suspend fun updateSortOrders(ordered: List<Account>)
}
