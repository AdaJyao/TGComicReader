package com.lcs.tgcomic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 禁漫的一页。带上打乱参数交给下面的 Fetcher，
 * 下载完先还原再交给 Coil —— 不然每页都是错位的横条。
 */
data class JmImage(
    val url: String,
    val info: JmScramble.Info,
    /** 已经缓存到本地时直接读文件 */
    val cached: java.io.File? = null
)

class JmImageKeyer : Keyer<JmImage> {
    // 缓存键只认原链接：同一张图不管走网络还是走本地文件都命中同一份，
    // 否则换个键就是一次重新解码（会把画面闪一下）
    override fun key(data: JmImage, options: Options): String = data.url
}

/** 取到的一页：原始字节 + 这份字节还需不需要还原 */
internal class JmBytes(val bytes: ByteArray, val needsReorder: Boolean)

/** 原始字节留一份在内存里：同一页来回翻不用再下载 */
private object JmByteCache {
    private const val MAX = 32
    private val map = object : LinkedHashMap<String, JmBytes>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JmBytes>) = size > MAX
    }

    @Synchronized
    fun get(key: String): JmBytes? = map[key]

    @Synchronized
    fun put(key: String, value: JmBytes) {
        map[key] = value
    }
}

/**
 * 自己的磁盘缓存，存的是**站点原图字节**（不重新压缩）。
 *
 * Coil 的 diskCache 只对 HTTP fetcher 生效，自定义 Fetcher 返回的数据不会写盘；
 * 只靠内存缓存的话滑远一点就没了，回到那一页又要重新下载。
 *
 * 两个后缀：
 *   · `.raw` —— 站点原图，显示时先还原再画（新版本统一用这个）
 *   · `.dec` —— 老版本重压过的 JPEG（已经是还原后的画面），直接画，别再还原一次
 */
private object JmDiskCache {
    private const val MAX_FILES = 900

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "jm").apply { if (!exists()) mkdirs() }

    private fun rawFile(ctx: Context, info: JmScramble.Info) =
        File(dir(ctx), "${info.aid}_${info.name}.raw")

    private fun legacyFile(ctx: Context, info: JmScramble.Info) =
        File(dir(ctx), "${info.aid}_${info.name}.dec")

    fun get(ctx: Context, info: JmScramble.Info): JmBytes? {
        val raw = rawFile(ctx, info)
        if (raw.exists() && raw.length() > 512) {
            return runCatching { JmBytes(raw.readBytes(), true) }.getOrNull()
        }
        val old = legacyFile(ctx, info)
        if (old.exists() && old.length() > 512) {
            return runCatching { JmBytes(old.readBytes(), false) }.getOrNull()
        }
        return null
    }

    fun put(ctx: Context, info: JmScramble.Info, bytes: ByteArray) {
        runCatching {
            val f = rawFile(ctx, info)
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
 * 取一页的原始字节：内存 → 自己的磁盘缓存 → 整册缓存文件 → 网络。
 * 阅读器预取和 Coil 的 Fetcher 都走这里，保证同一张图只下载一次。
 */
internal suspend fun loadJmBytes(
    ctx: Context,
    info: JmScramble.Info,
    url: String,
    pageFile: File?,
    callFactory: Call.Factory
): JmBytes = withContext(Dispatchers.IO) {
    JmByteCache.get(url)?.let { return@withContext it }

    JmDiskCache.get(ctx, info)?.let {
        JmByteCache.put(url, it)
        return@withContext it
    }

    if (pageFile != null && pageFile.exists() && pageFile.length() > 1024) {
        // 老版本的整册缓存是「还原过的 JPEG」（旁边有 .jm 标记）；新版本存原图
        val done = JmScramble.isDecoded(pageFile)
        val bytes = pageFile.readBytes()
        val v = JmBytes(bytes, !done)
        JmByteCache.put(url, v)
        if (!done) JmDiskCache.put(ctx, info, bytes)
        return@withContext v
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
        val v = JmBytes(bytes, true)
        JmByteCache.put(url, v)
        JmDiskCache.put(ctx, info, bytes)
        v
    }
}

/**
 * 邻页预取：滚到下一页时字节已经在本地，只剩解码 + 还原。
 * 只预取禁漫（其它来源走 Coil 自己的磁盘缓存，且 EH 要限速）。
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

/**
 * 关键：**不重新压缩**。
 *
 * 老实现是「解码 → 切条重排 → 压回 JPEG」。重压会让黑白漫画的深色区域泛出淡淡色偏
 * （JPEG 色度子采样在近黑处最容易显形），和站点浏览器的画面不一致。
 * 现在直接把排好的位图交给 Coil（DrawableResult），像素与原图完全一致。
 */
private class JmImageFetcher(
    private val data: JmImage,
    private val options: Options,
    private val callFactory: Call.Factory
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val loaded = loadJmBytes(options.context, data.info, data.url, data.cached, callFactory)
        val sample = sampleSizeFor(loaded.bytes, options)
        val bmp = BitmapFactory.decodeByteArray(
            loaded.bytes, 0, loaded.bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw IOException("这张图解不开（可能是坏图或封禁页）")

        val src = if (loaded.needsReorder) {
            val strips = JmScramble.strips(data.info)
            if (strips >= 2) JmScramble.reorder(bmp, strips) else bmp
        } else bmp

        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, src),
            isSampled = sample > 1,
            dataSource = DataSource.NETWORK
        )
    }

    /** 按目标尺寸算 2 的幂采样率，别为了书架缩略图把整张大图解码出来 */
    private fun sampleSizeFor(bytes: ByteArray, options: Options): Int {
        val tw = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val th = (options.size.height as? Dimension.Pixels)?.px ?: 0
        if (tw <= 0 || th <= 0) return 1
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1
        var s = 1
        while (bounds.outWidth / (s * 2) >= tw && bounds.outHeight / (s * 2) >= th) s *= 2
        return s
    }
}
