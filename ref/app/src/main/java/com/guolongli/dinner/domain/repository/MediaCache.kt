package com.guolongli.dinner.domain.repository

import java.io.File

/**
 * 远端媒体（照片 / 视频）的本机缓存。
 *
 * 背景：两端只同步结构化记录（records.json），媒体文件停留在网盘上。
 * 对端的时间线要显示照片或播放视频，必须自己把文件拉下来。
 * 这个仓库就是唯一的拉取入口，负责去重、落盘与命中判断。
 *
 * 约定：
 * - 所有缓存文件都落在应用私有目录，绝不写外部存储。
 * - 同一个远端路径并发请求只下载一次。
 * - 命中缓存直接返回，不发任何网盘请求（开发期接口频率很低，必须省着用）。
 */
interface MediaCache {

    /** 已缓存到本机的文件；不存在或为空返回 null。 */
    fun cachedFile(remotePath: String): File?

    /**
     * 把远端媒体解析成本机文件。
     *
     * @param remotePath 网盘绝对路径，必须落在数据根目录（默认 /apps/dinner/）之下。
     * @return 成功返回本机文件；失败返回可翻译成用户文案的异常。
     */
    suspend fun resolve(remotePath: String): Result<File>
}
