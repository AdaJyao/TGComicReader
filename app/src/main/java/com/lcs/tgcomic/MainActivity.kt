package com.lcs.tgcomic

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var lib: Library
    private lateinit var prefs: Prefs

    private var clipLinks by mutableStateOf<List<String>>(emptyList())
    private var dismissedClipKey by mutableStateOf("")
    private var importState by mutableStateOf<ImportState>(ImportState.Idle)
    private var screen by mutableStateOf<Screen>(Screen.Shelf)
    private var cacheState by mutableStateOf<CacheState?>(null)
    private var message by mutableStateOf("")
    private var theme by mutableStateOf(AppTheme.AURORA)

    private var cacheJob: Job? = null
    private var clipJob: Job? = null
    private var lastSaveAt = 0L
    private var pendingExport: Pair<ExportKind, Set<String>?>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val job = pendingExport
            pendingExport = null
            if (granted && job != null) performExport(job.first, job.second)
            else if (!granted) message = "没有存储权限，导出停在原地"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lib = Library(applicationContext)
        lib.load()
        prefs = Prefs(applicationContext)

        theme = prefs.theme
        Glass.apply(paletteOf(theme, systemDark()))

        setContent {
            TGTheme {
                val pal = Glass.palette
                androidx.compose.runtime.SideEffect {
                    applySystemBars(pal.dark)
                }
                AppRoot(
                    lib = lib,
                    prefs = prefs,
                    theme = theme,
                    onTheme = { applyTheme(it) },
                    clipLinks = clipLinks,
                    importState = importState,
                    screen = screen,
                    message = message,
                    cacheState = cacheState,
                    onImport = { urls -> startImport(urls) },
                    onConfirmImport = { items -> confirmImport(items) },
                    onCloseImport = { importState = ImportState.Idle },
                    onDismissClip = {
                        dismissedClipKey = clipLinks.joinToString("|")
                        clipLinks = emptyList()
                    },
                    onNavigate = { s ->
                        screen = s
                        if (s !is Screen.Reader) {
                            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                        }
                        if (s is Screen.Shelf) saveAsync()
                    },
                    onExport = { kind, ids -> ensureAndExport(kind, ids) },
                    onCache = { c -> cacheComic(c) },
                    onCancelCache = { cancelCache() },
                    onSavePage = { c, i -> savePage(c, i) },
                    onProgress = { c, i ->
                        if (c.lastIndex != i) {
                            c.lastIndex = i
                            lib.tick.value++
                            saveThrottled()
                        }
                        c.updatedAt = System.currentTimeMillis()
                    },
                    onRemoveComics = { ids ->
                        lib.removeComics(ids)
                        saveAsync()
                        message = "已删除 ${ids.size} 册"
                    },
                    onRename = { id, title ->
                        lib.rename(id, title)
                        saveAsync()
                        message = "已重命名"
                    },
                    onToggleFavorite = { id ->
                        val on = lib.toggleFavorite(id)
                        saveAsync()
                        message = if (on) "已加入收藏" else "已取消收藏"
                    },
                    onClearCache = { c ->
                        lib.clearCache(c.id)
                        message = "已清除《${c.title.take(14)}》的本地缓存"
                    },
                    onRetryLink = { url -> startImport(listOf(url)) },
                    onClearLinks = {
                        lib.clearLinks()
                        saveAsync()
                        message = "链接记录已清空"
                    },
                    onRemoveLink = { url ->
                        lib.removeLink(url)
                        saveAsync()
                    },
                    onBrightness = { v -> setBrightness(v) },
                    onCheckUpdates = { checkUpdates() },
                    onShareComic = { c -> shareComic(c) },
                    onCopyImageLink = { c, i -> copyImageLink(c, i) },
                    onClearMessage = { message = "" }
                )
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        scheduleClipScan()
    }

    /** Android 12+ 剪贴板只在窗口获得焦点后可读，resume 与 focus 两个时机都尝试，并做几次轮询 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleClipScan()
    }

    private fun scheduleClipScan() {
        if (clipJob?.isActive == true) return
        clipJob = lifecycleScope.launch {
            repeat(8) { i ->
                delay(if (i == 0) 150L else 700L)
                if (scanClipboard()) return@launch
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveAsync()
    }

    // ---------------- 主题 ----------------

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun applyTheme(t: AppTheme) {
        theme = t
        prefs.theme = t
        Glass.apply(paletteOf(t, systemDark()))
        message = "主题：${t.label}"
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars(dark: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        window.statusBarColor = Glass.palette.bg.toArgb()
        window.navigationBarColor = Glass.palette.bg.toArgb()
    }

    // ---------------- 入口：分享 / 打开链接 / 剪贴板 ----------------

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val urls = TelegraphParser.findAllUrls(text).filter { TelegraphParser.isSupported(it) }
        if (urls.isNotEmpty()) startImport(urls)
    }

    /** 返回 true 表示已经识别到新链接（停止轮询） */
    private fun scanClipboard(): Boolean {
        if (importState !is ImportState.Idle) return false
        if (screen !is Screen.Shelf) return false
        val text = clipboardText(this)
        if (text.isBlank()) return false
        val urls = TelegraphParser.findAllUrls(text).filter { TelegraphParser.isSupported(it) }
        if (urls.isEmpty()) return false
        val fresh = urls.filter { !lib.containsUrl(it) }
        if (fresh.isEmpty()) return false
        val key = fresh.joinToString("|")
        if (key == dismissedClipKey) return false
        clipLinks = fresh
        return true
    }

    // ---------------- 解析 ----------------

    private suspend fun loadArticle(url: String): TelegraphParser.Article = withContext(Dispatchers.IO) {
        when {
            TelegraphParser.isTgLink(url) -> loadTelegram(url)
            TelegraphParser.isImageUrl(url) -> TelegraphParser.Article(
                url = url,
                title = "图片直链",
                author = "",
                images = listOf(url)
            )
            else -> loadTelegraph(url)
        }
    }

    /** telegra.ph 图文页（含页面内“下一页”追踪） */
    private suspend fun loadTelegraph(url: String): TelegraphParser.Article {
        val html = Net.getText(url)
        val base = TelegraphParser.parse(html, url)
        val imgs = base.images.toMutableList()
        var curHtml = html
        var curUrl = url
        var guard = 0
        while (guard < 4) {
            val next = TelegraphParser.findNext(curHtml, curUrl) ?: break
            val nextHtml = Net.getText(next)
            TelegraphParser.parse(nextHtml, next).images.forEach { if (!imgs.contains(it)) imgs.add(it) }
            curHtml = nextHtml
            curUrl = next
            guard++
        }
        return base.copy(images = imgs)
    }

    /** t.me 消息 / 频道：取消息里的图片，并递归跟进消息里出现的 telegra.ph 链接 */
    private suspend fun loadTelegram(url: String): TelegraphParser.Article {
        val channel = TgParser.load(url)
            ?: throw IllegalStateException(
                "抓不到这条消息：可能是私有频道（t.me/c/…）、频道没开网页预览，或消息已删除"
            )
        val imgs = LinkedHashSet<String>()
        var title = ""
        var author = channel.name

        for (p in channel.posts) {
            p.images.forEach { imgs.add(it) }
            if (title.isBlank() && p.text.isNotBlank()) {
                title = p.text.replace(Regex("""\s+"""), " ").trim().take(48)
            }
            for (link in p.links.take(3)) {
                runCatching {
                    val art = loadTelegraph(link)
                    art.images.forEach { imgs.add(it) }
                    if (title.isBlank()) title = art.title
                    if (author.isBlank()) author = art.author
                }
            }
        }
        if (imgs.isEmpty()) {
            throw IllegalStateException("这条消息里没有可解析的图片（纯文字或视频消息）")
        }
        val finalTitle = title.ifBlank {
            if (channel.posts.size == 1) "${channel.name} 的消息"
            else "${channel.name} · 最近 ${channel.posts.size} 条"
        }
        return TelegraphParser.Article(url, finalTitle, author, imgs.toList())
    }

    private fun startImport(urls: List<String>) {
        val list = urls.distinct().filter { TelegraphParser.isSupported(it) }
        if (list.isEmpty()) {
            message = "没识别到 telegra.ph 链接"
            return
        }
        clipLinks = emptyList()
        lifecycleScope.launch {
            importState = ImportState.Loading(list.first(), list.size)
            val parsed = mutableListOf<Parsed>()
            var lastErr = ""
            var failedUrl = ""
            for (u in list) {
                try {
                    val art = loadArticle(u)
                    if (art.images.isEmpty()) {
                        lastErr = "页面里没找到图片"
                        failedUrl = u
                        lib.addLink(u, art.title, "fail", lastErr)
                    } else {
                        parsed.add(Parsed(u, art.title, art.author, art.images))
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "网络或解析异常"
                    failedUrl = u
                    lib.addLink(u, "未知标题", "fail", lastErr)
                }
            }
            importState = if (parsed.isEmpty()) ImportState.Failed(lastErr.ifBlank { "解析失败" }, failedUrl)
            else ImportState.Ready(parsed)
        }
    }

    private fun confirmImport(items: List<Parsed>) {
        lifecycleScope.launch {
            var created = 0
            var merged = 0
            for (p in items) {
                val (_, added, isNew) = lib.upsert(p.title, p.url, p.images, p.author)
                if (isNew) {
                    created++
                    lib.addLink(p.url, p.title, "ok", "${p.images.size} 页")
                } else {
                    merged += added
                    lib.addLink(p.url, p.title, if (added > 0) "merged" else "dup", "新增 $added 页")
                }
            }
            saveAsync()
            importState = ImportState.Idle
            clipLinks = emptyList()
            dismissedClipKey = items.joinToString("|") { it.url }
            message = when {
                created > 0 && merged > 0 -> "新增 $created 册，另合并 $merged 页"
                created > 0 -> "已加入书架：$created 册"
                merged > 0 -> "已合并 $merged 页进原有册"
                else -> "这些页面已在书架里"
            }
        }
    }

    // ---------------- 导出 ----------------

    private fun ensureAndExport(kind: ExportKind, ids: Set<String>?) {
        val needPerm = Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needPerm) {
            pendingExport = kind to ids
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            performExport(kind, ids)
        }
    }

    private fun performExport(kind: ExportKind, ids: Set<String>?) {
        lifecycleScope.launch {
            if (lib.comics.isEmpty() && lib.links.isEmpty()) {
                message = "书架和链接记录都是空的"
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val content = when (kind) {
                ExportKind.JSON -> lib.exportJson(ids)
                ExportKind.TXT -> lib.exportTxt(ids)
                ExportKind.URLS -> lib.exportUrlList(ids)
            }
            val name = "tgcomic-$stamp.${kind.ext}"
            val res = withContext(Dispatchers.IO) {
                runCatching { lib.writeToDownloads(name, kind.mime, content) }
            }
            message = res.fold({ "已导出：$it" }, { "导出失败：${it.message}" })
        }
    }

    // ---------------- 缓存 ----------------

    private fun cacheComic(c: Comic) {
        if (cacheState != null) return
        cacheJob?.cancel()
        cacheJob = lifecycleScope.launch {
            val total = c.pages.size
            if (total == 0) return@launch
            cacheState = CacheState(c.id, 0, total)
            var ok = 0
            for ((i, url) in c.pages.withIndex()) {
                if (!isActive) break
                val f = lib.pageFile(c.id, i)
                val good = if (f.exists() && f.length() > 1024) true
                else withContext(Dispatchers.IO) { Net.download(url, f) }
                if (good) ok++
                cacheState = CacheState(c.id, i + 1, total)
            }
            val cancelled = !isActive
            cacheState = null
            lib.tick.value++
            if (!cancelled) message = "已缓存 $ok/$total 页，可离线阅读"
        }
    }

    private fun cancelCache() {
        cacheJob?.cancel()
        cacheJob = null
        cacheState = null
        message = "缓存已取消"
    }

    // ---------------- 检查更新 / 分享 ----------------

    private fun checkUpdates() {
        if (lib.comics.isEmpty()) {
            message = "书架是空的，先导入一册"
            return
        }
        if (cacheState != null) {
            message = "正在缓存，稍后再检查更新"
            return
        }
        lifecycleScope.launch {
            val list = lib.comics.toList()
            importState = ImportState.Loading(list.first().sourceUrl, list.size)
            var addedPages = 0
            var failed = 0
            for (c in list) {
                try {
                    val art = loadArticle(c.sourceUrl)
                    if (art.images.isEmpty()) {
                        failed++
                        continue
                    }
                    var n = 0
                    for (p in art.images) {
                        if (!c.pages.contains(p)) {
                            c.pages.add(p)
                            n++
                        }
                    }
                    if (n > 0) {
                        c.updatedAt = System.currentTimeMillis()
                        addedPages += n
                    }
                } catch (e: Exception) {
                    failed++
                }
            }
            importState = ImportState.Idle
            lib.tick.value++
            saveAsync()
            message = when {
                addedPages > 0 && failed > 0 -> "检查完成：新增 $addedPages 页，$failed 册失败"
                addedPages > 0 -> "检查完成：新增 $addedPages 页"
                failed > 0 -> "检查完成：没有新页（$failed 册失败）"
                else -> "检查完成：没有新页"
            }
        }
    }

    private fun shareComic(c: Comic) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, c.title)
            putExtra(Intent.EXTRA_TEXT, "${c.title}\n${c.sourceUrl}")
        }
        runCatching { startActivity(Intent.createChooser(send, "分享到")) }
    }

    private fun copyImageLink(c: Comic, index: Int) {
        val url = c.pages.getOrNull(index) ?: return
        copyToClipboard(this, url)
        message = "已复制第 ${index + 1} 页直链"
    }

    // ---------------- 保存单页到相册 ----------------

    private fun savePage(c: Comic, index: Int) {
        lifecycleScope.launch {
            val url = c.pages.getOrNull(index) ?: return@launch
            message = "保存第 ${index + 1} 页…"
            val f = lib.pageFile(c.id, index)
            val ok = withContext(Dispatchers.IO) {
                if (f.exists() && f.length() > 1024) true else Net.download(url, f)
            }
            if (!ok) {
                message = "图片下载失败"
                return@launch
            }
            val where = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "TG_${c.id}_${index + 1}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TGComic")
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("无法创建相册条目")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    }
                    "Pictures/TGComic"
                }.getOrElse { "保存失败：${it.message}" }
            }
            lib.tick.value++
            message = "第 ${index + 1} 页已保存到 $where"
        }
    }

    // ---------------- 杂项 ----------------

    private fun setBrightness(v: Float) {
        runOnUiThread {
            val attrs = window.attributes
            attrs.screenBrightness = v
            window.attributes = attrs
        }
    }

    private fun saveThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastSaveAt > 1500) {
            lastSaveAt = now
            saveAsync()
        }
    }

    private fun saveAsync() {
        val snapshot = lib
        lifecycleScope.launch(Dispatchers.IO) { snapshot.save() }
    }
}
