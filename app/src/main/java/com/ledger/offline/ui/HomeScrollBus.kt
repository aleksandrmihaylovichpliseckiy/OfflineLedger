package com.ledger.offline.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 底部导航“首页”点击事件：无论当前在哪个页面，点击首页都要回到首页顶部。
 *
 * 用 StateFlow<Int> 计数而不是 SharedFlow，保证“在别的页面点击首页”时事件不会丢失：
 * HomeScreen 重新进入组合后会读到最新计数并滚动到顶部。
 */
object HomeScrollBus {

    private val _requestId = MutableStateFlow(0)
    val requestId: StateFlow<Int> = _requestId

    fun requestTop() {
        _requestId.value = _requestId.value + 1
    }
}
