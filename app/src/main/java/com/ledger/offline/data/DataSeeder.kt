package com.ledger.offline.data

import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.SmsSource
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.domain.repo.AccountRepository
import com.ledger.offline.domain.repo.CategoryRepository
import com.ledger.offline.domain.repo.SmsSourceRepository
import kotlinx.coroutines.sync.withLock

/**
 * 首次启动 / 版本升级时写入默认账户、分类与银行短信来源。
 *
 * 账户种子 v2（1.1.0 起，以 [com.ledger.offline.domain.model.LedgerSettings.seedVersion] 判断）：
 * - 全空数据库（新安装）：写入 微信 / 支付宝 / 银行卡 三个账户，**不再创建现金账户**；
 * - 已有数据库（从 1.0 升级）：删除所有“现金”账户中**没有流水**的（有流水的保留，避免挂空流水），
 *   并把微信 / 支付宝的 sortOrder 规范为 0 / 1，其它账户按原顺序顺延为 2..n-1；
 * - 迁移成功后把 seedVersion 写为 2，因此重复调用是幂等的，不会重复插入或反复改写。
 *
 * 分类与银行短信来源沿用 1.0 的种子内容，与 seedVersion 无关：
 * 只要对应表为空就补种（防止旧版异常数据导致的空表）。
 */
object DataSeeder {

    /** 当前种子数据版本；与 [com.ledger.offline.domain.model.LedgerSettings.seedVersion] 对应。 */
    private const val CURRENT_SEED_VERSION = 2

    suspend fun ensureSeeded(repos: LedgerRepos) {
        val previousSeedVersion = repos.settings.current().seedVersion
        // 与账户 / 分类的 ensure 方法共用同一把写锁，避免与服务侧的并发写入交错。
        DbWriteMutex.mutex.withLock {
            if (previousSeedVersion < CURRENT_SEED_VERSION) {
                seedAccountsV2(repos.accounts)
            }
            // 分类 / 短信来源无论版本如何，空表都补种（幂等）。
            seedCategories(repos.categories)
            seedSmsSources(repos.smsSources)
        }
        // 数据库迁移提交后再写版本号；中途失败则下次启动重试（迁移本身幂等）。
        if (previousSeedVersion < CURRENT_SEED_VERSION) {
            repos.settings.update { settings ->
                settings.copy(seedVersion = CURRENT_SEED_VERSION)
            }
        }
    }

    /**
     * 账户种子 v2：全空库按新默认账户播种；已有数据走升级迁移（删除无流水的现金、规范排序）。
     */
    private suspend fun seedAccountsV2(repos: AccountRepository) {
        val existing = repos.getAll()
        if (existing.isEmpty()) {
            insertDefaultAccounts(repos)
            return
        }
        deleteEmptyCashAccounts(repos, existing)
        normalizeAccountSortOrders(repos)
    }

    /** 新安装默认账户：微信、支付宝、银行卡（余额未知 null），sortOrder = 0 / 1 / 2。 */
    private suspend fun insertDefaultAccounts(repos: AccountRepository) {
        ACCOUNT_SEEDS_V2.forEachIndexed { index, seed ->
            repos.insert(
                Account(
                    name = seed.name,
                    type = seed.type,
                    cardTail = "",
                    balanceCents = null,
                    balanceUpdatedAt = 0L,
                    sortOrder = index
                )
            )
        }
    }

    /** 升级迁移：只删除没有任何流水的现金账户；有流水的现金账户保留（用户已用它记过账）。 */
    private suspend fun deleteEmptyCashAccounts(
        repos: AccountRepository,
        accounts: List<Account>
    ) {
        for (account in accounts) {
            if (account.type != AccountType.CASH) {
                continue
            }
            if (repos.countTransactions(account.id) == 0) {
                repos.delete(account.id)
            }
        }
    }

    /**
     * 升级迁移：微信 / 支付宝排在最前（0 / 1），其它账户按原 sortOrder 顺延为 2..n-1。
     * 只有顺序确实变化时才写库，重复执行不会产生额外写入。
     */
    private suspend fun normalizeAccountSortOrders(repos: AccountRepository) {
        val accounts = repos.getAll()
        if (accounts.isEmpty()) {
            return
        }
        val wechat = accounts.firstOrNull { account -> account.type == AccountType.WECHAT }
        val alipay = accounts.firstOrNull { account -> account.type == AccountType.ALIPAY }
        val ordered = ArrayList<Account>(accounts.size)
        wechat?.let { account -> ordered.add(account) }
        alipay?.let { account -> ordered.add(account) }
        accounts
            .filter { account -> account.id != wechat?.id && account.id != alipay?.id }
            .sortedWith(compareBy<Account>({ account -> account.sortOrder }, { account -> account.id }))
            .forEach { account -> ordered.add(account) }

        ordered.forEachIndexed { index, account ->
            if (account.sortOrder != index) {
                repos.update(account.copy(sortOrder = index))
            }
        }
    }

    /** 默认分类：支出 10 个 + 收入 5 个，sortOrder 全局递增，全部标记为默认分类。 */
    private suspend fun seedCategories(repos: CategoryRepository) {
        if (repos.getAll().isNotEmpty()) {
            return
        }
        var sortOrder = 0
        for (seed in EXPENSE_CATEGORY_SEEDS) {
            repos.insert(
                Category(
                    name = seed.name,
                    type = TxType.EXPENSE,
                    icon = seed.icon,
                    keywords = seed.keywords,
                    sortOrder = sortOrder,
                    isDefault = true
                )
            )
            sortOrder++
        }
        for (seed in INCOME_CATEGORY_SEEDS) {
            repos.insert(
                Category(
                    name = seed.name,
                    type = TxType.INCOME,
                    icon = seed.icon,
                    keywords = seed.keywords,
                    sortOrder = sortOrder,
                    isDefault = true
                )
            )
            sortOrder++
        }
    }

    /**
     * 默认银行短信来源：默认全部关闭（enabled = false），
     * accountId = 0 表示导入时按银行名 / 尾号自动建卡。
     */
    private suspend fun seedSmsSources(repos: SmsSourceRepository) {
        if (repos.getAll().isNotEmpty()) {
            return
        }
        for (seed in SMS_SOURCE_SEEDS) {
            repos.insert(
                SmsSource(
                    label = seed.label,
                    addressPattern = seed.addressPattern,
                    bankName = seed.bankName,
                    cardTail = "",
                    accountId = 0L,
                    enabled = false
                )
            )
        }
    }
}

/** 账户种子。 */
private data class AccountSeed(
    val name: String,
    val type: AccountType
)

/** 分类种子：图标与自动归类关键词。 */
private data class CategorySeed(
    val name: String,
    val icon: String,
    val keywords: String
)

/** 短信来源种子：显示名 = 银行名，发送号码为银行客服号。 */
private data class SmsSourceSeed(
    val label: String,
    val addressPattern: String,
    val bankName: String
)

/** 账户种子（v2）：顺序即 sortOrder，微信 / 支付宝 / 银行卡，不再包含现金。 */
private val ACCOUNT_SEEDS_V2 = listOf(
    AccountSeed(name = "微信", type = AccountType.WECHAT),
    AccountSeed(name = "支付宝", type = AccountType.ALIPAY),
    AccountSeed(name = "银行卡", type = AccountType.BANK)
)

private val EXPENSE_CATEGORY_SEEDS = listOf(
    CategorySeed(
        name = "餐饮",
        icon = "🍜",
        keywords = "餐,饭,美食,美团,饿了么,肯德基,麦当劳,星巴克,咖啡,奶茶,烧烤,火锅,外卖"
    ),
    CategorySeed(
        name = "交通",
        icon = "🚕",
        keywords = "地铁,公交,滴滴,打车,加油,停车,高铁,火车,机票,出行"
    ),
    CategorySeed(
        name = "购物",
        icon = "🛍️",
        keywords = "淘宝,天猫,京东,拼多多,超市,商场,便利店,购物"
    ),
    CategorySeed(
        name = "居住",
        icon = "🏠",
        keywords = "房租,物业,水费,电费,燃气,宽带"
    ),
    CategorySeed(
        name = "通讯",
        icon = "📱",
        keywords = "话费,流量,中国移动,中国联通,中国电信"
    ),
    CategorySeed(
        name = "娱乐",
        icon = "🎮",
        keywords = "电影,游戏,视频,会员,KTV,演出,旅游"
    ),
    CategorySeed(
        name = "医疗",
        icon = "💊",
        keywords = "医院,药,诊所,体检,挂号"
    ),
    CategorySeed(
        name = "教育",
        icon = "📚",
        keywords = "学费,课程,培训,考试,书店"
    ),
    CategorySeed(
        name = "人情",
        icon = "🎁",
        keywords = "红包,礼金,转账,请客"
    ),
    CategorySeed(
        name = "其他支出",
        icon = "📦",
        keywords = ""
    )
)

private val INCOME_CATEGORY_SEEDS = listOf(
    CategorySeed(
        name = "工资",
        icon = "💰",
        keywords = "工资,薪资,薪酬,代发"
    ),
    CategorySeed(
        name = "奖金",
        icon = "🏆",
        keywords = "奖金,年终奖,绩效"
    ),
    CategorySeed(
        name = "红包",
        icon = "🧧",
        keywords = "红包,压岁钱"
    ),
    CategorySeed(
        name = "退款",
        icon = "↩️",
        keywords = "退款,退货,返现"
    ),
    CategorySeed(
        name = "其他收入",
        icon = "📥",
        keywords = ""
    )
)

private val SMS_SOURCE_SEEDS = listOf(
    SmsSourceSeed(label = "招商银行", addressPattern = "95555", bankName = "招商银行"),
    SmsSourceSeed(label = "工商银行", addressPattern = "95588", bankName = "工商银行"),
    SmsSourceSeed(label = "建设银行", addressPattern = "95533", bankName = "建设银行"),
    SmsSourceSeed(label = "农业银行", addressPattern = "95599", bankName = "农业银行"),
    SmsSourceSeed(label = "中国银行", addressPattern = "95566", bankName = "中国银行"),
    SmsSourceSeed(label = "交通银行", addressPattern = "95559", bankName = "交通银行")
)
