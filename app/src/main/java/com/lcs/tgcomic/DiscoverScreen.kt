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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * 「发现」页：直接浏览站点上的新内容。带封面，点卡片看大图预览，再一键加入书架。
 *
 * 三个入口：
 *  · 紳士漫畫最新（wnacg albums.html，缩略图是静态地址）
 *  · E-Hentai 首页（ehgt.org 缩略图）
 *  · E-Hentai 搜索（关键词）
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
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<FoundItem>>(emptyList()) }
    var preview by remember { mutableStateOf<FoundItem?>(null) }

    fun load() {
        if (loading) return
        loading = true
        error = ""
        items = emptyList()
        scope.launch {
            val res = runCatching {
                when (source) {
                    DiscoverSource.WNACG ->
                        WnacgApi.expandList("https://www.wnacg.com/albums.html", 24)
                            .map { FoundItem(it.url, it.title, it.cover, "紳士漫畫") }

                    DiscoverSource.EH_HOME ->
                        EhApi.expandList("https://e-hentai.org/", prefs.ehCookie, 24)
                            .map { FoundItem(it.url, it.title, it.cover, "E-Hentai") }

                    DiscoverSource.EH_SEARCH -> {
                        val q = query.trim()
                        if (q.isBlank()) throw IllegalStateException("先在上面输入关键词")
                        val url = "https://e-hentai.org/?f_search=" +
                            java.net.URLEncoder.encode(q, "UTF-8")
                        EhApi.expandList(url, prefs.ehCookie, 24)
                            .map { FoundItem(it.url, it.title, it.cover, "E-Hentai") }
                    }
                }
            }
            loading = false
            res.fold({ items = it }, { error = it.message ?: "加载失败" })
        }
    }

    // 进入页面先自动拉一次「最新」，切源也重拉（搜索源等用户按回车）
    LaunchedEffect(source) {
        if (source != DiscoverSource.EH_SEARCH) load()
    }

    Column(modifier) {
        GlassTopBar(
            title = "发现",
            subtitle = when {
                loading -> "正在拉取…"
                error.isNotBlank() -> "加载失败"
                else -> "${items.size} 个结果 · 点卡片预览，点「加入」直接导入"
            },
            trailing = {}
        )

        // 来源切换
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
                        keyboardActions = KeyboardActions(onSearch = { load() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                GlassPill("搜索", selected = true) { load() }
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
                    GlassPill("重试", selected = true) { load() }
                }
            }

            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这一源暂时没拉到内容", fontSize = 12.sp, color = Glass.TextTertiary)
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.url }) { it0 ->
                    DiscoverCard(it0) { preview = it0 }
                }
            }
        }
    }

    // 点卡片 → 大图预览
    preview?.let { p ->
        GlassDialog(onDismiss = { preview = null }) {
            Text(
                p.title.ifBlank { p.url },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Glass.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(p.source, fontSize = 10.5.sp, color = Glass.TextTertiary)
            Spacer(Modifier.height(10.dp))
            if (p.cover.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(p.cover).size(600, 800).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.74f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill("关闭", modifier = Modifier.weight(1f).height(42.dp)) { preview = null }
                GlassPill("加入书架", selected = true, modifier = Modifier.weight(1f).height(42.dp)) {
                    preview = null
                    onImport(listOf(p.url))
                }
            }
        }
    }
}

/** 一行：封面 + 标题 + 来源；点整行看大图预览 */
@Composable
private fun DiscoverCard(item: FoundItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass.palette.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 66.dp, height = 88.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Glass.palette.inputField),
            contentAlignment = Alignment.Center
        ) {
            if (item.cover.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.cover).size(200, 270).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("无封面", fontSize = 9.sp, color = Glass.TextTertiary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { item.url },
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = Glass.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(item.source, fontSize = 9.5.sp, color = Glass.TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        GlassPill("加入", selected = true) { onClick() }
    }
}
