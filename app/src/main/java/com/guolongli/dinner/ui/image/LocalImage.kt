package com.guolongli.dinner.ui.image

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.guolongli.dinner.data.media.ImageTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地图片的读取与缓存。
 *
 * 只做一件事：把私有目录里的文件按需解码成缩略图并缓存住。
 * 列表滚动时同一张图会被反复请求，所以缓存键是「路径 + 目标尺寸」——
 * 头像要 512、九宫格要 360、编辑页要 640，各自缓存互不干扰。
 *
 * 解码放在 IO 线程，主线程只负责把结果贴上去，滚动不会掉帧。
 */
object PhotoCache {

    private const val MAX_BYTES = 32 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.height * value.width * 4
    }

    fun cached(path: String, maxPx: Int): ImageBitmap? = cache.get(key(path, maxPx))

    fun load(path: String, maxPx: Int): ImageBitmap? {
        val key = key(path, maxPx)
        cache.get(key)?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        val bitmap: Bitmap = ImageTools.decodeFile(file, maxPx) ?: return null
        val image = bitmap.asImageBitmap()
        cache.put(key, image)
        return image
    }

    fun clear() = cache.evictAll()

    private fun key(path: String, maxPx: Int) = "$path@$maxPx"
}

/** 读本地图片；路径变了或文件不在都安全，返回 null 交给调用方画占位 */
@Composable
fun rememberLocalImage(path: String?, maxPx: Int = 640): ImageBitmap? {
    var image by remember(path, maxPx) { mutableStateOf(path?.let { PhotoCache.cached(it, maxPx) }) }
    LaunchedEffect(path, maxPx) {
        if (path == null) {
            image = null
            return@LaunchedEffect
        }
        image = PhotoCache.cached(path, maxPx)
            ?: withContext(Dispatchers.IO) { PhotoCache.load(path, maxPx) }
    }
    return image
}

/**
 * 铺满父级的一张本地图片。
 * 默认 [ContentScale.Fit]：等比完整显示、保持原始宽高比（不裁剪、不变形）；
 * 头像这类需要填满圆形的场景由调用方显式传 [ContentScale.Crop]。
 */
@Composable
fun LocalImage(
    path: String?,
    modifier: Modifier = Modifier,
    maxPx: Int = 640,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val image = rememberLocalImage(path, maxPx) ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}
