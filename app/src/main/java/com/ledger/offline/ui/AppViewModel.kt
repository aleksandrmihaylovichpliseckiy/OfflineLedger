package com.ledger.offline.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.domain.model.LedgerSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 根级 ViewModel：把应用设置暴露给 [LedgerApp]，用于主题、语言、密度、隐私模式、
 * 底部导航顺序与首次启动更新日志。
 *
 * 初值为 null：DataStore 首次读取完成前不要弹更新日志，避免老用户误触发。
 */
class AppViewModel(private val repos: LedgerRepos) : ViewModel() {

    val settings: StateFlow<LedgerSettings?> = repos.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** 用户读完更新日志后记录版本号，之后不再弹窗。 */
    fun markChangelogSeen(versionCode: Int) {
        viewModelScope.launch {
            runCatching {
                repos.settings.update { it.copy(lastSeenVersion = versionCode) }
            }
        }
    }

    companion object {
        fun factory(repos: LedgerRepos): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(repos) }
        }
    }
}
