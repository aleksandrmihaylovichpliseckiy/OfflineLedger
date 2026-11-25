package com.ledger.offline.data.repo

import com.ledger.offline.data.db.SmsSourceDao
import com.ledger.offline.data.db.toDomain
import com.ledger.offline.data.db.toEntity
import com.ledger.offline.domain.model.SmsSource
import com.ledger.offline.domain.repo.SmsSourceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [SmsSourceRepository] 的 Room 实现。 */
class RoomSmsSourceRepository(
    private val dao: SmsSourceDao
) : SmsSourceRepository {

    override fun observeAll(): Flow<List<SmsSource>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<SmsSource> = dao.getAll().map { it.toDomain() }

    override suspend fun getEnabled(): List<SmsSource> = dao.getEnabled().map { it.toDomain() }

    override suspend fun insert(source: SmsSource): Long = dao.insert(source.toEntity())

    override suspend fun update(source: SmsSource) {
        dao.update(source.toEntity())
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}
