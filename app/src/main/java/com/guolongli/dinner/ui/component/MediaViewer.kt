package com.guolongli.dinner.ui.component

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guolongli.dinner.ui.image.LocalImage
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.Dimens
import java.io.File

/**
 * 全屏看照片 / 放视频。
 *
 * 图片用 [ContentScale.Fit]、视频用等比缩放（scale-to-fit）：
 * 整屏只做「完整展示」，宽高比保持原样，不裁剪、不变形。
 *
 * 只有本机有文件时才打得开——没下载的格子由调用方先「按需取回」（MediaRestorer.restoreOne），
 * 这里不做网络请求：看图是 UI 动作，一旦允许它自己下载，就会出现
 * 「点开一格，界面卡住等网络」体验。
 *
 * ## 为什么用 TextureView 而不是 SurfaceView
 *
 * SurfaceView 是**独立图层**：它自己开一个 surface 贴在窗口下面。
 * 放进 Compose 的 [Dialog] 里时，对话框窗口有自己的一套图层顺序，
 * SurfaceView 常常被黑色背景整个盖住——表现就是「点开视频一片黑，什么都不动」。
 * 这不是播放器坏了，是图层被遮住了（也是本项目实测踩到的问题）。
 *
 * TextureView 走普通绘制管线，就是一个普通 View，
 * 能被 Compose 正常摆放到任何位置、跟任何东西叠在一起。
 * 代价是画面要走一次 GPU 合成，1080p 在低端机上多一点开销——
 * 对「看一条短视频」这个场景完全可以接受。
 *
 * 视频用系统 MediaPlayer，不引第三方播放器（多 3~5 MB）。
 */
@Composable
fun MediaViewerDialog(
    path: String,
    isVideo: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                VideoSurface(path = path, modifier = Modifier.fillMaxSize())
            } else {
                // Fit：整图等比完整显示，不裁剪不变形
                LocalImage(
                    path = path,
                    maxPx = 2048,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = "点任意处关闭",
                style = DinnerText.footnote,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            )
            // 覆盖一层透明点击区：图片本身不消费点击，视频也要能点掉
            Box(Modifier.fillMaxSize().clickable { onDismiss() })
        }
    }
}

/**
 * 视频播放面。
 *
 * 用 [TextureView] + MediaPlayer：
 * - 画面按原始宽高比完整显示（等比缩放），不变形；
 * - 播放失败（格式不支持、文件损坏、扩展名不对）时给出文字提示，
 *   而不是留一块黑屏让人猜；
 * - 离开界面时释放 MediaPlayer，避免解码器泄漏。
 */
@Composable
private fun VideoSurface(path: String, modifier: Modifier = Modifier) {
    var error by remember(path) { mutableStateOf<String?>(null) }

    /** 视频的真实宽高比；播放器准备好之前是 null，此时按默认比例占位 */
    var ratio by remember(path) { mutableStateOf<Float?>(null) }

    val player = remember(path) { MediaPlayer() }

    // 播放器生命周期跟着这个 composable 走：离开就释放，别泄漏解码器
    DisposableEffect(path) {
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // TextureView 按视频真实比例撑开：
        // 直接 fillMaxSize 会把画面拉成整个屏幕的宽高比（竖屏视频被压扁），
        // 播放器自己的 SCALE_TO_FIT 只保证「内容不变形」，
        // 不保证「显示区域就是视频形状」——后者得靠这里的 aspectRatio。
        //
        // 比例要先算出来再进修饰符链：Modifier.then() 的参数里不能写 val 声明。
        // 另外 ratio 是委托属性（mutableStateOf），Kotlin 不允许对它 smart cast，
        // 所以先落到局部变量上再做判空。
        val currentRatio = ratio
        val scaleModifier = if (currentRatio != null && currentRatio > 0f) {
            Modifier.aspectRatio(currentRatio)
        } else {
            Modifier
        }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .then(scaleModifier),
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            runCatching {
                                player.setDataSource(File(path).absolutePath)
                                player.setSurface(Surface(texture))
                                // 等比缩放（scale-to-fit）：保持视频原始宽高比，不变形
                                player.setVideoScalingMode(
                                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT,
                                )
                                player.isLooping = true
                                player.setOnPreparedListener { it.start() }
                                // 拿到视频真实尺寸后按比例摆好，避免拉伸
                                player.setOnVideoSizeChangedListener { _, width, height ->
                                    if (width > 0 && height > 0) {
                                        ratio = width.toFloat() / height.toFloat()
                                    }
                                }
                                player.setOnErrorListener { _, what, extra ->
                                    error = "这个视频放不了（错误码 $what/$extra）"
                                    true
                                }
                                player.prepareAsync()
                            }.onFailure {
                                error = "视频读不出来：${it.message ?: it::class.java.simpleName}"
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            runCatching { player.setSurface(null) }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )

        error?.let { message ->
            Text(
                text = message,
                style = DinnerText.body,
                color = Color.White,
                modifier = Modifier.padding(Dimens.GapL),
            )
        }
    }
}
