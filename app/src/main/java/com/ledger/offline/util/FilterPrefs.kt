package com.ledger.offline.util

import com.ledger.offline.domain.model.NotificationKind

/**
 * 通知类型过滤与屏蔽词的纯文本编解码（DataStore 里只存字符串，便于跨版本兼容）。
 *
 * 通知类型过滤格式：`包名=PAYMENT,INCOME;包名2=...`，未出现的包表示“全部类型都允许”。
 * 屏蔽词格式：换行 / 中英文逗号 / 分号分隔，忽略空白项。
 */
object FilterPrefs {

    private val BLOCK_WORD_SEPARATOR = Regex("[,，;；\\n\\r]+")
    private val PACKAGE_SEPARATOR = ';'

    fun parseBlockWords(raw: String): List<String> =
        raw.split(BLOCK_WORD_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun encodeBlockWords(words: List<String>): String =
        words.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")

    fun isBlocked(text: String, words: List<String>): Boolean {
        if (text.isBlank() || words.isEmpty()) return false
        val lower = text.lowercase()
        return words.any { word -> word.isNotEmpty() && lower.contains(word.lowercase()) }
    }

    /** 解析过滤表；非法项忽略。 */
    fun parseNotifyKindFilters(raw: String): Map<String, Set<NotificationKind>> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, Set<NotificationKind>>()
        raw.split(PACKAGE_SEPARATOR).forEach { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            val pkg = trimmed.substring(0, eq).trim()
            if (pkg.isEmpty()) return@forEach
            val kinds = trimmed.substring(eq + 1)
                .split(',')
                .mapNotNull { name -> NotificationKind.values().firstOrNull { it.name == name.trim() } }
                .toSet()
            if (kinds.isNotEmpty()) result[pkg] = kinds
        }
        return result
    }

    /** 某包允许的通知类型；没有配置时返回全部。 */
    fun allowedKinds(raw: String, packageName: String): Set<NotificationKind> =
        parseNotifyKindFilters(raw)[packageName] ?: NotificationKind.values().toSet()

    /** 写回过滤表：全部类型时不写该项（表示允许全部）。 */
    fun encodeNotifyKindFilters(filters: Map<String, Set<NotificationKind>>): String {
        val all = NotificationKind.values().toSet()
        return filters.entries
            .filter { it.key.isNotBlank() && it.value.isNotEmpty() && it.value != all }
            .joinToString(PACKAGE_SEPARATOR.toString()) { (pkg, kinds) ->
                val names = NotificationKind.values().filter { it in kinds }.joinToString(",") { it.name }
                "$pkg=$names"
            }
    }
}
