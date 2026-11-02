package com.guolongli.dinner.data.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 视频封面：从视频里抽一帧存成同名 `.thumb.jpg`，放在视频旁边。
 *
 * 为什么不把封面路径写进数据库：封面是可以随时由视频重新算出来的东西，
 * 存下来只会多一份会失效、会不同步的状态。用「同名派生」的约定，
 * 换设备、清缓存、补下载之后都能自动对上。
 *
 * 抽帧很贵（要解一帧 H.264），所以只在第一次用到时抽，抽完就落盘，
 * 之后都是纯文件读取。
 */
object VideoThumb {

    /** 1 秒处：视频开头常见纯黑或黑场渐入，1 秒通常已经是真实画面 */
    private const val FRAME_AT_US = 1_000_000L
    private const val MAX_EDGE = 640
    private const val QUALITY = 80
    private const val TAG = "VideoThumb"

    /** 约定好的封面位置，不保证存在 */
    fun fileFor(videoPath: String): File {
        val video = File(videoPath)
        return File(video.parentFile, video.nameWithoutExtension + ".thumb.jpg")
    }

    /** 已经存在的封面；没有就返回 null，不抽帧 */
    fun existing(videoPath: String?): File? {
        if (videoPath.isNullOrBlank()) return null
        val file = fileFor(videoPath)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * 确保封面存在：已有就直接返回，没有就抽一帧写盘。
     *
     * 任何失败都只返回 null（格子退回占位图标），绝不让一张封面把列表搞崩。
     */
    fun ensure(videoPath: String?): File? {
        if (videoPath.isNullOrBlank()) return null
        existing(videoPath)?.let { return it }

        val video = File(videoPath)
        if (!video.isFile || video.length() == 0L) return null

        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        return try {
            retriever.setDataSource(video.absolutePath)
            // 先试 1 秒处；极短视频（< 1s）会返回 null，再退回第一帧
            frame = retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { write(fileFor(videoPath), it) }
        } catch (error: Throwable) {
            Log.w(TAG, "抽视频封面失败：${error.message}")
            null
        } finally {
            frame?.recycle()
            runCatching { retriever.release() }
        }
    }

    /** 删视频时把封面一起删掉，别在私有目录里留孤儿文件 */
    fun deleteFor(videoPath: String?) {
        if (videoPath.isNullOrBlank()) return
        runCatching { fileFor(videoPath).delete() }
    }

    private fun write(target: File, frame: Bitmap): File? {
        val scaled = scaleToMaxEdge(frame, MAX_EDGE)
        return try {
            FileOutputStream(target).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
            }
            target.takeIf { it.length() > 0L }
        } catch (error: Throwable) {
            // 写坏了就删掉半成品。
            // 不删的话会留下一个「有长度但内容损坏」的 jpg，
            // 而 [existing] 只看文件大小——下次就当成有效封面直接用，
            // 于是这个视频的封面**永远**是那张坏图，再也不会重抽。
            Log.w(TAG, "写视频封面失败：${error.message}")
            runCatching { target.delete() }
            null
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
