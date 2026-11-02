package com.guolongli.dinner.ui.components

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 全屏视频播放器。
 *
 * 用系统 VideoView 而不是引入 Media3：这里只需要播放本地文件，
 * VideoView 自带控制器与硬件解码链路，且不增加包体积与依赖维护成本。
 * 播放结束 / 关闭时主动 stopPlayback，避免 MediaPlayer 继续占用解码器。
 */
@Composable
fun VideoPlayerDialog(
    path: String,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    // 全屏是近黑底，前景一律用语义的「压在媒体上」色，不硬写 Color.White。
    val foreground = DinnerTheme.colors.onMedia

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var videoView by remember { mutableStateOf<VideoView?>(null) }

        DisposableEffect(Unit) {
            onDispose {
                videoView?.stopPlayback()
                videoView = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim),
        ) {
            var loading by remember { mutableStateOf(true) }
            var failed by remember { mutableStateOf(false) }

            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        val controller = MediaController(context)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { player ->
                            loading = false
                            player.isLooping = false
                            controller.show()
                            start()
                        }
                        setOnErrorListener { _, _, _ ->
                            loading = false
                            failed = true
                            true
                        }
                        setVideoPath(path)
                        videoView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (loading && !failed) {
                CircularProgressIndicator(
                    color = foreground,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (failed) {
                Text(
                    text = "视频无法播放，可能文件已损坏",
                    color = foreground,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(DinnerDimens.spaceXs),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭播放",
                    tint = foreground,
                )
            }
        }
    }
}
