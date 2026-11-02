package com.guolongli.dinner.domain.location

import com.guolongli.dinner.domain.model.GeoLocation

/**
 * 一次性取当前所在位置。
 *
 * 只在保存记录时调用一次，不订阅、不追踪、不上传原始坐标到任何第三方——
 * 位置随记录本身走，谁能看到这条记录，谁就能看到这个位置。
 */
interface LocationProvider {

    /**
     * 取当前位置。
     *
     * - 没有定位权限：返回失败，由 UI 提示「开启定位后可附上地点」，
     *   绝不偷偷去要权限（权限要由用户在编辑页显式点开）。
     * - 定位服务关掉 / 取不到：返回失败，保存记录照样成功，只是没有位置。
     */
    suspend fun current(): Result<GeoLocation>
}
