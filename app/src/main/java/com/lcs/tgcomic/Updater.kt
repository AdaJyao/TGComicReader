package com.lcs.tgcomic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置更新：查 GitHub Releases（AdaJyao/TGComicReader）→ 下载 APK → 调系统安装器。
 *
 * 说明：Android 不允许应用静默安装 APK（除非是设备管理员/有 root）。
 * 所以「自动更新」的边界是：**自动检查 + 自动下载**，最后一步装由系统弹窗确认（点一下）。
 */
object Updater {

    private const val REPO = "AdaJyao/TGComicReader"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"

    data class Info(
        val version: String,   // 2.17
        val tag: String,       // v2.17
        val notes: String,
        val apkUrl: String
    )

    /** 版本号比较：只看数字段，1.10 > 1.9 */
    fun isNewer(remote: String, current: String): Boolean {
        val a = remote.trim().trimStart('v', 'V').split('.', '-', '+')
        val b = current.trim().trimStart('v', 'V').split('.', '-', '+')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** 查最新版本；没有新版本返回 null。API 被墙时退回到 releases/latest 的重定向 */
    suspend fun check(current: String): Info? = withContext(Dispatchers.IO) {
        runCatching { fromApi(current) }.getOrNull() ?: runCatching { fromRedirect(current) }.getOrNull()
    }

    private fun fromApi(current: String): Info? {
        val body = httpText(API_LATEST) ?: return null
        val o = JSONObject(body)
        val tag = o.optString("tag_name").ifBlank { return null }
        val ver = tag.trimStart('v', 'V')
        if (!isNewer(ver, current)) return null
        val notes = o.optString("body").lineSequence().take(12).joinToString("\n").trim()
        var apk = ""
        o.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk", true) && url.isNotBlank()) {
                    apk = url
                    break
                }
            }
        }
        if (apk.isBlank()) apk = downloadUrl(ver)
        return Info(ver, tag, notes, apk)
    }

    private fun fromRedirect(current: String): Info? {
        val conn = URL(LATEST_PAGE).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", UA)
            val loc = conn.getHeaderField("Location") ?: return null
            val tag = loc.substringAfterLast("/tag/", "").ifBlank { return null }
            val ver = tag.trimStart('v', 'V')
            if (!isNewer(ver, current)) return null
            return Info(ver, tag, "", downloadUrl(ver))
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadUrl(ver: String) =
        "https://github.com/$REPO/releases/download/v$ver/TGComicReader-$ver.apk"

    private fun httpText(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 下载到 cacheDir，返回文件；onProgress(已下字节, 总字节) */
    suspend fun download(
        ctx: Context,
        info: Info,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val dest = File(dir, "tgcomic-${info.version}.apk")
            val part = File(dir, "tgcomic-${info.version}.part")
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", UA)
                if (conn.responseCode !in 200..299) return@runCatching null
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (part.length() < 1024 * 512) return@runCatching null // 明显不是 APK
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                dest
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 是否已经允许安装未知来源应用（Android 8+） */
    fun canInstall(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 26)
            ctx.packageManager.canRequestPackageInstalls()
        else true

    fun openInstallPermission(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching {
                ctx.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 调系统安装器 */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)
}
