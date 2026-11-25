package com.ledger.offline.di

import android.content.Context
import com.ledger.offline.data.RepoFactory
import com.ledger.offline.domain.LedgerRepos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简依赖容器：不引入 Hilt，避免额外的注解处理与构建复杂度。
 * 由 [com.ledger.offline.LedgerApplication] 创建并持有，其余模块通过它拿仓库。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val repos: LedgerRepos by lazy { RepoFactory.create(appContext) }

    /** 应用级协程作用域，用于启动时的种子数据、报表补发等后台任务。 */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
