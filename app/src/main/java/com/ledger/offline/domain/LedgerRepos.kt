package com.ledger.offline.domain

import com.ledger.offline.domain.repo.AccountRepository
import com.ledger.offline.domain.repo.CategoryRepository
import com.ledger.offline.domain.repo.ReportRepository
import com.ledger.offline.domain.repo.SettingsRepository
import com.ledger.offline.domain.repo.SmsSourceRepository
import com.ledger.offline.domain.repo.TransactionRepository

/**
 * 所有模块共用的仓库集合。
 * 由数据层 [com.ledger.offline.data.RepoFactory] 创建，挂在 Application 上，
 * UI / 短信导入 / 报表生成 / 通知监听都只依赖这里，不直接碰 Room。
 */
data class LedgerRepos(
    val transactions: TransactionRepository,
    val accounts: AccountRepository,
    val categories: CategoryRepository,
    val smsSources: SmsSourceRepository,
    val reports: ReportRepository,
    val settings: SettingsRepository
)
