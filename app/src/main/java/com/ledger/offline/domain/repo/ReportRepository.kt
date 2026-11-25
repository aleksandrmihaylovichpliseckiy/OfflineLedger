package com.ledger.offline.domain.repo

import com.ledger.offline.domain.model.LedgerReport
import com.ledger.offline.domain.model.ReportPeriod
import kotlinx.coroutines.flow.Flow

/** 已生成报表的本地存档。 */
interface ReportRepository {

    fun observeRecent(limit: Int): Flow<List<LedgerReport>>

    suspend fun recent(limit: Int): List<LedgerReport>

    suspend fun save(report: LedgerReport): Long

    suspend fun findByPeriodAndStart(period: ReportPeriod, startMillis: Long): LedgerReport?

    suspend fun lastGeneratedAt(period: ReportPeriod): Long?

    suspend fun delete(id: Long)
}
