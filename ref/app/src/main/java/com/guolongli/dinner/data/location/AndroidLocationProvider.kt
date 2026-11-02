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
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.domain.location.LocationProvider
import com.guolongli.dinner.domain.model.GeoLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 系统定位实现。
 *
 * 只在用户点「获取位置」时取一次，不订阅位置更新、不进后台、不做轨迹。
 *
 * 取法分两步，都有明确的兜底：
 * 1. 先用 [LocationManager.getLastKnownLocation] 拿缓存——多数情况下上一个应用已经定位过，
 *    零等待；
 * 2. 拿不到再注册一次单次定位（GPS 与网络各试一次），带 8 秒超时。
 *    超时就放弃，保存记录照样成功，只是没有位置。
 *
 * 绝不悄悄要权限：没权限直接返回失败，由编辑页把「开启定位」按钮亮出来，
 * 用户点了才走系统授权流程（见 EditScreen）。
 */
@Singleton
class AndroidLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationProvider {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun current(): Result<GeoLocation> {
        if (!hasPermission()) {
            return Result.failure(
                DinnerException.Validation("没有定位权限，开启后可把地点记进这条记录")
            )
        }
        if (!isEnabled()) {
            return Result.failure(DinnerException.Validation("系统定位服务已关闭"))
        }

        val location = lastKnown() ?: singleUpdate()
            ?: return Result.failure(DinnerException.Local("暂时取不到位置，可稍后重试"))

        if (!GeoLocation.isValid(location.latitude, location.longitude)) {
            return Result.failure(DinnerException.Local("定位结果不合法，已忽略"))
        }

        val (address, placeName) = reverseGeocode(location.latitude, location.longitude)
        return Result.success(
            GeoLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                address = address,
                placeName = placeName,
            )
        )
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isEnabled(): Boolean = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)

    @SuppressLint("MissingPermission") // 调用前已校验权限
    private fun lastKnown(): Location? = runCatching {
        sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }.getOrNull()

    /**
     * 取一次新位置。两个 provider 都注册上，谁先回用谁的，整体 8 秒超时。
     *
     * 用 [suspendCancellableCoroutine] 而不是轮询：系统定位回调是异步的，
     * 轮询既浪费 CPU 又没法及时取消。超时/取消时记得 removeUpdates，别让定位芯片一直跑。
     */
    @SuppressLint("MissingPermission") // 调用前已校验权限
    private suspend fun singleUpdate(): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: android.os.Bundle?,
                    ) = Unit

                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }

                val started = providers.mapNotNull { provider ->
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            locationManager.getCurrentLocation(provider, null, context.mainExecutor) {
                                it?.let { loc -> if (continuation.isActive) continuation.resume(loc) }
                            }
                            provider
                        } else {
                            @Suppress("DEPRECATION")
                            locationManager.requestSingleUpdate(provider, listener, null)
                            provider
                        }
                    }.getOrNull()
                }
                if (started.isEmpty() && continuation.isActive) {
                    continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    runCatching { locationManager.removeUpdates(listener) }
                }
            }
        }
    }

    /**
     * 逆地理编码：经纬度 -> 一句人话地址。
     *
     * Geocoder 走的是系统服务，可能慢、可能失败、可能返回空——
     * 任何一种情况都只影响「地址文案」，不影响位置本身，因此全程吞异常。
     * 优先给短地名（路名 / 小区），完整地址放第二行。
     */
    private suspend fun reverseGeocode(
        latitude: Double,
        longitude: Double,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            // Geocoder.isPresent 是静态方法：判断系统里有没有可用的地理编码服务。
            if (!Geocoder.isPresent()) return@runCatching "" to ""
            @Suppress("DEPRECATION") // getFromLocation 在 API 33 起换了签名，这里双路兜底
            val addresses = geocoder.getFromLocation(latitude, longitude, 3).orEmpty()
            val first = addresses.firstOrNull() ?: return@runCatching "" to ""

            val place = listOfNotNull(
                first.featureName,
                first.thoroughfare,
                first.subLocality,
            ).distinct().take(2).joinToString(" ").takeIf { it.isNotBlank() } ?: ""

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
        const val LOCATION_TIMEOUT_MS = 8_000L
    }
}
