package com.ledger.offline.domain.repo

import com.ledger.offline.domain.model.LedgerSettings
import kotlinx.coroutines.flow.Flow

/** 应用设置（DataStore）。 */
interface SettingsRepository {

    val settings: Flow<LedgerSettings>

    suspend fun current(): LedgerSettings

    suspend fun update(transform: (LedgerSettings) -> LedgerSettings)
}
