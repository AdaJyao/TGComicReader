package com.lcs.tgcomic

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * E-Hentai / ExHentai 支持。
 *
 * 站点结构（2026-11 实测 e-hentai.org）：
 *
 *  画廊页 https://e-hentai.org/g/<gid>/<token>/
 *    <h1 id="gn">标题</h1>
 *    <div id="gdn"><a href="/uploader/xxx">上传者</a></div>
 *    <div id="taglist"> … <div id="td_artist:xxx"><a>作者</a></div>
 *    <td class="gdt1">Length:</td><td class="gdt2">42 pages</td>
 *    <div id="gdt"><a href="https://e-hentai.org/s/<hash>/<gid>-<n>">
 *        <div title="Page n: 001.png" style="…url(<缩略图>) …"></div></a>
 *    分页：?p=1、?p=2 …（每页 20 张缩略图）
 *
 *  图片页 https://e-hentai.org/s/<hash>/<gid>-<n>
 *    <img id="img" src="https://xxx.hath.network/h/…/001.webp" …>
 *
 *  列表页 https://e-hentai.org/?f_search=xxx、/tag/xxx、/popular、首页
 *    <td class="gl3c glname"><a href="…/g/<gid>/<token>/"><div class="glink">标题</div>
 *
 * 注意：图片直链里的 keystamp 是有时效的，所以 **书架里存 /s/ 图片页地址**，
 * 真正读图/缓存时再换直链（EhHub 里带 6 分钟内存缓存）。
 *
 * ExHentai 必须带登录 Cookie（ignorant / ipb_member_id / ipb_pass_hash），否则返回空白（sad panda）。
 */
object EhApi {

    private val HOSTS = listOf("e-hentai.org", "exhentai.org", "e-hentai.moe", "e-hentai.eu")

    /** 请求间隔：EH 对翻页速度敏感，太快会 509 / 临时封 IP */
    private const val THROTTLE_MS = 1300L

    /** 最多翻多少页缩略图（每页 20 张 → 2000 张，够用且防跑飞） */
    private const val MAX_THUMB_PAGES = 100

    /** 一个列表页最多展开多少册（再往上就要手动分页导入，免得触发站点限速） */
    private const val MAX_LIST_ITEMS = 15

    private val GALLERY_RE = Regex("""^https?://([^/]+)/(?:g|mpv)/(\d+)/([0-9a-fA-F]+)""", RegexOption.IGNORE_CASE)
    private val IMAGE_PAGE_RE = Regex("""^https?://([^/]+)/s/[0-9a-fA-F]+/\d+-\d+""", RegexOption.IGNORE_CASE)

    private val GN = Regex("""<h1[^>]*id="gn"[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val UPLOADER = Regex("""<div[^>]*id="gdn"[^>]*>\s*<a[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ARTIST = Regex("""<div[^>]*id="td_artist:[^"]*"[^>]*>\s*<a[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val GROUP = Regex("""<div[^>]*id="td_group:[^"]*"[^>]*>\s*<a[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LENGTH = Regex("""Length:\s*</td>\s*<td[^>]*class="gdt2"[^>]*>\s*([\d,]+)\s+pages?""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SHOWING = Regex("""Showing\s+([\d,]+)\s*-\s*([\d,]+)\s+of\s+([\d,]+)""", RegexOption.IGNORE_CASE)
    private val THUMB = Regex(
        """<a[^>]+href="(https?://[^"]+/s/[0-9a-fA-F]+/\d+-\d+)"[^>]*>\s*<div[^>]*title="Page\s+(\d+)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val THUMB_BG = Regex("""url\((https?://[^)"]+\.(?:webp|jpe?g|png))\)""", RegexOption.IGNORE_CASE)
    /** 缩略图格子：精灵图里一格是 width×height，背景偏移定位 */
    private val THUMB_STYLE = Regex(
        """style="width:(\d+)px;height:(\d+)px;background:[^"]*?url\((https?://[^)"]+)\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val IMG_TAG = Regex("""<img\b[^>]*\bid=["']img["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val SRC_ATTR = Regex("""\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE = Regex("""<meta\b[^>]*property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ANY_HATH_IMG = Regex("""(https?://[^"'\s]*hath\.network[^"'\s]*)""", RegexOption.IGNORE_CASE)
    /** 列表页结果行 */
    private val GL3 = Regex(
        """<td[^>]*class="gl3c[^"]*"[^>]*>\s*<a[^>]+href="(https?://[^"]+/g/\d+/[0-9a-fA-F]+/)"[^>]*>\s*<div class="glink">(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val GALLERY_HREF = Regex("href=\"(https?://[^\"]+/g/\\d+/[0-9a-fA-F]+/)\"", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")

    // ---------------- 链接判定 ----------------

    fun isEh(url: String): Boolean {
        val h = runCatching { URI(url.trim()).host ?: "" }.getOrDefault("").lowercase()
        if (h.isEmpty()) return false
        return HOSTS.any { h == it || h.endsWith(".$it") }
    }

    fun isGalleryPage(url: String): Boolean = GALLERY_RE.containsMatchIn(url.trim())

    fun isImagePage(url: String): Boolean = IMAGE_PAGE_RE.containsMatchIn(url.trim())

    /** 列表/搜索/标签页：可展开成多册 */
    fun isListPage(url: String): Boolean =
        isEh(url) && !isGalleryPage(url) && !isImagePage(url)

    /** 还是图片页（需要换直链）才返回 true */
    fun needsResolve(url: String): Boolean = isImagePage(url)

    /** 去掉 ?p= 之类，得到画廊规范地址 */
    fun galleryKey(url: String): String {
        val m = GALLERY_RE.find(url.trim()) ?: return url.trim()
        return "https://${m.groupValues[1]}/g/${m.groupValues[2]}/${m.groupValues[3]}/"
    }

    // ---------------- 数据 ----------------

    data class Gallery(
        val url: String,
        val title: String,
        val author: String,
        val total: Int,
        /** 每页的 /s/ 图片页地址（顺序即页码） */
        val pageUrls: List<String>,
        /** 封面：EH 的缩略图是横排精灵图，这里给出整条地址 + 第一格尺寸，由调用方裁切 */
        val cover: String,
        val coverW: Int = 200,
        val coverH: Int = 258
    )

    data class Ref(val url: String, val title: String)

    // ---------------- 抓取 ----------------

    private var lastAt = 0L

    /** 站点临时封禁的到期时间（全局闸门，避免继续打请求把封禁延长） */
    @Volatile
    private var banUntil = 0L

    /** 被站点临时封禁（IP 级），带上剩余时间 */
    class Banned(val remainMs: Long) : IOException(
        "E-Hentai 临时封禁了这个 IP：请求太频繁，还要等 ${((remainMs / 60_000) + 1)} 分钟"
    )

    /** 稳妥模式：请求间隔翻倍（设置页可开） */
    @Volatile
    private var slowMode = false

    fun setSlowMode(on: Boolean) {
        slowMode = on
    }

    private suspend fun throttle() {
        val gap = if (slowMode) THROTTLE_MS * 2 else THROTTLE_MS
        val wait = gap - (System.currentTimeMillis() - lastAt)
        if (wait > 0) delay(wait)
        // 加一点抖动，别形成固定节奏
        delay((0..400).random().toLong())
        lastAt = System.currentTimeMillis()
    }

    /** 命中「temporarily banned」文本时返回剩余毫秒 */
    private fun detectBan(body: String): Long? {
        if (!body.contains("temporarily banned", ignoreCase = true)) return null
        val m = Regex(
            """expires in (?:(\d+)\s+minutes?\s+and\s+)?(\d+)\s+seconds""",
            RegexOption.IGNORE_CASE
        ).find(body)
        val secs = if (m != null) {
            (m.groupValues[1].toLongOrNull() ?: 0L) * 60 + (m.groupValues[2].toLongOrNull() ?: 0L)
        } else 900L
        return (secs.coerceAtLeast(60L)) * 1000L
    }

    /** 剩余封禁时间（0 = 没被禁） */
    fun banRemainMs(): Long = (banUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun baseOf(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.host}/" } }.getOrDefault("https://e-hentai.org/")

    private fun headers(url: String, cookie: String): Map<String, String> {
        val h = mutableMapOf(
            "Referer" to baseOf(url),
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
        )
        if (cookie.isNotBlank()) h["Cookie"] = cookie.trim()
        return h
    }

    private suspend fun getHtml(url: String, cookie: String): String {
        // 已经被封就别再打请求了，直接告诉用户还剩多久
        val banned = banRemainMs()
        if (banned > 0) throw Banned(banned)

        throttle()
        val html = Net.getText(url, headers(url, cookie), retries = 2)

        detectBan(html)?.let { ms ->
            banUntil = System.currentTimeMillis() + ms
            throw Banned(ms)
        }
        if (html.isBlank() || html.contains("sadpanda", true)) {
            throw IOException(
                if (isEx(url)) "ExHentai 需要登录 Cookie（ipb_member_id / ipb_pass_hash / ignorant），在 设置 → 来源 里填"
                else "站点返回空页面，稍后重试"
            )
        }
        return html
    }

    private fun isEx(url: String): Boolean = url.contains("exhentai", true)

    /** 带分页地抓完整个画廊，只取图片页地址 + 封面缩略图 */
    suspend fun loadGallery(
        url: String,
        cookie: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Gallery {
        val canon = galleryKey(url)
        val first = getHtml(canon, cookie)

        val title = GN.find(first)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            .ifBlank { "未命名" }
        val author = (ARTIST.find(first) ?: GROUP.find(first))?.groupValues?.get(1)?.let { clean(it) }
            ?: UPLOADER.find(first)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        val total = LENGTH.find(first)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            ?: SHOWING.find(first)?.groupValues?.get(3)?.replace(",", "")?.toIntOrNull()
            ?: 0

        val pages = LinkedHashMap<Int, String>()
        var cover = ""
        var coverW = 200
        var coverH = 258

        fun collect(html: String) {
            THUMB.findAll(html).forEach { m ->
                val idx = m.groupValues[2].toIntOrNull() ?: return@forEach
                pages.putIfAbsent(idx, m.groupValues[1])
            }
            if (cover.isBlank()) {
                // 只在缩略图区里找封面，避免取到页面头部的装饰图
                val gdt = html.substringAfter("id=\"gdt\"", "")
                val scope = if (gdt.isNotBlank()) gdt else html
                THUMB_STYLE.find(scope)?.let { m ->
                    // 组：1=width 2=height 3=url
                    coverW = m.groupValues[1].toIntOrNull() ?: coverW
                    coverH = m.groupValues[2].toIntOrNull() ?: coverH
                    cover = decode(m.groupValues[3])
                }
            }
        }

        collect(first)
        var p = 1
        while ((total <= 0 || pages.size < total) && p <= MAX_THUMB_PAGES) {
            onProgress(pages.size, if (total > 0) total else pages.size + 1)
            val html = try {
                getHtml("$canon?p=$p", cookie)
            } catch (e: Exception) {
                break
            }
            val before = pages.size
            collect(html)
            if (pages.size == before) break
            p++
        }

        val ordered = pages.toSortedMap().values.toList()
        if (ordered.isEmpty()) {
            throw IOException("这个画廊没解析到图片：可能需要登录，或页面结构变了")
        }
        return Gallery(canon, title, author, if (total > 0) total else ordered.size, ordered, cover, coverW, coverH)
    }

    /** 图片页 → 图片直链（keystamp 有时效，不要长期保存） */
    suspend fun resolvePage(pageUrl: String, cookie: String): String? {
        if (!isImagePage(pageUrl)) return null
        val html = getHtml(pageUrl, cookie)
        val tag = IMG_TAG.find(html)?.value
        val raw = tag?.let { SRC_ATTR.find(it)?.groupValues?.get(1) }
            ?: OG_IMAGE.find(html)?.groupValues?.get(1)
            ?: ANY_HATH_IMG.find(html)?.groupValues?.get(1)
            ?: return null
        return decode(raw).trim().takeIf { it.startsWith("http") }
    }

    /** 列表页 → 多册（搜索 / 标签 / 首页 / 收藏夹） */
    suspend fun expandList(url: String, cookie: String, limit: Int = MAX_LIST_ITEMS): List<Ref> {
        val html = getHtml(url.trim(), cookie)
        val out = LinkedHashMap<String, String>()
        GL3.findAll(html).forEach { m ->
            if (out.size < limit) out.putIfAbsent(m.groupValues[1], clean(m.groupValues[2]))
        }
        if (out.isEmpty()) {
            GALLERY_HREF.findAll(html).forEach { m ->
                if (out.size < limit) out.putIfAbsent(m.groupValues[1], "")
            }
        }
        return out.map { Ref(it.key, it.value) }
    }

    // ---------------- 文本 ----------------

    private fun decode(s: String): String {
        var t = s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        t = Regex("""&#(\d+);""").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
        }
        return t
    }

    private fun clean(s: String): String = decode(TAG.replace(s, " "))
        .replace(Regex("""\s+"""), " ")
        .trim()
}

/**
 * 图片直链的短期缓存。EH 直链带 keystamp（有时效），
 * 所以只做 6 分钟内存缓存，过期就重新去图片页换一条。
 */
object EhHub {
    private data class Entry(val direct: String, val at: Long)
    private const val TTL = 6 * 60_000L

    private val memo = ConcurrentHashMap<String, Entry>()

    fun peek(pageUrl: String): String? =
        memo[pageUrl]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.direct

    fun put(pageUrl: String, direct: String) {
        memo[pageUrl] = Entry(direct, System.currentTimeMillis())
    }

    fun forget(pageUrl: String) {
        memo.remove(pageUrl)
    }

    /** Cookie 换了之后清空缓存 */
    fun clearAll() {
        memo.clear()
    }

    /** 拿到直链（命中缓存就不发请求） */
    suspend fun direct(pageUrl: String, cookie: String): String? {
        peek(pageUrl)?.let { return it }
        val d = EhApi.resolvePage(pageUrl, cookie) ?: return null
        put(pageUrl, d)
        return d
    }
}
