package com.lcs.tgcomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * 禁漫天堂的图片是「按像素竖向切成 N 条、上下颠倒后重新拼」发出来的，
 * 站点自己的阅读器靠 JS 在浏览器里还原；客户端直接显示原图就是一条条错位的横带
 * （看着很像「页码乱序」，其实是每一页自己被切乱了）。
 *
 * 切几条由站点下发的 scramble_id 和作品 id 决定：
 *   · aid < scramble_id              → 不打乱
 *   · aid < 268850                   → 固定 10 条（旧图库）
 *   · 268850 ≤ aid < 421926          → md5("aid+文件名").末位 % 10
 *   · aid ≥ 421926                   → md5("aid+文件名").末位 % 8
 *   条数 = 该值 × 2 + 2（2 / 4 / … / 18）
 *
 * 「文件名」用不带扩展名的序号（如 00001），这是站点阅读器脚本的取法。
 * 公式已用真实页面逐条核对：aid 250221、250729（<268850，固定 10 条）、
 * 1458184 的 00002（算出 2 条）和 00003（算出 10 条）还原后画面都连续正常。
 */
object JmScramble {

    /** 站点目前所有相册/章节页都是 220980 */
    const val DEFAULT_SID = 220980L
    private const val SCRAMBLE_268850 = 268850L
    private const val SCRAMBLE_421926 = 421926L

    private val CDN_PHOTO = Regex(
        """^https?://[^/]*?18comic\.[a-z]+/media/photos/(\d+)/([^/?#]+)""",
        RegexOption.IGNORE_CASE
    )

    data class Info(val aid: Long, val name: String, val sid: Long = DEFAULT_SID)

    /** 认得出是禁漫的图就返回还原参数，其它来源返回 null */
    fun parse(url: String): Info? {
        val m = CDN_PHOTO.find(url.trim()) ?: return null
        val aid = m.groupValues[1].toLongOrNull() ?: return null
        val name = m.groupValues[2].substringBefore('.').ifBlank { return null }
        return Info(aid, name)
    }

    /** 切几条；0 表示这张图没被打乱 */
    fun strips(info: Info): Int {
        if (info.aid < info.sid) return 0
        if (info.aid < SCRAMBLE_268850) return 10
        val mod = if (info.aid < SCRAMBLE_421926) 10 else 8
        val hex = md5Hex("${info.aid}${info.name}")
        return (hex.last().code % mod) * 2 + 2
    }

    fun needsDecode(info: Info): Boolean = strips(info) >= 2

    /**
     * 还原图片字节。不需要还原 / 解码失败时原样返回（返回同一个数组引用，
     * 调用方据此判断有没有改动过）。
     */
    fun decode(bytes: ByteArray, info: Info): ByteArray {
        val num = strips(info)
        if (num < 2) return bytes
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return bytes
        val out = reorder(bmp, num)
        bmp.recycle()
        val bo = ByteArrayOutputStream(out.width * out.height / 2 + 4096)
        out.compress(Bitmap.CompressFormat.JPEG, 95, bo)
        out.recycle()
        return bo.toByteArray()
    }

    /** 本地缓存里这一张是否已经还原过（旁边有 .jm 标记） */
    fun isDecoded(file: File): Boolean = markerOf(file).exists()

    /**
     * 就地还原缓存文件。
     * 老版本缓存下来的是打乱的原图，这里顺手修好并打标记，避免被二次还原切坏。
     */
    fun decodeInPlace(file: File, info: Info) {
        if (!needsDecode(info) || isDecoded(file)) return
        runCatching {
            if (!file.exists() || file.length() < 1024) return
            val bytes = file.readBytes()
            val fixed = decode(bytes, info)
            if (fixed !== bytes) file.writeBytes(fixed)
            markerOf(file).writeText("${info.aid}/${info.name}/${strips(info)}")
        }
    }

    private fun markerOf(file: File) = File(file.parentFile, file.name + ".jm")

    /** 按官方阅读器的算法把竖条倒序拼回来（返回新位图，原图不动） */
    internal fun reorder(src: Bitmap, num: Int): Bitmap {
        val w = src.width
        val h = src.height
        val move = h / num
        val over = h % num
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until num) {
            val m = move + if (i == 0) over else 0
            val ySrc = h - move * (i + 1) - over
            val yDst = move * i + if (i == 0) 0 else over
            if (ySrc < 0 || yDst >= h) continue
            canvas.drawBitmap(
                src,
                Rect(0, ySrc, w, min(h, ySrc + m)),
                Rect(0, yDst, w, min(h, yDst + m)),
                paint
            )
        }
        return out
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
