package com.lcs.tgcomic

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.widget.Toast

/**
 * ExHentai 登录页：在应用内用 WebView 登录，登录成功后自动抓 Cookie 存起来。
 *
 * 为什么需要它：exhentai.org 必须有登录 Cookie（ipb_member_id / ipb_pass_hash / ignorant），
 * 而让用户自己去浏览器 F12 抄一遍 Cookie 太反人类。
 *
 * 流程：
 *  1. 直接开 https://exhentai.org/ —— 没登录会看到「sad panda」
 *  2. 点「去登录」跳论坛登录页（forums.e-hentai.org），登录后 SSO 会把 Cookie 种到 e-hentai/exhentai
 *  3. 每次页面加载完检查 Cookie，抓到 ipb_member_id 就自动保存并提示
 *  4. 也可以随时点「保存 Cookie」手动存当前全部 Cookie
 */
class ExhLoginActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var saved = false
    private var loaded = false

    private val startUrl = "https://exhentai.org/"
    private val loginUrl = "https://forums.e-hentai.org/index.php?act=Login&CODE=00"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14161c"))
        }

        // 顶部：标题 + 状态 + 两个按钮
        val statusH = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else 60 }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, statusH + 28, 28, 24)
        }
        val title = TextView(this).apply {
            text = "ExHentai 登录"
            setTextColor(Color.WHITE)
            textSize = 17f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("去登录") { web.loadUrl(loginUrl) })
        bar.addView(button("保存") { saveAndFinish(prefs, auto = false) })
        bar.addView(button("返回") { finish() })
        root.addView(bar)

        status = TextView(this).apply {
            text = "在下方登录 E-Hentai 论坛（登录后 exhentai 才能看）。登录成功会自动保存。"
            setTextColor(Color.parseColor("#9aa4b2"))
            textSize = 12f
            setPadding(28, 0, 28, 18)
        }
        root.addView(status)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(FrameLayout(this).apply { addView(root) })

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = WnacgApi.DESKTOP_UA // 桌面版式页面对 WebView 更友好
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = false
            override fun onReceivedError(
                v: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    status.text = "打不开 ${request.url.host}（${error.description}）：手机网络到不了该站点，国内一般要先开代理，再点「去登录」"
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                CookieManager.getInstance().flush()
                // 错误页也会触发 onPageFinished，所以用正文长度判断到底有没有加载出来
                v.evaluateJavascript(
                    "(function(){try{return (document.body&&document.body.innerText||'').length}catch(e){return 0}})()"
                ) { raw ->
                    val len = raw?.trim('"')?.toIntOrNull() ?: 0
                    if (len > 40) loaded = true
                }
                val cookies = collectCookies()
                if (!saved && cookies.contains("ipb_member_id") && cookies.contains("ipb_pass_hash")) {
                    status.text = "已抓到登录 Cookie，正在保存…"
                    saveAndFinish(prefs, auto = true)
                } else if (cookies.contains("ipb_member_id")) {
                    status.text = "已检测到 ipb_member_id，可点右上「保存」"
                }
            }
        }
        web.loadUrl(startUrl)
        // 12 秒还没打开任何页面：基本就是网络到不了（国内要代理）
        web.postDelayed({
            if (!loaded && !saved && !isFinishing) {
                status.text = "还打不开 exhentai.org：确认手机能上网（国内一般要先开代理），再点「去登录」"
            }
        }, 12_000)
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#7ab8ff"))
            textSize = 13f
            setPadding(24, 8, 8, 8)
            setOnClickListener { onClick() }
        }

    /** 合并 exhentai / e-hentai 两个域的 Cookie（exhentai 优先） */
    private fun collectCookies(): String {
        val parts = LinkedHashMap<String, String>()
        for (u in listOf("https://exhentai.org", "https://e-hentai.org", "https://forums.e-hentai.org")) {
            val raw = runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull() ?: continue
            for (kv in raw.split(";")) {
                val k = kv.substringBefore('=').trim()
                val v = kv.substringAfter('=', "").trim()
                if (k.isNotEmpty() && !parts.containsKey(k)) parts[k] = v
            }
        }
        // EX 专属的几个值不能丢；EH 登录也会带 ignorant
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun saveAndFinish(prefs: Prefs, auto: Boolean) {
        val cookies = collectCookies()
        if (!cookies.contains("ipb_member_id")) {
            status.text = "还没登录：先点「去登录」用 E-Hentai 账号登录，登录成功再回来。"
            if (!auto) Toast.makeText(this, "还没抓到登录 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        val merged = buildString {
            append(cookies)
            if (!cookies.contains("ignorant")) append("; ignorant=1")
        }
        prefs.ehCookie = merged
        EhHub.clearAll()
        saved = true
        status.text = "已保存：${merged.length} 字符。回到书架重新打开这本书即可。"
        Toast.makeText(this, "Cookie 已保存", Toast.LENGTH_SHORT).show()
        if (auto) {
            web.postDelayed({
                if (!isFinishing) {
                    setResult(RESULT_OK, Intent().putExtra("cookie", merged))
                    finish()
                }
            }, 900)
        }
    }

    override fun onDestroy() {
        runCatching {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
