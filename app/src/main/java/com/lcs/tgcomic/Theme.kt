package com.lcs.tgcomic

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ══════════════════════════════════════════════════════════
//  主题
// ══════════════════════════════════════════════════════════

enum class AppTheme(val label: String, val dark: Boolean) {
    AURORA("墨蓝", true),
    OLED("纯黑", true),
    VIOLET("靛紫", true),
    FOREST("森绿", true),
    AMBER("落日", true),
    ROSE("玫瑰", true),
    PAPER("浅色", false),
    SAND("沙金", false),
    SYSTEM("跟随系统", true)
}

/**
 * 一套完整的语义色板。
 * 三套主题共用同一套“角色”，只是明暗与冷暖不同，保证切换时观感一致。
 */
data class GlassPalette(
    val dark: Boolean,
    val accent: Color,
    val accent2: Color,
    val accent3: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val text: Color,
    val textDim: Color,
    val textTertiary: Color,
    val bg: Color,
    val body: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val blob1: Color,
    val blob2: Color,
    val blob3: Color,
    val blobAlpha1: Float,
    val blobAlpha2: Float,
    val blobAlpha3: Float,
    val veil: Color,
    val veilAlpha: Float,
    val topGlow: Color,
    val glassSheen: Float,
    val glassShade: Float,
    val textTertiaryColor: Color = Color.Unspecified,
    val dividerColor: Color = Color.Unspecified,
    val onAccentColor: Color = Color.Unspecified
) {
    /** 弹层底色 */
    val panelTop: Color get() = if (dark) Color(0xFF20242D) else Color(0xFFFFFFFF)
    val panelMid: Color get() = if (dark) Color(0xFF1A1E26) else Color(0xFFF9FAFD)
    val panelBottom: Color get() = if (dark) Color(0xFF161A21) else Color(0xFFF1F3F8)
    val panelBorder: Color get() = if (dark) Color(0x1FFFFFFF) else Color(0x14000000)

    /** 遮罩 */
    val scrim: Color get() = if (dark) Color(0x99080A10) else Color(0x4D2A3242)

    /** 顶栏标题渐变（保留一点品牌感，但克制） */
    val titleGradient: List<Color>
        get() = if (dark) listOf(Color(0xFFF1F3F8), Color(0xFFCDD4E4), Color(0xFFB4BEE0))
        else listOf(Color(0xFF15181F), Color(0xFF2C3A5C), Color(0xFF3A2E6B))

    /** 搜索/输入框填充 */
    val fieldFill: Color get() = if (dark) surfaceVariant else Color(0xFFFFFFFF)

    /** 弹窗内的输入区（比弹窗底色更暗，形成内凹层次） */
    val inputField: Color get() = if (dark) Color(0xFF12151B) else Color(0xFFF1F3F8)

    /** 底部导航底色 */
    val navBar: Color get() = if (dark) Color(0xFF13161C) else Color(0xFFFFFFFF)

    /** 卡片实体色 */
    val card: Color get() = surface

    /** 进度条 / 滑块轨道 */
    val track: Color get() = if (dark) Color(0x24FFFFFF) else Color(0x14000000)

    /** 底部弹层拖拽条 */
    val handle: Color get() = if (dark) Color(0x59FFFFFF) else Color(0x33000000)

    /** 遮罩层上的描边（封面、缩略图） */
    val mediaStroke: Color get() = if (dark) Color(0x33FFFFFF) else Color(0x12000000)
}

// ── 墨蓝（默认深色）──────────────────────────────────────
val InkPalette = GlassPalette(
    dark = true,
    accent = Color(0xFF8296F5),
    accent2 = Color(0xFFC08CF0),
    accent3 = Color(0xFFF08AB8),
    good = Color(0xFF6FD79E),
    warn = Color(0xFFE6C36A),
    bad = Color(0xFFEF8A8A),
    text = Color(0xFFEAECF2),
    textDim = Color(0xFFA3A9B6),
    textTertiary = Color(0xFF6B7280),
    bg = Color(0xFF0B0D12),
    body = Color(0xFF15181F),
    borderTop = Color(0xFF1FFFFFFF),
    borderBottom = Color(0xFF12FFFFFF),
    surface = Color(0xFF15181F),
    surfaceVariant = Color(0xFF1E222B),
    outline = Color(0xFF1FFFFFFF),
    bgTop = Color(0xFF0B0D12),
    bgMid = Color(0xFF0B0D12),
    bgBottom = Color(0xFF0B0D12),
    blob1 = Color(0xFF8296F5),
    blob2 = Color(0xFFC08CF0),
    blob3 = Color(0xFF8296F5),
    blobAlpha1 = 0f,
    blobAlpha2 = 0f,
    blobAlpha3 = 0f,
    veil = Color(0xFF0B0D12),
    veilAlpha = 0f,
    topGlow = Color(0x00000000),
    glassSheen = 1f,
    glassShade = 0.15f
)

// ── 纯黑（OLED）──────────────────────────────────────────
val OledPalette = InkPalette.copy(
    bg = Color(0xFF000000),
    surface = Color(0xFF0E1015),
    surfaceVariant = Color(0xFF171A21),
    body = Color(0xFF0E1015),
    bgTop = Color(0xFF000000),
    bgMid = Color(0xFF000000),
    bgBottom = Color(0xFF000000),
    veil = Color(0xFF000000),

)

// ── 浅色 ────────────────────────────────────────────────
val PaperPalette = GlassPalette(
    dark = false,
    accent = Color(0xFF3355D1),
    accent2 = Color(0xFF7B4FD6),
    accent3 = Color(0xFFC0457F),
    good = Color(0xFF12855A),
    warn = Color(0xFF96660A),
    bad = Color(0xFFC0392B),
    text = Color(0xFF14171D),
    textDim = Color(0xFF525864),
    textTertiary = Color(0xFF878E9B),
    bg = Color(0xFFF4F6FA),
    body = Color(0xFFFFFFFF),
    borderTop = Color(0xFFFFFFFF),
    borderBottom = Color(0xFFE8EBF2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F6),
    outline = Color(0xFFE2E6EE),
    bgTop = Color(0xFFF4F6FA),
    bgMid = Color(0xFFF4F6FA),
    bgBottom = Color(0xFFF4F6FA),
    blob1 = Color(0xFF3355D1),
    blob2 = Color(0xFF7B4FD6),
    blob3 = Color(0xFF3355D1),
    blobAlpha1 = 0f,
    blobAlpha2 = 0f,
    blobAlpha3 = 0f,
    veil = Color(0xFFF4F6FA),
    veilAlpha = 0f,
    topGlow = Color(0x00000000),
    glassSheen = 1f,
    glassShade = 0f
)

// ── 其余深色主题：沿用墨蓝的层级，只换强调色与背景色温 ──
val VioletPalette = InkPalette.copy(
    accent = Color(0xFF9B7BF7),
    accent2 = Color(0xFF6FA8F5),
    accent3 = Color(0xFFD982C8),
    bg = Color(0xFF0C0A14),
    body = Color(0xFF14121C),
    surface = Color(0xFF16131F),
    surfaceVariant = Color(0xFF1D1928),
    bgTop = Color(0xFF0C0A14),
    bgMid = Color(0xFF0C0A14),
    bgBottom = Color(0xFF0C0A14),
    veil = Color(0xFF0C0A14)
)

val ForestPalette = InkPalette.copy(
    accent = Color(0xFF43C08D),
    accent2 = Color(0xFF7FD3A8),
    accent3 = Color(0xFFC8B65A),
    bg = Color(0xFF08110D),
    body = Color(0xFF0E1713),
    surface = Color(0xFF101A15),
    surfaceVariant = Color(0xFF16211B),
    bgTop = Color(0xFF08110D),
    bgMid = Color(0xFF08110D),
    bgBottom = Color(0xFF08110D),
    veil = Color(0xFF08110D)
)

val AmberPalette = InkPalette.copy(
    accent = Color(0xFFE09A46),
    accent2 = Color(0xFFE8C46A),
    accent3 = Color(0xFFCF6E58),
    bg = Color(0xFF110D08),
    body = Color(0xFF191410),
    surface = Color(0xFF1B1611),
    surfaceVariant = Color(0xFF221C15),
    bgTop = Color(0xFF110D08),
    bgMid = Color(0xFF110D08),
    bgBottom = Color(0xFF110D08),
    veil = Color(0xFF110D08)
)

val RosePalette = InkPalette.copy(
    accent = Color(0xFFE0709E),
    accent2 = Color(0xFFF09C7A),
    accent3 = Color(0xFF9C7BE0),
    bg = Color(0xFF12090E),
    body = Color(0xFF1A1016),
    surface = Color(0xFF1C1118),
    surfaceVariant = Color(0xFF24161F),
    bgTop = Color(0xFF12090E),
    bgMid = Color(0xFF12090E),
    bgBottom = Color(0xFF12090E),
    veil = Color(0xFF12090E)
)

// ── 沙金（浅色暖调）────────────────────────────────────
val SandPalette = PaperPalette.copy(
    accent = Color(0xFF8A6220),
    accent2 = Color(0xFFB0733A),
    accent3 = Color(0xFF6F7F3A),
    bg = Color(0xFFF7F3EA),
    surfaceVariant = Color(0xFFEFE8DA),
    outline = Color(0xFFE4DBC8),
    bgTop = Color(0xFFF7F3EA),
    bgMid = Color(0xFFF7F3EA),
    bgBottom = Color(0xFFF7F3EA),
    veil = Color(0xFFF7F3EA)
)

fun paletteOf(theme: AppTheme, systemDark: Boolean): GlassPalette = when (theme) {
    AppTheme.AURORA -> InkPalette
    AppTheme.OLED -> OledPalette
    AppTheme.VIOLET -> VioletPalette
    AppTheme.FOREST -> ForestPalette
    AppTheme.AMBER -> AmberPalette
    AppTheme.ROSE -> RosePalette
    AppTheme.PAPER -> PaperPalette
    AppTheme.SAND -> SandPalette
    AppTheme.SYSTEM -> if (systemDark) InkPalette else PaperPalette
}

// ══════════════════════════════════════════════════════════
//  全局调色板（可变 state：切主题时所有界面自动重组）
// ══════════════════════════════════════════════════════════

object Glass {
    private val state = mutableStateOf(InkPalette)

    val palette: GlassPalette get() = state.value
    fun apply(p: GlassPalette) {
        state.value = p
    }

    val Accent: Color get() = palette.accent
    val Accent2: Color get() = palette.accent2
    val Accent3: Color get() = palette.accent3
    val Good: Color get() = palette.good
    val Warn: Color get() = palette.warn
    val Bad: Color get() = palette.bad

    val Text: Color get() = palette.text
    val TextDim: Color get() = palette.textDim
    val TextTertiary: Color get() = palette.textTertiary
    val Divider: Color get() = palette.outline
    val OnAccent: Color get() = if (palette.dark) Color(0xFF0A1130) else Color(0xFFFFFFFF)
    val Bg: Color get() = palette.bg

    val Body: Color get() = palette.body
    val BorderTop: Color get() = palette.borderTop
    val BorderBottom: Color get() = palette.borderBottom

    /** 进度条 / 滑块轨道 */
    val Track: Color get() = palette.track
    /** 弹层拖拽条 */
    val Handle: Color get() = palette.handle
    /** 封面/缩略图描边 */
    val MediaStroke: Color get() = palette.mediaStroke

    val Tint: Color get() = if (palette.dark) Color(0x1FFFFFFF) else Color(0x66FFFFFF)
    val TintSoft: Color get() = if (palette.dark) Color(0x12FFFFFF) else Color(0x40FFFFFF)

    val R24 = RoundedCornerShape(24)
    val R22 = RoundedCornerShape(22)
    val R18 = RoundedCornerShape(18)
    val R14 = RoundedCornerShape(14)
    val R10 = RoundedCornerShape(10)
    val Pill = RoundedCornerShape(50)
}

// ══════════════════════════════════════════════════════════
//  Material 主题
// ══════════════════════════════════════════════════════════

private val GlassType = Typography(
    displaySmall = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
)

@Composable
fun TGTheme(content: @Composable () -> Unit) {
    val p = Glass.palette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = Color(0xFF0A1130),
            primaryContainer = Color(0xFF2A3352),
            onPrimaryContainer = Color(0xFFE3E8FF),
            secondary = p.accent2,
            onSecondary = Color(0xFF1A0B33),
            tertiary = p.accent3,
            background = p.bg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surfaceVariant,
            onSurfaceVariant = p.textDim,
            outline = p.outline,
            error = p.bad,
            onError = Color(0xFF3B0004)
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFDFE5FF),
            onPrimaryContainer = Color(0xFF0B1B4D),
            secondary = p.accent2,
            onSecondary = Color(0xFFFFFFFF),
            tertiary = p.accent3,
            background = p.bg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surfaceVariant,
            onSurfaceVariant = p.textDim,
            outline = p.outline,
            error = p.bad,
            onError = Color(0xFFFFFFFF)
        )
    }
    MaterialTheme(colorScheme = scheme, typography = GlassType, content = content)
}
