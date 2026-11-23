package com.lcs.tgcomic

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 紳士漫畫（wnacg.com）支持。
 *
 * 站点结构（2026-11 实测，**用桌面 UA** 才给经典版式）：
 *
 *  相册页 https://www.wnacg.com/photos-index-aid-324632.html
 *    <h2>[作者] 标题 [汉化组]</h2>
 *    頁數：246P
 *    <li class="li tb gallary_item"><a href="/photos-view-id-27258533.html">
 *        <img src="//t4.qy0.ru/data/t/3246/32/17595652317183.jpg" alt="标题_001" />
 *    分页：/photos-index-page-2-aid-324632.html … （每页 18~36 个 view 链接）
 *
 *  看图页 https://www.wnacg.com/photos-view-id-27258533.html
 *    <title>001 - 标题 - 紳士漫畫…</title>
 *    <img src="//img5.qy0.ru/data/3246/32/001.jpg?verify=1795392000-xxxx" />
 *
 *  列表页 https://www.wnacg.com/albums.html、/albums-index-tag-xxx.html
 *    <a href="/photos-index-aid-406793.html"><img alt="标题" src="//t4.qy0.ru/data/t/…"></a>
 *
 * 和 EH 一样：图片直链带 verify 令牌（有时效），所以书架里存**看图页地址**，
 * 真正读图/缓存时才换直链。缩略图（/data/t/）是静态地址，可以当封面。
 */
object WnacgApi {

    /** 桌面 UA：移动 UA 会拿到另一套版式（没有 view 链接、没有分页） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    private const val HOST = "wnacg.com"
    private const val THROTTLE_MS = 500L
    private const val MAX_INDEX_PAGES = 60
    private const val MAX_LIST_ITEMS = 20

    private val ALBUM_RE = Regex("""^https?://([^/]+)/photos-index-(?:page-\d+-)?aid-(\d+)\.html""", RegexOption.IGNORE_CASE)
    private val SLIDE_RE = Regex("""^https?://([^/]+)/photos-slide-aid-(\d+)\.html""", RegexOption.IGNORE_CASE)
    private val VIEW_RE = Regex("""^https?://([^/]+)/photos-view-id-(\d+)\.html""", RegexOption.IGNORE_CASE)

    private val H2 = Regex("""<h2[^>]*>(.*?)</h2>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PAGE_COUNT = Regex("""頁數[：:]\s*(\d+)\s*[Pp]""")
    private val VIEW_LINK = Regex("""/photos-view-id-(\d+)\.html""")
    private val PAGER = Regex("""/photos-index-page-(\d+)-aid-\d+\.html""")
    private val THUMB = Regex("""<img[^>]+src="(//t\d+\.qy0\.ru/data/t/[^"]+)"[^>]*alt="[^"]*_(\d+)""", RegexOption.IGNORE_CASE)
    private val FULL_IMG = Regex("<img[^>]+src=\"(//img\\d+\\.qy0\\.ru/data/[^\"]+)\"", RegexOption.IGNORE_CASE)
    private val ALBUM_ITEM = Regex(
        """<a[^>]+href="/photos-index-aid-(\d+)\.html"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val ALT = Regex("alt=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")

    // ---------------- 链接判定 ----------------

    fun isWnacg(url: String): Boolean {
        val h = runCatching { URI(url.trim()).host ?: "" }.getOrDefault("").lowercase()
        return h == HOST || h.endsWith(".$HOST") || h.endsWith(".wnacg.net") || h == "wnacg.net"
    }

    fun isAlbum(url: String): Boolean = ALBUM_RE.containsMatchIn(url.trim()) || SLIDE_RE.containsMatchIn(url.trim())

    fun isViewPage(url: String): Boolean = VIEW_RE.containsMatchIn(url.trim())

    /** 列表/标签/分类/首页：可批量展开成多册 */
    fun isListPage(url: String): Boolean =
        isWnacg(url) && !isAlbum(url) && !isViewPage(url)

    /** 书架里存的是看图页，读图前要换直链 */
    fun needsResolve(url: String): Boolean = isViewPage(url)

    fun albumId(url: String): String? =
        (ALBUM_RE.find(url.trim()) ?: SLIDE_RE.find(url.trim()))?.groupValues?.get(2)

    fun canonicalAlbum(url: String): String {
        val id = albumId(url) ?: return url.trim()
        return "https://www.$HOST/photos-index-aid-$id.html"
    }

    // ---------------- 数据 ----------------

    data class Album(
        val url: String,
        val title: String,
        val author: String,
        val total: Int,
        /** 每页的看图页地址 */
        val pageUrls: List<String>,
        val cover: String
    )

    data class Ref(val url: String, val title: String)

    // ---------------- 抓取 ----------------

    private var lastAt = 0L

    private suspend fun throttle(ms: Long = THROTTLE_MS) {
        val wait = ms - (System.currentTimeMillis() - lastAt)
        if (wait > 0) delay(wait)
        lastAt = System.currentTimeMillis()
    }

    private fun headers(url: String): Map<String, String> {
        val base = runCatching { URI(url).let { "${it.scheme}://${it.host}/" } }.getOrDefault("https://www.$HOST/")
        return mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to base,
            "Accept-Language" to "zh-CN,zh;q=0.9,ja;q=0.8"
        )
    }

    private suspend fun getHtml(url: String, throttleMs: Long = THROTTLE_MS): String {
        throttle(throttleMs)
        val html = Net.getText(url, headers(url), retries = 2)
        if (html.isBlank()) throw IOException("wnacg 返回空页面，稍后重试")
        return html
    }

    /** 抓完整相册：翻页收齐所有看图页地址 */
    suspend fun loadAlbum(
        url: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Album {
        val aid = albumId(url) ?: throw IOException("认不出这个 wnacg 相册链接")
        val first = getHtml("https://www.$HOST/photos-index-aid-$aid.html")

        val title = H2.find(first)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            .ifBlank { "wnacg-$aid" }
        val total = PAGE_COUNT.find(first)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var cover = ""

        val ids = LinkedHashSet<Long>()
        fun collect(html: String) {
            VIEW_LINK.findAll(html).forEach { m ->
                m.groupValues[1].toLongOrNull()?.let { ids.add(it) }
            }
            if (cover.isBlank()) {
                cover = THUMB.find(html)?.groupValues?.get(1)?.let { https(it) }.orEmpty()
            }
        }

        collect(first)
        // 注意从第 2 页开始：photos-index-page-1-aid-N.html 和相册首页内容一样，
        // 从 1 开始会「没有新图」而提前 break（246 页的相册只会抓到 12 页）
        var p = 2
        while ((total <= 0 || ids.size < total) && p <= MAX_INDEX_PAGES) {
            onProgress(ids.size, if (total > 0) total else ids.size + 1)
            val html = try {
                getHtml("https://www.$HOST/photos-index-page-$p-aid-$aid.html")
            } catch (e: Exception) {
                break
            }
            val before = ids.size
            collect(html)
            if (ids.size == before) break
            p++
        }

        if (ids.isEmpty()) throw IOException("这个相册没解析到图片页：可能被删除或需要登录")
        val pages = ids.map { "https://www.$HOST/photos-view-id-$it.html" }
        // 作者：标题里的第一个方括号
        val author = Regex("""^[\[［]([^\]］]+)[\]］]""").find(title)?.groupValues?.get(1).orEmpty()
        val canonical = "https://www.$HOST/photos-index-aid-$aid.html"
        return Album(canonical, title, author, if (total > 0) total else pages.size, pages, cover)
    }

    /** 看图页 → 图片直链（verify 令牌有时效，别长期保存） */
    suspend fun resolvePage(viewUrl: String): String? {
        if (!isViewPage(viewUrl)) return null
        // 看图页只是普通 HTML，间隔放到 250ms：翻页/预解析才跟得上手
        val html = getHtml(viewUrl, throttleMs = 250L)
        val raw = FULL_IMG.find(html)?.groupValues?.get(1) ?: return null
        return https(raw)
    }

    /** 列表页 → 多册 */
    suspend fun expandList(url: String, limit: Int = MAX_LIST_ITEMS): List<Ref> {
        val html = getHtml(url.trim())
        val out = LinkedHashMap<String, String>()
        ALBUM_ITEM.findAll(html).forEach { m ->
            if (out.size >= limit) return@forEach
            val id = m.groupValues[1]
            val body = m.groupValues[2]
            val title = ALT.find(body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: clean(TAG.replace(body, " ")).takeIf { it.isNotBlank() }
                ?: ""
            out.putIfAbsent("https://www.$HOST/photos-index-aid-$id.html", title)
        }
        return out.map { Ref(it.key, it.value) }
    }

    // ---------------- 文本 ----------------

    private fun https(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        else -> u
    }

    private fun clean(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

/** 看图页直链的短期缓存（verify 令牌会过期） */
object WnacgHub {
    private data class Entry(val direct: String, val at: Long)
    private const val TTL = 5 * 60_000L

    private val memo = ConcurrentHashMap<String, Entry>()

    fun peek(viewUrl: String): String? =
        memo[viewUrl]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.direct

    fun put(viewUrl: String, direct: String) {
        memo[viewUrl] = Entry(direct, System.currentTimeMillis())
    }

    fun clearAll() = memo.clear()

    suspend fun direct(viewUrl: String): String? {
        peek(viewUrl)?.let { return it }
        val d = WnacgApi.resolvePage(viewUrl) ?: return null
        put(viewUrl, d)
        return d
    }
}

/** 按来源分发「页面地址 → 图片直链」 */
object PageResolver {
    suspend fun direct(url: String, ehCookie: String): String? = when {
        EhApi.needsResolve(url) -> EhHub.direct(url, ehCookie)
        WnacgApi.needsResolve(url) -> WnacgHub.direct(url)
        else -> null
    }

    fun needsResolve(url: String): Boolean = EhApi.needsResolve(url) || WnacgApi.needsResolve(url)
}
