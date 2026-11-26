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

    /** 按文档顺序扫出来的「图片块」：<a href="图"><img src="图"></a> 只算一张，别重复也别乱序 */
    private val BLOCK = Regex(
        """<a\b[^>]*>.*?</a>|<img\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val IMG_TAG = Regex("""<img\b[^>]*>""", setOf(RegexOption.IGNORE_CASE))
    private val ATTR = Regex("""([\w-]+)\s*=\s*["']([^"']*)["']""")

    /**
     * 占位图 / 跟踪像素 / 视频封面之类：作者在正文里插的这些东西不是漫画页，
     * 不滤掉就会在阅读器里显示成一整张纯色块或者 YouTube 封面。
     */
    private val JUNK_HOSTS = listOf(
        "placehold.co", "placehold.it", "placeholder.com", "via.placeholder.com",
        "dummyimage.com", "fakeimg.pl", "picsum.photos",
        "i.ytimg.com", "img.youtube.com", "yt3.ggpht.com",
        "analytics", "doubleclick", "googletagmanager"
    )
    private val JUNK_PATH = Regex(
        """(?:^|/)(?:spacer|blank|pixel|transparent|loading|lazy|1x1|dot)\.(?:gif|png|jpe?g|webp)$""",
        RegexOption.IGNORE_CASE
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

    private val TELEGRAPH_RE = Regex(
        """https?://(?:telegra\.ph|telegraph\.ph|te\.legra\.ph|graph\.org)/[^\s"'<>）【】，,；;]+""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** telegra.ph 自己的图床直链：/file/<hash> 常常没有扩展名，应当当图片处理而不是文章 */
    private val TELEGRAPH_FILE_RE = Regex(
        """https?://(?:telegra\.ph|telegraph\.ph|te\.legra\.ph|graph\.org)/file/[^\s"'<>）【】，,；;]+""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun isTelegraphFile(url: String): Boolean = TELEGRAPH_FILE_RE.containsMatchIn(url.trim())

    /** 图片直链的标题：用文件名；telegra.ph 的 /file/<hash> 没名字，就用「telegra 图片」 */
    fun imageTitle(url: String): String {
        val name = url.substringAfterLast('/').substringBefore('?').trim()
        val looksHashed = name.length >= 24 && !name.contains('.')
        return when {
            looksHashed -> "telegra 图片"
            name.isBlank() -> "图片直链"
            else -> name
        }
    }

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

    /** 紳士漫畫 wnacg */
    private val WN_RE = Regex(
        """https?://(?:www\.|m\.)?wnacg\.(?:com|net|org)/[^\s"'<>）【】，,；;]*""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** 禁漫天堂：/album/<aid>、/photo/<cid>（任意镜像域名） */
    private val JM_RE = Regex("""https?://[\w.-]+/(?:album|photo)/\d+""", RegexOption.IGNORE_CASE)

    /** 图片直链（以图片扩展名结尾）。注意不要用 $ 锚定：一段文字中间也要能认出来 */
    private val IMAGE_RE = Regex(
        """https?://[^\s"'<>）【】，,；;]+?\.(?:jpe?g|png|webp|gif|avif)(?:\?[^\s"'<>）【】，,；;]*)?""",
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
        for (re in listOf(TELEGRAPH_RE, TELEGRAPH_FILE_RE, TG_RE, EH_RE, WN_RE, JM_RE, IMAGE_RE)) {
            for (m in re.findAll(text)) {
                found.add(m.range.first to cleanUrl(m.value))
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun cleanUrl(raw: String): String =
        raw.trim().trimEnd('》', '。', '.', ',', ')', '）', ']', '】', '"', '\'', '，', ';', '；')

    fun isSupported(url: String): Boolean =
        TELEGRAPH_RE.containsMatchIn(url) || isTgLink(url) || isImageUrl(url) ||
            EhApi.isEh(url) || WnacgApi.isWnacg(url) || JmApi.isAlbum(url) || JmApi.isChapter(url)

    /** t.me 消息 / 频道链接 */
    fun isTgLink(url: String): Boolean =
        Regex("""^https?://(?:t\.me|telegram\.me)/""", RegexOption.IGNORE_CASE).containsMatchIn(url.trim())

    /** 图片直链（telegra.ph 的 /file/<hash> 没有扩展名，也算） */
    fun isImageUrl(url: String): Boolean =
        isTelegraphFile(url) || Regex("""\.(?:jpe?g|png|webp|gif|avif)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url.trim())

    /** 抓一页正文图片 */
    fun parse(html: String, pageUrl: String): Article {
        val title = extractTitle(html)
        val author = extractAuthor(html)

        val scope = ARTICLE.find(html)?.groupValues?.get(1) ?: html
        val imgs = LinkedHashSet<String>()
        val seen = HashSet<String>()

        fun push(raw: String) {
            val u = absolute(raw, pageUrl) ?: return
            if (isJunk(u)) return
            if (!seen.add(dedupeKey(u))) return
            imgs.add(u)
        }

        // 按文档顺序走一遍：<a href="大图"><img src="缩略图"></a> 这类只取一张
        for (block in BLOCK.findAll(scope)) {
            val text = block.value
            val inner = IMG_TAG.find(text)
            if (inner != null) {
                pickImgUrl(inner.value)?.let { push(it) }
            } else {
                // 纯文字锚点里挂的原图直链
                val open = text.substringBefore('>') + ">"
                val href = ATTR.findAll(open)
                    .firstOrNull { it.groupValues[1].equals("href", true) }
                    ?.groupValues?.get(2)
                if (href != null && isImageUrl(href)) push(href)
            }
        }

        if (imgs.size < 2) {
            // 有些页面图在 article 外，或只有 og:image
            IMG_SRC.findAll(html).forEach { push(it.groupValues[1]) }
            IMG_SRC_ALT.findAll(html).forEach { push(it.groupValues[1]) }
            OG_IMAGE.find(html)?.groupValues?.get(1)?.let { push(it) }
        }

        return Article(pageUrl, title, author, imgs.toList(), imgs.firstOrNull().orEmpty())
    }

    /** 一个 <img> 里挑最像原图的那个属性（懒加载时 src 常是 1px 占位图） */
    private fun pickImgUrl(tag: String): String? {
        val attrs = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(tag)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
        val order = listOf(
            "data-original", "data-src", "data-lazy-src", "data-actualsrc", "data-echo", "src"
        )
        for (k in order) {
            val v = attrs[k]?.trim().orEmpty()
            if (v.isNotEmpty() && !v.startsWith("data:")) return v
        }
        return null
    }

    /** 相对路径补全；返回 null 表示这行不该要 */
    private fun absolute(raw: String, pageUrl: String): String? {
        var u = raw.trim()
        if (u.isEmpty() || u.startsWith("data:")) return null
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http", true)) {
            u = runCatching { java.net.URI(pageUrl).resolve(u).toString() }.getOrNull() ?: return null
        }
        return u.substringBefore('#')
    }

    /** 占位图 / 视频封面 / 站点图标：这些不是漫画页 */
    private fun isJunk(u: String): Boolean {
        val low = u.lowercase()
        if (low.endsWith(".svg") || low.endsWith(".ico") || low.contains("favicon")) return true
        if (low.contains("/emoji/") || low.contains("emoji.")) return true
        if (low.contains("logo") && low.contains("telegra.ph")) return true
        for (h in JUNK_HOSTS) if (low.contains(h)) return true
        val path = low.substringBefore('?')
        if (JUNK_PATH.containsMatchIn(path)) return true
        // 明确标了 1x1 / 32x32 的尺寸占位
        if (Regex("""[/=_-](?:1|2|10|16|32)x(?:1|2|10|16|32)[/._-]""").containsMatchIn(path)) return true
        return false
    }

    /** 去重键：同 host + 同路径算同一张（忽略 ?w=800 这类缩放参数和 #锚点） */
    private fun dedupeKey(u: String): String {
        val noQuery = u.substringBefore('?')
        return noQuery.removePrefix("https://").removePrefix("http://").lowercase()
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
        val anchors = Regex(
            """<a\b([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (a in anchors.findAll(html)) {
            val text = decode(TAG.replace(a.groupValues[2], " "))
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (text.isEmpty() || text.length > 24) continue
            if (NEXT_TEXT.containsMatchIn(text) && !PREV_TEXT.containsMatchIn(text)) {
                val href = ATTR.findAll(a.groupValues[1])
                    .firstOrNull { it.groupValues[1].equals("href", true) }
                    ?.groupValues?.get(2) ?: continue
                if (!isSupported(href) && !TELEGRAPH_RE.containsMatchIn(href)) continue
                val abs = if (href.startsWith("http", true)) href else
                    runCatching { java.net.URI(pageUrl).resolve(href).toString() }.getOrNull() ?: continue
                if (cleanUrl(abs) != cleanUrl(pageUrl)) return cleanUrl(abs)
            }
        }
        return null
    }

    private val NEXT_TEXT = Regex(
        """^(?:下一?[页頁篇章张張]|下|続き|次へ|next|continue|part\s*\d+|more|[›»→]|&raquo;|&rarr;|第\s*\d+\s*[页頁]|[2-9]\s*[\/|]\s*\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val PREV_TEXT = Regex(
        """^(?:上一?[页頁篇章]|前|prev|back|[‹«←]|&laquo;|&larr;)""",
        RegexOption.IGNORE_CASE
    )

    private fun decode(s: String): String {
        var t = s
        t = t.replace("&nbsp;", " ")
        t = t.replace("&amp;", "&")
        t = t.replace("&quot;", "\"")
        t = t.replace("&#39;", "'")
        t = t.replace("&apos;", "'")
        t = t.replace("&lt;", "<")
        t = t.replace("&gt;", ">")
        // 翻页箭头：先解成字符，找「下一页」时才好统一判断
        t = t.replace("&raquo;", "»").replace("&laquo;", "«")
        t = t.replace("&rarr;", "→").replace("&larr;", "←")
        t = t.replace("\u200b", "").replace("\u00a0", " ")
        t = Regex("""&#(\d+);""").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
        }
        t = Regex("""&#x([0-9a-fA-F]+);""").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault("")
        }
        return t
    }
}
