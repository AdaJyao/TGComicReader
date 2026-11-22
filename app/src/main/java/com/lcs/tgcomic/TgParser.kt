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

    /** 解析链接 → (频道, 消息 id?, 是否频道页)；t.me/c/... 私有链接返回 null */
    fun parseLink(url: String): Triple<String, Long?, Boolean>? {
        val u = url.trim()
        if (Regex("""^https?://(?:t\.me|telegram\.me)/c/""", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
            return null
        }
        Regex("""^https?://(?:t\.me|telegram\.me)/s/([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE).find(u)?.let {
            return Triple(it.groupValues[1], null, true)
        }
        Regex("""^https?://(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/(\d+)""", RegexOption.IGNORE_CASE).find(u)?.let {
            return Triple(it.groupValues[1], it.groupValues[2].toLongOrNull(), false)
        }
        Regex("""^https?://(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/?$""", RegexOption.IGNORE_CASE).find(u)?.let {
            return Triple(it.groupValues[1], null, true)
        }
        return null
    }

    /** 拉取并解析；目标消息不在首页时用 ?before=id+1 精确定位 */
    suspend fun load(url: String): Channel? {
        val parsed = parseLink(url) ?: return null
        val (channel, msgId, _) = parsed

        if (msgId != null) {
            val html = Net.getText("https://t.me/s/$channel?before=${msgId + 1}")
            val hit = parsePosts(html, channel).filter { it.key == "$channel/$msgId" }
            if (hit.isNotEmpty()) return Channel(channelTitle(html, channel), hit)

            val html2 = Net.getText("https://t.me/s/$channel")
            val hit2 = parsePosts(html2, channel).filter { it.key == "$channel/$msgId" }
            if (hit2.isNotEmpty()) return Channel(channelTitle(html2, channel), hit2)
            return null
        }

        val html = Net.getText("https://t.me/s/$channel")
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
