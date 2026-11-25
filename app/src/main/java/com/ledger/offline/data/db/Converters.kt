package com.ledger.offline.data.db

import androidx.room.TypeConverter
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType

/**
 * 枚举与数据库 TEXT 的互转。
 * 存枚举的 `name` 而不是 `ordinal`，避免以后枚举顺序变化导致历史数据错位。
 */
class LedgerConverters {

    @TypeConverter
    fun fromTxType(value: TxType): String = value.name

    @TypeConverter
    fun toTxType(value: String): TxType = TxType.valueOf(value)

    @TypeConverter
    fun fromTxSource(value: TxSource): String = value.name

    @TypeConverter
    fun toTxSource(value: String): TxSource = TxSource.valueOf(value)

    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.name

    @TypeConverter
    fun toAccountType(value: String): AccountType = AccountType.valueOf(value)

    @TypeConverter
    fun fromReportPeriod(value: ReportPeriod): String = value.name

    @TypeConverter
    fun toReportPeriod(value: String): ReportPeriod = ReportPeriod.valueOf(value)
}
