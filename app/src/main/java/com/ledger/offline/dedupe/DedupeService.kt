package com.ledger.offline.dedupe

import android.util.Log
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.ManualBalanceSync
import com.ledger.offline.domain.model.DedupeMode
import com.ledger.offline.domain.model.TimeRange
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.util.DateTimes
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自动去重服务（CONTRACT v4 §2）。
 *
 * 解决的问题：同一笔真实收付会从多个来源各产生一条流水（银行短信、微信 / 支付宝通知、
 * 其它应用通知、手动补记），直接入库会重复记账。本服务在入库后按 [DedupeMode] 决定怎么处理：
 *
 * - [DedupeMode.AUTO_BETA]：自动托管——按 [DedupeMatcher.keepRank] 保留优先级最高的那条
 *   （SMS > WECHAT > ALIPAY > APP > MANUAL，同来源保留先入库的），删除另一条；
 *   若保留记录的 merchant 为空而另一条有值，则把 merchant / rawText 合并进保留记录。
 *   **只要这一对里有手工记账（[com.ledger.offline.domain.model.TxSource.MANUAL]），
 *   绝不自动删除，改为加入待询问**；
 * - [DedupeMode.ASK]：两条都保留，把 `keepId|dropId` 追加进 `settings.pendingDuplicate` 等用户决定；
 * - [DedupeMode.OFF]：不做任何写操作，`verifyNow` 只统计疑似重复数。
 *
 * 约定：
 * - [afterInsert] 只做“本次插入相关的近邻匹配”，成本很低，可以在短信广播 / 通知监听里同步调用；
 * - [verifyNow] 是首页“一键核验”的补扫入口，扫描最近 [VERIFY_LOOKBACK_DAYS] 天的全部流水；
 * - 所有公开方法都有统一异常兜底（[guard]，内部步骤再用 `runCatching`），只记日志：
 *   自动去重永远不能让导入或首页崩溃；
 * - “同一对”用与顺序无关的 (id, id) 判重：已在 `pendingDuplicate` 里的对不会被重复合并 / 重复询问；
 * - 已经在待询问列表里的删除侧记录会被保护，不会被另一次自动合并悄悄删掉（用户还要确认它）。
 */
object DedupeService {

    private const val TAG = "DedupeService"

    /** 一键核验的回顾天数（契约：最近 90 天）。 */
    private const val VERIFY_LOOKBACK_DAYS = 90

    /**
     * 写串行化。短信广播、通知监听、首页核验、手动记账可能并发触发，
     * 串行执行保证“删一条 + 记一条待询问”这类组合操作不会互相踩踏。
     */
    private val writeLock = Mutex()

    /**
     * 新流水入库成功后调用（CONTRACT v4 §2）：返回 true 表示本次已自动合并（AUTO_BETA）
     * 或已加入待询问（ASK / AUTO 遇到手工记账）。
     *
     * [DedupeMode.OFF] 时立即返回 false；任何异常都只记日志，不影响调用方（导入 / 手动记账）。
     */
    suspend fun afterInsert(repos: LedgerRepos, inserted: Transaction): Boolean = guard(false) {
        if (inserted.amountCents <= 0L || inserted.occurredAt <= 0L) return@guard false
        // TRANSFER（基金申购 / 赎回等划转）不计收支，不参与去重配对。
        if (!DedupeMatcher.isDedupeType(inserted.type)) return@guard false
        // 关闭模式直接返回，连写锁都不抢，保证不影响导入流程。
        val settings = repos.settings.current()
        if (settings.dedupeMode == DedupeMode.OFF) return@guard false
        writeLock.withLock {
            handleInsert(repos, inserted, settings.dedupeMode, settings.pendingDuplicate)
        }
    }

    /**
     * 扫描最近 90 天的疑似重复流水，并按当前 [DedupeMode] 自动合并 / 加入待询问 / 只统计。
     *
     * 已经在 `pendingDuplicate` 里的对不会被重复处理（“不能重复处理同一对”），
     * 但会继续计入返回值的 [VerifyResult.pending]，方便首页决定是否弹出询问对话框。
     * 自动模式遇到含手工记账的对只加入待询问，绝不删除手工记录。
     */
    suspend fun verifyNow(repos: LedgerRepos): VerifyResult =
        guard(VerifyResult(found = 0, merged = 0, pending = 0)) {
            writeLock.withLock { runVerify(repos) }
        }

    /**
     * 还原待询问列表：从 `settings.pendingDuplicate` 解码 id，再用 `transactions.getById` 取回流水。
     *
     * 记录已被删除 / 找不到的对会被清理出 `pendingDuplicate`，避免列表里堆积幽灵项。
     * 返回顺序与存储顺序一致。
     */
    suspend fun pendingPairs(repos: LedgerRepos): List<DuplicatePair> =
        guard(emptyList<DuplicatePair>()) {
            writeLock.withLock { loadPending(repos, persistCleanup = true) }
        }

    /**
     * 用户在“每次询问”里做出选择：
     * - [DedupeChoice.MERGE]：删除 [DuplicatePair.dropId]（保留 keepId），并从待询问列表移除该对；
     * - [DedupeChoice.KEEP_BOTH]：两条都保留，只从待询问列表移除该对。
     *
     * 删除失败时不会把该对从待询问列表里抹掉，用户下次还能重试；异常同样只记日志。
     */
    suspend fun resolve(repos: LedgerRepos, pair: DuplicatePair, choice: DedupeChoice) {
        guard(Unit) {
            writeLock.withLock { resolvePair(repos, pair, choice) }
        }
    }

    // ---------------------------------------------------------------- afterInsert

    private suspend fun handleInsert(
        repos: LedgerRepos,
        inserted: Transaction,
        mode: DedupeMode,
        pendingRaw: String
    ): Boolean {
        if (mode == DedupeMode.OFF) return false

        // 调用方可能在 insert 之后直接传回入库前的对象（id = 0），这里按 sourceRef 找回真实记录。
        val stored = resolveInserted(repos, inserted) ?: return false
        if (!DedupeMatcher.isDedupeType(stored.type)) return false
        // 待询问列表里的记录不能被自动合并删掉：用户还要在对话框里确认它。
        val pair = findPair(repos, stored, pendingIds(pendingRaw)) ?: return false
        val keep = pair.first
        val drop = pair.second

        return when (mode) {
            // 含手工记账：自动托管不删任何记录，降级成“每次询问”。
            DedupeMode.AUTO_BETA ->
                if (DedupeMatcher.involvesManual(keep, drop)) {
                    addPendingPair(repos, keep, drop)
                } else {
                    mergePair(repos, keep, drop)
                }
            DedupeMode.ASK -> addPendingPair(repos, keep, drop)
            DedupeMode.OFF -> false
        }
    }

    /**
     * 把 [inserted] 解析成数据库里的真实流水（保证 id > 0）。
     *
     * 优先用 id；id 为 0 时在 ±180 秒窗口内按 sourceRef（必要时退化为
     * source + amount + type + occurredAt）反查，兼容“insert 后直接传回原对象”的调用方式。
     */
    private suspend fun resolveInserted(repos: LedgerRepos, inserted: Transaction): Transaction? {
        if (inserted.id > 0L) {
            val stored = runCatching { repos.transactions.getById(inserted.id) }.getOrNull()
            if (stored != null) return stored
        }
        if (inserted.sourceRef.isBlank()) return null
        val rows = candidatesAround(repos, inserted.occurredAt) ?: return null
        rows.firstOrNull { it.tx.sourceRef == inserted.sourceRef }?.let { return it.tx }
        return rows.firstOrNull { record ->
            val tx = record.tx
            tx.source == inserted.source &&
                tx.amountCents == inserted.amountCents &&
                tx.type == inserted.type &&
                tx.occurredAt == inserted.occurredAt
        }?.tx
    }

    /**
     * 在 ±180 秒窗口内找出与 [tx] 配对的流水：先取时间最接近、且删除侧不在待询问列表里
     * （[protectedIds]）的一条，避免删掉用户还要确认的记录。
     *
     * 匹配规则完全交给 [DedupeMatcher.isCandidatePair]（任意来源，含同来源）；
     * 保留 / 删除侧由 [DedupeMatcher.orderedPair] 决定。
     */
    private suspend fun findPair(
        repos: LedgerRepos,
        tx: Transaction,
        protectedIds: Set<Long>
    ): Pair<Transaction, Transaction>? {
        val rows: List<TransactionRecord> = candidatesAround(repos, tx.occurredAt) ?: return null
        return rows.asSequence()
            .map { it.tx }
            .filter { candidate -> DedupeMatcher.isCandidatePair(tx, candidate) }
            .sortedWith(compareBy<Transaction>({ abs(it.occurredAt - tx.occurredAt) }, { it.id }))
            .mapNotNull { candidate -> DedupeMatcher.orderedPair(tx, candidate) }
            .firstOrNull { (_, drop) -> drop.id !in protectedIds }
    }

    /** 取 [occurredAt] 前后各 180 秒窗口内的流水（时间差恰好 180 秒也要包含，所以右端 +1ms）。 */
    private suspend fun candidatesAround(
        repos: LedgerRepos,
        occurredAt: Long
    ): List<TransactionRecord>? {
        val start = (occurredAt - DedupeMatcher.TIME_TOLERANCE_MILLIS).coerceAtLeast(0L)
        val end = occurredAt + DedupeMatcher.TIME_TOLERANCE_MILLIS + 1L
        return runCatching { repos.transactions.recordsBetween(TimeRange(start, end)) }
            .onFailure { Log.w(TAG, "读取去重候选流水失败", it) }
            .getOrNull()
    }

    /** 待询问列表里出现过的所有 id（两侧）：其中任何一条都不能被自动合并删掉。 */
    private fun pendingIds(raw: String): Set<Long> {
        val ids = HashSet<Long>()
        for ((keepId, dropId) in DedupeStore.decode(raw)) {
            ids.add(keepId)
            ids.add(dropId)
        }
        return ids
    }

    // ---------------------------------------------------------------- 合并 / 待询问

    /**
     * 自动托管的核心动作：删除 [drop]、保留 [keep]；
     * 保留记录缺少 merchant / rawText 时用 [drop] 的值补齐（见 [composeMergedTransaction]）。
     *
     * @return 是否真的删掉了重复记录（删除失败时返回 false，调用方只计 found 不计 merged）。
     */
    private suspend fun mergePair(
        repos: LedgerRepos,
        keep: Transaction,
        drop: Transaction
    ): Boolean {
        composeMergedTransaction(keep, drop)?.let { merged ->
            runCatching { repos.transactions.update(merged) }
                .onFailure { Log.w(TAG, "合并重复流水商户信息失败：id=${keep.id}", it) }
        }
        val deleted = runCatching { repos.transactions.delete(drop.id) }
            .onFailure { Log.w(TAG, "删除重复流水失败：id=${drop.id}", it) }
            .isSuccess
        if (deleted) {
            // 被删的是已同步过余额的手动流水时，把余额加回去。
            ManualBalanceSync.revert(repos, drop)
        }
        return deleted
    }

    /**
     * 需要合并进保留记录的副本；没有可合并的信息时返回 null（避免无意义的写库）。
     *
     * - merchant：保留记录为空才用 [drop] 的补上，两条都有时保留原值；
     * - rawText：合并两段原始文本（去重 / 包含关系时保留更完整的一段，否则换行拼接），
     *   便于在保留记录里核对两边的原文。
     */
    private fun composeMergedTransaction(keep: Transaction, drop: Transaction): Transaction? {
        val merchant = if (keep.merchant.isNotBlank()) keep.merchant else drop.merchant
        val rawText = when {
            drop.rawText.isBlank() -> keep.rawText
            keep.rawText.isBlank() -> drop.rawText
            keep.rawText == drop.rawText -> keep.rawText
            keep.rawText.contains(drop.rawText) -> keep.rawText
            drop.rawText.contains(keep.rawText) -> drop.rawText
            else -> keep.rawText + "\n" + drop.rawText
        }
        if (merchant == keep.merchant && rawText == keep.rawText) return null
        return keep.copy(merchant = merchant, rawText = rawText)
    }

    /**
     * 每次询问：两条都保留，把这一对追加进 `settings.pendingDuplicate`。
     * 已存在（顺序无关）时返回 false，不重复写设置。
     */
    private suspend fun addPendingPair(
        repos: LedgerRepos,
        keep: Transaction,
        drop: Transaction
    ): Boolean {
        val current = repos.settings.current().pendingDuplicate
        if (DedupeStore.contains(current, keep.id, drop.id)) return false
        val next = DedupeStore.append(current, keep.id, drop.id)
        repos.settings.update { it.copy(pendingDuplicate = next) }
        return true
    }

    /** 从 `settings.pendingDuplicate` 移除一对（顺序无关）。 */
    private suspend fun removePendingPair(repos: LedgerRepos, keepId: Long, dropId: Long) {
        repos.settings.update { current ->
            val next = DedupeStore.remove(current.pendingDuplicate, keepId, dropId)
            if (next == current.pendingDuplicate) current else current.copy(pendingDuplicate = next)
        }
    }

    // ---------------------------------------------------------------- 待询问列表

    /**
     * 解码 + 还原待询问列表。
     *
     * @param persistCleanup 是否把清理结果写回设置（关闭模式 / 只读场景传 false）。
     */
    private suspend fun loadPending(
        repos: LedgerRepos,
        persistCleanup: Boolean
    ): List<DuplicatePair> {
        val raw = repos.settings.current().pendingDuplicate
        val entries = DedupeStore.decode(raw)
        val pairs = ArrayList<DuplicatePair>(entries.size)
        val validEntries = ArrayList<Pair<Long, Long>>(entries.size)
        for (entry in entries) {
            val keepTx = runCatching { repos.transactions.getById(entry.first) }.getOrNull()
            val dropTx = runCatching { repos.transactions.getById(entry.second) }.getOrNull()
            if (keepTx == null || dropTx == null) {
                // 记录已删除 / 找不到：跳过这一对，稍后从存储里清掉。
                continue
            }
            validEntries.add(entry)
            pairs.add(
                DuplicatePair(
                    keepId = keepTx.id,
                    dropId = dropTx.id,
                    amountCents = keepTx.amountCents,
                    occurredAt = keepTx.occurredAt,
                    keepMerchant = keepTx.merchant,
                    dropMerchant = dropTx.merchant,
                    keepSource = keepTx.source.label,
                    dropSource = dropTx.source.label
                )
            )
        }
        // 存储里可能残留已删除的记录 / 脏项：把清理后的结果写回，避免每次都要重新过滤。
        val normalized = DedupeStore.encode(validEntries)
        if (persistCleanup && normalized != raw) {
            writePendingDuplicate(repos, normalized)
        }
        return pairs
    }

    private suspend fun writePendingDuplicate(repos: LedgerRepos, value: String) {
        runCatching { repos.settings.update { it.copy(pendingDuplicate = value) } }
            .onFailure { Log.w(TAG, "写入待询问去重列表失败", it) }
    }

    // ---------------------------------------------------------------- resolve

    private suspend fun resolvePair(
        repos: LedgerRepos,
        pair: DuplicatePair,
        choice: DedupeChoice
    ) {
        when (choice) {
            DedupeChoice.MERGE -> {
                val dropTx = repos.transactions.getById(pair.dropId)
                if (dropTx != null) {
                    // 与自动托管保持一致：保留记录缺 merchant 时，用被删记录的 merchant / rawText 补齐。
                    val keepTx = runCatching { repos.transactions.getById(pair.keepId) }.getOrNull()
                    if (keepTx != null) {
                        composeMergedTransaction(keepTx, dropTx)?.let { merged ->
                            runCatching { repos.transactions.update(merged) }
                                .onFailure {
                                    Log.w(TAG, "合并重复流水商户信息失败：id=${keepTx.id}", it)
                                }
                        }
                    }
                    val deleted = runCatching { repos.transactions.delete(dropTx.id) }
                        .onFailure { Log.w(TAG, "删除重复流水失败：id=${dropTx.id}", it) }
                        .isSuccess
                    if (!deleted) {
                        // 删除失败：保留待询问项，用户下次还能重试。
                        return
                    }
                    // 被删的是已同步过余额的手动流水时，把余额加回去。
                    ManualBalanceSync.revert(repos, dropTx)
                }
                removePendingPair(repos, pair.keepId, pair.dropId)
            }
            DedupeChoice.KEEP_BOTH -> removePendingPair(repos, pair.keepId, pair.dropId)
        }
    }

    // ---------------------------------------------------------------- verifyNow

    /**
     * 补扫最近 90 天：按 (amountCents, type) 分桶，桶内按时间排序后用滑动窗口找 ≤180 秒的对，
     * 因此同金额流水很多时也不会退化成全表两两比较。
     *
     * 每对按 [DedupeMatcher.orderedPair] 决定保留 / 删除；已消费的记录（[usedIds]）不再参与
     * 其它配对，待询问列表里的删除侧记录（[protectedIds]）不会被删除。
     */
    private suspend fun runVerify(repos: LedgerRepos): VerifyResult {
        val mode = repos.settings.current().dedupeMode

        // 先还原待询问列表（关闭模式只读，不写回清理结果）。
        val pending = loadPending(repos, persistCleanup = mode != DedupeMode.OFF)
        val pendingKeys = HashSet<Pair<Long, Long>>(pending.size)
        val protectedIds = HashSet<Long>(pending.size)
        for (pair in pending) {
            pendingKeys.add(DedupeStore.pairKey(pair.keepId, pair.dropId))
            protectedIds.add(pair.keepId)
            protectedIds.add(pair.dropId)
        }

        val records = repos.transactions.recordsBetween(DateTimes.lastDaysRange(VERIFY_LOOKBACK_DAYS))
        val candidates = records.asSequence()
            .map { it.tx }
            .filter { tx ->
                tx.id > 0L && tx.amountCents > 0L && DedupeMatcher.isDedupeType(tx.type)
            }
            .toList()
        // 金额 + 收支方向完全相同才可能重复，先按这个 key 分桶。
        val buckets = candidates.groupBy { Pair(it.amountCents, it.type) }

        var found = 0
        var merged = 0
        var added = 0
        val usedIds = HashSet<Long>()
        for (bucket in buckets.values) {
            val sorted = bucket.sortedWith(compareBy<Transaction>({ it.occurredAt }, { it.id }))
            var end = 0
            for (i in sorted.indices) {
                val first = sorted[i]
                if (end < i + 1) end = i + 1
                // 时间窗右边界单调右移：桶内按 occurredAt 升序，窗口只增不减。
                while (end < sorted.size &&
                    sorted[end].occurredAt - first.occurredAt <= DedupeMatcher.TIME_TOLERANCE_MILLIS
                ) {
                    end++
                }
                for (j in (i + 1) until end) {
                    val second = sorted[j]
                    if (!DedupeMatcher.isCandidatePair(first, second)) continue
                    val pair = DedupeMatcher.orderedPair(first, second) ?: continue
                    val keep = pair.first
                    val drop = pair.second
                    // 一条流水最多参与一次处理，避免“A 删 B、B 又删 C”的连锁误删。
                    if (keep.id in usedIds || drop.id in usedIds) continue
                    // 已在待询问列表里的对不重复处理，等用户自己决定。
                    if (DedupeStore.pairKey(keep.id, drop.id) in pendingKeys) {
                        usedIds.add(keep.id)
                        usedIds.add(drop.id)
                        continue
                    }
                    // 自动模式 / 询问模式都不会删掉待询问列表里的记录（关闭模式不写库，无需保护）。
                    if (mode != DedupeMode.OFF && drop.id in protectedIds) continue

                    found++
                    when (mode) {
                        DedupeMode.AUTO_BETA ->
                            if (DedupeMatcher.involvesManual(keep, drop)) {
                                // 含手工记账：只转“待询问”，绝不删记录。
                                if (addPendingPair(repos, keep, drop)) added++
                                protectedIds.add(keep.id)
                                protectedIds.add(drop.id)
                            } else {
                                if (mergePair(repos, keep, drop)) merged++
                            }
                        DedupeMode.ASK -> {
                            if (addPendingPair(repos, keep, drop)) added++
                            protectedIds.add(keep.id)
                            protectedIds.add(drop.id)
                        }
                        DedupeMode.OFF -> Unit
                    }
                    usedIds.add(keep.id)
                    usedIds.add(drop.id)
                }
            }
        }
        return VerifyResult(found = found, merged = merged, pending = pending.size + added)
    }

    // ---------------------------------------------------------------- 兜底

    /**
     * 统一异常兜底：任何异常都只记日志并返回 [fallback]，绝不让导入 / 首页崩溃。
     * 协程取消（[CancellationException]）仍按协程规范向上抛，避免把取消当成业务失败。
     */
    private inline fun <T> guard(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "自动去重流程异常，已兜底", error)
            fallback
        }
}
