package com.lcs.tgcomic

/**
 * Telegram 公开频道 / 消息链接解析。
 *
 * 支持的形态：
 *   https://t.me/<channel>/<id>      单条消息（用 ?before=id+1 定位）
 *   https://t.me/s/<channel>         频道预览页（最近 ~20 条）
 *   https://t.me/<channel>           同上
 *
 * 页面结构（2026-11 实测 t.me/s/telegram）：
 *   <div class="tgme_widget_message ..." data-post="telegram/452">
 *     <a class="tgme_widget_message_photo_wrap ..." style="...url('https://cdn1.telesco.pe/file/...')">
 *     <div class="tgme_widget_message_text ...">正文 HTML</div>
 *   </div>
 *   频道名在 .tgme_channel_info_header_title > span
 */
object TgParser {

    private val POST_MARK = Regex("""data-post="([^"]+)"""")
    private val PHOTO_BG = Regex(
        // 允许 &（EH/TG 的 CDN 链接常带查询参数），只在引号/右括号处断开
        """url\((['"]?)(https?://[^)'"]+)\1\)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val TEXT_BLOCK = Regex(
        """<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val CHANNEL_TITLE = Regex(
        """tgme_channel_info_header_title[^>]*>\s*<span[^>]*>([^<]+)</span>""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val ANY_IMG = Regex("""<img[^>]+src="(https?://[^"]+)"""", setOf(RegexOption.IGNORE_CASE))
    private val HREF = Regex("""href="(https?://[^"]+)"""", setOf(RegexOption.IGNORE_CASE))
    private val TAG = Regex("<[^>]+>")

    data class Post(
        val key: String,          // channel/id
        val text: String,
        val images: List<String>,
        val links: List<String>   // 消息里出现的 telegra.ph 链接
    )

    data class Channel(val name: String, val posts: List<Post>)

    /** 解析出来的链接形态 */
    data class Link(
        val channel: String,
        val msgId: Long? = null,
        /** true = 整个频道页（最近若干条），false = 单条消息 */
        val wholeChannel: Boolean = false,
        /** ?comment=<id> 评论区消息（网页预览拿不到正文） */
        val commentId: Long? = null
    )

    /** t.me/c/... 私有链接：未登录抓不到 */
    fun isPrivate(url: String): Boolean =
        Regex("""^https?://(?:t\.me|telegram\.me)/c/""", RegexOption.IGNORE_CASE).containsMatchIn(url.trim())

    /**
     * 支持的形态：
     *   t.me/<ch>/<id>           单条消息
     *   t.me/s/<ch>/<id>         单条消息（网页预览形式）
     *   t.me/s/<ch>  t.me/<ch>   频道页
     *   以上都可带 ?single、?comment=<id> 等参数（参数忽略；comment 只做提示）
     */
    fun parseLink(url: String): Link? {
        val u = url.trim()
        if (isPrivate(u)) return null
        val comment = Regex("""[?&]comment=(\d+)""").find(u)?.groupValues?.get(1)?.toLongOrNull()

        // 单条消息：t.me/s/<ch>/<id>
        Regex("""^https?://(?:t\.me|telegram\.me)/s/([A-Za-z0-9_]+)/(\d+)""", RegexOption.IGNORE_CASE)
            .find(u)?.let { m ->
                return Link(m.groupValues[1], m.groupValues[2].toLongOrNull(), wholeChannel = false, commentId = comment)
            }
        // 单条消息：t.me/<ch>/<id>
        Regex("""^https?://(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/(\d+)""", RegexOption.IGNORE_CASE)
            .find(u)?.let { m ->
                return Link(m.groupValues[1], m.groupValues[2].toLongOrNull(), wholeChannel = false, commentId = comment)
            }
        // 频道页：t.me/s/<ch>、t.me/<ch>
        Regex("""^https?://(?:t\.me|telegram\.me)/s/([A-Za-z0-9_]+)/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
            .find(u)?.let { m -> return Link(m.groupValues[1], null, wholeChannel = true, commentId = comment) }
        Regex("""^https?://(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
            .find(u)?.let { m -> return Link(m.groupValues[1], null, wholeChannel = true, commentId = comment) }
        return null
    }

    /** 抓 t.me 页面，网络失败给能看懂的提示（国内直连 Telegram 基本不通） */
    private suspend fun fetch(url: String): String = try {
        Net.getText(url)
    } catch (e: Exception) {
        throw IllegalStateException(
            "打不开 t.me（${e.message}）：手机要能访问 Telegram，国内一般需要开代理",
            e
        )
    }

    /** 拉取并解析；目标消息不在首页时用 ?before=id+1 精确定位 */
    suspend fun load(url: String): Channel? {
        val link = parseLink(url) ?: return null
        val channel = link.channel
        val msgId = link.msgId

        if (msgId != null) {
            val html = fetch("https://t.me/s/$channel?before=${msgId + 1}")
            val hit = parsePosts(html, channel).filter { it.key == "$channel/$msgId" }
            if (hit.isNotEmpty()) return Channel(channelTitle(html, channel), hit)

            val html2 = fetch("https://t.me/s/$channel")
            val hit2 = parsePosts(html2, channel).filter { it.key == "$channel/$msgId" }
            if (hit2.isNotEmpty()) return Channel(channelTitle(html2, channel), hit2)
            return null
        }

        val html = fetch("https://t.me/s/$channel")
        val posts = parsePosts(html, channel)
        if (posts.isEmpty()) return null
        return Channel(channelTitle(html, channel), posts)
    }

    private fun channelTitle(html: String, fallback: String): String =
        CHANNEL_TITLE.find(html)?.groupValues?.get(1)?.trim()?.ifBlank { fallback } ?: fallback

    private fun parsePosts(html: String, channel: String): List<Post> {
        val marks = POST_MARK.findAll(html).toList()
        if (marks.isEmpty()) return emptyList()
        return marks.mapIndexed { i, m ->
            val start = m.range.first
            val end = marks.getOrNull(i + 1)?.range?.first ?: html.length
            val chunk = html.substring(start, end)

            val textHtml = TEXT_BLOCK.find(chunk)?.groupValues?.get(1) ?: ""
            val text = decode(TAG.replace(textHtml, " "))
                .replace(Regex("""\s+"""), " ")
                .trim()

            val images = LinkedHashSet<String>()
            PHOTO_BG.findAll(chunk).forEach { images.add(it.groupValues[1]) }
            ANY_IMG.findAll(chunk).forEach {
                val u = it.groupValues[1]
                if (!u.contains("emoji") && !u.contains("telegram.org")) images.add(u)
            }

            val links = HREF.findAll(chunk)
                .map { it.groupValues[1] }
                .filter { it.contains("telegra.ph") || it.contains("telegraph.ph") }
                .distinct().toList()

            Post(m.groupValues[1], text, images.toList(), links)
        }
    }

    private fun decode(s: String): String {
        var t = s
        t = t.replace("&nbsp;", " ")
        t = t.replace("&amp;", "&")
        t = t.replace("&quot;", "\"")
        t = t.replace("&#39;", "'")
        t = t.replace("&lt;", "<")
        t = t.replace("&gt;", ">")
        t = Regex("""&#(\d+);""").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
        }
        return t
    }
}
