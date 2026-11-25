package com.ledger.offline.data

import android.content.Context
import com.ledger.offline.data.db.LedgerDatabase
import com.ledger.offline.data.repo.DataStoreSettingsRepository
import com.ledger.offline.data.repo.RoomAccountRepository
import com.ledger.offline.data.repo.RoomCategoryRepository
import com.ledger.offline.data.repo.RoomReportRepository
import com.ledger.offline.data.repo.RoomSmsSourceRepository
import com.ledger.offline.data.repo.RoomTransactionRepository
import com.ledger.offline.domain.LedgerRepos

/**
 * 数据层对外的唯一入口：按“进程内单例”创建并缓存 [LedgerRepos]。
 *
 * 六类仓库共用同一个 Room 数据库与同一个 DataStore，避免重复打开文件；
 * 由 `AppContainer` 在 Application 启动时调用，重复调用返回同一份实例。
 */
object RepoFactory {

    @Volatile
    private var instance: LedgerRepos? = null

    fun create(context: Context): LedgerRepos =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { repos -> instance = repos }
        }

    private fun build(context: Context): LedgerRepos {
        val database = LedgerDatabase.get(context)
        return LedgerRepos(
            transactions = RoomTransactionRepository(database.transactionDao()),
            accounts = RoomAccountRepository(database.accountDao(), database.transactionDao()),
            categories = RoomCategoryRepository(database.categoryDao()),
            smsSources = RoomSmsSourceRepository(database.smsSourceDao()),
            reports = RoomReportRepository(database.reportDao()),
            settings = DataStoreSettingsRepository(context)
        )
    }
}
