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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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

    private companion object {
        /** 一次导入最多处理多少个页面/画廊，防止列表页展开后跑飞 */
        const val MAX_BATCH_TARGETS = 60
    }

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
    private var updateJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var pendingApk: java.io.File? = null
    private var updateUi by mutableStateOf(UpdateUi())
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
        // 注意：启动时不再自动整理书架——合并/清重只在用户点「整理」时执行
        prefs = Prefs(applicationContext)
        EhApi.setSlowMode(prefs.ehSlow)

        theme = prefs.theme
        Glass.apply(paletteOf(theme, systemDark()))

        setContent {
            TGTheme {
                val pal = Glass.palette
                androidx.compose.runtime.SideEffect {
                    applySystemBars(pal.dark)
                }
                Box(Modifier.fillMaxSize()) {
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
                    onCacheMany = { list -> cacheComics(list) },
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
                    onCheckAppUpdate = { checkAppUpdate(silent = false) },
                    onShareComic = { c -> shareComic(c) },
                    onCopyImageLink = { c, i -> copyImageLink(c, i) },
                    update = updateUi,
                    onUpdateAccept = { startUpdateDownload() },
                    onUpdateInstall = { installUpdate() },
                    onUpdateDismiss = { updateUi = UpdateUi() },
                    onClearMessage = { message = "" }
                )

                // 导入冲突弹窗：叠在整页之上（同层 Box，不然会被页面挤到屏幕外）
                conflict?.let { c ->
                    ImportConflictDialog(
                        newTitle = c.incoming.title,
                        newUrl = c.incoming.url,
                        newPages = c.incoming.images.size,
                        oldTitle = c.existing.title,
                        oldUrl = c.existing.sourceUrl,
                        oldPages = c.existing.pages.size,
                        onKeepOld = { resolveConflict("old") },
                        onMerge = { resolveConflict("merge") },
                        onReplace = { resolveConflict("replace") },
                        onKeepBoth = { resolveConflict("both") }
                    )
                }
                }
            }
        }

        handleIntent(intent)
        maybeAutoCheckUpdate()
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
            JmApi.isAlbum(url) -> loadJmAlbum(url)
            JmApi.isChapter(url) -> loadJmChapter(url)
            EhApi.isGalleryPage(url) -> loadEhGallery(url)
            EhApi.isListPage(url) -> throw IllegalStateException("这是 E-Hentai 列表页，需要先展开成多册")
            WnacgApi.isAlbum(url) -> loadWnacgAlbum(url)
            WnacgApi.isViewPage(url) -> TelegraphParser.Article(
                url = url,
                title = "wnacg 单页",
                author = "",
                images = listOf(url)
            )
            WnacgApi.isListPage(url) -> throw IllegalStateException("这是 wnacg 清单页，需要先展开成多册")
            TelegraphParser.isTgLink(url) -> loadTelegram(url)
            TelegraphParser.isImageUrl(url) -> TelegraphParser.Article(
                url = url,
                // 用文件名当标题，别让所有直链都叫「图片直链」被合并成一册
                title = url.substringAfterLast('/').substringBefore('?').ifBlank { "图片直链" },
                author = "",
                images = listOf(url)
            )
            else -> loadTelegraph(url)
        }
    }

    /** 禁漫天堂相册：逐话取 page_arr，图片直链是 CDN 静态文件（不用换令牌） */
    private suspend fun loadJmAlbum(url: String): TelegraphParser.Article {
        val a = JmApi.loadAlbum(url) { done, total ->
            importState = ImportState.Loading(url, total.coerceAtLeast(1), done)
        }
        return TelegraphParser.Article(a.url, a.title, a.author, a.pageUrls, a.cover)
    }

    /** 禁漫天堂单话：/photo/<cid> */
    private suspend fun loadJmChapter(url: String): TelegraphParser.Article {
        val a = JmApi.loadChapter(url) { done, total ->
            importState = ImportState.Loading(url, total.coerceAtLeast(1), done)
        }
        return TelegraphParser.Article(a.url, a.title, a.author, a.pageUrls, a.cover)
    }

    /** 紳士漫畫相册：翻页收齐所有看图页地址，直链在读图/缓存时才换（verify 令牌有时效） */
    private suspend fun loadWnacgAlbum(url: String): TelegraphParser.Article {
        val a = WnacgApi.loadAlbum(url) { done, total ->
            importState = ImportState.Loading(url, total.coerceAtLeast(1), done)
        }
        return TelegraphParser.Article(a.url, a.title, a.author, a.pageUrls, a.cover)
    }

    /** E-Hentai / ExHentai 画廊：取每页的图片页地址，直链在读图/缓存时才换（keystamp 有时效） */
    private suspend fun loadEhGallery(url: String): TelegraphParser.Article {
        val g = EhApi.loadGallery(url, prefs.ehCookie) { done, total ->
            importState = ImportState.Loading(url, total.coerceAtLeast(1), done)
        }
        // EH 的缩略图是横排精灵图，本地裁出第一格当封面（地址有时效，存本地最稳）
        val cover = withContext(Dispatchers.IO) { makeCover(g.url, g.cover, g.coverW, g.coverH) }
        return TelegraphParser.Article(g.url, g.title, g.author, g.pageUrls, cover.ifBlank { g.cover })
    }

    /** 精灵图裁第一格 → filesDir/covers/<id>.jpg，返回 file:// 地址 */
    private fun makeCover(comicUrl: String, sprite: String, cellW: Int, cellH: Int): String {
        if (sprite.isBlank()) return ""
        return runCatching {
            val dir = java.io.File(filesDir, "covers").apply { mkdirs() }
            val out = java.io.File(dir, "${comicIdOf(comicUrl)}.jpg")
            if (out.exists() && out.length() > 1024) return "file://" + out.absolutePath

            val conn = java.net.URL(sprite).openConnection() as java.net.HttpURLConnection
            val bytes = try {
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Referer", Net.autoReferer(sprite))
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
            val w = cellW.coerceIn(1, bmp.width)
            val h = cellH.coerceIn(1, bmp.height)
            val cell = android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h)
            java.io.FileOutputStream(out).use {
                cell.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            if (cell !== bmp) cell.recycle()
            bmp.recycle()
            "file://" + out.absolutePath
        }.getOrDefault("")
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
        if (TgParser.isPrivate(url)) {
            throw IllegalStateException(
                "t.me/c/… 是私有频道内部链接，未登录抓不到。请在 Telegram 里对那条消息「复制链接」再试"
            )
        }
        val link = TgParser.parseLink(url)
            ?: throw IllegalStateException("认不出这个 Telegram 链接形态：$url")
        val channel = TgParser.load(url)
            ?: throw IllegalStateException(
                if (link.wholeChannel)
                    "抓不到 @${link.channel}：可能是私有频道、没开「网页预览」，或频道已注销"
                else
                    "抓不到 @${link.channel} 的第 ${link.msgId} 条：可能是频道关了网页预览、消息已删除，" +
                        "或这条消息本身没图片（视频 / 纯文字 / 评论区消息）"
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
        // 整个频道导入时用「频道名 · 最近 N 条」当标题：帖子正文容易是半句话，不适合当书名
        val finalTitle = if (channel.posts.size > 1) {
            "${channel.name} · 最近 ${channel.posts.size} 条"
        } else {
            title.ifBlank { "${channel.name} 的消息" }
        }
        return TelegraphParser.Article(url, finalTitle, author, imgs.toList())
    }

    private fun startImport(urls: List<String>) {
        val list = urls.distinct().filter { TelegraphParser.isSupported(it) }
        if (list.isEmpty()) {
            message = "没识别到可导入的链接"
            return
        }
        clipLinks = emptyList()
        lifecycleScope.launch {
            importState = ImportState.Loading(list.first(), list.size, 0)

            // 列表页（E-Hentai 搜索页 / wnacg 清单页）：先展开成一批相册链接（批量识别）
            val work = mutableListOf<String>()
            for ((i, u) in list.withIndex()) {
                val isEhList = EhApi.isListPage(u)
                val isWnList = WnacgApi.isListPage(u)
                if (!isEhList && !isWnList) {
                    work.add(u)
                    continue
                }
                importState = ImportState.Loading(u, list.size, i)
                val refs: List<String> = try {
                    if (isWnList) WnacgApi.expandList(u).map { it.url }
                    else EhApi.expandList(u, prefs.ehCookie).map { it.url }
                } catch (e: Exception) {
                    lib.addLink(u, if (isWnList) "wnacg 清单页" else "E-Hentai 列表页", "fail", e.message ?: "列表展开失败")
                    emptyList()
                }
                if (refs.isEmpty()) {
                    if (lib.links.none { it.url == u }) {
                        lib.addLink(u, if (isWnList) "wnacg 清单页" else "E-Hentai 列表页", "fail", "没解析到相册")
                    }
                } else {
                    work.addAll(refs)
                }
            }
            val targets = work.distinct().take(MAX_BATCH_TARGETS)

            val parsed = mutableListOf<Parsed>()
            var lastErr = ""
            var failedUrl = ""
            for ((i, u) in targets.withIndex()) {
                importState = ImportState.Loading(u, targets.size, i)
                try {
                    val art = loadArticle(u)
                    if (art.images.isEmpty()) {
                        lastErr = "页面里没找到图片"
                        failedUrl = u
                        lib.addLink(u, art.title, "fail", lastErr)
                    } else {
                        parsed.add(Parsed(u, art.title, art.author, art.images, art.cover))
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

    /** 导入时发现的重复：让用户选保留哪个 */
    private var conflict by mutableStateOf<ImportConflict?>(null)

    private data class ImportConflict(
        val incoming: Parsed,
        val existing: Comic,
        val rest: List<Parsed>
    )

    private fun confirmImport(items: List<Parsed>) {
        // 单条导入才问「发现重复」；批量导入/从链接一键恢复一律静默合并，
        // 免得恢复 50 条链接时要连点 50 次弹窗（整理交给手动）
        if (items.size == 1) {
            val p = items.first()
            val ex = runCatching { lib.findConflict(p.title, p.url, p.images, p.cover) }.getOrNull()
            if (ex != null) {
                importState = ImportState.Idle
                conflict = ImportConflict(p, ex, emptyList())
                return
            }
        }
        lifecycleScope.launch {
            var created = 0
            var merged = 0
            for (p in items) {
                val (_, added, isNew) = lib.upsert(p.title, p.url, p.images, p.author, p.cover)
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
            // 导入只按标题补页，不做任何系列归并；有可并的组就提示一句，让用户自己决定
            val pending = runCatching { lib.planOrganize(true, false).seriesGroups.size }.getOrDefault(0)
            val tip = if (pending > 0) " · 有 $pending 组同系列可整理" else ""
            message = when {
                created > 0 && merged > 0 -> "新增 $created 册，另合并 $merged 页$tip"
                created > 0 -> "已加入书架：$created 册$tip"
                merged > 0 -> "已合并 $merged 页进原有册$tip"
                else -> "这些页面已在书架里"
            }
        }
    }

    /** 冲突解决后：应用用户的选择，再继续导入后面的链接 */
    private fun resolveConflict(choice: String) {
        val c = conflict ?: return
        val p = c.incoming
        when (choice) {
            "old" -> {
                lib.addLink(p.url, p.title, "dup", "保留了书架原有的那册")
                message = "已保留原册，跳过这一条"
            }
            "merge" -> {
                val n = lib.mergeInto(c.existing.id, p.title, p.images)
                lib.addLink(p.url, p.title, "merged", "合并 $n 页进原册")
                message = if (n > 0) "已合并 $n 页进原册" else "页面完全相同，没有新增"
            }
            "replace" -> {
                lib.replaceComic(c.existing.id, p.title, p.url, p.images, p.author, p.cover)
                lib.addLink(p.url, p.title, "ok", "替换了原有那册")
                message = "已用新导入的替换原册"
            }
            else -> {
                lib.addComic(p.title, p.url, p.images, p.author, p.cover)
                lib.addLink(p.url, p.title, "ok", "作为新册保留")
                message = "已作为新的一册加入书架"
            }
        }
        saveAsync()
        conflict = null
        if (c.rest.isNotEmpty()) confirmImport(c.rest)
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

    private fun cacheComic(c: Comic) = cacheComics(listOf(c))

    /** 批量缓存：按顺序一册一册下，进度按当前这册显示（多册时显示 第 i/n 册） */
    private fun cacheComics(targets: List<Comic>) {
        if (cacheState != null) {
            message = "已经在缓存了，等这一轮结束"
            return
        }
        val list = targets.filter { it.pages.isNotEmpty() }
        if (list.isEmpty()) {
            message = "这些册子没有可缓存的页面"
            return
        }
        cacheJob?.cancel()
        cacheJob = lifecycleScope.launch {
            var okTotal = 0
            var finished = 0
            val pagesTotal = list.sumOf { it.pages.size }
            for ((ci, c) in list.withIndex()) {
                val total = c.pages.size
                var ok = 0
                for ((i, url) in c.pages.withIndex()) {
                    if (!isActive) break
                    val f = lib.pageFile(c.id, i)
                    cacheState = CacheState(c.id, i, total, c.title, ci + 1, list.size)
                    // EH 解析可能抛异常（限速/封禁/网络），这里必须兜住，否则整个缓存任务会被打断
                    val direct = runCatching { resolveIfNeeded(c, i) }.getOrNull()
                    val good = if (f.exists() && f.length() > 1024) true
                    else withContext(Dispatchers.IO) { Net.download(direct ?: url, f) }
                    if (good) {
                        ok++
                        lib.invalidateCacheCount(c.id)   // 让书架上的「⤓N」立刻对
                    }
                    cacheState = CacheState(c.id, i + 1, total, c.title, ci + 1, list.size)
                }
                okTotal += ok
                if (isActive) finished++
            }
            val cancelled = !isActive
            cacheState = null
            lib.invalidateCacheCount()
            lib.tick.value++
            lib.tick.value++
            if (!cancelled) {
                message = if (list.size == 1) "已缓存 $okTotal/$pagesTotal 页，可离线阅读"
                else "已缓存 $finished 册 · $okTotal/$pagesTotal 页"
            }
        }
    }

    /** 来源页面（EH / wnacg）在缓存前先解析成直链；其它来源原样返回（解析失败返回 null → 退回原始 URL） */
    private suspend fun resolveIfNeeded(c: Comic, i: Int): String? = withContext(Dispatchers.IO) {
        val url = c.pages.getOrNull(i) ?: return@withContext null
        // 只换直链，不改写 pages：页面地址才是长期有效的身份
        PageResolver.direct(url, prefs.ehCookie)
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
                if (f.exists() && f.length() > 1024) true
                else Net.download(runCatching { resolveIfNeeded(c, index) }.getOrNull() ?: url, f)
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

    // ---------------- 软件更新 ----------------

    /** 启动时静默检查：自动更新开启且距上次检查超过 12 小时 */
    private fun maybeAutoCheckUpdate() {
        if (!prefs.autoUpdate) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < 12 * 3600_000L) return
        checkAppUpdate(silent = true)
    }

    private fun checkAppUpdate(silent: Boolean) {
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch {
            if (!silent) message = "正在检查软件更新…"
            val info = Updater.check(BuildConfig.VERSION_NAME)
            prefs.lastUpdateCheck = System.currentTimeMillis()
            if (info == null) {
                if (!silent) message = "已经是最新版本 v${BuildConfig.VERSION_NAME}"
                return@launch
            }
            updateUi = UpdateUi(info = info)
            message = "发现新版本 ${info.tag}"
            if (prefs.autoUpdate) startUpdateDownload()
        }
    }

    private fun startUpdateDownload() {
        val info = updateUi.info ?: return
        // 注意：这里不能复用 updateJob —— 自动更新时是在「检查」那个协程里调过来的，
        // 用它做守卫会把自己挡掉，导致有新版也不自动下
        if (updateDownloadJob?.isActive == true) return
        updateDownloadJob = lifecycleScope.launch {
            updateUi = updateUi.copy(progress = 0f, ready = false, error = "")
            val apk = Updater.download(this@MainActivity, info) { done, total ->
                updateUi = updateUi.copy(
                    progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                )
            }
            if (apk == null) {
                updateUi = updateUi.copy(progress = null, error = "下载失败，稍后再试")
                return@launch
            }
            pendingApk = apk
            updateUi = updateUi.copy(progress = 1f, ready = true)
            message = "新版本已下载，点「安装更新」"
        }
    }

    private fun installUpdate() {
        val apk = pendingApk ?: return
        if (!Updater.canInstall(this)) {
            message = "请先允许「安装未知应用」"
            Updater.openInstallPermission(this)
            return
        }
        if (!Updater.install(this, apk)) message = "打不开系统安装器"
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
