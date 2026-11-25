package com.ledger.offline.report

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ledger.offline.LedgerApplication
import com.ledger.offline.domain.model.LedgerSettings
import com.ledger.offline.util.DateTimes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 报表定时任务调度。
 *
 * 使用 WorkManager 唯一周期任务：[UNIQUE_WORK_NAME]，24 小时一次；
 * 初始延迟对齐设置里的“生成时间” reportHour:reportMinute，之后每 24 小时跑一次 [ReportWorker]。
 * 重复调用用 [ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE] 覆盖旧任务（取消后按新时间重排），不会堆叠。
 *
 * [schedule] 的签名是普通函数（契约要求），但读取 DataStore 设置需要挂起，
 * 因此内部丢到应用级后台协程里异步完成：本方法立即返回、绝不抛异常；
 * 入队失败只打日志（WorkManager 下次启动仍可重新排期）。
 */
object ReportScheduler {

    /** 唯一周期任务名，重复 enqueue 时用它定位并覆盖。 */
    const val UNIQUE_WORK_NAME = "ledger_report_periodic"

    private const val TAG = "ReportScheduler"

    /** 应用级后台作用域：只用于“读设置 + 入队”这一小段逻辑。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 排期（或按最新设置重排）每日报表任务；幂等，可重复调用。 */
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            scope.launch {
                runCatching { enqueue(appContext) }
                    .onFailure { Log.w(TAG, "排期报表定时任务失败", it) }
            }
        }.onFailure { Log.w(TAG, "启动报表排期失败", it) }
    }

    /** 取消每日报表任务（用户关闭报表通知时调用）。 */
    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }.onFailure { Log.w(TAG, "取消报表定时任务失败", it) }
    }

    private suspend fun enqueue(appContext: Context) {
        // 从 Application 容器取设置，避免报表层直接依赖数据层内部实现（RepoFactory）。
        val app = appContext as? LedgerApplication
        val settings = app?.container?.repos?.settings?.current() ?: LedgerSettings()
        val delayMillis = initialDelayMillis(settings, System.currentTimeMillis())
        val request = PeriodicWorkRequestBuilder<ReportWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            // 用取消后重新入队，确保用户修改“生成时间”后立即按新时间排期；
            // UPDATE 会保留旧的 periodStartTime，改时间可能不生效。
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request
        )
    }

    /**
     * 距离下一次 reportHour:reportMinute 的毫秒数。
     *
     * 用 java.time 按本机时区计算：今天的目标时刻已过就顺延到明天；
     * 返回值至少 1 秒，避免“刚好等于当前时刻”导致立即触发。
     */
    private fun initialDelayMillis(settings: LedgerSettings, nowMillis: Long): Long {
        val zone = DateTimes.zone()
        val today = DateTimes.localDate(nowMillis)
        val hour = settings.reportHour.coerceIn(0, 23)
        val minute = settings.reportMinute.coerceIn(0, 59)
        val todayTarget = today.atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val nextTarget = if (todayTarget > nowMillis) {
            todayTarget
        } else {
            today.plusDays(1).atTime(hour, minute)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }
        return (nextTarget - nowMillis).coerceAtLeast(1_000L)
    }
}
