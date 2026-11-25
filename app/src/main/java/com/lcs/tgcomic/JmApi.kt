package com.lcs.tgcomic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 禁漫天堂（18comic）。
 *
 * 域名说明：`18comic.vip` 挂在 Cloudflare 后面，普通 HTTP 请求只会拿到
 * 「Just a moment...」403；`18comic.ink` 是同一站点的镜像，直连可拿 HTML，
 * 所以这里统一走 .ink。
 *
 * 页面结构（都是服务端渲染，正则够用）：
 *   · 列表：`/albums?o=mr&page=N` —— 卡片是 `<a href="/album/<aid>/<标题>">` +
 *     `<img data-src=".../media/albums/<aid>_3x4.jpg?u=...">`
 *   · 相册：`/album/<aid>` —— `<h1 id="book-name">标题</h1>`、`頁數：N`、
 *     章节列表是 `/photo/<cid>`（单章节相册只有一个）
 *   · 章节：`/photo/<cid>` —— `var page_arr = ["00001.webp", ...]`，
 *     图片直链 `<cdn>/media/photos/<cid>/<name>`（不需要 Referer）
 */
object JmApi {

    const val HOME = "https://18comic.ink"
    private const val CDN = "https://cdn-msp3.18comic.ink"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val ALBUM = Regex("""^https?://[\w.-]*/album/(\d+)""", RegexOption.IGNORE_CASE)
    private val PHOTO = Regex("""^https?://[\w.-]*/photo/(\d+)""", RegexOption.IGNORE_CASE)
    private val ALBUM_LINK = Regex("""<a href="/album/(\d+)[^"]*"""")
    private val ALBUM_ID_IN_PAGE = Regex("""/album/(\d+)""")
    private val PHOTO_ID_IN_PAGE = Regex("""/photo/(\d+)""")
    private val PAGE_ARR = Regex("""var page_arr = \[([^\]]*)\]""")
    private val QUOTED = Regex("""["']([^"']+)["']""")
    private val PHOTOS_DIR = Regex("""photos/(\d+)/""")
    private val CHAPTER_TITLE = Regex("""<title>\s*(\d+)\s*-\s*([^|<]+)""")
    private val IMG_LAZY = Regex("""(?:data-src|data-original)="([^"]+)"""")
    private val IMG_TITLE = Regex("""title="([^"]*)"""")
    private val H1 = Regex("""<h1[^>]*id="book-name"[^>]*>([^<]+)""")
    private val PAGE_COUNT = Regex("""頁數[：:]\s*(\d+)""")
    private val AUTHOR = Regex("""作者[：:]\s*(?:</span>)?\s*(?:<a[^>]*>)?([^<]{1,40})""")
    private val TAG_LINK = Regex("""main_tag=2">([^<]{1,24})</a>""")

    /** 同一站点连续请求要慢一点，避免被限速 */
    private var lastAt = 0L

    data class Ref(val url: String, val title: String, val cover: String)

    data class Peek(
        val title: String,
        val pages: Int,
        val author: String,
        val category: String,
        val cover: String
    )

    data class Album(
        val url: String,
        val title: String,
        val author: String,
        val pageUrls: List<String>,
        val cover: String
    )

    fun isAlbum(url: String): Boolean = ALBUM.find(url.trim()) != null

    fun isChapter(url: String): Boolean = PHOTO.find(url.trim()) != null

    fun albumId(url: String): String? = ALBUM.find(url.trim())?.groupValues?.get(1)

    fun chapterId(url: String): String? = PHOTO.find(url.trim())?.groupValues?.get(1)

    fun canonicalAlbum(url: String): String = albumId(url)?.let { "$HOME/album/$it" } ?: url.trim()

    /** 列表页 → 卡片（去重、按出现顺序） */
    suspend fun expandList(url: String, limit: Int = 24): List<Ref> = withContext(Dispatchers.IO) {
        val html = get(url)
        val out = LinkedHashMap<String, Ref>()
        for (m in ALBUM_LINK.findAll(html)) {
            val aid = m.groupValues[1]
            if (out.containsKey(aid)) continue
            if (out.size >= limit) break
            val end = (m.range.first + 1200).coerceAtMost(html.length)
            val block = html.substring(m.range.first, end)
            val cover = IMG_LAZY.find(block)?.groupValues?.get(1)?.let { abs(it) }.orEmpty()
            val title = IMG_TITLE.find(block)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()
                .ifBlank { "18comic-$aid" }
            out[aid] = Ref("$HOME/album/$aid", title, cover)
        }
        out.values.toList()
    }

    /** 详情：只拉相册页一次，不展开章节 */
    suspend fun peek(url: String): Peek = withContext(Dispatchers.IO) {
        val aid = albumId(url) ?: throw IOException("认不出这个禁漫天堂链接")
        val html = get("$HOME/album/$aid")
        val title = H1.find(html)?.groupValues?.get(1)?.let { unescape(it) }?.trim().orEmpty()
            .ifBlank { "18comic-$aid" }
        val total = PAGE_COUNT.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val author = AUTHOR.find(html)?.groupValues?.get(1)?.let { unescape(it) }?.trim().orEmpty()
            .ifBlank { TAG_LINK.find(html)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty() }
        val chapters = PHOTO_ID_IN_PAGE.findAll(html).map { it.groupValues[1] }.distinct().count()
        val cover = "$CDN/media/albums/${aid}_3x4.jpg"
        Peek(title, total, author, if (chapters > 1) "$chapters 话" else "", cover)
    }

    /** 导入：相册 → 逐章取 page_arr，拼成完整图片地址列表 */
    suspend fun loadAlbum(url: String, onProgress: (done: Int, total: Int) -> Unit): Album =
        withContext(Dispatchers.IO) {
            val aid = albumId(url) ?: throw IOException("认不出这个禁漫天堂链接")
            val html = get("$HOME/album/$aid")
            val title = H1.find(html)?.groupValues?.get(1)?.let { unescape(it) }?.trim().orEmpty()
                .ifBlank { "18comic-$aid" }
            val author = AUTHOR.find(html)?.groupValues?.get(1)?.let { unescape(it) }?.trim().orEmpty()
            // 相册页的章节列表通常是「最新在前」，按 cid 升序还原阅读顺序，
            // 否则合并成多话之后页序会整个反掉
            val chapters = PHOTO_ID_IN_PAGE.findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .sortedBy { it.toLongOrNull() ?: 0L }
                .toList()
            val pages = ArrayList<String>()
            val list = chapters.ifEmpty { listOf(aid) }
            list.forEachIndexed { i, cid ->
                onProgress(i, list.size)
                runCatching { pages += chapterPages(cid) }
                if (i < list.size - 1) delay(350)
            }
            if (pages.isEmpty()) {
                throw IOException("禁漫天堂没给出图片列表（这一话可能已下架，或站点改版了）")
            }
            Album("$HOME/album/$aid", title, author, pages, "$CDN/media/albums/${aid}_3x4.jpg")
        }

    /** 单话导入：/photo/<cid>；标题取「<序号>-<系列名>」，多话就会自动并进同一系列册 */
    suspend fun loadChapter(url: String, onProgress: (Int, Int) -> Unit): Album =
        withContext(Dispatchers.IO) {
            val cid = chapterId(url) ?: throw IOException("认不出这个禁漫天堂章节链接")
            onProgress(0, 1)
            val html = get("$HOME/photo/$cid")
            val pages = pagesOf(html, cid)
            if (pages.isEmpty()) throw IOException("这一话没有解析到图片（可能已下架）")
            onProgress(1, 1)
            val head = CHAPTER_TITLE.find(html)
            val no = head?.groupValues?.get(1)?.trim().orEmpty()
            val name = head?.groupValues?.get(2)?.trim().orEmpty()
            val title = if (name.isNotBlank() && no.isNotBlank()) "$name 第${no}话"
            else if (name.isNotBlank()) name
            else "禁漫天堂 $cid"
            Album("$HOME/photo/$cid", title, "", pages, pages.first())
        }

    private suspend fun chapterPages(cid: String): List<String> = pagesOf(get("$HOME/photo/$cid"), cid)

    private fun pagesOf(html: String, cid: String): List<String> {
        val arr = PAGE_ARR.find(html)?.groupValues?.get(1) ?: return emptyList()
        val dir = PHOTOS_DIR.find(html)?.groupValues?.get(1) ?: cid
        return QUOTED.findAll(arr)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".webp") || it.endsWith(".jpg") || it.endsWith(".png") }
            .map { "$CDN/media/photos/$dir/$it" }
            .toList()
    }

    private suspend fun get(url: String): String {
        throttle()
        return Net.getText(
            url,
            mapOf(
                "User-Agent" to UA,
                "Referer" to "$HOME/",
                "Accept-Language" to "zh-CN,zh;q=0.9"
            )
        )
    }

    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = 350L - (now - lastAt)
        if (wait in 1..350) runCatching { Thread.sleep(wait) }
        lastAt = System.currentTimeMillis()
    }

    private fun abs(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> HOME + u
        else -> u
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#039;", "'")
}
