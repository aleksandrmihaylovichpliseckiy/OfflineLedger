package com.ledger.offline.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ledger.offline.domain.model.IconColorMode
import com.ledger.offline.domain.model.LedgerSettings
import com.ledger.offline.domain.model.ThemeTone

/**
 * 启动图标配色切换。
 *
 * Android 不支持在运行时直接改 `android:icon`，因此用 6 个 `activity-alias`
 * 分别指向 MainActivity、各自带不同配色的自适应图标；这里根据设置启用其中一个、禁用其它。
 *
 * 启用状态使用 [PackageManager.DONT_KILL_APP]，切换后桌面图标可能需几秒钟刷新。
 */
object AppIconController {

    private data class Alias(val className: String, val tone: ThemeTone, val defaultEnabled: Boolean)

    private val ALIASES = listOf(
        Alias("com.ledger.offline.LauncherGreen", ThemeTone.GREEN, true),
        Alias("com.ledger.offline.LauncherBlue", ThemeTone.BLUE, false),
        Alias("com.ledger.offline.LauncherPurple", ThemeTone.PURPLE, false),
        Alias("com.ledger.offline.LauncherOrange", ThemeTone.ORANGE, false),
        Alias("com.ledger.offline.LauncherSakura", ThemeTone.SAKURA_PINK, false),
        Alias("com.ledger.offline.LauncherMono", ThemeTone.MONO, false)
    )

    fun apply(context: Context, settings: LedgerSettings) {
        val target = if (settings.iconColorMode == IconColorMode.MANUAL) {
            settings.iconTone
        } else {
            settings.themeTone
        }
        runCatching { applyTone(context.applicationContext, target) }
    }

    private fun applyTone(context: Context, target: ThemeTone) {
        val pm = context.packageManager
        ALIASES.forEach { alias ->
            val component = ComponentName(context.packageName, alias.className)
            val current = runCatching { pm.getComponentEnabledSetting(component) }
                .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val effective = when (current) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> alias.defaultEnabled
            }
            val desired = alias.tone == target
            if (effective != desired) {
                runCatching {
                    pm.setComponentEnabledSetting(
                        component,
                        if (desired) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
    }
}
