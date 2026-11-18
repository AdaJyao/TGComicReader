package com.lcs.tgcomic

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ══════════════════════════════════════════════════════════
//  液态玻璃材质
// ══════════════════════════════════════════════════════════

/**
 * 液态玻璃层：高光扫掠 + 双色折射 + 渐变描边 + 内阴影。
 * 纯 Canvas 实现，不依赖 RenderEffect，全 API 版本一致。
 */
/**
 * 统一的「表面」材质：纯色卡片 + 细描边 + 极淡阴影（Material 3 tonal 风格）。
 * 名字保留 liquidGlass 以兼容既有调用，但已不再是毛玻璃。
 */
fun Modifier.liquidGlass(
    shape: Shape = Glass.R24,
    cornerRadius: Dp = 26.dp,
    elevation: Dp = 20.dp,
    glow: Color = Color.Transparent,
    borderWidth: Dp = 1.dp,
    tintScale: Float = 1f,
    topEdge: Boolean = false
): Modifier = this
    .then(
        if (elevation > 0.dp) Modifier.shadow(
            elevation = elevation.coerceAtMost(3.dp),
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.45f),
            spotColor = Color.Black.copy(alpha = 0.35f)
        ) else Modifier
    )
    .clip(shape)
    .background(Glass.palette.surface)
    .border(borderWidth, Glass.palette.outline, shape)
/** 页面背景：Material 3 纯色（跟随主题） */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val pal = Glass.palette
    Box(modifier.fillMaxSize().background(pal.bg)) {
        content()
    }
}

// ══════════════════════════════════════════════════════════
//  基础玻璃控件
// ══════════════════════════════════════════════════════════

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Glass.R22,
    cornerRadius: Dp = 22.dp,
    elevation: Dp = 16.dp,
    glow: Color = Color.Transparent,
    tintScale: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier
        .liquidGlass(shape, cornerRadius, elevation, glow, tintScale = tintScale)
    Column(if (onClick != null) base.clickable { onClick() } else base) { content() }
}

@Composable
fun GlassPill(
    text: String,
    selected: Boolean = false,
    accent: Color = Glass.Accent,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        filled -> accent.copy(alpha = if (enabled) 1f else 0.35f)
        selected -> accent.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(Glass.Pill)
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            fontWeight = if (filled || selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                !enabled && filled -> Glass.OnAccent.copy(alpha = 0.6f)
                !enabled -> Glass.TextTertiary
                filled -> Glass.OnAccent
                selected -> accent
                else -> Glass.TextDim
            }
        )
    }
}

@Composable
fun GlassSegmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    accent: Color = Glass.Accent,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = modifier
            .liquidGlass(Glass.Pill, cornerRadius = 40.dp, elevation = 10.dp, borderWidth = 1.dp, topEdge = false)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { i, label ->
            GlassPill(label, selected = i == selected, accent = accent, onClick = { onSelect(i) })
        }
    }
}

@Composable
fun GlassSymbolButton(
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = Glass.Text,
    glow: Color = Color.Transparent
) {
    Box(
        modifier = modifier
            .size(size)
            .liquidGlass(Glass.Pill, cornerRadius = size / 2, elevation = 8.dp, glow = glow, topEdge = false)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = (size.value * 0.42f).sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun GlassTopBar(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = Brush.linearGradient(Glass.palette.titleGradient)
                )
            )
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Glass.TextDim)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trailing()
        }
    }
}

@Composable
fun GlassFab(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = Glass.Accent,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(Glass.Pill)
            .background(accent)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(Glyph.ADD, color = Glass.OnAccent, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.5.sp, color = Glass.OnAccent, fontWeight = FontWeight.SemiBold)
    }
}
/** 环形进度（缓存用） */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 34.dp,
    color: Color = Glass.Accent,
    track: Color = Glass.Track
) {
    Canvas(modifier.size(diameter)) {
        val stroke = size.minDimension * 0.10f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}

/** 空状态 */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    accent: Color = Glass.Accent,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(132.dp, 108.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = w * 0.55f
                ),
                radius = w * 0.55f,
                center = Offset(cx, cy)
            )
            // 叠放的三张“空页”
            repeat(3) { i ->
                val off = (2 - i) * 5.dp.toPx()
                val alpha = 0.14f + i * 0.10f
                drawRoundRect(
                    color = Glass.Text.copy(alpha = alpha),
                    topLeft = Offset(cx - w * 0.21f + off * 0.4f, cy - h * 0.27f + off),
                    size = Size(w * 0.42f, h * 0.54f),
                    cornerRadius = CornerRadius(10.dp.toPx())
                )
            }
            drawRoundRect(
                color = accent.copy(alpha = 0.55f),
                topLeft = Offset(cx - w * 0.21f - 6.dp.toPx(), cy - h * 0.27f - 6.dp.toPx()),
                size = Size(w * 0.42f, h * 0.54f),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(1.4.dp.toPx())
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Glass.Text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = Glass.TextDim,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** 细描边玻璃条（顶/底浮条用） */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .liquidGlass(Glass.R24, cornerRadius = cornerRadius, elevation = 18.dp, borderWidth = 1.dp, topEdge = false)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

// ══════════════════════════════════════════════════════════
//  底部导航 / 设置项
// ══════════════════════════════════════════════════════════

data class NavItem(val glyph: Glyph, val label: String)

@Composable
fun GlassBottomNav(
    items: List<NavItem>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Column(modifier.fillMaxWidth().background(Glass.palette.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.palette.outline))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, item ->
                val active = i == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(Glass.R14)
                        .clickable { onSelect(i) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .height(30.dp)
                            .width(52.dp)
                            .clip(Glass.Pill)
                            .background(if (active) Glass.Accent.copy(alpha = 0.14f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        GlyphIcon(
                            glyph = item.glyph,
                            color = if (active) Glass.Accent else Glass.TextDim,
                            size = 20.dp
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) Glass.Accent else Glass.TextDim
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 22.dp, top = 16.dp, bottom = 8.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Glass.TextTertiary,
        letterSpacing = 0.6.sp
    )
}

@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() } else Modifier
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            color = if (danger) Glass.Bad else Glass.Text,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(value, fontSize = 12.sp, color = Glass.TextDim)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp)
            .height(1.dp)
            .background(Glass.Divider)
    )
}

/** 矢量图标按钮（替代字体符号，线条统一） */
@Composable
fun GlassIconButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = Glass.Text,
    glow: Color = Color.Transparent
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(Glass.Pill)
            .background(Glass.palette.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(glyph = glyph, color = tint, size = size * 0.46f)
    }
}