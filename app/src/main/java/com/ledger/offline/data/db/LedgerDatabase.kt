package com.ledger.offline.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用唯一的 Room 数据库（文件 `ledger.db`）。
 *
 * version 1 → 2：流水表新增 `balanceApplied`，记录手动记账是否已同步过账户余额。
 * 旧版手动流水默认 0（未同步），删除/编辑旧流水时不会错误回退余额。
 */
@Database(
    entities = [
        TransactionEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        SmsSourceEntity::class,
        ReportEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(LedgerConverters::class)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun accountDao(): AccountDao

    abstract fun categoryDao(): CategoryDao

    abstract fun smsSourceDao(): SmsSourceDao

    abstract fun reportDao(): ReportDao

    companion object {

        @Volatile
        private var instance: LedgerDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN balanceApplied INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** 进程内单例；首次访问时才真正打开数据库文件。 */
        fun get(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "ledger.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
