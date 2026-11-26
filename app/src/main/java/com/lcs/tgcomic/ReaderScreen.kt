package com.lcs.tgcomic

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 屏幕常亮（设置页可关）
    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

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
    // 看图页（wnacg/EH）要先换成直链。打开就把后面几页解析好，翻到就能显示，
    // 不然每翻一页都要等一次站点请求（体感就是「极慢 + 一片空白」）
    val pageDirs = remember(comic.id) { mutableStateMapOf<String, String>() }
    val current = if (mode == ReaderMode.VERTICAL) listState.firstVisibleItemIndex
    else pagerState.currentPage

    LaunchedEffect(current) { onProgress(current) }

    LaunchedEffect(comic.id, current, prefs.prefetchAhead) {
        val ahead = prefs.prefetchAhead
        var done = 0
        var i = current
        while (ahead > 0 && done < ahead && i < comic.pages.size) {
            val u = comic.pages[i]
            if (isPendingPage(u) && !pageDirs.containsKey(u)) {
                val direct = runCatching { PageResolver.direct(u, prefs.ehCookie) }.getOrNull()
                if (direct != null) {
                    pageDirs[u] = direct
                    done++
                }
            }
            i++
        }
    }

    fun goto(index: Int) {
        val i = index.coerceIn(0, last)
        scope.launch {
            if (mode == ReaderMode.VERTICAL) listState.scrollToItem(i) else pagerState.scrollToPage(i)
        }
    }

    // 邻页预取：禁漫的图下载 + 还原要几百毫秒，先备好下一页，滚过去就不会空一下再闪出来
    LaunchedEffect(comic.id, current) {
        val app = ctx.applicationContext as? TgApp ?: return@LaunchedEffect
        for (d in intArrayOf(1, 2)) {
            val i = current + d
            if (i > last) break
            val u = comic.pages.getOrNull(i) ?: continue
            JmWarmer.warm(ctx, pageFileOf(ctx, comic.id, i), u, app.okHttp)
        }
    }

    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(bg.color)
            .focusRequester(keyFocus)
            .focusable()
            .onKeyEvent { e ->
                if (!prefs.volumeKeys || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.VolumeDown -> {
                        if (current < last) goto(current + 1)
                        true
                    }
                    androidx.compose.ui.input.key.Key.VolumeUp -> {
                        if (current > 0) goto(current - 1)
                        true
                    }
                    else -> false
                }
            }
    ) {

        if (mode == ReaderMode.VERTICAL) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(comic.pages) { i, url ->
                    var retry by remember(url) { mutableStateOf(0) }
                    val load = rememberPageLoad(ctx, comic.id, i, url, prefs.ehCookie, retry, pageDirs[url])
                    Box(
                        Modifier
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
                            }
                    ) {
                        val m = load.model
                        if (m != null) {
                            PageView(
                                model = m,
                                file = pageFileOf(ctx, comic.id, i),
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                                desc = "第${i + 1}页",
                                pageIndex = i,
                                onRetry = { retry++ }
                            )
                        } else {
                            PendingPage(
                                index = i,
                                failed = load.failed,
                                message = load.message,
                                onClick = { retry++ }
                            )
                        }
                    }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val url = comic.pages.getOrNull(page) ?: ""
                var retry by remember(url) { mutableStateOf(0) }
                val load = rememberPageLoad(ctx, comic.id, page, url, prefs.ehCookie, retry, pageDirs[url])
                Box(
                    Modifier
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
                    contentAlignment = Alignment.Center
                ) {
                    val m = load.model
                    if (m != null) {
                        PageView(
                            model = m,
                            file = pageFileOf(ctx, comic.id, page),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            desc = "第${page + 1}页",
                            pageIndex = page,
                            onRetry = { retry++ }
                        )
                    } else {
                        PendingPage(index = page, failed = load.failed, message = load.message, onClick = { retry++ })
                    }
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
                    .liquidGlass(Glass.R24, cornerRadius = 26.dp, elevation = 16.dp, borderWidth = 1.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${((dragValue ?: current.toFloat()) + 1).toInt()}", fontSize = 11.sp, color = Glass.Accent)
                    if (last > 0) {
                        Slider(
                            value = (dragValue ?: current.toFloat()).coerceIn(0f, last.toFloat()),
                            onValueChange = { dragValue = it.coerceIn(0f, last.toFloat()) },
                            onValueChangeFinished = {
                                dragValue?.let { goto(it.toInt()) }
                                dragValue = null
                            },
                            valueRange = 0f..last.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Glass.Accent,
                                activeTrackColor = Glass.Accent,
                                inactiveTrackColor = Glass.Track
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                        Text("单页", fontSize = 10.sp, color = Glass.TextDim)
                    }
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
            var zRetry by remember(url) { mutableStateOf(0) }
            val load = rememberPageLoad(ctx, comic.id, idx, url, prefs.ehCookie, zRetry, pageDirs[url])
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
                val zm = load.model
                if (zm != null) {
                    PageView(
                        model = zm,
                        file = pageFileOf(ctx, comic.id, idx),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zScale
                                scaleY = zScale
                                translationX = zOffset.x
                                translationY = zOffset.y
                            },
                        contentScale = ContentScale.Fit,
                        desc = "第${idx + 1}页",
                        pageIndex = idx,
                        onRetry = { zRetry++ }
                    )
                } else {
                    PendingPage(index = idx, failed = load.failed, message = load.message, onClick = { zRetry++ })
                }
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
            // 先记下当前页，换容器后再滚过去，否则会跳回进来时那一页
            val at = current
            mode = if (mode == ReaderMode.VERTICAL) ReaderMode.PAGED else ReaderMode.VERTICAL
            prefs.readerMode = mode
            close()
            scope.launch {
                if (mode == ReaderMode.VERTICAL) listState.scrollToItem(at) else pagerState.scrollToPage(at)
            }
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

// ══════════════ 单页加载（E-Hentai 的图片页要先换成直链） ══════════════

private data class PageLoad(
    val model: Any? = null,
    val failed: Boolean = false,
    val message: String = ""
)

/**
 * 返回这一页可渲染的 model：
 *  - 已经整册缓存 → 本地文件
 *  - telegra.ph / t.me / 图片直链 → 原 URL
 *  - E-Hentai 图片页 → 异步换直链（EhHub 有 6 分钟缓存），失败可点着重试
 */
@Composable
private fun rememberPageLoad(
    ctx: Context,
    comicId: String,
    index: Int,
    url: String,
    cookie: String,
    retry: Int,
    known: String? = null
): PageLoad {
    val cached = remember(index, url) { pageModel(ctx, comicId, index, url) }
    val pending = remember(url) { isPendingPage(url) }
    val state = produceState<PageLoad>(
        initialValue = when {
            cached is java.io.File -> PageLoad(cached)
            known != null -> PageLoad(known)
            else -> PageLoad()
        },
        key1 = url,
        key2 = retry,
        key3 = known
    ) {
        if (cached is java.io.File) {
            value = PageLoad(cached)
            return@produceState
        }
        if (!pending) {
            value = PageLoad(cached)
            return@produceState
        }
        // 预解析已经拿到直链：直接用，不再请求
        if (known != null) {
            value = PageLoad(known)
            return@produceState
        }
        value = PageLoad()
        val res = runCatching { PageResolver.direct(url, cookie) }
        val direct = res.getOrNull()
        value = if (direct != null) PageLoad(direct)
        else PageLoad(failed = true, message = res.exceptionOrNull()?.message.orEmpty())
    }
    return state.value
}

@Composable
private fun PendingPage(index: Int, failed: Boolean, message: String = "", onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .then(if (failed) Modifier.clickable { onClick() } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!failed) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Glass.Accent)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (failed) message.ifBlank { "第 ${index + 1} 页没取到图片，点一下重试" }
            else "第 ${index + 1} 页 · 正在解析直链…",
            fontSize = 11.sp,
            color = if (failed) Glass.Warn else Glass.TextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}


/** 预解析领先页数：打开就先把后面几页的直链换好 */
private const val PREFETCH_AHEAD = 4

/**
 * 单页显示：本地已缓存 → 直接读数；远程 → Coil 直连，失败时自动用 Net.download
 * 把这一页存到本地再显示（和「缓存整册」走同一条下载通道，成功率高得多）。
 * 两条路都失败才提示点击重试。
 */
@Composable
private fun PageView(
    model: Any,
    file: java.io.File?,
    modifier: Modifier,
    contentScale: ContentScale,
    desc: String,
    pageIndex: Int = 0,
    onRetry: () -> Unit
) {
    val ctx = LocalContext.current
    var cur by remember(model) { mutableStateOf(model) }
    var phase by remember(model) { mutableStateOf(0) } // 0 正常 / 1 兜底下载中 / 2 失败

    // 兜底下载用 LaunchedEffect 驱动：不能从 onState 回调里 launch（
    // 那个 scope 一旦随重建离开组合就会抛「The coroutine scope left the composition」）
    LaunchedEffect(phase, model) {
        if (phase != 1) return@LaunchedEffect
        val remote = (cur as? String) ?: (cur as? JmImage)?.url ?: return@LaunchedEffect
        val target = file
        if (target == null) {
            phase = 2
            return@LaunchedEffect
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching { Net.download(remote, target) && target.length() > 1024 }.getOrDefault(false)
        }
        if (ok) {
            cur = target
            phase = 0
        } else {
            phase = 2
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val m = cur) {
            is java.io.File -> AsyncImage(
                model = rememberImage(m, crossfadeMs = 90) { c -> ImageRequest.Builder(c).data(m) },
                contentDescription = desc,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                contentScale = contentScale
            )
            else -> {
                // 禁漫的页是 JmImage（下载后要先还原切片），其它来源是普通地址字符串
                val remote = (m as? String) ?: (m as? JmImage)?.url
                if (remote == null) {
                    PendingPage(index = 0, failed = true, message = "这一页没有地址", onClick = onRetry)
                } else {
                    AsyncImage(
                        model = rememberImage(m) { c -> ImageRequest.Builder(c).data(m) },
                        contentDescription = desc,
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        contentScale = contentScale,
                        onState = { st ->
                            if (st is AsyncImagePainter.State.Error && phase == 0) {
                                phase = if (file == null) 2 else 1
                            }
                        }
                    )
                    when (phase) {
                        1 -> Text(
                            "网络慢，正在把这一页存到本地…",
                            fontSize = 11.sp,
                            color = Glass.TextDim
                        )
                        2 -> Text(
                            "这一页没加载出来，点一下重试",
                            fontSize = 11.sp,
                            color = Glass.Warn,
                            modifier = Modifier.clickable {
                                phase = 0
                                cur = model
                                onRetry()
                            }
                        )
                    }
                }
            }
        }
    }
}
