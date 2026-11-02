package com.guolongli.dinner.ui.util

/**
 * 视频时长文案：`m:ss`，超过一小时用 `h:mm:ss`。
 *
 * 时长为空或非法时返回 null，由调用方决定不显示角标。
 */
fun durationTextOf(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
