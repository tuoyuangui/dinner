package com.guolongli.dinner.data

import android.content.Context

/**
 * 应用级设置。和身份无关，都是「这台设备上怎么用」的偏好。
 *
 * 全部落在 SharedPreferences：数据量极小、读写频繁、丢了也不致命，
 * 没必要为它建表或多引一个 DataStore 依赖。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 照片入库前先压缩：长边 1920、JPEG 88 */
    var compressEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPRESS, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPRESS, value).apply()

    /** 本地媒体保留天数；0 表示永久保留（只清照片视频，不动记录本身） */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION, 30)
        set(value) = prefs.edit().putInt(KEY_RETENTION, value.coerceAtLeast(0)).apply()

    /**
     * 应用锁：开启后回到前台要用系统身份验证（指纹 / 人脸 / 锁屏密码）。
     *
     * **只存这一个布尔值**，不存任何口令：验证交给系统，应用这边拿不到
     * 生物特征，也不需要自己维护一份 PIN。旧版本存在这里的 `app_lock_pin`
     * 会在第一次读取设置时被清掉，见 [migrate]。
     */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK, value).apply()

    init {
        // 上一版把 PIN 明文存这里，与「应用不保存任何口令」相违，升级时直接抹掉
        if (prefs.contains(LEGACY_KEY_PIN)) {
            prefs.edit().remove(LEGACY_KEY_PIN).apply()
        }
    }

    /**
     * 瞬时故障（频控 / 断网）的重试次数。
     *
     * README 的「网盘重试」就是这个：只重试 20012、31034 这类瞬时故障，
     * 鉴权与业务错误重试多少次都一样，不浪费配额。
     */
    var retryTimes: Int
        get() = prefs.getInt(KEY_RETRY, 3).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_RETRY, value.coerceIn(1, 5)).apply()

    /** 主题：浅色 / 深色 / 跟随系统 */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM).orEmpty()
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** 上次清理本地媒体的时间，用来避免每次启动都扫一遍 */
    var lastCleanupAt: Long
        get() = prefs.getLong(KEY_CLEANUP, 0L)
        set(value) = prefs.edit().putLong(KEY_CLEANUP, value).apply()

    /**
     * 上次整理云端（清墓碑 / 孤儿附件）的时间。
     *
     * 单独一个 key，不和 [lastCleanupAt] 混用：一个删本机文件、一个删云端文件，
     * 混在一起会出现「清完本机顺手把云端的节奏也提前了」这种莫名其妙的后果。
     */
    var lastCloudSweepAt: Long
        get() = prefs.getLong(KEY_CLOUD_SWEEP, 0L)
        set(value) = prefs.edit().putLong(KEY_CLOUD_SWEEP, value).apply()

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** 「外观」弹窗的可选项与文案 */
        val THEME_OPTIONS = listOf(THEME_LIGHT, THEME_DARK, THEME_SYSTEM)

        fun themeLabel(mode: String): String = when (mode) {
            THEME_DARK -> "深色"
            THEME_SYSTEM -> "跟随系统"
            else -> "浅色"
        }
        const val RETENTION_FOREVER = 0

        /** 两次云端整理之间至少隔这么久：整理要走列表 + 逐个下载墓碑，别天天扫 */
        const val CLOUD_SWEEP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        /** 两次本地清理之间至少隔一天：保留期是按天的，扫得更勤也没有新东西可删 */
        const val CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** 「网盘重试」可选的几档 */
        val RETRY_OPTIONS = listOf(1, 2, 3, 5)

        /** 「本地数据」里可选的几档，单位天 */
        val RETENTION_OPTIONS = listOf(0, 30, 90, 180, 365)

        private const val KEY_COMPRESS = "compress"
        private const val KEY_RETENTION = "retention_days"
        private const val KEY_LOCK = "app_lock"
        private const val LEGACY_KEY_PIN = "app_lock_pin"
        private const val KEY_RETRY = "netdisk_retry"
        private const val KEY_THEME = "theme"
        private const val KEY_CLEANUP = "last_cleanup"
        private const val KEY_CLOUD_SWEEP = "last_cloud_sweep"

        fun retentionLabel(days: Int): String = when (days) {
            RETENTION_FOREVER -> "永久保留"
            365 -> "保留最近 1 年"
            else -> "保留最近 $days 天"
        }
    }
}
