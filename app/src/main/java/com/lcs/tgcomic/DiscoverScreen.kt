package com.lcs.tgcomic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * 「发现」页：浏览站点上的新内容。
 *
 * - 两列卡片（封面 + 标题），**可翻页**（上一页 / 下一页）
 * - 点卡片 → 详情弹窗：大图 + 页数 + 作者/分类（只拉一次页面，不展开全部图片）
 * - 详情里「加入书架」走和手动粘贴完全相同的导入流程
 */
private enum class DiscoverSource(val label: String) {
    WNACG("紳士漫畫最新"),
    EH_HOME("E-Hentai 首页"),
    EH_SEARCH("搜索 E-Hentai")
}

private data class FoundItem(
    val url: String,
    val title: String,
    val cover: String,
    val source: String
)

private data class DetailInfo(
    val title: String,
    val pages: Int,
    val author: String,
    val category: String,
    val cover: String
)

@Composable
fun DiscoverScreen(
    prefs: Prefs,
    onImport: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(DiscoverSource.WNACG) }
    var query by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<FoundItem>>(emptyList()) }

    // 详情弹窗
    var detailFor by remember { mutableStateOf<FoundItem?>(null) }
    var detail by remember { mutableStateOf<DetailInfo?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }

    fun pageUrl(src: DiscoverSource, p: Int): String = when (src) {
        DiscoverSource.WNACG ->
            if (p <= 1) "https://www.wnacg.com/albums.html"
            else "https://www.wnacg.com/albums-index-page-$p.html"

        DiscoverSource.EH_HOME ->
            if (p <= 1) "https://e-hentai.org/" else "https://e-hentai.org/?page=$p"

        DiscoverSource.EH_SEARCH -> {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            "https://e-hentai.org/?f_search=$q&page=$p"
        }
    }

    fun load(p: Int) {
        if (loading) return
        if (source == DiscoverSource.EH_SEARCH && query.isBlank()) {
            error = "先在上面输入关键词"
            return
        }
        loading = true
        error = ""
        items = emptyList()
        page = p
        scope.launch {
            val res = runCatching {
                when (source) {
                    DiscoverSource.WNACG ->
                        WnacgApi.expandList(pageUrl(source, p), 24)
                            .map { FoundItem(it.url, it.title, it.cover, "紳士漫畫") }

                    else -> EhApi.expandList(pageUrl(source, p), prefs.ehCookie, 24)
                        .map { FoundItem(it.url, it.title, it.cover, "E-Hentai") }
                }
            }
            loading = false
            res.fold({ items = it }, { error = it.message ?: "加载失败" })
        }
    }

    fun openDetail(item: FoundItem) {
        detailFor = item
        detail = null
        detailError = ""
        detailLoading = true
        scope.launch {
            val res = runCatching {
                if (item.source == "紳士漫畫") {
                    val p = WnacgApi.peek(item.url)
                    DetailInfo(
                        p.title.ifBlank { item.title },
                        p.total,
                        p.author,
                        p.category,
                        p.cover.ifBlank { item.cover }
                    )
                } else {
                    val p = EhApi.peek(item.url, prefs.ehCookie)
                    DetailInfo(
                        p.title.ifBlank { item.title },
                        p.pages,
                        p.author,
                        p.category,
                        p.cover.ifBlank { item.cover }
                    )
                }
            }
            detailLoading = false
            res.fold({ detail = it }, { detailError = it.message ?: "详情加载失败" })
        }
    }

    LaunchedEffect(source) {
        page = 1
        if (source != DiscoverSource.EH_SEARCH) load(1)
    }

    // 弹窗必须和列表叠在同一个 Box 里（否则会被父布局当成第二行排到屏幕外）
    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        GlassTopBar(
            title = "发现",
            subtitle = when {
                loading -> "正在拉取第 $page 页…"
                error.isNotBlank() -> "加载失败"
                else -> "第 $page 页 · ${items.size} 个结果"
            },
            trailing = {}
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DiscoverSource.entries.forEach { s ->
                GlassPill(s.label, selected = source == s, accent = Glass.Accent) {
                    if (source != s) {
                        source = s
                        items = emptyList()
                        error = ""
                    }
                }
            }
        }

        if (source == DiscoverSource.EH_SEARCH) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Glass.palette.inputField)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text("关键词，比如 touhou", fontSize = 12.sp, color = Glass.TextTertiary)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Glass.Text, fontSize = 13.sp),
                        cursorBrush = SolidColor(Glass.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { load(1) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                GlassPill("搜索", selected = true) { load(1) }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Glass.Accent)
            }

            error.isNotBlank() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, fontSize = 12.sp, color = Glass.Warn)
                    Spacer(Modifier.height(10.dp))
                    GlassPill("重试", selected = true) { load(page) }
                }
            }

            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这一页没有内容了 · 返回上一页看看", fontSize = 12.sp, color = Glass.TextTertiary)
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 118.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items, key = { it.url }) { it0 ->
                    DiscoverCard(it0) { openDetail(it0) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PagerRow(
                        page = page,
                        onPrev = { if (page > 1) load(page - 1) },
                        onNext = { if (items.isNotEmpty()) load(page + 1) }
                    )
                }
            }
        }
    }


    // ---------------- 详情弹窗 ----------------
    detailFor?.let { item ->
        GlassDialog(onDismiss = { detailFor = null }) {
            val d = detail
            Row(Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(d?.cover?.ifBlank { item.cover } ?: item.cover)
                        .size(400, 560)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Glass.palette.inputField),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d?.title?.ifBlank { item.title } ?: item.title,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.Text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    if (detailLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Glass.Accent)
                            Spacer(Modifier.width(6.dp))
                            Text("正在读取详情…", fontSize = 10.5.sp, color = Glass.TextTertiary)
                        }
                    } else if (detailError.isNotBlank()) {
                        Text(detailError, fontSize = 10.5.sp, color = Glass.Warn, maxLines = 3)
                    } else if (d != null) {
                        if (d.pages > 0) InfoLine("页数", "${d.pages} 页")
                        if (d.author.isNotBlank()) InfoLine("作者", d.author)
                        if (d.category.isNotBlank()) InfoLine("分类", d.category)
                        InfoLine("来源", item.source)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill("关闭", modifier = Modifier.weight(1f).height(42.dp)) { detailFor = null }
                GlassPill("加入书架", selected = true, modifier = Modifier.weight(1f).height(42.dp)) {
                    val u = item.url
                    detailFor = null
                    onImport(listOf(u))
                }
            }
        }
    }
    }
    }

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 10.5.sp, color = Glass.TextTertiary, modifier = Modifier.width(34.dp))
        Text(
            value,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = Glass.TextDim,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 两列卡片：封面 + 两行标题，点卡片进详情 */
@Composable
private fun DiscoverCard(item: FoundItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Glass.palette.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.74f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Glass.palette.inputField),
            contentAlignment = Alignment.Center
        ) {
            if (item.cover.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.cover).size(400, 540).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("无封面", fontSize = 10.sp, color = Glass.TextTertiary)
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                item.title.ifBlank { item.url },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Glass.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(item.source, fontSize = 9.sp, color = Glass.TextTertiary)
        }
    }
}

/** 翻页行：上一页 / 第 N 页 / 下一页 */
@Composable
private fun PagerRow(page: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        GlassPill("上一页", selected = page > 1, enabled = page > 1, onClick = onPrev)
        Spacer(Modifier.width(14.dp))
        Text("第 $page 页", fontSize = 11.sp, color = Glass.TextDim)
        Spacer(Modifier.width(14.dp))
        GlassPill("下一页", selected = true, onClick = onNext)
    }
}
