package com.lcs.tgcomic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    comic: Comic,
    prefs: Prefs,
    cacheState: CacheState?,
    initialIndex: Int,
    onBack: () -> Unit,
    onProgress: (Int) -> Unit,
    onCache: () -> Unit,
    onCancelCache: () -> Unit,
    onSavePage: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyLink: () -> Unit,
    onBrightness: (Float) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(prefs.readerMode) }
    var bg by remember { mutableStateOf(prefs.readerBg) }
    var showUi by remember { mutableStateOf(true) }
    var showMore by remember { mutableStateOf(false) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    var fav by remember { mutableStateOf(comic.favorite) }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var bright by remember { mutableStateOf(1) }

    BackHandler(enabled = true) {
        if (zoomPage != null) zoomPage = null else onBack()
    }

    val last = (comic.pages.size - 1).coerceAtLeast(0)
    val start = initialIndex.coerceIn(0, last)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val pagerState = rememberPagerState(
        initialPage = start,
        pageCount = { comic.pages.size }
    )
    val current = if (mode == ReaderMode.VERTICAL) listState.firstVisibleItemIndex
    else pagerState.currentPage

    LaunchedEffect(current) { onProgress(current) }

    fun goto(index: Int) {
        val i = index.coerceIn(0, last)
        scope.launch {
            if (mode == ReaderMode.VERTICAL) listState.scrollToItem(i) else pagerState.scrollToPage(i)
        }
    }

    Box(Modifier.fillMaxSize().background(bg.color)) {

        if (mode == ReaderMode.VERTICAL) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(comic.pages) { i, url ->
                    val model = remember(i, url) { pageModel(ctx, comic.id, i, url) }
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(model).crossfade(true).build(),
                        contentDescription = "第${i + 1}页",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .pointerInput(i, url) {
                                detectTapGestures(
                                    onTap = { pos ->
                                        val w = size.width.toFloat()
                                        when {
                                            pos.x < w * 0.26f -> goto(i - 1)
                                            pos.x > w * 0.74f -> goto(i + 1)
                                            else -> showUi = !showUi
                                        }
                                    },
                                    onDoubleTap = { zoomPage = i },
                                    onLongPress = { onSavePage(i) }
                                )
                            },
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val url = comic.pages.getOrNull(page) ?: ""
                val model = remember(page, url) { pageModel(ctx, comic.id, page, url) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(model).crossfade(true).build(),
                        contentDescription = "第${page + 1}页",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(page, url) {
                                detectTapGestures(
                                    onTap = { pos ->
                                        val w = size.width.toFloat()
                                        when {
                                            pos.x < w * 0.26f -> goto(page - 1)
                                            pos.x > w * 0.74f -> goto(page + 1)
                                            else -> showUi = !showUi
                                        }
                                    },
                                    onDoubleTap = { zoomPage = page },
                                    onLongPress = { onSavePage(page) }
                                )
                            },
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // ── 顶部浮条 ──
        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(Glass.R24)
                    .background(Glass.palette.surface.copy(alpha = 0.94f))
                    .liquidGlass(Glass.R24, cornerRadius = 26.dp, elevation = 16.dp, borderWidth = 1.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(Glyph.BACK, onClick = onBack, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        comic.title,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "第 ${current + 1} / ${comic.pages.size} 页" +
                            if (cacheState != null) " · 缓存 ${(cacheState.progress * 100).toInt()}%" else "",
                        fontSize = 10.sp,
                        color = Glass.TextDim
                    )
                }
                GlassIconButton(
                    if (fav) Glyph.STAR else Glyph.STAR_OUTLINE,
                    onClick = {
                        onToggleFavorite()
                        fav = !fav
                    },
                    size = 36.dp,
                    tint = if (fav) Glass.Warn else Glass.TextDim
                )
                Spacer(Modifier.width(6.dp))
                GlassIconButton(Glyph.MORE_DOTS, onClick = { showMore = true }, size = 36.dp)
            }
        }

        // ── 底部浮条 ──
        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(Glass.R24)
                    .background(Glass.palette.surface.copy(alpha = 0.96f))
                    .liquidGlass(Glass.R24, cornerRadius = 26.dp, elevation = 16.dp, borderWidth = 1.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${((dragValue ?: current.toFloat()) + 1).toInt()}", fontSize = 11.sp, color = Glass.Accent)
                    Slider(
                        value = dragValue ?: current.toFloat(),
                        onValueChange = { dragValue = it },
                        onValueChangeFinished = {
                            dragValue?.let { goto(it.toInt()) }
                            dragValue = null
                        },
                        valueRange = 0f..last.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Glass.Accent,
                            activeTrackColor = Glass.Accent,
                            inactiveTrackColor = Glass.Track
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    Text("${comic.pages.size}", fontSize = 11.sp, color = Glass.TextDim)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        Text(
                            "左右点击翻页 · 双击放大 · 长按存图",
                            fontSize = 9.5.sp,
                            color = Glass.TextDim.copy(alpha = 0.8f)
                        )
                    }
                    if (cacheState != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .liquidGlass(Glass.Pill, cornerRadius = 30.dp, elevation = 4.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            ProgressRing(cacheState.progress, diameter = 15.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("${cacheState.done}/${cacheState.total}", fontSize = 10.sp, color = Glass.Text)
                        }
                        GlassPill("取消", accent = Glass.Bad, onClick = onCancelCache)
                    } else {
                        GlassPill("缓存整册", accent = Glass.Good, onClick = onCache)
                    }
                    GlassIconButton(
                        Glyph.SUN,
                        size = 34.dp,
                        tint = Glass.Warn,
                        onClick = {
                            bright = (bright + 1) % 3
                            onBrightness(
                                when (bright) {
                                    0 -> 0.35f
                                    1 -> 0.7f
                                    else -> 1f
                                }
                            )
                        }
                    )
                }
            }
        }

        // ── 放大层 ──
        zoomPage?.let { idx ->
            val url = comic.pages.getOrNull(idx) ?: ""
            val model = remember(idx, url) { pageModel(ctx, comic.id, idx, url) }
            var zScale by remember(idx) { mutableStateOf(1f) }
            var zOffset by remember(idx) { mutableStateOf(Offset.Zero) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(idx) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zScale = (zScale * zoom).coerceIn(1f, 5f)
                            zOffset = if (zScale > 1f) zOffset + pan else Offset.Zero
                        }
                    }
                    .pointerInput(idx) {
                        detectTapGestures(onTap = { zoomPage = null })
                    }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(model).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zScale
                            scaleY = zScale
                            translationX = zOffset.x
                            translationY = zOffset.y
                        },
                    contentScale = ContentScale.Fit
                )
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 10.dp)
                        .liquidGlass(Glass.Pill, cornerRadius = 30.dp, elevation = 10.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("双指缩放 · 单击退出 · 第 ${idx + 1} 页", fontSize = 10.5.sp, color = Glass.Text)
                }
            }
        }
    }

    GlassSheet(
        visible = showMore,
        onDismiss = { showMore = false },
        title = "阅读设置",
        subtitle = comic.title
    ) { close ->
        GlassMenuItem("阅读方向：${mode.label}") {
            mode = if (mode == ReaderMode.VERTICAL) ReaderMode.PAGED else ReaderMode.VERTICAL
            prefs.readerMode = mode
            close()
        }
        GlassMenuItem("底色：${bg.label}") {
            bg = when (bg) {
                ReaderBg.BLACK -> ReaderBg.PAPER
                ReaderBg.PAPER -> ReaderBg.MINT
                ReaderBg.MINT -> ReaderBg.BLACK
            }
            prefs.readerBg = bg
        }
        GlassMenuItem("从第一页开始") {
            close()
            onProgress(0)
            goto(0)
        }
        GlassMenuItem("跳到上次位置（第 ${comic.lastIndex + 1} 页）") {
            close()
            goto(comic.lastIndex)
        }
        GlassMenuItem(if (comic.favorite) "★ 取消收藏" else "☆ 加入收藏") {
            close()
            onToggleFavorite()
            fav = !fav
        }
        GlassMenuItem("复制来源链接") {
            close()
            onCopyLink()
        }
        GlassMenuItem("保存当前页到相册") {
            close()
            onSavePage(current)
        }
        if (cacheState != null) {
            GlassMenuItem("取消缓存", danger = true) {
                close()
                onCancelCache()
            }
        } else {
            GlassMenuItem("缓存整册到本地") {
                close()
                onCache()
            }
        }
    }
}
