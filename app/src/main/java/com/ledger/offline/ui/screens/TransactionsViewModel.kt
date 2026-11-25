package com.ledger.offline.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.ManualBalanceSync
import com.ledger.offline.domain.model.TransactionRecord
import com.ledger.offline.domain.model.TxSource
import com.ledger.offline.domain.model.TxType
import com.ledger.offline.i18n.I18n
import com.ledger.offline.util.DateTimes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 明细页的时间范围筛选。 */
enum class RangeFilter(val label: String) {
    TODAY("今天"),
    LAST_7_DAYS("近7天"),
    THIS_MONTH("本月"),
    ALL("全部")
}

/**
 * 明细页：全量流水 + 类型 / 来源 / 时间三重筛选。
 */
class TransactionsViewModel(private val repos: LedgerRepos) : ViewModel() {

    private val _typeFilter = MutableStateFlow<TxType?>(null)
    val typeFilter: StateFlow<TxType?> = _typeFilter

    private val _sourceFilter = MutableStateFlow<TxSource?>(null)
    val sourceFilter: StateFlow<TxSource?> = _sourceFilter

    private val _rangeFilter = MutableStateFlow(RangeFilter.THIS_MONTH)
    val rangeFilter: StateFlow<RangeFilter> = _rangeFilter

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val records: StateFlow<List<TransactionRecord>> = combine(
        repos.transactions.observeAll(),
        _typeFilter,
        _sourceFilter,
        _rangeFilter
    ) { all, type, source, range ->
        val timeRange = when (range) {
            RangeFilter.TODAY -> DateTimes.todayRange()
            RangeFilter.LAST_7_DAYS -> DateTimes.lastDaysRange(7)
            RangeFilter.THIS_MONTH -> DateTimes.thisMonthRange()
            RangeFilter.ALL -> null
        }
        all.filter { record ->
            val tx = record.tx
            val typeOk = type == null || tx.type == type
            val sourceOk = source == null || tx.source == source
            val timeOk = timeRange == null ||
                (tx.occurredAt >= timeRange.startMillis && tx.occurredAt < timeRange.endMillisExclusive)
            typeOk && sourceOk && timeOk
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun setTypeFilter(type: TxType?) {
        _typeFilter.value = type
    }

    fun setSourceFilter(source: TxSource?) {
        _sourceFilter.value = source
    }

    fun setRangeFilter(range: RangeFilter) {
        _rangeFilter.value = range
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            try {
                // 手动记账已经同步过余额时，删除流水要把余额加回去。
                val tx = runCatching { repos.transactions.getById(id) }.getOrNull()
                if (tx != null) {
                    ManualBalanceSync.revert(repos, tx)
                }
                repos.transactions.delete(id)
                _messages.emit(I18n.t("已删除这笔流水"))
            } catch (e: Exception) {
                _messages.emit(I18n.t("删除失败：%1\$s", e.message ?: I18n.t("未知错误")))
            }
        }
    }

    companion object {
        fun factory(repos: LedgerRepos): ViewModelProvider.Factory = viewModelFactory {
            initializer { TransactionsViewModel(repos) }
        }
    }
}
