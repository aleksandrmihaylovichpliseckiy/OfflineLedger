package com.ledger.offline.domain.model

/**
 * 应用语言。SYSTEM 表示跟随系统（系统为中文/日文/俄文时用对应语言，其它用英文）。
 */
enum class AppLanguage(val label: String) {
    SYSTEM("跟随系统"),
    ZH("简体中文"),
    EN("English"),
    JA("日本語"),
    RU("Русский")
}

/** 主题色调。 */
enum class ThemeTone(val label: String) {
    GREEN("墨绿"),
    BLUE("钴蓝"),
    PURPLE("暮紫"),
    ORANGE("暖橙"),
    SAKURA_PINK("樱花粉"),
    MONO("黑白")
}

/** 日间 / 夜间模式。 */
enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("日间"),
    DARK("夜间")
}

/** 图标配色方式。 */
enum class IconColorMode(val label: String) {
    FOLLOW_THEME("跟随主题"),
    MANUAL("手动设置")
}

/** 银行卡短信 / 支付通知重复记账的处理方式。 */
enum class DedupeMode(val label: String) {
    AUTO_BETA("自动托管（beta）"),
    ASK("每次询问"),
    OFF("关闭")
}

/** 界面密度（只缩放字体，不改布局结构）。 */
enum class UiDensity(val label: String, val fontScale: Float) {
    COMPACT("紧凑", 0.88f),
    STANDARD("标准", 1.0f),
    COMFORTABLE("宽松", 1.12f)
}

/** 主页账户余额的排序方式。 */
enum class AccountSortMode(val label: String) {
    CUSTOM("按设定顺序"),
    BALANCE_DESC("按余额降序"),
    FLOW_DESC("按流水降序")
}

/** 报表周期计算模式。 */
enum class ReportMode(val label: String) {
    STANDARD("收支默认模式"),
    MONTHLY_SETTLEMENT("月结模式")
}

/** 主页可排序 / 隐藏的卡片区块。 */
enum class HomeSection(val label: String) {
    ACCOUNTS("账户余额"),
    TREND("收支趋势"),
    RECENT("最近流水"),
    QUICK("快捷操作")
}

/** 底部导航项。HOME 与 SETTINGS 始终保留，不能隐藏。 */
enum class BottomTab(val label: String) {
    HOME("首页"),
    TRANSACTIONS("明细"),
    REPORTS("报表"),
    SETTINGS("设置")
}
