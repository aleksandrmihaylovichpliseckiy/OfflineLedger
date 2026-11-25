@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ledger.offline.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ledger.offline.AppVersion
import com.ledger.offline.di.AppContainer
import com.ledger.offline.domain.model.BottomTab
import com.ledger.offline.domain.model.DarkMode
import com.ledger.offline.domain.model.LedgerSettings
import com.ledger.offline.domain.model.ReportPeriod
import com.ledger.offline.i18n.I18n
import com.ledger.offline.i18n.LocalAppLanguage
import com.ledger.offline.i18n.tr
import com.ledger.offline.ui.screens.AnalysisScreen
import com.ledger.offline.ui.screens.EditTransactionScreen
import com.ledger.offline.ui.screens.HomeScreen
import com.ledger.offline.ui.screens.ReportsScreen
import com.ledger.offline.ui.screens.SettingsScreen
import com.ledger.offline.ui.screens.TransactionsScreen
import com.ledger.offline.ui.theme.LedgerTheme
import com.ledger.offline.util.LayoutPrefs

/**
 * 应用唯一入口（由 MainActivity 调用）。
 *
 * 负责：主题色调、语言、界面密度、隐私模式、底部导航顺序、
 * 首次打开新版本的更新日志弹窗，以及各页面的导航。
 *
 * @param initialReportPeriod 点系统报表通知进入时的周期（DAILY/WEEKLY/MONTHLY/YEARLY）
 * @param reportRequestNonce 通知点击序号：周期相同但再次点击通知时也能重新跳转
 */
@Composable
fun LedgerApp(
    container: AppContainer,
    initialReportPeriod: String? = null,
    reportRequestNonce: Int = 0
) {
    val appViewModel: AppViewModel = viewModel(
        factory = remember(container) { AppViewModel.factory(container.repos) }
    )
    val settingsOrNull by appViewModel.settings.collectAsStateWithLifecycle()
    val settings = settingsOrNull ?: LedgerSettings()
    val language = settings.language
    val context = LocalContext.current
    val darkTheme = when (settings.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }

    // tr() 是非 Composable 的全局翻译函数：先同步语言，再用 key(language) 重建界面，
    // 这样切换语言后所有页面文案都会立刻刷新（导航会回到首页）。
    I18n.set(language)

    // 图标配色：跟随主题或手动指定；通过 activity-alias 切换桌面图标。
    LaunchedEffect(settings.iconColorMode, settings.iconTone, settings.themeTone) {
        AppIconController.apply(context.applicationContext, settings)
    }

    CompositionLocalProvider(LocalAppLanguage provides language) {
        key(language) {
            val baseDensity = LocalDensity.current
            val scaledDensity = Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * settings.density.fontScale
            )
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
            LedgerTheme(tone = settings.themeTone, darkTheme = darkTheme) {
                PrivacyGuard(enabled = settings.privacyMode)

                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route

                val tabs = remember(settings.bottomNavOrder, settings.hiddenBottomNav, settings.simpleMode) {
                    LayoutPrefs.parseBottomTabs(settings.bottomNavOrder, settings.hiddenBottomNav)
                        .filter { tab ->
                            // 简易模式不展示明细页
                            !(settings.simpleMode && tab == BottomTab.TRANSACTIONS)
                        }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (!Routes.isEdit(currentRoute)) {
                            LedgerBottomBar(
                                tabs = tabs,
                                currentRoute = currentRoute,
                                onNavigate = { route ->
                                    if (route == Routes.HOME) {
                                        HomeScrollBus.requestTop()
                                    }
                                    navController.navigate(route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                container = container,
                                snackbarHostState = snackbarHostState,
                                onAddTransaction = { navController.navigate(Routes.edit(-1L)) },
                                onOpenReports = { period ->
                                    navController.navigate(Routes.report(period, auto = true))
                                },
                                onOpenTransactions = {
                                    navController.navigate(Routes.TRANSACTIONS) { launchSingleTop = true }
                                },
                                onOpenAnalysis = {
                                    navController.navigate(Routes.ANALYSIS) { launchSingleTop = true }
                                }
                            )
                        }
                        composable(Routes.TRANSACTIONS) {
                            TransactionsScreen(
                                container = container,
                                snackbarHostState = snackbarHostState,
                                onAddTransaction = { navController.navigate(Routes.edit(-1L)) },
                                onEditTransaction = { id -> navController.navigate(Routes.edit(id)) }
                            )
                        }
                        composable(
                            route = Routes.REPORTS,
                            arguments = listOf(
                                navArgument("period") {
                                    type = NavType.StringType
                                    defaultValue = ReportPeriod.DAILY.name
                                },
                                navArgument("auto") {
                                    type = NavType.BoolType
                                    defaultValue = false
                                }
                            )
                        ) { entry ->
                            ReportsScreen(
                                container = container,
                                snackbarHostState = snackbarHostState,
                                periodArg = entry.arguments?.getString("period"),
                                autoGenerate = entry.arguments?.getBoolean("auto") ?: false
                            )
                        }
                        composable(Routes.ANALYSIS) {
                            AnalysisScreen(
                                container = container,
                                snackbarHostState = snackbarHostState,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                container = container,
                                snackbarHostState = snackbarHostState
                            )
                        }
                        composable(
                            route = Routes.EDIT,
                            arguments = listOf(
                                navArgument("txId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                }
                            )
                        ) { entry ->
                            EditTransactionScreen(
                                container = container,
                                txId = entry.arguments?.getLong("txId") ?: -1L,
                                snackbarHostState = snackbarHostState,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                // 从报表通知进入时直接跳到报表页
                LaunchedEffect(initialReportPeriod, reportRequestNonce) {
                    val raw = initialReportPeriod ?: return@LaunchedEffect
                    val period = runCatching {
                        ReportPeriod.valueOf(raw.trim().uppercase())
                    }.getOrNull() ?: return@LaunchedEffect
                    navController.navigate(Routes.report(period)) { launchSingleTop = true }
                }

                // 首次打开新版本：展示更新日志
                if (settingsOrNull != null && settings.lastSeenVersion < AppVersion.CODE) {
                    ChangelogDialog(onDismiss = {
                        appViewModel.markChangelogSeen(AppVersion.CODE)
                    })
                }
            }
            }
        }
    }
}

/** 底部导航项与路由的对应关系。 */
private fun BottomTab.route(): String = when (this) {
    BottomTab.HOME -> Routes.HOME
    BottomTab.TRANSACTIONS -> Routes.TRANSACTIONS
    BottomTab.REPORTS -> Routes.REPORT_TAB
    BottomTab.SETTINGS -> Routes.SETTINGS
}

private fun BottomTab.icon(): ImageVector = when (this) {
    BottomTab.HOME -> Icons.Filled.Home
    BottomTab.TRANSACTIONS -> Icons.Filled.ReceiptLong
    BottomTab.REPORTS -> Icons.Filled.BarChart
    BottomTab.SETTINGS -> Icons.Filled.Settings
}

/** 首页 / 明细 / 报表 / 设置，顺序与显隐由设置决定。 */
@Composable
private fun LedgerBottomBar(
    tabs: List<BottomTab>,
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar {
        tabs.forEach { tab ->
            val route = tab.route()
            NavigationBarItem(
                selected = when (tab) {
                    BottomTab.HOME -> Routes.isHome(currentRoute)
                    BottomTab.TRANSACTIONS -> Routes.isTransactions(currentRoute)
                    BottomTab.REPORTS -> Routes.isReports(currentRoute)
                    BottomTab.SETTINGS -> Routes.isSettings(currentRoute)
                },
                onClick = { onNavigate(route) },
                icon = { Icon(imageVector = tab.icon(), contentDescription = tr(tab.label)) },
                label = { Text(tr(tab.label)) }
            )
        }
    }
}

/**
 * 隐私模式：开启时给窗口加 FLAG_SECURE 阻止截屏；新用户默认关闭。
 * 关闭时立即清除标记，离开 Composable 时也清理。
 */
@Composable
private fun PrivacyGuard(enabled: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (window != null) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** 从任意 Context 逐层向上找到宿主 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
