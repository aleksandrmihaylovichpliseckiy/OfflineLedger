package com.ledger.offline.ui

import com.ledger.offline.domain.model.ReportPeriod

/**
 * 导航路由统一定义，避免各页面硬编码字符串。
 *
 * 报表页带两个参数：period（DAILY/WEEKLY/MONTHLY）与 auto（是否进入后立即生成）。
 * 记一笔 / 编辑页复用同一路由，txId 为 -1 表示新增。
 */
object Routes {

    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val SETTINGS = "settings"

    /** 收支分析与趋势页（从主页趋势卡片 / 报表页进入，不占底部导航）。 */
    const val ANALYSIS = "analysis"

    /** 报表页路由模板（NavHost 注册时使用）。 */
    const val REPORTS = "reports?period={period}&auto={auto}"

    /** 底部导航“报表”标签使用的固定入口。 */
    const val REPORT_TAB = "reports?period=DAILY&auto=false"

    /** 记一笔 / 编辑页路由模板。 */
    const val EDIT = "edit?txId={txId}"

    fun report(period: ReportPeriod, auto: Boolean = false): String =
        "reports?period=${period.name}&auto=$auto"

    fun edit(txId: Long): String = "edit?txId=$txId"

    fun isHome(route: String?): Boolean = route == HOME

    fun isTransactions(route: String?): Boolean = route == TRANSACTIONS

    fun isReports(route: String?): Boolean = route != null && route.startsWith("reports")

    fun isSettings(route: String?): Boolean = route == SETTINGS

    fun isAnalysis(route: String?): Boolean = route == ANALYSIS

    fun isEdit(route: String?): Boolean = route != null && route.startsWith("edit")
}
