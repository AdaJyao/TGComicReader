package com.lcs.tgcomic

import android.content.Context
import android.os.Build
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一册漫画（一个 telegra.ph 页面 = 若干图片页） */
data class Comic(
    val id: String,
    var title: String,
    val sourceUrl: String,
    // 用 SnapshotStateList：EH 图片页在后台解析成直链时，界面能自动重组
    val pages: MutableList<String> = mutableStateListOf(),
    var lastIndex: Int = 0,
    var addedAt: Long = System.currentTimeMillis(),
    var author: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var favorite: Boolean = false,
    /** 封面缩略图直链（EH 用画廊缩略图；为空则用第一页） */
    var cover: String = ""
)

/** 分享/粘贴/剪贴板进来的链接记录（列表类） */
data class LinkRecord(
    val url: String,
    var title: String,
    var status: String,      // ok / merged / dup / fail
    var detail: String,
    val at: Long = System.currentTimeMillis()
)

object Titles {
    private val pageSuffix = Regex("""\s*[-–—]?\s*Page\s*\d+\s*$""", RegexOption.IGNORE_CASE)
    private val zhPageSuffix = Regex("""\s*[-–—]?\s*第\s*\d+\s*[页頁]\s*$""")
    private val noise = Regex("""\s*[–—-]\s*Telegraph\s*$""", RegexOption.IGNORE_CASE)

    /** 去掉 " - Page 1" 之类的页码尾巴，用于把同一册的多页帖子合并 */
    fun normalize(raw: String): String {
        var t = raw.trim()
        t = noise.replace(t, "")
        t = pageSuffix.replace(t, "")
        t = zhPageSuffix.replace(t, "")
        return t.trim()
    }
}

private fun md5(s: String): String {
    val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
    return d.joinToString("") { "%02x".format(it) }
}

fun comicIdOf(url: String): String = md5(url).substring(0, 12)

/** 相对时间：刚刚 / 12 分钟前 / 3 小时前 / 5 天前 / 03-14 */
fun relTime(ts: Long): String {
    if (ts <= 0L) return ""
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000L -> "刚刚"
        d < 3_600_000L -> "${d / 60_000L} 分钟前"
        d < 86_400_000L -> "${d / 3_600_000L} 小时前"
        d < 7 * 86_400_000L -> "${d / 86_400_000L} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

/** 排序方式 */
enum class SortMode(val label: String) {
    RECENT("最近阅读"),
    ADDED("最近添加"),
    TITLE("标题 A-Z"),
    PAGES("页数最多")
}

/** 阅读方向 */
enum class ReaderMode(val label: String) {
    VERTICAL("连续滚动"),
    PAGED("左右翻页")
}

/** 阅读底色 */
enum class ReaderBg(val label: String, val color: androidx.compose.ui.graphics.Color) {
    BLACK("纯黑", androidx.compose.ui.graphics.Color(0xFF000000)),
    PAPER("纸白", androidx.compose.ui.graphics.Color(0xFFF4F1EA)),
    MINT("护眼", androidx.compose.ui.graphics.Color(0xFFE7F2E4))
}

/** 轻量设置存储 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tgcomic_prefs", Context.MODE_PRIVATE)

    var theme: AppTheme
        get() = runCatching { AppTheme.valueOf(sp.getString("theme", AppTheme.AURORA.name)!!) }
            .getOrDefault(AppTheme.AURORA)
        set(v) = sp.edit().putString("theme", v.name).apply()

    var grid: Boolean
        get() = sp.getBoolean("grid", true)
        set(v) = sp.edit().putBoolean("grid", v).apply()

    var columns: Int
        get() = sp.getInt("columns", 3)
        set(v) = sp.edit().putInt("columns", v).apply()

    var sort: SortMode
        get() = runCatching { SortMode.valueOf(sp.getString("sort", SortMode.RECENT.name)!!) }
            .getOrDefault(SortMode.RECENT)
        set(v) = sp.edit().putString("sort", v.name).apply()

    var readerMode: ReaderMode
        get() = runCatching { ReaderMode.valueOf(sp.getString("readerMode", ReaderMode.VERTICAL.name)!!) }
            .getOrDefault(ReaderMode.VERTICAL)
        set(v) = sp.edit().putString("readerMode", v.name).apply()

    var readerBg: ReaderBg
        get() = runCatching { ReaderBg.valueOf(sp.getString("readerBg", ReaderBg.BLACK.name)!!) }
            .getOrDefault(ReaderBg.BLACK)
        set(v) = sp.edit().putString("readerBg", v.name).apply()

    var favoritesFirst: Boolean
        get() = sp.getBoolean("favFirst", true)
        set(v) = sp.edit().putBoolean("favFirst", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepOn", true)
        set(v) = sp.edit().putBoolean("keepOn", v).apply()

    /** ExHentai / E-Hentai 登录 Cookie（ipb_member_id=...; ipb_pass_hash=...; ignorant=1） */
    var ehCookie: String
        get() = sp.getString("ehCookie", "") ?: ""
        set(v) = sp.edit().putString("ehCookie", v.trim()).apply()

    /** 自动检查并下载新版本（安装仍需系统确认） */
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    /** 上次检查更新的时间戳 */
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()
}

class Library(private val ctx: Context) {

    val comics = mutableStateListOf<Comic>()
    val links = mutableStateListOf<LinkRecord>()

    /** 缓存/文件类变更的版本号，用于触发 UI 重组 */
    val tick = mutableStateOf(0)

    private val file = File(ctx.filesDir, "library.json")
    private val comicDir = File(ctx.filesDir, "comics")

    // ---------------- 读写 ----------------

    fun load() {
        comics.clear()
        links.clear()
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("comics") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pages = mutableStateListOf<String>()
                val pa = o.optJSONArray("pages") ?: JSONArray()
                for (j in 0 until pa.length()) pages.add(pa.getString(j))
                comics.add(
                    Comic(
                        id = o.optString("id", comicIdOf(o.optString("url", ""))),
                        title = o.optString("title", "未命名"),
                        sourceUrl = o.optString("url", ""),
                        pages = pages,
                        lastIndex = o.optInt("lastIndex", 0),
                        addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                        author = o.optString("author", ""),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        favorite = o.optBoolean("favorite", false),
                        cover = o.optString("cover", "")
                    )
                )
            }
            val la = root.optJSONArray("links") ?: JSONArray()
            for (i in 0 until la.length()) {
                val o = la.getJSONObject(i)
                links.add(
                    LinkRecord(
                        url = o.optString("url", ""),
                        title = o.optString("title", ""),
                        status = o.optString("status", "ok"),
                        detail = o.optString("detail", ""),
                        at = o.optLong("at", 0L)
                    )
                )
            }
        }
        sortLinks()
    }

    fun sortLinks() {
        val ls = links.sortedByDescending { it.at }
        links.clear()
        links.addAll(ls)
    }

    fun save() {
        runCatching { file.parentFile?.mkdirs() }
        val root = JSONObject()
        root.put("version", 2)
        root.put("savedAt", System.currentTimeMillis())
        val ca = JSONArray()
        for (c in comics) {
            ca.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("url", c.sourceUrl)
                    put("lastIndex", c.lastIndex)
                    put("addedAt", c.addedAt)
                    put("updatedAt", c.updatedAt)
                    put("author", c.author)
                    put("favorite", c.favorite)
                    put("cover", c.cover)
                    put("pages", JSONArray(c.pages))
                }
            )
        }
        root.put("comics", ca)
        val la = JSONArray()
        for (l in links) {
            la.put(
                JSONObject().apply {
                    put("url", l.url); put("title", l.title)
                    put("status", l.status); put("detail", l.detail); put("at", l.at)
                }
            )
        }
        root.put("links", la)
        // 原子写：先写 .tmp 再 rename，避免写一半被杀掉留下残文件导致整库丢失
        runCatching {
            val tmp = File(ctx.filesDir, "library.json.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    // ---------------- 业务 ----------------

    /** 是否已在书架。链接记录不参与判断，失败过的链接允许再次识别 */
    fun containsUrl(url: String): Boolean = comics.any { it.sourceUrl == url }

    fun addLink(url: String, title: String, status: String, detail: String = "") {
        val idx = links.indexOfFirst { it.url == url }
        if (idx >= 0) {
            links[idx] = links[idx].copy(title = title.ifBlank { links[idx].title }, status = status, detail = detail)
        } else {
            links.add(0, LinkRecord(url, title, status, detail))
        }
    }

    fun findSameVolume(title: String): Comic? {
        val n = Titles.normalize(title)
        if (n.isBlank()) return null
        return comics.firstOrNull { Titles.normalize(it.title).equals(n, ignoreCase = true) }
    }

    /** 返回 (comic, 新增页数, 是否新建) */
    fun upsert(
        title: String,
        url: String,
        pages: List<String>,
        author: String,
        cover: String = ""
    ): Triple<Comic, Int, Boolean> {
        val exist = findSameVolume(title)
        if (exist == null) {
            val c = Comic(
                id = comicIdOf(url),
                title = title,
                sourceUrl = url,
                pages = mutableStateListOf<String>().also { it.addAll(pages) },
                author = author,
                cover = cover
            )
            comics.add(0, c)
            tick.value++
            return Triple(c, pages.size, true)
        }
        var added = 0
        for (p in pages) if (!exist.pages.contains(p)) { exist.pages.add(p); added++ }
        // 分页标题（"xxx - Page 2" / "xxx 第2页"）合并时把页码尾巴剥掉，中英文都要管
        val stripped = Titles.normalize(exist.title)
        if (stripped.isNotBlank() && stripped != exist.title) exist.title = stripped
        if (exist.author.isBlank() && author.isNotBlank()) exist.author = author
        if (exist.cover.isBlank() && cover.isNotBlank()) exist.cover = cover
        exist.updatedAt = System.currentTimeMillis()
        tick.value++
        return Triple(exist, added, false)
    }

    fun removeComic(id: String) {
        comics.removeAll { it.id == id }
        File(comicDir, id).deleteRecursively()
        runCatching { File(File(ctx.filesDir, "covers"), "$id.jpg").delete() }
        tick.value++
    }

    fun removeComics(ids: Collection<String>) {
        ids.forEach { removeComic(it) }
    }

    fun toggleFavorite(id: String): Boolean {
        val c = comics.firstOrNull { it.id == id } ?: return false
        c.favorite = !c.favorite
        c.updatedAt = System.currentTimeMillis()
        tick.value++
        return c.favorite
    }

    fun rename(id: String, newTitle: String) {
        val c = comics.firstOrNull { it.id == id } ?: return
        if (newTitle.isNotBlank()) {
            c.title = newTitle.trim()
            tick.value++
        }
    }

    fun removeLink(url: String) {
        links.removeAll { it.url == url }
    }

    fun clearLinks() = links.clear()

    fun sortComics(mode: SortMode, favoritesFirst: Boolean): List<Comic> {
        val base = when (mode) {
            SortMode.RECENT -> comics.sortedByDescending { it.updatedAt }
            SortMode.ADDED -> comics.sortedByDescending { it.addedAt }
            SortMode.TITLE -> comics.sortedBy { it.title.lowercase() }
            SortMode.PAGES -> comics.sortedByDescending { it.pages.size }
        }
        return if (favoritesFirst) base.sortedByDescending { it.favorite } else base
    }

    // ---------------- 本地缓存页 ----------------

    fun pageFile(comicId: String, index: Int): File =
        File(File(comicDir, comicId), "%04d.img".format(index))

    fun cachedPageCount(comicId: String): Int {
        val d = File(comicDir, comicId)
        if (!d.isDirectory) return 0
        // 与 pageModel 的判定保持一致：>1KB 才算一张有效页
        return d.listFiles()?.count { it.length() > 1024 } ?: 0
    }

    fun clearCache(comicId: String) {
        File(comicDir, comicId).deleteRecursively()
        tick.value++
    }

    // ---------------- 导出 ----------------

    fun exportJson(onlyIds: Set<String>? = null): String {
        val root = JSONObject()
        root.put("app", "TGComicReader")
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        val list = if (onlyIds == null) comics else comics.filter { it.id in onlyIds }
        root.put("count", list.size)
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("url", c.sourceUrl)
                    put("author", c.author)
                    put("favorite", c.favorite)
                    put("pageCount", c.pages.size)
                    put("lastIndex", c.lastIndex)
                    put("pages", JSONArray(c.pages))
                }
            )
        }
        root.put("comics", arr)
        val la = JSONArray()
        for (l in links) {
            la.put(JSONObject().apply {
                put("url", l.url); put("title", l.title)
                put("status", l.status); put("at", l.at)
            })
        }
        root.put("links", la)
        return root.toString(2)
    }

    fun exportTxt(onlyIds: Set<String>? = null): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val list = if (onlyIds == null) comics else comics.filter { it.id in onlyIds }
        sb.append("# TG漫画 书架导出\n# 时间: ").append(now).append("\n# 册数: ").append(list.size).append("\n\n")
        list.forEachIndexed { i, c ->
            sb.append("[").append(i + 1).append("] ").append(c.title)
            if (c.favorite) sb.append("  ★")
            sb.append("\n")
            sb.append("    来源: ").append(c.sourceUrl).append("\n")
            if (c.author.isNotBlank()) sb.append("    作者: ").append(c.author).append("\n")
            sb.append("    页数: ").append(c.pages.size).append("（读到第 ").append(c.lastIndex + 1).append(" 页）\n")
            sb.append("    图片:\n")
            c.pages.forEachIndexed { j, p -> sb.append("      ").append(j + 1).append(". ").append(p).append("\n") }
            sb.append("\n")
        }
        if (links.isNotEmpty()) {
            sb.append("=== 链接记录 ===\n")
            val f = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            for (l in links) {
                sb.append(f.format(Date(l.at))).append("  [").append(l.status).append("]  ")
                    .append(l.title.ifBlank { "(无标题)" }).append("\n    ").append(l.url).append("\n")
            }
        }
        return sb.toString()
    }

    fun exportUrlList(onlyIds: Set<String>? = null): String {
        val list = if (onlyIds == null) comics else comics.filter { it.id in onlyIds }
        return list.joinToString("\n") { it.sourceUrl }
    }

    /** 写到 公共下载目录/TGComic/ ，返回可读路径 */
    fun writeToDownloads(name: String, mime: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/TGComic")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            return "Download/TGComic/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TGComic")
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            return f.absolutePath
        }
    }
}
