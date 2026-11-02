package com.guolongli.dinner.core.log

import android.util.Log
import com.guolongli.dinner.BuildConfig

/**
 * 统一 logger。token 绝不允许出现在任何日志里，所有输出先经过 [redact]。
 */
object Logger {
    private const val TAG = "dinner"

    private val SECRET_PATTERN = Regex(
        "(?i)(access_token|refresh_token|client_secret|client_id|code|dlink)=[^&\\s]+"
    )

    fun redact(text: String): String =
        SECRET_PATTERN.replace(text) { match -> "${match.groupValues[1]}=<redacted>" }

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, redact(message))
    }

    fun i(message: String) {
        Log.i(TAG, redact(message))
    }

    fun w(message: String) {
        Log.w(TAG, redact(message))
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, redact(message), throwable)
    }
}
