package com.guolongli.dinner.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.guolongli.dinner.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动崩溃的落地记录。
 *
 * 为什么要有这个：用户报「打开一片空白」时，我手上没有任何信息——进程已经死了，
 * 白屏只是启动窗口留下的底色，界面上不可能有提示。把堆栈写进私有目录，
 * 下次启动先把它摆出来，至少能一眼看到是哪一行。
 *
 * 只记录，不拦截：写完照旧交还给系统默认处理器，该结束进程就结束进程。
 * 吞掉崩溃会让应用停在一个半死不活的状态，比崩掉更难查。
 */
object CrashGuard {

    private const val DIR = "crash"
    private const val FILE = "last.txt"
    private const val TAG = "CrashGuard"

    /** 在 `Application.onCreate` 里装，越早越好，晚了就捕不到启动期的崩溃 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
                .onFailure { Log.w(TAG, "崩溃记录写入失败", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 上次崩溃的详情；没有就返回 null */
    fun lastReport(context: Context): String? = runCatching {
        val file = File(context.filesDir, "$DIR/$FILE")
        if (!file.exists() || file.length() == 0L) null else file.readText()
    }.getOrNull()

    /** 用户选了「继续使用」，或者界面已经正常起来之后清掉 */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, "$DIR/$FILE").delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val text = buildString {
            appendLine("时间：$time")
            appendLine("线程：${thread.name}")
            appendLine("版本：${BuildConfig.VERSION_NAME}")
            appendLine(
                "设备：${Build.MANUFACTURER} ${Build.MODEL} / " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            appendLine()
            append(Log.getStackTraceString(error))
        }
        File(dir, FILE).writeText(text)
    }
}
