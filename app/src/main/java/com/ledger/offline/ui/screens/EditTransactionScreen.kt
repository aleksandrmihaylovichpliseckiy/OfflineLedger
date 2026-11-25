@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ledger.offline.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.offline.di.AppContainer
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.tr
import com.ledger.offline.ui.components.OptionPickerDialog
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.Money

/**
 * 记一笔 / 编辑流水表单：金额、收支类型、分类、账户、商户、备注、日期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    container: AppContainer,
    txId: Long,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: EditTransactionViewModel = viewModel(
        factory = remember(container, txId) { EditTransactionViewModel.factory(container.repos, txId) }
    )
    val amountText by viewModel.amountText.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val categoryId by viewModel.categoryId.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
    val merchant by viewModel.merchant.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()
    val occurredAt by viewModel.occurredAt.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    var showCategoryPicker by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }

    val typeCategories = categories.filter { it.type == type }
    val selectedCategory = typeCategories.firstOrNull { it.id == categoryId }
    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    val amountError = amountText.isNotBlank() && Money.parseToCents(amountText) == null

    LaunchedEffect(viewModel) {
        viewModel.saved.collect { onBack() }
    }
    LaunchedEffect(viewModel) {
        viewModel.error.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // 分类 / 账户的默认选项，避免出现“未选择”导致保存时兜底
    LaunchedEffect(typeCategories) {
        if (typeCategories.isNotEmpty() && typeCategories.none { it.id == categoryId }) {
            viewModel.selectCategory(typeCategories.first().id)
        }
    }
    LaunchedEffect(accounts) {
        if (accounts.isNotEmpty() && accounts.none { it.id == accountId }) {
            viewModel.selectAccount(accounts.first().id)
        }
    }

    val date = DateTimes.localDate(occurredAt)
    val datePickerDialog = remember(context, date.year, date.monthValue, date.dayOfMonth) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth -> viewModel.onDatePicked(year, month + 1, dayOfMonth) },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) tr("编辑流水") else tr("记一笔")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !saving && !loading) {
                        Text(if (saving) tr("保存中…") else tr("保存"))
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TxType.EXPENSE,
                        onClick = { viewModel.onTypeChange(TxType.EXPENSE) },
                        label = { Text(tr("支出")) }
                    )
                    FilterChip(
                        selected = type == TxType.INCOME,
                        onClick = { viewModel.onTypeChange(TxType.INCOME) },
                        label = { Text(tr("收入")) }
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = viewModel::onAmountChange,
                    label = { Text(tr("金额（元）")) },
                    singleLine = true,
                    isError = amountError,
                    supportingText = {
                        if (amountError) {
                            Text(tr("金额格式不正确，例如 12.50"))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                PickerField(
                    label = tr("分类"),
                    value = selectedCategory?.let { "${it.icon} ${it.name}" } ?: tr("请选择分类"),
                    onClick = { showCategoryPicker = true }
                )

                PickerField(
                    label = tr("账户"),
                    value = selectedAccount?.displayName ?: tr("请选择账户"),
                    onClick = { showAccountPicker = true }
                )

                PickerField(
                    label = tr("日期"),
                    value = "${DateTimes.formatDate(occurredAt)} ${DateTimes.weekdayCn(occurredAt)}",
                    onClick = { datePickerDialog.show() }
                )

                OutlinedTextField(
                    value = merchant,
                    onValueChange = viewModel::onMerchantChange,
                    label = { Text(tr("商户（可选）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = viewModel::onNoteChange,
                    label = { Text(tr("备注（可选）")) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = viewModel::save,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(if (saving) tr("保存中…") else tr("保存"))
                }
            }
        }
    }

    if (showCategoryPicker) {
        OptionPickerDialog(
            title = tr("选择分类"),
            options = typeCategories.map { "${it.icon} ${it.name}" },
            selectedIndex = typeCategories.indexOfFirst { it.id == categoryId },
            onPick = { index ->
                typeCategories.getOrNull(index)?.let { viewModel.selectCategory(it.id) }
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false }
        )
    }

    if (showAccountPicker) {
        OptionPickerDialog(
            title = tr("选择账户"),
            options = accounts.map { it.displayName },
            selectedIndex = accounts.indexOfFirst { it.id == accountId },
            onPick = { index ->
                accounts.getOrNull(index)?.let { viewModel.selectAccount(it.id) }
                showAccountPicker = false
            },
            onDismiss = { showAccountPicker = false }
        )
    }
}

/** 表单项样式的选择入口：上方小标题、下方当前值。 */
@Composable
private fun PickerField(label: String, value: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = value, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = tr("展开选择"))
    }
}
