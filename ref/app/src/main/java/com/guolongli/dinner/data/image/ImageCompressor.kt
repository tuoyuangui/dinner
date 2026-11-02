package com.guolongli.dinner.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.guolongli.dinner.core.UploadConfig
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import java.io.File
import java.io.FileOutputStream

/**
 * 上传前压缩：JPEG 质量 80%，最长边不超过 1920px。
 *
 * 解码分两条通道，解决「无法解析图片」：
 * 1. 首选 [ImageDecoder]（API 28+），支持 HEIF / AVIF 等新格式，
 *    并会在解码阶段应用 EXIF 方向，不做二次旋转。
 * 2. [ImageDecoder] 失败时回退 [BitmapFactory]（含采样，避免超大图 OOM），
 *    该通道不处理方向，由 [applyExifRotation] 补一次。
 *
 * 两条通道都失败才报「无法解析所选图片」，不再把所有失败都混成同一句文案，
 * 便于用户判断是换了格式还是文件真的损坏。
 */
class ImageCompressor(private val context: Context) {

    /**
     * 把相册 / 相机返回的 Uri 压缩成指定路径的 JPEG。
     *
     * @param output 目标文件，父目录不存在会自动创建。
     * @return 写入完成的输出文件。
     */
    fun compressToFile(uri: Uri, output: File): File =
        encode(
            uri = uri,
            output = output,
            maxEdge = UploadConfig.MAX_EDGE_PX,
            quality = UploadConfig.JPEG_QUALITY,
            sampleEdge = UploadConfig.MAX_EDGE_PX,
        )

    /**
     * 按「是否压缩」把图片写到目标文件。
     *
     * - 不压缩：源本来就是 JPEG 时整字节复制，属于真正意义上的原图，一点画质都不掉；
     *   其它格式（HEIF / PNG / WebP）没法直接沿用，转为接近原尺寸的 JPEG（质量 95，
     *   解码采样上限 [ORIGINAL_SAMPLE_EDGE_PX]，只为了不把上亿像素整张读进内存）。
     * - 压缩：JPEG 80% + 最长边 1920。
     *
     * 复制失败会退回转码通道：宁可多一次编解码，也不能让用户选完图发现存不进去。
     */
    fun saveToFile(uri: Uri, output: File, compress: Boolean): File {
        if (compress) return compressToFile(uri, output)

        output.parentFile?.mkdirs()
        if (context.contentResolver.getType(uri) == MIME_JPEG) {
            val copied = runCatching { copyRaw(uri, output) }.isSuccess &&
                output.exists() && output.length() > 0L
            if (copied) return output
            Logger.w("原图直接复制失败，退回转 JPEG 通道")
        }
        return encode(
            uri = uri,
            output = output,
            maxEdge = null,
            quality = UploadConfig.JPEG_QUALITY_ORIGINAL,
            sampleEdge = ORIGINAL_SAMPLE_EDGE_PX,
        )
    }

    /** 原样复制字节，不做任何解码与重编码。 */
    private fun copyRaw(uri: Uri, output: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw DinnerException.Local("无法读取所选图片")
        input.use { source ->
            FileOutputStream(output).use { target -> source.copyTo(target) }
        }
    }

    /**
     * [maxEdge] 为 null 表示不缩放；[sampleEdge] 是解码阶段的采样上限，
     * 只用于控制峰值内存，与最终输出尺寸无关（解码后仍可缩放）。
     */
    private fun encode(uri: Uri, output: File, maxEdge: Int?, quality: Int, sampleEdge: Int): File {
        val decoded = decode(uri, sampleEdge)
            ?: throw DinnerException.Local("无法解析所选图片，请换一张或先在相册中导出为 JPG")

        val decodedBitmap = decoded.bitmap
        val oriented = if (decoded.orientationApplied) {
            decodedBitmap
        } else {
            applyExifRotation(uri, decodedBitmap)
        }
        val scaled = if (maxEdge == null) oriented else scaleToMaxEdge(oriented, maxEdge)

        try {
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { stream ->
                val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                if (!ok) throw DinnerException.Local("图片写入失败，请重试")
            }
        } finally {
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== decodedBitmap) oriented.recycle()
            decodedBitmap.recycle()
        }

        if (!output.exists() || output.length() == 0L) {
            throw DinnerException.Local("图片写入失败，请检查存储空间")
        }
        return output
    }

    private companion object {
        const val MIME_JPEG = "image/jpeg"

        /**
         * 不压缩时的解码采样上限。4000px 足以覆盖主流手机原图（12MP 约 4000x3000），
         * 再大只会把内存顶到 OOM，而手机屏幕上看不出差别。
         */
        const val ORIGINAL_SAMPLE_EDGE_PX = 4000
    }

    private data class Decoded(
        val bitmap: Bitmap,
        /** 解码阶段是否已经应用了 EXIF 方向，避免二次旋转。 */
        val orientationApplied: Boolean,
    )

    private fun decode(uri: Uri, sampleEdge: Int): Decoded? {
        val decodedByImageDecoder = runCatching { decodeWithImageDecoder(uri, sampleEdge) }
            .onFailure { Logger.d("ImageDecoder 解码失败：${it::class.java.simpleName}") }
            .getOrNull()
        if (decodedByImageDecoder != null) {
            return Decoded(decodedByImageDecoder, orientationApplied = true)
        }
        val decodedByFactory = runCatching { decodeWithBitmapFactory(uri, sampleEdge) }
            .onFailure { Logger.d("BitmapFactory 解码失败：${it::class.java.simpleName}") }
            .getOrNull()
        return decodedByFactory?.let { Decoded(it, orientationApplied = false) }
    }

    /** API 28+ 的 ImageDecoder：格式覆盖最广，且解码阶段就会摆正方向。 */
    private fun decodeWithImageDecoder(uri: Uri, sampleEdge: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 软件位图：后续要缩放与 JPEG 压缩，硬件位图不支持这些操作。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetSampleSize(
                calculateInSampleSize(info.size.width, info.size.height, sampleEdge),
            )
        }
    }

    /** 兜底通道：按采样率解码，避免超大原图整张读进内存导致 OOM。 */
    private fun decodeWithBitmapFactory(uri: Uri, sampleEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, sampleEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    /** 目标是最长边不超过 [targetEdge]，按 2 的幂次采样即可。 */
    private fun calculateInSampleSize(width: Int, height: Int, targetEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var maxEdge = maxOf(width, height)
        while (maxEdge / 2 >= targetEdge) {
            sample *= 2
            maxEdge /= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap

        val ratio = maxEdge.toFloat() / longest.toFloat()
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /** 仅 BitmapFactory 通道需要：按 EXIF 方向旋转/翻转。 */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
