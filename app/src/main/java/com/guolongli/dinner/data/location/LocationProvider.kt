package com.guolongli.dinner.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** 记录发生的位置：经纬度 + 一句人话地址 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val placeName: String = "",
) {
    val displayText: String
        get() = placeName.takeIf { it.isNotBlank() }
            ?: address.takeIf { it.isNotBlank() }
            ?: coordinateText

    /** 纬度, 经度（ASCII 数字，避免某些语言环境输出非 ASCII 小数点） */
    val coordinateText: String
        get() = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    companion object {
        /** 进 UI 前先兜一层：反序列化出来的数据可能是别的版本写的，甚至手改过的 */
        fun isValid(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

/**
 * 一次性取当前所在位置。
 *
 * 只在保存记录时取一次，不订阅、不进后台、不做轨迹——一顿饭只需要一个点。
 *
 * 取法分两步，都有兜底：
 * 1. 先看 [LocationManager.getLastKnownLocation]，多数情况下系统里已经有缓存，零等待；
 * 2. 拿不到再注册一次单次定位，整体 8 秒超时。超时就放弃，保存记录照样成功，只是没有位置。
 *
 * **绝不悄悄要权限**：没权限直接返回失败，由编辑页把「加上位置」按钮亮出来，
 * 用户点了才走系统授权流程。
 */
class LocationProvider(context: Context) {

    private val app = context.applicationContext

    private val manager: LocationManager? by lazy {
        runCatching {
            app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }.getOrNull()
    }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isEnabled(): Boolean = runCatching {
        val m = manager ?: return@runCatching false
        m.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    /** 取当前位置；失败返回 null，调用方据此只提示一句，不影响保存 */
    suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        val m = manager ?: return null
        if (!isEnabled()) return null
        val location = lastKnown(m) ?: singleUpdate(m) ?: return null
        if (!GeoPoint.isValid(location.latitude, location.longitude)) return null
        val (address, place) = reverseGeocode(location.latitude, location.longitude)
        return GeoPoint(location.latitude, location.longitude, address, place)
    }

    @SuppressLint("MissingPermission") // 调用前已校验权限
    private fun lastKnown(m: LocationManager): Location? = runCatching {
        sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching { m.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }.getOrNull()

    /**
     * 取一次新位置。两个 provider 都注册，谁先回用谁的。
     *
     * 用 [suspendCancellableCoroutine] 而不是轮询：系统定位回调是异步的，
     * 轮询既费电又没法及时取消。超时/取消时记得 removeUpdates，别让定位芯片一直跑。
     */
    @SuppressLint("MissingPermission")
    private suspend fun singleUpdate(m: LocationManager): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }

                val started = providers.mapNotNull { provider ->
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            m.getCurrentLocation(provider, null, app.mainExecutor) { loc ->
                                if (loc != null && continuation.isActive) continuation.resume(loc)
                            }
                            provider
                        } else {
                            @Suppress("DEPRECATION")
                            m.requestSingleUpdate(provider, listener, null)
                            provider
                        }
                    }.getOrNull()
                }
                if (started.isEmpty() && continuation.isActive) continuation.resume(null)
                continuation.invokeOnCancellation { runCatching { m.removeUpdates(listener) } }
            }
        }
    }

    /**
     * 逆地理编码：经纬度 → 一句人话地址。
     *
     * Geocoder 走系统服务，可能慢、可能失败、可能返回空——任何一种情况
     * 都只影响「地址文案」，不影响位置本身，所以全程吞异常。
     */
    private suspend fun reverseGeocode(latitude: Double, longitude: Double): Pair<String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Geocoder.isPresent()) return@runCatching "" to ""
                @Suppress("DEPRECATION")
                val addresses = Geocoder(app, Locale.getDefault())
                    .getFromLocation(latitude, longitude, 3)
                    .orEmpty()
                val first = addresses.firstOrNull() ?: return@runCatching "" to ""
                val place = listOfNotNull(first.featureName, first.thoroughfare, first.subLocality)
                    .distinct()
                    .take(2)
                    .joinToString(" ")
                val full = listOfNotNull(
                    first.adminArea,
                    first.locality,
                    first.subLocality,
                    first.thoroughfare,
                    first.featureName,
                ).distinct().joinToString("")
                full to place
            }.getOrDefault("" to "")
        }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
