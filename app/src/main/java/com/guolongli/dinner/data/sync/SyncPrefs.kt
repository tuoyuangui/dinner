package com.guolongli.dinner.data.sync

/**
 * 自动同步的设置与「上次结果」。
 *
 * 抽成接口有两个原因：
 * 1. 同步协调器不碰 Android API，规则能在 JVM 上测；
 * 2. 后台任务（WorkManager）和前台界面读的是同一份，不会出现「界面显示已同步、后台还在传」。
 */
interface SyncPrefs {

    /** 是否开启自动同步；关掉后只有手动点「立即同步」才动网盘 */
    var autoEnabled: Boolean

    /** 后台同步间隔（分钟）。WorkManager 的周期任务下限是 15 分钟 */
    var intervalMinutes: Int

    /** 只在 Wi-Fi 下自动传，省流量 */
    var wifiOnly: Boolean

    /** 上次同步完成的时间戳（毫秒），0 表示从未同步 */
    var lastSyncAt: Long

    /** 上次同步的一句话结论，直接显示在「我的 → 网盘连接」 */
    var lastSummary: String

    /** 上次同步的错误（多条用分号连起来），空串表示没有 */
    var lastError: String

    /** 上次同步是否因为授权失效而中止 */
    var lastAuthRequired: Boolean

    /** 上次自动补回远端媒体（区间取回）的时间戳（毫秒），用于限流 */
    var lastMediaRestoreAt: Long
}
