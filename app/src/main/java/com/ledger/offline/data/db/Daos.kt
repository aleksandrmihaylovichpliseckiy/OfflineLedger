package com.ledger.offline.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import kotlinx.coroutines.flow.Flow

/** 流水 + 分类 / 账户名称的投影行，用于列表展示。 */
data class TransactionRecordRow(
    @Embedded val tx: TransactionEntity,
    val categoryName: String?,
    val categoryIcon: String?,
    val accountName: String?,
    val accountCardTail: String?
)

/** 区间收支合计投影。 */
data class TotalsRow(
    val incomeCents: Long,
    val expenseCents: Long,
    val count: Int
)

/** 分类合计投影；分类被删除后 name / icon 可能为 null，由仓库层兜底。 */
data class CategoryTotalsRow(
    val categoryId: Long,
    val categoryName: String?,
    val categoryIcon: String?,
    val type: TxType,
    val totalCents: Long,
    val count: Int
)

/**
 * 来源合计投影。
 *
 * 只汇总 `INCOME` / `EXPENSE` 两种类型；`TxType.TRANSFER`（基金申购 / 赎回等划转）
 * 不属于收支，不计入任何来源的金额或笔数。
 */
data class SourceTotalsRow(
    val source: TxSource,
    val totalCents: Long,
    val count: Int
)

/**
 * 账户累计流水投影，用于主页“按流水降序”排序（1.1.0）。
 *
 * @param accountId 账户主键
 * @param totalCents 该账户所有流水金额（分）之和；amountCents 入库时恒为非负。
 *                   这里只用于“账户活跃度”排序，**不是**收支合计，
 *                   因此 `TxType.TRANSFER`（基金申购 / 赎回等划转）也计入；
 *                   收入 / 支出合计见 [TransactionDao.observeTotals] / [TransactionDao.totals]，
 *                   那两个查询不会把 TRANSFER 算进收入或支出。
 */
data class AccountFlowRow(
    val accountId: Long,
    val totalCents: Long
)

/**
 * 账户最新余额投影，用于“余额修正”。
 *
 * 每个账户最多一行：取 `occurredAt` 最大、同一时间取 `id` 最大且
 * `balanceAfterCents` 非空的那条流水；没有带余额流水的账户不会出现在结果里。
 *
 * @param accountId 账户主键
 * @param balanceCents 该笔流水后的账户余额（分）；SQL 已过滤 null，因此非空
 * @param occurredAt 该笔流水的发生时间（毫秒）
 */
data class AccountBalanceRow(
    val accountId: Long,
    val balanceCents: Long,
    val occurredAt: Long
)

/** 流水表 DAO。 */
@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransactionEntity): Long

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TransactionEntity?

    @Query(
        "SELECT * FROM transactions " +
            "WHERE occurredAt >= :startMillis AND occurredAt < :endMillisExclusive " +
            "ORDER BY occurredAt ASC, id ASC"
    )
    suspend fun listBetween(startMillis: Long, endMillisExclusive: Long): List<TransactionEntity>

    @Query("SELECT MAX(occurredAt) FROM transactions")
    suspend fun maxOccurredAt(): Long?

    @Query(
        """
        SELECT t.id AS id,
               t.amountCents AS amountCents,
               t.type AS type,
               t.source AS source,
               t.categoryId AS categoryId,
               t.accountId AS accountId,
               t.merchant AS merchant,
               t.note AS note,
               t.occurredAt AS occurredAt,
               t.createdAt AS createdAt,
               t.balanceAfterCents AS balanceAfterCents,
               t.rawText AS rawText,
               t.sourceRef AS sourceRef,
               t.balanceApplied AS balanceApplied,
               c.name AS categoryName,
               c.icon AS categoryIcon,
               a.name AS accountName,
               a.cardTail AS accountCardTail
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        ORDER BY t.occurredAt DESC, t.id DESC
        """
    )
    fun observeAll(): Flow<List<TransactionRecordRow>>

    @Query(
        """
        SELECT t.id AS id,
               t.amountCents AS amountCents,
               t.type AS type,
               t.source AS source,
               t.categoryId AS categoryId,
               t.accountId AS accountId,
               t.merchant AS merchant,
               t.note AS note,
               t.occurredAt AS occurredAt,
               t.createdAt AS createdAt,
               t.balanceAfterCents AS balanceAfterCents,
               t.rawText AS rawText,
               t.sourceRef AS sourceRef,
               t.balanceApplied AS balanceApplied,
               c.name AS categoryName,
               c.icon AS categoryIcon,
               a.name AS accountName,
               a.cardTail AS accountCardTail
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        WHERE t.occurredAt >= :startMillis AND t.occurredAt < :endMillisExclusive
        ORDER BY t.occurredAt DESC, t.id DESC
        """
    )
    fun observeBetween(startMillis: Long, endMillisExclusive: Long): Flow<List<TransactionRecordRow>>

    @Query(
        """
        SELECT t.id AS id,
               t.amountCents AS amountCents,
               t.type AS type,
               t.source AS source,
               t.categoryId AS categoryId,
               t.accountId AS accountId,
               t.merchant AS merchant,
               t.note AS note,
               t.occurredAt AS occurredAt,
               t.createdAt AS createdAt,
               t.balanceAfterCents AS balanceAfterCents,
               t.rawText AS rawText,
               t.sourceRef AS sourceRef,
               t.balanceApplied AS balanceApplied,
               c.name AS categoryName,
               c.icon AS categoryIcon,
               a.name AS accountName,
               a.cardTail AS accountCardTail
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        ORDER BY t.occurredAt DESC, t.id DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<TransactionRecordRow>>

    @Query(
        """
        SELECT t.id AS id,
               t.amountCents AS amountCents,
               t.type AS type,
               t.source AS source,
               t.categoryId AS categoryId,
               t.accountId AS accountId,
               t.merchant AS merchant,
               t.note AS note,
               t.occurredAt AS occurredAt,
               t.createdAt AS createdAt,
               t.balanceAfterCents AS balanceAfterCents,
               t.rawText AS rawText,
               t.sourceRef AS sourceRef,
               t.balanceApplied AS balanceApplied,
               c.name AS categoryName,
               c.icon AS categoryIcon,
               a.name AS accountName,
               a.cardTail AS accountCardTail
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        WHERE t.occurredAt >= :startMillis AND t.occurredAt < :endMillisExclusive
        ORDER BY t.occurredAt DESC, t.id DESC
        """
    )
    suspend fun recordsBetween(
        startMillis: Long,
        endMillisExclusive: Long
    ): List<TransactionRecordRow>

    /**
     * 区间收支合计。
     *
     * CASE 只匹配 `INCOME` / `EXPENSE`，`TxType.TRANSFER`（基金申购 / 赎回等账户间划转）
     * 不会计入收入或支出；count 仍为区间内流水总笔数（含 TRANSFER）。
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amountCents ELSE 0 END), 0) AS incomeCents,
               COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS expenseCents,
               COUNT(*) AS count
        FROM transactions
        WHERE occurredAt >= :startMillis AND occurredAt < :endMillisExclusive
        """
    )
    fun observeTotals(startMillis: Long, endMillisExclusive: Long): Flow<TotalsRow>

    /** 与 [observeTotals] 同一条 SQL 的一次性版本；TRANSFER 同样不计入收入 / 支出。 */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amountCents ELSE 0 END), 0) AS incomeCents,
               COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS expenseCents,
               COUNT(*) AS count
        FROM transactions
        WHERE occurredAt >= :startMillis AND occurredAt < :endMillisExclusive
        """
    )
    suspend fun totals(startMillis: Long, endMillisExclusive: Long): TotalsRow

    @Query(
        """
        SELECT t.categoryId AS categoryId,
               c.name AS categoryName,
               c.icon AS categoryIcon,
               t.type AS type,
               SUM(t.amountCents) AS totalCents,
               COUNT(*) AS count
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        WHERE t.occurredAt >= :startMillis
          AND t.occurredAt < :endMillisExclusive
          AND t.type = :type
        GROUP BY t.categoryId, c.name, c.icon, t.type
        ORDER BY totalCents DESC
        """
    )
    suspend fun categoryTotals(
        startMillis: Long,
        endMillisExclusive: Long,
        type: TxType
    ): List<CategoryTotalsRow>

    /** 区间内按来源汇总收支；TRANSFER 不计入任何来源（见 [SourceTotalsRow]）。 */
    @Query(
        """
        SELECT t.source AS source,
               SUM(t.amountCents) AS totalCents,
               COUNT(*) AS count
        FROM transactions t
        WHERE t.occurredAt >= :startMillis AND t.occurredAt < :endMillisExclusive
          AND t.type IN ('INCOME', 'EXPENSE')
        GROUP BY t.source
        ORDER BY totalCents DESC
        """
    )
    suspend fun sourceTotals(startMillis: Long, endMillisExclusive: Long): List<SourceTotalsRow>

    /** 该账户下的流水笔数（删除账户前用它做保护判断）。 */
    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
    suspend fun countByAccount(accountId: Long): Int

    /** 每个账户的累计流水金额；没有任何流水的账户不出现在结果里。 */
    @Query("SELECT accountId, SUM(amountCents) AS totalCents FROM transactions GROUP BY accountId")
    suspend fun accountFlowTotals(): List<AccountFlowRow>

    /**
     * 每个账户最后一条带余额的流水（`balanceAfterCents IS NOT NULL`）。
     *
     * 先用派生表按账户取 `MAX(occurredAt)`，再用 `MAX(id)` 处理同一毫秒的多笔流水，
     * 保证每个账户最多返回一行；没有余额信息的账户不会出现。
     */
    @Query(
        """
        SELECT t.accountId AS accountId,
               t.balanceAfterCents AS balanceCents,
               t.occurredAt AS occurredAt
        FROM transactions t
        JOIN (
            SELECT accountId, MAX(occurredAt) AS maxAt
            FROM transactions
            WHERE balanceAfterCents IS NOT NULL
            GROUP BY accountId
        ) m ON m.accountId = t.accountId AND m.maxAt = t.occurredAt
        WHERE t.balanceAfterCents IS NOT NULL
          AND t.id = (
              SELECT MAX(t2.id)
              FROM transactions t2
              WHERE t2.accountId = t.accountId
                AND t2.occurredAt = t.occurredAt
                AND t2.balanceAfterCents IS NOT NULL
          )
        ORDER BY t.accountId ASC
        """
    )
    suspend fun latestBalanceByAccount(): List<AccountBalanceRow>
}

/** 账户表 DAO。 */
@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun getByType(type: AccountType): List<AccountEntity>

    @Query(
        "SELECT * FROM accounts WHERE type = :type AND name = :name " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    suspend fun getByTypeAndName(type: AccountType, name: String): List<AccountEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM accounts")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(entity: AccountEntity): Long

    @Update
    suspend fun update(entity: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE accounts SET balanceCents = :balanceCents, balanceUpdatedAt = :atMillis WHERE id = :id")
    suspend fun updateBalance(id: Long, balanceCents: Long, atMillis: Long)

    /** 只更新排序号，用于账户管理里的上移 / 下移。 */
    @Query("UPDATE accounts SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)
}

/** 分类表 DAO。 */
@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun getByType(type: TxType): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories WHERE type = :type")
    suspend fun maxSortOrder(type: TxType): Int

    @Insert
    suspend fun insert(entity: CategoryEntity): Long

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/** 短信来源表 DAO。 */
@Dao
interface SmsSourceDao {

    @Query("SELECT * FROM sms_sources ORDER BY id ASC")
    fun observeAll(): Flow<List<SmsSourceEntity>>

    @Query("SELECT * FROM sms_sources ORDER BY id ASC")
    suspend fun getAll(): List<SmsSourceEntity>

    @Query("SELECT * FROM sms_sources WHERE enabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<SmsSourceEntity>

    @Insert
    suspend fun insert(entity: SmsSourceEntity): Long

    @Update
    suspend fun update(entity: SmsSourceEntity)

    @Query("DELETE FROM sms_sources WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/** 报表存档表 DAO。 */
@Dao
interface ReportDao {

    @Query("SELECT * FROM reports ORDER BY generatedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports ORDER BY generatedAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ReportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: ReportEntity): Long

    @Query("SELECT * FROM reports WHERE period = :period AND rangeStart = :startMillis LIMIT 1")
    suspend fun findByPeriodAndStart(period: ReportPeriod, startMillis: Long): ReportEntity?

    @Query("SELECT MAX(generatedAt) FROM reports WHERE period = :period")
    suspend fun lastGeneratedAt(period: ReportPeriod): Long?

    @Query("DELETE FROM reports WHERE id = :id")
    suspend fun deleteById(id: Long)
}
