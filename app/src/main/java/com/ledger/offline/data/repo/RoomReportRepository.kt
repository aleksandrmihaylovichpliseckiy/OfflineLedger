package com.ledger.offline.data.repo

import com.ledger.offline.data.db.ReportDao
import com.ledger.offline.data.db.toDomain
import com.ledger.offline.data.db.toEntity
import com.ledger.offline.domain.model.LedgerReport
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.repo.ReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ReportRepository] 的 Room 实现。
 *
 * `(period, rangeStart)` 建唯一索引，保存时使用 REPLACE 覆盖同一周期的旧报表；
 * 若 REPLACE 意外返回 -1，则按 `(period, rangeStart)` 再查一次并回填 id。
 */
class RoomReportRepository(
    private val dao: ReportDao
) : ReportRepository {

    override fun observeRecent(limit: Int): Flow<List<LedgerReport>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun recent(limit: Int): List<LedgerReport> =
        dao.recent(limit).map { it.toDomain() }

    override suspend fun save(report: LedgerReport): Long {
        val rowId = dao.save(report.toEntity())
        if (rowId != -1L) {
            return rowId
        }
        return dao.findByPeriodAndStart(report.period, report.range.startMillis)?.id ?: -1L
    }

    override suspend fun findByPeriodAndStart(
        period: ReportPeriod,
        startMillis: Long
    ): LedgerReport? = dao.findByPeriodAndStart(period, startMillis)?.toDomain()

    override suspend fun lastGeneratedAt(period: ReportPeriod): Long? =
        dao.lastGeneratedAt(period)

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}
