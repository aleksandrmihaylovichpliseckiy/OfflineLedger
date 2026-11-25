package com.ledger.offline.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.IngestStats
import com.ledger.offline.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 短信导入入口：扫描系统短信收件箱并解析入库。
 *
 * UI 的“立即扫描短信”与启动自动扫描都只依赖这里的两个公开方法；
 * 实时广播由 [SmsReceiver] 复用 [IngestWriter] 的入库逻辑。
 */
object LedgerIngest {

    private const val TAG = "LedgerIngest"

    private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** 收件箱查询投影：契约要求只读 _id / address / body / date。 */
    private val SMS_PROJECTION = arrayOf("_id", "address", "body", "date")

    /**
     * 是否已授予 READ_SMS 权限。
     * 未授予时扫描不会抛异常，而是返回 `permissionDenied = true` 的结果。
     */
    fun hasSmsPermission(context: Context): Boolean = try {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    } catch (throwable: Throwable) {
        Log.w(TAG, "检查短信权限失败", throwable)
        false
    }

    /**
     * 立即扫描一次短信收件箱。
     *
     * 流程（CONTRACT 3.2）：
     * 1. 无 READ_SMS 权限直接返回 permissionDenied；
     * 2. 读取启用的短信来源与设置；
     * 3. 查询最近 `smsLookbackDays` 天的收件箱，按发送方包含匹配来源，按时间倒序；
     * 4. 逐条解析 → 解析账户 / 刷新余额 / 归类 / 生成 sourceRef → 插入（重复跳过）；
     * 5. 返回扫描统计。
     */
    suspend fun scanSmsNow(context: Context, repos: LedgerRepos): IngestStats {
        if (!hasSmsPermission(context)) {
            return IngestStats(permissionDenied = true)
        }
        return withContext(Dispatchers.IO) {
            try {
                scanInternal(context, repos)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(TAG, "扫描短信失败", throwable)
                IngestStats(
                    failed = 1,
                    message = I18n.t(
                        "扫描短信失败：%1\$s",
                        throwable.message ?: I18n.t("未知错误")
                    )
                )
            }
        }
    }

    private suspend fun scanInternal(context: Context, repos: LedgerRepos): IngestStats {
        val settings = repos.settings.current()
        val sources = repos.smsSources.getEnabled()
        if (sources.isEmpty()) {
            return IngestStats(message = I18n.t("尚未启用任何银行短信来源"))
        }

        val lookbackDays = settings.smsLookbackDays.coerceAtLeast(1)
        val cutoff = System.currentTimeMillis() - lookbackDays * ONE_DAY_MILLIS

        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                SMS_PROJECTION,
                "date >= ?",
                arrayOf(cutoff.toString()),
                "date DESC"
            )
        } catch (security: SecurityException) {
            Log.w(TAG, "读取短信收件箱被拒绝", security)
            return IngestStats(permissionDenied = true)
        }
        if (cursor == null) {
            return IngestStats(failed = 1, message = I18n.t("无法打开短信收件箱"))
        }

        var scanned = 0
        var imported = 0
        var skipped = 0
        var failed = 0

        cursor.use { data ->
            val addressIndex = data.getColumnIndex("address")
            val bodyIndex = data.getColumnIndex("body")
            val dateIndex = data.getColumnIndex("date")
            if (addressIndex < 0 || bodyIndex < 0 || dateIndex < 0) return@use

            while (data.moveToNext()) {
                val address = data.getString(addressIndex) ?: ""
                val body = data.getString(bodyIndex) ?: ""
                val dateMillis = data.getLong(dateIndex)
                if (dateMillis < cutoff) continue

                // 多个来源命中同一号码时只处理一次（取第一个命中的来源）。
                val source = IngestWriter.findMatchingSource(sources, address) ?: continue

                scanned++
                val outcome = try {
                    IngestWriter.importSmsMessage(
                        repos = repos,
                        source = source,
                        address = address,
                        body = body,
                        occurredAt = dateMillis,
                        autoCategorize = settings.autoCategorize
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    Log.w(TAG, "导入一条短信失败", throwable)
                    ImportOutcome.FAILED
                }
                when (outcome) {
                    ImportOutcome.IMPORTED -> imported++
                    ImportOutcome.SKIPPED -> skipped++
                    ImportOutcome.FAILED -> failed++
                }
            }
        }

        return IngestStats(
            scanned = scanned,
            imported = imported,
            skipped = skipped,
            failed = failed
        )
    }
}
