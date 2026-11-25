package com.lcs.tgcomic

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    lib: Library,
    prefs: Prefs,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    cacheState: CacheState?,
    onOpen: (Comic) -> Unit,
    onReadNow: (Comic) -> Unit,
    onImport: (List<String>) -> Unit,
    onExport: (ExportKind, Set<String>?) -> Unit,
    onRemove: (Collection<String>) -> Unit,
    onRename: (String, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onCache: (Comic) -> Unit,
    onCacheMany: (List<Comic>) -> Unit,
    onCancelCache: () -> Unit,
    onClearCache: (Comic) -> Unit,
    onRetryLink: (String) -> Unit,
    onClearLinks: () -> Unit,
    onRemoveLink: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onShareComic: (Comic) -> Unit,
    onCopyImageLink: (Comic, Int) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(prefs.grid) }
    var columns by remember { mutableStateOf(prefs.columns.coerceAtLeast(2)) }
    var sort by remember { mutableStateOf(prefs.sort) }
    var favFirst by remember { mutableStateOf(prefs.favoritesFirst) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFor by remember { mutableStateOf<Comic?>(null) }
    var renameFor by remember { mutableStateOf<Comic?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearLinks by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }

    val selectMode = selected.isNotEmpty()
    val ctx = LocalContext.current
    // 已缓存页数在后台预热：书架每格都要显示「⤓N」，滚动时不能去扫目录
    LaunchedEffect(lib.tick.value, lib.comics.size) {
        withContext(Dispatchers.IO) { lib.warmCacheCounts() }
    }
    var infoDialog by remember { mutableStateOf<String?>(null) }

    // 登录页回来后刷新「已登录」状态（设置页要立即反映）
    var ehCookieSet by remember { mutableStateOf(prefs.ehCookie.isNotBlank()) }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val had = ehCookieSet
        ehCookieSet = prefs.ehCookie.isNotBlank()
        if (!had && ehCookieSet) {
            EhHub.clearAll()
            android.widget.Toast.makeText(ctx, "ExHentai Cookie 已保存", android.widget.Toast.LENGTH_SHORT).show()
        } else if (res.resultCode != android.app.Activity.RESULT_OK && !ehCookieSet) {
            android.widget.Toast.makeText(ctx, "还没登录成功", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 从备份 JSON 恢复书架（系统文件选择器）
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            infoDialog = "读取备份文件失败，确认选的是导出的 tgcomic-*.json"
        } else {
            val result = lib.importJson(text)
            // 必须落盘：importJson 只改内存，不 save 的话重启就回到恢复前
            if (result != null) {
                Thread { runCatching { lib.save() } }.start()
            }
            infoDialog = if (result == null) {
                "这不是有效的书架备份：JSON 解析失败（文件可能被改坏或不是 TG漫画 导出的）"
            } else {
                val (added, merged) = result
                when {
                    added == 0 && merged == 0 -> "这个备份里的册子都已经在书架上了"
                    merged > 0 -> "已恢复：新增 $added 册，另有 $merged 册补全了缺页"
                    else -> "已恢复：新增 $added 册"
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val tick = lib.tick.value
    // 排序/过滤只在书架、排序、搜索词变化时重算：滚动时不再每帧排一遍
    // tab==1 是「收藏」页：同一套网格，只留收藏的册子
    // 收藏页固定「最近阅读」排序（那里的排序/筛选控件已经去掉，避免和书架重复）
    val effectiveSort = if (tab == 1) SortMode.RECENT else sort
    val useGrid = if (tab == 1) false else grid
    val shown = remember(tick, effectiveSort, favFirst, query, tab) {
        val all = lib.sortComics(effectiveSort, favFirst).let { list ->
            if (tab == 1) list.filter { it.favorite } else list
        }
        if (query.isBlank()) all else all.filter {
            it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
        }
    }

    fun toggleSelect(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    AuroraBackground(animate = false) {
        Column(Modifier.fillMaxSize()) {
            // 「发现」有自己的标题栏，这里再画一条就会出现「TG漫画 / 31 册」叠在「发现」上面的双层标题
            if (tab != 2) {
                GlassTopBar(
                    title = if (tab == 3) "设置" else "TG漫画",
                    subtitle = if (tab == 3) "个性化 · 来源 · 数据与备份"
                    else "${lib.comics.size} 册 · ${lib.links.size} 条链接",
                    trailing = {}
                )
            }

            if (tab == 0 || tab == 1) {
                // 搜索 + 视图切换 + 排序筛选：只有书架有；收藏页是「固定视图」
                if (tab == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassSearchField(
                        value = query,
                        onValue = { query = it },
                        modifier = Modifier.weight(1f)
                    )
                    GlassIconButton(
                        if (grid) Glyph.LIST_LINES else Glyph.GRID,
                        size = 42.dp,
                        onClick = {
                            grid = !grid
                            prefs.grid = grid
                        }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassPill(
                        when (sort) {
                            SortMode.RECENT -> "最近阅读 ▾"
                            SortMode.ADDED -> "最近添加 ▾"
                            SortMode.TITLE -> "标题 ▾"
                            SortMode.PAGES -> "页数 ▾"
                        },
                        selected = true,
                        accent = Glass.Accent,
                        onClick = {
                            sort = when (sort) {
                                SortMode.RECENT -> SortMode.ADDED
                                SortMode.ADDED -> SortMode.TITLE
                                SortMode.TITLE -> SortMode.PAGES
                                SortMode.PAGES -> SortMode.RECENT
                            }
                            prefs.sort = sort
                        }
                    )
                    GlassPill(
                        if (favFirst) "收藏优先" else "不置顶收藏",
                        selected = favFirst,
                        accent = Glass.Accent,
                        onClick = {
                            favFirst = !favFirst
                            prefs.favoritesFirst = favFirst
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${shown.size} 册", fontSize = 10.5.sp, color = Glass.TextTertiary)
                }
                }
                if (tab == 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "收藏 ${shown.size} 册 · 按最近阅读排序",
                            fontSize = 11.sp,
                            color = Glass.TextTertiary
                        )
                        Spacer(Modifier.weight(1f))
                        Text("长按可取消收藏", fontSize = 10.sp, color = Glass.TextTertiary)
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                if (lib.comics.isEmpty() || (tab == 1 && shown.isEmpty() && query.isBlank())) {
                    EmptyState(
                        title = if (tab == 1) "还没有收藏的册子" else "书架还是空的",
                        detail = if (tab == 1) "在书架里长按封面可以加入收藏，收藏的册子集中显示在这里。"
                        else "复制 telegra.ph 链接、t.me 消息链接或图片直链，\n切回这里会自动识别；也可以在别的 App 里分享给 TG漫画。",
                        accent = Glass.Accent,
                        modifier = Modifier.padding(bottom = 90.dp)
                    )
                } else if (shown.isEmpty()) {
                    EmptyState(
                        title = "没有匹配的册子",
                        detail = "换个关键词试试",
                        accent = Glass.Accent2
                    )
                } else if (useGrid) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            top = 4.dp,
                            bottom = if (selectMode) 210.dp else 150.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        gridItems(shown, key = { it.id }) { c ->
                            ComicTile(
                                comic = c,
                                cached = lib.cachedPageCount(c.id),
                                cacheState = cacheState?.takeIf { it.comicId == c.id },
                                selected = c.id in selected,
                                selectMode = selectMode,
                                onClick = { if (selectMode) toggleSelect(c.id) else onOpen(c) },
                                onRead = { if (selectMode) toggleSelect(c.id) else onReadNow(c) },
                                onLongClick = { toggleSelect(c.id) },
                                onMenu = { menuFor = c }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            top = 4.dp,
                            bottom = if (selectMode) 210.dp else 150.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(shown, key = { it.id }) { c ->
                            SwipeRow(
                                enabled = !selectMode,
                                onSwipeLeft = { confirmDelete = setOf(c.id) },
                                onSwipeRight = { onToggleFavorite(c.id) }
                            ) {
                                ComicRow(
                                    comic = c,
                                    cached = lib.cachedPageCount(c.id),
                                    cacheState = cacheState?.takeIf { it.comicId == c.id },
                                    selected = c.id in selected,
                                    selectMode = selectMode,
                                    onClick = { if (selectMode) toggleSelect(c.id) else onOpen(c) },
                                    onRead = { if (selectMode) toggleSelect(c.id) else onReadNow(c) },
                                    onLongClick = { toggleSelect(c.id) },
                                    onMenu = { menuFor = c }
                                )
                            }
                        }
                    }
                }
            } else if (tab == 2) {
                DiscoverScreen(
                    prefs = prefs,
                    onImport = onImport,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SettingsScreen(
                    lib = lib,
                    prefs = prefs,
                    theme = theme,
                    onTheme = onTheme,
                    grid = grid,
                    onGrid = { v ->
                        grid = v
                        prefs.grid = v
                    },
                    columns = columns,
                    onColumns = { v ->
                        columns = v
                        prefs.columns = v
                    },
                    sort = sort,
                    onSort = { v ->
                        sort = v
                        prefs.sort = v
                    },
                    favFirst = favFirst,
                    onFavFirst = { v ->
                        favFirst = v
                        prefs.favoritesFirst = v
                    },
                    onClearAllCache = { lib.comics.forEach(onClearCache) },
                    onClearOneCache = { id ->
                        lib.comics.firstOrNull { it.id == id }?.let(onClearCache)
                        lib.invalidateCacheCount(id)
                    },
                    onClearLinks = { showClearLinks = true },
                    onRetryLink = onRetryLink,
                    onRemoveLink = onRemoveLink,
                    onCheckUpdates = onCheckUpdates,
                    onCheckAppUpdate = onCheckAppUpdate,
                    onExport = onExport,
                    onImportBackup = {
                        runCatching {
                            backupPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }
                    },
                    ehCookieSet = ehCookieSet,
                    onLoginEh = { loginLauncher.launch(Intent(ctx, ExhLoginActivity::class.java)) }
                )
            }
        }

        if (selectMode) {
            SelectionBar(
                count = selected.size,
                totalCount = shown.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 96.dp),
                onSelectAll = {
                    selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet()
                },
                onCache = {
                    onCacheMany(selected.mapNotNull { id -> lib.comics.firstOrNull { it.id == id } })
                },
                onExport = { onExport(ExportKind.TXT, selected) },
                onDelete = { confirmDelete = selected },
                onExit = { selected = emptySet() },
                caching = cacheState != null
            )
        }

        // 导入：从底栏挪到右下角悬浮按钮（导入是动作，不该占一个“目的地”位）
        if (!selectMode && tab == 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 104.dp)
                    .size(54.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Glass.Accent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showPaste = true },
                contentAlignment = Alignment.Center
            ) {
                Text("+", fontSize = 26.sp, color = Color(0xFF06222B), fontWeight = FontWeight.Bold)
            }
        }

        GlassBottomNav(
            items = listOf(
                NavItem(Glyph.SHELF, "书架"),
                NavItem(Glyph.STAR, "收藏"),
                NavItem(Glyph.SEARCH, "发现"),
                NavItem(Glyph.TUNE, "设置")
            ),
            selected = when (tab) {
                0 -> 0
                1 -> 1
                2 -> 2
                else -> 3
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            onSelect = { i ->
                tab = i
                selected = emptySet()
            }
        )
    }


    // 单册菜单
    menuFor?.let { c ->
        GlassSheet(
            visible = true,
            onDismiss = { menuFor = null },
            title = c.title,
            subtitle = (if (c.chapterMarks.size > 1) "${c.chapterMarks.size} 章 · " else "") +
                "${c.pages.size} 页 · 读到第 ${(c.lastIndex + 1).coerceAtMost(c.pages.size)} 页"
        ) { close ->
            GlassMenuItem(if (c.favorite) "★ 取消收藏" else "☆ 加入收藏") {
                close()
                onToggleFavorite(c.id)
            }
            GlassMenuItem("重命名") {
                close()
                renameFor = c
            }
            GlassMenuItem("继续阅读") {
                close()
                onReadNow(c)
            }
            GlassMenuItem("打开详情") {
                close()
                onOpen(c)
            }
            GlassMenuItem("缓存整册（${lib.cachedPageCount(c.id)}/${c.pages.size}）") {
                close()
                onCache(c)
            }
            GlassMenuItem("清除本地缓存") {
                close()
                onClearCache(c)
            }
            GlassMenuItem("复制来源链接") {
                close()
                copyToClipboard(ctx, c.sourceUrl)
            }
            GlassMenuItem("复制封面直链") {
                close()
                onCopyImageLink(c, 0)
            }
            GlassMenuItem("分享这册") {
                close()
                onShareComic(c)
            }
            GlassMenuItem("导出本册 TXT") {
                close()
                onExport(ExportKind.TXT, setOf(c.id))
            }
            GlassMenuItem("删除（含缓存）", danger = true) {
                close()
                confirmDelete = setOf(c.id)
            }
        }
    }

    renameFor?.let { c ->
        RenameDialog(
            initial = c.title,
            onDismiss = { renameFor = null },
            onConfirm = { newTitle ->
                onRename(c.id, newTitle)
                renameFor = null
            }
        )
    }

    if (confirmDelete.isNotEmpty()) {
        ConfirmDialog(
            title = if (confirmDelete.size == 1) "删除这册？" else "删除所选 ${confirmDelete.size} 册？",
            body = "封面缓存与阅读进度会一起清掉，来源链接仍保留在「链接记录」里。",
            confirmText = "删除",
            danger = true,
            onDismiss = { confirmDelete = emptySet() },
            onConfirm = {
                onRemove(confirmDelete)
                selected = selected - confirmDelete
                confirmDelete = emptySet()
            }
        )
    }

    if (showClearLinks) {
        ConfirmDialog(
            title = "清空链接记录？",
            body = "只清记录，书架上已入库的册子不受影响。",
            confirmText = "清空",
            danger = true,
            onDismiss = { showClearLinks = false },
            onConfirm = {
                onClearLinks()
                showClearLinks = false
            }
        )
    }

    if (showPaste) {
        PasteDialog(onDismiss = { showPaste = false }) { urls ->
            showPaste = false
            onImport(urls)
        }
    }

    infoDialog?.let { text ->
        InfoDialog(title = "提示", body = text, onDismiss = { infoDialog = null })
    }
}

// ══════════════ 搜索框 ══════════════

@Composable
private fun GlassSearchField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Glass.palette.surfaceVariant)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(Glyph.SEARCH, color = Glass.TextDim, size = 17.dp)
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("搜索标题 / 作者", fontSize = 13.sp, color = Glass.TextDim.copy(alpha = 0.7f))
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = Glass.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(Glass.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            GlyphIcon(
                Glyph.CLOSE,
                color = Glass.TextDim,
                size = 15.dp,
                modifier = Modifier
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
                    .clickable { onValue("") }
            )
        }
    }
}

// ══════════════ 网格卡片 ══════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicTile(
    comic: Comic,
    cached: Int,
    cacheState: CacheState?,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onRead: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit
) {
    val ctx = LocalContext.current
    val cover = remember(comic.id, comic.cover, comic.pages.firstOrNull()) {
        coverModel(ctx, comic)
    }
    val progress = if (comic.pages.isEmpty()) 0f
    else (comic.lastIndex + 1).toFloat() / comic.pages.size

    Column(
        Modifier
            .clip(Glass.R18)
            .liquidGlass(Glass.R18, cornerRadius = 18.dp, elevation = 14.dp, tintScale = 0.9f)
            .border(1.6.dp, if (selected) Glass.Accent else Color.Transparent, Glass.R18)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.74f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                // 封面要自己处理长按，否则内层 clickable 会把「长按进多选」吃掉
                .combinedClickable(onClick = { onRead() }, onLongClick = { onLongClick() })
        ) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(cover).size(360, 480).crossfade(true).build(),
                contentDescription = comic.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 底部压暗 + 进度
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.6f to Color.Transparent,
                            1f to Color(0xB305080E)
                        )
                    )
            )
            if (comic.favorite) {
                Text(
                    "★",
                    fontSize = 14.sp,
                    color = Glass.Warn,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
            if (cacheState != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .liquidGlass(Glass.Pill, cornerRadius = 20.dp, elevation = 6.dp)
                        .padding(5.dp)
                ) {
                    ProgressRing(cacheState.progress, diameter = 22.dp)
                }
            } else if (cached > 0) {
                Text(
                    "⤓$cached",
                    fontSize = 9.5.sp,
                    color = Glass.Good,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .liquidGlass(Glass.Pill, cornerRadius = 20.dp, elevation = 4.dp)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(Glass.Accent.copy(alpha = 0.18f)))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .background(Glass.Accent, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", fontSize = 12.sp, color = Color(0xFF06222B), fontWeight = FontWeight.Bold)
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 7.dp)
            ) {
                MiniProgress(progress)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 9.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    comic.title,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    (if (comic.chapterMarks.size > 1) "${comic.chapterMarks.size} 章 · " else "") +
                        "${comic.pages.size} 页 · ${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    fontSize = 10.sp,
                    color = Glass.TextDim
                )
            }
            Text(
                "⋯",
                fontSize = 15.sp,
                color = Glass.TextDim,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable { onMenu() }
            )
        }
    }
}

// ══════════════ 列表行 ══════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicRow(
    comic: Comic,
    cached: Int,
    cacheState: CacheState?,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onRead: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit
) {
    val ctx = LocalContext.current
    val cover = remember(comic.id, comic.cover, comic.pages.firstOrNull()) {
        coverModel(ctx, comic)
    }
    val progress = if (comic.pages.isEmpty()) 0f
    else (comic.lastIndex + 1).toFloat() / comic.pages.size

    Row(
        Modifier
            .fillMaxWidth()
            .liquidGlass(Glass.R22, cornerRadius = 22.dp, elevation = 14.dp)
            .border(1.6.dp, if (selected) Glass.Accent else Color.Transparent, Glass.R22)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(64.dp)
                .height(86.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Glass.MediaStroke, RoundedCornerShape(12.dp))
                .combinedClickable(onClick = { onRead() }, onLongClick = { onLongClick() })
        ) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(cover).size(240, 320).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (selected) {
                Box(Modifier.fillMaxSize().background(Glass.Accent.copy(alpha = 0.20f)))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .background(Glass.Accent, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", fontSize = 10.sp, color = Color(0xFF06222B), fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (comic.favorite) {
                    Text("★ ", fontSize = 11.sp, color = Glass.Warn)
                }
                Text(
                    comic.title,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                buildString {
                    if (comic.chapterMarks.size > 1) append("${comic.chapterMarks.size} 章 · ")
                    append("${comic.pages.size} 页 · 读到 ${(comic.lastIndex + 1).coerceAtMost(comic.pages.size)}")
                    if (cached > 0) append(" · 缓存 $cached")
                    if (comic.author.isNotBlank()) append(" · ${comic.author}")
                    append(" · ${relTime(comic.updatedAt)}")
                },
                fontSize = 10.5.sp,
                color = Glass.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { MiniProgress(progress) }
                if (cacheState != null) {
                    Spacer(Modifier.width(8.dp))
                    ProgressRing(cacheState.progress, diameter = 16.dp)
                }
            }
        }
        Text(
            "⋯",
            fontSize = 16.sp,
            color = Glass.TextDim,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clickable { onMenu() }
        )
    }
}

@Composable
private fun MiniProgress(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Glass.Track, cornerRadius = r)
        val w = size.width * progress.coerceIn(0f, 1f)
        if (w > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(Glass.Accent, Glass.Accent2)),
                size = Size(w, size.height),
                cornerRadius = r
            )
        }
    }
}

// ══════════════ 链接记录 ══════════════

@Composable
private fun RecordList(
    lib: Library,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val failed = lib.links.count { it.status == "fail" }
    if (lib.links.isEmpty()) {
        EmptyState(
            title = "还没有链接记录",
            detail = "分享、粘贴、剪贴板识别到的 telegra.ph 链接都会记在这里。",
            accent = Glass.Accent3,
            modifier = modifier.padding(bottom = 80.dp)
        )
        return
    }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${lib.links.size} 条记录", fontSize = 11.sp, color = Glass.TextDim)
            Spacer(Modifier.weight(1f))
            if (failed > 0) {
                GlassPill("重试失败 $failed", accent = Glass.Warn, onClick = {
                    lib.links.filter { it.status == "fail" }.forEach { onRetry(it.url) }
                })
            }
            Spacer(Modifier.width(6.dp))
            GlassPill("清空", accent = Glass.Bad, onClick = onClear)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(lib.links) { l ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .liquidGlass(Glass.R18, cornerRadius = 18.dp, elevation = 10.dp)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(statusColor(l.status), RoundedCornerShape(50))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            l.title.ifBlank { "(无标题)" },
                            modifier = Modifier.weight(1f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Glass.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(statusLabel(l.status), fontSize = 10.sp, color = statusColor(l.status))
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        l.url,
                        fontSize = 10.sp,
                        color = Glass.TextDim.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassPill("复制", onClick = { copyToClipboard(ctx, l.url) })
                        Spacer(Modifier.width(4.dp))
                        GlassPill("重试", accent = Glass.Warn, onClick = { onRetry(l.url) })
                        Spacer(Modifier.width(4.dp))
                        GlassPill("删除", accent = Glass.Bad, onClick = { onRemove(l.url) })
                    }
                }
            }
        }
    }
}

// ══════════════ 多选操作条 ══════════════

@Composable
private fun SelectionBar(
    count: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
    caching: Boolean,
    onSelectAll: () -> Unit,
    onCache: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .clip(Glass.R24)
            .background(Color(0xEB0B0E15))
            .liquidGlass(Glass.R24, cornerRadius = 24.dp, elevation = 26.dp, glow = Glass.Accent)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已选 $count", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
            Spacer(Modifier.weight(1f))
            GlassPill(
                if (count == totalCount) "取消全选" else "全选",
                onClick = onSelectAll
            )
            Spacer(Modifier.width(6.dp))
            GlassPill("退出", onClick = onExit)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(if (caching) "缓存中…" else "缓存", accent = Glass.Good, enabled = !caching, onClick = onCache)
            GlassPill("导出", accent = Glass.Accent, onClick = onExport)
            GlassPill("删除", accent = Glass.Bad, onClick = onDelete)
        }
    }
}

// ══════════════ 列表左右滑动操作 ══════════════

/** 右滑收藏、左滑删除。点在滑动的子项内仍能正常点击（水平拖拽消费后 clickable 不触发） */
@Composable
private fun SwipeRow(
    enabled: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxOffset = with(density) { 150.dp.toPx() }
    val threshold = with(density) { 84.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val off = offsetX.value

    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .matchParentSize()
                .padding(horizontal = 26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "☆ 收藏",
                color = Glass.Warn.copy(alpha = if (off > 24f) 1f else 0f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "删除 ✕",
                color = Glass.Bad.copy(alpha = if (off < -24f) 1f else 0f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = off }
                .then(
                    if (!enabled) Modifier
                    else Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    val v = offsetX.value
                                    when {
                                        v > threshold -> {
                                            offsetX.animateTo(0f)
                                            onSwipeRight()
                                        }
                                        v < -threshold -> {
                                            offsetX.animateTo(0f)
                                            onSwipeLeft()
                                        }
                                        else -> offsetX.animateTo(0f)
                                    }
                                }
                            },
                            onHorizontalDrag = { change, drag ->
                                change.consume()
                                scope.launch {
                                    offsetX.snapTo(
                                        (offsetX.value + drag).coerceIn(-maxOffset, maxOffset)
                                    )
                                }
                            }
                        )
                    }
                )
        ) { content() }
    }
}


@Composable
internal fun ThemeSwatch(t: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val p = paletteOf(t, false)
    Column(
        Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.border(1.5.dp, Glass.Accent, RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (t == AppTheme.SYSTEM)
                        Brush.linearGradient(listOf(Color(0xFF0A0F1A), Color(0xFF0E1420), Color(0xFFF1F4F9)))
                    else
                        Brush.linearGradient(listOf(p.bgTop, p.bgMid, p.bgBottom))
                )
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(28.dp)
                    .height(16.dp)
                    .background(p.accent, RoundedCornerShape(5.dp))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t.label,
            fontSize = 10.sp,
            color = if (selected) Glass.Accent else Glass.TextDim,
            maxLines = 1
        )
    }
}