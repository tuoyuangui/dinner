package com.guolongli.dinner.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.guolongli.dinner.data.sync.SyncReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自动同步的「点火」逻辑：应用一启动就把后台任务排上，并盯着网络状态，
 * 断网恢复的瞬间补一次——用户从地铁里出来，不用打开应用记录就已经传上去了。
 *
 * 这里只负责触发时机，具体同步规则在 AutoSync / SyncEngine。
 *
 * 两条纪律：
 * 1. **不占启动帧**：排期要开 WorkManager 的库、请求要开 Room，都是 IO；
 * 2. **失败不致命**：定时同步是增强功能，排不上顶多「得手动点一下同步」，
 *    绝不该把应用拖下水。所以整段跑在后台线程，且逐段兜住异常。
 */
object AutoSyncRuntime {

    private const val TAG = "AutoSyncRuntime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (started) return
        started = true

        scope.launch {
            // 让后台任务和当前设置对齐（首次安装时把周期任务建起来）
            runCatching { Graph.scheduler.reschedule() }
                .onFailure { Log.w(TAG, "后台同步排期失败，先只靠前台同步", it) }

            // 回到前台先补一次：可能上次退出时没网
            runCatching { Graph.autoSync.request(SyncReason.STARTUP) }
                .onFailure { Log.w(TAG, "启动时触发同步失败", it) }
        }

        watchNetwork(context.applicationContext)
    }

    /** 注册网络恢复回调；拿不到系统服务就只靠前台触发，不影响功能 */
    private fun watchNetwork(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        if (callback != null) return

        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Graph.autoSync.request(SyncReason.NETWORK)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                // 从流量切到 Wi-Fi 也算一次可用网络，顺手同步
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    Graph.autoSync.request(SyncReason.NETWORK)
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(watcher) }
            .onSuccess { callback = watcher }
            .onFailure { Log.w(TAG, "网络回调注册失败，改由前台触发同步", it) }
    }
}
