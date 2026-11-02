package com.guolongli.dinner.di.impl

import android.content.Context
import android.graphics.Bitmap
import com.guolongli.dinner.core.UploadConfig
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.data.image.ImageCompressor
import com.guolongli.dinner.data.media.VideoImporter
import com.guolongli.dinner.domain.media.ImportedMedia
import com.guolongli.dinner.domain.media.MediaImporter
import com.guolongli.dinner.domain.media.MediaSource
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体导入实现：图片走 [ImageCompressor]，视频走 [VideoImporter]。
 *
 * 落盘位置从 cacheDir 改到 filesDir：
 * 系统会在存储紧张或用户「清理缓存」时删掉 cacheDir 里的文件，
 * 一旦被清理，记录里的本地路径就成了死路径，界面只能显示「加载失败」。
 * 这些是用户自己的原始素材，必须放在不会被随手清掉的位置。
 */
@Singleton
class AppMediaImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compressor: ImageCompressor,
    private val videoImporter: VideoImporter,
    private val storageConfig: StorageConfigRepository,
) : MediaImporter {

    private val photoDir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    override suspend fun import(source: MediaSource): Result<ImportedMedia> = runCatching {
        // 「是否压缩」在导入这一刻读取：改设置只影响之后添加的媒体，不会回头动已有文件。
        val compress = storageConfig.compressMedia()
        // 解码/EXIF/写盘/读视频元数据都是同步阻塞操作，必须移到 IO 线程。
        withContext(Dispatchers.IO) {
            when (source) {
                is MediaSource.Image -> importImage(source, compress)
                is MediaSource.CameraBitmap -> importBitmap(source.bitmap)
                is MediaSource.Video -> videoImporter.import(source.uri, compress)
            }
        }
    }

    private fun importImage(source: MediaSource.Image, compress: Boolean): ImportedMedia {
        val target = File(photoDir, FileNames.mealPhoto(extension = "jpg"))
        compressor.saveToFile(source.uri, target, compress)
        return ImportedMedia(
            localPath = target.absolutePath,
            thumbnailPath = null,
            mediaType = MediaType.IMAGE,
            durationMs = null,
        )
    }

    /** 相机缩略图 Bitmap 按同样的约定压缩后落盘。 */
    private fun importBitmap(bitmap: Bitmap): ImportedMedia {
        val target = File(photoDir, FileNames.mealPhoto(extension = "jpg"))
        val scaled = scaleToMaxEdge(bitmap, UploadConfig.MAX_EDGE_PX)
        try {
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, UploadConfig.JPEG_QUALITY, out)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        return ImportedMedia(
            localPath = target.absolutePath,
            thumbnailPath = null,
            mediaType = MediaType.IMAGE,
            durationMs = null,
        )
    }

    private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
