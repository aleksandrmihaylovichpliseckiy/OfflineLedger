package com.ledger.offline.i18n

import com.ledger.offline.domain.model.AppLanguage

/**
 * 多语言翻译表的统一入口。
 *
 * 每个语言一个文件（TranslationsEn / TranslationsJa / TranslationsRu），
 * 方便并行维护；key 为中文原文，缺失时回退中文。
 */
internal object Translations {

    fun mapFor(language: AppLanguage): Map<String, String> = when (language) {
        AppLanguage.EN -> TranslationsEn.MAP
        AppLanguage.JA -> TranslationsJa.MAP
        AppLanguage.RU -> TranslationsRu.MAP
        else -> emptyMap()
    }
}
