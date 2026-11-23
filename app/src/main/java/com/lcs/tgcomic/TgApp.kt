package com.lcs.tgcomic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局图片加载器。
 *
 * EH 的 hath.network / ehgt.org 和 wnacg 的 qy0.ru 都只认自家 Referer，
 * 不带的直接 403 —— Coil 默认的请求没有 Referer，所以统一在这里加：
 *   · UA / Referer 按域名补
 *   · 256MB 磁盘缓存 + 25% 内存缓存（翻页回来不用重下）
 *   · respectCacheHeaders(false)：图床常用 ?verify=令牌 会过期，别让缓存被强制失效
 *   · crossfade 短一点（180ms），连续滚动时不闪
 */
class TgApp : Application(), ImageLoaderFactory {

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host.lowercase()
                val b = req.newBuilder().header("User-Agent", UA)
                val ref = when {
                    host.contains("qy0.ru") || host.contains("wnacg") -> "https://www.wnacg.com/"
                    host.contains("hath.network") || host.contains("ehgt.org") ||
                        host.contains("e-hentai") || host.contains("exhentai") -> "https://e-hentai.org/"
                    host.contains("telesco.pe") || host.contains("telegram-cdn") || host == "t.me" ->
                        "https://t.me/"
                    host.contains("telegra.ph") -> "https://telegra.ph/"
                    else -> null
                }
                if (ref != null) b.header("Referer", ref)
                chain.proceed(b.build())
            }
            .build()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .callFactory(okHttp)
        .respectCacheHeaders(false)
        .build()
}
