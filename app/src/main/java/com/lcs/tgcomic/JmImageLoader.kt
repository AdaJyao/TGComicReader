package com.lcs.tgcomic

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource as newImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okio.Buffer
import java.io.IOException

/**
 * 禁漫的一页。带上打乱参数交给下面的 Fetcher，
 * 下载完先还原再交给 Coil —— 不然每页都是错位的横条。
 */
class JmImage(
    val url: String,
    val info: JmScramble.Info,
    /** 已经缓存到本地时直接读文件，但仍然要先还原（老版本的缓存文件是打乱的） */
    val cached: java.io.File? = null
)

class JmImageKeyer : Keyer<JmImage> {
    // 磁盘/内存缓存键就用原链接：同一张图不管从书架还是阅读器进来都命中同一份
    override fun key(data: JmImage, options: Options): String =
        if (data.cached != null) data.url + "#cache" else data.url
}

class JmImageFetcherFactory(private val callFactory: Call.Factory) : Fetcher.Factory<JmImage> {
    override fun create(data: JmImage, options: Options, imageLoader: ImageLoader): Fetcher =
        JmImageFetcher(data, options, callFactory)
}

private class JmImageFetcher(
    private val data: JmImage,
    private val options: Options,
    private val callFactory: Call.Factory
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val local = data.cached
        if (local != null && local.exists()) {
            // 老版本缓存下来的是打乱的原图，这里还原一次并打标记（再读到就直接用）
            JmScramble.decodeInPlace(local, data.info)
            val bytes = local.readBytes()
            return@withContext SourceResult(
                source = newImageSource(Buffer().apply { write(bytes) }, options.context.cacheDir),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        }
        val req = Request.Builder()
            .url(data.url)
            .header("User-Agent", UA)
            // 禁漫的图床不带 Referer 会 403
            .header("Referer", "https://18comic.ink/")
            .build()
        callFactory.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("响应为空")
            val fixed = JmScramble.decode(bytes, data.info)
            SourceResult(
                source = newImageSource(Buffer().apply { write(fixed) }, options.context.cacheDir),
                mimeType = if (fixed === bytes) resp.header("Content-Type") else "image/jpeg",
                dataSource = DataSource.NETWORK
            )
        }
    }
}
