@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ledger.offline.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.offline.di.AppContainer
import com.ledger.offline.domain.model.LedgerReport
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.i18n.tr
import com.ledger.offline.ui.components.EmptyHint
import com.ledger.offline.ui.components.SectionCard
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.Money
import kotlinx.coroutines.launch

/**
 * 报表页：日报 / 周报 / 月报切换、生成、复制正文、发通知、历史报表。
 *
 * @param periodArg 从通知或首页快捷入口带入的周期字符串（DAILY/WEEKLY/MONTHLY）。
 * @param autoGenerate 进入页面后是否立即生成（首页快捷按钮使用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    periodArg: String?,
    autoGenerate: Boolean
) {
    val context = LocalContext.current.applicationContext
    val initialPeriod = remember(periodArg) {
        periodArg
            ?.let { raw -> runCatching { ReportPeriod.valueOf(raw.trim().uppercase()) }.getOrNull() }
            ?: ReportPeriod.DAILY
    }
    val viewModel: ReportsViewModel = viewModel(
        factory = remember(container, initialPeriod) {
            ReportsViewModel.factory(context, container.repos, initialPeriod)
        }
    )
    val period by viewModel.period.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // onClick 不是 @Composable 上下文，文案先在这里取好
    val copiedMessage = tr("报表正文已复制到剪贴板")

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // 通知 / 快捷入口带入的周期变化时同步到 ViewModel；autoGenerate 只在进入时生成一次。
    LaunchedEffect(initialPeriod, autoGenerate) {
        viewModel.selectPeriod(initialPeriod)
        if (autoGenerate) viewModel.generate()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("报表")) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { PeriodSelector(period = period, onSelect = viewModel::selectPeriod) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::generate, enabled = !generating) {
                        Text(if (generating) tr("生成中…") else tr("生成"))
                    }
                    OutlinedButton(onClick = viewModel::generateAndNotify, enabled = !generating) {
                        Text(tr("生成并发通知"))
                    }
                    OutlinedButton(
                        onClick = {
                            val report = current
                            if (report != null) {
                                clipboard.setText(AnnotatedString(report.text))
                                scope.launch {
                                    snackbarHostState.showSnackbar(copiedMessage)
                                }
                            }
                        },
                        enabled = current != null
                    ) {
                        Text(tr("复制"))
                    }
                }
            }

            item {
                val report = current
                SectionCard(title = report?.title ?: tr("报表正文")) {
                    if (report == null) {
                        EmptyHint(tr("选择周期后点“生成”，这里会显示纯文本报表，可直接复制或发通知"))
                    } else {
                        Text(
                            text = tr(
                                "生成于 %1\$s · 共 %2\$d 笔",
                                DateTimes.formatDateTime(report.generatedAt),
                                report.txCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SelectionContainer {
                            Text(
                                text = report.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = tr("历史报表")) {
                    if (history.isEmpty()) {
                        EmptyHint(tr("还没有历史报表，生成的报表会按月 / 日归档在这里"))
                    } else {
                        history.forEach { report ->
                            HistoryRow(
                                report = report,
                                selected = current?.id == report.id,
                                onClick = { viewModel.showReport(report) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日报 / 周报 / 月报 / 年报切换（[ReportPeriod.values] 已包含 YEARLY）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PeriodSelector(period: ReportPeriod, onSelect: (ReportPeriod) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ReportPeriod.values().forEach { item ->
            FilterChip(
                selected = item == period,
                onClick = { onSelect(item) },
                label = { Text(tr(item.label)) }
            )
        }
    }
}

/** 历史报表行。 */
@Composable
private fun HistoryRow(report: LedgerReport, selected: Boolean, onClick: () -> Unit) {
    val periodLabel = tr(report.period.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = report.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = tr(
                    "%1\$s · 支出 %2\$s · 收入 %3\$s",
                    periodLabel,
                    Money.format(report.totalExpenseCents),
                    Money.format(report.totalIncomeCents)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = DateTimes.formatDateTime(report.generatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = tr(if (selected) "当前" else "查看"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
