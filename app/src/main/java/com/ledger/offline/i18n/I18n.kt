package com.ledger.offline.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.ledger.offline.domain.model.AppLanguage
import java.util.Locale

/**
 * 当前界面语言（保留给需要的 Composable 使用；实际翻译走 [I18n] 全局语言）。
 * 语言切换时 [com.ledger.offline.ui.LedgerApp] 会以 `key(language)` 重建界面并同步 [I18n]。
 */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.SYSTEM }

/**
 * 取翻译文本。**故意不做成 @Composable**：
 * 这样在 onClick / 协程 / 普通函数里也能调用，避免“@Composable invocations”编译错误；
 * 语言切换时 LedgerApp 会用 key(language) 重建整个界面，从而刷新所有文案。
 */
fun tr(zh: String): String = I18n.t(zh)

/** 带参数的翻译：`tr("已删除：%1$s", name)`。 */
fun tr(zh: String, vararg args: Any): String = I18n.t(zh, *args)

/**
 * 极简本地化：以中文原文作为 key，查对应语言的翻译表，查不到就回退中文。
 *
 * 这样做的原因：
 * 1. 现有代码全是中文字面量，改造成本最低；
 * 2. 报表正文、通知、短信导入提示等非 Compose 代码可以直接用 [t]，不需要 Context；
 * 3. 翻译缺失时不会崩溃，也不会出现空白。
 */
object I18n {

    @Volatile
    var current: AppLanguage = AppLanguage.SYSTEM
        private set

    fun set(language: AppLanguage) {
        current = language
    }

    fun t(zh: String): String = t(current, zh)

    fun t(zh: String, vararg args: Any): String = t(current, zh, *args)

    fun t(language: AppLanguage, zh: String): String = lookup(language, zh)

    fun t(language: AppLanguage, zh: String, vararg args: Any): String {
        val text = lookup(language, zh)
        if (args.isEmpty()) return text
        return try {
            String.format(Locale.getDefault(), text, *args)
        } catch (e: Exception) {
            text
        }
    }

    /** 把 SYSTEM 解析成具体语言：系统为中文 / 日文 / 俄文时用对应语言，其它用英文。 */
    fun resolve(language: AppLanguage): AppLanguage = when (language) {
        AppLanguage.SYSTEM -> when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "zh" -> AppLanguage.ZH
            "ja" -> AppLanguage.JA
            "ru" -> AppLanguage.RU
            else -> AppLanguage.EN
        }
        else -> language
    }

    private fun lookup(language: AppLanguage, zh: String): String {
        val resolved = resolve(language)
        if (resolved == AppLanguage.ZH) return zh
        return Translations.mapFor(resolved)[zh] ?: zh
    }
}
