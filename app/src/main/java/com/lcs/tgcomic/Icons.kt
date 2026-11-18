package com.lcs.tgcomic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘线性图标：统一 1.9dp 描边、圆头、24dp 画布比例。
 * 比字体符号（▦ ⚙ ◷）稳定：粗细一致、基线一致、不依赖系统 emoji 字体。
 */
enum class Glyph {
    SHELF, HISTORY, ADD, TUNE,
    GRID, LIST_LINES, MORE_DOTS, BACK, SEARCH, CLOSE, CHECK,
    STAR, STAR_OUTLINE, CACHE, TRASH, SHARE, SUN, EDIT, PLAY, INFO, COPY, EXPORT, REFRESH
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeScale: Float = 1f
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.082f * strokeScale
        val cap = StrokeCap.Round
        val stroke = Stroke(width = sw, cap = cap)
        val u = s // 便于按比例书写
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        when (glyph) {
            // 三本书（最后一本斜放）
            Glyph.SHELF -> {
                drawRoundRect(
                    color, topLeft = p(0.06f, 0.20f), size = Size(0.17f * u, 0.60f * u),
                    cornerRadius = CornerRadius(0.035f * u), style = stroke
                )
                drawRoundRect(
                    color, topLeft = p(0.29f, 0.32f), size = Size(0.17f * u, 0.48f * u),
                    cornerRadius = CornerRadius(0.035f * u), style = stroke
                )
                rotate(degrees = 8f, pivot = p(0.63f, 0.55f)) {
                    drawRoundRect(
                        color, topLeft = p(0.52f, 0.22f), size = Size(0.17f * u, 0.58f * u),
                        cornerRadius = CornerRadius(0.035f * u), style = stroke
                    )
                }
            }

            // 时钟
            Glyph.HISTORY -> {
                drawCircle(color, radius = 0.33f * u, center = p(0.5f, 0.5f), style = stroke)
                drawLine(color, p(0.5f, 0.5f), p(0.5f, 0.29f), sw, cap)
                drawLine(color, p(0.5f, 0.5f), p(0.69f, 0.57f), sw, cap)
            }

            // 加号
            Glyph.ADD -> {
                drawLine(color, p(0.20f, 0.5f), p(0.80f, 0.5f), sw * 1.15f, cap)
                drawLine(color, p(0.5f, 0.20f), p(0.5f, 0.80f), sw * 1.15f, cap)
            }

            // 三档滑块
            Glyph.TUNE -> {
                val ys = listOf(0.28f, 0.5f, 0.72f)
                val knobs = listOf(0.34f, 0.66f, 0.46f)
                ys.forEachIndexed { i, y ->
                    drawLine(color, p(0.10f, y), p(0.90f, y), sw * 0.85f, cap)
                    drawCircle(color, radius = 0.072f * u, center = p(knobs[i], y))
                }
            }

            // 网格
            Glyph.GRID -> {
                val cell = 0.32f * u
                val gap = 0.09f * u
                listOf(0f, 1f).forEach { cx ->
                    listOf(0f, 1f).forEach { cy ->
                        drawRoundRect(
                            color,
                            topLeft = p(0.135f + cx * (cell / u + gap / u), 0.135f + cy * (cell / u + gap / u)),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(0.05f * u),
                            style = stroke
                        )
                    }
                }
            }

            // 列表
            Glyph.LIST_LINES -> {
                val ys = listOf(0.26f, 0.5f, 0.74f)
                ys.forEach { y ->
                    drawCircle(color, radius = 0.055f * u, center = p(0.20f, y))
                    drawLine(color, p(0.36f, y), p(0.84f, y), sw * 0.95f, cap)
                }
            }

            // 三个点
            Glyph.MORE_DOTS -> {
                listOf(0.24f, 0.5f, 0.76f).forEach { x ->
                    drawCircle(color, radius = 0.062f * u, center = p(x, 0.5f))
                }
            }

            // 返回
            Glyph.BACK -> {
                drawLine(color, p(0.56f, 0.22f), p(0.28f, 0.5f), sw, cap)
                drawLine(color, p(0.28f, 0.5f), p(0.56f, 0.78f), sw, cap)
            }

            // 搜索
            Glyph.SEARCH -> {
                drawCircle(color, radius = 0.26f * u, center = p(0.44f, 0.44f), style = stroke)
                drawLine(color, p(0.63f, 0.63f), p(0.80f, 0.80f), sw, cap)
            }

            // 关闭
            Glyph.CLOSE -> {
                drawLine(color, p(0.28f, 0.28f), p(0.72f, 0.72f), sw, cap)
                drawLine(color, p(0.72f, 0.28f), p(0.28f, 0.72f), sw, cap)
            }

            // 对勾
            Glyph.CHECK -> {
                drawLine(color, p(0.24f, 0.52f), p(0.43f, 0.71f), sw, cap)
                drawLine(color, p(0.43f, 0.71f), p(0.77f, 0.30f), sw, cap)
            }

            // 实心星
            Glyph.STAR -> drawPath(starPath(0.5f * u, 0.5f * u, 0.38f * u, 0.16f * u), color)

            // 空星
            Glyph.STAR_OUTLINE -> drawPath(
                path = starPath(0.5f * u, 0.5f * u, 0.38f * u, 0.16f * u),
                color = color,
                style = stroke
            )

            // 下载
            Glyph.CACHE -> {
                drawLine(color, p(0.5f, 0.18f), p(0.5f, 0.62f), sw, cap)
                drawLine(color, p(0.32f, 0.46f), p(0.5f, 0.64f), sw, cap)
                drawLine(color, p(0.68f, 0.46f), p(0.5f, 0.64f), sw, cap)
                drawLine(color, p(0.24f, 0.80f), p(0.76f, 0.80f), sw, cap)
            }

            // 垃圾桶
            Glyph.TRASH -> {
                drawLine(color, p(0.20f, 0.30f), p(0.80f, 0.30f), sw, cap)
                drawRoundRect(
                    color, topLeft = p(0.28f, 0.30f), size = Size(0.44f * u, 0.50f * u),
                    cornerRadius = CornerRadius(0.06f * u), style = stroke
                )
                drawLine(color, p(0.42f, 0.20f), p(0.58f, 0.20f), sw, cap)
            }

            // 铅笔
            Glyph.EDIT -> {
                rotate(degrees = -45f, pivot = p(0.5f, 0.5f)) {
                    drawRoundRect(
                        color, topLeft = p(0.46f, 0.16f), size = Size(0.16f * u, 0.50f * u),
                        cornerRadius = CornerRadius(0.03f * u), style = stroke
                    )
                    drawLine(color, p(0.46f, 0.66f), p(0.62f, 0.66f), sw, cap)
                    drawLine(color, p(0.54f, 0.66f), p(0.54f, 0.80f), sw, cap)
                }
            }

            // 播放（三角）
            Glyph.PLAY -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0.36f * u, 0.26f * u)
                    lineTo(0.76f * u, 0.50f * u)
                    lineTo(0.36f * u, 0.74f * u)
                    close()
                }
                drawPath(path = path, color = color)
            }

            // 信息
            Glyph.INFO -> {
                drawCircle(color, radius = 0.34f * u, center = p(0.5f, 0.5f), style = stroke)
                drawCircle(color, radius = 0.045f * u, center = p(0.5f, 0.34f))
                drawLine(color, p(0.5f, 0.46f), p(0.5f, 0.68f), sw, cap)
            }

            // 复制
            Glyph.COPY -> {
                drawRoundRect(
                    color, topLeft = p(0.30f, 0.30f), size = Size(0.42f * u, 0.42f * u),
                    cornerRadius = CornerRadius(0.07f * u), style = stroke
                )
                drawLine(color, p(0.24f, 0.68f), p(0.24f, 0.28f), sw, cap)
                drawLine(color, p(0.24f, 0.28f), p(0.62f, 0.28f), sw, cap)
            }

            // 导出（上箭头 + 托盘）
            Glyph.EXPORT -> {
                drawLine(color, p(0.5f, 0.66f), p(0.5f, 0.20f), sw, cap)
                drawLine(color, p(0.33f, 0.37f), p(0.5f, 0.20f), sw, cap)
                drawLine(color, p(0.67f, 0.37f), p(0.5f, 0.20f), sw, cap)
                drawLine(color, p(0.24f, 0.62f), p(0.24f, 0.82f), sw, cap)
                drawLine(color, p(0.24f, 0.82f), p(0.76f, 0.82f), sw, cap)
                drawLine(color, p(0.76f, 0.82f), p(0.76f, 0.62f), sw, cap)
            }

            // 刷新（圆弧 + 箭头）
            Glyph.REFRESH -> {
                drawArc(
                    color = color,
                    startAngle = -60f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = p(0.18f, 0.18f),
                    size = Size(0.64f * u, 0.64f * u),
                    style = stroke
                )
                rotate(degrees = -60f, pivot = p(0.5f, 0.5f)) {
                    drawLine(color, p(0.50f, 0.18f), p(0.68f, 0.24f), sw, cap)
                    drawLine(color, p(0.50f, 0.18f), p(0.52f, 0.36f), sw, cap)
                }
            }

            // 太阳（亮度）
            Glyph.SUN -> {
                drawCircle(color, radius = 0.155f * u, center = p(0.5f, 0.5f), style = stroke)
                for (i in 0 until 8) {
                    val a = (i * 45f) * (Math.PI / 180f).toFloat()
                    val x1 = 0.5f + 0.27f * kotlin.math.cos(a)
                    val y1 = 0.5f + 0.27f * kotlin.math.sin(a)
                    val x2 = 0.5f + 0.40f * kotlin.math.cos(a)
                    val y2 = 0.5f + 0.40f * kotlin.math.sin(a)
                    drawLine(color, p(x1, y1), p(x2, y2), sw * 0.88f, cap)
                }
            }

            // 分享
            Glyph.SHARE -> {
                drawLine(color, p(0.5f, 0.62f), p(0.5f, 0.16f), sw, cap)
                drawLine(color, p(0.34f, 0.32f), p(0.5f, 0.16f), sw, cap)
                drawLine(color, p(0.66f, 0.32f), p(0.5f, 0.16f), sw, cap)
                drawLine(color, p(0.24f, 0.52f), p(0.24f, 0.82f), sw, cap)
                drawLine(color, p(0.24f, 0.82f), p(0.76f, 0.82f), sw, cap)
                drawLine(color, p(0.76f, 0.82f), p(0.76f, 0.52f), sw, cap)
            }
        }
    }
}

private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float) =
    androidx.compose.ui.graphics.Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = (-90f + i * 36f) * (Math.PI / 180f).toFloat()
            val x = cx + r * kotlin.math.cos(a)
            val y = cy + r * kotlin.math.sin(a)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
