package com.ledger.offline.ax

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 信任模式专用的无障碍服务。
 *
 * 职责边界：
 * 1. 只处理微信 `com.tencent.mm` 与支付宝 `com.eg.android.AlipayGphone`（Manifest 已按包名过滤）；
 * 2. 对外提供“按文本点击”与“读取当前窗口全部文本”两个能力；
 * 3. 不做解析、不写数据库、不统计，页面文本统一交给 [AutoUpdateController] 处理；
 * 4. 点击带重试、延时与超时，全部回调切回主线程。
 *
 * 单例通过 [instance] / [connected] 暴露给同进程的 Controller；
 * 服务销毁或被打断时清理所有 Handler 回调并通知 Controller 终止状态机。
 */
class AutoUpdateAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 点击序列令牌：新的一次点击会让旧的延时任务失效，避免重复回调。 */
    private var clickToken = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        Log.i(TAG, "无障碍服务已连接")
        AutoUpdateController.onServiceStateChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        // 只处理微信 / 支付宝；事件可能非常频繁，这里刻意不做解析、不读节点，
        // 页面文本由 Controller 在需要时主动调用 readVisibleTexts() 拉取。
        if (AutoUpdateTarget.fromPackage(packageName) == null) return
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被系统中断")
        clickToken++
        mainHandler.removeCallbacksAndMessages(null)
        AutoUpdateController.onAccessibilityInterrupted()
    }

    override fun onDestroy() {
        Log.i(TAG, "无障碍服务已销毁")
        clickToken++
        mainHandler.removeCallbacksAndMessages(null)
        instance = null
        connected = false
        AutoUpdateController.onServiceStateChanged(false)
        super.onDestroy()
    }

    /** 当前激活窗口的包名；不是微信 / 支付宝时返回 null 由 Controller 判断。 */
    fun currentPackageName(): String? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        return try {
            root.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "读取当前窗口包名失败", e)
            null
        } finally {
            recycleNode(root)
        }
    }

    /**
     * 读取当前窗口全部可见文本节点。
     *
     * 同时收集 `text` 与 `contentDescription`，按页面顺序（深度优先）返回；
     * 相邻重复文本只保留一条，避免父子节点重复输出同一行。
     */
    fun readVisibleTexts(): List<String> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
        // 只读取微信 / 支付宝窗口；系统弹窗、通知栏等一律忽略。
        if (!isTargetRoot(root)) {
            recycleNode(root)
            return emptyList()
        }
        val visibleTexts = ArrayList<String>()
        val allTexts = ArrayList<String>()
        try {
            walk(root) { node ->
                val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
                val target = if (visible) visibleTexts else allTexts
                appendText(target, node.text)
                appendText(target, node.contentDescription)
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取当前窗口文本失败", e)
        } finally {
            recycleNode(root)
        }
        // 个别页面所有节点都标记为不可见时，退回未过滤文本，保证仍能读取内容。
        return if (visibleTexts.isNotEmpty()) visibleTexts else allTexts
    }

    /**
     * 按文本列表点击：在当前窗口里依次匹配 text / contentDescription。
     *
     * @return 是否点击成功。仅尝试一次，供 Controller 自己控制重试节奏；
     *         需要内置重试时用 [navigateByTextsWithRetry]。
     */
    fun navigateByTexts(vararg labels: String): Boolean {
        val wanted = normalizeLabels(labels.toList())
        if (wanted.isEmpty()) return false
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        if (!isTargetRoot(root)) {
            recycleNode(root)
            return false
        }
        return try {
            clickPass(root, wanted, exactOnly = true) || clickPass(root, wanted, exactOnly = false)
        } catch (e: Exception) {
            Log.w(TAG, "按文本点击失败", e)
            false
        } finally {
            recycleNode(root)
        }
    }

    /**
     * 带重试 / 延时 / 超时的按文本点击。
     *
     * 每 [RETRY_DELAY_MS] 毫秒重试一次，直到 [timeoutMillis] 超时；
     * 回调一定在主线程触发，且只会触发一次。
     *
     * @param labels 文本列表，任意一个命中即可点击（按传入顺序优先）。
     * @param timeoutMillis 单次点击总超时，默认 2.5 秒。
     * @param onResult true 表示已成功派发 ACTION_CLICK。
     */
    fun navigateByTextsWithRetry(
        labels: List<String>,
        timeoutMillis: Long = DEFAULT_CLICK_TIMEOUT_MS,
        onResult: (Boolean) -> Unit
    ) {
        val wanted = normalizeLabels(labels)
        if (wanted.isEmpty()) {
            onResult(false)
            return
        }
        clickToken++
        val token = clickToken
        val deadline = SystemClock.uptimeMillis() + timeoutMillis.coerceAtLeast(RETRY_DELAY_MS)
        val attempt = object : Runnable {
            override fun run() {
                if (token != clickToken || !connected) {
                    onResult(false)
                    return
                }
                if (navigateByTexts(*wanted.toTypedArray())) {
                    onResult(true)
                    return
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    onResult(false)
                    return
                }
                mainHandler.postDelayed(this, RETRY_DELAY_MS)
            }
        }
        mainHandler.post(attempt)
    }

    /**
     * 取消尚未执行完的点击重试 / 延时任务。
     * Controller 在任务中止或结束时调用，避免自动更新停了以后还继续点页面。
     */
    fun cancelPendingNavigation() {
        clickToken++
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ 节点遍历

    /** 文本追加：去空白、相邻去重。 */
    private fun appendText(out: MutableList<String>, raw: CharSequence?) {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (out.isNotEmpty() && out.last() == text) return
        out.add(text)
    }

    /**
     * 深度优先遍历节点树（先序遍历，尽量贴近页面从上到下的阅读顺序）。
     * 遍历结束后统一回收本次持有的子节点，根节点由调用方回收。
     */
    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        val owned = ArrayList<AccessibilityNodeInfo>()
        stack.addLast(root)
        try {
            var visited = 0
            while (stack.isNotEmpty() && visited < MAX_TREE_NODES) {
                val node = stack.removeLast()
                if (node !== root) owned.add(node)
                visited++
                if (visit(node)) return
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                // 逆序压栈，保证弹出时仍是从第一个子节点开始。
                for (index in childCount - 1 downTo 0) {
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    stack.addLast(child)
                }
            }
        } finally {
            while (stack.isNotEmpty()) {
                val leftover = stack.removeLast()
                if (leftover !== root) owned.add(leftover)
            }
            owned.forEach { recycleNode(it) }
        }
    }

    private fun clickPass(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        exactOnly: Boolean
    ): Boolean {
        var clicked = false
        walk(root) { node ->
            if (!matches(node, labels, exactOnly)) {
                false
            } else if (clickSelfOrAncestor(node)) {
                clicked = true
                true
            } else {
                // 命中但不可点击时继续扫描，后面可能有真正可点击的同类节点。
                false
            }
        }
        return clicked
    }

    /** text / contentDescription 精确匹配；非精确模式额外容忍纯数字 / 括号角标（例如“账单(5)”）。 */
    private fun matches(node: AccessibilityNodeInfo, labels: List<String>, exactOnly: Boolean): Boolean {
        val text = nodeText(node)
        val description = nodeDescription(node)
        for (label in labels) {
            if (text == label || description == label) return true
            if (!exactOnly) {
                if (isSoftMatch(text, label) || isSoftMatch(description, label)) return true
            }
        }
        return false
    }

    /**
     * 只容忍“账单(5)”这类角标后缀；中文后缀（如“我的账单”“账单详情”）一律不算，
     * 避免把页面标题 / 菜单项当成入口按钮点错。
     */
    private fun isSoftMatch(candidate: String, label: String): Boolean {
        if (!candidate.startsWith(label)) return false
        if (candidate.length > label.length + 4) return false
        val suffix = candidate.substring(label.length)
        // 后缀里出现任何文字（中文 / 英文）都不接受；只允许数字、括号、空格等角标字符。
        if (suffix.any { it.isLetter() }) return false
        return true
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")

    private fun nodeDescription(node: AccessibilityNodeInfo): String =
        runCatching { node.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")

    /**
     * 点击节点本身；不可点击时最多向上找 5 层可点击容器。
     * 微信 / 支付宝很多列表项的可点击节点是文本的父容器。
     */
    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= MAX_ANCESTOR_DEPTH) {
            val target = current
            val enabled = runCatching { target.isEnabled }.getOrDefault(false)
            val clickable = runCatching { target.isClickable }.getOrDefault(false)
            if (enabled && clickable) {
                val success = runCatching {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (success) return true
            }
            current = runCatching { target.parent }.getOrNull()
            depth++
        }
        return false
    }

    private fun normalizeLabels(labels: List<String>): List<String> =
        labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 当前窗口根节点是否属于微信 / 支付宝。 */
    private fun isTargetRoot(root: AccessibilityNodeInfo): Boolean {
        val packageName = runCatching { root.packageName?.toString() }.getOrNull() ?: return false
        return AutoUpdateTarget.fromPackage(packageName) != null
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }

    companion object {
        private const val TAG = "AutoUpdate"
        private const val MAX_TREE_NODES = 3000
        private const val MAX_ANCESTOR_DEPTH = 5
        private const val RETRY_DELAY_MS = 450L
        private const val DEFAULT_CLICK_TIMEOUT_MS = 2_500L

        /** 当前服务实例；未连接时为 null。 */
        @Volatile
        var instance: AutoUpdateAccessibilityService? = null
            private set

        /** 服务是否已连接并可以读取窗口内容。 */
        @Volatile
        var connected: Boolean = false
            private set
    }
}
