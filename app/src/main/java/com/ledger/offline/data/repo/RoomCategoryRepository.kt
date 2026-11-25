package com.ledger.offline.data.repo

import com.ledger.offline.data.DbWriteMutex
import com.ledger.offline.data.db.CategoryDao
import com.ledger.offline.data.db.CategoryEntity
import com.ledger.offline.data.db.toDomain
import com.ledger.offline.data.db.toEntity
import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.domain.repo.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

/**
 * [CategoryRepository] 的 Room 实现，包含短信自动归类逻辑。
 *
 * 自动归类规则（契约 3.1）：把分类的关键词按中英文逗号拆开、trim、忽略空串，
 * 在文本中做包含匹配；命中多个关键词时取**最长**关键词所属的分类（关键词越长越具体）。
 */
class RoomCategoryRepository(
    private val dao: CategoryDao
) : CategoryRepository {

    override fun observeAll(): Flow<List<Category>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(type: TxType?): List<Category> {
        val rows = if (type == null) dao.getAll() else dao.getByType(type)
        return rows.map { it.toDomain() }
    }

    override suspend fun getById(id: Long): Category? = dao.findById(id)?.toDomain()

    override suspend fun findMatching(text: String, type: TxType): Category? {
        if (text.isBlank()) {
            return null
        }
        val candidates = dao.getByType(type)
        var bestEntity: CategoryEntity? = null
        var bestKeywordLength = 0
        for (entity in candidates) {
            val hitLength = splitKeywords(entity.keywords)
                .filter { keyword -> text.contains(keyword, ignoreCase = true) }
                .maxOfOrNull { keyword -> keyword.length } ?: 0
            if (hitLength > bestKeywordLength) {
                bestKeywordLength = hitLength
                bestEntity = entity
            }
        }
        return bestEntity?.toDomain()
    }

    override suspend fun defaultFor(type: TxType): Category = DbWriteMutex.mutex.withLock {
        val fallbackName = defaultName(type)
        val candidates = dao.getByType(type)
        candidates.firstOrNull { category -> category.name == fallbackName }?.let { category ->
            return@withLock category.toDomain()
        }
        candidates.firstOrNull()?.let { category -> return@withLock category.toDomain() }
        // 极端情况：该类型一个分类都没有（用户全部删除），自动插入兜底分类。
        val fallback = Category(
            name = fallbackName,
            type = type,
            icon = "📦",
            keywords = "",
            sortOrder = dao.maxSortOrder(type) + 1,
            isDefault = true
        )
        val id = dao.insert(fallback.toEntity())
        fallback.copy(id = id)
    }

    override suspend fun insert(category: Category): Long = dao.insert(category.toEntity())

    override suspend fun update(category: Category) {
        dao.update(category.toEntity())
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    private fun splitKeywords(raw: String): List<String> =
        raw.split(',', '，')
            .map { keyword -> keyword.trim() }
            .filter { keyword -> keyword.isNotEmpty() }

    private fun defaultName(type: TxType): String =
        if (type == TxType.EXPENSE) "其他支出" else "其他收入"
}
