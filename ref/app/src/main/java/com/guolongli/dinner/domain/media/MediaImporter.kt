package com.guolongli.dinner.domain.media

import android.graphics.Bitmap
import android.net.Uri
import com.guolongli.dinner.domain.model.MediaType

/**
 * 用户挑中的媒体来源。
 *
 * - [Image]：系统相册选择器返回的图片 content Uri（含 HEIF / AVIF，导入时统一转 JPEG）。
 * - [Video]：系统相册选择器返回的视频 content Uri（只落库浅拷贝，不做转码）。
 * - [CameraBitmap]：系统相机返回的缩略图（TakePicturePreview 不需要 FileProvider 配置，
 *   因此在没有配置 Provider 的情况下也不会崩溃）。
 */
sealed interface MediaSource {
    data class Image(val uri: Uri) : MediaSource
    data class Video(val uri: Uri) : MediaSource
    data class CameraBitmap(val bitmap: Bitmap) : MediaSource
}

/**
 * 导入结果。所有路径都是应用私有目录下的绝对路径，可直接展示、可直接上传。
 *
 * @param thumbnailPath 视频封面帧；图片为 null（图片文件本身就是缩略图源）。
 * @param durationMs    视频时长；图片为 null。
 */
data class ImportedMedia(
    val localPath: String,
    val thumbnailPath: String?,
    val mediaType: MediaType,
    val durationMs: Long?,
)

/**
 * 把用户挑中的媒体落到应用私有目录。
 *
 * 「我的 - 本地数据 - 压缩后存储」决定这一批怎么落盘（默认关闭）：
 * - 关：图片原字节直存（非 JPEG 才转高质量 JPEG），视频原样保存；
 * - 开：图片 JPEG 80% / 最长边 1920，视频尽力转码到长边 1280，失败保留原片。
 *
 * 无论开不开，体积校验、封面帧、时长提取都必须做。
 * 具体阈值见 com.guolongli.dinner.core.UploadConfig。
 *
 * 之所以必须有一道「导入」步骤：系统相册返回的 content Uri 授权不会跨进程重启保留，
 * 直接把它写进 Room 会在下次启动后失效，因此必须先复制成私有文件。
 */
interface MediaImporter {

    /**
     * 导入并返回可直接展示、可直接上传的本地路径。
     *
     * 失败时返回 [Result.failure]，携带 DinnerException.Local 或 Validation，
     * 由 UI 翻译成用户能看懂的文案。
     */
    suspend fun import(source: MediaSource): Result<ImportedMedia>
}
