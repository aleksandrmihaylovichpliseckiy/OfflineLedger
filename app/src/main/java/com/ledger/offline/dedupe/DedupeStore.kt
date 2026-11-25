package com.ledger.offline.dedupe

/**
 * `settings.pendingDuplicate` 的编解码（CONTRACT v3 §2）。
 *
 * 存储格式：`keepId|dropId;keepId|dropId;...`（分号分隔多条，竖线分隔保留 / 删除的流水 id）。
 * 解析时容忍空白、空项、非数字与非法 id；任何非法项都会被安静丢弃，
 * 从而保证一条脏数据不会让“每次询问”的整个待办列表失效。
 */
internal object DedupeStore {

    /** 多条重复对之间的分隔符。 */
    private const val ENTRY_SEPARATOR = ";"

    /** 一对重复对内部两个 id 的分隔符。 */
    private const val ID_SEPARATOR = "|"

    /** 解析成有序去重的 (keepId, dropId) 列表；空串 / 脏数据返回空列表。 */
    fun decode(raw: String): List<Pair<Long, Long>> {
        if (raw.isBlank()) return emptyList()
        val entries = LinkedHashSet<Pair<Long, Long>>()
        for (token in raw.split(ENTRY_SEPARATOR)) {
            val text = token.trim()
            if (text.isEmpty()) continue
            val parts = text.split(ID_SEPARATOR)
            if (parts.size != 2) continue
            val keepId = parts[0].trim().toLongOrNull() ?: continue
            val dropId = parts[1].trim().toLongOrNull() ?: continue
            if (keepId <= 0L || dropId <= 0L || keepId == dropId) continue
            entries.add(keepId to dropId)
        }
        return entries.toList()
    }

    /** 编码回字符串；空列表输出空串（表示没有待询问项）。 */
    fun encode(entries: List<Pair<Long, Long>>): String =
        entries.joinToString(ENTRY_SEPARATOR) { (keepId, dropId) -> "$keepId$ID_SEPARATOR$dropId" }

    /** 该对是否已在待询问列表里（顺序无关）。 */
    fun contains(raw: String, keepId: Long, dropId: Long): Boolean =
        decode(raw).any { (storedKeep, storedDrop) ->
            samePair(storedKeep, storedDrop, keepId, dropId)
        }

    /** 追加一对；已存在（顺序无关）时原样返回，避免重复询问同一对。 */
    fun append(raw: String, keepId: Long, dropId: Long): String {
        val entries = decode(raw)
        val duplicated = entries.any { (storedKeep, storedDrop) ->
            samePair(storedKeep, storedDrop, keepId, dropId)
        }
        if (duplicated) return encode(entries)
        return encode(entries + (keepId to dropId))
    }

    /** 移除一对（顺序无关）；不存在时只是把脏项清理干净。 */
    fun remove(raw: String, keepId: Long, dropId: Long): String =
        encode(
            decode(raw).filterNot { (storedKeep, storedDrop) ->
                samePair(storedKeep, storedDrop, keepId, dropId)
            }
        )

    /** 与顺序无关的一对 key，用于“已处理 / 已待询问”判重。 */
    fun pairKey(firstId: Long, secondId: Long): Pair<Long, Long> =
        if (firstId <= secondId) firstId to secondId else secondId to firstId

    private fun samePair(
        storedKeep: Long,
        storedDrop: Long,
        keepId: Long,
        dropId: Long
    ): Boolean =
        (storedKeep == keepId && storedDrop == dropId) ||
            (storedKeep == dropId && storedDrop == keepId)
}
