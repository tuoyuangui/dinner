package com.guolongli.dinner.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.guolongli.dinner.data.media.VideoThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频封面路径：有现成的就直接给，没有就抽一帧写盘再给。
 *
 * 抽帧要解一帧 H.264，几十毫秒起，不能放主线程；但也不能放到
 * 记录映射里——那会让「刷新列表」变成「批量解码视频」。所以放在
 * 用到这张封面的 composable 里：只有真正滚到屏幕上才抽。
 *
 * 抽不出来返回 null，调用方画占位图标即可。
 */
@Composable
fun rememberVideoThumbPath(videoPath: String?): String? {
    var thumb by remember(videoPath) {
        mutableStateOf(VideoThumb.existing(videoPath)?.absolutePath)
    }
    LaunchedEffect(videoPath) {
        if (videoPath == null || thumb != null) return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) { VideoThumb.ensure(videoPath)?.absolutePath }
    }
    return thumb
}
