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

    /** 上/中/下、前篇/后篇——必须最先剥，否则「第3话 上」的章节尾巴不在行尾会剥不掉 */
    private val partTail = Regex(
        """[\s\-_·:：]*(?:上|中|下|前篇|中篇|后篇|前編|中編|後編|后編|前编|后编|上篇|下篇)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 章节尾巴：「第3话 / 第3話 / 第三话 / 第12章 / Ch.3 / Chapter 3 / Vol.2 / (3) / 3話」
     * 命中就剥掉，剩下的当系列名——这样「XXX 第1话」「XXX 第2话」会归到同一册。
     */
    private val chapterTail = Regex(
        """[\s\-_–—·:：]*[\(\[（【]?\s*(?:第\s*[0-9一二三四五六七八九十百]+(?:\s*[话話章卷集部篇回])|ch(?:apter)?\.?\s*\d+|ep(?:isode)?\.?\s*\d+|vol(?:ume)?\.?\s*\d+|\d+\s*[话話章卷])\s*[\)\]）】]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** 纯数字尾巴：「夢幻家教 01」「XXX (3)」「XXX [2]」 */
    private val trailingNo = Regex(
        """[\s\-_–—·:：,，]*(?:[\(\[（【]?\s*\d{1,4}\s*[\)\]）】]?)\s*$"""
    )

    /**
     * 页码区间尾巴：「 | 1 - 150 」「(1-150)」「1-150页」。
     *
     * 有些来源把一部作品按页拆成几册发（1-150 / 151-300 / 301-427），
     * 标题只差这一截，所以：① 系列名要剥掉它，三册才归得到同一部；
     * ② 合并时用它当排序键，起始页小的排前面。
     */
    private val rangeTailBracket = Regex(
        """(?:[|｜]\s*|\s*[\(（\[【]\s*)(\d{1,5})\s*[-–~～〜]\s*(\d{1,5})\s*[\)\）\]】]?\s*$"""
    )
    private val rangeTailPages = Regex(
        """(\d{1,5})\s*[-–~～〜]\s*(\d{1,5})\s*[页頁]\s*$"""
    )

    /** 标题里的页码区间：1 - 150 → 1..150；认不出来返回 null */
    fun pageRange(raw: String): IntRange? {
        val t = normalize(raw)
        val m = rangeTailBracket.find(t) ?: rangeTailPages.find(t) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return if (a > 0 && b >= a) a..b else null
    }

    /** 剥掉页码区间尾巴（含残留的 | - 等分隔符） */
    private fun stripPageRange(raw: String): String {
        if (pageRange(raw) == null) return raw
        var t = rangeTailBracket.replace(raw, "").let { r -> if (r == raw) rangeTailPages.replace(raw, "") else r }
        return t.trim().trimEnd('|', '｜', '-', '–', '—', ':', '：', '·', ',').trim()
    }

    /** 全角 → 半角（数字/字母/空格），否则「１２」和「12」会被当成两册 */
    private fun halfWidth(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when {
                    ch in '０'..'９' -> '0' + (ch - '０')
                    ch in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
                    ch in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                    ch == '　' -> ' '
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** 去掉 " - Page 1" 之类的页码尾巴，用于把同一册的多页帖子合并 */
    fun normalize(raw: String): String {
        var t = halfWidth(raw.trim())
        t = noise.replace(t, "")
        t = pageSuffix.replace(t, "")
        t = zhPageSuffix.replace(t, "")
        return t.trim()
    }

    /** 系列名：先剥页码区间/「上中下」，再剥章节尾巴，最后剥纯数字尾巴 */
    fun series(raw: String): String {
        var t = stripPageRange(raw.trim())
        repeat(2) {
            val p = partTail.replace(t, "").trim()
            if (p.isNotBlank()) t = p
            val n = chapterTail.replace(t, "").trim()
            if (n.isNotBlank()) t = n
        }
        val noTail = trailingNo.replace(t, "").trim()
        if (noTail.isNotBlank()) t = noTail
        return t.trim().trimEnd('-', '_', '·', ':', '：').trim()
    }

    /**
     * 查重用的标题键：忽略大小写、空白、全半角、页码尾巴、首尾标点，
     * 并把「第01话 / Ch.05」里的前导零抹掉——**只看标题，不看来源链接**。
     */
    fun titleKey(raw: String): String {
        var t = normalize(raw).lowercase()
        t = t.replace(Regex("""[\s\u3000]+"""), "")
        t = t.replace(Regex("""[_*~`'"“”‘’]"""), "")
        t = t.replace(Regex("""[()\[\]（）【】《》]"""), "")
        t = Regex("""(第|ch|chapter|ep|episode|vol|volume|#)\.?\s*0*(\d+)""", RegexOption.IGNORE_CASE)
            .replace(t, "$1$2")
        return t.trimEnd('。', '.', '!', '！', '?', '？', '·', '：', ':', '-', '_', ',', '，', '、')
    }

    private val cnDigit = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** 中文数字：十二 → 12、二十三 → 23、一百 → 100 */
    private fun cnNumber(s: String): Int? {
        if (s.isBlank()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()
        var total = 0
        var cur = 0
        var seen = false
        for (ch in s) {
            when {
                cnDigit.containsKey(ch) -> { cur = cnDigit.getValue(ch); seen = true }
                ch == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0; seen = true }
                ch == '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0; seen = true }
                else -> return null
            }
        }
        return if (seen) total + cur else null
    }

    private val cnTail = Regex("""第\s*([0-9]+|[零〇一二三四五六七八九十百两]+)\s*[话話章卷集部篇回]""")
    private val latinTail = Regex(
        """(?:^|[\s\-_·:：\[\(【])(?:ch|chapter|ep|episode|vol|volume|#)\.?\s*(\d{1,4})""",
        RegexOption.IGNORE_CASE
    )
    private val plainTail = Regex(
        """[\s\-_–—·:：,，]*(?:[\(\[（【]?\s*(\d{1,4})\s*[\)\]）】]?)\s*$"""
    )

    /** 标题里带的序号：「第3话 / Ch.3 / Vol.2 / 03」→ 3、3、2、3；认不出来返回 null */
    fun chapterNo(raw: String): Int? {
        val t = normalize(raw)
        cnTail.find(t)?.let { m -> cnNumber(m.groupValues[1])?.takeIf { it > 0 }?.let { return it } }
        latinTail.find(t)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { return it } }
        plainTail.find(t)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { return it } }
        return null
    }

    /** 上/中/下 → 1/2/3（配合「第3话 上 / 第3话 下」这类拆分） */
    fun chapterPart(raw: String): Int {
        val t = normalize(raw)
        return when {
            Regex("""(?:^|[\s\-_·:：])(?:上|上篇|前篇|前編|前编|part\s*1)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(t) -> 1
            Regex("""(?:^|[\s\-_·:：])(?:中|中篇|中編|中编|part\s*2)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(t) -> 2
            Regex("""(?:^|[\s\-_·:：])(?:下|下篇|后篇|後篇|后編|後編|part\s*3)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(t) -> 3
            else -> 0
        }
    }

    /** 章节排序键：序号 ×10 + 上中下；没有序号的排在组首（当作整册/第 0 话） */
    fun chapterOrder(raw: String): Long {
        // 按页拆开的册子（「| 151 - 300」）用起始页当序号：1 - 150 排第一，301 - 427 排最后
        pageRange(raw)?.let { return it.first.toLong() * 10L + chapterPart(raw) }
        return (chapterNo(raw) ?: 0).toLong() * 10L + chapterPart(raw)
    }

    /**
     * 标题排序键（书架「标题」排序用）：
     * 去掉开头的「[作者]」「(社团)」「【标签】」和标点，数字段补零到 4 位，
     * 这样「第2话」会排在「第10话」前面，不会出现 10 在 2 前面。
     */
    fun titleSortKey(raw: String): String {
        var t = normalize(raw).lowercase()
        t = t.replace(Regex("""^[\s\(\)\[\]【】（）《》<>〈〉\-–—_.,，。:：;；!！?？'"“”‘’*~#｜|]+"""), "")
        if (t.isBlank()) t = normalize(raw).lowercase()
        return Regex("""\d+""").replace(t) { m -> m.value.padStart(4, '0') }
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

    /**
     * 系列键：**只由标题决定**（去掉「第N话 / Ch.N / 上中下」后的系列名），
     * 来源链接完全不参与——所以同一部作品从不同站点导入也能按序号并到一起。
     */
    fun seriesKeyOf(title: String, url: String = ""): String =
        Titles.titleKey(Titles.series(Titles.normalize(title)))

    /** 同标题（同一册的分页帖子 / 同一部作品重复收录） */
    fun findSameVolume(title: String): Comic? {
        val k = Titles.titleKey(title)
        if (k.isBlank()) return null
        return comics.firstOrNull { Titles.titleKey(it.title) == k }
    }

    /** 同一系列的已有册子 */
    fun findSameSeries(title: String, url: String = ""): Comic? {
        val key = seriesKeyOf(title, url)
        if (key.isBlank()) return null
        return comics.firstOrNull { it.seriesKey.isNotEmpty() && it.seriesKey == key }
            ?: comics.firstOrNull { seriesKeyOf(it.title) == key }
    }

    /**
     * 查重：**只看标题**。
     *
     * 之前拿页面重合度/来源 id 判定，telegra.ph 的文章页共用模板和首图，
     * 结果把不是同一部作品的两册判成重复，反过来同一个标题换个链接又重复收录。
     * 现在标题归一化（忽略大小写/空白/全半角/页码尾巴/前导零）后完全相同才算重复。
     */
    fun findDuplicate(title: String, url: String, pages: List<String>, cover: String): Comic? {
        val k = Titles.titleKey(title)
        if (k.isBlank()) return null
        return comics.firstOrNull { Titles.titleKey(it.title) == k }
    }

    /**
     * 导入前是否要弹「发现重复」让用户选：
     * 标题相同、且两边的页面不是同一份（已有那册没包含这次的页、重合度也不高）才问。
     */
    fun findConflict(title: String, url: String, pages: List<String>, cover: String): Comic? {
        val exist = findDuplicate(title, url, pages, cover) ?: return null
        if (pages.isEmpty()) return null
        val mine = exist.pages.map { it.substringBefore('?') }.toSet()
        val theirs = pages.map { it.substringBefore('?') }
        if (theirs.all { it in mine }) return null
        val inter = theirs.count { it in mine }
        if (inter.toDouble() / theirs.size >= 0.9) return null
        return exist
    }

    /** 用户选择「合并进原来那册」 */
    fun mergeInto(existingId: String, title: String, pages: List<String>): Int {
        val c = comics.firstOrNull { it.id == existingId } ?: return 0
        val added = mergePages(c, pages, sameChapter = false, chapterName = title)
        if (added > 0) reorderChaptersOf(c)
        save()
        return added
    }

    /**
     * 把已合并的多话册内部按标题序号重排。
     *
     * 老版本按钮是「按加入时间」排的，导入顺序乱就会得到「第2话 在 第1话 前面」；
     * 这里用已有的 chapterMarks 切段、按序号稳定排序后重新拼回去。
     * @return 是否真的动过
     */
    private fun reorderChaptersOf(c: Comic): Boolean {
        val n = c.pages.size
        if (n == 0 || c.chapterMarks.size < 2) return false
        val bounds = c.chapterMarks.filter { it in 0 until n }.distinct().sorted().toMutableList()
        if (bounds.isEmpty()) return false
        if (bounds.first() != 0) bounds.add(0, 0)
        val segments = ArrayList<Pair<String, List<String>>>()
        for (i in bounds.indices) {
            val from = bounds[i]
            val to = if (i + 1 < bounds.size) bounds[i + 1] else n
            if (to <= from) continue
            val name = c.chapterNames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "第 ${i + 1} 话"
            segments += name to c.pages.subList(from, to).toList()
        }
        if (segments.size < 2) return false
        // sortedWith 是稳定排序：序号一样的保持原来的先后，不会乱动
        val ordered = segments.sortedBy { Titles.chapterOrder(it.first) }
        if (ordered.map { it.first } == segments.map { it.first }) return false
        c.pages.clear()
        c.chapterMarks.clear()
        c.chapterMarks.add(0)
        c.chapterNames.clear()
        ordered.forEachIndexed { i, (name, pages) ->
            if (i > 0) c.chapterMarks.add(c.pages.size)
            c.chapterNames.add(name)
            c.pages.addAll(pages)
        }
        return true
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

    /**
     * 导入一条解析结果。
     *
     * 规则（对应用户要求）：
     *   · 标题相同 → 当作同一册，只把缺的页补进去，**不新建**
     *   · 其它情况一律新建一册，**绝不自动归并系列**——合并只在用户手动「整理」时做
     * 这样「第一次从链接批量恢复」得到的就是忠实的一册一条，之后再整理。
     */
    fun upsert(
        title: String,
        url: String,
        pages: List<String>,
        author: String,
        cover: String = ""
    ): Triple<Comic, Int, Boolean> {
        val clean = Titles.normalize(title).ifBlank { "未命名" }
        findSameVolume(clean)?.let { exist ->
            val added = mergePages(exist, pages, sameChapter = true)
            if (exist.author.isBlank() && author.isNotBlank()) exist.author = author
            if (exist.cover.isBlank() && cover.isNotBlank()) exist.cover = cover
            exist.updatedAt = System.currentTimeMillis()
            tick.value++
            return Triple(exist, added, false)
        }
        val c = Comic(
            id = comicIdOf(url),
            title = clean,
            sourceUrl = url,
            pages = mutableStateListOf<String>().also { it.addAll(pages) },
            author = author,
            cover = cover,
            seriesKey = seriesKeyOf(clean),
            chapterMarks = mutableStateListOf(0),
            chapterNames = mutableStateListOf(clean)
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
        val chapters: List<Pair<String, Int>>   // 章节名 → 页数（已按标题序号排好）
    )

    /** 整理前的预览：哪些册会被并成一组 / 哪些算重复 */
    data class OrganizePlan(
        val seriesGroups: List<MergePlan>,
        val duplicatePairs: List<Pair<Comic, Comic>>,
        val before: Int,
        val after: Int
    )

    /** 组内排序：标题里的序号升序（第2话 在 第10话 前面），序号相同再按加入时间 */
    private fun chapterSorted(list: List<Comic>): List<Comic> =
        list.sortedWith(compareBy({ Titles.chapterOrder(it.title) }, { it.addedAt }))

    /** 合并后的书名：取组内最靠前那册的系列名（去掉「第N话」），剥不出来就用它自己的标题 */
    private fun mergedTitleOf(sorted: List<Comic>): String {
        val first = sorted.firstOrNull() ?: return ""
        val series = Titles.series(Titles.normalize(first.title))
        return series.ifBlank { first.title }
    }

    private fun mergePlanOf(list: List<Comic>): MergePlan? {
        if (list.size < 2) return null
        val sorted = chapterSorted(list)
        return MergePlan(
            ids = sorted.map { it.id },
            title = mergedTitleOf(sorted),
            totalPages = sorted.sumOf { it.pages.size },
            chapters = sorted.map { c -> c.title to c.pages.size }
        )
    }

    /** 合并预览：按标题里的序号排，序号最小的那册当主册 */
    fun previewMerge(ids: Collection<String>): MergePlan? =
        mergePlanOf(ids.mapNotNull { id -> comics.firstOrNull { it.id == id } })

    /** 手动合并：按序号升序拼接，各算一章 */
    fun mergeComics(ids: Collection<String>, title: String? = null): Comic? {
        val plan = previewMerge(ids) ?: return null
        snapshot()
        val keep = comics.firstOrNull { it.id == plan.ids.first() } ?: return null
        keep.title = title?.takeIf { it.isNotBlank() }?.trim() ?: plan.title
        plan.ids.drop(1).forEach { id ->
            val other = comics.firstOrNull { it.id == id } ?: return@forEach
            mergePages(keep, other.pages, sameChapter = false, chapterName = other.title)
            if (keep.cover.isBlank()) keep.cover = other.cover
            if (keep.author.isBlank()) keep.author = other.author
            if (other.favorite) keep.favorite = true
            if (other.lastIndex > keep.lastIndex) keep.lastIndex = other.lastIndex
            dropCache(other.id)
            comics.removeAll { it.id == other.id }
        }
        // 合并后系列名按新标题重算
        keep.seriesKey = seriesKeyOf(keep.title)
        reorderChaptersOf(keep)
        tick.value++
        save()
        return keep
    }

    /**
     * 只算不改：整理会做哪些事。
     *
     * ① 清重：**标题完全相同**的归一组，只留页数最多的一册（页数相同留早的）
     * ② 并系列：同一系列名的多话，按标题里的序号升序拼成一册
     * 两步都是模拟计算，保证预览和真正的整理结果一致。
     */
    fun planOrganize(mergeSameSeries: Boolean, removeDuplicates: Boolean): OrganizePlan {
        val before = comics.size

        // ① 清重（模拟）
        val dupPairs = ArrayList<Pair<Comic, Comic>>()
        val dropped = HashSet<String>()
        if (removeDuplicates) {
            comics.groupBy { Titles.titleKey(it.title) }
                .filterKeys { it.isNotBlank() }
                .forEach { (_, list) ->
                    if (list.size < 2) return@forEach
                    val keep = list.sortedWith(
                        compareByDescending<Comic> { it.pages.size }.thenBy { it.addedAt }
                    ).first()
                    list.filter { it.id != keep.id }.forEach { drop ->
                        if (dropped.add(drop.id)) dupPairs += keep to drop
                    }
                }
        }

        // ② 并系列（在清重后的列表上模拟）
        val alive = comics.filter { it.id !in dropped }
        val groups = ArrayList<MergePlan>()
        if (mergeSameSeries) {
            alive.groupBy { seriesKeyOf(it.title) }
                .filterKeys { it.isNotBlank() }
                .forEach { (_, list) ->
                    // 组里全是同名（只差大小写/空白）的那是重复，交给清重，不算系列
                    val distinctTitles = list.map { Titles.titleKey(it.title) }.toSet()
                    if (list.size >= 2 && distinctTitles.size >= 2) {
                        mergePlanOf(list)?.let { groups += it }
                    }
                }
        }

        val after = alive.size - groups.sumOf { (it.ids.size - 1).coerceAtLeast(0) }
        return OrganizePlan(groups, dupPairs, before, after.coerceAtLeast(0))
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
     * 整理书架（**只在用户手动触发时执行**，启动/导入都不会自动跑）：
     *   ① removeDuplicates：标题相同的只留页数最多的一册，页集合合并进来
     *   ② mergeSameSeries：同系列的多话按标题序号升序拼成一册，第 1 话当主册
     */
    fun organize(removeDuplicates: Boolean = true, mergeSameSeries: Boolean = true): OrganizeReport {
        // 空操作就别留快照，免得「撤销」把无关的更早状态翻出来
        val plan = planOrganize(mergeSameSeries, removeDuplicates)
        if (plan.duplicatePairs.isEmpty() && plan.seriesGroups.isEmpty()) {
            return OrganizeReport(comics.size, comics.size, 0, 0)
        }
        snapshot()
        val before = comics.size
        // 规则可能变过，先按当前规则重算一遍系列键
        comics.forEach { c -> c.seriesKey = seriesKeyOf(c.title) }

        // ① 清重：保留页数多的那册，另一册的页并进来（可能补上它缺的图）
        var dups = 0
        plan.duplicatePairs.forEach { (keep, drop) ->
            if (drop.pages.isNotEmpty()) {
                runCatching { mergePages(keep, drop.pages, sameChapter = true) }
            }
            if (keep.cover.isBlank()) keep.cover = drop.cover
            if (keep.author.isBlank()) keep.author = drop.author
            if (drop.favorite) keep.favorite = true
            if (drop.lastIndex > keep.lastIndex) keep.lastIndex = drop.lastIndex
            dropCache(drop.id)
            comics.removeAll { it.id == drop.id }
            dups++
        }

        // ② 并系列：按序号升序，序号最小的那册当主册，书名剥成系列名
        var merged = 0
        plan.seriesGroups.forEach { g ->
            val list = g.ids.mapNotNull { id -> comics.firstOrNull { it.id == id } }
            val keep = list.firstOrNull() ?: return@forEach
            list.drop(1).forEach { other ->
                // 页完全相同就没什么可并的，跳过（避免留下空章节）
                if (other.pages.isNotEmpty()) {
                    mergePages(keep, other.pages, sameChapter = false, chapterName = other.title)
                }
                if (keep.cover.isBlank()) keep.cover = other.cover
                if (keep.author.isBlank()) keep.author = other.author
                if (other.favorite) keep.favorite = true
                if (other.lastIndex > keep.lastIndex) keep.lastIndex = other.lastIndex
                dropCache(other.id)
                comics.removeAll { it.id == other.id }
                merged++
            }
            keep.title = Titles.series(Titles.normalize(keep.title)).ifBlank { keep.title }
            keep.seriesKey = seriesKeyOf(keep.title)
            reorderChaptersOf(keep)
        }

        // 已合并过的老数据也可能是乱序的，顺手按序号捋一遍
        var reordered = 0
        if (mergeSameSeries) {
            comics.forEach { if (runCatching { reorderChaptersOf(it) }.getOrDefault(false)) reordered++ }
        }

        tick.value++
        if (merged > 0 || dups > 0 || reordered > 0) save()
        return OrganizeReport(before, comics.size, merged, dups)
    }

    /** UI 用：两个开关各管一件事 */
    fun organizeWith(mergeSameSeries: Boolean, removeDuplicates: Boolean): OrganizeReport =
        organize(removeDuplicates = removeDuplicates, mergeSameSeries = mergeSameSeries)

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
            // 按标题判重（链接不参与）：同名就当作同一册，缺的页补上
            val tk = Titles.titleKey(title)
            val exist = if (tk.isBlank()) null else comics.firstOrNull { Titles.titleKey(it.title) == tk }
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
            SortMode.RECENT -> comics.sortedWith(
                compareByDescending<Comic> { it.updatedAt }.thenBy { Titles.titleSortKey(it.title) }
            )
            SortMode.ADDED -> comics.sortedWith(
                compareByDescending<Comic> { it.addedAt }.thenBy { Titles.titleSortKey(it.title) }
            )
            // 标题：按「去括号前缀 + 数字补零」的键排，不然 10 会跑到 2 前面
            SortMode.TITLE -> comics.sortedBy { Titles.titleSortKey(it.title) }
            SortMode.PAGES -> comics.sortedWith(
                compareByDescending<Comic> { it.pages.size }.thenBy { Titles.titleSortKey(it.title) }
            )
        }
        // sortedByDescending 是稳定排序：收藏的提到最前，组内保持上面排好的顺序
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
