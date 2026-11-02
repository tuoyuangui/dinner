package com.guolongli.dinner.data.media

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.guolongli.dinner.data.local.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 从相册拿回来、已经落到应用私有目录的一份媒体 */
data class ImportedMedia(
    val localPath: String,
    val kind: MediaKind,
    val durationMs: Long? = null,
)

/**
 * 相册选择器的 Uri 是临时的：授权只在本次进程内有效，而且内容有可能被系统清理。
 * 所以选中之后立刻复制到应用私有目录，之后上传、回看都用自己的文件。
 *
 * 照片按「压缩存储」开关重编码（长边 1920 / JPEG 82，关掉则 2560 / 92）；
 * 视频同理：开着就把长边降到 1280（[VideoTranscoder]），关掉原样保存。
 * 压缩一律「尽力而为」——压不成、产物校验不过，都退回原文件。
 */
class MediaCache(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "media")
    private val transcoder = VideoTranscoder()

    fun fileOf(path: String): File = File(path)

    /** 给「区间取回」用的空文件位置，扩展名跟着远端文件名走 */
    fun newFile(prefix: String, extension: String): File {
        dir.mkdirs()
        val ext = extension.ifBlank { "jpg" }
        return File(dir, "$prefix-${UUID.randomUUID()}.$ext")
    }

    /** 导入一个相册 Uri；失败返回 null，界面提示一下就行，不该让整条记录存不下去 */
    suspend fun import(
        resolver: ContentResolver,
        uri: Uri,
        compress: Boolean = false,
    ): ImportedMedia? = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val mime = resolver.getType(uri).orEmpty()
            val isVideo = mime.startsWith("video/")
            if (isVideo) {
                importVideo(resolver, uri, mime, compress)
            } else {
                importPhoto(resolver, uri, compress)
            }
        }.getOrNull()
    }

    private fun importPhoto(resolver: ContentResolver, uri: Uri, compress: Boolean): ImportedMedia? {
        val maxEdge = if (compress) COMPRESS_EDGE else KEEP_EDGE
        val bitmap = ImageTools.loadScaled(resolver, uri, maxEdge) ?: return null
        val target = File(dir, "photo-${UUID.randomUUID()}.jpg")
        val bytes = ImageTools.compressToJpeg(bitmap, quality = if (compress) 82 else 92)
        bitmap.recycle()
        target.writeBytes(bytes)
        return ImportedMedia(target.absolutePath, MediaKind.PHOTO)
    }

    private fun importVideo(
        resolver: ContentResolver,
        uri: Uri,
        mime: String,
        compress: Boolean,
    ): ImportedMedia? {
        val ext = extensionOf(mime, displayName(resolver, uri)) ?: "mp4"

        // 先落成临时文件：无论压不压，手上都有一份完整原片，不会两头空。
        val staging = File(dir, "staging-${UUID.randomUUID()}.tmp")
        try {
            resolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: return null
        } catch (error: Throwable) {
            staging.delete()
            return null
        }
        if (!staging.isFile || staging.length() == 0L) {
            staging.delete()
            return null
        }

        // 要不要转码：两种情况
        //  1. 用户开了压缩 → 压到长边 1280，产物统一是 mp4；
        //  2. 容器本身系统解不了（mkv / webm / avi…）→ 哪怕不压缩也要转成 mp4，
        //     否则本机存下来的这份视频谁都放不了，传给别人更放不了。
        val needsTranscode = compress || ext.lowercase() !in PLAYABLE_VIDEO_EXTS

        if (needsTranscode) {
            val compressed = File(dir, "video-${UUID.randomUUID()}.mp4")
            val ok = transcoder.transcode(staging, compressed) &&
                VideoTranscoder.isValidVideo(compressed)
            if (ok) {
                staging.delete()
                return ImportedMedia(
                    localPath = compressed.absolutePath,
                    kind = MediaKind.VIDEO,
                    durationMs = durationOf(compressed),
                ).also { VideoThumb.ensure(compressed.absolutePath) }
            }
            compressed.delete()
        }

        // 没转成（或转不了）：保留原片，但扩展名要如实反映内容。
        // 扩展名决定 MediaPlayer 按哪种容器去解析，起错名字 = 播放器直接罢工。
        val realExt = ext.lowercase().takeIf { it in PLAYABLE_VIDEO_EXTS } ?: "mp4"
        val target = File(dir, "video-${UUID.randomUUID()}.$realExt")
        if (!staging.renameTo(target)) {
            // 跨文件系统或目标已存在时 rename 会失败，退回复制
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
        VideoThumb.ensure(target.absolutePath)
        return ImportedMedia(target.absolutePath, MediaKind.VIDEO, durationOf(target))
    }

    /** 视频时长：九宫格上的角标要用，取不到就当没有 */
    private fun durationOf(file: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun extensionOf(mime: String, name: String?): String? {
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { return it }
        return name?.substringAfterLast('.', "")?.lowercase()?.ifBlank { null }
    }

    /**
     * 删掉一个本地媒体文件。
     *
     * 只处理自己目录下的文件：用户可能从别处引用过路径，误删就是别人的东西。
     * 视频还要连带删掉同名封面，否则私有目录里会留下一堆孤儿 jpg。
     *
     * @return 是否真的删掉了文件（本来就不在返回 false，调用方不要当成清理成功）
     */
    fun delete(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        if (file.parentFile != dir) return false
        if (file.extension.lowercase() in VIDEO_EXTENSIONS) VideoThumb.deleteFor(path)
        return file.delete()
    }

    private companion object {
        const val COMPRESS_EDGE = 1920
        const val KEEP_EDGE = 2560

        /**
         * Android 系统播放器能直接解的容器。
         *
         * 不在表里的（mkv / webm / avi / flv…）在部分机型上压根没有解码器，
         * 存下来就是一段放不了的死文件——所以导入时会转成 mp4。
         */
        val PLAYABLE_VIDEO_EXTS = setOf("mp4", "mov", "3gp", "3gpp", "m4v")

        /** 需要「视频配套文件」（封面）一起清理的扩展名 */
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "3gpp", "mkv", "webm", "avi")
    }
}
