package com.ledger.offline.data.repo

import com.ledger.offline.data.db.CategoryTotalsRow
import com.ledger.offline.data.db.SourceTotalsRow
import com.ledger.offline.data.db.TotalsRow
import com.ledger.offline.data.db.TransactionDao
import com.ledger.offline.data.db.toDomain
import com.ledger.offline.data.db.toEntity
import com.ledger.offline.domain.model.AccountBalanceSnapshot
import com.ledger.offline.domain.model.CategoryTotal
import com.ledger.offline.domain.model.DailyTotal
import com.ledger.offline.domain.model.FlowTotals
import com.ledger.offline.domain.model.SourceTotal
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.domain.repo.TransactionRepository
import com.ledger.offline.util.DateTimes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [TransactionRepository] 的 Room 实现。
 *
 * 说明：
 * - 列表类查询在 DAO 里做 `LEFT JOIN categories / accounts`，投影成带分类 / 账户名的行对象；
 * - 分类 / 来源合计用 SQL 聚合，`dailyTotals` 用 Kotlin 按本机时区自然日分组；
 * - 插入依赖 `sourceRef` 唯一索引 + IGNORE 冲突策略，重复导入返回 -1。
 */
class RoomTransactionRepository(
    private val dao: TransactionDao
) : TransactionRepository {

    override fun observeRecent(limit: Int): Flow<List<TransactionRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeAll(): Flow<List<TransactionRecord>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeBetween(range: TimeRange): Flow<List<TransactionRecord>> =
        dao.observeBetween(range.startMillis, range.endMillisExclusive)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeTotals(range: TimeRange): Flow<FlowTotals> =
        dao.observeTotals(range.startMillis, range.endMillisExclusive)
            .map { row -> row.toDomain() }

    override suspend fun getById(id: Long): Transaction? = dao.findById(id)?.toDomain()

    override suspend fun recordsBetween(range: TimeRange): List<TransactionRecord> =
        dao.recordsBetween(range.startMillis, range.endMillisExclusive)
            .map { row -> row.toDomain() }

    override suspend fun totals(range: TimeRange): FlowTotals {
        val row: TotalsRow = dao.totals(range.startMillis, range.endMillisExclusive)
        return row.toDomain()
    }

    override suspend fun categoryTotals(range: TimeRange, type: TxType): List<CategoryTotal> {
        val rows: List<CategoryTotalsRow> =
            dao.categoryTotals(range.startMillis, range.endMillisExclusive, type)
        return rows.map { row -> row.toDomain() }
    }

    override suspend fun sourceTotals(range: TimeRange): List<SourceTotal> {
        val rows: List<SourceTotalsRow> = dao.sourceTotals(range.startMillis, range.endMillisExclusive)
        return rows.map { row -> row.toDomain() }
    }

    /**
     * 按本机时区的自然日分组汇总。
     * 返回结果按日期升序排列，便于首页近 7 天趋势图直接使用。
     *
     * 只累加 EXPENSE / INCOME；`TxType.TRANSFER`（基金申购 / 赎回等账户间划转）
     * 既不是收入也不是支出，直接跳过，也不会为该日生成 0/0 的空桶。
     */
    override suspend fun dailyTotals(range: TimeRange): List<DailyTotal> {
        val transactions = dao.listBetween(range.startMillis, range.endMillisExclusive)
        val grouped = LinkedHashMap<Long, LongArray>()
        for (entity in transactions) {
            // 三分支：LongArray 下标 0 = 收入，1 = 支出；
            // TRANSFER 是账户间划转，既不是收入也不是支出，直接跳过（也不生成 0/0 空桶）。
            val index = when (entity.type) {
                TxType.INCOME -> 0
                TxType.EXPENSE -> 1
                TxType.TRANSFER -> null
            } ?: continue
            val dayStart = DateTimes.startOfDay(entity.occurredAt)
            grouped.getOrPut(dayStart) { LongArray(2) }[index] += entity.amountCents
        }
        return grouped.entries
            .sortedBy { entry -> entry.key }
            .map { entry ->
                DailyTotal(
                    dayStartMillis = entry.key,
                    incomeCents = entry.value[0],
                    expenseCents = entry.value[1]
                )
            }
    }

    /**
     * 每个账户最后一条带余额的流水，供“余额修正”使用。
     *
     * 同一账户同一毫秒有多笔时取 id 最大的那条；没有余额信息的账户不会出现。
     */
    override suspend fun latestBalanceByAccount(): List<AccountBalanceSnapshot> =
        dao.latestBalanceByAccount().map { row -> row.toDomain() }

    override suspend fun lastOccurredAt(): Long = dao.maxOccurredAt() ?: 0L

    override suspend fun insert(tx: Transaction): Long = dao.insert(tx.toEntity())

    override suspend fun insertAll(txs: List<Transaction>): Int {
        var inserted = 0
        for (tx in txs) {
            if (dao.insert(tx.toEntity()) != -1L) {
                inserted++
            }
        }
        return inserted
    }

    override suspend fun update(tx: Transaction) {
        dao.update(tx.toEntity())
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}
