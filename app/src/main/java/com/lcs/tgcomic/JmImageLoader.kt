package com.lcs.tgcomic

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource as newImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.io.IOException

/**
 * 禁漫的一页。带上打乱参数交给下面的 Fetcher，
 * 下载完先还原再交给 Coil —— 不然每页都是错位的横条。
 */
data class JmImage(
    val url: String,
    val info: JmScramble.Info,
    /** 已经缓存到本地时直接读文件，但仍然要先还原（老版本的缓存文件是打乱的） */
    val cached: java.io.File? = null
)

class JmImageKeyer : Keyer<JmImage> {
    // 缓存键只认原链接：同一张图不管走网络还是走本地文件都命中同一份，
    // 否则换个键就是一次重新解码（会把画面闪一下）
    override fun key(data: JmImage, options: Options): String = data.url
}

/** 还原后的字节留一份在内存里：同一页来回翻不用再切一遍条 */
private object JmByteCache {
    private const val MAX = 32
    private val map = object : LinkedHashMap<String, ByteArray>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX
    }

    @Synchronized
    fun get(key: String): ByteArray? = map[key]

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        map[key] = bytes
    }
}

/**
 * 自己落一份磁盘缓存。
 *
 * Coil 的 diskCache 只对 HTTP fetcher 生效，自定义 Fetcher 返回的数据不会写盘 ——
 * 只靠内存缓存的话，滑动稍微远一点就被回收，回到那一页又要重新下载 + 重新切条，
 * 表现出来就是画面一遍遍闪。这里把「还原后的字节」存下来，下次直接从本地读。
 * 文件名带 .dec 后缀，表示已经是还原过的，避免再解一遍（还原是对合变换，解两次就回去了）。
 */
private object JmDiskCache {
    private const val MAX_FILES = 900

    private fun fileFor(ctx: Context, info: JmScramble.Info): File {
        val dir = File(ctx.cacheDir, "jm")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${info.aid}_${info.name}.dec")
    }

    fun get(ctx: Context, info: JmScramble.Info): ByteArray? {
        val f = fileFor(ctx, info)
        if (!f.exists() || f.length() < 512) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun put(ctx: Context, info: JmScramble.Info, bytes: ByteArray) {
        runCatching {
            val f = fileFor(ctx, info)
            f.writeBytes(bytes)
            trim(f.parentFile)
        }
    }

    /** 简单按数量裁剪：只删最旧的那批，别让缓存无限长 */
    private fun trim(dir: File?) {
        dir ?: return
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }
}

/**
 * 取「已经还原好」的字节：内存 → 自己的磁盘缓存 → 整册缓存文件 → 网络。
 * 阅读器预取和 Coil 的 Fetcher 都走这里，保证同一张图只下载一次、只切一次条。
 */
internal suspend fun loadJmBytes(
    ctx: Context,
    info: JmScramble.Info,
    url: String,
    pageFile: File?,
    callFactory: Call.Factory
): ByteArray = withContext(Dispatchers.IO) {
    JmByteCache.get(url)?.let { return@withContext it }

    JmDiskCache.get(ctx, info)?.let {
        JmByteCache.put(url, it)
        return@withContext it
    }

    if (pageFile != null && pageFile.exists() && pageFile.length() > 1024) {
        // 「缓存整册」下来的文件：老版本的可能是打乱的原图，还原一次并打标记
        JmScramble.decodeInPlace(pageFile, info)
        val bytes = pageFile.readBytes()
        JmByteCache.put(url, bytes)
        if (JmScramble.needsDecode(info)) JmDiskCache.put(ctx, info, bytes)
        return@withContext bytes
    }

    val req = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        // 禁漫的图床不带 Referer 会 403
        .header("Referer", "https://18comic.ink/")
        .build()
    callFactory.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        val bytes = resp.body?.bytes() ?: throw IOException("响应为空")
        val fixed = JmScramble.decode(bytes, info)
        JmByteCache.put(url, fixed)
        if (JmScramble.needsDecode(info)) JmDiskCache.put(ctx, info, fixed)
        fixed
    }
}

/**
 * 邻页预取：滚到下一页时图已经在手里，就不会先空一下再「闪」出来。
 * 只预取禁漫的图（其它来源走 Coil 自己的磁盘缓存，且 EH 要限速）。
 */
object JmWarmer {
    private val running = java.util.Collections.synchronizedSet(HashSet<String>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun warm(ctx: Context, pageFile: File?, url: String, callFactory: Call.Factory) {
        val info = JmScramble.parse(url) ?: return
        if (JmByteCache.get(url) != null) return
        if (!running.add(url)) return
        scope.launch {
            runCatching { loadJmBytes(ctx, info, url, pageFile, callFactory) }
            running.remove(url)
        }
    }
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

    override suspend fun fetch(): FetchResult {
        val bytes = loadJmBytes(options.context, data.info, data.url, data.cached, callFactory)
        return SourceResult(
            source = newImageSource(Buffer().apply { write(bytes) }, options.context.cacheDir),
            // 还原后是重新压的 JPEG；没打乱的原样透传，交给 Coil 自己嗅探
            mimeType = if (JmScramble.needsDecode(data.info)) "image/jpeg" else null,
            dataSource = DataSource.NETWORK
        )
    }
}
