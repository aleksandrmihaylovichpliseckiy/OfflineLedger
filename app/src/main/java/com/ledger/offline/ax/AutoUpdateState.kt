package com.ledger.offline.ax

import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.TxSource

/**
 * 自动更新的目标应用。
 *
 * 只支持微信 / 支付宝两个包：账户、来源、默认商户都集中在这里，
 * Controller 与入库逻辑不再散落 if/else，避免以后新增平台时到处改动。
 *
 * @param packageName 目标应用包名（无障碍配置里也只放行这两个包）。
 * @param displayName 用户可见名称，用于拼接提示文案。
 * @param accountName 自动创建 / 匹配的账户名。
 * @param accountType 账户类型。
 * @param source 写入流水时使用的来源。
 * @param defaultMerchant 页面上取不到商户时的兜底名称。
 */
enum class AutoUpdateTarget(
    val packageName: String,
    val displayName: String,
    val accountName: String,
    val accountType: AccountType,
    val source: TxSource,
    val defaultMerchant: String
) {
    WECHAT(
        packageName = "com.tencent.mm",
        displayName = "微信",
        accountName = "微信",
        accountType = AccountType.WECHAT,
        source = TxSource.WECHAT,
        defaultMerchant = "微信支付"
    ),
    ALIPAY(
        packageName = "com.eg.android.AlipayGphone",
        displayName = "支付宝",
        accountName = "支付宝",
        accountType = AccountType.ALIPAY,
        source = TxSource.ALIPAY,
        defaultMerchant = "支付宝"
    );

    companion object {
        /** 包名转目标应用；不是微信 / 支付宝时返回 null。 */
        fun fromPackage(packageName: String?): AutoUpdateTarget? =
            values().firstOrNull { it.packageName == packageName }
    }
}

/**
 * 自动更新状态机对外暴露的状态。
 *
 * 首页只订阅 [AutoUpdateController.state]：
 * - [Running] 用于按钮禁用与进度提示；
 * - [Success] / [Error] 通过 Snackbar 展示一次；
 * - [Idle] 不提示。
 */
sealed class AutoUpdateState {

    /** 空闲：没有任务在执行。 */
    object Idle : AutoUpdateState()

    /** 执行中，message 是当前步骤的中文提示。 */
    data class Running(val message: String) : AutoUpdateState()

    /** 成功完成，message 是本次结果（导入笔数 / 余额更新等）。 */
    data class Success(val message: String) : AutoUpdateState()

    /** 失败，message 会提示用户手动打开账单页后点“读取当前页”。 */
    data class Error(val message: String) : AutoUpdateState()
}
