package com.ledger.offline.ingest

import android.util.Log
import com.ledger.offline.dedupe.DedupeService
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.SmsSource
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n
import com.ledger.offline.util.FilterPrefs
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** 单条短信 / 通知的导入结果。 */
internal enum class ImportOutcome {
    /** 成功入库。 */
    IMPORTED,

    /** 解析失败、被屏蔽词 / 类型过滤或重复（sourceRef 已存在），本次跳过。 */
    SKIPPED,

    /** 写库 / 查账户等过程抛异常。 */
    FAILED
}

/**
 * 短信与通知入库的公共逻辑：屏蔽词与通知类型过滤、基金申购 / 赎回、账户解析、余额刷新、
 * 自动归类、sourceRef 生成、去重统计，以及入库成功后的自动去重（[DedupeService.afterInsert]）。
 *
 * 扫描（[LedgerIngest]）、实时广播（[SmsReceiver]）、通知监听（[LedgerNotificationListener]）
 * 三处复用同一份入库逻辑，保证行为一致。本文件只依赖 domain 接口与 dedupe 服务，不直接碰 Room。
 *
 * 设置默认值（`fundTrackingEnabled = false`、`notifyKindFilters = ""`、`blockWords = ""`）下，
 * 这里的行为与 1.2.0 完全一致：不跳过任何记录，也不产生基金流水。
 */
internal object IngestWriter {

    private val whitespaceRegex = Regex("""\s+""")

    private const val TAG = "IngestWriter"

    private const val WECHAT_ACCOUNT_NAME = "微信"
    private const val ALIPAY_ACCOUNT_NAME = "支付宝"
    private const val APP_ACCOUNT_NAME = "应用通知"
    private const val WECHAT_DEFAULT_MERCHANT = "微信支付"
    private const val ALIPAY_DEFAULT_MERCHANT = "支付宝"

    /** 基金申购 / 赎回统一落在“基金余额”账户上（AccountType.FUND）。 */
    private const val FUND_ACCOUNT_NAME = "基金余额"
    private const val FUND_PURCHASE_MERCHANT = "基金申购"
    private const val FUND_REDEMPTION_MERCHANT = "基金赎回"

    /** 号码包含匹配：忽略大小写与空白（CONTRACT 3.2）。 */
    fun addressMatches(address: String, pattern: String): Boolean {
        val safePattern = normalizeAddress(pattern)
        if (safePattern.isEmpty()) return false
        return normalizeAddress(address).contains(safePattern)
    }

    /**
     * 多个来源命中同一号码时只取第一个，保证一条短信只处理一次。
     */
    fun findMatchingSource(sources: List<SmsSource>, address: String): SmsSource? =
        sources.firstOrNull { addressMatches(address, it.addressPattern) }

    /**
     * 解析并入库一条短信。
     *
     * v1.2.1 增加的过滤 / 基金分支（CONTRACT v4 §3.1、§3.3）：
     * 1. 设置的屏蔽词命中短信正文 → [ImportOutcome.SKIPPED]；
     * 2. 开启 `fundTrackingEnabled` 且是基金申购 / 赎回 → 调整“基金余额”、插入 TRANSFER 记录，
     *    不产生 EXPENSE / INCOME；
     * 3. 其它情况保持原逻辑：解析 → 解析账户 → 归类 → 插入 → 用短信余额刷新银行卡余额。
     *
     * 设置只读一次（[com.ledger.offline.domain.repo.SettingsRepository.current]）。
     *
     * @param autoCategorize 是否启用自动归类（关闭时直接落到默认分类）。
     */
    suspend fun importSmsMessage(
        repos: LedgerRepos,
        source: SmsSource,
        address: String,
        body: String,
        occurredAt: Long,
        autoCategorize: Boolean
    ): ImportOutcome {
        val settings = repos.settings.current()
        val blockWords = FilterPrefs.parseBlockWords(settings.blockWords)
        if (FilterPrefs.isBlocked(body, blockWords)) return ImportOutcome.SKIPPED

        val parsed = SmsParser.parse(body, address, occurredAt) ?: return ImportOutcome.SKIPPED

        // 基金申购 / 赎回只在用户显式开启时处理；默认关闭时完全走原逻辑。
        if (settings.fundTrackingEnabled && (parsed.isFundPurchase || parsed.isFundRedemption)) {
            return importFundMovement(repos, source, parsed, address, body)
        }

        val account = resolveSmsAccount(repos, source, parsed.cardTail)

        val category = resolveCategory(repos, body, parsed.type, autoCategorize)
        val merchant = parsed.merchant.ifBlank { source.bankName.ifBlank { source.label } }

        val transaction = Transaction(
            amountCents = parsed.amountCents,
            type = parsed.type,
            source = TxSource.SMS,
            categoryId = category.id,
            accountId = account.id,
            merchant = merchant,
            note = "",
            occurredAt = parsed.occurredAt,
            createdAt = System.currentTimeMillis(),
            balanceAfterCents = parsed.balanceCents,
            rawText = body,
            sourceRef = smsSourceRef(address, occurredAt, body)
        )
        val outcome = insertOutcome(repos, transaction)
        // 只有真正新增的流水才刷新余额，且只允许“更新的短信”覆盖余额：
        // 收件箱扫描按时间倒序，若不比较时间，最后写入的会是最旧一条短信的余额。
        if (outcome == ImportOutcome.IMPORTED) {
            refreshBankBalanceFromSms(repos, account, parsed)
        }
        return outcome
    }

    /**
     * 解析并入库一条支付通知（任意应用）。
     *
     * v1.2.1 增加的过滤（CONTRACT v4 §3.3）：
     * 1. 屏蔽词命中标题 + 正文 → [ImportOutcome.SKIPPED]；
     * 2. 解析出的 [ParsedNotification.kind] 不在该包允许的类型里 → [ImportOutcome.SKIPPED]；
     * 3. 其它保持原逻辑（任意包名解析、账户 / 分类 / sourceRef、插入后自动去重）。
     *
     * 设置只读一次。
     *
     * @param appLabel 通知所属应用的显示名；微信 / 支付宝不用它，
     *   其它应用用它（取不到时用包名）作为账户“应用通知”下流水的默认商户。
     */
    suspend fun importNotification(
        repos: LedgerRepos,
        packageName: String,
        title: String,
        text: String,
        postTime: Long,
        autoCategorize: Boolean,
        appLabel: String = ""
    ): ImportOutcome {
        val settings = repos.settings.current()
        val blockWords = FilterPrefs.parseBlockWords(settings.blockWords)
        val displayText = listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
        if (FilterPrefs.isBlocked(displayText, blockWords)) return ImportOutcome.SKIPPED

        val parsed = NotificationParser.parse(packageName, title, text, postTime)
            ?: return ImportOutcome.SKIPPED

        // 通知类型过滤：默认配置为空串时允许全部类型，行为与 1.2.0 一致。
        val allowedKinds = FilterPrefs.allowedKinds(settings.notifyKindFilters, packageName)
        if (parsed.kind !in allowedKinds) return ImportOutcome.SKIPPED

        val accountInfo = when (packageName) {
            WECHAT_PACKAGE -> NotificationAccount(
                name = WECHAT_ACCOUNT_NAME,
                type = AccountType.WECHAT,
                source = TxSource.WECHAT,
                defaultMerchant = WECHAT_DEFAULT_MERCHANT
            )
            ALIPAY_PACKAGE -> NotificationAccount(
                name = ALIPAY_ACCOUNT_NAME,
                type = AccountType.ALIPAY,
                source = TxSource.ALIPAY,
                defaultMerchant = ALIPAY_DEFAULT_MERCHANT
            )
            else -> NotificationAccount(
                name = APP_ACCOUNT_NAME,
                type = AccountType.APP,
                source = TxSource.APP,
                defaultMerchant = appLabel.trim().ifBlank { packageName }
            )
        }

        val account = repos.accounts.ensureAccount(accountInfo.name, accountInfo.type)
        val category = resolveCategory(
            repos = repos,
            text = listOf(title, text).filter { it.isNotBlank() }.joinToString(" "),
            type = parsed.type,
            autoCategorize = autoCategorize
        )

        val transaction = Transaction(
            amountCents = parsed.amountCents,
            type = parsed.type,
            source = accountInfo.source,
            categoryId = category.id,
            accountId = account.id,
            merchant = parsed.merchant.ifBlank { accountInfo.defaultMerchant },
            note = "",
            occurredAt = postTime,
            createdAt = System.currentTimeMillis(),
            balanceAfterCents = null,
            rawText = parsed.rawText,
            sourceRef = notificationSourceRef(packageName, postTime, text)
        )
        return insertOutcome(repos, transaction)
    }

    /**
     * 基金申购 / 赎回（仅短信、且用户开启 `fundTrackingEnabled`）。
     *
     * - 申购：基金余额 `+amountCents`，插入 TRANSFER（merchant=“基金申购”）；
     * - 赎回：基金余额 `-amountCents`，插入 TRANSFER（merchant=“基金赎回”）；
     * - 两种都不插入 EXPENSE / INCOME，因此不计入收支统计；
     * - 分类用默认支出分类（TRANSFER 只影响展示，不影响统计）；
     * - 短信里的银行卡余额仍然刷新。
     *
     * 先按 sourceRef 插入去重、成功后才调整基金余额：同一笔短信被重复扫描时不能重复加减余额。
     * 基金划转是账户内部转账，不调用 [DedupeService.afterInsert]（TRANSFER 不参与配对）。
     */
    private suspend fun importFundMovement(
        repos: LedgerRepos,
        source: SmsSource,
        parsed: ParsedSms,
        address: String,
        body: String
    ): ImportOutcome {
        val bankAccount = resolveSmsAccount(repos, source, parsed.cardTail)
        val fundAccount = repos.accounts.ensureAccount(FUND_ACCOUNT_NAME, AccountType.FUND)
        val category = repos.categories.defaultFor(TxType.EXPENSE)
        val isPurchase = parsed.isFundPurchase

        val transaction = Transaction(
            amountCents = parsed.amountCents,
            type = TxType.TRANSFER,
            source = TxSource.SMS,
            categoryId = category.id,
            accountId = fundAccount.id,
            merchant = I18n.t(if (isPurchase) FUND_PURCHASE_MERCHANT else FUND_REDEMPTION_MERCHANT),
            note = "",
            occurredAt = parsed.occurredAt,
            createdAt = System.currentTimeMillis(),
            balanceAfterCents = null,
            rawText = body,
            sourceRef = smsSourceRef(address, parsed.occurredAt, body)
        )
        if (repos.transactions.insert(transaction) == -1L) return ImportOutcome.SKIPPED

        val deltaCents = if (isPurchase) parsed.amountCents else -parsed.amountCents
        repos.accounts.adjustBalance(fundAccount.id, deltaCents, parsed.occurredAt)

        // 基金买卖不改变银行卡收支，但短信里的银行卡余额仍然是有效的最新余额。
        refreshBankBalanceFromSms(repos, bankAccount, parsed)
        return ImportOutcome.IMPORTED
    }

    /**
     * 用短信里的余额刷新银行卡余额（仅当短信带余额，且不早于账户已有的余额时间）。
     *
     * 只在流水真正新增后调用，避免重复导入同一笔短信把余额改回旧值。
     */
    private suspend fun refreshBankBalanceFromSms(
        repos: LedgerRepos,
        account: Account,
        parsed: ParsedSms
    ) {
        val balanceCents = parsed.balanceCents ?: return
        runCatching {
            // 银行短信余额是权威值：即使手动记账刚改过余额、短信时间戳更早，
            // 读取到短信后也用短信余额覆盖（需求：冲突时以读取短信为准）。
            repos.accounts.updateBalance(account.id, balanceCents, parsed.occurredAt)
        }
    }

    /** 契约规则：sourceRef = "sms:" + address.trim() + ":" + dateMillis + ":" + body.hashCode()。 */
    fun smsSourceRef(address: String, dateMillis: Long, body: String): String =
        "sms:" + address.trim() + ":" + dateMillis + ":" + body.hashCode()

    /** 契约规则：sourceRef = "notif:" + packageName + ":" + postTime + ":" + text.hashCode()。 */
    fun notificationSourceRef(packageName: String, postTime: Long, text: String): String =
        "notif:" + packageName + ":" + postTime + ":" + text.hashCode()

    /**
     * 短信来源对应的账户：
     * 1. source.accountId > 0，直接使用该账户；
     * 2. 否则按银行名 + 尾号自动建卡（accountId = 0 的语义）；
     * 3. accountId 指定的账户已被删除时，退化为自动建卡，避免整条短信丢失。
     */
    private suspend fun resolveSmsAccount(
        repos: LedgerRepos,
        source: SmsSource,
        parsedCardTail: String
    ): Account {
        if (source.accountId > 0L) {
            val existing = repos.accounts.getById(source.accountId)
            if (existing != null) return existing
        }
        val cardTail = parsedCardTail.ifBlank { source.cardTail }
        return repos.accounts.ensureBankAccount(source.bankName.ifBlank { source.label }, cardTail)
    }

    /** 自动归类开关打开时按关键词匹配，关闭或没命中时落到该类型的默认分类。 */
    private suspend fun resolveCategory(
        repos: LedgerRepos,
        text: String,
        type: TxType,
        autoCategorize: Boolean
    ): Category {
        val matched = if (autoCategorize) repos.categories.findMatching(text, type) else null
        return matched ?: repos.categories.defaultFor(type)
    }

    /** insert 返回 -1 表示 sourceRef 重复（CONTRACT 3.1），计为跳过；成功入库后触发自动去重。 */
    private suspend fun insertOutcome(repos: LedgerRepos, transaction: Transaction): ImportOutcome {
        val insertedId = repos.transactions.insert(transaction)
        if (insertedId == -1L) return ImportOutcome.SKIPPED
        notifyDedupe(repos, transaction.copy(id = insertedId))
        return ImportOutcome.IMPORTED
    }

    /**
     * 新流水入库成功后交给自动去重（CONTRACT v3 §3.4）。
     *
     * 去重失败只写日志：绝不影响本次导入的返回值与扫描统计。
     */
    private suspend fun notifyDedupe(repos: LedgerRepos, transaction: Transaction) {
        try {
            DedupeService.afterInsert(repos, transaction)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "自动去重失败", throwable)
        }
    }

    private fun normalizeAddress(value: String): String =
        value.replace(whitespaceRegex, "").lowercase(Locale.ROOT)

    private data class NotificationAccount(
        val name: String,
        val type: AccountType,
        val source: TxSource,
        val defaultMerchant: String
    )
}
