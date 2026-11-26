package com.lcs.tgcomic

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

object Net {

    /** E-Hentai 的 509（请求太快）单独抛出，避免无脑重试把封禁时间拖长 */
    class RateLimited(message: String) : IOException(message)

    /**
     * 抓 HTML 用的共享 OkHttp：连接池 + HTTP/2。
     * 原来每次 Net.getText 都新建 HttpURLConnection，telegra.ph / t.me 这种海外站点
     * 每次都要重新 TLS 握手（1~2 秒），连着解析几页时体感就是「慢」。
     */
    private val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 页面 HTML 短缓存（2 分钟 / 12 条）：同一链接重复解析、多页图文来回跳时不再重新下载 */
    private const val CACHE_TTL = 120_000L
    private val htmlCache = object : LinkedHashMap<String, Pair<String, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<String, Long>>
        ): Boolean = size > 12
    }

    fun cachedHtml(url: String): String? = synchronized(htmlCache) {
        val hit = htmlCache[url] ?: return@synchronized null
        if (System.currentTimeMillis() - hit.second > CACHE_TTL) {
            htmlCache.remove(url)
            null
        } else hit.first
    }

    private fun putHtml(url: String, html: String) = synchronized(htmlCache) {
        htmlCache[url] = html to System.currentTimeMillis()
    }

    /**
     * Cloudflare 人机验证页：e-hentai / exhentai 现在整站在 CF 后面，
     * 拿到这类页面时不能当成「页面结构变了」，要单独给用户提示。
     */
    fun looksLikeChallenge(html: String): Boolean {
        if (html.length > 20000) return false
        val h = html.lowercase()
        return h.contains("just a moment") ||
            h.contains("cf-challenge") ||
            h.contains("performing security verification") ||
            h.contains("challenge-platform") ||
            h.contains("attention required")
    }

    /** 把含中文/空格的 URL 变成合法 URL */
    fun safeUrl(raw: String): String {
        return try {
            URL(raw).toString()
        } catch (e: Exception) {
            val encoded = raw.replace(" ", "%20")
            try {
                URI(encoded).toURL().toString()
            } catch (e2: Exception) {
                // 逐段编码非 ASCII
                val sb = StringBuilder()
                for (ch in encoded) {
                    if (ch.code > 127) {
                        sb.append(URLEncoder.encode(ch.toString(), "UTF-8"))
                    } else sb.append(ch)
                }
                sb.toString()
            }
        }
    }

    /**
     * 抓页面。
     * @param headers 额外请求头（Cookie / Referer 等，EH 需要）
     * @param retries 重试次数；509 不重试
     */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        retries: Int = 3
    ): String = withContext(Dispatchers.IO) {
        if (headers.isEmpty()) cachedHtml(url)?.let { return@withContext it }
        var last: Exception? = null
        for (attempt in 0 until retries.coerceAtLeast(1)) {
            try {
                val html = fetch(url, headers)
                if (headers.isEmpty()) putHtml(url, html)
                return@withContext html
            } catch (e: RateLimited) {
                throw e
            } catch (e: Exception) {
                last = e
                runCatching { Thread.sleep(400L * (attempt + 1)) }
            }
        }
        throw last ?: IOException("请求失败: $url")
    }

    private fun fetch(url: String, headers: Map<String, String>): String {
        return try {
            fetchOkhttp(url, headers)
        } catch (e: RateLimited) {
            throw e
        } catch (e: Exception) {
            // OkHttp 不通（个别站点/代理组合）再退回老路子
            fetchConn(url, headers)
        }
    }

    private fun fetchOkhttp(url: String, headers: Map<String, String>): String {
        val b = okhttp3.Request.Builder().url(safeUrl(url))
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7")
        headers.forEach { (k, v) -> if (v.isNotBlank()) b.header(k, v) }
        return http.newCall(b.build()).execute().use { r ->
            if (r.code == 509) throw RateLimited("站点限速（HTTP 509）：请求太快，等几分钟再试")
            if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
            val ct = r.header("Content-Type") ?: "text/html"
            val charset = if (ct.contains("charset=", true))
                ct.substringAfter("charset=", "UTF-8").substringBefore(';').trim().ifBlank { "UTF-8" }
            else "UTF-8"
            val body = r.body?.bytes() ?: ByteArray(0)
            body.toString(charsetOf(charset))
        }
    }

    private fun fetchConn(url: String, headers: Map<String, String>): String {
        val conn = URL(safeUrl(url)).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7")
            headers.forEach { (k, v) -> if (v.isNotBlank()) conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            if (code == 509) throw RateLimited("站点限速（HTTP 509）：请求太快，等几分钟再试")
            if (code !in 200..299) throw IOException("HTTP $code")
            val ct = conn.contentType ?: "text/html"
            val charset = when {
                ct.contains("charset=", true) ->
                    ct.substringAfter("charset=", "UTF-8").substringBefore(';').trim().ifBlank { "UTF-8" }
                else -> "UTF-8"
            }
            return conn.inputStream.readBytes().toString(charsetOf(charset))
        } finally {
            conn.disconnect()
        }
    }

    private fun charsetOf(name: String): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)

    /** 下图到本地。referer 传 null 时按域名自动挑（telegra.ph / t.me / e-hentai） */
    suspend fun download(url: String, dest: File, referer: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                dest.parentFile?.mkdirs()
                val conn = URL(safeUrl(url)).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Referer", referer ?: autoReferer(url))
                val code = conn.responseCode
                if (code !in 200..299) return@withContext false
                conn.inputStream.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 32 * 1024) }
                }
                // 被封禁/被拦截时会返回一个几百字节的小页面，别把它当成图片留在缓存目录里
                if (dest.length() < 1024) {
                    runCatching { dest.delete() }
                    return@withContext false
                }
                // 禁漫：这里只存站点原图，还原放到显示时做（重压会掉色，黑白漫深色区会泛色）
                true
            } catch (e: Exception) {
                runCatching { dest.delete() }
                false
            }
        }

    /** 图床按来源挑 Referer：EH 的 hath.network / ehgt.org、wnacg 的 qy0.ru 只认自家站点 */
    fun autoReferer(url: String): String = when {
        url.contains("hath.network") || url.contains("ehgt.org") ||
            url.contains("e-hentai.org") || url.contains("exhentai.org") -> "https://e-hentai.org/"
        url.contains("qy0.ru") || url.contains("wnacg.") -> "https://www.wnacg.com/"
        url.contains("18comic") || url.contains("jmcomic") -> "https://18comic.ink/"
        url.contains("telesco.pe") || url.contains("telegram-cdn") || url.contains("t.me") ->
            "https://t.me/"
        else -> "https://telegra.ph/"
    }
}
