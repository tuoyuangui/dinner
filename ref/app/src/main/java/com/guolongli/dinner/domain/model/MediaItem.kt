package com.guolongli.dinner.domain.model

/**
 * 一条记录里的一段媒体。
 *
 * 一条记录可以带 0..[MAX_ITEMS] 段媒体，照片与视频可以混排——就像发一条动态。
 * [id] 在记录内稳定，用于上传定位、单张删除与本地缓存回填；
 * [localPath] 是本机文件（可能是用户原创，也可能是从网盘下来的缓存）；
 * [remotePath] 是网盘上的路径，为空表示还没上传成功。
 */
data class MediaItem(
    val id: String,
    val mediaType: MediaType,
    val localPath: String? = null,
    val remotePath: String? = null,
    val md5: String? = null,
    val thumbPath: String? = null,
    val durationMs: Long? = null,
    /**
     * 视频封面在网盘上的路径。
     *
     * 封面是独立的小图（`<视频名>.cover.jpg`）：对端只看时间线时拉封面就够了，
     * 不必为一个缩略图把几十 MB 的视频整个下载下来。
     * 图片没有封面，这个字段恒为 null。
     */
    val thumbRemotePath: String? = null,
) {
    val isVideo: Boolean get() = mediaType == MediaType.VIDEO

    /** 云端已有副本。 */
    val isUploaded: Boolean get() = !remotePath.isNullOrBlank()

    /** 本机有可用文件（路径有值且文件真实存在）。 */
    val hasLocalFile: Boolean get() = MealRecord.existingFile(localPath) != null

    companion object {
        /** 单条记录的媒体上限。九宫格刚好铺满，也避免一次上传太多文件触发频控。 */
        const val MAX_ITEMS = 9
    }
}
