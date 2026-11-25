package com.ledger.offline.ax

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.ledger.offline.domain.LedgerRepos
import com.ledger.offline.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动更新总控。
 *
 * 流程（微信）：拉起微信 → 我 → 服务 → 钱包（读零钱余额）→ 账单（读流水）→ 入库。
 * 流程（支付宝）：拉起支付宝 → 我的（读余额）→ 账单（读流水）→ 入库。
 *
 * 约束：
 * - 只在用户开启 `trustMode` 且无障碍服务已授权 / 已连接时工作；
 * - 每一步点击失败重试 2 次，单步点击内部还有 2.5 秒的轮询重试；
 * - 进入钱包 / 账单页后先等待设置里的读取延迟（`autoUpdateDelaySeconds`，默认 5 秒，收敛到
 *   1..30 秒）再读屏，手动“读取当前页”同样先等待；
 * - 整个状态机总超时 90 秒，超时或失败时尽量读取当前页兜底，并提示手动打开账单页；
 * - 所有用户可见文案走 [I18n.t]，页面内容只在内存处理，不写日志、不上传。
 */
object AutoUpdateController {

    private const val TAG = "AutoUpdate"

    /** 整个导航状态机的总预算。 */
    private const val TOTAL_TIMEOUT_MS = 90_000L

    /** 拉起微信 / 支付宝后等待首页渲染的时间。 */
    private const val LAUNCH_WAIT_MS = 1_200L

    /** 单步点击内部轮询超时。 */
    private const val CLICK_TIMEOUT_MS = 2_500L

    /** 点击成功后等待页面切换的时间（每个步骤单独覆盖）。 */
    private const val DEFAULT_STEP_WAIT_MS = 800L

    /** 单个步骤失败后的整体重试次数（契约要求 2 次）。 */
    private const val MAX_STEP_RETRIES = 2

    /** 读取失败（页面未加载完）后的重试间隔。 */
    private const val READ_RETRY_DELAY_MS = 800L

    /** 余额区域的二次读取间隔：钱包页可能比标题晚一点渲染。 */
    private const val BALANCE_RETRY_DELAY_MS = 500L

    /** 手动“读取当前页”的超时，避免协程异常时一直停在 Running。 */
    private const val MANUAL_TIMEOUT_MS = 15_000L

    /** 读取屏幕前的默认等待秒数：设置读不到或非法时使用。 */
    private const val DEFAULT_READ_DELAY_SECONDS = 5

    /** 读取延迟允许区间（秒），与设置项约束一致。 */
    private const val MIN_READ_DELAY_SECONDS = 1
    private const val MAX_READ_DELAY_SECONDS = 30

    private val _state = MutableStateFlow<AutoUpdateState>(AutoUpdateState.Idle)

    /** 供首页 Snackbar / 按钮状态订阅。 */
    val state: StateFlow<AutoUpdateState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 防止连续点击在协程启动前重复创建任务。 */
    private var starting = false

    /** 当前任务；同一时间只允许一个自动更新 / 手动读取。 */
    private var session: Session? = null

    /** 状态机步骤类型。 */
    private enum class Phase {
        CLICK,
        READ_BALANCE,
        READ_BILL
    }

    private data class Step(
        val phase: Phase,
        val labels: List<String> = emptyList(),
        val waitMillis: Long = DEFAULT_STEP_WAIT_MS
    )

    private class Session(
        val target: AutoUpdateTarget,
        val repos: LedgerRepos,
        val startedAtElapsed: Long,
        /** 进入钱包 / 账单页后、读屏前的等待时长（来自设置，已收敛到 1..30 秒）。 */
        val readDelayMillis: Long
    ) {
        var stepIndex: Int = 0
        var stepRetries: Int = 0

        /** 账单页的读取延迟只等一次，后续重试沿用原有短间隔。 */
        var billDelayApplied: Boolean = false

        /** 已停止推进步骤（失败 / 超时），但兜底读取可能仍在进行。 */
        var stopped: Boolean = false

        /** 最终状态已交付，防止重复回调。 */
        var delivered: Boolean = false

        var timeoutRunnable: Runnable? = null
        var workJob: Job? = null
    }

    // ------------------------------------------------------------------ 对外 API

    /** 无障碍服务是否已在系统设置里勾选（只检查安全设置，不要求当前已连接）。 */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AutoUpdateAccessibilityService::class.java)
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        if (enabled.isBlank()) return false
        return enabled.split(':').any { flattened ->
            runCatching { ComponentName.unflattenFromString(flattened) }
                .getOrNull() == expected
        }
    }

    /** 打开系统无障碍设置页；失败时退回系统设置首页。 */
    fun openAccessibilitySettings(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { appContext.startActivity(intent) }.isSuccess
        if (opened) return
        Log.w(TAG, "打开无障碍设置失败，退回系统设置首页")
        val fallback = Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(fallback) }
            .onFailure { Log.e(TAG, "打开系统设置失败", it) }
    }

    /**
     * 启动自动更新。
     *
     * @param target 目标应用，默认微信。
     */
    fun start(
        context: Context,
        repos: LedgerRepos,
        target: AutoUpdateTarget = AutoUpdateTarget.WECHAT
    ) {
        if (starting || session != null) {
            Log.d(TAG, "已有自动更新任务在执行，忽略本次启动")
            return
        }
        starting = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                val settings = runCatching { repos.settings.current() }.getOrNull()
                if (settings == null) {
                    _state.value = AutoUpdateState.Error(I18n.t("读取设置失败，请稍后重试"))
                    return@launch
                }
                if (!settings.trustMode) {
                    _state.value = AutoUpdateState.Error(I18n.t("请先在设置中开启“信任模式”"))
                    return@launch
                }
                if (!isServiceEnabled(appContext) ||
                    !AutoUpdateAccessibilityService.connected ||
                    AutoUpdateAccessibilityService.instance == null
                ) {
                    _state.value = AutoUpdateState.Error(
                        I18n.t("无障碍服务未开启，请先在系统设置中授权“自动记账”")
                    )
                    return@launch
                }

                val launchIntent = appContext.packageManager
                    .getLaunchIntentForPackage(target.packageName)
                if (launchIntent == null) {
                    _state.value = AutoUpdateState.Error(I18n.t("未安装%1\$s", target.displayName))
                    return@launch
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val launched = runCatching { appContext.startActivity(launchIntent) }.isSuccess
                if (!launched) {
                    _state.value = AutoUpdateState.Error(I18n.t("无法打开%1\$s", target.displayName))
                    return@launch
                }

                val current = Session(
                    target = target,
                    repos = repos,
                    startedAtElapsed = SystemClock.elapsedRealtime(),
                    readDelayMillis = readDelayMillisOf(settings.autoUpdateDelaySeconds)
                )
                session = current
                current.timeoutRunnable = Runnable {
                    abort(current, I18n.t("自动更新超时，请稍后重试"))
                }
                mainHandler.postDelayed(current.timeoutRunnable!!, TOTAL_TIMEOUT_MS)
                _state.value = AutoUpdateState.Running(
                    I18n.t("正在打开%1\$s…", target.displayName)
                )
                mainHandler.postDelayed({ runCurrentStep(current) }, LAUNCH_WAIT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "启动自动更新失败", e)
                _state.value = AutoUpdateState.Error(
                    I18n.t("自动更新启动失败：%1\$s", e.message ?: I18n.t("未知错误"))
                )
            } finally {
                starting = false
            }
        }
    }

    /**
     * 手动兜底：只解析当前已打开的微信 / 支付宝页面，不拉起应用、不自动跳转。
     *
     * 和自动流程一样，进入页面后先等待设置里的读取延迟，再读屏解析。
     * 只有页面像账单页（或至少能读到余额）时才写库，避免把聊天页里的金额误记成流水。
     */
    fun readCurrentPage(context: Context, repos: LedgerRepos) {
        if (starting || session != null) {
            Log.d(TAG, "当前有任务在执行，忽略读取当前页")
            return
        }
        val service = AutoUpdateAccessibilityService.instance
        if (service == null || !AutoUpdateAccessibilityService.connected) {
            _state.value = AutoUpdateState.Error(
                I18n.t("无障碍服务未开启，请先在系统设置中授权“自动记账”")
            )
            return
        }
        if (!isServiceEnabled(context)) {
            _state.value = AutoUpdateState.Error(
                I18n.t("无障碍服务未开启，请先在系统设置中授权“自动记账”")
            )
            return
        }
        val target = AutoUpdateTarget.fromPackage(service.currentPackageName())
        if (target == null) {
            _state.value = AutoUpdateState.Error(I18n.t("请先打开微信或支付宝账单页"))
            return
        }

        // 延迟要等读完设置才知道，先占住 starting，避免期间被自动更新抢走。
        starting = true
        _state.value = AutoUpdateState.Running(I18n.t("正在读取当前页面…"))
        scope.launch {
            var active: Session? = null
            try {
                val readDelayMillis = resolveReadDelayMillis(repos)
                val current = Session(
                    target = target,
                    repos = repos,
                    startedAtElapsed = SystemClock.elapsedRealtime(),
                    readDelayMillis = readDelayMillis
                )
                active = current
                session = current
                current.timeoutRunnable = Runnable {
                    deliver(current, AutoUpdateState.Error(manualHint(target)))
                }
                // 解析预算仍是 15 秒，只是把“进入页面后的等待”前置到预算之外。
                mainHandler.postDelayed(
                    current.timeoutRunnable!!,
                    MANUAL_TIMEOUT_MS + readDelayMillis
                )
                _state.value = AutoUpdateState.Running(waitHint(readDelayMillis))
                Log.d(TAG, "手动读取：等待 ${readDelayMillis / 1_000L} 秒后读屏")
                mainHandler.postDelayed(
                    { readManualPage(current, service) },
                    readDelayMillis
                )
            } catch (e: Exception) {
                Log.e(TAG, "读取当前页失败", e)
                val created = active
                if (created != null) {
                    deliver(created, AutoUpdateState.Error(manualHint(target)))
                } else {
                    _state.value = AutoUpdateState.Error(manualHint(target))
                }
            } finally {
                starting = false
            }
        }
    }

    /** 手动读取：等待延迟后读屏，再解析 / 写库（逻辑与原来的 readCurrentPage 一致）。 */
    private fun readManualPage(
        current: Session,
        service: AutoUpdateAccessibilityService
    ) {
        if (!sessionStillActive(current)) return
        // 等待期间服务可能被系统重启 / 用户关闭，读屏前再确认一次。
        if (AutoUpdateAccessibilityService.instance !== service ||
            !AutoUpdateAccessibilityService.connected
        ) {
            deliver(current, AutoUpdateState.Error(I18n.t("无障碍服务已断开")))
            return
        }
        _state.value = AutoUpdateState.Running(I18n.t("正在读取当前页面…"))
        val lines = runCatching { service.readVisibleTexts() }.getOrDefault(emptyList())
        current.workJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    AutoUpdatePageParser.parse(current.target, lines, System.currentTimeMillis())
                }
                if (!isCurrent(current)) return@launch
                if (!page.looksLikeBillPage && page.balanceCents == null) {
                    deliver(current, AutoUpdateState.Error(manualHint(current.target)))
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    AutoUpdateWriter.apply(
                        repos = current.repos,
                        target = current.target,
                        page = page,
                        insertRows = page.looksLikeBillPage,
                        now = System.currentTimeMillis()
                    )
                }
                deliver(current, successState(current.target, result))
            } catch (e: Exception) {
                Log.e(TAG, "读取当前页失败", e)
                deliver(current, AutoUpdateState.Error(manualHint(current.target)))
            }
        }
    }

    // ------------------------------------------------------------------ 无障碍服务回调

    /** 无障碍服务连接 / 断开；断开时终止正在执行的任务，避免状态机卡住。 */
    internal fun onServiceStateChanged(connected: Boolean) {
        if (connected) return
        val current = session ?: return
        abort(current, I18n.t("无障碍服务已断开"))
    }

    /** 无障碍服务被系统打断（例如用户切换到别的服务）。 */
    internal fun onAccessibilityInterrupted() {
        val current = session ?: return
        abort(current, I18n.t("无障碍服务被中断，请重试"))
    }

    // ------------------------------------------------------------------ 状态机

    private fun stepsFor(target: AutoUpdateTarget): List<Step> = when (target) {
        AutoUpdateTarget.WECHAT -> listOf(
            Step(Phase.CLICK, labels = listOf("我"), waitMillis = 900L),
            Step(Phase.CLICK, labels = listOf("服务"), waitMillis = 900L),
            Step(Phase.CLICK, labels = listOf("钱包"), waitMillis = 1_100L),
            Step(Phase.READ_BALANCE, waitMillis = 400L),
            Step(Phase.CLICK, labels = listOf("账单"), waitMillis = 1_300L),
            Step(Phase.READ_BILL)
        )
        AutoUpdateTarget.ALIPAY -> listOf(
            Step(Phase.CLICK, labels = listOf("我的"), waitMillis = 900L),
            Step(Phase.READ_BALANCE, waitMillis = 400L),
            Step(Phase.CLICK, labels = listOf("账单"), waitMillis = 1_300L),
            Step(Phase.READ_BILL)
        )
    }

    private fun runCurrentStep(current: Session) {
        if (!sessionStillActive(current)) return
        if (SystemClock.elapsedRealtime() - current.startedAtElapsed > TOTAL_TIMEOUT_MS) {
            abort(current, I18n.t("自动更新超时，请稍后重试"))
            return
        }
        val steps = stepsFor(current.target)
        if (current.stepIndex >= steps.size) {
            deliver(
                current,
                AutoUpdateState.Error(manualHint(current.target))
            )
            return
        }
        val step = steps[current.stepIndex]
        when (step.phase) {
            Phase.CLICK -> executeClick(current, step)
            Phase.READ_BALANCE -> executeReadBalance(current, step)
            Phase.READ_BILL -> executeReadBill(current)
        }
    }

    /** 按文本点击一步；失败时整步重试 2 次，仍失败则进入兜底流程。 */
    private fun executeClick(current: Session, step: Step) {
        val service = AutoUpdateAccessibilityService.instance
        if (service == null || !AutoUpdateAccessibilityService.connected) {
            abort(current, I18n.t("无障碍服务已断开"))
            return
        }
        val label = step.labels.firstOrNull().orEmpty()
        _state.value = AutoUpdateState.Running(
            I18n.t("正在打开%1\$s“%2\$s”…", current.target.displayName, label)
        )
        service.navigateByTextsWithRetry(step.labels, CLICK_TIMEOUT_MS) { clicked ->
            if (!sessionStillActive(current)) {
                // 任务已被取消 / 超时，忽略迟到的点击回调。
            } else if (clicked) {
                current.stepRetries = 0
                current.stepIndex++
                mainHandler.postDelayed({ runCurrentStep(current) }, step.waitMillis)
            } else if (current.stepRetries < MAX_STEP_RETRIES) {
                current.stepRetries++
                Log.d(TAG, "点击“$label”失败，整步重试 ${current.stepRetries}/$MAX_STEP_RETRIES")
                mainHandler.postDelayed({ executeClick(current, step) }, READ_RETRY_DELAY_MS)
            } else {
                abort(current, I18n.t("无法在当前页面找到“%1\$s”", label))
            }
        }
    }

    /** 读取余额：微信钱包页“零钱”、支付宝“我的”页“余额”；读不到时再试两次，仍失败不阻塞后续步骤。 */
    private fun executeReadBalance(current: Session, step: Step, attempt: Int = 0) {
        if (!sessionStillActive(current)) return
        val service = AutoUpdateAccessibilityService.instance
        if (service == null || !AutoUpdateAccessibilityService.connected) {
            abort(current, I18n.t("无障碍服务已断开"))
            return
        }
        // 进入钱包 / 我的页面后先等设置里的读取延迟再读屏；余额二次重试沿用原有短间隔，不重复整段延迟。
        if (attempt == 0 && current.readDelayMillis > 0L) {
            Log.d(TAG, "等待 ${current.readDelayMillis / 1_000L} 秒后读取余额")
            _state.value = AutoUpdateState.Running(waitHint(current.readDelayMillis))
            mainHandler.postDelayed(
                { readBalanceNow(current, step, attempt) },
                current.readDelayMillis
            )
            return
        }
        readBalanceNow(current, step, attempt)
    }

    /** 延迟结束（或余额重试）后真正读屏并写余额。 */
    private fun readBalanceNow(current: Session, step: Step, attempt: Int) {
        if (!sessionStillActive(current)) return
        val service = AutoUpdateAccessibilityService.instance
        if (service == null || !AutoUpdateAccessibilityService.connected) {
            abort(current, I18n.t("无障碍服务已断开"))
            return
        }
        _state.value = AutoUpdateState.Running(
            I18n.t("正在读取%1\$s余额…", current.target.displayName)
        )
        val lines = runCatching { service.readVisibleTexts() }.getOrDefault(emptyList())
        val balance = AutoUpdatePageParser.findBalance(current.target, lines)
        if (balance != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val account = current.repos.accounts.ensureAccount(
                            current.target.accountName,
                            current.target.accountType
                        )
                        current.repos.accounts.updateBalance(
                            account.id,
                            balance,
                            System.currentTimeMillis()
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "写入余额失败", e)
                }
            }
        }
        if (balance == null && attempt < MAX_STEP_RETRIES && sessionStillActive(current)) {
            mainHandler.postDelayed(
                { executeReadBalance(current, step, attempt + 1) },
                BALANCE_RETRY_DELAY_MS
            )
            return
        }
        current.stepIndex++
        mainHandler.postDelayed({ runCurrentStep(current) }, step.waitMillis)
    }

    /** 读取账单页：解析 → 写库 → 交付结果；页面未加载完时重试最多 2 次。 */
    private fun executeReadBill(current: Session) {
        if (!sessionStillActive(current)) return
        val service = AutoUpdateAccessibilityService.instance
        if (service == null || !AutoUpdateAccessibilityService.connected) {
            abort(current, I18n.t("无障碍服务已断开"))
            return
        }
        // 进入账单页后先等设置里的读取延迟；只等一次，后续解析重试沿用原有短间隔。
        if (!current.billDelayApplied && current.readDelayMillis > 0L) {
            current.billDelayApplied = true
            Log.d(TAG, "等待 ${current.readDelayMillis / 1_000L} 秒后读取账单")
            _state.value = AutoUpdateState.Running(waitHint(current.readDelayMillis))
            mainHandler.postDelayed({ executeReadBill(current) }, current.readDelayMillis)
            return
        }
        current.billDelayApplied = true
        _state.value = AutoUpdateState.Running(
            I18n.t("正在读取%1\$s账单…", current.target.displayName)
        )
        val lines = runCatching { service.readVisibleTexts() }.getOrDefault(emptyList())
        val readAt = System.currentTimeMillis()
        current.workJob = scope.launch {
            val page = try {
                withContext(Dispatchers.IO) {
                    AutoUpdatePageParser.parse(current.target, lines, readAt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析账单失败", e)
                abort(current, I18n.t("解析账单失败"))
                return@launch
            }
            if (!sessionStillActive(current)) return@launch

            // 页面可能还没加载完：没有流水、没有余额，也不像账单页时重试。
            if (page.rows.isEmpty() && page.balanceCents == null && !page.looksLikeBillPage) {
                if (current.stepRetries < MAX_STEP_RETRIES) {
                    current.stepRetries++
                    Log.d(TAG, "账单页尚未加载完成，重试 ${current.stepRetries}/$MAX_STEP_RETRIES")
                    mainHandler.postDelayed({ executeReadBill(current) }, READ_RETRY_DELAY_MS)
                } else {
                    abort(current, I18n.t("没有读取到账单"))
                }
                return@launch
            }

            val result = try {
                withContext(Dispatchers.IO) {
                    AutoUpdateWriter.apply(
                        repos = current.repos,
                        target = current.target,
                        page = page,
                        insertRows = true,
                        now = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入账单失败", e)
                abort(current, I18n.t("写入账单失败"))
                return@launch
            }
            deliver(current, successState(current.target, result))
        }
    }

    /**
     * 中止当前任务：
     * 1. 停止后续步骤与总超时；
     * 2. 若无障碍仍连接，尝试读取当前页兜底（只在当前页确实是账单页时才写库）；
     * 3. 兜底失败 / 服务断开时交付 Error，提示用户手动打开账单页。
     */
    private fun abort(current: Session, reason: String) {
        if (current.delivered || current.stopped) return
        current.stopped = true
        current.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        current.workJob?.cancel()
        AutoUpdateAccessibilityService.instance?.cancelPendingNavigation()
        Log.w(TAG, "自动更新中止：$reason")

        val service = AutoUpdateAccessibilityService.instance
        if (!AutoUpdateAccessibilityService.connected || service == null) {
            deliver(current, AutoUpdateState.Error(reason))
            return
        }

        _state.value = AutoUpdateState.Running(I18n.t("正在尝试读取当前页面…"))
        val lines = runCatching { service.readVisibleTexts() }.getOrDefault(emptyList())
        current.workJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    AutoUpdatePageParser.parse(current.target, lines, System.currentTimeMillis())
                }
                if (!isCurrent(current)) return@launch
                if (!page.looksLikeBillPage) {
                    deliver(current, AutoUpdateState.Error(failureMessage(reason, current.target)))
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    AutoUpdateWriter.apply(
                        repos = current.repos,
                        target = current.target,
                        page = page,
                        insertRows = true,
                        now = System.currentTimeMillis()
                    )
                }
                deliver(current, successState(current.target, result))
            } catch (e: Exception) {
                Log.e(TAG, "兜底读取当前页失败", e)
                deliver(current, AutoUpdateState.Error(failureMessage(reason, current.target)))
            }
        }
    }

    // ------------------------------------------------------------------ 读取延迟

    /** 设置的秒数 → 毫秒；设置层已保证默认 5 秒，这里再兜底收敛到 1..30 秒。 */
    private fun readDelayMillisOf(seconds: Int): Long =
        seconds.coerceIn(MIN_READ_DELAY_SECONDS, MAX_READ_DELAY_SECONDS) * 1_000L

    /** 手动读取时临时读设置；读不到就用默认 5 秒，不让延迟失败阻塞读取。 */
    private suspend fun resolveReadDelayMillis(repos: LedgerRepos): Long {
        val settings = runCatching { repos.settings.current() }.getOrNull()
            ?: return readDelayMillisOf(DEFAULT_READ_DELAY_SECONDS)
        return readDelayMillisOf(settings.autoUpdateDelaySeconds)
    }

    /** 等待读屏期间的提示文案。 */
    private fun waitHint(delayMillis: Long): String =
        I18n.t("等待页面加载，%1\$d 秒后读取…", (delayMillis / 1_000L).toInt())

    // ------------------------------------------------------------------ 状态交付

    private fun deliver(current: Session, newState: AutoUpdateState) {
        if (current.delivered) return
        current.delivered = true
        current.stopped = true
        current.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        if (session === current) session = null
        _state.value = newState
        Log.i(TAG, "自动更新结束：${newState.javaClass.simpleName}")
    }

    private fun sessionStillActive(current: Session): Boolean =
        session === current && !current.delivered && !current.stopped

    private fun isCurrent(current: Session): Boolean =
        session === current && !current.delivered

    /** 成功提示：优先报新增笔数，其次余额，最后“无新增”。 */
    private fun successState(
        target: AutoUpdateTarget,
        result: AutoUpdateWriter.Result
    ): AutoUpdateState = when {
        result.imported > 0 ->
            AutoUpdateState.Success(I18n.t("已自动记账 %1\$d 笔", result.imported))
        result.rowsOnPage > 0 ->
            AutoUpdateState.Success(I18n.t("账单已是最新，没有新增流水"))
        result.balanceUpdated ->
            AutoUpdateState.Success(I18n.t("已更新%1\$s余额", target.displayName))
        result.looksLikeBillPage ->
            AutoUpdateState.Success(I18n.t("%1\$s账单页暂无可读取的流水", target.displayName))
        else ->
            AutoUpdateState.Error(manualHint(target))
    }

    private fun manualHint(target: AutoUpdateTarget): String =
        I18n.t("请手动打开%1\$s账单页后点“读取当前页”", target.displayName)

    private fun failureMessage(reason: String, target: AutoUpdateTarget): String =
        I18n.t("%1\$s。%2\$s", reason, manualHint(target))
}
