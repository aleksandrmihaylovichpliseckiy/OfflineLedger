@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ledger.offline.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.offline.AppVersion
import com.ledger.offline.ax.AutoUpdateController
import com.ledger.offline.di.AppContainer
import com.ledger.offline.domain.BalanceCorrectionResult
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.AppLanguage
import com.ledger.offline.domain.model.BottomTab
import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.DarkMode
import com.ledger.offline.domain.model.DedupeMode
import com.ledger.offline.domain.model.HomeSection
import com.ledger.offline.domain.model.IconColorMode
import com.ledger.offline.domain.model.LedgerSettings
import com.ledger.offline.domain.model.NotificationKind
import com.ledger.offline.domain.model.ReportMode
import com.ledger.offline.domain.model.SmsSource
import com.ledger.offline.domain.model.ThemeTone
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.domain.model.UiDensity
import com.ledger.offline.i18n.tr
import com.ledger.offline.ui.ChangelogDialog
import com.ledger.offline.ui.components.EmptyHint
import com.ledger.offline.ui.components.OptionPickerDialog
import com.ledger.offline.ui.components.SectionCard
import com.ledger.offline.util.FilterPrefs
import com.ledger.offline.util.LayoutPrefs
import com.ledger.offline.util.Money

/**
 * 设置页：使用场景、权限引导、短信来源（默认折叠）、动账信源（含支付通知应用与通知类型筛选、
 * 屏蔽词清单、自动去重）、报表通知与折叠式日期滚轮、外观与语言（默认折叠，含日夜模式、图标配色）、
 * 页面布局、自动归类、分类管理（默认折叠）/ 账户管理（含“余额修正”入口）、当前版本与关于。
 *
 * 所有设置项修改后立即写回 DataStore；报表相关项会重新排期 WorkManager 任务。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = remember(container) { SettingsViewModel.factory(context.applicationContext, container.repos) }
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val smsSources by viewModel.smsSources.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val accountDeleteState by viewModel.accountDeleteState.collectAsStateWithLifecycle()
    val correctingBalances by viewModel.correctingBalances.collectAsStateWithLifecycle()
    val lastBalanceCorrection by viewModel.lastBalanceCorrection.collectAsStateWithLifecycle()
    val notifyApps by viewModel.notifyApps.collectAsStateWithLifecycle()

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> viewModel.refreshPermissions() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.refreshPermissions() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // 从系统设置页（通知使用权 / 无障碍）返回时重新检测权限与无障碍状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 各类编辑弹窗状态
    var editingSource by remember { mutableStateOf<SmsSource?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<SmsSource?>(null) }

    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }

    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var showAccountDialog by remember { mutableStateOf(false) }

    // “需要解析支付通知的应用”二级菜单与“更新日志”弹窗
    var showNotifyAppsDialog by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }

    // 报表生成时间选择（平台 TimePickerDialog）
    val timePickerDialog = remember(context, settings.reportHour, settings.reportMinute) {
        TimePickerDialog(
            context,
            { _, hour, minute -> viewModel.setReportTime(hour, minute) },
            settings.reportHour,
            settings.reportMinute,
            true
        )
    }

    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("设置")) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                UsageSection(
                    settings = settings,
                    permissions = permissions,
                    onSimpleModeChange = viewModel::setSimpleMode,
                    onTrustModeChange = viewModel::setTrustMode,
                    onPrivacyModeChange = viewModel::setPrivacyMode,
                    onAutoUpdateDelayChange = viewModel::setAutoUpdateDelay,
                    onOpenAccessibilitySettings = {
                        runCatching { AutoUpdateController.openAccessibilitySettings(context) }
                    }
                )
            }

            item {
                PermissionSection(
                    permissions = permissions,
                    needsNotificationPermission = needsNotificationPermission,
                    onRequestSms = {
                        smsPermissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                        )
                    },
                    onOpenListenerSettings = { openNotificationListenerSettings(context) },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            item {
                SmsSourceSection(
                    sources = smsSources,
                    fundTrackingEnabled = settings.fundTrackingEnabled,
                    onFundTrackingChange = viewModel::setFundTrackingEnabled,
                    onAdd = {
                        editingSource = null
                        showSourceDialog = true
                    },
                    onToggle = viewModel::toggleSmsSource,
                    onEdit = { source ->
                        editingSource = source
                        showSourceDialog = true
                    },
                    onDelete = { source -> sourceToDelete = source }
                )
            }

            item {
                SourceSection(
                    settings = settings,
                    onAutoScanChange = viewModel::setSmsAutoScan,
                    onManageNotifyApps = {
                        // 每次打开二级菜单都重新读取已安装应用，避免安装 / 卸载后列表过期。
                        viewModel.loadNotifyApps()
                        showNotifyAppsDialog = true
                    },
                    onDedupeModeChange = viewModel::setDedupeMode,
                    onLookbackChange = viewModel::setSmsLookbackDays,
                    onBlockWordsChange = viewModel::setBlockWords
                )
            }

            item {
                ReportSettingsSection(
                    settings = settings,
                    onReportNotificationsChange = viewModel::setReportNotifications,
                    onDailyChange = viewModel::setDailyReportEnabled,
                    onWeeklyChange = viewModel::setWeeklyReportEnabled,
                    onMonthlyChange = viewModel::setMonthlyReportEnabled,
                    onYearlyChange = viewModel::setYearlyReportEnabled,
                    onPickTime = { timePickerDialog.show() },
                    onWeeklyDayChange = viewModel::setWeeklyDay,
                    onMonthlyDayChange = viewModel::setMonthlyDay,
                    onYearlyDateChange = viewModel::setYearlyDate,
                    onReportModeChange = viewModel::setReportMode
                )
            }

            item {
                AppearanceSection(
                    settings = settings,
                    onLanguageChange = viewModel::setLanguage,
                    onThemeToneChange = viewModel::setThemeTone,
                    onDensityChange = viewModel::setDensity,
                    onDarkModeChange = viewModel::setDarkMode,
                    onIconColorModeChange = viewModel::setIconColorMode,
                    onIconToneChange = viewModel::setIconTone
                )
            }

            item {
                LayoutSection(
                    settings = settings,
                    onMoveHomeSection = viewModel::moveHomeSection,
                    onToggleHomeSection = viewModel::toggleHomeSection,
                    onMoveBottomNav = viewModel::moveBottomNav,
                    onToggleBottomNav = viewModel::toggleBottomNav,
                    onResetLayout = viewModel::resetLayout
                )
            }

            item {
                OtherSettingsSection(
                    settings = settings,
                    onAutoCategorizeChange = viewModel::setAutoCategorize
                )
            }

            item {
                CategorySection(
                    categories = categories,
                    onAdd = {
                        editingCategory = null
                        showCategoryDialog = true
                    },
                    onEdit = { category ->
                        editingCategory = category
                        showCategoryDialog = true
                    },
                    onDelete = { category -> categoryToDelete = category }
                )
            }

            item {
                AccountSection(
                    accounts = accounts,
                    onAdd = {
                        editingAccount = null
                        showAccountDialog = true
                    },
                    onEdit = { account ->
                        editingAccount = account
                        showAccountDialog = true
                    },
                    onMove = viewModel::reorderAccount,
                    onDelete = viewModel::requestDeleteAccount,
                    correcting = correctingBalances,
                    lastCorrection = lastBalanceCorrection,
                    onCorrectBalances = viewModel::correctBalances
                )
            }

            item { AboutSection(onShowChangelog = { showChangelog = true }) }
        }
    }

    if (showSourceDialog) {
        SmsSourceEditorDialog(
            initial = editingSource,
            accounts = accounts,
            onDismiss = { showSourceDialog = false },
            onConfirm = { source ->
                viewModel.saveSmsSource(source)
                showSourceDialog = false
            }
        )
    }
    val deletingSource = sourceToDelete
    if (deletingSource != null) {
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text(tr("删除短信来源")) },
            text = {
                Text(
                    tr(
                        "确定删除“%1\$s”（%2\$s）吗？已导入的流水不受影响。",
                        deletingSource.label,
                        deletingSource.addressPattern
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSmsSource(deletingSource)
                    sourceToDelete = null
                }) { Text(tr("删除")) }
            },
            dismissButton = {
                TextButton(onClick = { sourceToDelete = null }) { Text(tr("取消")) }
            }
        )
    }

    if (showCategoryDialog) {
        CategoryEditorDialog(
            initial = editingCategory,
            onDismiss = { showCategoryDialog = false },
            onConfirm = { category ->
                viewModel.saveCategory(category)
                showCategoryDialog = false
            }
        )
    }
    val deletingCategory = categoryToDelete
    if (deletingCategory != null) {
        AlertDialog(
            onDismissRequest = { categoryToDelete = null },
            title = { Text(tr("删除分类")) },
            text = {
                Text(
                    tr(
                        "删除“%1\$s”后，已有流水会归到“其他支出 / 其他收入”。确定删除吗？",
                        deletingCategory.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCategory(deletingCategory)
                    categoryToDelete = null
                }) { Text(tr("删除")) }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDelete = null }) { Text(tr("取消")) }
            }
        )
    }

    if (showAccountDialog) {
        AccountEditorDialog(
            initial = editingAccount,
            onDismiss = { showAccountDialog = false },
            onConfirm = { account ->
                viewModel.saveAccount(account)
                showAccountDialog = false
            }
        )
    }

    // 账户删除：有流水时只提示不可删除，无流水时二次确认。
    val deletingAccount = accountDeleteState
    if (deletingAccount != null) {
        if (deletingAccount.canDelete) {
            AlertDialog(
                onDismissRequest = viewModel::dismissAccountDelete,
                title = { Text(tr("删除账户")) },
                text = {
                    Text(
                        tr(
                            "确定删除“%1\$s”吗？该账户没有流水，删除后不可恢复。",
                            deletingAccount.account.displayName
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDeleteAccount) { Text(tr("删除")) }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissAccountDelete) { Text(tr("取消")) }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = viewModel::dismissAccountDelete,
                title = { Text(tr("无法删除账户")) },
                text = {
                    Text(
                        tr(
                            "“%1\$s”还有 %2\$d 笔流水，不能删除。请先在明细里删除或转移这些流水。",
                            deletingAccount.account.displayName,
                            deletingAccount.txCount
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissAccountDelete) { Text(tr("知道了")) }
                }
            )
        }
    }

    // “需要解析支付通知的应用”二级菜单：搜索 + 勾选已安装启动器应用。
    if (showNotifyAppsDialog) {
        NotifyAppsDialog(
            apps = notifyApps,
            selected = LayoutPrefs.splitCsv(settings.notifyPackages).toSet(),
            kindFilters = FilterPrefs.parseNotifyKindFilters(settings.notifyKindFilters),
            onKindChange = viewModel::setNotifyKind,
            onDismiss = { showNotifyAppsDialog = false },
            onConfirm = { packages ->
                viewModel.setNotifyPackages(packages)
                showNotifyAppsDialog = false
            }
        )
    }

    // 点击版本行：查看本版本更新日志。
    if (showChangelog) {
        ChangelogDialog(onDismiss = { showChangelog = false })
    }
}

/**
 * 使用场景：简易 / 信任 / 隐私三行**整行**点击切换，右侧小圆点表示是否激活。
 *
 * 契约要求不使用 Switch 控件（避免与其它开关混淆），所以用圆点颜色区分状态；
 * 信任模式展开后额外提供“自动更新读取延迟”，写入 `autoUpdateDelaySeconds`。
 */
@Composable
private fun UsageSection(
    settings: LedgerSettings,
    permissions: PermissionState,
    onSimpleModeChange: (Boolean) -> Unit,
    onTrustModeChange: (Boolean) -> Unit,
    onPrivacyModeChange: (Boolean) -> Unit,
    onAutoUpdateDelayChange: (Int) -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    SectionCard(title = tr("使用场景")) {
        UsageRow(
            title = tr("简易模式"),
            description = tr("只保留收支汇总、账户与快捷操作，隐藏趋势、最近流水和分类统计"),
            active = settings.simpleMode,
            onToggle = { onSimpleModeChange(!settings.simpleMode) }
        )
        HorizontalDivider()
        UsageRow(
            title = tr("信任模式"),
            description = tr("开启后可在首页一键自动更新：读取微信 / 支付宝账单页与余额，自动补全流水"),
            active = settings.trustMode,
            onToggle = { onTrustModeChange(!settings.trustMode) }
        )
        if (settings.trustMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (permissions.accessibilityEnabled) {
                        tr("无障碍权限已开启")
                    } else {
                        tr("无障碍权限未开启")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (permissions.accessibilityEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenAccessibilitySettings) {
                    Text(
                        if (permissions.accessibilityEnabled) {
                            tr("去查看")
                        } else {
                            tr("去开启")
                        }
                    )
                }
            }
            Text(
                text = tr(
                    "自动更新需要系统“无障碍（无障碍服务）”权限，用它模拟点击并读取屏幕文字；" +
                        "读取到的内容只在内存中解析并写入本机数据库，不会上传。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NumberInputRow(
                title = tr("自动更新读取延迟（秒）"),
                description = tr("拉起应用后先等待这段时间再读屏，避免页面没加载完导致漏记；默认 5 秒。"),
                value = settings.autoUpdateDelaySeconds,
                range = 1..30,
                unit = tr("秒"),
                onValueChange = onAutoUpdateDelayChange
            )
        }
        HorizontalDivider()
        UsageRow(
            title = tr("隐私模式"),
            description = tr("开启后禁止截屏与录屏，界面内容不会出现在截图或最近任务预览中"),
            active = settings.privacyMode,
            onToggle = { onPrivacyModeChange(!settings.privacyMode) }
        )
    }
}

/** 使用场景的一行：整行可点击，右侧小圆点亮起表示已开启。 */
@Composable
private fun UsageRow(
    title: String,
    description: String,
    active: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
        )
    }
}

/**
 * 数字输入行（回溯天数 / 自动更新延迟共用）。
 *
 * 只接受数字，范围外的输入不写回设置，并在输入框下给出范围提示；
 * 失焦时把文本重置成当前的设置值，避免留下“空框”或越界数字。
 */
@Composable
private fun NumberInputRow(
    title: String,
    description: String,
    value: Int,
    range: IntRange,
    unit: String,
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var invalid by remember(value) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(4)
                    text = digits
                    val parsed = digits.toIntOrNull()
                    if (parsed != null && parsed in range) {
                        invalid = false
                        onValueChange(parsed)
                    } else {
                        invalid = digits.isNotEmpty()
                    }
                },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(84.dp)
                    .onFocusChanged { state ->
                        if (!state.isFocused) {
                            text = value.toString()
                            invalid = false
                        }
                    }
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (invalid) {
            Text(
                text = tr("请输入 %1\$d 到 %2\$d 之间的数字", range.first, range.last),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 权限与授权区。 */
@Composable
private fun PermissionSection(
    permissions: PermissionState,
    needsNotificationPermission: Boolean,
    onRequestSms: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onRequestNotification: () -> Unit
) {
    SectionCard(title = tr("权限与授权")) {
        PermissionRow(
            title = tr("短信读取权限"),
            granted = permissions.smsGranted,
            description = if (permissions.smsGranted) {
                tr("已授权：可读取指定号码的银行短信")
            } else {
                tr("未授权：无法自动导入银行短信，手动记账不受影响")
            },
            actionLabel = if (permissions.smsGranted) tr("重新授权") else tr("去授权"),
            onAction = onRequestSms
        )
        HorizontalDivider()
        PermissionRow(
            title = tr("通知使用权"),
            granted = permissions.notificationListenerEnabled,
            description = if (permissions.notificationListenerEnabled) {
                tr("已开启：可解析“动账信源”里勾选的应用的支付通知")
            } else {
                tr(
                    "未开启：只能从银行短信导入流水。Android 13+ 侧载安装后，若系统设置里无法勾选，" +
                        "请先到“应用信息 → 允许受限设置”放行"
                )
            },
            actionLabel = if (permissions.notificationListenerEnabled) tr("去查看") else tr("去开启"),
            onAction = onOpenListenerSettings
        )
        if (needsNotificationPermission) {
            HorizontalDivider()
            PermissionRow(
                title = tr("通知权限（Android 13+）"),
                granted = permissions.notificationPermissionGranted,
                description = if (permissions.notificationPermissionGranted) {
                    tr("已授权：报表通知可以正常显示")
                } else {
                    tr("未授权：生成报表时不会弹出系统通知")
                },
                actionLabel = tr("去授权"),
                onAction = onRequestNotification
            )
        }
        if (!permissions.notificationsEnabled) {
            Text(
                text = tr("系统通知总开关当前是关闭的，报表通知不会显示。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (granted) tr("已授权") else tr("未授权"),
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/**
 * 可折叠的分区卡片（短信来源 / 外观与语言 / 分类管理共用）。
 *
 * 默认收起：点标题行展开 / 收起，右侧箭头用 [Modifier.rotate] 旋转 180° 表示状态；
 * 展开状态由调用方的 `rememberSaveable` 保存，屏幕旋转 / 进程重建后仍保持。
 * 视觉与 [SectionCard] 一致，展开内容仍是各板块原有的设置项。
 */
@Composable
private fun CollapsibleSectionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                action?.invoke()
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) tr("收起") else tr("展开"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
            if (expanded) {
                content()
            }
        }
    }
}

/** 卡片内的二级折叠行（屏蔽词清单）：标题 + 摘要 + 旋转箭头，展开后显示 [content]。 */
@Composable
private fun CollapsibleRow(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) tr("收起") else tr("展开"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 复选框行：整行可点，标题在右、可选说明在下方。
 *
 * 用于“基金购买不计入支出”和“通知类型”筛选；[enabled] 为 false 时整行不可点，
 * 避免出现 FilterPrefs 无法表达的“一个类型都不允许”状态。
 */
@Composable
private fun CheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 短信来源列表（默认折叠）。
 *
 * 展开后保持原有来源管理功能，并额外提供“基金购买不计入支出”开关：
 * 开启后短信里的基金申购 / 赎回只调整“基金余额”账户，不再计入日常收支。
 */
@Composable
private fun SmsSourceSection(
    sources: List<SmsSource>,
    fundTrackingEnabled: Boolean,
    onFundTrackingChange: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onToggle: (SmsSource) -> Unit,
    onEdit: (SmsSource) -> Unit,
    onDelete: (SmsSource) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CollapsibleSectionCard(
        title = tr("短信来源"),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        action = { TextButton(onClick = onAdd) { Text(tr("新增")) } }
    ) {
        Text(
            text = tr("只解析这些号码发来的银行短信；归属账户选“自动建卡”时按银行名与尾号自动创建账户。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (sources.isEmpty()) {
            EmptyHint(tr("还没有短信来源"))
        } else {
            sources.forEach { source ->
                val tailText = if (source.cardTail.isBlank()) "" else tr(" · 尾号%1\$s", source.cardTail)
                val modeText = if (source.accountId == 0L) tr(" · 自动建卡") else tr(" · 指定账户")
                val disabledText = if (source.enabled) "" else tr(" · 已停用")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = tr("%1\$s（%2\$s）", source.label, source.addressPattern),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = source.bankName + tailText + modeText + disabledText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = source.enabled, onCheckedChange = { onToggle(source) })
                    IconButton(onClick = { onEdit(source) }) {
                        Icon(Icons.Filled.Edit, contentDescription = tr("编辑"))
                    }
                    IconButton(onClick = { onDelete(source) }) {
                        Icon(Icons.Filled.Delete, contentDescription = tr("删除"))
                    }
                }
            }
        }
        HorizontalDivider()
        CheckboxRow(
            title = tr("基金购买不计入支出"),
            checked = fundTrackingEnabled,
            onCheckedChange = onFundTrackingChange,
            description = tr(
                "基金申购转入“基金余额”、赎回从“基金余额”扣除，都不计入收入或支出；" +
                    "基金余额账户由短信导入自动创建。"
            )
        )
    }
}

/**
 * 动账信源（原“自动导入”）：短信自动扫描、需要解析支付通知的应用、屏蔽词清单、
 * 自动去重、回溯天数。
 *
 * 微信 / 支付宝的解析开关已合并进“需要解析支付通知的应用”二级菜单里的勾选项；
 * 屏蔽词清单默认折叠，展开后可多行编辑，失焦或点“保存”时写入 `settings.blockWords`。
 */
@Composable
private fun SourceSection(
    settings: LedgerSettings,
    onAutoScanChange: (Boolean) -> Unit,
    onManageNotifyApps: () -> Unit,
    onDedupeModeChange: (DedupeMode) -> Unit,
    onLookbackChange: (Int) -> Unit,
    onBlockWordsChange: (String) -> Unit
) {
    val notifyPackages = remember(settings.notifyPackages) {
        LayoutPrefs.splitCsv(settings.notifyPackages)
    }
    // 屏蔽词：展开状态用 rememberSaveable，输入草稿跟随已保存值重建。
    var blockWordsExpanded by rememberSaveable { mutableStateOf(false) }
    var blockWordsDraft by remember(settings.blockWords) { mutableStateOf(settings.blockWords) }
    val savedBlockWords = remember(settings.blockWords) {
        FilterPrefs.encodeBlockWords(FilterPrefs.parseBlockWords(settings.blockWords))
    }
    val blockWordCount = remember(savedBlockWords) {
        FilterPrefs.parseBlockWords(savedBlockWords).size
    }
    val normalizedBlockWords = FilterPrefs.encodeBlockWords(FilterPrefs.parseBlockWords(blockWordsDraft))
    val blockWordsDirty = normalizedBlockWords != savedBlockWords
    val saveBlockWords = {
        if (blockWordsDirty) onBlockWordsChange(normalizedBlockWords)
        if (blockWordsDraft != normalizedBlockWords) blockWordsDraft = normalizedBlockWords
    }
    SectionCard(title = tr("动账信源")) {
        Text(
            text = tr("动账信源决定流水从哪里来：银行短信，以及你在下面勾选的应用发出的支付通知。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchRow(
            title = tr("启动时自动扫描短信"),
            checked = settings.smsAutoScan,
            onCheckedChange = onAutoScanChange,
            description = tr("打开应用时自动读取最近短信，只解析“短信来源”里的号码")
        )
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onManageNotifyApps)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("需要解析支付通知的应用"), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (notifyPackages.isEmpty()) {
                        tr("尚未选择应用：不会解析任何支付通知")
                    } else {
                        tr("已选择 %1\$d 个应用：只解析这些应用发出的支付通知", notifyPackages.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onManageNotifyApps) { Text(tr("管理")) }
        }
        HorizontalDivider()
        CollapsibleRow(
            title = tr("屏蔽词清单"),
            summary = if (blockWordCount == 0) {
                tr("未设置：所有短信 / 通知都参与统计")
            } else {
                tr("已设置 %1\$d 个屏蔽词", blockWordCount)
            },
            expanded = blockWordsExpanded,
            onToggle = {
                // 收起时先保存，避免已输入的内容因内容区销毁而丢失。
                if (blockWordsExpanded) saveBlockWords()
                blockWordsExpanded = !blockWordsExpanded
            }
        ) {
            Text(
                text = tr("包含这些词的短信/通知不统计；多个词用换行或逗号分隔。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = blockWordsDraft,
                onValueChange = { blockWordsDraft = it },
                label = { Text(tr("屏蔽词")) },
                placeholder = { Text(tr("例如：验证码，还款提醒")) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        // 失焦保存：点别处 / 收起键盘时也会写回设置。
                        if (!focusState.isFocused) saveBlockWords()
                    }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("当前生效 %1\$d 个词", blockWordCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = saveBlockWords, enabled = blockWordsDirty) {
                    Text(tr("保存"))
                }
            }
        }
        HorizontalDivider()
        Text(tr("自动去重"), style = MaterialTheme.typography.titleSmall)
        Text(
            text = tr(
                "同一笔动账常常既有银行卡短信、又有支付宝 / 微信通知；开启后按精确金额、时间与支出去向核验。"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChoiceChips(
            labels = DedupeMode.values().map { tr(it.label) },
            selectedIndex = DedupeMode.values().indexOf(settings.dedupeMode),
            onSelect = { onDedupeModeChange(DedupeMode.values()[it]) }
        )
        Text(
            text = when (settings.dedupeMode) {
                DedupeMode.AUTO_BETA ->
                    tr("自动托管：保留银行卡短信，通知里的商户信息会合并进保留记录，其余重复记录自动删除。")
                DedupeMode.ASK ->
                    tr("每次询问：发现疑似重复时两笔都保留，并在首页逐条询问“合并 / 保留两笔”。")
                DedupeMode.OFF ->
                    tr("关闭：不做任何去重，短信与通知各记一笔。")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        NumberInputRow(
            title = tr("回溯天数"),
            description = tr("只扫描最近这些天内的短信（1 至 365）。"),
            value = settings.smsLookbackDays,
            range = 1..365,
            unit = tr("天"),
            onValueChange = onLookbackChange
        )
    }
}

/**
 * “需要解析支付通知的应用”二级菜单：顶部关键词搜索 + 已安装启动器应用勾选列表。
 *
 * 勾选结果以 CSV 写入 `settings.notifyPackages`（点“保存”后生效）；微信 / 支付宝默认勾选。
 * 每个已勾选应用右侧有展开箭头，展开后可按 5 种通知类型筛选（默认全选）。
 * 类型勾选通过 [onKindChange] 走 [SettingsViewModel.setNotifyKind] **即时生效**，
 * 由 ViewModel 用 [FilterPrefs] 编解码写入 `settings.notifyKindFilters`：
 * 全部类型时不写该包配置；取消勾选应用只改 `notifyPackages`，不会丢失已有的类型配置。
 */
@Composable
private fun NotifyAppsDialog(
    apps: List<NotifyAppOption>,
    selected: Set<String>,
    kindFilters: Map<String, Set<NotificationKind>>,
    onKindChange: (String, NotificationKind, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(selected) }
    var expandedApps by remember { mutableStateOf(emptySet<String>()) }
    val allKinds = remember { NotificationKind.values().toSet() }
    val keyword = query.trim()
    val filtered = remember(keyword, apps) {
        if (keyword.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(keyword, ignoreCase = true) ||
                    app.packageName.contains(keyword, ignoreCase = true)
            }
        }
    }

    /** 勾选 / 取消勾选应用：只动 chosen，已有的类型配置保持不变（再次勾选时自动恢复）。 */
    fun toggleApp(packageName: String) {
        chosen = if (packageName in chosen) chosen - packageName else chosen + packageName
    }

    fun toggleExpanded(packageName: String) {
        expandedApps = if (packageName in expandedApps) expandedApps - packageName else expandedApps + packageName
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("需要解析支付通知的应用")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("搜索应用名")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = tr(
                        "已勾选 %1\$d 个应用；微信 / 支付宝默认勾选。展开右侧箭头可按通知类型筛选，" +
                            "类型修改即时生效，应用勾选点“保存”后生效。",
                        chosen.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (filtered.isEmpty()) {
                        item { EmptyHint(tr("没有可列出的应用：可能仍在读取，或系统不允许读取已安装应用")) }
                    } else {
                        items(filtered, key = { app -> app.packageName }) { app ->
                            val checked = app.packageName in chosen
                            val expanded = app.packageName in expandedApps
                            val allowed = kindFilters[app.packageName] ?: allKinds
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggleApp(app.packageName) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { enabled ->
                                            chosen = if (enabled) {
                                                chosen + app.packageName
                                            } else {
                                                chosen - app.packageName
                                            }
                                        }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (checked) {
                                            Text(
                                                text = if (allowed == allKinds) {
                                                    tr("通知类型：全部")
                                                } else {
                                                    tr("通知类型：%1\$s", notificationKindSummary(allowed))
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (allowed == allKinds) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                }
                                            )
                                        }
                                    }
                                    if (checked) {
                                        IconButton(onClick = { toggleExpanded(app.packageName) }) {
                                            Icon(
                                                imageVector = Icons.Filled.ExpandMore,
                                                contentDescription = if (expanded) {
                                                    tr("收起通知类型")
                                                } else {
                                                    tr("展开通知类型")
                                                },
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.rotate(if (expanded) 180f else 0f)
                                            )
                                        }
                                    }
                                }
                                if (checked && expanded) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 44.dp, bottom = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = tr("只解析勾选类型的通知，至少保留一种；修改后立即生效。"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        NotificationKind.values().forEach { kind ->
                                            val kindChecked = kind in allowed
                                            CheckboxRow(
                                                title = tr(kind.label),
                                                checked = kindChecked,
                                                enabled = allowed.size > 1 || !kindChecked,
                                                onCheckedChange = { enabled ->
                                                    onKindChange(app.packageName, kind, enabled)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen.toList()) }) { Text(tr("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        }
    )
}

/** 应用行下方的通知类型摘要：按枚举声明顺序拼接，如“支出 / 收入”。 */
private fun notificationKindSummary(kinds: Set<NotificationKind>): String =
    NotificationKind.values().filter { kind -> kind in kinds }.joinToString(" / ") { kind -> tr(kind.label) }

/** 报表日期折叠行的展开键：同一时间只允许展开一个二级区域。 */
private const val KEY_WEEK = "WEEK"
private const val KEY_MONTH = "MONTH"
private const val KEY_YEAR = "YEAR"

/**
 * 报表通知：开关、生成时间、折叠式日期滚轮与报表模式。
 *
 * 周报 / 月报 / 年报日期默认折叠：点标题行展开二级区域，用 NumberPicker 滚轮
 * （与“自动生成时间”的平台 TimePickerDialog 同一套滚轮观感）选择星期 / 日 / 月+日；
 * 点“保存”后写回设置，ViewModel 的 setter 会调用 [com.ledger.offline.report.ReportScheduler] 重新排期。
 */
@Composable
private fun ReportSettingsSection(
    settings: LedgerSettings,
    onReportNotificationsChange: (Boolean) -> Unit,
    onDailyChange: (Boolean) -> Unit,
    onWeeklyChange: (Boolean) -> Unit,
    onMonthlyChange: (Boolean) -> Unit,
    onYearlyChange: (Boolean) -> Unit,
    onPickTime: () -> Unit,
    onWeeklyDayChange: (Int) -> Unit,
    onMonthlyDayChange: (Int) -> Unit,
    onYearlyDateChange: (Int, Int) -> Unit,
    onReportModeChange: (ReportMode) -> Unit
) {
    val weekDayLabels = remember {
        listOf(tr("周一"), tr("周二"), tr("周三"), tr("周四"), tr("周五"), tr("周六"), tr("周日"))
    }
    val monthDayLabels = remember { (1..28).map { tr("%1\$d 日", it) } }
    val monthLabels = remember { (1..12).map { tr("%1\$d 月", it) } }
    var expandedKey by remember { mutableStateOf("") }
    var weekDraft by remember(settings.weeklyDayOfWeek) { mutableStateOf(settings.weeklyDayOfWeek) }
    var monthDraft by remember(settings.monthlyDay) { mutableStateOf(settings.monthlyDay) }
    var yearMonthDraft by remember(settings.yearlyMonth) { mutableStateOf(settings.yearlyMonth) }
    var yearDayDraft by remember(settings.yearlyDay) { mutableStateOf(settings.yearlyDay) }

    SectionCard(title = tr("报表通知")) {
        SwitchRow(
            title = tr("开启报表通知"),
            checked = settings.reportNotifications,
            onCheckedChange = onReportNotificationsChange
        )
        SwitchRow(
            title = tr("每日日报"),
            checked = settings.dailyReportEnabled,
            onCheckedChange = onDailyChange
        )
        SwitchRow(
            title = tr("每周周报"),
            checked = settings.weeklyReportEnabled,
            onCheckedChange = onWeeklyChange
        )
        SwitchRow(
            title = tr("每月月报"),
            checked = settings.monthlyReportEnabled,
            onCheckedChange = onMonthlyChange
        )
        SwitchRow(
            title = tr("每年年报"),
            checked = settings.yearlyReportEnabled,
            onCheckedChange = onYearlyChange
        )
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("自动生成时间"), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = tr(
                        "每天 %1\$s 生成报表；修改后会自动重新排期",
                        formatHourMinute(settings.reportHour, settings.reportMinute)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onPickTime) {
                Text(formatHourMinute(settings.reportHour, settings.reportMinute))
            }
        }
        HorizontalDivider()
        Text(
            text = tr("报表日期默认折叠，点击对应的行展开滚轮选择。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ReportDateRow(
            title = tr("周报日期"),
            summary = weekDayLabels.getOrElse((settings.weeklyDayOfWeek - 1).coerceIn(0, 6)) { "" },
            expanded = expandedKey == KEY_WEEK,
            onToggle = { expandedKey = if (expandedKey == KEY_WEEK) "" else KEY_WEEK }
        ) {
            Text(
                text = tr("滚轮选择周报在星期几生成（生成上一自然周）。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WheelPicker(
                values = weekDayLabels,
                selectedIndex = (weekDraft - 1).coerceIn(0, 6),
                onSelected = { weekDraft = it + 1 }
            )
            WheelActions(
                canSave = weekDraft != settings.weeklyDayOfWeek,
                onCancel = { expandedKey = "" },
                onSave = {
                    onWeeklyDayChange(weekDraft)
                    expandedKey = ""
                }
            )
        }
        HorizontalDivider()
        ReportDateRow(
            title = tr("月报日期"),
            summary = tr("%1\$d 日", settings.monthlyDay),
            expanded = expandedKey == KEY_MONTH,
            onToggle = { expandedKey = if (expandedKey == KEY_MONTH) "" else KEY_MONTH }
        ) {
            Text(
                text = tr("滚轮选择每月哪一天生成上月月报（只提供 1 至 28 日，保证每月都能触发）。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WheelPicker(
                values = monthDayLabels,
                selectedIndex = (monthDraft - 1).coerceIn(0, 27),
                onSelected = { monthDraft = it + 1 }
            )
            WheelActions(
                canSave = monthDraft != settings.monthlyDay,
                onCancel = { expandedKey = "" },
                onSave = {
                    onMonthlyDayChange(monthDraft)
                    expandedKey = ""
                }
            )
        }
        HorizontalDivider()
        ReportDateRow(
            title = tr("年报日期"),
            summary = tr("%1\$d 月 %2\$d 日", settings.yearlyMonth, settings.yearlyDay),
            expanded = expandedKey == KEY_YEAR,
            onToggle = { expandedKey = if (expandedKey == KEY_YEAR) "" else KEY_YEAR }
        ) {
            Text(
                text = tr("滚轮选择年报的月份与日期（1 至 28 日）。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelPicker(
                    values = monthLabels,
                    selectedIndex = (yearMonthDraft - 1).coerceIn(0, 11),
                    onSelected = { yearMonthDraft = it + 1 },
                    modifier = Modifier.weight(1f)
                )
                WheelPicker(
                    values = monthDayLabels,
                    selectedIndex = (yearDayDraft - 1).coerceIn(0, 27),
                    onSelected = { yearDayDraft = it + 1 },
                    modifier = Modifier.weight(1f)
                )
            }
            WheelActions(
                canSave = yearMonthDraft != settings.yearlyMonth || yearDayDraft != settings.yearlyDay,
                onCancel = { expandedKey = "" },
                onSave = {
                    onYearlyDateChange(yearMonthDraft, yearDayDraft)
                    expandedKey = ""
                }
            )
        }
        HorizontalDivider()
        Text(tr("报表模式"), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(
            labels = ReportMode.values().map { tr(it.label) },
            selectedIndex = ReportMode.values().indexOf(settings.reportMode),
            onSelect = { onReportModeChange(ReportMode.values()[it]) }
        )
        Text(
            text = if (settings.reportMode == ReportMode.MONTHLY_SETTLEMENT) {
                tr(
                    "月结模式：周报改为 8 / 15 / 22 日生成上一 7 天区块，次月 1 日合并月底尾巴；" +
                        "月报与年报仍按上面设定的日期生成。"
                )
            } else {
                tr(
                    "收支默认模式：日报每天生成，周报在选定星期生成上一自然周，" +
                        "月报与年报按上面设定的日期生成。"
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 报表日期折叠行：标题 + 当前取值 + 展开箭头，点击整行切换展开。 */
@Composable
private fun ReportDateRow(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) tr("收起") else tr("展开"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content()
            }
        }
    }
}

/** 滚轮的“取消 / 保存”操作行；取值没变时保存按钮置灰。 */
@Composable
private fun WheelActions(
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onCancel) { Text(tr("取消")) }
        TextButton(onClick = onSave, enabled = canSave) { Text(tr("保存")) }
    }
}

/**
 * 外观与语言（默认折叠）：语言、色调（6 色）、日间 / 夜间模式、图标配色、密度，修改后立即生效。
 *
 * 图标配色有两种模式：跟随主题时用 [LedgerSettings.themeTone] 决定桌面图标；
 * 手动设置时单独用 [LedgerSettings.iconTone]，与界面色调解耦。
 */
@Composable
private fun AppearanceSection(
    settings: LedgerSettings,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeToneChange: (ThemeTone) -> Unit,
    onDensityChange: (UiDensity) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
    onIconColorModeChange: (IconColorMode) -> Unit,
    onIconToneChange: (ThemeTone) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CollapsibleSectionCard(
        title = tr("外观与语言"),
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Text(tr("界面语言"), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(
            labels = AppLanguage.values().map { tr(it.label) },
            selectedIndex = AppLanguage.values().indexOf(settings.language),
            onSelect = { onLanguageChange(AppLanguage.values()[it]) }
        )
        HorizontalDivider()
        Text(tr("色调"), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(
            labels = ThemeTone.values().map { tr(it.label) },
            selectedIndex = ThemeTone.values().indexOf(settings.themeTone),
            onSelect = { onThemeToneChange(ThemeTone.values()[it]) }
        )
        Text(
            text = tr("共 6 种色调，新增樱花粉与黑白；调整后界面配色立即生效。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Text(tr("密度"), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(
            labels = UiDensity.values().map { tr(it.label) },
            selectedIndex = UiDensity.values().indexOf(settings.density),
            onSelect = { onDensityChange(UiDensity.values()[it]) }
        )
        Text(
            text = tr("密度只调整字体大小，不改变页面结构。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Text(tr("日间 / 夜间模式"), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(
            labels = DarkMode.values().map { tr(it.label) },
            selectedIndex = DarkMode.values().indexOf(settings.darkMode),
            onSelect = { onDarkModeChange(DarkMode.values()[it]) }
        )
        Text(
            text = tr("跟随系统时随系统深色设置切换；日间 / 夜间会强制覆盖系统设置。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Text(tr("图标配色"), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(
            labels = IconColorMode.values().map { tr(it.label) },
            selectedIndex = IconColorMode.values().indexOf(settings.iconColorMode),
            onSelect = { onIconColorModeChange(IconColorMode.values()[it]) }
        )
        if (settings.iconColorMode == IconColorMode.MANUAL) {
            ChoiceChips(
                labels = ThemeTone.values().map { tr(it.label) },
                selectedIndex = ThemeTone.values().indexOf(settings.iconTone),
                onSelect = { onIconToneChange(ThemeTone.values()[it]) }
            )
            Text(
                text = tr("手动配色只在桌面图标上生效，不影响界面色调。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = tr("跟随主题：桌面图标配色与上面的“色调”保持一致。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = tr("图标通过启动器别名切换，桌面可能需几秒才会刷新。以上修改都会立即生效并保存在本机。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 页面布局：首页卡片与底部导航的排序 / 显隐，以及恢复默认。 */
@Composable
private fun LayoutSection(
    settings: LedgerSettings,
    onMoveHomeSection: (HomeSection, Boolean) -> Unit,
    onToggleHomeSection: (HomeSection, Boolean) -> Unit,
    onMoveBottomNav: (BottomTab, Boolean) -> Unit,
    onToggleBottomNav: (BottomTab, Boolean) -> Unit,
    onResetLayout: () -> Unit
) {
    val homeOrder = remember(settings.homeSectionOrder) {
        orderedEnums(settings.homeSectionOrder, HomeSection.values().toList())
    }
    val hiddenHome = remember(settings.hiddenHomeSections) {
        LayoutPrefs.splitCsv(settings.hiddenHomeSections).toSet()
    }
    val navOrder = remember(settings.bottomNavOrder) {
        orderedEnums(settings.bottomNavOrder, BottomTab.values().toList())
    }
    val hiddenNav = remember(settings.hiddenBottomNav) {
        LayoutPrefs.splitCsv(settings.hiddenBottomNav).toSet()
    }
    SectionCard(title = tr("页面布局")) {
        Text(tr("首页卡片"), style = MaterialTheme.typography.titleSmall)
        Text(
            text = tr("按下面的顺序从上到下显示，可用箭头调整；关闭开关后隐藏该区块。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        homeOrder.forEachIndexed { index, section ->
            LayoutRow(
                label = tr(section.label),
                canMoveUp = index > 0,
                canMoveDown = index < homeOrder.lastIndex,
                visible = section.name !in hiddenHome,
                alwaysVisible = false,
                onMove = { up -> onMoveHomeSection(section, up) },
                onVisibleChange = { visible -> onToggleHomeSection(section, visible) }
            )
        }
        HorizontalDivider()
        Text(tr("底部导航"), style = MaterialTheme.typography.titleSmall)
        Text(
            text = tr("首页与设置必须常驻、不能隐藏；明细与报表可以上移、下移或隐藏。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        navOrder.forEachIndexed { index, tab ->
            LayoutRow(
                label = tr(tab.label),
                canMoveUp = index > 0,
                canMoveDown = index < navOrder.lastIndex,
                visible = tab.name !in hiddenNav,
                alwaysVisible = tab == BottomTab.HOME || tab == BottomTab.SETTINGS,
                onMove = { up -> onMoveBottomNav(tab, up) },
                onVisibleChange = { visible -> onToggleBottomNav(tab, visible) }
            )
        }
        TextButton(onClick = onResetLayout) { Text(tr("恢复默认布局")) }
    }
}

/** 布局编辑的一行：名称 + 显隐开关（或“常驻”标记）+ 上移 / 下移。 */
@Composable
private fun LayoutRow(
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    visible: Boolean,
    alwaysVisible: Boolean,
    onMove: (Boolean) -> Unit,
    onVisibleChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (alwaysVisible) {
            Text(
                text = tr("常驻"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Switch(checked = visible, onCheckedChange = onVisibleChange)
        }
        IconButton(onClick = { onMove(true) }, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = tr("上移"),
                tint = if (canMoveUp) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        IconButton(onClick = { onMove(false) }, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = tr("下移"),
                tint = if (canMoveDown) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 其他设置。 */
@Composable
private fun OtherSettingsSection(
    settings: LedgerSettings,
    onAutoCategorizeChange: (Boolean) -> Unit
) {
    SectionCard(title = tr("其他")) {
        SwitchRow(
            title = tr("自动归类"),
            description = tr("按分类关键词自动匹配短信 / 通知内容"),
            checked = settings.autoCategorize,
            onCheckedChange = onAutoCategorizeChange
        )
    }
}

/** 分类管理（默认折叠）：展开后保持新增 / 编辑 / 删除的原有功能。 */
@Composable
private fun CategorySection(
    categories: List<Category>,
    onAdd: () -> Unit,
    onEdit: (Category) -> Unit,
    onDelete: (Category) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CollapsibleSectionCard(
        title = tr("分类管理"),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        action = { TextButton(onClick = onAdd) { Text(tr("新增")) } }
    ) {
        if (categories.isEmpty()) {
            EmptyHint(tr("还没有分类"))
        } else {
            categories
                .sortedWith(compareBy({ it.type.ordinal }, { it.sortOrder }))
                .forEach { category ->
                    val typeLabel = tr(category.type.label)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category.icon, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(category.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (category.isDefault) {
                                    tr("%1\$s · 预置", typeLabel)
                                } else {
                                    typeLabel
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onEdit(category) }) {
                            Icon(Icons.Filled.Edit, contentDescription = tr("编辑"))
                        }
                        IconButton(onClick = { onDelete(category) }) {
                            Icon(Icons.Filled.Delete, contentDescription = tr("删除"))
                        }
                    }
                }
        }
    }
}

/**
 * 账户管理：上移 / 下移 / 编辑 / 删除，以及 v1.2.3 的“余额修正”入口。
 *
 * 余额修正读的是账户流水里最后一条带余额的记录（由 domain 层统一实现），
 * 这里只负责触发与展示结果，不参与新增 / 编辑 / 删除 / 排序。
 */
@Composable
private fun AccountSection(
    accounts: List<Account>,
    onAdd: () -> Unit,
    onEdit: (Account) -> Unit,
    onMove: (Account, Boolean) -> Unit,
    onDelete: (Account) -> Unit,
    correcting: Boolean,
    lastCorrection: BalanceCorrectionResult?,
    onCorrectBalances: () -> Unit
) {
    val ordered = remember(accounts) { accounts.sortedBy { it.sortOrder } }
    SectionCard(
        title = tr("账户管理"),
        action = { TextButton(onClick = onAdd) { Text(tr("新增")) } }
    ) {
        if (ordered.isEmpty()) {
            EmptyHint(tr("还没有账户"))
        } else {
            ordered.forEachIndexed { index, account ->
                val balanceText = account.balanceCents?.let { Money.format(it) } ?: tr("余额未同步")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(account.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = tr("%1\$s · %2\$s", tr(account.type.label), balanceText),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onEdit(account) }) {
                            Icon(Icons.Filled.Edit, contentDescription = tr("编辑"))
                        }
                        IconButton(onClick = { onDelete(account) }) {
                            Icon(Icons.Filled.Delete, contentDescription = tr("删除"))
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = { onMove(account, true) },
                            enabled = index > 0
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(tr("上移"))
                        }
                        TextButton(
                            onClick = { onMove(account, false) },
                            enabled = index < ordered.lastIndex
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(tr("下移"))
                        }
                    }
                }
                if (index != ordered.lastIndex) HorizontalDivider()
            }
        }
        // 余额修正：独立于上面的账户增删改排序，只在点击时调用 ViewModel 修正全部账户。
        HorizontalDivider()
        Text(tr("余额修正"), style = MaterialTheme.typography.titleSmall)
        Text(
            text = tr("读取每个账户最后一条带余额的流水，把账户余额修正为短信中记录的余额；没有余额信息的账户会跳过。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onCorrectBalances, enabled = !correcting) {
            Text(if (correcting) tr("正在修正…") else tr("余额修正"))
        }
        val lastResult = lastCorrection
        if (lastResult != null) {
            Text(
                text = tr(
                    "上次成功修正：已修正 %1\$d 个账户余额，跳过 %2\$d 个",
                    lastResult.updated,
                    lastResult.skipped
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 当前版本 / 关于自动记账：点版本行看更新日志，下面是离线、权限、去重等简述。 */
@Composable
private fun AboutSection(onShowChangelog: () -> Unit) {
    SectionCard(title = tr("当前版本 / 关于自动记账")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onShowChangelog)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("当前版本"), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = tr("版本 %1\$s", AppVersion.NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = tr("更新日志"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = tr("查看更新日志"),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        HorizontalDivider()
        Text(tr("关于自动记账"), style = MaterialTheme.typography.titleSmall)
        Text(
            text = tr(
                "离线：本应用不申请网络权限，没有任何联网能力；流水、账户与报表只保存在本机数据库，" +
                    "卸载应用会连同数据一起删除。"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = tr(
                "权限：短信读取只解析你指定号码发来的银行短信；通知使用权只解析你在“动账信源”里勾选的应用；" +
                    "信任模式的无障碍读取只在开启时用于账单页，内容不会上传。"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = tr(
                "去重：同一笔动账可能同时出现在银行短信与支付通知里，可在“动账信源 → 自动去重”选择" +
                    "自动托管、每次询问或关闭。"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = tr("开源组件：AndroidX、Jetpack Compose、Room、WorkManager、DataStore（均为 Apache-2.0 许可）。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 滚轮选择器：把平台 [NumberPicker] 包进 Compose。
 *
 * 与“自动生成时间”的平台 TimePickerDialog 用同一套滚轮观感；
 * 平台控件默认取 Activity 主题色，所以这里按当前 Compose 主题的 onSurface 重新染色，
 * 保证夜间模式下文字仍然清晰（Q 以上直接 setTextColor，更早版本遍历子 View）。
 */
@Composable
private fun WheelPicker(
    values: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnSelected by rememberUpdatedState(onSelected)
    val lastIndex = (values.size - 1).coerceAtLeast(0)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp),
        factory = { viewContext ->
            NumberPicker(viewContext).apply {
                minValue = 0
                maxValue = lastIndex
                displayedValues = values.toTypedArray()
                wrapSelectorWheel = values.size > 2
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setOnValueChangedListener { _, _, newValue -> currentOnSelected(newValue) }
                styleWheel(this, textColor)
            }
        },
        update = { picker ->
            val target = selectedIndex.coerceIn(0, lastIndex)
            if (picker.maxValue != lastIndex) picker.maxValue = lastIndex
            if (picker.value != target) picker.value = target
            styleWheel(picker, textColor)
        }
    )
}

/** 给 NumberPicker 重新染色：Q 及以上用官方接口，更早版本退化为遍历子 View。 */
private fun styleWheel(picker: NumberPicker, textColor: Int) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.setTextColor(textColor)
        } else {
            paintWheelChildren(picker, textColor)
        }
    }
}

/** 递归把文字颜色写到滚轮内部的 EditText / TextView 上。 */
private fun paintWheelChildren(group: ViewGroup, color: Int) {
    for (index in 0 until group.childCount) {
        when (val child = group.getChildAt(index)) {
            is TextView -> child.setTextColor(color)
            is ViewGroup -> paintWheelChildren(child, color)
        }
    }
}

/** 通用开关行。 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 一排可换行的单选 FilterChip；[labels] 由调用方翻译好后传入。 */
@Composable
private fun ChoiceChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label) }
            )
        }
    }
}

/** 短信来源新增 / 编辑弹窗。 */
@Composable
private fun SmsSourceEditorDialog(
    initial: SmsSource?,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (SmsSource) -> Unit
) {
    var label by remember(initial) { mutableStateOf(initial?.label ?: "") }
    var addressPattern by remember(initial) { mutableStateOf(initial?.addressPattern ?: "") }
    var bankName by remember(initial) { mutableStateOf(initial?.bankName ?: "") }
    var cardTail by remember(initial) { mutableStateOf(initial?.cardTail ?: "") }
    var accountId by remember(initial) { mutableStateOf(initial?.accountId ?: 0L) }
    var showAccountPicker by remember { mutableStateOf(false) }

    val accountName = accounts.firstOrNull { it.id == accountId }?.displayName ?: tr("未知账户")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("新增短信来源") else tr("编辑短信来源")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(tr("显示名，如 招商银行")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = addressPattern,
                    onValueChange = { addressPattern = it },
                    label = { Text(tr("发送号码关键字，如 95555")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bankName,
                    onValueChange = { bankName = it },
                    label = { Text(tr("银行名，如 招商银行")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cardTail,
                    onValueChange = { cardTail = it },
                    label = { Text(tr("尾号（可选，4 位）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (accountId == 0L) {
                            tr("归属账户：自动建卡")
                        } else {
                            tr("归属账户：%1\$s", accountName)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showAccountPicker = true }) { Text(tr("选择")) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && addressPattern.isNotBlank(),
                onClick = {
                    val base = initial ?: SmsSource(
                        label = "",
                        addressPattern = "",
                        bankName = "",
                        accountId = 0L
                    )
                    onConfirm(
                        base.copy(
                            label = label.trim(),
                            addressPattern = addressPattern.trim(),
                            bankName = bankName.trim(),
                            cardTail = cardTail.trim(),
                            accountId = accountId
                        )
                    )
                }
            ) { Text(tr("保存")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } }
    )

    if (showAccountPicker) {
        val options = listOf(tr("自动建卡（按银行名 / 尾号）")) + accounts.map { it.displayName }
        val selectedIndex = if (accountId == 0L) 0 else accounts.indexOfFirst { it.id == accountId } + 1
        OptionPickerDialog(
            title = tr("选择归属账户"),
            options = options,
            selectedIndex = selectedIndex,
            onPick = { index ->
                accountId = if (index == 0) 0L else accounts.getOrNull(index - 1)?.id ?: 0L
                showAccountPicker = false
            },
            onDismiss = { showAccountPicker = false }
        )
    }
}

/** 分类新增 / 编辑弹窗。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorDialog(
    initial: Category?,
    onDismiss: () -> Unit,
    onConfirm: (Category) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var type by remember(initial) { mutableStateOf(initial?.type ?: TxType.EXPENSE) }
    var icon by remember(initial) { mutableStateOf(initial?.icon ?: "📦") }
    var keywords by remember(initial) { mutableStateOf(initial?.keywords ?: "") }
    val fallbackName = tr("新分类")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("新增分类") else tr("编辑分类")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TxType.EXPENSE,
                        onClick = { type = TxType.EXPENSE },
                        label = { Text(tr("支出")) }
                    )
                    FilterChip(
                        selected = type == TxType.INCOME,
                        onClick = { type = TxType.INCOME },
                        label = { Text(tr("收入")) }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("分类名，如 餐饮")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = icon,
                    onValueChange = { icon = it },
                    label = { Text(tr("图标（一个 emoji）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text(tr("自动归类关键词（逗号分隔，可留空）")) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val base = initial ?: Category(name = fallbackName, type = type)
                    onConfirm(
                        base.copy(
                            name = name.trim(),
                            type = type,
                            icon = icon.trim().ifBlank { "📦" },
                            keywords = keywords.trim()
                        )
                    )
                }
            ) { Text(tr("保存")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } }
    )
}

/** 账户新增 / 编辑弹窗（银行卡可手填余额）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccountEditorDialog(
    initial: Account?,
    onDismiss: () -> Unit,
    onConfirm: (Account) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var type by remember(initial) { mutableStateOf(initial?.type ?: AccountType.BANK) }
    var cardTail by remember(initial) { mutableStateOf(initial?.cardTail ?: "") }
    var balanceText by remember(initial) {
        mutableStateOf(initial?.balanceCents?.let { Money.editable(it) } ?: "")
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    val fallbackName = tr("新账户")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("新增账户") else tr("编辑账户")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("账户名，如 招商银行")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AccountType.values().forEach { accountType ->
                        FilterChip(
                            selected = type == accountType,
                            onClick = { type = accountType },
                            label = { Text(tr(accountType.label)) }
                        )
                    }
                }
                if (type == AccountType.BANK) {
                    OutlinedTextField(
                        value = cardTail,
                        onValueChange = { cardTail = it },
                        label = { Text(tr("卡号尾号（可选，4 位）")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text(tr("余额（元，留空表示未同步）")) },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = { if (errorText != null) Text(errorText ?: "") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val balance = if (balanceText.isBlank()) null else Money.parseToCents(balanceText)
                    if (balanceText.isNotBlank() && balance == null) {
                        errorText = tr("余额格式不正确，例如 1234.56")
                    } else {
                        errorText = null
                        val base = initial ?: Account(name = fallbackName, type = type)
                        onConfirm(
                            base.copy(
                                name = name.trim(),
                                type = type,
                                cardTail = if (type == AccountType.BANK) cardTail.trim() else "",
                                balanceCents = balance,
                                balanceUpdatedAt = if (balance != null) {
                                    System.currentTimeMillis()
                                } else {
                                    base.balanceUpdatedAt
                                }
                            )
                        )
                    }
                }
            ) { Text(tr("保存")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } }
    )
}

/** 打开系统“通知使用权”设置页。 */
private fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 把小时 / 分钟格式化为 HH:mm。 */
private fun formatHourMinute(hour: Int, minute: Int): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
