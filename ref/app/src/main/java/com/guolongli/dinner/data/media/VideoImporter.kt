package com.guolongli.dinner.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.guolongli.dinner.core.UploadConfig
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.domain.media.ImportedMedia
import com.guolongli.dinner.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频导入：复制到应用私有目录，按设置决定要不要压小，并生成封面帧与时长。
 *
 * 压缩（[compress] = true）时交给 [VideoTranscoder] 把长边降到 1280 再重编码；
 * 转码失败或产物校验不通过，一律保留原文件——绝不能为了省空间把视频弄坏。
 * 默认不压缩，原视频直接存。
 */
@Singleton
class VideoImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcoder: VideoTranscoder,
) {

    private val videoDir: File
        get() = File(context.filesDir, "videos").apply { mkdirs() }

    suspend fun import(uri: Uri, compress: Boolean): ImportedMedia = withContext(Dispatchers.IO) {
        val extension = FileNames.videoExtension(context.contentResolver.getType(uri))

        // 先落到临时文件：无论后面压不压，都有一份完整原片在手，不会出现两头空。
        val staging = File(videoDir, "staging_${System.currentTimeMillis()}.tmp")
        try {
            copyTo(uri, staging)
        } catch (error: Throwable) {
            staging.delete()
            throw DinnerException.Local("视频读取失败，请换一个视频或稍后重试", error)
        }

        if (!staging.exists() || staging.length() == 0L) {
            staging.delete()
            throw DinnerException.Local("视频读取失败：文件为空")
        }
        if (staging.length() > UploadConfig.MAX_VIDEO_BYTES) {
            val limitMb = UploadConfig.MAX_VIDEO_BYTES / (1024 * 1024)
            staging.delete()
            throw DinnerException.Validation("视频超过 $limitMb MB，请先在系统相册里裁剪后再选")
        }

        val target = if (compress) {
            transcodedOrNull(staging) ?: staging.moveTo(extension)
        } else {
            staging.moveTo(extension)
        }

        val (durationMs, thumbnail) = readMetadataAndThumbnail(target)
        ImportedMedia(
            localPath = target.absolutePath,
            thumbnailPath = thumbnail?.absolutePath,
            mediaType = MediaType.VIDEO,
            durationMs = durationMs,
        )
    }

    /**
     * 尝试压缩。[staging] 不变，成功时返回压缩产物（临时文件由调用方删除）。
     *
     * 校验分两层：转码器自己保证流程没抛异常，这里再用 MediaMetadataRetriever
     * 确认产物能被系统解析出时长与视频轨——只有文件存在是不够的。
     */
    private fun transcodedOrNull(staging: File): File? {
        val compressed = File(videoDir, FileNames.mealPhoto(extension = "mp4"))
        val ok = transcoder.transcode(staging, compressed) &&
            VideoTranscoder.isValidVideo(compressed)
        if (ok) {
            staging.delete()
            return compressed
        }
        compressed.delete()
        Logger.w("视频未压缩，按原文件保存")
        return null
    }

    /** 临时文件转成正式文件名：原扩展名保持不变，压缩产物统一是 mp4。 */
    private fun File.moveTo(extension: String): File {
        val target = File(videoDir, FileNames.mealPhoto(extension = extension))
        if (!renameTo(target)) {
            copyTo(target)
            delete()
        }
        return target
    }

    private fun copyTo(uri: Uri, target: File): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw DinnerException.Local("无法打开所选视频")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return target
    }

    /**
     * 提取时长与封面帧。两者都失败不影响上传，只是列表会显示占位。
     *
     * 从落盘后的文件读，而不是原始 Uri：压缩过的视频尺寸与时长都变了，
     * 封面必须来自最终要上传的那一份。
     *
     * 用 OPTION_CLOSEST_SYNC 取 1 秒处画面：视频开头常见纯黑，
     * 1 秒处通常已经是真实画面。
     */
    private fun readMetadataAndThumbnail(file: File): Pair<Long?, File?> {
        val retriever = MediaMetadataRetriever()
        var duration: Long? = null
        var thumbnail: File? = null
        try {
            retriever.setDataSource(file.absolutePath)
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }

            val frame = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
            if (frame != null) {
                thumbnail = writeThumbnail(frame, file.nameWithoutExtension)
                frame.recycle()
            }
        } catch (error: Throwable) {
            Logger.w("读取视频元数据失败：${error::class.java.simpleName}")
        } finally {
            runCatching { retriever.release() }
        }
        return duration to thumbnail
    }

    private fun writeThumbnail(frame: Bitmap, baseName: String): File? = runCatching {
        val scaled = scaleToMaxEdge(frame, UploadConfig.THUMBNAIL_MAX_EDGE_PX)
        val target = File(videoDir, "$baseName.thumb.jpg")
        FileOutputStream(target).use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, UploadConfig.THUMBNAIL_QUALITY, stream)
        }
        if (scaled !== frame) scaled.recycle()
        target.takeIf { it.length() > 0L }
    }.getOrNull()

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
