package com.ledger.offline.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ledger.offline.LedgerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 实时短信广播接收器（Manifest 已注册，带 BROADCAST_SMS 权限）。
 *
 * 用 `goAsync()` + IO 协程把解析入库放到后台线程，避免在主线程做 ContentResolver / Room 查询；
 * 同一发送方 + 时间戳的多条分片短信先拼接成完整正文再解析。
 * 任何异常都吞掉并写 Log，绝不把异常抛回系统。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        val receivedIntent = intent ?: return
        if (receivedIntent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                consume(appContext, receivedIntent)
            } catch (throwable: Throwable) {
                Log.w(TAG, "处理实时短信失败", throwable)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun consume(context: Context, intent: Intent) {
        val app = context.applicationContext as? LedgerApplication ?: return
        val repos = app.container.repos

        val settings = repos.settings.current()
        // 用户关闭“自动导入”后，实时广播也不再入库，与启动扫描保持一致。
        if (!settings.smsAutoScan) return

        val sources = repos.smsSources.getEnabled()
        if (sources.isEmpty()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // 同一发送方 + 时间戳的分片短信拼成一条完整正文。
        val grouped = LinkedHashMap<String, GroupedSms>()
        for (message in messages) {
            if (message == null) continue
            val address = message.originatingAddress?.trim().orEmpty()
            val body = message.messageBody.orEmpty()
            val timestamp = message.timestampMillis
            val key = address + "|" + timestamp
            grouped.getOrPut(key) { GroupedSms(address, timestamp, StringBuilder()) }
                .body
                .append(body)
        }

        for (item in grouped.values) {
            val source = IngestWriter.findMatchingSource(sources, item.address) ?: continue
            runCatching {
                IngestWriter.importSmsMessage(
                    repos = repos,
                    source = source,
                    address = item.address,
                    body = item.body.toString(),
                    occurredAt = item.dateMillis,
                    autoCategorize = settings.autoCategorize
                )
            }.onFailure { throwable ->
                Log.w(TAG, "实时短信入库失败", throwable)
            }
        }
    }

    /** 一条已拼接完整的分片短信。 */
    private data class GroupedSms(
        val address: String,
        val dateMillis: Long,
        val body: StringBuilder
    )

    private companion object {
        private const val TAG = "SmsReceiver"
    }
}
