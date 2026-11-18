package com.lcs.tgcomic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** 单册详情：参考主流漫画阅读器的「封面 + 信息 + 主按钮 + 缩略图」结构 */
@Composable
fun DetailScreen(
    comic: Comic,
    cached: Int,
    cacheState: CacheState?,
    onBack: () -> Unit,
    onRead: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onCache: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val progress = if (comic.pages.isEmpty()) 0f
    else ((comic.lastIndex + 1).toFloat() / comic.pages.size).coerceIn(0f, 1f)
    val host = runCatching { java.net.URI(comic.sourceUrl).host ?: "" }.getOrDefault("")

    AuroraBackground(animate = false) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 10.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(Glyph.BACK, onClick = onBack, size = 38.dp)
                Spacer(Modifier.weight(1f))
                GlassPill(
                    if (comic.favorite) "★ 已收藏" else "☆ 收藏",
                    selected = comic.favorite,
                    accent = Glass.Warn,
                    onClick = onToggleFavorite
                )
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                // ── 封面 + 信息 ──
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier
                                .width(112.dp)
                                .height(150.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, Glass.MediaStroke, RoundedCornerShape(14.dp))
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx)
                                    .data(pageModel(ctx, comic.id, 0, comic.pages.firstOrNull() ?: ""))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = comic.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                comic.title,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Glass.Text,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                comic.author.ifBlank { host.ifBlank { "未知来源" } },
                                fontSize = 11.5.sp,
                                color = Glass.TextDim
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                buildString {
                                    append("${comic.pages.size} 页")
                                    append(" · 已读 ${(progress.coerceIn(0f, 1f) * 100).toInt()}%")
                                    if (cached > 0) append(" · 缓存 $cached 页")
                                },
                                fontSize = 11.sp,
                                color = Glass.TextTertiary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                relTime(comic.updatedAt),
                                fontSize = 10.5.sp,
                                color = Glass.TextTertiary
                            )
                        }
                    }
                }

                // ── 主按钮 ──
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.weight(1.6f)) {
                            PrimaryButton(
                                text = if (comic.lastIndex > 0) "继续阅读 · 第 ${comic.lastIndex + 1} 页" else "开始阅读",
                                modifier = Modifier.fillMaxWidth()
                            ) { onRead(comic.lastIndex) }
                        }
                        Box(Modifier.weight(1f)) {
                            GhostButton(
                                text = if (cacheState != null) "缓存中…" else "缓存整册",
                                modifier = Modifier.fillMaxWidth()
                            ) { if (cacheState == null) onCache() }
                        }
                    }
                }

                // ── 操作行 ──
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassPill("从头读", accent = Glass.Accent2, onClick = { onRead(0) })
                        GlassPill("分享", onClick = onShare)
                        GlassPill("复制链接", onClick = onCopyLink)
                        GlassPill("导出", onClick = onExport)
                    }
                }

                // ── 阅读进度 ──
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .liquidGlass(Glass.R18, cornerRadius = 18.dp, elevation = 12.dp, topEdge = false)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("阅读进度", fontSize = 12.sp, color = Glass.Text)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "第 ${(comic.lastIndex + 1).coerceAtMost(comic.pages.size)} 页 / 共 ${comic.pages.size} 页",
                                fontSize = 11.sp,
                                color = Glass.TextDim
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Glass.Track)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                                    .height(5.dp)
                                    .background(
                                        Brush.horizontalGradient(listOf(Glass.Accent, Glass.Accent2))
                                    )
                            )
                        }
                    }
                }

                // ── 页面缩略 ──
                if (comic.pages.isNotEmpty()) {
                    item {
                        SectionHeader("页面预览 · 点开即跳转")
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(comic.pages.take(24)) { i, url ->
                                val model = pageModel(ctx, comic.id, i, url)
                                Column(
                                    Modifier
                                        .width(76.dp)
                                        .clickable { onRead(i) },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        Modifier
                                            .width(76.dp)
                                            .height(102.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(1.dp, Glass.MediaStroke, RoundedCornerShape(10.dp))
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(ctx).data(model).crossfade(true).build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("${i + 1}", fontSize = 10.sp, color = Glass.TextTertiary)
                                }
                            }
                        }
                    }
                }

                // ── 危险操作 ──
                item {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GlassPill("删除这册", accent = Glass.Bad, onClick = onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Glass.Accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Glass.OnAccent,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Glass.palette.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 12.5.sp, color = Glass.Text, fontWeight = FontWeight.Medium)
    }
}
