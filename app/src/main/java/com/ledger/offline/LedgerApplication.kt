package com.ledger.offline

import android.app.Application
import android.util.Log
import com.ledger.offline.data.DataSeeder
import com.ledger.offline.di.AppContainer
import com.ledger.offline.i18n.I18n
import com.ledger.offline.report.ReportCenter
import com.ledger.offline.report.ReportNotifier
import com.ledger.offline.report.ReportScheduler
import com.ledger.offline.ui.AppIconController
import com.ledger.offline.util.UpgradeBackup
import kotlinx.coroutines.launch

/**
 * 应用入口：
 * 1. 初始化依赖容器（Room / DataStore 仓库）。
 * 2. 创建通知渠道（报表通知用）。
 * 3. 后台补种子数据、注册定时报表、补发当天错过的日报。
 */
class LedgerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 打开数据库之前先备份上一版本数据，确保升级不丢流水 / 手动修正。
        UpgradeBackup.ensureBackup(this)
        container = AppContainer(this)
        ReportNotifier.ensureChannels(this)
        container.applicationScope.launch {
            runCatching {
                val settings = container.repos.settings.current()
                I18n.set(settings.language)
                AppIconController.apply(this@LedgerApplication, settings)
            }.onFailure { Log.w(TAG, "读取语言 / 图标设置失败", it) }
            runCatching { DataSeeder.ensureSeeded(container.repos) }
                .onFailure { Log.w(TAG, "写入种子数据失败，下次启动重试", it) }
            runCatching { ReportScheduler.schedule(this@LedgerApplication) }
                .onFailure { Log.w(TAG, "注册定时报表失败", it) }
            runCatching { ReportCenter.catchUpOnLaunch(this@LedgerApplication, container.repos) }
                .onFailure { Log.w(TAG, "补发日报失败", it) }
        }
    }

    private companion object {
        private const val TAG = "LedgerApplication"
    }
}
