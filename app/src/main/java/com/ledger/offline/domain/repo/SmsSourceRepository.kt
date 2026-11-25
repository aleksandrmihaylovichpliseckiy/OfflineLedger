package com.ledger.offline.domain.repo

import com.ledger.offline.domain.model.SmsSource
import kotlinx.coroutines.flow.Flow

/** 用户指定的银行短信来源。 */
interface SmsSourceRepository {

    fun observeAll(): Flow<List<SmsSource>>

    suspend fun getAll(): List<SmsSource>

    suspend fun getEnabled(): List<SmsSource>

    suspend fun insert(source: SmsSource): Long

    suspend fun update(source: SmsSource)

    suspend fun delete(id: Long)
}
