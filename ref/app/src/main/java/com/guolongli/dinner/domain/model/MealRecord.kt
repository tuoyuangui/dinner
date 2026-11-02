package com.guolongli.dinner.domain.model

/**
 * 一条记录（一顿饭，或者一段生活）。
 *
 * [timestamp] 为发生时间，日视图按它在本地时区归档。
 * [media] 是这条记录带的媒体列表，可以为空（纯文字）、可以多段（照片与视频混排）。
 * [marked] 是用户打的星标，用于快速索引「想翻回去看」的那些条。
 * [tags] 是标签列表（如「探店」「家常」），随记录上云，其他成员可见。
 * [deletedAt] 为软删除时间戳，同步时用于告知其他人（云端删除）。
 * [localRemovedAt] 为「只删本地」标记，不参与同步：本机不再展示，
 *   云端与他人完全不受影响。
 */
data class MealRecord(
    val id: String,
    val timestamp: Long,
    val mealType: MealType,
    val note: String,
    val createdBy: String,
    val syncState: SyncState,
    val updatedAt: Long,
    val deletedAt: Long?,
    val rating: Int? = null,
    val media: List<MediaItem> = emptyList(),
    val marked: Boolean = false,
    val localRemovedAt: Long? = null,
    /** 记录时所在位置。没有授权定位或用户关掉时为 null，不参与展示。 */
    val location: GeoLocation? = null,
    /** 标签列表。空列表表示没打标签；随记录上云并在他人端展示。 */
    val tags: List<String> = emptyList(),
) {
    val isDeleted: Boolean get() = deletedAt != null

    /** 仅本机隐藏，云端仍在。 */
    val isLocallyRemoved: Boolean get() = localRemovedAt != null

    /** 是否附带媒体（照片或视频）。 */
    val hasMedia: Boolean get() = media.isNotEmpty()

    /** 第一条媒体的类型。卡片主图与文件名后缀都按它走。 */
    val mediaType: MediaType get() = media.firstOrNull()?.mediaType ?: MediaType.IMAGE

    val isVideo: Boolean get() = mediaType == MediaType.VIDEO

    // ---------- 单媒体兼容视图 ----------
    // 记录列表、缓存回填、播放器等处只关心「首图」，这里给成派生属性，
    // 避免每个调用点都写 media.firstOrNull()?.xxx。

    val photoLocalPath: String? get() = media.firstOrNull()?.localPath
    val photoRemotePath: String? get() = media.firstOrNull()?.remotePath
    val photoMd5: String? get() = media.firstOrNull()?.md5
    val mediaThumbPath: String? get() = media.firstOrNull()?.thumbPath
    val durationMs: Long? get() = media.firstOrNull()?.durationMs

    /** 还没上传成功的媒体段数，用于卡片提示与同步判断。 */
    val pendingMediaCount: Int get() = media.count { !it.isUploaded }

    /** 云端全部就位（含无媒体的纯文字记录）。 */
    val isFullyUploaded: Boolean get() = media.all { it.isUploaded }

    companion object {
        /**
         * 返回真实存在且非空的本地文件，否则 null。
         *
         * 图片与视频都走这里判断，避免「路径有值但文件已被清理」被当成可播放，
         * 导致界面显示加载失败而不是去补下载。
         */
        fun existingFile(path: String?): java.io.File? =
            path?.takeIf { it.isNotBlank() }
                ?.let { java.io.File(it) }
                ?.takeIf { it.isFile && it.length() > 0L }
    }
}
