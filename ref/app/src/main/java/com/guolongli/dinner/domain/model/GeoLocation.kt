package com.guolongli.dinner.domain.model

import com.guolongli.dinner.core.util.Ascii
import kotlinx.serialization.Serializable

/**
 * 记录发生的位置。
 *
 * 只保留展示需要的最少信息：经纬度 + 一句人话地址。
 * 不存轨迹、不存精度、不持续定位——一顿饭只在保存那一刻取一次当前位置。
 *
 * [address] 是逆地理编码得到的完整地址，[placeName] 是更短的地名（路名 / 小区 / 店名）。
 * 两个都拿不到时退化成经纬度字符串，绝不显示空白行。
 */
@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val placeName: String = "",
) {
    /**
     * 卡片上的一行文案：优先地名，其次地址，最后经纬度。
     *
     * 经纬度用 [Ascii.fixed] 而不是 `"%.5f".format(...)`：后者走默认 Locale，
     * 阿拉伯语 / 波斯语设备会输出 `٣٥٫٧٠٠٠٠`（连小数点都是阿拉伯字符）。
     * 这是坐标，用户要拿去复制、搜索、对照地图，必须是可读的 ASCII 数字。
     */
    val displayText: String
        get() = placeName.takeIf { it.isNotBlank() }
            ?: address.takeIf { it.isNotBlank() }
            ?: "${Ascii.fixed(latitude, COORDINATE_DECIMALS)}, ${Ascii.fixed(longitude, COORDINATE_DECIMALS)}"

    /** 一行「纬度, 经度」；卡片与编辑页共用同一格式，避免两处各写一份。 */
    val coordinateText: String
        get() = "${Ascii.fixed(latitude, COORDINATE_DECIMALS)}, " +
            Ascii.fixed(longitude, COORDINATE_DECIMALS)

    /** 是否拿到了人话地址（用于决定要不要再显示经纬度小字）。 */
    val hasAddress: Boolean
        get() = placeName.isNotBlank() || address.isNotBlank()

    companion object {
        /** 坐标展示的小数位数：约 1 米精度，够对照地图，也不至于噪成一长串。 */
        const val COORDINATE_DECIMALS = 5

        /**
         * 经纬度是否落在合理范围内。
         *
         * 位置来自系统定位，理论上合法，但反序列化出来的数据可能是别的版本写的、
         * 甚至手改过的——进 UI 前先兜一层，别让 NaN / 91 度这种值把布局算崩。
         */
        fun isValid(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}
