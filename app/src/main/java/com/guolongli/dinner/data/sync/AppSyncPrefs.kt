package com.guolongli.dinner.data.sync

import android.content.Context

/**
 * 自动同步设置的落地。默认开着，间隔 30 分钟，只走 Wi-Fi——
 * 家里长辈的手机多半是流量套餐，默认不吃流量更稳妥。
 */
class AppSyncPrefs(context: Context) : SyncPrefs {

    private val prefs = context.applicationContext
        .getSharedPreferences("sync_settings", Context.MODE_PRIVATE)

    override var autoEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    override var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL).coerceAtLeast(MIN_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceAtLeast(MIN_INTERVAL)).apply()

    override var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    override var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AT, value).apply()

    override var lastSummary: String
        get() = prefs.getString(KEY_LAST_SUMMARY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_SUMMARY, value).apply()

    override var lastError: String
        get() = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    override var lastAuthRequired: Boolean
        get() = prefs.getBoolean(KEY_AUTH_REQUIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTH_REQUIRED, value).apply()

    override var lastMediaRestoreAt: Long
        get() = prefs.getLong(KEY_MEDIA_RESTORE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_MEDIA_RESTORE_AT, value).apply()

    companion object {
        const val MIN_INTERVAL = 15
        const val DEFAULT_INTERVAL = 30

        /** 界面上的三档间隔 */
        val INTERVALS = listOf(15, 30, 60)

        /**
         * 同步后自动补回「最近一段时间」的远端媒体，两次之间至少隔这么久。
         * 防止每次同步都去把几百张照片拉一遍；又保证换设备同步后能尽快看到近照。
         */
        const val MEDIA_RESTORE_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** 一次自动补回最多取回多少个文件，避免一口气跑太久 */
        const val MEDIA_RESTORE_LIMIT = 40

        private const val KEY_ENABLED = "auto_enabled"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_LAST_AT = "last_sync_at"
        private const val KEY_LAST_SUMMARY = "last_summary"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_AUTH_REQUIRED = "last_auth_required"
        private const val KEY_MEDIA_RESTORE_AT = "last_media_restore_at"
    }
}
