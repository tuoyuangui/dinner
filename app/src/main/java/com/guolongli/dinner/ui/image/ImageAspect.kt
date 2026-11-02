package com.guolongli.dinner.ui.image

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 读一张本地图片的宽高比（宽 ÷ 高）。
 *
 * 只读文件头，不解码像素，所以很快——但也必须在 IO 线程做，
 * 否则列表滚动时会掉帧。
 *
 * 为什么需要它：**单张图片要保持原比例**，
 * 光靠 [androidx.compose.ui.layout.ContentScale.Fit] 只是「不裁切」，
 * 画面的实际显示高度还是由容器决定的，容器给多少就是多少——
 * 一张竖图放进一个扁扁的格子里，上下就留出两条大黑边。
 * 拿到真实宽高比之后按比例给容器定高，才是真正的「保持比例」。
 */
@Composable
fun rememberImageAspectRatio(path: String?, maxEdge: Int = 512): Float? {
    var ratio by remember(path) { mutableStateOf<Float?>(null) }
    LaunchedEffect(path) {
        if (path.isNullOrBlank()) {
            ratio = null
            return@LaunchedEffect
        }
        ratio = withContext(Dispatchers.IO) { aspectRatioOf(path, maxEdge) }
    }
    return ratio
}

/** 读宽高比；读不出来返回 null（调用方按「不知道比例」处理） */
private fun aspectRatioOf(path: String, maxEdge: Int): Float? {
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return null
    return runCatching {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth.toFloat() / options.outHeight.toFloat()
        } else {
            null
        }
    }.getOrNull()
}
