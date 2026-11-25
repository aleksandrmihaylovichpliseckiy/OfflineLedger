package com.ledger.offline.domain.repo

import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.TxType
import kotlinx.coroutines.flow.Flow

/** 分类读写与自动归类。 */
interface CategoryRepository {

    fun observeAll(): Flow<List<Category>>

    suspend fun getAll(type: TxType? = null): List<Category>

    suspend fun getById(id: Long): Category?

    /**
     * 用关键词对一段文本（短信 / 通知正文）做自动归类。
     * 命中多个关键词时取关键词更长的分类；没有命中返回 null。
     */
    suspend fun findMatching(text: String, type: TxType): Category?

    /** 兜底分类（“其他支出”/“其他收入”）。种子数据保证一定存在。 */
    suspend fun defaultFor(type: TxType): Category

    suspend fun insert(category: Category): Long

    suspend fun update(category: Category)

    suspend fun delete(id: Long)
}
