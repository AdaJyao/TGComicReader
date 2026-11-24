package com.lcs.tgcomic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端，只做两件事：上传/下载一个书架备份 JSON。
 *
 * 地址填**目录**（如坚果云 `https://dav.jianguoyun.com/dav/我的坚果云`），
 * 文件固定叫 tgcomic-backup.json；也允许直接填到 `.json` 结尾的完整文件地址。
 *
 * 不上第三方库：Jcifs/ Sardine 都太重，PUT/GET + Basic 认证几十行就够。
 */
object WebDav {

    const val FILE = "tgcomic-backup.json"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun endpoint(url: String): String {
        val base = url.trim().trimEnd('/')
        return if (base.endsWith(".json", true)) base else "$base/$FILE"
    }

    private fun dirOf(ep: String): String = ep.substringBeforeLast('/')

    /** 上传；返回实际写入的完整地址 */
    suspend fun put(url: String, user: String, pass: String, body: String): String =
        withContext(Dispatchers.IO) {
            val ep = endpoint(url)
            val auth = Credentials.basic(user.ifBlank { "" }, pass.ifBlank { "" })
            // 目录不存在先建一个：坚果云/Nextcloud 对已存在目录会返回 405/301，忽略即可
            runCatching {
                val mk = Request.Builder()
                    .url(dirOf(ep))
                    .method("MKCOL", "".toRequestBody(null))
                    .header("Authorization", auth)
                    .build()
                client.newCall(mk).execute().close()
            }
            val req = Request.Builder()
                .url(ep)
                .put(body.toRequestBody(jsonType))
                .header("Authorization", auth)
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    throw IOException(
                        when (r.code) {
                            401, 403 -> "认证失败（HTTP ${r.code}）：账号密码不对，或服务端没开 WebDAV"
                            404 -> "地址不存在（HTTP 404）：确认目录地址填对了"
                            507 -> "服务器空间不足（HTTP 507）"
                            else -> "上传失败 HTTP ${r.code}"
                        }
                    )
                }
            }
            ep
        }

    /** 下载；文件不存在时给明确提示 */
    suspend fun get(url: String, user: String, pass: String): String =
        withContext(Dispatchers.IO) {
            val ep = endpoint(url)
            val req = Request.Builder()
                .url(ep)
                .get()
                .header("Authorization", Credentials.basic(user.ifBlank { "" }, pass.ifBlank { "" }))
                .build()
            client.newCall(req).execute().use { r ->
                if (r.code == 404) throw IOException("服务器上没有 $FILE，先在另一台设备上备份一次")
                if (!r.isSuccessful) throw IOException("下载失败 HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }
}
