package com.ledger.offline.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledger.offline.dedupe.DedupeService
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.ManualBalanceSync
import com.ledger.offline.domain.model.Account
import com.ledger.offline.domain.model.AccountType
import com.ledger.offline.domain.model.Category
import com.ledger.offline.domain.model.Transaction
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n
import com.ledger.offline.util.DateTimes
import com.ledger.offline.util.Money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 记一笔 / 编辑流水。
 *
 * 新增保存成功后会把入库 id 交给 `DedupeService.afterInsert`，
 * 让手动记账也参与和短信 / 通知的疑似重复处理；编辑既有流水不重复触发。
 *
 * @param txId 大于 0 表示编辑既有流水；-1 表示新增。
 */
class EditTransactionViewModel(
    private val repos: LedgerRepos,
    private val txId: Long
) : ViewModel() {

    private val _amountText = MutableStateFlow("")
    val amountText: StateFlow<String> = _amountText.asStateFlow()

    private val _type = MutableStateFlow(TxType.EXPENSE)
    val type: StateFlow<TxType> = _type.asStateFlow()

    private val _categoryId = MutableStateFlow(0L)
    val categoryId: StateFlow<Long> = _categoryId.asStateFlow()

    private val _accountId = MutableStateFlow(0L)
    val accountId: StateFlow<Long> = _accountId.asStateFlow()

    private val _merchant = MutableStateFlow("")
    val merchant: StateFlow<String> = _merchant.asStateFlow()

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    private val _occurredAt = MutableStateFlow(System.currentTimeMillis())
    val occurredAt: StateFlow<Long> = _occurredAt.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _loading = MutableStateFlow(txId > 0L)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val error: SharedFlow<String> = _error

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved

    val categories: StateFlow<List<Category>> = repos.categories.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val accounts: StateFlow<List<Account>> = repos.accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private var existing: Transaction? = null

    val isEditing: Boolean get() = txId > 0L

    init {
        if (txId > 0L) {
            viewModelScope.launch {
                val tx = runCatching { repos.transactions.getById(txId) }.getOrNull()
                if (tx != null) {
                    existing = tx
                    _amountText.value = Money.editable(tx.amountCents)
                    _type.value = tx.type
                    _categoryId.value = tx.categoryId
                    _accountId.value = tx.accountId
                    _merchant.value = tx.merchant
                    _note.value = tx.note
                    _occurredAt.value = tx.occurredAt
                } else {
                    _error.tryEmit(I18n.t("没有找到这笔流水，可能已被删除"))
                }
                _loading.value = false
            }
        }
    }

    fun onAmountChange(value: String) {
        _amountText.value = value
    }

    fun onTypeChange(value: TxType) {
        _type.value = value
    }

    fun selectCategory(id: Long) {
        _categoryId.value = id
    }

    fun selectAccount(id: Long) {
        _accountId.value = id
    }

    fun onMerchantChange(value: String) {
        _merchant.value = value
    }

    fun onNoteChange(value: String) {
        _note.value = value
    }

    /** 只改日期，保留原来的时分。 */
    fun onDatePicked(year: Int, month: Int, dayOfMonth: Int) {
        val zone = DateTimes.zone()
        val oldTime: LocalTime = Instant.ofEpochMilli(_occurredAt.value).atZone(zone).toLocalTime()
        val newDateTime = LocalDate.of(year, month, dayOfMonth).atTime(oldTime).atZone(zone)
        _occurredAt.value = newDateTime.toInstant().toEpochMilli()
    }

    /** 校验金额后保存：新增走 Transaction.manual，编辑保留原 source / sourceRef。 */
    fun save() {
        if (_saving.value) return
        val cents = Money.parseToCents(_amountText.value)
        if (cents == null || cents <= 0L) {
            _error.tryEmit(I18n.t("请输入正确的金额，例如 12.50"))
            return
        }
        viewModelScope.launch {
            _saving.value = true
            try {
                val type = _type.value
                val categoryId = resolveCategoryId(type)
                val accountId = resolveAccountId()
                val base = existing
                if (base != null) {
                    val updated = base.copy(
                        amountCents = cents,
                        type = type,
                        categoryId = categoryId,
                        accountId = accountId,
                        merchant = _merchant.value.trim(),
                        note = _note.value.trim(),
                        occurredAt = _occurredAt.value
                    )
                    if (base.source == TxSource.MANUAL) {
                        // 手动流水编辑：先回退旧金额对余额的影响，再按新金额同步一次。
                        if (base.balanceApplied) {
                            ManualBalanceSync.revert(repos, base)
                        }
                        val applied = ManualBalanceSync.apply(repos, updated)
                        repos.transactions.update(updated.copy(balanceApplied = applied))
                    } else {
                        repos.transactions.update(updated)
                    }
                } else {
                    val manual = Transaction.manual(
                        amountCents = cents,
                        type = type,
                        categoryId = categoryId,
                        accountId = accountId,
                        merchant = _merchant.value.trim(),
                        note = _note.value.trim(),
                        occurredAt = _occurredAt.value
                    )
                    val newId = repos.transactions.insert(manual)
                    if (newId > 0L) {
                        // 手动记账直接增减对应账户余额；余额同步失败只记日志，不影响保存。
                        // 银行短信余额始终是权威值，后续读取短信会覆盖这里的结果。
                        var saved = manual.copy(id = newId)
                        if (ManualBalanceSync.apply(repos, saved)) {
                            saved = saved.copy(balanceApplied = true)
                            runCatching { repos.transactions.update(saved) }
                                .onFailure { Log.w(TAG, "写回手动记账余额同步标记失败", it) }
                        }
                        // 手动记账也要参与去重（可能和同时到达的短信 / 通知重复）。
                        // 去重失败只记日志，绝不能影响保存结果与返回导航。
                        try {
                            DedupeService.afterInsert(repos, saved)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w(TAG, "手动记账去重失败，已忽略", e)
                        }
                    }
                }
                _saving.value = false
                _saved.emit(Unit)
            } catch (cancelled: CancellationException) {
                _saving.value = false
                throw cancelled
            } catch (e: Exception) {
                _saving.value = false
                _error.emit(I18n.t("保存失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    /** 分类兜底：当前选择无效时取该类型第一个分类，再不行取仓库默认分类。 */
    private suspend fun resolveCategoryId(type: TxType): Long {
        val current = categories.value.filter { it.type == type }
        val selected = _categoryId.value
        if (current.any { it.id == selected }) return selected
        current.firstOrNull()?.let { return it.id }
        return runCatching { repos.categories.defaultFor(type).id }.getOrDefault(0L)
    }

    /** 账户兜底：没有任何账户时自动创建一个“现金”账户，保证记账不会失败。 */
    private suspend fun resolveAccountId(): Long {
        val current = accounts.value
        val selected = _accountId.value
        if (current.any { it.id == selected }) return selected
        current.firstOrNull()?.let { return it.id }
        return runCatching { repos.accounts.ensureAccount("现金", AccountType.CASH).id }.getOrDefault(0L)
    }

    companion object {
        private const val TAG = "EditTransactionVM"

        fun factory(repos: LedgerRepos, txId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditTransactionViewModel(repos, txId) }
        }
    }
}
