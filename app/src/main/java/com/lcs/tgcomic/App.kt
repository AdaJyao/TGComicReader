package com.lcs.tgcomic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

// ─────────────── 路由 ───────────────

sealed interface Screen {
    data object Shelf : Screen
    data class Detail(val id: String) : Screen
    data class Reader(val id: String, val startIndex: Int) : Screen
}

// ─────────────── 状态模型 ───────────────

data class Parsed(
    val url: String,
    val title: String,
    val author: String,
    val images: List<String>,
    val cover: String = ""
) {
    val host: String get() = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
}

sealed interface ImportState {
    data object Idle : ImportState
    data class Loading(val url: String, val total: Int, val done: Int = 0) : ImportState
    data class Ready(val items: List<Parsed>) : ImportState
    data class Failed(val message: String, val url: String = "") : ImportState
}

data class CacheState(
    val comicId: String,
    val done: Int,
    val total: Int,
    val title: String = "",
    val queueIndex: Int = 1,
    val queueTotal: Int = 1
) {
    val progress: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/** 软件更新状态（info 为空表示没有新版本） */
data class UpdateUi(
    val info: Updater.Info? = null,
    /** null = 没在下载；0..1 = 下载进度 */
    val progress: Float? = null,
    val ready: Boolean = false,
    val error: String = ""
)

enum class ExportKind(val label: String, val short: String, val mime: String, val ext: String) {
    JSON("导出书架 JSON", "JSON", "application/json", "json"),
    TXT("导出 TXT（含图片直链）", "TXT", "text/plain", "txt"),
    URLS("导出链接列表", "链接", "text/plain", "txt")
}

// ─────────────── 小工具 ───────────────

fun clipboardText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
}

fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("tgcomic", text))
}

/** 单页缓存文件的位置（和 Library.pageFile 一致） */
fun pageFileOf(ctx: Context, comicId: String, index: Int): File =
    File(File(File(ctx.filesDir, "comics"), comicId), "%04d.img".format(index))

fun pageModel(ctx: Context, comicId: String, index: Int, url: String): Any {
    val f = pageFileOf(ctx, comicId, index)
    val cached = if (f.exists() && f.length() > 1024) f else null
    // 禁漫的图是切片打乱的：远端和本地都要过还原这一步。
    // 已还原过的缓存有 .jm 标记，直接读文件；没有标记的（老版本存下的原图）交给
    // Fetcher 还原一次并回写，所以不能像别的来源那样直接把 File 丢给 Coil。
    JmScramble.parse(url)?.let { info ->
        if (cached != null && JmScramble.isDecoded(cached)) return cached
        return JmImage(url, info, cached)
    }
    return cached ?: url
}

/** 封面：优先用存下来的缩略图（EH），否则退回第一页 */
fun coverModel(ctx: Context, comic: Comic): Any {
    if (comic.cover.isNotBlank()) {
        // 禁漫的封面也是打乱过的原图，同样要走还原
        JmScramble.parse(comic.cover)?.let { return JmImage(comic.cover, it) }
        return comic.cover
    }
    return pageModel(ctx, comic.id, 0, comic.pages.firstOrNull() ?: "")
}

/** 这一页还是「页面地址」，需要先换直链才能显示（EH 看图页 / wnacg 看图页） */
fun isPendingPage(url: String): Boolean = PageResolver.needsResolve(url)

fun statusLabel(s: String): String = when (s) {
    "ok" -> "已入书架"
    "merged" -> "已合并"
    "dup" -> "已存在"
    "fail" -> "失败"
    else -> s
}

fun statusColor(s: String): Color = when (s) {
    "ok", "merged" -> Glass.Good
    "dup" -> Glass.TextDim
    "fail" -> Glass.Bad
    else -> Glass.TextDim
}

// ─────────────── 根组件 ───────────────

@Composable
fun AppRoot(
    lib: Library,
    prefs: Prefs,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    clipLinks: List<String>,
    importState: ImportState,
    screen: Screen,
    message: String,
    cacheState: CacheState?,
    onImport: (List<String>) -> Unit,
    onConfirmImport: (List<Parsed>) -> Unit,
    onCloseImport: () -> Unit,
    onDismissClip: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onExport: (ExportKind, Set<String>?) -> Unit,
    onCache: (Comic) -> Unit,
    onCacheMany: (List<Comic>) -> Unit,
    onCancelCache: () -> Unit,
    onSavePage: (Comic, Int) -> Unit,
    onProgress: (Comic, Int) -> Unit,
    onRemoveComics: (Collection<String>) -> Unit,
    onRename: (String, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onClearCache: (Comic) -> Unit,
    onRetryLink: (String) -> Unit,
    onClearLinks: () -> Unit,
    onRemoveLink: (String) -> Unit,
    onBrightness: (Float) -> Unit,
    onCheckUpdates: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onShareComic: (Comic) -> Unit,
    onCopyImageLink: (Comic, Int) -> Unit,
    update: UpdateUi,
    onUpdateAccept: () -> Unit,
    onUpdateInstall: () -> Unit,
    onUpdateDismiss: () -> Unit,
    onClearMessage: () -> Unit
) {
    val ctx = LocalContext.current

    Box(Modifier.fillMaxSize().background(Glass.Bg)) {
        // 页面切换淡入淡出：书架 ↔ 详情 ↔ 阅读器（比瞬切舒服，也不吃性能）
        Crossfade(targetState = screen, animationSpec = tween(210), label = "screen") { s ->
        when (s) {
            Screen.Shelf -> ShelfScreen(
                lib = lib,
                prefs = prefs,
                theme = theme,
                onTheme = onTheme,
                cacheState = cacheState,
                onOpen = { c -> onNavigate(Screen.Detail(c.id)) },
                onReadNow = { c -> onNavigate(Screen.Reader(c.id, c.lastIndex)) },
                onImport = onImport,
                onExport = onExport,
                onRemove = onRemoveComics,
                onRename = onRename,
                onToggleFavorite = onToggleFavorite,
                onCache = onCache,
                onCacheMany = onCacheMany,
                onCancelCache = onCancelCache,
                onClearCache = onClearCache,
                onRetryLink = onRetryLink,
                onClearLinks = onClearLinks,
                onRemoveLink = onRemoveLink,
                onCheckUpdates = onCheckUpdates,
                onCheckAppUpdate = onCheckAppUpdate,
                onShareComic = onShareComic,
                onCopyImageLink = onCopyImageLink
            )

            is Screen.Detail -> {
                BackHandler(enabled = true) { onNavigate(Screen.Shelf) }
                val c = lib.comics.firstOrNull { it.id == s.id }
                if (c != null) {
                    DetailScreen(
                        comic = c,
                        cached = lib.cachedPageCount(c.id),
                        cacheState = cacheState?.takeIf { it.comicId == c.id },
                        onBack = { onNavigate(Screen.Shelf) },
                        onRead = { idx -> onNavigate(Screen.Reader(c.id, idx.coerceIn(0, (c.pages.size - 1).coerceAtLeast(0)))) },
                        onToggleFavorite = { onToggleFavorite(c.id) },
                        onCache = { onCache(c) },
                        onShare = { onShareComic(c) },
                        onCopyLink = { copyToClipboard(ctx, c.sourceUrl) },
                        onExport = { onExport(ExportKind.TXT, setOf(c.id)) },
                        onDelete = {
                            onRemoveComics(listOf(c.id))
                            onNavigate(Screen.Shelf)
                        }
                    )
                } else {
                    onNavigate(Screen.Shelf)
                }
            }

            is Screen.Reader -> {
                val c = lib.comics.firstOrNull { it.id == s.id }
                if (c != null) {
                    ReaderScreen(
                        comic = c,
                        prefs = prefs,
                        cacheState = cacheState?.takeIf { it.comicId == c.id },
                        initialIndex = s.startIndex,
                        onBack = { onNavigate(Screen.Shelf) },
                        onProgress = { onProgress(c, it) },
                        onCache = { onCache(c) },
                        onCancelCache = onCancelCache,
                        onSavePage = { onSavePage(c, it) },
                        onToggleFavorite = { onToggleFavorite(c.id) },
                        onCopyLink = { copyToClipboard(ctx, c.sourceUrl) },
                        onBrightness = onBrightness
                    )
                } else {
                    onNavigate(Screen.Shelf)
                }
            }
        }
        }

        ImportDialogs(importState, onConfirmImport, onCloseImport, onRetryLink)
        ClipDialog(clipLinks, onImport, onDismissClip)

        UpdateDialog(
            ui = update,
            onAccept = onUpdateAccept,
            onInstall = onUpdateInstall,
            onDismiss = onUpdateDismiss
        )

        LaunchedEffect(message) {
            if (message.isNotBlank()) {
                delay(2800)
                onClearMessage()
            }
        }
        AnimatedVisibility(
            visible = message.isNotBlank(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 96.dp)
        ) {
            ToastBar(message)
        }
    }
}

@Composable
private fun ToastBar(text: String) {
    Row(
        modifier = Modifier
            .liquidGlass(Glass.Pill, cornerRadius = 40.dp, elevation = 24.dp, borderWidth = 1.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(Glass.Accent, RoundedCornerShape(50))
        )
        Text(text, fontSize = 12.5.sp, color = Glass.Text, style = MaterialTheme.typography.bodySmall)
    }
}
