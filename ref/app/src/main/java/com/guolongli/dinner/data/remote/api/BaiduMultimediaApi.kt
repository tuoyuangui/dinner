package com.guolongli.dinner.data.remote.api

import com.guolongli.dinner.data.remote.dto.BaiduFileMetaResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 多媒体接口，用于获取 dlink。Base URL 与文件接口相同。
 */
interface BaiduMultimediaApi {

    /**
     * 文件信息（获取 dlink）。
     * fsids 为 JSON 数组字符串，例如 [123456789]；dlink=1 要求返回下载链接。
     */
    @GET("multimedia")
    suspend fun fileMetas(
        @Query("method") method: String = "filemetas",
        @Query("fsids") fsIds: String,
        @Query("dlink") dlink: Int = 1,
        @Query("thumb") thumb: Int? = null,
        @Query("size") size: String? = null,
    ): BaiduFileMetaResponse
}
