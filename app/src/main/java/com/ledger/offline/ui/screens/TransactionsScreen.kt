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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.offline.di.AppContainer
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.tr
import com.ledger.offline.ui.components.AmountText
import com.ledger.offline.ui.components.EmptyHint
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.Money

/**
 * 明细页：按日期分组的流水列表，支持类型 / 来源 / 时间筛选，点击编辑、可删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit
) {
    val viewModel: TransactionsViewModel = viewModel(
        factory = remember(container) { TransactionsViewModel.factory(container.repos) }
    )
    val records by viewModel.records.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    val rangeFilter by viewModel.rangeFilter.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TransactionRecord?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("明细")) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTransaction) {
                Icon(Icons.Filled.Add, contentDescription = tr("记一笔"))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                FilterBar(
                    typeFilter = typeFilter,
                    sourceFilter = sourceFilter,
                    rangeFilter = rangeFilter,
                    onTypeChange = viewModel::setTypeFilter,
                    onSourceChange = viewModel::setSourceFilter,
                    onRangeChange = viewModel::setRangeFilter
                )
            }
            val grouped = records.groupBy { DateTimes.startOfDay(it.tx.occurredAt) }
            if (records.isEmpty()) {
                item { EmptyHint(tr("没有符合筛选条件的流水")) }
            } else {
                grouped.keys.sortedDescending().forEach { dayStart ->
                    val dayRecords = grouped[dayStart].orEmpty()
                    item(key = "day-$dayStart") { DayHeader(dayStart, dayRecords) }
                    items(dayRecords, key = { it.tx.id }) { record ->
                        TransactionRow(
                            record = record,
                            onClick = { onEditTransaction(record.tx.id) },
                            onDelete = { pendingDelete = record }
                        )
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("删除流水")) },
            text = {
                Text(tr("确定删除这笔 %1\$s 的流水吗？删除后不可恢复。", Money.signed(toDelete.tx.amountCents, toDelete.tx.type == TxType.EXPENSE)))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(toDelete.tx.id)
                    pendingDelete = null
                }) { Text(tr("删除")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("取消")) }
            }
        )
    }
}

/** 类型 / 来源 / 时间三组筛选。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(
    typeFilter: TxType?,
    sourceFilter: TxSource?,
    rangeFilter: RangeFilter,
    onTypeChange: (TxType?) -> Unit,
    onSourceChange: (TxSource?) -> Unit,
    onRangeChange: (RangeFilter) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = typeFilter == null,
                onClick = { onTypeChange(null) },
                label = { Text(tr("全部类型")) }
            )
            FilterChip(
                selected = typeFilter == TxType.EXPENSE,
                onClick = { onTypeChange(TxType.EXPENSE) },
                label = { Text(tr("支出")) }
            )
            FilterChip(
                selected = typeFilter == TxType.INCOME,
                onClick = { onTypeChange(TxType.INCOME) },
                label = { Text(tr("收入")) }
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = sourceFilter == null,
                onClick = { onSourceChange(null) },
                label = { Text(tr("全部来源")) }
            )
            TxSource.values().forEach { source ->
                FilterChip(
                    selected = sourceFilter == source,
                    onClick = { onSourceChange(source) },
                    label = { Text(tr(source.label)) }
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RangeFilter.values().forEach { range ->
                FilterChip(
                    selected = rangeFilter == range,
                    onClick = { onRangeChange(range) },
                    label = { Text(tr(range.label)) }
                )
            }
        }
    }
}

/** 日期分组标题：日期 + 当天收支小计。 */
@Composable
private fun DayHeader(dayStart: Long, records: List<TransactionRecord>) {
    val income = records.filter { it.tx.type == TxType.INCOME }.sumOf { it.tx.amountCents }
    val expense = records.filter { it.tx.type == TxType.EXPENSE }.sumOf { it.tx.amountCents }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(DateTimes.humanDay(dayStart), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                text = tr("支出 %1\$s · 收入 %2\$s", Money.format(expense), Money.format(income)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
    }
}

/**
 * 单笔流水：点击进入编辑，右侧删除按钮。
 *
 * v1.2.0 起每行明确展示资金方向：
 * - 支出：“去向：<商户/分类>” + “来自：<账户>”；
 * - 收入：“来源：<商户/分类>” + “入账：<账户>”；
 * - 时间统一精确到秒，并保留分类与来源标签。
 */
@Composable
private fun TransactionRow(
    record: TransactionRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val tx = record.tx
    val expense = tx.type == TxType.EXPENSE
    val transfer = tx.type == TxType.TRANSFER
    // 商户是用户数据，不翻译；缺失时退回分类名（契约里的 <merchant/category>）
    val counterparty = tx.merchant.ifBlank { record.categoryName }
    val directionText = when (tx.type) {
        TxType.EXPENSE -> tr("去向：%1\$s", counterparty)
        TxType.INCOME -> tr("来源：%1\$s", counterparty)
        TxType.TRANSFER -> tr("转账：%1\$s", counterparty)
    }
    val accountText = when (tx.type) {
        TxType.EXPENSE -> tr("来自：%1\$s", record.accountName)
        TxType.INCOME -> tr("入账：%1\$s", record.accountName)
        TxType.TRANSFER -> tr("账户：%1\$s", record.accountName)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(record.categoryIcon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "$directionText · ${tr(tx.source.label)}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = accountText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${record.categoryName} · ${DateTimes.formatDateTimeSeconds(tx.occurredAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        AmountText(cents = tx.amountCents, expense = expense, neutral = transfer)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = tr("删除"))
        }
    }
}
