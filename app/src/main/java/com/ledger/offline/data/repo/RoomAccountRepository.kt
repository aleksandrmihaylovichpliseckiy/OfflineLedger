package com.ledger.offline.data.repo

import com.ledger.offline.data.DbWriteMutex
import com.ledger.offline.data.db.AccountDao
import com.ledger.offline.data.db.AccountFlowRow
import com.ledger.offline.data.db.TransactionDao
import com.ledger.offline.data.db.toDomain
import com.ledger.offline.data.db.toEntity
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.repo.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

/**
 * [AccountRepository] 的 Room 实现。
 *
 * 自动建卡规则（契约 3.1）：按 `type=BANK AND name=bankName` 查；
 * 尾号非空时还要求尾号一致；找不到才插入一张余额未知（null）的新卡。
 *
 * 1.1.0 起额外实现删除保护与主页排序所需的流水统计：
 * 流水笔数 / 累计流水查询走 [TransactionDao]，不把聚合放进账户表。
 */
class RoomAccountRepository(
    private val dao: AccountDao,
    private val transactionDao: TransactionDao
) : AccountRepository {

    override fun observeAll(): Flow<List<Account>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<Account> = dao.getAll().map { it.toDomain() }

    override suspend fun getById(id: Long): Account? = dao.findById(id)?.toDomain()

    override suspend fun getByType(type: AccountType): List<Account> =
        dao.getByType(type).map { it.toDomain() }

    override suspend fun ensureBankAccount(bankName: String, cardTail: String): Account =
        DbWriteMutex.mutex.withLock {
            val safeBankName = bankName.trim().ifBlank { "银行卡" }
            val safeCardTail = cardTail.trim()
            val candidates = dao.getByTypeAndName(AccountType.BANK, safeBankName)
            val matched = if (safeCardTail.isBlank()) {
                candidates.firstOrNull()
            } else {
                candidates.firstOrNull { account -> account.cardTail == safeCardTail }
            }
            if (matched != null) {
                matched.toDomain()
            } else {
                createAccount(name = safeBankName, type = AccountType.BANK, cardTail = safeCardTail)
            }
        }

    override suspend fun ensureAccount(name: String, type: AccountType, cardTail: String): Account =
        DbWriteMutex.mutex.withLock {
            val safeName = name.trim().ifBlank { type.label }
            val candidates = dao.getByTypeAndName(type, safeName)
            candidates.firstOrNull()?.let { account -> return@withLock account.toDomain() }
            createAccount(name = safeName, type = type, cardTail = cardTail.trim())
        }

    override suspend fun updateBalance(accountId: Long, balanceCents: Long, atMillis: Long) {
        dao.updateBalance(id = accountId, balanceCents = balanceCents, atMillis = atMillis)
    }

    /**
     * 在当前余额上增减（基金申购 / 赎回）。
     *
     * 读余额 -> 计算 -> 写回整段都在 [DbWriteMutex] 内完成，与其它账户写操作串行，
     * 避免并发申购 / 赎回互相覆盖（丢失更新）。
     * - 余额未知（null）按 0 起算，结果 `coerceAtLeast(0)`，不会出现负余额；
     * - 实体里的 `balanceUpdatedAt` 是唯一的时间戳字段，与余额一起刷新；
     * - 账户不存在时返回 0，且不写库。
     */
    override suspend fun adjustBalance(accountId: Long, deltaCents: Long, atMillis: Long): Long =
        DbWriteMutex.mutex.withLock {
            val account = dao.findById(accountId) ?: return@withLock 0L
            val newBalance = ((account.balanceCents ?: 0L) + deltaCents).coerceAtLeast(0L)
            dao.updateBalance(id = accountId, balanceCents = newBalance, atMillis = atMillis)
            newBalance
        }

    override suspend fun insert(account: Account): Long = dao.insert(account.toEntity())

    override suspend fun update(account: Account) {
        dao.update(account.toEntity())
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    /** 该账户下的流水笔数；删除账户前由 UI 调用，避免挂空流水。 */
    override suspend fun countTransactions(accountId: Long): Int =
        transactionDao.countByAccount(accountId)

    /**
     * 每个账户的累计流水金额；没有流水的账户不出现在结果里。
     *
     * 这里保留全部流水（含 `TxType.TRANSFER` 的基金申赎划转），仅用于主页“按流水降序”排序，
     * 不能当作收入 / 支出合计使用（收支合计由 [RoomTransactionRepository] 单独查询）。
     */
    override suspend fun flowTotals(): Map<Long, Long> {
        val rows: List<AccountFlowRow> = transactionDao.accountFlowTotals()
        return rows.associate { row -> row.accountId to row.totalCents }
    }

    /**
     * 按传入顺序把 sortOrder 重写为 0..n-1。
     *
     * 账户管理页上移 / 下移后整体调用一次，主页按 `sortOrder` 重新排序；
     * 逐条 update 足以保证顺序（账户数量很小，且与其它写操作共用 [DbWriteMutex]）。
     */
    override suspend fun updateSortOrders(ordered: List<Account>) {
        DbWriteMutex.mutex.withLock {
            ordered.forEachIndexed { index, account ->
                dao.updateSortOrder(id = account.id, sortOrder = index)
            }
        }
    }

    /** 新建账户：余额未知（null），排序号排在现有账户之后。 */
    private suspend fun createAccount(name: String, type: AccountType, cardTail: String): Account {
        val account = Account(
            name = name,
            type = type,
            cardTail = cardTail,
            balanceCents = null,
            balanceUpdatedAt = 0L,
            sortOrder = dao.maxSortOrder() + 1
        )
        val id = dao.insert(account.toEntity())
        return account.copy(id = id)
    }
}
