package com.ledger.offline.domain.repo

import com.ledger.offline.domain.model.AccountBalanceSnapshot
import com.ledger.offline.domain.model.CategoryTotal
import com.ledger.offline.domain.model.DailyTotal
import com.ledger.offline.domain.model.FlowTotals
import com.ledger.offline.domain.model.SourceTotal
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxType
import kotlinx.coroutines.flow.Flow

/** 流水读写。所有查询都按“左闭右开”的时间区间。 */
interface TransactionRepository {

    fun observeRecent(limit: Int): Flow<List<TransactionRecord>>

    fun observeAll(): Flow<List<TransactionRecord>>

    fun observeBetween(range: TimeRange): Flow<List<TransactionRecord>>

    fun observeTotals(range: TimeRange): Flow<FlowTotals>

    suspend fun getById(id: Long): Transaction?

    suspend fun recordsBetween(range: TimeRange): List<TransactionRecord>

    suspend fun totals(range: TimeRange): FlowTotals

    suspend fun categoryTotals(range: TimeRange, type: TxType): List<CategoryTotal>

    suspend fun sourceTotals(range: TimeRange): List<SourceTotal>

    suspend fun dailyTotals(range: TimeRange): List<DailyTotal>

    /** 每个账户最后一条带余额信息的流水（balanceAfterCents != null），没有则不出现在结果里。 */
    suspend fun latestBalanceByAccount(): List<AccountBalanceSnapshot>

    /** 最近一笔流水时间，用于判断是否需要补扫短信；没有流水时返回 0。 */
    suspend fun lastOccurredAt(): Long

    /**
     * 插入一笔流水。
     * @return 新记录 id；若 sourceRef 已存在（重复导入）返回 -1，不抛异常。
     */
    suspend fun insert(tx: Transaction): Long

    /** 批量插入并自动去重，返回真正新增的条数。 */
    suspend fun insertAll(txs: List<Transaction>): Int

    suspend fun update(tx: Transaction)

    suspend fun delete(id: Long)
}
