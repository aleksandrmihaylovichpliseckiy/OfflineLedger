package com.ledger.offline.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * 版本升级前的本地数据库备份。
 *
 * 应用升级本身不会清除 data 目录，Room 迁移也只做增量字段；这里额外在**打开数据库之前**
 * 把上一版本的 `ledger.db`（含 -wal / -shm）复制到 `filesDir/upgrades/`，避免迁移异常或
 * 误操作导致流水 / 手动修正丢失。只保留最近几个版本，不联网。
 */
object UpgradeBackup {

    private const val PREFS = "upgrade_state"
    private const val KEY_LAST_VERSION = "last_version_code"
    private const val MAX_BACKUP_FILES = 9

    fun ensureBackup(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = currentVersionCode(context)
            val db = context.getDatabasePath("ledger.db")

            if (!prefs.contains(KEY_LAST_VERSION)) {
                // 第一次运行带备份逻辑的版本：无法得知旧 code，若已有数据库则先备份为 previous。
                if (db.exists()) {
                    backup(context, db, "previous")
                }
                prefs.edit().putInt(KEY_LAST_VERSION, current).apply()
                return
            }

            val last = prefs.getInt(KEY_LAST_VERSION, current)
            if (last == current) return
            if (db.exists()) {
                backup(context, db, "v$last")
            }
            prefs.edit().putInt(KEY_LAST_VERSION, current).apply()
        }
    }

    private fun backup(context: Context, db: File, stamp: String) {
        val dir = File(context.filesDir, "upgrades").apply { mkdirs() }
        copyIfExists(db, File(dir, "ledger-$stamp.db"))
        copyIfExists(File(db.path + "-wal"), File(dir, "ledger-$stamp.db-wal"))
        copyIfExists(File(db.path + "-shm"), File(dir, "ledger-$stamp.db-shm"))
        prune(dir)
    }

    private fun currentVersionCode(context: Context): Int {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(0)
    }

    private fun copyIfExists(source: File, target: File) {
        if (!source.exists()) return
        runCatching {
            source.copyTo(target, overwrite = true)
        }
    }

    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: return
            files.drop(MAX_BACKUP_FILES).forEach { it.delete() }
        }
    }
}
