package com.ledger.offline.report

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ledger.offline.MainActivity
import com.ledger.offline.R
import com.ledger.offline.domain.model.LedgerReport
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.i18n.I18n
import com.ledger.offline.util.Money

/**
 * 报表本地通知。
 *
 * - 渠道固定为 [CHANNEL_ID]（“账本报表”），由 [ensureChannels] 幂等创建；
 * - 通知 id 按周期固定，同一周期的新报表覆盖旧通知，不会越积越多；
 * - 点击打开 [MainActivity]，并携带 `EXTRA_REPORT_PERIOD` 让界面直接跳到报表页；
 * - 通知总开关关闭 / Android 13+ 未授予 POST_NOTIFICATIONS 时静默降级，绝不抛异常。
 */
object ReportNotifier {

    /** 通知点击时带给 [MainActivity] 的周期参数，值为 `ReportPeriod.name`。 */
    const val EXTRA_REPORT_PERIOD = "extra_report_period"

    /** 报表通知渠道 id（LedgerApplication.onCreate 已调用 [ensureChannels]）。 */
    const val CHANNEL_ID = "ledger_reports"

    private const val TAG = "ReportNotifier"

    /** 周期 → 通知 id：固定值，新报表覆盖同周期旧通知。 */
    private fun notificationId(period: ReportPeriod): Int = 1000 + period.ordinal

    /** 创建通知渠道；已存在时直接返回，重复调用不会报错。 */
    fun ensureChannels(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    I18n.t("账本报表"),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = I18n.t("日报 / 周报 / 月报 / 年报")
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }.onFailure { Log.w(TAG, "创建报表通知渠道失败", it) }
    }

    /**
     * 发送报表通知：摘要一行 + BigTextStyle 展开完整正文。
     *
     * 权限已由 [canNotify] 手动检查（通知总开关 + Android 13+ 运行时权限），
     * 这里用 @SuppressLint 关掉 lint 对 notify() 的静态权限告警。
     */
    @SuppressLint("MissingPermission")
    fun notify(context: Context, report: LedgerReport) {
        runCatching {
            if (!canNotify(context)) {
                Log.i(TAG, "通知不可用（权限未授予或总开关关闭），跳过报表通知")
                return@runCatching
            }
            ensureChannels(context)

            val title = I18n.t("自动记账 · %1\$s", I18n.t(report.period.label))
            val summary = I18n.t(
                "支出 %1\$s · 收入 %2\$s",
                Money.format(report.totalExpenseCents),
                Money.format(report.totalIncomeCents)
            )
            // 锁屏 / 通知栏折叠时只显示汇总，不展示完整财务明细（隐私保护）。
            val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ledger)
                .setContentTitle(title)
                .setContentText(summary)
                .setContentIntent(buildContentIntent(context, report.period))
                .build()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ledger)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(report.text))
                .setContentIntent(buildContentIntent(context, report.period))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build()
            NotificationManagerCompat.from(context)
                .notify(notificationId(report.period), notification)
        }.onFailure { Log.w(TAG, "发送报表通知失败", it) }
    }

    /** 通知能力检查：总开关 + Android 13+ 的 POST_NOTIFICATIONS 运行时权限。 */
    private fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    /** 点击通知回到 MainActivity，singleTop + CLEAR_TOP 保证不会叠出一堆页面。 */
    private fun buildContentIntent(context: Context, period: ReportPeriod): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_REPORT_PERIOD, period.name)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(period),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
