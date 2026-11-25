package com.ledger.offline

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ledger.offline.report.ReportNotifier
import com.ledger.offline.ui.LedgerApp

/**
 * 唯一的 Activity。界面全部由 Compose 承载，
 * 点报表通知进来时会把周期（DAILY/WEEKLY/MONTHLY）带给界面，直接跳到报表页。
 */
class MainActivity : ComponentActivity() {

    private val reportPeriodExtra = mutableStateOf<String?>(null)

    /** 每次收到“报表通知”点击都自增，即使周期字符串相同也能触发界面跳转。 */
    private val reportRequestNonce = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(ReportNotifier.EXTRA_REPORT_PERIOD)?.let { period ->
            reportPeriodExtra.value = period
            reportRequestNonce.intValue += 1
        }
        val container = (application as LedgerApplication).container
        setContent {
            LedgerApp(
                container = container,
                initialReportPeriod = reportPeriodExtra.value,
                reportRequestNonce = reportRequestNonce.intValue
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ReportNotifier.EXTRA_REPORT_PERIOD)?.let { period ->
            reportPeriodExtra.value = period
            reportRequestNonce.intValue += 1
        }
    }
}
