package com.ledger.offline.util

import com.ledger.offline.domain.model.BottomTab
import com.ledger.offline.domain.model.HomeSection

/**
 * 布局偏好的解析与默认值工具。
 *
 * 设置里以逗号分隔的枚举名保存顺序与隐藏项，例如：
 * - homeSectionOrder = "ACCOUNTS,TREND,RECENT,QUICK"
 * - hiddenHomeSections = "TREND"
 * - bottomNavOrder = "HOME,TRANSACTIONS,REPORTS,SETTINGS"
 * - hiddenBottomNav = ""
 */
object LayoutPrefs {

    const val DEFAULT_HOME_ORDER = "ACCOUNTS,TREND,RECENT,QUICK"
    const val DEFAULT_NAV_ORDER = "HOME,TRANSACTIONS,REPORTS,SETTINGS"

    fun parseHomeSections(order: String, hidden: String): List<HomeSection> {
        val hiddenSet = splitCsv(hidden).toSet()
        val parsed = splitCsv(order).mapNotNull { name ->
            HomeSection.values().firstOrNull { it.name == name }
        }
        val ordered = parsed.distinct().ifEmpty { HomeSection.values().toList() }
        val missing = HomeSection.values().filter { it !in ordered }
        return (ordered + missing).filter { it.name !in hiddenSet }
    }

    fun parseBottomTabs(order: String, hidden: String): List<BottomTab> {
        val hiddenSet = splitCsv(hidden).toSet()
        val parsed = splitCsv(order).mapNotNull { name ->
            BottomTab.values().firstOrNull { it.name == name }
        }
        val ordered = parsed.distinct().ifEmpty { BottomTab.values().toList() }
        val missing = BottomTab.values().filter { it !in ordered }
        // 首页与设置页必须保留，否则用户无法再进入设置恢复布局。
        return (ordered + missing)
            .filter { tab ->
                tab == BottomTab.HOME || tab == BottomTab.SETTINGS || tab.name !in hiddenSet
            }
    }

    fun splitCsv(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun join(values: List<Enum<*>>): String =
        values.joinToString(",") { it.name }

    fun joinStrings(values: List<String>): String = values.joinToString(",") { it.trim() }
}
