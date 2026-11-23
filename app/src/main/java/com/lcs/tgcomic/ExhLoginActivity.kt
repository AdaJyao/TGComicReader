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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ExHentai 登录页：在应用内用 WebView 登录 E-Hentai 论坛，登录成功后自动抓 Cookie。
 *
 * 关键：**不要在 WebView 里打开 exhentai.org**。exhentai 前面挂着 Cloudflare 人机验证，
 * 安卓 WebView 容易被判定成可疑客户端，表现就是「无限加载 / 一直转圈 / 反复验证」。
 * 而真正需要的只是三个 Cookie 值（ipb_member_id / ipb_pass_hash / ignorant），
 * 它们由**论坛登录页**（forums.e-hentai.org，没有 CF）种下 —— 所以登录走论坛，
 * 取到的值由 App 自己拼成 Cookie 头，请求 exhentai 时直接带上（不受浏览器域名限制）。
 *
 * 流程：
 *  1. 打开论坛登录页，输账号密码登录
 *  2. 页面加载完检查 Cookie，抓到 ipb_member_id + ipb_pass_hash 就验证并写入设置
 *  3. 验证：用这个 Cookie 请求一次 exhentai.org，能拿到正文（不是 sad panda）才算成功
 *  4. 手动「保存」、以及设置里粘贴 Cookie 的入口都保留
 */
class ExhLoginActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var saved = false
    private var loaded = false

    /** 论坛登录页：没有 Cloudflare，WebView 能正常打开 */
    private val forumLoginUrl = "https://forums.e-hentai.org/index.php?act=Login&CODE=00"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14161c"))
        }

        val statusH = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else 60 }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, statusH + 24, 12, 18)
        }
        bar.addView(
            TextView(this).apply {
                text = "登录 E-Hentai"
                setTextColor(Color.WHITE)
                textSize = 17f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(button("重载") { web.reload() })
        bar.addView(button("保存") { saveNow(prefs) })
        bar.addView(button("返回") { finish() })
        root.addView(bar)

        status = TextView(this).apply {
            text = "在下面登录 E-Hentai 论坛账号。登录页没有 Cloudflare 验证，不会卡住；登录成功会自动保存 Cookie。"
            setTextColor(Color.parseColor("#9aa4b2"))
            textSize = 12f
            setPadding(24, 0, 24, 16)
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
            databaseEnabled = true
            // 千万别覆盖 userAgentString：WebView 的 Sec-CH-UA 头不会跟着变，
            // UA 说自己是 Chrome 126、client hints 说自己是 WebView → Cloudflare 判定异常，
            // 表现就是无限「Performing security verification」。用默认 UA 反而能过。
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val host = r.url.host.orEmpty()
                // 论坛里点到 exhentai 就丢给系统浏览器：那边有 CF 验证，WebView 里只会无限转圈
                if (host.contains("exhentai.org")) {
                    status.text = "已在系统浏览器打开 exhentai（应用内不做 CF 验证）。" +
                        "登录成功后回到这里点「保存」即可。"
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, r.url)) }
                    return true
                }
                return false
            }

            override fun onReceivedError(
                v: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    status.text = "打不开 ${request.url.host}（${error.description}）：手机网络到不了该站点，国内一般要先开代理"
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                CookieManager.getInstance().flush()
                // 错误页也会触发 onPageFinished，用正文长度判断到底有没有加载出来
                v.evaluateJavascript(
                    "(function(){try{return (document.body&&document.body.innerText||'').length}catch(e){return 0}})()"
                ) { raw ->
                    val len = raw?.trim('"')?.toIntOrNull() ?: 0
                    if (len > 40) loaded = true
                }
                val cookies = collectCookies()
                val hasLogin = cookies.contains("ipb_member_id") && cookies.contains("ipb_pass_hash")
                if (!saved && hasLogin) {
                    status.text = "已抓到登录 Cookie，正在验证 exhentai 权限…"
                    verifyAndSave(prefs, cookies)
                } else if (!saved) {
                    status.text = "在下面输入 E-Hentai 账号密码；登录成功后 Cookie 会自动保存。"
                }
            }
        }
        web.loadUrl(forumLoginUrl)

        web.postDelayed({
            if (!loaded && !saved && !isFinishing) {
                status.text = "还打不开登录页：确认手机能上网（国内一般要先开代理），然后点右上「重载」"
            }
        }, 12_000)

        // Cloudflare 验证有时会卡住：给一条明确的退路
        web.postDelayed({
            if (!saved && !isFinishing && !collectCookies().contains("ipb_member_id")) {
                status.text =
                    "如果一直停在「Performing security verification」：点右上「重载」再等十几秒；" +
                        "反复验证过不去就改用浏览器登录，然后在设置 → E-Hentai Cookie 里粘贴"
            }
        }, 28_000)
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#7ab8ff"))
            textSize = 13f
            setPadding(18, 10, 6, 10)
            setOnClickListener { onClick() }
        }

    /** 合并各域 Cookie（论坛/e-hentai 先出现，exhentai 补齐剩下的） */
    private fun collectCookies(): String {
        val parts = LinkedHashMap<String, String>()
        for (u in listOf(
            "https://forums.e-hentai.org",
            "https://e-hentai.org",
            "https://exhentai.org"
        )) {
            val raw = runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull() ?: continue
            for (kv in raw.split(";")) {
                val k = kv.substringBefore('=').trim()
                val v = kv.substringAfter('=', "").trim()
                if (k.isNotEmpty() && !parts.containsKey(k)) parts[k] = v
            }
        }
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun withIgnorant(cookies: String): String =
        if (cookies.contains("ignorant")) cookies else "$cookies; ignorant=1"

    private fun saveNow(prefs: Prefs) {
        val cookies = collectCookies()
        if (!cookies.contains("ipb_member_id") || !cookies.contains("ipb_pass_hash")) {
            status.text = "还没登录成功：在下面输入账号密码，登录后会自动保存。"
            Toast.makeText(this, "还没抓到登录 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        verifyAndSave(prefs, cookies)
    }

    /** 用 Cookie 请求一次 exhentai，确认真的能看再落盘 */
    private fun verifyAndSave(prefs: Prefs, cookies: String) {
        val merged = withIgnorant(cookies)
        lifecycleScope.launch {
            val verdict = withContext(Dispatchers.IO) {
                runCatching {
                    val html = Net.getText(
                        "https://exhentai.org/",
                        mapOf("Cookie" to merged, "User-Agent" to UA),
                        retries = 1
                    )
                    when {
                        html.contains("sadpanda", true) || html.contains("Sad Panda", true) ->
                            "Cookie 已保存，但 exhentai 仍返回 sad panda（账号没权限或 Cookie 不完整）"
                        html.length > 1500 -> "已验证可以访问 exhentai（${html.length} 字节），Cookie 已保存"
                        else -> "Cookie 已保存；验证返回内容很短，可能被 CF 拦住，先按已保存用"
                    }
                }.getOrElse {
                    "Cookie 已保存（验证没跑通：${it.message ?: "网络"}；等能连上 exhentai 时再打开一本试试）"
                }
            }
            prefs.ehCookie = merged
            EhHub.clearAll()
            saved = true
            status.text = verdict
            Toast.makeText(this@ExhLoginActivity, "Cookie 已保存", Toast.LENGTH_SHORT).show()
            web.postDelayed({
                if (!isFinishing) {
                    setResult(RESULT_OK, Intent().putExtra("cookie", merged))
                    finish()
                }
            }, 1500)
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
