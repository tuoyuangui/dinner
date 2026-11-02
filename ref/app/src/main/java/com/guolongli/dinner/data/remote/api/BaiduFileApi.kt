package com.guolongli.dinner.data.remote.api

import com.guolongli.dinner.core.BaiduApi
import com.guolongli.dinner.data.remote.dto.BaiduCreateFileResponse
import com.guolongli.dinner.data.remote.dto.BaiduFileListResponse
import com.guolongli.dinner.data.remote.dto.BaiduPrecreateResponse
import com.guolongli.dinner.data.remote.dto.BaiduUploadPartResponse
import okhttp3.MultipartBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * 网盘文件接口。Base URL 为 https://pan.baidu.com/rest/2.0/xpan/，
 * access_token 由拦截器统一注入查询参数，业务代码不得自行拼接。
 *
 * 注意 [uploadPart] 是唯一一个**不在这台主机**上的接口：分片上传固定打到
 * d.pcs.baidu.com/rest/2.0/pcs/superfile2，所有参数（method / type / path /
 * uploadid / partseq）都必须放在查询串里，只有文件本体走 multipart。
 * 早期把它按 `xpan/file?method=superfile2` 来写，会在 Retrofit 校验阶段直接抛
 * 「@Field parameters can only be used with form encoding」，导致每一段新文件
 * 都在第二步失败。
 */
interface BaiduFileApi {

    /**
     * 文件列表。/file?method=list&dir=<绝对路径>
     *
     * 分页同时传 start/limit 与 num：这两个是百度不同版本文档里的参数名，
     * 服务端认哪个都能拿到同一页。翻页与终止判断在
     * [com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource.list] 里做，
     * 这里只取一页——**调用方不该直接用它拼全量**，否则目录一大就会漏文件。
     */
    @GET("file")
    suspend fun list(
        @Query("method") method: String = "list",
        @Query("dir") dir: String,
        @Query("order") order: String = "time",
        @Query("desc") desc: Int = 1,
        @Query("start") start: Int = 0,
        @Query("limit") limit: Int = BaiduApi.LIST_PAGE_SIZE,
        @Query("num") num: Int = BaiduApi.LIST_PAGE_SIZE,
    ): BaiduFileListResponse

    /**
     * 预上传。block_list 为各分片 MD5 的 JSON 数组字符串。
     *
     * - rtype=3：云端存在同名文件时直接覆盖（records.json / members.json /
     *   头像这类固定路径每次同步都要重写，不带 3 会撞 31061）。
     * - autoinit=1：百度要求原样传的固定值，缺了可能只拿到空的 uploadid。
     */
    @FormUrlEncoded
    @POST("file")
    suspend fun precreate(
        @Query("method") method: String = "precreate",
        @Field("path") path: String,
        @Field("size") size: Long,
        @Field("isdir") isDir: Int = 0,
        @Field("rtype") rtype: Int = 3,
        @Field("autoinit") autoInit: Int = 1,
        @Field("block_list") blockList: String,
    ): BaiduPrecreateResponse

    /**
     * 获取上传域名。`GET /rest/2.0/pcs/file?method=locateupload&appid=250528&uploadid=…`
     *
     * 文档要求上传前必须调用：分片上传与单步上传都要用这里返回的域名，
     * **不要长期缓存或写死**某个上传服务器。appid 固定 250528。
     */
    @GET("rest/2.0/pcs/file")
    suspend fun locateUpload(
        @Query("method") method: String = "locateupload",
        @Query("appid") appId: Int = 250528,
        @Query("uploadid") uploadId: String,
    ): com.guolongli.dinner.data.remote.dto.BaiduLocateUploadResponse

    /**
     * 单步上传（小文件）：`POST <UPLOAD_HOST>/rest/2.0/pcs/file?method=upload`。
     *
     * 文档口径：文件小于等于 4 MB 时无需切片，可直接上传一次，
     * 省掉预上传 + 分片 + 创建文件三步。这里参数同样整体拼在 URL 上，
     * 与官方 curl 示例一致。
     */
    @Multipart
    @POST
    suspend fun singleStepUpload(
        @Url url: String,
        @Part file: MultipartBody.Part,
    ): BaiduCreateFileResponse

    /**
     * 分片上传。url 由调用方用 [com.guolongli.dinner.core.BaiduApi.PCS_BASE_URL] 拼好，
     * 形如 `https://d.pcs.baidu.com/rest/2.0/pcs/superfile2?method=upload&type=tmpfile
     * &path=...&uploadid=...&partseq=0`。
     *
     * 这里刻意不用 @Query / @Field：分片接口的参数解析对顺序与位置很敏感，
     * 直接把链接拼完整最贴近官方 curl 示例。
     */
    @Multipart
    @POST
    suspend fun uploadPart(
        @Url url: String,
        @Part file: MultipartBody.Part,
    ): BaiduUploadPartResponse

    /**
     * 创建文件。path、size、block_list 必须与预上传完全一致。
     *
     * rtype=3 同样要带上：create 才是真正落盘的一步，少了它，同名路径
     * 会被判定为「文件已存在」（31061），覆盖式重传全部失败。
     */
    @FormUrlEncoded
    @POST("file")
    suspend fun createFile(
        @Query("method") method: String = "create",
        @Field("path") path: String,
        @Field("size") size: Long,
        @Field("isdir") isDir: Int = 0,
        @Field("rtype") rtype: Int = 3,
        @Field("block_list") blockList: String,
        @Field("uploadid") uploadId: String,
    ): BaiduCreateFileResponse

    /** 删除文件，用于软删除后的远端清理。 */
    @FormUrlEncoded
    @POST("file")
    suspend fun deleteFile(
        @Query("method") method: String = "delete",
        @Field("path") path: String,
    ): BaiduCreateFileResponse
}
