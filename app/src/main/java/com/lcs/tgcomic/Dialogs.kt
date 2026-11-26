package com.lcs.tgcomic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

// ══════════════ 通用玻璃弹层（同窗口 overlay，玻璃能透出背景） ══════════════

@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // 弹窗圆角：24dp 太大（导入/更新弹窗看着像药丸），统一收到 16dp
    radius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    BackHandler(enabled = true) { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Glass.palette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        // 弹窗入场：淡入 + 轻微放大上浮（比直接蹦出来自然）
        val vis = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = vis,
            enter = fadeIn(tween(140)) +
                scaleIn(initialScale = 0.94f, animationSpec = tween(170)) +
                slideInVertically(tween(170)) { it / 40 },
            exit = fadeOut(tween(110))
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Glass.palette.panelTop)
                    .border(1.dp, Glass.palette.outline, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .padding(20.dp),
                content = content
            )
        }
    }
}

/** 底部滑出菜单 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    if (!visible) return
    BackHandler(enabled = true) { onDismiss() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    val topShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Glass.palette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
    ) {
        // 底部菜单：从底部滑上来（带一点淡入）
        val vis = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = vis,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(240)) { it } + fadeIn(tween(150)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(120))
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(topShape)
                .background(Glass.palette.surfaceVariant)
                .border(1.dp, Glass.palette.outline, topShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .background(Glass.Handle, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            subtitle,
                            fontSize = 11.5.sp,
                            color = Glass.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                GlassIconButton(Glyph.CLOSE, onClick = onDismiss, size = 32.dp, tint = Glass.TextDim)
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 14.dp)
            ) { content(onDismiss) }
        }
        }
    }
}

@Composable
fun GlassMenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val label = text.trimStart('★', '☆', ' ')
    val icon = when {
        text.startsWith("★") -> Glyph.STAR
        text.startsWith("☆") -> Glyph.STAR_OUTLINE
        label.contains("删除") -> Glyph.TRASH
        label.contains("清空") || label.contains("清除") -> Glyph.TRASH
        label.contains("重命名") -> Glyph.EDIT
        label.contains("继续阅读") || label.contains("从第一页") || label.contains("跳到") -> Glyph.PLAY
        label.contains("详情") -> Glyph.INFO
        label.contains("取消") && label.contains("缓存") -> Glyph.REFRESH
        label.contains("缓存") -> Glyph.CACHE
        label.contains("复制") -> Glyph.COPY
        label.contains("导出") -> Glyph.EXPORT
        label.contains("分享") -> Glyph.SHARE
        label.contains("重试") || label.contains("更新") -> Glyph.REFRESH
        else -> Glyph.MORE_DOTS
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (danger) Glass.Bad.copy(alpha = 0.14f)
                    else Glass.palette.surface
                ),
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(
                glyph = icon,
                color = if (danger) Glass.Bad else Glass.Accent,
                size = 16.dp
            )
        }
        Spacer(Modifier.width(13.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (danger) Glass.Bad else Glass.Text
        )
    }
}

@Composable
fun GlassMenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .height(1.dp)
            .background(Glass.Divider)
    )
}

// ══════════════ 确认 / 重命名 ══════════════

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String = "确定",
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    GlassDialog(onDismiss) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(10.dp))
        Text(body, fontSize = 12.5.sp, lineHeight = 19.sp, color = Glass.TextDim)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GlassPill(
                    "取消",
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = onDismiss
                )
            }
            Box(Modifier.weight(1f)) {
                GlassPill(
                    confirmText,
                    selected = true,
                    accent = if (danger) Glass.Bad else Glass.Accent,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = onConfirm
                )
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    GlassDialog(onDismiss) {
        Text("重命名", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .liquidGlass(Glass.R14, cornerRadius = 14.dp, elevation = 6.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (text.isEmpty()) {
                Text("输入新标题", fontSize = 13.sp, color = Glass.TextDim.copy(alpha = 0.6f))
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Glass.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(Glass.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GlassPill("取消", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onDismiss)
            }
            Box(Modifier.weight(1f)) {
                GlassPill(
                    "保存",
                    selected = true,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = { if (text.isNotBlank()) onConfirm(text) }
                )
            }
        }
    }
}

// ══════════════ 导入相关 ══════════════

@Composable
fun ImportDialogs(
    state: ImportState,
    onConfirm: (List<Parsed>) -> Unit,
    onClose: () -> Unit,
    onRetry: (String) -> Unit
) {
    when (state) {
        ImportState.Idle -> Unit

        is ImportState.Loading -> GlassDialog(onDismiss = onClose) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Glass.Accent
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("正在解析", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
                    if (state.total > 1) {
                        Text(
                            if (state.done > 0) "进度 ${state.done} / ${state.total}"
                            else "共 ${state.total} 个页面",
                            fontSize = 11.sp,
                            color = Glass.TextDim
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                state.url,
                fontSize = 11.sp,
                color = Glass.TextDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        is ImportState.Failed -> GlassDialog(onDismiss = onClose) {
            Text("解析失败", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
            Spacer(Modifier.height(10.dp))
            Text(state.message, fontSize = 12.sp, lineHeight = 18.sp, color = Glass.TextDim)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    GlassPill("关闭", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onClose)
                }
                if (state.url.isNotBlank()) {
                    Box(Modifier.weight(1f)) {
                        GlassPill(
                            "重试",
                            selected = true,
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            onClick = {
                                onClose()
                                onRetry(state.url)
                            }
                        )
                    }
                }
            }
        }

        is ImportState.Ready -> {
            val ctx = LocalContext.current
            var chosen by remember(state.items) { mutableStateOf(state.items.indices.toSet()) }
            val totalImgs = state.items.sumOf { it.images.size }
            GlassDialog(onDismiss = onClose) {
                Text(
                    if (state.items.size == 1) state.items[0].title else "识别到 ${state.items.size} 个链接",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "共 $totalImgs 张图 · 已选 ${chosen.size} 个页面",
                    fontSize = 11.5.sp,
                    color = Glass.Accent
                )
                Spacer(Modifier.height(12.dp))
                // EH 的 pages 是图片页地址，不能直接当缩略图；有封面就用封面
                val previews = state.items.flatMap { p ->
                    if (p.cover.isNotBlank()) listOf(p.cover)
                    else p.images.filter { !isPendingPage(it) }
                }.distinct().take(12)
                if (previews.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(previews) { u ->
                            AsyncImage(
                                model = rememberImage(u, crossfadeMs = 90) { c ->
                                    ImageRequest.Builder(c).data(u)
                                },
                                contentDescription = null,
                                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                if (state.items.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                        state.items.forEachIndexed { i, p ->
                            val checked = i in chosen
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(Glass.R10)
                                    .clickable {
                                        chosen = if (checked) chosen - i else chosen + i
                                    }
                                    .padding(vertical = 9.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (checked) Glass.Accent.copy(alpha = 0.9f)
                                            else Color.White.copy(alpha = 0.10f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (checked) {
                                        Text("✓", fontSize = 11.sp, color = Color(0xFF06222B), fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        p.title,
                                        fontSize = 12.sp,
                                        color = Glass.Text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text("${p.images.size} 页", fontSize = 10.sp, color = Glass.TextDim)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        GlassPill("取消", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onClose)
                    }
                    Box(Modifier.weight(1.2f)) {
                        GlassPill(
                            "加入书架",
                            filled = true,
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            onClick = {
                                val list = state.items.filterIndexed { i, _ -> i in chosen }
                                if (list.isNotEmpty()) onConfirm(list)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClipDialog(links: List<String>, onImport: (List<String>) -> Unit, onDismiss: () -> Unit) {
    if (links.isEmpty()) return
    GlassDialog(onDismiss) {
        Text("检测到剪贴板链接", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            if (links.size == 1) "1 个可导入链接" else "${links.size} 个可导入链接",
            fontSize = 11.5.sp,
            color = Glass.Accent
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
            links.take(6).forEach {
                Text(
                    "· ${it.take(76)}",
                    fontSize = 10.5.sp,
                    lineHeight = 16.sp,
                    color = Glass.TextDim,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GlassPill("忽略", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onDismiss)
            }
            Box(Modifier.weight(1.2f)) {
                GlassPill(
                    "导入",
                    selected = true,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = { onImport(links) }
                )
            }
        }
    }
}

@Composable
fun PasteDialog(onDismiss: () -> Unit, onImport: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    // 打开即预填剪贴板里的链接，省掉一次“读剪贴板”
    LaunchedEffect(Unit) {
        if (text.isBlank()) {
            val cb = clipboardText(ctx)
            if (TelegraphParser.findAllUrls(cb).any { TelegraphParser.isSupported(it) }) text = cb
        }
    }
    val urls = remember(text) {
        TelegraphParser.findAllUrls(text).filter { TelegraphParser.isSupported(it) }
    }
    GlassDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("导入链接", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
            Spacer(Modifier.weight(1f))
            GlassPill("读剪贴板", onClick = { text = clipboardText(ctx) })
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .clip(Glass.R14)
                .background(Glass.palette.inputField)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (text.isEmpty()) {
                Text(
                    "粘贴链接：telegra.ph 图文页 / t.me 消息或频道 / e-hentai 画廊或搜索页 / 紳士漫畫 / 禁漫天堂 / 图片直链，\n一行一个；整段分享文字也可以",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Glass.TextDim.copy(alpha = 0.7f)
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Glass.Text, fontSize = 12.5.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(Glass.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (urls.isEmpty()) "支持 telegra.ph · t.me · e-hentai/exhentai · 紳士漫畫 wnacg · 禁漫天堂 18comic · 图片直链" else "已识别 ${urls.size} 个链接",
            fontSize = 11.sp,
            color = if (urls.isEmpty()) Glass.TextDim else Glass.Good
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GlassPill("取消", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onDismiss)
            }
            Box(Modifier.weight(1.2f)) {
                GlassPill(
                    "解析",
                    selected = true,
                    enabled = urls.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = { onImport(urls) }
                )
            }
        }
    }
}

// ══════════════ 通用提示弹窗 ══════════════

@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    GlassDialog(onDismiss) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(10.dp))
        Text(body, fontSize = 12.5.sp, lineHeight = 19.sp, color = Glass.TextDim)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            GlassPill(
                "好",
                filled = true,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                onClick = onDismiss
            )
        }
    }
}

// ══════════════ 软件更新 ══════════════

@Composable
fun UpdateDialog(
    ui: UpdateUi,
    onAccept: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = ui.info ?: return
    GlassDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发现新版本", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Glass.Accent.copy(alpha = 0.14f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(info.tag, fontSize = 10.5.sp, color = Glass.Accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("当前 v${BuildConfig.VERSION_NAME}", fontSize = 11.5.sp, color = Glass.TextDim)
        if (info.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                Text(info.notes, fontSize = 11.sp, lineHeight = 17.sp, color = Glass.TextDim)
            }
        }
        val p = ui.progress
        if (p != null) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { p.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Glass.Accent,
                trackColor = Glass.Track
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (ui.ready) "已下载，可以安装" else "正在下载 ${(p * 100).toInt()}%",
                fontSize = 11.sp,
                color = Glass.TextDim
            )
        }
        if (ui.error.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(ui.error, fontSize = 11.sp, lineHeight = 16.sp, color = Glass.Bad)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GlassPill("稍后", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onDismiss)
            }
            if (ui.ready) {
                Box(Modifier.weight(1.2f)) {
                    GlassPill(
                        "安装更新",
                        filled = true,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        onClick = onInstall
                    )
                }
            } else if (p == null) {
                Box(Modifier.weight(1.2f)) {
                    GlassPill(
                        "立即更新",
                        filled = true,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        onClick = onAccept
                    )
                }
            }
        }
    }
}

// ══════════════ E-Hentai Cookie ══════════════

@Composable
fun EhCookieDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    GlassDialog(onDismiss) {
        Text(
            "E-Hentai / ExHentai Cookie",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Glass.Text
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "e-hentai.org 匿名能看；exhentai.org 必须带登录 Cookie。\n" +
                "浏览器登录后 F12 → Application → Cookies，把三个值拼成\n" +
                "ipb_member_id=…; ipb_pass_hash=…; ignorant=1 贴进来。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = Glass.TextDim
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp)
                .clip(Glass.R14)
                .background(Glass.palette.inputField)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (text.isEmpty()) {
                Text(
                    "ipb_member_id=…; ipb_pass_hash=…; ignorant=1",
                    fontSize = 12.sp,
                    color = Glass.TextDim.copy(alpha = 0.7f)
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Glass.Text, fontSize = 12.sp, lineHeight = 17.sp),
                cursorBrush = SolidColor(Glass.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GlassPill("取消", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onDismiss)
            }
            Box(Modifier.weight(1f)) {
                GlassPill(
                    "保存",
                    filled = true,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = { onConfirm(text.trim()) }
                )
            }
        }
    }
}


/** WebDAV 配置：地址（目录）/ 账号 / 密码 */
@Composable
fun WebDavDialog(
    url: String,
    user: String,
    pass: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var u by remember { mutableStateOf(url) }
    var n by remember { mutableStateOf(user) }
    var w by remember { mutableStateOf(pass) }

    GlassDialog(onDismiss) {
        Text(
            "WebDAV 备份",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Glass.Text
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "地址填目录（例：坚果云 https://dav.jianguoyun.com/dav/我的坚果云），" +
                "备份文件固定叫 tgcomic-backup.json；也可以直接填到 .json 结尾的完整地址。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = Glass.TextDim
        )
        Spacer(Modifier.height(12.dp))

        DavField("服务器地址", u, single = true) { u = it }
        Spacer(Modifier.height(8.dp))
        DavField("账号", n, single = true) { n = it }
        Spacer(Modifier.height(8.dp))
        DavField("密码 / 应用密码", w, single = true, secret = true) { w = it }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPill("取消", modifier = Modifier.weight(1f).height(42.dp), onClick = onDismiss)
            GlassPill(
                "保存",
                selected = true,
                enabled = u.isNotBlank(),
                modifier = Modifier.weight(1f).height(42.dp)
            ) { onConfirm(u, n, w) }
        }
    }
}

@Composable
private fun DavField(
    label: String,
    value: String,
    single: Boolean,
    secret: Boolean = false,
    onChange: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 10.5.sp, color = Glass.TextDim)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(Glass.R14)
                .background(Glass.palette.inputField)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = single,
                textStyle = TextStyle(color = Glass.Text, fontSize = 12.5.sp),
                cursorBrush = SolidColor(Glass.Accent),
                visualTransformation = if (secret) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 导入时发现「书架里已有同一册」——把两边都列出来，让用户自己决定保留哪个。
 * 自动判重很容易一刀切（telegra.ph 的文章常常共用首图/模板），所以这里不替用户拍板。
 */
@Composable
fun ImportConflictDialog(
    newTitle: String,
    newUrl: String,
    newPages: Int,
    oldTitle: String,
    oldUrl: String,
    oldPages: Int,
    onKeepOld: () -> Unit,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onKeepBoth: () -> Unit
) {
    GlassDialog(onDismiss = onKeepOld) {
        Text("发现重复", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(4.dp))
        Text(
            "书架里已经有一册和这次导入的很像，保留哪个由你决定",
            fontSize = 11.sp,
            color = Glass.TextDim
        )
        Spacer(Modifier.height(12.dp))

        ConflictBlock("书架里已有的", oldTitle, oldUrl, oldPages, Glass.TextDim)
        Spacer(Modifier.height(8.dp))
        ConflictBlock("这次要导入的", newTitle, newUrl, newPages, Glass.Accent)

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill("保留原册", modifier = Modifier.weight(1f).height(40.dp), onClick = onKeepOld)
            GlassPill("合并两册", selected = true, modifier = Modifier.weight(1f).height(40.dp), onClick = onMerge)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill("用新的替换", modifier = Modifier.weight(1f).height(40.dp), onClick = onReplace)
            GlassPill("两个都留", modifier = Modifier.weight(1f).height(40.dp), onClick = onKeepBoth)
        }
    }
}

@Composable
private fun ConflictBlock(tag: String, title: String, url: String, pages: Int, color: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Glass.palette.inputField)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(tag, fontSize = 10.sp, color = Glass.TextTertiary)
        Spacer(Modifier.height(3.dp))
        Text(
            title,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = Glass.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text("$pages 页", fontSize = 10.5.sp, color = color)
        Text(
            url,
            fontSize = 9.5.sp,
            color = Glass.TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
