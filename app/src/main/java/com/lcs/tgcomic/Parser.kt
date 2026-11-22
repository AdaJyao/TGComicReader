package com.lcs.tgcomic

/**
 * telegra.ph 页面解析。
 *
 * 实测结构（2026-11 抽样）：
 *   <article id="_tl_editor" class="tl_article_content">
 *       <h1>标题</h1><address>作者</address>
 *       <p><img src="https://pic.850123.xyz/d/BQACAg..."><img src="..."></p>
 *   </article>
 *
 * 注意：图床 URL 可能没有文件扩展名（BQACAg... 这种 Telegram file_id 形态），
 * 所以不能靠 .jpg/.png 后缀判断是不是图片，只看 <img src> 与 <a href> 的形态。
 */
object TelegraphParser {

    private val IMG_SRC = Regex(
        """<img\b[^>]*?\b(?:src|data-src)\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val IMG_SRC_ALT = Regex(
        """<img\b[^>]*?\bdata-original\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val A_IMG = Regex(
        """<a\b[^>]*?\bhref\s*=\s*["'](https?://[^"']+\.(?:jpe?g|png|webp|gif|bmp|avif)(?:\?[^"']*)?)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val ARTICLE = Regex(
        """<article\b[^>]*>(.*?)</article>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val H1 = Regex(
        """<h1\b[^>]*>(.*?)</h1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val OG_TITLE = Regex(
        """<meta\b[^>]*property\s*=\s*["']og:title["'][^>]*content\s*=\s*["']([^"']*)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val OG_IMAGE = Regex(
        """<meta\b[^>]*property\s*=\s*["']og:image["'][^>]*content\s*=\s*["']([^"']*)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val AUTHOR = Regex(
        """<address\b[^>]*>(.*?)</address>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TAG = Regex("""<[^>]+>""")
    private val NEXT_LINK = Regex(
        """<a\b[^>]*?\bhref\s*=\s*["']([^"']+)["'][^>]*>\s*(?:Next|下一页|下一頁|›|»|Continued)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val TELEGRAPH_RE = Regex(
        """https?://(?:telegra\.ph|telegraph\.ph|te\.legra\.ph|graph\.org)/[^\s"'<>）【】，,；;]+""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** t.me 消息 / 频道链接 */
    private val TG_RE = Regex(
        """https?://(?:t\.me|telegram\.me)/(?:s/)?[A-Za-z0-9_]+(?:/\d+)?[^\s"'<>）【】，,；;]*""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** e-hentai / exhentai 链接（画廊 / 图片页 / 搜索列表页） */
    private val EH_RE = Regex(
        """https?://(?:e-hentai\.org|exhentai\.org|e-hentai\.moe|e-hentai\.eu)/[^\s"'<>）【】，,；;]*""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** 图片直链（以图片扩展名结尾） */
    private val IMAGE_RE = Regex(
        """https?://[^\s"'<>）【】，,；;]+?\.(?:jpe?g|png|webp|gif|avif)(?:\?[^\s"'<>）【】，,；;]*)?$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    data class Article(
        val url: String,
        val title: String,
        val author: String,
        val images: List<String>,
        val cover: String = ""
    )

    /** 从任意文本里找第一个可解析链接 */
    fun findUrl(text: String?): String? = text?.let { findAllUrls(it).firstOrNull() }

    /** 从任意文本里找全部可导入链接（telegra.ph / t.me / 图片直链），按出现顺序去重 */
    fun findAllUrls(text: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        for (re in listOf(TELEGRAPH_RE, TG_RE, EH_RE, IMAGE_RE)) {
            for (m in re.findAll(text)) {
                found.add(m.range.first to cleanUrl(m.value))
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun cleanUrl(raw: String): String =
        raw.trim().trimEnd('》', '。', '.', ',', ')', '）', ']', '】', '"', '\'', '，', ';', '；')

    fun isSupported(url: String): Boolean =
        TELEGRAPH_RE.containsMatchIn(url) || isTgLink(url) || isImageUrl(url) || EhApi.isEh(url)

    /** t.me 消息 / 频道链接 */
    fun isTgLink(url: String): Boolean =
        Regex("""^https?://(?:t\.me|telegram\.me)/""", RegexOption.IGNORE_CASE).containsMatchIn(url.trim())

    /** 图片直链 */
    fun isImageUrl(url: String): Boolean =
        Regex("""\.(?:jpe?g|png|webp|gif|avif)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url.trim())

    /** 抓一页正文图片 */
    fun parse(html: String, pageUrl: String): Article {
        val title = extractTitle(html)
        val author = extractAuthor(html)

        val scope = ARTICLE.find(html)?.groupValues?.get(1) ?: html
        val imgs = LinkedHashSet<String>()

        fun push(raw: String) {
            var u = raw.trim()
            if (u.isEmpty()) return
            if (u.startsWith("data:")) return
            if (u.startsWith("//")) u = "https:$u"
            if (!u.startsWith("http", true)) {
                u = try {
                    val base = java.net.URI(pageUrl)
                    base.resolve(u).toString()
                } catch (e: Exception) {
                    return
                }
            }
            val low = u.lowercase()
            if (low.endsWith(".svg") || low.endsWith(".ico") || low.contains("favicon")) return
            if (low.contains("logo") && low.contains("telegra.ph")) return
            imgs.add(u)
        }

        IMG_SRC.findAll(scope).forEach { push(it.groupValues[1]) }
        IMG_SRC_ALT.findAll(scope).forEach { push(it.groupValues[1]) }
        A_IMG.findAll(scope).forEach { push(it.groupValues[1]) }

        if (imgs.size < 2) {
            // 有些页面图在 article 外，或只有 og:image
            IMG_SRC.findAll(html).forEach { push(it.groupValues[1]) }
            OG_IMAGE.find(html)?.groupValues?.get(1)?.let { push(it) }
        }

        return Article(pageUrl, title, author, imgs.toList())
    }

    fun extractTitle(html: String): String {
        val raw = H1.find(html)?.groupValues?.get(1)
            ?: OG_TITLE.find(html)?.groupValues?.get(1)
            ?: "未命名"
        var t = decode(TAG.replace(raw, " "))
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = t.replace(Regex("""\s*[–—-]\s*Telegraph\s*$""", RegexOption.IGNORE_CASE), "")
        return t.ifBlank { "未命名" }
    }

    /** 作者：优先取 <address> 里的第一个 <a> 文本，避免把 <time> 的日期也拼进去 */
    fun extractAuthor(html: String): String {
        val addr = AUTHOR.find(html)?.groupValues?.get(1) ?: return ""
        val firstLink = Regex("""<a\b[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(addr)?.groupValues?.get(1)
        val src = firstLink ?: Regex("""<time\b.*?</time>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(addr, " ")
        return decode(TAG.replace(src, " "))
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
    }

    /** 找出页面里的“下一页”链接（telegra.ph 长文分页 / 作者自连页） */
    fun findNext(html: String, pageUrl: String): String? {
        val m = NEXT_LINK.find(html) ?: return null
        val href = m.groupValues[1]
        if (!isSupported(href)) return null
        val abs = if (href.startsWith("http", true)) href else
            runCatching { java.net.URI(pageUrl).resolve(href).toString() }.getOrNull() ?: return null
        return if (cleanUrl(abs) == cleanUrl(pageUrl)) null else cleanUrl(abs)
    }

    private fun decode(s: String): String {
        var t = s
        t = t.replace("&nbsp;", " ")
        t = t.replace("&amp;", "&")
        t = t.replace("&quot;", "\"")
        t = t.replace("&#39;", "'")
        t = t.replace("&apos;", "'")
        t = t.replace("&lt;", "<")
        t = t.replace("&gt;", ">")
        t = Regex("""&#(\d+);""").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
        }
        t = Regex("""&#x([0-9a-fA-F]+);""").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault("")
        }
        return t
    }
}
