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
    private val PICTURE = Regex(
        """<picture\b[^>]*>(.*?)</picture>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val SOURCE_SRCSET = Regex(
        """<source\b[^>]*?\b(?:srcset|data-srcset)\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )
    /** HTML 注释里被注释掉的图不算（编辑过页面常留下） */
    private val COMMENT = Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL))

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

    /** telegra.ph 的文章页（不是图床直链） */
    fun isTelegraphArticle(url: String): Boolean =
        TELEGRAPH_RE.containsMatchIn(url.trim()) && !isTelegraphFile(url)

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

    /** 目录/合集页里的一条：标题（锚点文字）+ 目标文章地址 */
    data class IndexEntry(val title: String, val url: String)

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

        val cleaned = flattenPictures(COMMENT.replace(html, " "))
        val scope = ARTICLE.find(cleaned)?.groupValues?.get(1) ?: cleaned
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
                val fromImg = pickImgUrl(inner.value)
                val href = ATTR.findAll(text.substringBefore('>') + ">")
                    .firstOrNull { it.groupValues[1].equals("href", true) }
                    ?.groupValues?.get(2)
                val full = href?.let { h ->
                    absolute(h, pageUrl)?.takeIf { isImageUrl(it) || isTelegraphFile(it) }
                }
                // 缩略图带 ?w=600 这类缩放参数、而外层 <a> 指向同名原图时，用原图
                val chosen = when {
                    fromImg == null -> full
                    full != null && sameName(full, fromImg) && fromImg.contains('?') -> full
                    else -> fromImg
                }
                chosen?.let { push(it) }
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
            IMG_SRC.findAll(cleaned).forEach { push(it.groupValues[1]) }
            IMG_SRC_ALT.findAll(cleaned).forEach { push(it.groupValues[1]) }
            OG_IMAGE.find(html)?.groupValues?.get(1)?.let { push(it) }
        }

        return Article(pageUrl, title, author, imgs.toList(), imgs.firstOrNull().orEmpty())
    }

    /** 一个 <img> 里挑最像原图的那个属性（懒加载时 src 常是 1px 占位图） */
    private fun pickImgUrl(tag: String): String? {
        val attrs = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(tag)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
        // srcset 里一般同时放了多个尺寸，挑描述符最大的那个
        (attrs["srcset"] ?: attrs["data-srcset"])?.let { srcsetBest(it)?.first }?.let { return it }
        val order = listOf(
            "data-original", "data-original-src", "data-src", "data-lazy-src", "data-actualsrc",
            "data-actual-src", "data-echo", "data-full", "data-large", "data-zoom-src",
            "data-image", "src"
        )
        for (k in order) {
            val v = attrs[k]?.trim().orEmpty()
            if (v.isNotEmpty() && !v.startsWith("data:")) return v
        }
        return null
    }

    /** "a.jpg 800w, b.jpg 1600w" / "a.jpg 1x, b.jpg 2x" → 最大那份（带分数，方便跨 srcset 比较） */
    private fun srcsetBest(raw: String): Pair<String, Double>? {
        var best: Pair<String, Double>? = null
        for (part in raw.split(',')) {
            val bits = part.trim().split(Regex("""\s+"""))
            val url = bits.firstOrNull()?.trim().orEmpty()
            if (url.isEmpty() || url.startsWith("data:")) continue
            val desc = bits.getOrNull(1).orEmpty().lowercase()
            val score = when {
                desc.endsWith("w") -> desc.dropLast(1).toDoubleOrNull() ?: 0.0
                desc.endsWith("x") -> (desc.dropLast(1).toDoubleOrNull() ?: 0.0) * 10000
                else -> 1.0
            }
            if (best == null || score > best.second) best = url to score
        }
        return best
    }

    /** <picture><source srcset="大图"><img src="小图"></picture> → 统一成一张最大尺寸的 <img> */
    private fun flattenPictures(html: String): String = PICTURE.replace(html) { m ->
        val inner = m.groupValues[1]
        val fromSources = SOURCE_SRCSET.findAll(inner)
            .mapNotNull { srcsetBest(it.groupValues[1]) }
            .maxByOrNull { it.second }
        val fromImg = IMG_TAG.find(inner)?.value?.let { pickImgUrl(it) }
        val chosen = fromSources?.first ?: fromImg
        if (chosen == null) "" else """<img src="$chosen">"""
    }

    /** 两个地址是不是同一张图（只比文件名，不含目录和扩展名） */
    private fun sameName(a: String, b: String): Boolean {
        fun base(u: String) = u.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').lowercase()
        val x = base(a)
        val y = base(b)
        return x.isNotEmpty() && x == y
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

    /**
     * 目录 / 合集页：正文没几张图，全是指向**别的 telegra.ph 文章**的链接。
     * 这种页面当一册导入只有 1~2 页，没意义；调用方应该把它们展开成多册。
     */
    fun indexEntries(html: String, pageUrl: String, imageCount: Int): List<IndexEntry> {
        // 只有「一张图都没有、却挂了好几篇文章链接」才算目录页；
        // 有图的页面照常当一册导入（底下挂的“相关推荐”不该被展开成好几册）
        if (imageCount > 0) return emptyList()
        val scope = ARTICLE.find(html)?.groupValues?.get(1) ?: html
        val out = LinkedHashMap<String, IndexEntry>()
        for (a in ANCHOR.findAll(scope)) {
            val href = ATTR.findAll(a.groupValues[1])
                .firstOrNull { it.groupValues[1].equals("href", true) }
                ?.groupValues?.get(2) ?: continue
            val abs = if (href.startsWith("http", true)) href else
                runCatching { java.net.URI(pageUrl).resolve(href).toString() }.getOrNull() ?: continue
            val u = cleanUrl(abs)
            // 只认「文章」链接：图床 /file/、站外地址、自身、参数化的分享链接都排除
            if (!TELEGRAPH_RE.containsMatchIn(u) || isTelegraphFile(u)) continue
            if (u.substringAfter("://").contains('?')) continue
            if (u == cleanUrl(pageUrl)) continue
            val path = runCatching { java.net.URI(u).path.orEmpty() }.getOrDefault("")
            // 文章地址是一级路径、且 slug 里没有点；`/konachan.com` 这种其实是作者漏写协议的站外链接
            if (path.isBlank() || path == "/" || path.count { it == '/' } > 1) continue
            if (path.contains('.')) continue
            if (PATH_RESERVED.contains(path.trim('/').lowercase())) continue
            val text = decode(TAG.replace(a.groupValues[2], " "))
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (text.length !in 2..80) continue
            if (text.contains('.')) continue                     // "konachan.com" 这种不是文章标题
            if (NEXT_TEXT.containsMatchIn(text) || PREV_TEXT.containsMatchIn(text)) continue
            if (out.containsKey(u)) continue
            out[u] = IndexEntry(text, u)
        }
        return if (out.size >= 3) out.values.toList() else emptyList()
    }

    private val ANCHOR = Regex(
        """<a\b([^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** telegra.ph 自己的功能性路径，不是文章 */
    private val PATH_RESERVED = setOf(
        "account", "create", "updates", "version", "login", "search", "file", "about", "faq"
    )

    private val NEXT_TEXT = Regex(
        """^(?:下一?[页頁篇章张張]|下|続き|次へ|next|continue|part\s*\d+|more|[›»→]|&raquo;|&rarr;|第\s*\d+\s*[页頁]|[2-9]\s*[\/|]\s*\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val PREV_TEXT = Regex(
        """^(?:上一?[页頁篇章]|前|prev|back|[‹«←]|&laquo;|&larr;)""",
        RegexOption.IGNORE_CASE
    )

    /** 去掉标签后的正文纯文本：用来判断「这页到底有没有图、是不是下载清单」 */
    fun plainText(html: String): String =
        decode(TAG.replace(ARTICLE.find(html)?.groupValues?.get(1) ?: html, " "))
            .replace(Regex("""\s+"""), " ")

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
