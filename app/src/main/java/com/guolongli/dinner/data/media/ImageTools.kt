package com.guolongli.dinner.data.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片处理。工程里不放图片加载库（Coil / Glide 都太重），
 * 需要的地方少且明确：入库压缩、头像裁方、列表缩略图。
 * 全部按「先算采样率再解码」写，避免把 4000 万像素的图整张读进内存。
 */
object ImageTools {

    /** 读一张图的宽高，不解码像素 */
    fun boundsOf(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    /** 按最长边限制解码，返回 null 表示不是图片或读失败 */
    fun decodeFile(file: File, maxEdge: Int): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        val size = boundsOf(file) ?: return null
        val sample = sampleSize(size.first, size.second, maxEdge)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        // 采样只能按 2 的幂降，剩下的用一次精确缩放补齐
        return scaleToFit(bitmap, maxEdge)
    }

    /** 从相册 Uri 读一张图，限制最长边后按 EXIF 摆正 */
    fun loadScaled(resolver: ContentResolver, uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = resolver.openInputStream(uri)?.use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            if (options.outWidth > 0) options.outWidth to options.outHeight else null
        } ?: return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.first, bounds.second, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val scaled = scaleToFit(bitmap, maxEdge) ?: bitmap
        return applyExif(resolver, uri, scaled)
    }

    /** 头像：居中裁成正方形再缩到 size，返回 JPEG 字节 */
    fun squareJpeg(resolver: ContentResolver, uri: Uri, size: Int, quality: Int = 90): ByteArray? {
        val source = loadScaled(resolver, uri, size * 2) ?: return null
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side,
        )
        val target = if (side == size) {
            square
        } else {
            Bitmap.createScaledBitmap(square, size, size, true)
        }
        return ByteArrayOutputStream().use { output ->
            target.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (target !== square) target.recycle()
            if (square !== source) square.recycle()
            source.recycle()
            output.toByteArray()
        }
    }

    /** 照片入库：长边压到 maxEdge，JPEG 质量 88 */
    fun compressToJpeg(bitmap: Bitmap, quality: Int = 88): ByteArray =
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap? {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * 手机竖着拍的照片，像素其实是横的，方向信息放在 EXIF 里。
     * 相册应用会读它，我们自己解码就必须手动转，否则入库的照片全是躺着的。
     */
    private fun applyExif(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: return bitmap

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
