package com.ledger.offline.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.LedgerReport
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.i18n.I18n
import com.ledger.offline.report.ReportCenter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 报表页：选择周期、生成报表、发通知、浏览历史报表。
 */
class ReportsViewModel(
    private val context: Context,
    private val repos: LedgerRepos,
    initialPeriod: ReportPeriod
) : ViewModel() {

    private val _period = MutableStateFlow(initialPeriod)
    val period: StateFlow<ReportPeriod> = _period.asStateFlow()

    private val _current = MutableStateFlow<LedgerReport?>(null)
    val current: StateFlow<LedgerReport?> = _current.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val history: StateFlow<List<LedgerReport>> = repos.reports.observeRecent(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun selectPeriod(period: ReportPeriod) {
        _period.value = period
    }

    /** 点击历史报表时，把正文切换为历史内容。 */
    fun showReport(report: LedgerReport) {
        _current.value = report
        _period.value = report.period
    }

    /** 只生成并入库，不发送通知。 */
    fun generate() {
        if (_generating.value) return
        val period = _period.value
        viewModelScope.launch {
            _generating.value = true
            try {
                val report = ReportCenter.generate(period, System.currentTimeMillis(), repos)
                _current.value = report
                _messages.emit(I18n.t("已生成%1\$s：%2\$s", I18n.t(period.label), report.title))
            } catch (e: Exception) {
                _messages.emit(I18n.t("生成失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
            _generating.value = false
        }
    }

    /** 生成并发送系统通知（由 report 模块统一处理通知渠道与权限）。 */
    fun generateAndNotify() {
        if (_generating.value) return
        val period = _period.value
        viewModelScope.launch {
            _generating.value = true
            try {
                val report = ReportCenter.generateAndNotify(context, period, repos)
                _current.value = report
                _messages.emit(I18n.t("已生成%1\$s，并尝试发送通知", I18n.t(period.label)))
            } catch (e: Exception) {
                _messages.emit(I18n.t("生成失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
            _generating.value = false
        }
    }

    companion object {
        fun factory(
            context: Context,
            repos: LedgerRepos,
            initialPeriod: ReportPeriod
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportsViewModel(context, repos, initialPeriod) }
        }
    }
}
