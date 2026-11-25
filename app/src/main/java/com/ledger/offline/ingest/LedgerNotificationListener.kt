package com.ledger.offline.ingest

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ledger.offline.LedgerApplication
import com.ledger.offline.domain.LedgerRepos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知使用权服务（Manifest 已注册）：解析用户在设置里勾选的应用的动账通知。
 *
 * 设计要点（CONTRACT v3 §3.1）：
 * - 不再硬编码只处理微信 / 支付宝，包名过滤改为 `settings.notifyPackages`（CSV）；
 * - 跳过 group summary（汇总通知）与 ongoing（常驻通知）；
 * - 解析不出交易的通知（聊天、群消息、营销）直接忽略；
 * - 用“同包 + 同时间 + 同金额 + 同正文”的 60 秒内存窗口去重，避免系统重复回调导致重复记账；
 * - 本服务自管 [CoroutineScope]，onNotificationPosted 里只做轻量解析与去重判断，
 *   设置读取与数据库写入全部丢到 IO 协程，onDestroy 时取消协程作用域；
 * - 通知使用权由系统授予，与 trustMode / 隐私模式无关，这里不做额外开关判断；
 * - 所有回调异常吞掉并写 Log，不抛回系统。
 */
class LedgerNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dedupeLock = Any()

    /** 最近的去重键与时间，只保留 60 秒窗口内的记录。 */
    private val recentKeys = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        try {
            handleNotification(posted)
        } catch (throwable: Throwable) {
            Log.w(TAG, "处理通知回调失败", throwable)
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val packageName: String = sbn.packageName.orEmpty()
        if (packageName.isBlank()) return
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return
        // 跳过群汇总通知，否则一条群消息会被解析多次。
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        // EXTRA_TEXT 经常被截断，把 bigText / subText 合并起来一起解析。
        val body = listOf(text, bigText, subText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (title.isBlank() && body.isBlank()) return

        // 解析与去重都是纯内存操作，放在回调线程上，避免把明显的聊天通知也丢进协程。
        val parsed = runCatching {
            NotificationParser.parse(packageName, title, body, sbn.postTime)
        }.onFailure { throwable ->
            Log.w(TAG, "解析支付通知失败", throwable)
        }.getOrNull() ?: return

        // 契约要求：同包 + 同金额 + 同正文做短窗口去重；再把 postTime 计入键，
        // 避免 60 秒内两笔同金额同正文的真实交易被误判为重复。
        val dedupeKey = packageName + "|" + sbn.postTime + "|" + parsed.amountCents + "|" + body
        if (isDuplicate(dedupeKey)) return

        val app = application as? LedgerApplication ?: return
        val repos: LedgerRepos = app.container.repos

        serviceScope.launch {
            try {
                val settings = repos.settings.current()
                // 只处理“需要解析支付通知的应用”里勾选的包（CSV），默认微信 + 支付宝。
                if (!isPackageEnabled(settings.notifyPackages, packageName)) return@launch

                IngestWriter.importNotification(
                    repos = repos,
                    packageName = packageName,
                    title = title,
                    text = body,
                    postTime = sbn.postTime,
                    autoCategorize = settings.autoCategorize,
                    appLabel = resolveAppLabel(packageName)
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(TAG, "导入应用通知流水失败：$packageName", throwable)
            }
        }
    }

    /** 设置里的 `notifyPackages` 是逗号分隔的包名列表；空白项忽略。 */
    private fun isPackageEnabled(csv: String, packageName: String): Boolean =
        csv.split(',', '，')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { it == packageName }

    /** 取应用显示名；取不到（已卸载 / 隐藏应用）时退回包名。 */
    @Suppress("DEPRECATION")
    private fun resolveAppLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault("").trim().ifBlank { packageName }

    /** 60 秒内的同键通知视为重复；返回 true 表示本次应跳过。 */
    private fun isDuplicate(key: String): Boolean = synchronized(dedupeLock) {
        val now = System.currentTimeMillis()
        val iterator = recentKeys.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > DEDUPE_WINDOW_MILLIS) {
                iterator.remove()
            }
        }
        val exists = recentKeys.containsKey(key)
        recentKeys[key] = now
        exists
    }

    override fun onDestroy() {
        serviceScope.cancel()
        synchronized(dedupeLock) { recentKeys.clear() }
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "LedgerNotificationListener"
        private const val DEDUPE_WINDOW_MILLIS = 60_000L
    }
}
