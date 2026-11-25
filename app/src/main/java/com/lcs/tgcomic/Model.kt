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
    var cover: String = "",
    /** 系列键：站点 + 去掉「第N话/第N章/Ch.N」后的标题，同一系列的多次导入会并进同一册 */
    var seriesKey: String = "",
    /** 每章的起始页下标（chapterMarks[0] 恒为 0，size>1 说明这册是多话合并的） */
    val chapterMarks: MutableList<Int> = mutableStateListOf(),
    /** 每章标题（与 chapterMarks 一一对应） */
    val chapterNames: MutableList<String> = mutableStateListOf()
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

    /**
     * 章节尾巴：「第3话 / 第3話 / 第三话 / 第12章 / Ch.3 / Chapter 3 / Vol.2 / (3) / 3話」
     * 命中就剥掉，剩下的当系列名——这样「XXX 第1话」「XXX 第2话」会归到同一册。
     */
    private val chapterTail = Regex(
        """[\s\-_–—·:：]*[\(\[（【]?\s*(?:第\s*[0-9一二三四五六七八九十百]+\s*[话話章卷集部篇回]|ch(?:apter)?\.?\s*\d+|ep(?:isode)?\.?\s*\d+|vol(?:ume)?\.?\s*\d+|\d+\s*[话話章卷])\s*[\)\]）】]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** 纯数字尾巴：「夢幻家教 01」「XXX (3)」「XXX [2]」 */
    private val trailingNo = Regex(
        """[\s\-_–—·:：,，]*(?:[\(\[（【]?\s*\d{1,4}\s*[\)\]）】]?)\s*$"""
    )

    /** 系列名：连续剥两次，兼容「XXX 第3话 上」这种；最后再剥掉纯数字尾巴 */
    fun series(raw: String): String {
        var t = raw.trim()
        repeat(2) {
            val n = chapterTail.replace(t, "").trim()
            if (n.isNotBlank()) t = n
        }
        val noTail = trailingNo.replace(t, "").trim()
        if (noTail.isNotBlank()) t = noTail
        return t.trim().trimEnd('-', '_', '·', ':', '：').trim()
    }

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

    /** 阅读时提前解析/预载后面几页（0 = 关闭） */
    var prefetchAhead: Int
        get() = sp.getInt("prefetchAhead", 4).coerceIn(0, 8)
        set(v) = sp.edit().putInt("prefetchAhead", v.coerceIn(0, 8)).apply()

    /** 音量键翻页：音量+ 上一页、音量- 下一页 */
    var volumeKeys: Boolean
        get() = sp.getBoolean("volumeKeys", false)
        set(v) = sp.edit().putBoolean("volumeKeys", v).apply()

    /** ExHentai / E-Hentai 登录 Cookie（ipb_member_id=...; ipb_pass_hash=...; ignorant=1） */
    var ehCookie: String
        get() = sp.getString("ehCookie", "") ?: ""
        set(v) = sp.edit().putString("ehCookie", v.trim()).apply()

    // ---- WebDAV 备份 ----
    var webdavUrl: String
        get() = sp.getString("webdavUrl", "") ?: ""
        set(v) = sp.edit().putString("webdavUrl", v.trim()).apply()

    var webdavUser: String
        get() = sp.getString("webdavUser", "") ?: ""
        set(v) = sp.edit().putString("webdavUser", v.trim()).apply()

    var webdavPass: String
        get() = sp.getString("webdavPass", "") ?: ""
        set(v) = sp.edit().putString("webdavPass", v).apply()

    val webdavReady: Boolean
        get() = webdavUrl.isNotBlank()

    /** 自动检查并下载新版本（安装仍需系统确认） */
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    /** E-Hentai 稳妥模式：请求间隔翻倍，宁可慢也别被限速封 IP */
    var ehSlow: Boolean
        get() = sp.getBoolean("ehSlow", false)
        set(v) = sp.edit().putBoolean("ehSlow", v).apply()

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
                        cover = o.optString("cover", ""),
                        seriesKey = o.optString("seriesKey", ""),
                        chapterMarks = mutableStateListOf<Int>().also { l ->
                            val ma = o.optJSONArray("chapterMarks")
                            if (ma == null || ma.length() == 0) l.add(0)
                            else for (k in 0 until ma.length()) l.add(ma.getInt(k))
                        },
                        chapterNames = mutableStateListOf<String>().also { l ->
                            val na = o.optJSONArray("chapterNames")
                            if (na != null) for (k in 0 until na.length()) l.add(na.getString(k))
                        }
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
                    put("seriesKey", c.seriesKey)
                    put("chapterMarks", JSONArray(c.chapterMarks.toList()))
                    put("chapterNames", JSONArray(c.chapterNames.toList()))
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

    /** 站点名（host 去掉 www.），用于「同站同名才算一个系列」 */
    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').lowercase().removePrefix("www.")

    /** 系列键：站点 + 归一化系列名 */
    fun seriesKeyOf(title: String, url: String): String {
        val host = hostOf(url)
        val series = Titles.series(Titles.normalize(title))
        if (series.isBlank()) return ""
        return host + "|" + series.lowercase()
    }

    /** 同标题（同一册的分页帖子） */
    fun findSameVolume(title: String): Comic? {
        val n = Titles.normalize(title)
        if (n.isBlank()) return null
        return comics.firstOrNull { Titles.normalize(it.title).equals(n, ignoreCase = true) }
    }

    /** 同一系列的已有册子 */
    fun findSameSeries(title: String, url: String): Comic? {
        val key = seriesKeyOf(title, url)
        if (key.isBlank()) return null
        return comics.firstOrNull { it.seriesKey.isNotEmpty() && it.seriesKey == key }
            ?: comics.firstOrNull { seriesKeyOf(it.title, it.sourceUrl) == key }
    }

    /**
     * 多维查重（严格版）。telegra.ph 这类文章页经常共用第一张图/同一套模板，
     * 所以「同首图」不再单独算重复，必须再叠一层「页面有实质重合」。
     *
     *   1. 同一来源页（host + /album/123、/g/123、/photo/123 这类 id 相同）
     *   2. 页数相同 + 页面重合度 ≥ 90%（且至少 3 页）
     *   3. 页数相同 + 首图相同 + 页面重合度 ≥ 50%
     */
    fun findDuplicate(title: String, url: String, pages: List<String>, cover: String): Comic? {
        val urlKey = canonicalUrlKey(url)
        comics.firstOrNull { canonicalUrlKey(it.sourceUrl) == urlKey }?.let { return it }

        if (pages.size < 3) return null
        val incoming = pages.map { it.substringBefore('?') }.toSet()
        comics.firstOrNull { c ->
            if (c.pages.size != incoming.size) return@firstOrNull false
            val mine = c.pages.map { it.substringBefore('?') }.toSet()
            val inter = mine.count { it in incoming }
            inter.toDouble() / incoming.size >= 0.9
        }?.let { return it }

        val first = pages.firstOrNull()?.substringBefore('?') ?: return null
        comics.firstOrNull { c ->
            if (c.pages.size != incoming.size) return@firstOrNull false
            val theirFirst = c.pages.firstOrNull()?.substringBefore('?') ?: return@firstOrNull false
            if (theirFirst != first) return@firstOrNull false
            val mine = c.pages.map { it.substringBefore('?') }.toSet()
            val inter = mine.count { it in incoming }
            inter.toDouble() / incoming.size >= 0.5
        }?.let { return it }
        return null
    }

    /** 导入前先问一句：这册是不已经收过（用于弹「保留哪个」的对话框） */
    fun findConflict(title: String, url: String, pages: List<String>, cover: String): Comic? =
        findDuplicate(title, url, pages, cover)

    /** 用户选择「合并进原来那册」 */
    fun mergeInto(existingId: String, title: String, pages: List<String>): Int {
        val c = comics.firstOrNull { it.id == existingId } ?: return 0
        val added = mergePages(c, pages, sameChapter = false, chapterName = title)
        save()
        return added
    }

    /** 用户选择「用新导入替换旧的」 */
    fun replaceComic(existingId: String, title: String, url: String, pages: List<String>, author: String, cover: String): Comic {
        val old = comics.firstOrNull { it.id == existingId }
        val idx = if (old != null) comics.indexOf(old) else 0
        if (old != null) {
            dropCache(old.id)
            comics.removeAll { it.id == existingId }
        }
        val key = seriesKeyOf(title, url)
        val c = Comic(
            id = comicIdOf(url),
            title = title,
            sourceUrl = url,
            pages = mutableStateListOf<String>().also { it.addAll(pages) },
            author = author,
            cover = cover,
            seriesKey = key,
            chapterMarks = mutableStateListOf(0),
            chapterNames = mutableStateListOf(title)
        )
        comics.add(idx.coerceIn(0, comics.size), c)
        tick.value++
        save()
        return c
    }

    /** 用户选择「两个都留」 */
    fun addComic(title: String, url: String, pages: List<String>, author: String, cover: String): Comic {
        val key = seriesKeyOf(title, url)
        val c = Comic(
            id = comicIdOf(url) + "-" + (System.currentTimeMillis() % 100000),
            title = title,
            sourceUrl = url,
            pages = mutableStateListOf<String>().also { it.addAll(pages) },
            author = author,
            cover = cover,
            seriesKey = key,
            chapterMarks = mutableStateListOf(0),
            chapterNames = mutableStateListOf(title)
        )
        comics.add(0, c)
        tick.value++
        save()
        return c
    }

    private fun canonicalUrlKey(url: String): String {
        val u = url.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        val host = hostOf(u)
        val path = u.substringAfter("://", "").substringAfter('/', "")
        // 只要路径里的数字 id：/album/123/标题、/g/123456/abc → album/123、g/123456
        val m = Regex("""(album|g|photo|photos-index-aid|comic|manga|title|book)[^0-9]{0,4}(\d+)""")
            .find(path)
        return if (m != null) host + "|" + m.groupValues[1] + "|" + m.groupValues[2]
        else host + "|" + path.lowercase()
    }

    /** 返回 (comic, 新增页数, 是否新建) */
    fun upsert(
        title: String,
        url: String,
        pages: List<String>,
        author: String,
        cover: String = ""
    ): Triple<Comic, Int, Boolean> {
        // ① 同一册（标题完全一致）：只补页
        findSameVolume(title)?.let { exist ->
            val added = mergePages(exist, pages, sameChapter = true)
            if (exist.author.isBlank() && author.isNotBlank()) exist.author = author
            if (exist.cover.isBlank() && cover.isNotBlank()) exist.cover = cover
            val stripped = Titles.normalize(exist.title)
            if (stripped.isNotBlank() && stripped != exist.title) exist.title = stripped
            return Triple(exist, added, false)
        }
        // ② 已收过（多维查重）：直接返回，不新增
        findDuplicate(title, url, pages, cover)?.let { return Triple(it, 0, false) }
        // ③ 同系列的其它话：并进已有那一册，记一条章节起点
        findSameSeries(title, url)?.let { exist ->
            val added = mergePages(exist, pages, sameChapter = false, chapterName = title)
            if (exist.author.isBlank() && author.isNotBlank()) exist.author = author
            if (exist.cover.isBlank() && cover.isNotBlank()) exist.cover = cover
            exist.updatedAt = System.currentTimeMillis()
            tick.value++
            return Triple(exist, added, false)
        }
        // ④ 新的一册
        val key = seriesKeyOf(title, url)
        val c = Comic(
            id = comicIdOf(url),
            title = title,
            sourceUrl = url,
            pages = mutableStateListOf<String>().also { it.addAll(pages) },
            author = author,
            cover = cover,
            seriesKey = key,
            chapterMarks = mutableStateListOf(0),
            chapterNames = mutableStateListOf(title)
        )
        comics.add(0, c)
        tick.value++
        return Triple(c, pages.size, true)
    }

    /** 把 pages 追加进 comic（按键去重）；sameChapter=false 时记一章新起点 */
    private fun mergePages(
        comic: Comic,
        pages: List<String>,
        sameChapter: Boolean,
        chapterName: String = ""
    ): Int {
        val have = comic.pages.map { it.substringBefore('?') }.toMutableSet()
        val fresh = pages.filter { have.add(it.substringBefore('?')) }
        if (fresh.isEmpty()) return 0
        if (!sameChapter) {
            comic.chapterMarks.add(comic.pages.size)
            comic.chapterNames.add(chapterName)
        }
        comic.pages.addAll(fresh)
        if (comic.chapterMarks.isEmpty()) comic.chapterMarks.add(0)
        if (comic.chapterNames.isEmpty()) comic.chapterNames.add(comic.title)
        comic.updatedAt = System.currentTimeMillis()
        tick.value++
        return fresh.size
    }

    // ---------------- 合并 / 预览 / 撤销 ----------------

    /** 一次合并的结果预览（不含真正的改动） */
    data class MergePlan(
        val ids: List<String>,
        val title: String,
        val totalPages: Int,
        val chapters: List<Pair<String, Int>>   // 章节名 → 页数
    )

    /** 整理前的预览：哪些册会被并成一组 / 哪些算重复 */
    data class OrganizePlan(
        val seriesGroups: List<MergePlan>,
        val duplicatePairs: List<Pair<Comic, Comic>>,
        val before: Int,
        val after: Int
    )

    /** 合并预览：按加入时间排，最早的那册当主册 */
    fun previewMerge(ids: Collection<String>): MergePlan? {
        val list = ids.mapNotNull { id -> comics.firstOrNull { it.id == id } }
            .sortedBy { it.addedAt }
        if (list.size < 2) return null
        val chapters = list.map { c -> (Titles.series(c.title).ifBlank { c.title }) to c.pages.size }
        return MergePlan(
            ids = list.map { it.id },
            title = list.first().title,
            totalPages = list.sumOf { it.pages.size },
            chapters = chapters
        )
    }

    /** 手动合并：早的留名，其余按时间顺序并进去（各算一章） */
    fun mergeComics(ids: Collection<String>, title: String? = null): Comic? {
        val plan = previewMerge(ids) ?: return null
        snapshot()
        val keep = comics.firstOrNull { it.id == plan.ids.first() } ?: return null
        if (!title.isNullOrBlank()) keep.title = title.trim()
        plan.ids.drop(1).forEach { id ->
            val other = comics.firstOrNull { it.id == id } ?: return@forEach
            mergePages(keep, other.pages, sameChapter = false, chapterName = other.title)
            if (keep.cover.isBlank()) keep.cover = other.cover
            if (keep.author.isBlank()) keep.author = other.author
            dropCache(other.id)
            comics.removeAll { it.id == other.id }
        }
        // 合并后系列名统一
        keep.seriesKey = seriesKeyOf(keep.title, keep.sourceUrl)
        tick.value++
        save()
        return keep
    }

    /** 只算不改：整理会做哪些事 */
    fun planOrganize(mergeSameSeries: Boolean, removeDuplicates: Boolean): OrganizePlan {
        val keys = comics.associate { it.id to seriesKeyOf(it.title, it.sourceUrl) }
        val seriesGroups = ArrayList<MergePlan>()
        if (mergeSameSeries) {
            comics.groupBy { keys[it.id].orEmpty() }
                .filterKeys { it.isNotEmpty() }
                .forEach { (_, list) ->
                    if (list.size >= 2) {
                        previewMerge(list.map { it.id })?.let { seriesGroups += it }
                    }
                }
        }
        val dups = ArrayList<Pair<Comic, Comic>>()
        if (removeDuplicates) {
            var i = 0
            val removed = HashSet<String>()
            while (i < comics.size) {
                var j = i + 1
                while (j < comics.size) {
                    val a = comics[i]; val b = comics[j]
                    if (a.id !in removed && b.id !in removed && isDuplicatePair(a, b)) {
                        dups += a to b
                        removed += b.id
                    }
                    j++
                }
                i++
            }
        }
        val after = comics.size -
            dups.size -
            seriesGroups.sumOf { g -> (g.ids.size - 1).coerceAtLeast(0) } +
            dups.count { (a, b) -> seriesGroups.any { it.ids.contains(a.id) && it.ids.contains(b.id) } }
        return OrganizePlan(seriesGroups, dups, comics.size, after.coerceAtMost(comics.size))
    }

    // ---- 撤销：操作前存一份快照，整理/合并后可以一键还原 ----

    private var undoSnapshot: String? = null

    fun canUndo(): Boolean = undoSnapshot != null

    /** 操作前调用：把当前书架序列化存起来（内存里只留最近一次） */
    fun snapshot() {
        undoSnapshot = runCatching { comicsJson().toString() }.getOrNull()
    }

    fun undo(): Boolean {
        val snap = undoSnapshot ?: return false
        return runCatching {
            restoreComics(snap)
            undoSnapshot = null
            tick.value++
            save()
            true
        }.getOrDefault(false)
    }

    fun clearUndo() { undoSnapshot = null }

    private fun comicsJson(): JSONArray {
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
                    put("seriesKey", c.seriesKey)
                    put("chapterMarks", JSONArray(c.chapterMarks.toList()))
                    put("chapterNames", JSONArray(c.chapterNames.toList()))
                }
            )
        }
        return ca
    }

    private fun restoreComics(json: String) {
        val arr = JSONArray(json)
        val restored = ArrayList<Comic>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pages = mutableStateListOf<String>()
            val pa = o.optJSONArray("pages") ?: JSONArray()
            for (j in 0 until pa.length()) pages.add(pa.getString(j))
            restored.add(
                Comic(
                    id = o.optString("id"),
                    title = o.optString("title", "未命名"),
                    sourceUrl = o.optString("url", ""),
                    pages = pages,
                    lastIndex = o.optInt("lastIndex", 0),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                    author = o.optString("author", ""),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    favorite = o.optBoolean("favorite", false),
                    cover = o.optString("cover", ""),
                    seriesKey = o.optString("seriesKey", ""),
                    chapterMarks = mutableStateListOf<Int>().also { l ->
                        val ma = o.optJSONArray("chapterMarks")
                        if (ma == null || ma.length() == 0) l.add(0)
                        else for (k in 0 until ma.length()) l.add(ma.getInt(k))
                    },
                    chapterNames = mutableStateListOf<String>().also { l ->
                        val na = o.optJSONArray("chapterNames")
                        if (na != null) for (k in 0 until na.length()) l.add(na.getString(k))
                    }
                )
            )
        }
        comics.clear()
        comics.addAll(restored)
    }

    data class OrganizeReport(
        val before: Int,
        val after: Int,
        val seriesMerged: Int,
        val dupsRemoved: Int
    )

    /**
     * 整理书架：
     *   · 重算 seriesKey，把同一系列的多册并成一册（保留章节起点）
     *   · removeDuplicates=true 时再按多维规则清掉重复册（保留页数多的那本）
     */
    fun organize(removeDuplicates: Boolean = true, mergeSameSeries: Boolean = true): OrganizeReport {
        snapshot()
        val before = comics.size
        // 每次都重算：规则改了（比如新增「剥掉纯数字尾巴」）老数据才会跟着归并
        comics.forEach { c -> c.seriesKey = seriesKeyOf(c.title, c.sourceUrl) }

        var dups = 0
        if (removeDuplicates) {
            var i = 0
            while (i < comics.size) {
                var j = i + 1
                var restarted = false
                while (j < comics.size) {
                    val a = comics[i]
                    val b = comics[j]
                    if (isDuplicatePair(a, b)) {
                        val keep = if (a.pages.size >= b.pages.size) a else b
                        val drop = if (keep.id == a.id) b else a
                        runCatching { mergePages(keep, drop.pages, sameChapter = true) }
                        if (keep.cover.isBlank()) keep.cover = drop.cover
                        if (keep.author.isBlank()) keep.author = drop.author
                        dropCache(drop.id)
                        comics.remove(drop)
                        dups++
                        restarted = true
                        break
                    }
                    j++
                }
                if (!restarted) i++
            }
        }

        var merged = 0
        val groups = if (mergeSameSeries) comics.filter { it.seriesKey.isNotEmpty() }.groupBy { it.seriesKey }
        else emptyMap()
        groups.forEach { (_, list) ->
            if (list.size < 2) return@forEach
            val keep = list.minByOrNull { it.addedAt } ?: return@forEach
            list.filter { it.id != keep.id }.forEach { other ->
                if (other.pages.isNotEmpty()) {
                    mergePages(keep, other.pages, sameChapter = false, chapterName = other.title)
                }
                if (keep.cover.isBlank()) keep.cover = other.cover
                if (keep.author.isBlank()) keep.author = other.author
                dropCache(other.id)
                comics.removeAll { it.id == other.id }
                merged++
            }
        }

        tick.value++
        if (merged > 0 || dups > 0) save()
        return OrganizeReport(before, comics.size, merged, dups)
    }

    /** UI 用：两个开关各管一件事 */
    fun organizeWith(mergeSameSeries: Boolean, removeDuplicates: Boolean): OrganizeReport =
        organize(removeDuplicates = removeDuplicates, mergeSameSeries = mergeSameSeries)

    /** 启动时的轻量版本：只归并同系列，不删重复（避免静默删书） */
    fun rebuildSeries() {
        val r = organize(removeDuplicates = false)
        if (r.seriesMerged > 0) save()
    }

    /** 两册是否重复（与导入时的判定保持一致） */
    private fun isDuplicatePair(a: Comic, b: Comic): Boolean {
        if (a.id == b.id) return false
        val ka = canonicalUrlKey(a.sourceUrl)
        val kb = canonicalUrlKey(b.sourceUrl)
        if (ka.isNotBlank() && ka == kb) return true

        val pa = a.pages.map { it.substringBefore('?') }
        val pb = b.pages.map { it.substringBefore('?') }
        if (pa.size < 3 || pa.size != pb.size) return false
        val sa = pa.toSet()
        val inter = pb.count { it in sa }
        if (inter.toDouble() / pa.size >= 0.9) return true
        if (pa.first() == pb.first() && inter.toDouble() / pa.size >= 0.5) return true
        return false
    }

    private fun dropCache(id: String) {
        runCatching { File(comicDir, id).deleteRecursively() }
        runCatching { File(File(ctx.filesDir, "covers"), "$id.jpg").delete() }
    }

    fun removeComic(id: String) {
        comics.removeAll { it.id == id }
        dropCache(id)
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

    /**
     * 从导出的书架 JSON 恢复：按来源链接去重，已有的册子会把缺的页补上。
     * @return (新增册数, 被补页的册数)；null = 这个文件根本不是有效的书架备份
     */
    fun importJson(text: String): Pair<Int, Int>? {
        // 有些编辑器存 JSON 会带 BOM，直接 JSONObject() 会解析失败
        val clean = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val root = runCatching { JSONObject(clean) }.getOrNull() ?: return null
        val arr = root.optJSONArray("comics") ?: root.optJSONArray("data") ?: return null
        var added = 0
        var merged = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            val pages = mutableListOf<String>()
            val pa = o.optJSONArray("pages") ?: JSONArray()
            for (j in 0 until pa.length()) pages.add(pa.getString(j))
            val title = o.optString("title", "未命名")
            val cover = o.optString("cover", "")
            val exist = comics.firstOrNull { it.sourceUrl == url }
            if (exist == null) {
                comics.add(
                    Comic(
                        id = o.optString("id").ifBlank { comicIdOf(url) },
                        title = title,
                        sourceUrl = url,
                        pages = mutableStateListOf<String>().also { it.addAll(pages) },
                        lastIndex = o.optInt("lastIndex", 0),
                        addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                        author = o.optString("author", ""),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        favorite = o.optBoolean("favorite", false),
                        cover = cover
                    )
                )
                added++
            } else {
                var n = 0
                for (p in pages) if (!exist.pages.contains(p)) { exist.pages.add(p); n++ }
                if (n > 0) merged++
                if (exist.cover.isBlank() && cover.isNotBlank()) exist.cover = cover
            }
        }
        tick.value++
        return added to merged
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

    /**
     * 已缓存页数。**必须缓存结果**：书架每个格子都会在滚动时重组，
     * 原来每次都 `listFiles()` 扫一遍目录 → 主线程磁盘 IO，滑起来就卡。
     */
    private val cacheCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun cachedPageCount(comicId: String): Int =
        cacheCounts.getOrPut(comicId) { computeCachedPageCount(comicId) }

    private fun computeCachedPageCount(comicId: String): Int {
        val d = File(comicDir, comicId)
        if (!d.isDirectory) return 0
        // 与 pageModel 的判定保持一致：>1KB 才算一张有效页
        return d.listFiles()?.count { it.length() > 1024 } ?: 0
    }

    /** 缓存/清缓存后让计数失效（传 null 清全部） */
    fun invalidateCacheCount(comicId: String? = null) {
        if (comicId == null) cacheCounts.clear() else cacheCounts.remove(comicId)
    }

    /** 后台预热：把每册的已缓存页数先算好，滚动时就是纯内存读取 */
    fun warmCacheCounts() {
        for (c in comics.toList()) {
            runCatching { cacheCounts[c.id] = computeCachedPageCount(c.id) }
        }
    }

    fun clearCache(comicId: String) {
        File(comicDir, comicId).deleteRecursively()
        invalidateCacheCount(comicId)
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
