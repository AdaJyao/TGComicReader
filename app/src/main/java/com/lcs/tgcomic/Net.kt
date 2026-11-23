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
        var last: Exception? = null
        for (attempt in 0 until retries.coerceAtLeast(1)) {
            try {
                return@withContext fetch(url, headers)
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
        url.contains("telesco.pe") || url.contains("telegram-cdn") || url.contains("t.me") ->
            "https://t.me/"
        else -> "https://telegra.ph/"
    }
}
