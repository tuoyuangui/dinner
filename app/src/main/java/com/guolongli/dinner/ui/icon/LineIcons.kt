package com.guolongli.dinner.ui.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 线性图标：直接按设计稿 SVG 的 24 × 24 坐标系绘制，描边 1.8、圆头圆角。
 * 用 Canvas 重画而不是引 material-icons，是为了让线宽、端点、比例与画布完全一致。
 */
class LineIconPainter internal constructor(
    private val scope: DrawScope,
    private val scale: Float,
    private val tint: Color,
    strokeWidth: Float,
) {
    private val stroke = Stroke(
        width = strokeWidth * scale,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        scope.drawLine(
            color = tint,
            start = Offset(x1 * scale, y1 * scale),
            end = Offset(x2 * scale, y2 * scale),
            strokeWidth = stroke.width,
            cap = stroke.cap,
        )
    }

    fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false) {
        scope.drawCircle(
            color = tint,
            radius = r * scale,
            center = Offset(cx * scale, cy * scale),
            style = if (filled) Fill else stroke,
        )
    }

    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, filled: Boolean = false) {
        scope.drawRoundRect(
            color = tint,
            topLeft = Offset(x * scale, y * scale),
            size = Size(w * scale, h * scale),
            cornerRadius = CornerRadius(r * scale, r * scale),
            style = if (filled) Fill else stroke,
        )
    }

    fun path(filled: Boolean = false, block: PathScope.() -> Unit) {
        val path = Path()
        PathScope(path, scale).block()
        scope.drawPath(path = path, color = tint, style = if (filled) Fill else stroke)
    }
}

class PathScope internal constructor(private val path: Path, private val s: Float) {
    fun moveTo(x: Float, y: Float) = path.moveTo(x * s, y * s)
    fun lineTo(x: Float, y: Float) = path.lineTo(x * s, y * s)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        path.cubicTo(x1 * s, y1 * s, x2 * s, y2 * s, x3 * s, y3 * s)

    fun close() = path.close()
}

@Composable
fun LineIcon(
    spec: LineIconSpec,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Float = 1.8f,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        spec.draw.invoke(LineIconPainter(this, s, tint, strokeWidth))
    }
}

enum class LineIconSpec(internal val draw: LineIconPainter.() -> Unit) {

    /** 时间线：三横线 */
    LIST({
        line(4f, 6f, 20f, 6f)
        line(4f, 12f, 20f, 12f)
        line(4f, 18f, 13f, 18f)
    }),

    /** 日历 */
    CALENDAR({
        roundRect(3.5f, 5f, 17f, 15.5f, 2f)
        line(3.5f, 9.5f, 20.5f, 9.5f)
        line(8f, 3f, 8f, 7f)
        line(16f, 3f, 16f, 7f)
    }),

    /** 群组：两个人 */
    GROUP({
        circle(9f, 8.5f, 3f)
        path {
            moveTo(3.5f, 19f)
            cubicTo(3.5f, 16f, 5.9f, 14.2f, 9f, 14.2f)
            cubicTo(12.1f, 14.2f, 14.5f, 16f, 14.5f, 19f)
        }
        circle(16.8f, 9.2f, 2.4f)
        path {
            moveTo(16.5f, 14.4f)
            cubicTo(19.1f, 14.7f, 20.5f, 16.3f, 20.5f, 18.8f)
        }
    }),

    /** 我的 */
    USER({
        circle(12f, 8f, 3.5f)
        path {
            moveTo(5f, 20f)
            cubicTo(5f, 16.5f, 8f, 14.5f, 12f, 14.5f)
            cubicTo(16f, 14.5f, 19f, 16.5f, 19f, 20f)
        }
    }),

    CHEVRON_LEFT({
        path {
            moveTo(15f, 6f)
            lineTo(9f, 12f)
            lineTo(15f, 18f)
        }
    }),

    CHEVRON_RIGHT({
        path {
            moveTo(9f, 6f)
            lineTo(15f, 12f)
            lineTo(9f, 18f)
        }
    }),

    ARROW_LEFT({
        line(19f, 12f, 5f, 12f)
        path {
            moveTo(11f, 6f)
            lineTo(5f, 12f)
            lineTo(11f, 18f)
        }
    }),

    PLUS({
        line(12f, 5f, 12f, 19f)
        line(5f, 12f, 19f, 12f)
    }),

    /** 管理员口令未解锁 */
    LOCK({
        roundRect(5f, 11f, 14f, 9f, 2f)
        path {
            moveTo(8f, 11f)
            lineTo(8f, 8f)
            cubicTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
            cubicTo(14.2f, 4f, 16f, 5.8f, 16f, 8f)
            lineTo(16f, 11f)
        }
    }),

    FOLDER({
        path {
            moveTo(3.5f, 7f)
            cubicTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
            lineTo(9.5f, 5f)
            lineTo(11.5f, 7.5f)
            lineTo(18.5f, 7.5f)
            cubicTo(19.6f, 7.5f, 20.5f, 8.4f, 20.5f, 9.5f)
            lineTo(20.5f, 17f)
            cubicTo(20.5f, 18.1f, 19.6f, 19f, 18.5f, 19f)
            lineTo(5.5f, 19f)
            cubicTo(4.4f, 19f, 3.5f, 18.1f, 3.5f, 17f)
            close()
        }
    }),

    COMPRESS({
        line(4f, 9f, 4f, 4f)
        line(4f, 4f, 9f, 4f)
        line(20f, 9f, 20f, 4f)
        line(20f, 4f, 15f, 4f)
        line(4f, 15f, 4f, 20f)
        line(4f, 20f, 9f, 20f)
        line(20f, 15f, 20f, 20f)
        line(20f, 20f, 15f, 20f)
    }),

    CLOUD({
        path {
            moveTo(7f, 18f)
            cubicTo(4.7f, 18f, 3f, 16.3f, 3f, 14.1f)
            cubicTo(3f, 12f, 4.6f, 10.2f, 6.6f, 10.1f)
            cubicTo(7.2f, 7.4f, 9.6f, 5.5f, 12.4f, 5.5f)
            cubicTo(15.6f, 5.5f, 18.2f, 8f, 18.2f, 11.1f)
            cubicTo(19.9f, 11.7f, 21f, 13.3f, 21f, 15.1f)
            cubicTo(21f, 16.7f, 19.8f, 18f, 18.2f, 18f)
            close()
        }
    }),

    DOWNLOAD_TRAY({
        line(12f, 3f, 12f, 13f)
        path {
            moveTo(8.5f, 9.5f)
            lineTo(12f, 13f)
            lineTo(15.5f, 9.5f)
        }
        path {
            moveTo(4f, 17f)
            lineTo(4f, 19f)
            cubicTo(4f, 20.1f, 4.9f, 21f, 6f, 21f)
            lineTo(18f, 21f)
            cubicTo(19.1f, 21f, 20f, 20.1f, 20f, 19f)
            lineTo(20f, 17f)
        }
    }),

    SHIELD({
        path {
            moveTo(12f, 3f)
            lineTo(19f, 6f)
            lineTo(19f, 11.2f)
            cubicTo(19f, 15.6f, 16f, 18.5f, 12f, 20f)
            cubicTo(8f, 18.5f, 5f, 15.6f, 5f, 11.2f)
            lineTo(5f, 6f)
            close()
        }
    }),

    SUN({
        circle(12f, 12f, 4f)
        line(12f, 3f, 12f, 5f)
        line(12f, 19f, 12f, 21f)
        line(3f, 12f, 5f, 12f)
        line(19f, 12f, 21f, 12f)
        line(5.6f, 5.6f, 7f, 7f)
        line(17f, 17f, 18.4f, 18.4f)
        line(18.4f, 5.6f, 17f, 7f)
        line(7f, 17f, 5.6f, 18.4f)
    }),

    INFO({
        circle(12f, 12f, 8.5f)
        circle(12f, 8f, 0.6f, filled = true)
        line(12f, 11.5f, 12f, 16f)
    }),

    /** 媒体占位里的图片符号 */
    IMAGE({
        roundRect(3f, 5f, 18f, 14f, 2f)
        circle(8.5f, 10f, 1.5f, filled = true)
        path {
            moveTo(4.5f, 18.2f)
            lineTo(9.6f, 12.6f)
            lineTo(13f, 16f)
            lineTo(15.8f, 13.2f)
            lineTo(19.5f, 17f)
        }
    }),

    /** 视频没有封面时的播放符号：圆里一个右向三角 */
    PLAY({
        circle(12f, 12f, 8.5f)
        path(filled = true) {
            moveTo(10.4f, 8.6f)
            lineTo(16f, 12f)
            lineTo(10.4f, 15.4f)
            close()
        }
    }),

    /** 云端整理：垃圾桶（桶身收一点下缘，比直上直下的方块更像设计稿里的图标） */
    TRASH({
        line(4f, 6.5f, 20f, 6.5f)
        line(9.5f, 6.5f, 9.5f, 4.6f)
        line(9.5f, 4.6f, 14.5f, 4.6f)
        line(14.5f, 4.6f, 14.5f, 6.5f)
        path {
            moveTo(6f, 6.5f)
            lineTo(6.8f, 19.5f)
            cubicTo(6.86f, 20.4f, 7.5f, 21f, 8.3f, 21f)
            lineTo(15.7f, 21f)
            cubicTo(16.5f, 21f, 17.14f, 20.4f, 17.2f, 19.5f)
            lineTo(18f, 6.5f)
        }
        line(10.2f, 10f, 10.5f, 17.5f)
        line(13.8f, 10f, 13.5f, 17.5f)
    }),
}
