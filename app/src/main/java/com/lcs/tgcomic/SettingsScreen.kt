package com.lcs.tgcomic

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：一级「分组列表」→ 二级「具体设置」。
 *
 * 设计原则：**每一行都要能改**。
 *  - 开关类用真正的 Switch（不再靠「点一下整行取反」，看不出当前状态）
 *  - 多选一用弹窗单选（阅读方向 / 底色 / 排序 / 列数），而不是循环切换
 *  - 纯信息项（版本、支持来源）点开看详情，并给出下一步动作
 *  - 缓存从「只显示 N 页」变成「缓存管理」子页，可逐册清理
 */
private enum class SPage(val title: String) {
    ROOT("设置"),
    LOOK("外观"),
    READ("阅读"),
    SHELF("书架"),
    SOURCE("来源"),
    DATA("数据"),
    CACHE("缓存管理"),
    RECORDS("链接记录"),
    UPDATE("更新"),
    ABOUT("关于")
}

/** 需要弹窗时才用的状态 */
private sealed class SDialog {
    data object ReaderModePick : SDialog()
    data object ReaderBgPick : SDialog()
    data object SortPick : SDialog()
    data object ColumnPick : SDialog()
    data object PrefetchPick : SDialog()
    data object WebDav : SDialog()
    data class Info(val title: String, val body: String) : SDialog()
}

@Composable
fun SettingsScreen(
    lib: Library,
    prefs: Prefs,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    columns: Int,
    onColumns: (Int) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    favFirst: Boolean,
    onFavFirst: (Boolean) -> Unit,
    onClearAllCache: () -> Unit,
    onClearOneCache: (String) -> Unit,
    onClearLinks: () -> Unit,
    onRetryLink: (String) -> Unit,
    onRemoveLink: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onExport: (ExportKind, Set<String>?) -> Unit,
    onImportBackup: () -> Unit,
    ehCookieSet: Boolean,
    onLoginEh: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf(SPage.ROOT) }
    var dialog by remember { mutableStateOf<SDialog?>(null) }

    // 局部可编辑状态（改完立刻落 Prefs，界面同时刷新）
    var readerMode by remember { mutableStateOf(prefs.readerMode) }
    var readerBg by remember { mutableStateOf(prefs.readerBg) }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var volumeKeys by remember { mutableStateOf(prefs.volumeKeys) }
    var prefetch by remember { mutableStateOf(prefs.prefetchAhead) }
    var slowEh by remember { mutableStateOf(prefs.ehSlow) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    var davReady by remember { mutableStateOf(prefs.webdavReady) }
    var davBusy by remember { mutableStateOf("") }
    var organizing by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = page != SPage.ROOT) { page = SPage.ROOT }

    val cachedTotal = lib.comics.sumOf { lib.cachedPageCount(it.id) }
    val totalPages = lib.comics.sumOf { it.pages.size }
    @Suppress("UNUSED_VARIABLE") val tick = lib.tick.value

    // 弹窗必须和内容叠在同一个 Box 里：否则会被父布局当成第二行，排到屏幕外
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (page == SPage.ROOT) {
            SettingsRoot(
                theme = theme,
                readerMode = readerMode,
                readerBg = readerBg,
                prefetch = prefetch,
                grid = grid,
                columns = columns,
                sort = sort,
                favFirst = favFirst,
                ehCookieSet = ehCookieSet,
                slowEh = slowEh,
                books = lib.comics.size,
                cachedTotal = cachedTotal,
                autoUpdate = autoUpdate,
                onOpen = { page = it }
            )
        } else {
            SettingsPageHeader(page.title) { page = SPage.ROOT }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)
            ) {
                when (page) {
                    SPage.LOOK -> {
                        item { SectionHeader("主题") }
                        items(AppTheme.entries.chunked(4)) { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { t ->
                                    ThemeSwatch(t, selected = theme == t) { onTheme(t) }
                                }
                            }
                        }
                        item {
                            SettingRow(
                                "主题说明",
                                subtitle = "跟随系统：按系统的深/浅色自动切换。其余都是固定配色（6 种深色 + 7 种浅色）。",
                                onClick = null
                            )
                        }
                    }

                    SPage.READ -> {
                        item { SectionHeader("翻页与排版") }
                        item {
                            SettingRow(
                                "阅读方向",
                                value = readerMode.label,
                                subtitle = "连续滚动：一页接一页往下；左右翻页：一屏一页",
                                onClick = { dialog = SDialog.ReaderModePick }
                            )
                        }
                        item {
                            SettingRow(
                                "阅读底色",
                                value = readerBg.label,
                                subtitle = "纯黑省电、纸白接近纸质、护眼偏绿",
                                onClick = { dialog = SDialog.ReaderBgPick }
                            )
                        }
                        item {
                            SettingRow(
                                "预加载页数",
                                value = if (prefetch <= 0) "关闭" else "$prefetch 页",
                                subtitle = "提前把后面几页的直链/图片准备好，翻页更顺；调大更吃流量",
                                onClick = { dialog = SDialog.PrefetchPick }
                            )
                        }
                        item { SectionHeader("操作") }
                        item {
                            SwitchRow(
                                "屏幕常亮",
                                "阅读时不自动锁屏",
                                keepOn
                            ) { v ->
                                keepOn = v
                                prefs.keepScreenOn = v
                            }
                        }
                        item {
                            SwitchRow(
                                "音量键翻页",
                                "阅读时音量+上一页、音量-下一页（不用点屏幕两端）",
                                volumeKeys
                            ) { v ->
                                volumeKeys = v
                                prefs.volumeKeys = v
                            }
                        }
                    }

                    SPage.SHELF -> {
                        item { SectionHeader("排版") }
                        item {
                            SwitchRow(
                                "网格视图",
                                "关闭后书架用单列列表（书名更长、封面更大）",
                                grid
                            ) { onGrid(it) }
                        }
                        item {
                            SettingRow(
                                "网格列数",
                                value = "$columns 列",
                                subtitle = "网格模式下每行放几册",
                                onClick = { dialog = SDialog.ColumnPick }
                            )
                        }
                        item { SectionHeader("排序") }
                        item {
                            SettingRow(
                                "排序方式",
                                value = sort.label,
                                subtitle = "最近阅读 / 最近添加 / 标题 / 页数",
                                onClick = { dialog = SDialog.SortPick }
                            )
                        }
                        item {
                            SwitchRow(
                                "收藏优先",
                                "收藏的册子永远排在最前面",
                                favFirst
                            ) { onFavFirst(it) }
                        }
                    }

                    SPage.SOURCE -> {
                        item { SectionHeader("E-Hentai / ExHentai") }
                        item {
                            SettingRow(
                                "登录（自动保存 Cookie）",
                                value = if (ehCookieSet) "已登录" else "未登录",
                                subtitle = "应用内登录 E-Hentai 论坛，成功后自动写入 Cookie",
                                onClick = onLoginEh
                            )
                        }
                        item {
                            SettingRow(
                                "手动粘贴 Cookie",
                                value = if (ehCookieSet) "已设置" else "未设置",
                                subtitle = "ipb_member_id=…; ipb_pass_hash=…; ignorant=1",
                                onClick = { dialog = SDialog.Info("手动 Cookie", "已登录就不用管这里。\n\n需要手动填时：设置 → 来源 → 登录，或者用浏览器登录后在「手动粘贴 Cookie」里贴 ipb_member_id / ipb_pass_hash / ignorant 三个值。") }
                            )
                        }
                        item {
                            SwitchRow(
                                "稳妥模式",
                                "EH 请求间隔翻倍，慢一点但不容易被限速/封 IP",
                                slowEh
                            ) { v ->
                                slowEh = v
                                prefs.ehSlow = v
                                EhApi.setSlowMode(v)
                            }
                        }
                        item { SectionHeader("支持的链接") }
                        item {
                            SettingRow(
                                "查看支持的类型",
                                subtitle = "telegra.ph · t.me · e-hentai/exhentai · 紳士漫畫 wnacg · 图片直链",
                                onClick = {
                                    dialog = SDialog.Info(
                                        "支持的链接",
                                        "· telegra.ph 图文页\n" +
                                            "· t.me 消息 / 频道 / t.me/s/ 预览\n" +
                                            "· e-hentai.org、exhentai.org 画廊、图片页、搜索页\n" +
                                            "· 紳士漫畫 wnacg 相册页、看图页、清单页\n" +
                                            "· 任意图片直链（jpg/png/webp/gif）\n\n" +
                                            "把整段分享文字粘进来也行，会自动挑出里面的链接。"
                                    )
                                }
                            )
                        }
                    }

                    SPage.DATA -> {
                        item {
                            StatsCard(
                                books = lib.comics.size,
                                pages = totalPages,
                                links = lib.links.size,
                                cached = cachedTotal
                            )
                        }
                        item { SectionHeader("整理") }
                        item {
                            SettingRow(
                                "整理书架",
                                value = if (organizing) "整理中…" else "${lib.comics.size} 册",
                                subtitle = "同一系列的多个话并成一册；重复收录的只留页数最多的那本",
                                onClick = {
                                    if (!organizing) {
                                        organizing = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { runCatching { lib.organize() } }
                                            organizing = false
                                            r.fold(
                                                onSuccess = { rep ->
                                                    dialog = SDialog.Info(
                                                        "整理完成",
                                                        "整理前 ${rep.before} 册 → 现在 ${rep.after} 册" + "\n" +
                                                            "合并同系列 ${rep.seriesMerged} 册，清理重复 ${rep.dupsRemoved} 册"
                                                    )
                                                },
                                                onFailure = { e ->
                                                    dialog = SDialog.Info("整理失败", e.message ?: "未知错误")
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        item { SectionHeader("备份") }
                        item {
                            SettingRow(
                                "导出书架 JSON",
                                subtitle = "存到 下载/TGComic，可用来换机或恢复",
                                onClick = { onExport(ExportKind.JSON, null) }
                            )
                        }
                        item {
                            SettingRow(
                                "导出书架 TXT",
                                subtitle = "纯文本清单，方便贴到别处",
                                onClick = { onExport(ExportKind.TXT, null) }
                            )
                        }
                        item {
                            SettingRow(
                                "导出链接列表",
                                subtitle = "只要来源链接，一行一条，方便贴到别处",
                                onClick = { onExport(ExportKind.URLS, null) }
                            )
                        }
                        item {
                            SettingRow(
                                "从本地备份恢复",
                                subtitle = "选一个 tgcomic-*.json，按来源链接去重合并",
                                onClick = onImportBackup
                            )
                        }
                        item { SectionHeader("WebDAV") }
                        item {
                            SettingRow(
                                "服务器设置",
                                value = if (davReady) "已配置" else "未配置",
                                subtitle = "坚果云 / Nextcloud 等；地址填目录，文件固定为 ${WebDav.FILE}",
                                onClick = { dialog = SDialog.WebDav }
                            )
                        }
                        item {
                            SettingRow(
                                "备份到 WebDAV",
                                value = if (davBusy == "up") "上传中…" else null,
                                subtitle = "把书架 JSON 传到服务器，换手机/重装能拉回来",
                                onClick = {
                                    if (!prefs.webdavReady) {
                                        dialog = SDialog.Info("还没配置", "先点上面的「服务器设置」填地址和账号。")
                                    } else if (davBusy.isEmpty()) {
                                        scope.launch {
                                            davBusy = "up"
                                            val r = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    WebDav.put(prefs.webdavUrl, prefs.webdavUser, prefs.webdavPass, lib.exportJson())
                                                }
                                            }
                                            davBusy = ""
                                            dialog = SDialog.Info(
                                                "WebDAV 备份",
                                                r.fold(
                                                    { url -> "已上传 ${lib.comics.size} 册 → $url" },
                                                    { e -> "上传失败：${e.message ?: "网络错误"}" }
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        item {
                            SettingRow(
                                "从 WebDAV 恢复",
                                value = if (davBusy == "down") "下载中…" else null,
                                subtitle = "拉回 ${WebDav.FILE}，按来源链接去重合并",
                                onClick = {
                                    if (!prefs.webdavReady) {
                                        dialog = SDialog.Info("还没配置", "先点上面的「服务器设置」填地址和账号。")
                                    } else if (davBusy.isEmpty()) {
                                        scope.launch {
                                            davBusy = "down"
                                            val r = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    WebDav.get(prefs.webdavUrl, prefs.webdavUser, prefs.webdavPass)
                                                }
                                            }
                                            davBusy = ""
                                            val err = r.exceptionOrNull()
                                            dialog = if (err != null) {
                                                SDialog.Info("WebDAV 恢复", "下载失败：${err.message ?: "网络错误"}")
                                            } else {
                                                val text = r.getOrDefault("")
                                                val result = withContext(Dispatchers.IO) {
                                                    lib.importJson(text)?.also { lib.save() }
                                                }
                                                val msg = if (result == null) {
                                                    "服务器上的文件不是有效的书架备份"
                                                } else {
                                                    val (added, merged) = result
                                                    when {
                                                        added == 0 && merged == 0 -> "服务器上的册子都已经在书架上了"
                                                        merged > 0 -> "已恢复：新增 $added 册，另有 $merged 册补全了缺页"
                                                        else -> "已恢复：新增 $added 册"
                                                    }
                                                }
                                                SDialog.Info("WebDAV 恢复", msg)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        item { SectionHeader("清理") }
                        item {
                            SettingRow(
                                "缓存管理",
                                value = "$cachedTotal 页",
                                subtitle = "查看/清理每册的离线缓存",
                                onClick = { page = SPage.CACHE }
                            )
                        }
                        item {
                            SettingRow(
                                "链接记录",
                                value = "${lib.links.size} 条",
                                subtitle = "看导入过的链接，可重试失败项或删除记录",
                                onClick = { page = SPage.RECORDS }
                            )
                        }
                        item {
                            SettingRow(
                                "重抓来源更新",
                                subtitle = "按每册的来源链接重抓一遍，把新页并进来",
                                onClick = onCheckUpdates
                            )
                        }
                        item { SettingRow("清空全部缓存", danger = true, onClick = onClearAllCache) }
                        item { SettingRow("清空链接记录", danger = true, onClick = onClearLinks) }
                    }

                    SPage.CACHE -> {
                        item {
                            SettingRow(
                                "全部清空",
                                subtitle = "$cachedTotal 页 · ${lib.comics.count { lib.cachedPageCount(it.id) > 0 }} 册有缓存",
                                danger = true,
                                onClick = onClearAllCache
                            )
                        }
                        item { SectionHeader("按册管理") }
                        items(lib.comics, key = { it.id }) { c ->
                            val have = lib.cachedPageCount(c.id)
                            SettingRow(
                                title = c.title,
                                value = if (have > 0) "$have / ${c.pages.size} 页" else "未缓存",
                                subtitle = if (have > 0) "点右侧图标清除这一册的缓存" else null
                            )
                        }
                    }

                    SPage.RECORDS -> {
                        if (lib.links.isEmpty()) {
                            item {
                                SettingRow(
                                    "还没有记录",
                                    subtitle = "导入过的链接会出现在这里，失败的可重试"
                                )
                            }
                        } else {
                            item {
                                SettingRow(
                                    "全部清空",
                                    value = "${lib.links.size} 条",
                                    danger = true,
                                    onClick = onClearLinks
                                )
                            }
                            items(lib.links, key = { it.url + it.at }) { l ->
                                SettingRow(
                                    title = l.title.ifBlank { l.url },
                                    value = when (l.status) {
                                        "ok" -> "已导入"
                                        "merged" -> "已合并"
                                        "dup" -> "重复"
                                        "fail" -> "失败"
                                        else -> l.status
                                    },
                                    subtitle = (l.detail.ifBlank { l.url }).take(120),
                                    onClick = { onRetryLink(l.url) }
                                )
                            }
                        }
                    }

                    SPage.UPDATE -> {
                        item { SectionHeader("软件更新") }
                        item {
                            SettingRow(
                                "检查更新",
                                value = "当前 v${BuildConfig.VERSION_NAME}",
                                subtitle = "从 GitHub Releases 查最新版",
                                onClick = onCheckAppUpdate
                            )
                        }
                        item {
                            SwitchRow(
                                "自动检查并下载",
                                "启动时静默检查（12 小时一次），有新版自动下载，安装仍需你确认",
                                autoUpdate
                            ) { v ->
                                autoUpdate = v
                                prefs.autoUpdate = v
                            }
                        }
                    }

                    SPage.ABOUT -> {
                        item { SectionHeader("应用") }
                        item {
                            SettingRow(
                                "版本",
                                value = "v${BuildConfig.VERSION_NAME}",
                                subtitle = "versionCode ${BuildConfig.VERSION_CODE}",
                                onClick = {
                                    dialog = SDialog.Info(
                                        "关于",
                                        "TG漫画 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n\n" +
                                            "把 telegra.ph / t.me / E-Hentai / 紳士漫畫 的链接变成可离线看的漫画书架。"
                                    )
                                }
                            )
                        }
                        item {
                            SettingRow(
                                "开源地址",
                                value = "GitHub",
                                subtitle = "github.com/AdaJyao/TGComicReader",
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://github.com/AdaJyao/TGComicReader")
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            )
                        }
                        item {
                            SettingRow(
                                "遇到问题",
                                subtitle = "在 GitHub Issues 里带上版本号和链接，附截图会更快",
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://github.com/AdaJyao/TGComicReader/issues")
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            )
                        }
                    }

                    SPage.ROOT -> Unit
                }
            }
        }
    }

    // ---------------- 弹窗 ----------------
    when (val d = dialog) {
        SDialog.ReaderModePick -> ChoiceDialog(
            title = "阅读方向",
            options = ReaderMode.entries.map { it.label to it.name },
            current = readerMode.name,
            onPick = { name ->
                val m = ReaderMode.valueOf(name)
                readerMode = m
                prefs.readerMode = m
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SDialog.ReaderBgPick -> ChoiceDialog(
            title = "阅读底色",
            options = ReaderBg.entries.map { it.label to it.name },
            current = readerBg.name,
            onPick = { name ->
                val b = ReaderBg.valueOf(name)
                readerBg = b
                prefs.readerBg = b
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SDialog.SortPick -> ChoiceDialog(
            title = "排序方式",
            options = listOf(
                "最近阅读" to SortMode.RECENT.name,
                "最近添加" to SortMode.ADDED.name,
                "标题" to SortMode.TITLE.name,
                "页数" to SortMode.PAGES.name
            ),
            current = sort.name,
            onPick = { name ->
                onSort(SortMode.valueOf(name))
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SDialog.ColumnPick -> ChoiceDialog(
            title = "网格列数",
            options = listOf("2 列" to "2", "3 列" to "3", "4 列" to "4", "5 列" to "5"),
            current = columns.toString(),
            onPick = { v ->
                onColumns(v.toInt())
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SDialog.PrefetchPick -> ChoiceDialog(
            title = "预加载页数",
            options = listOf("关闭" to "0", "2 页" to "2", "4 页" to "4", "6 页" to "6"),
            current = prefetch.toString(),
            onPick = { v ->
                prefetch = v.toInt()
                prefs.prefetchAhead = prefetch
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SDialog.WebDav -> WebDavDialog(
            url = prefs.webdavUrl,
            user = prefs.webdavUser,
            pass = prefs.webdavPass,
            onDismiss = { dialog = null },
            onConfirm = { u, n, w ->
                prefs.webdavUrl = u
                prefs.webdavUser = n
                prefs.webdavPass = w
                davReady = prefs.webdavReady
                dialog = null
            }
        )

        is SDialog.Info -> GlassDialog(onDismiss = { dialog = null }) {
            Text(d.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
            Spacer(Modifier.height(8.dp))
            Text(d.body, fontSize = 12.sp, lineHeight = 18.sp, color = Glass.TextDim)
            Spacer(Modifier.height(14.dp))
            GlassPill("好", selected = true, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                dialog = null
            }
        }

        null -> Unit
    }
    }
}

// ══════════════ 一级：分组列表 ══════════════

@Composable
private fun SettingsRoot(
    theme: AppTheme,
    readerMode: ReaderMode,
    readerBg: ReaderBg,
    prefetch: Int,
    grid: Boolean,
    columns: Int,
    sort: SortMode,
    favFirst: Boolean,
    ehCookieSet: Boolean,
    slowEh: Boolean,
    books: Int,
    cachedTotal: Int,
    autoUpdate: Boolean,
    onOpen: (SPage) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        item { SectionHeader("个性化") }
        item {
            NavRow("外观", theme.label) { onOpen(SPage.LOOK) }
        }
        item {
            NavRow(
                "阅读",
                "${readerMode.label} · ${readerBg.label} · 预加载 ${if (prefetch <= 0) "关" else "$prefetch 页"}"
            ) { onOpen(SPage.READ) }
        }
        item {
            NavRow(
                "书架",
                "${if (grid) "$columns 列网格" else "列表"} · ${sort.label}${if (favFirst) " · 收藏优先" else ""}"
            ) { onOpen(SPage.SHELF) }
        }

        item { SectionHeader("来源与数据") }
        item {
            NavRow(
                "来源",
                "E-Hentai ${if (ehCookieSet) "已登录" else "未登录"} · 稳妥模式${if (slowEh) "开" else "关"}"
            ) { onOpen(SPage.SOURCE) }
        }
        item {
            NavRow("数据与备份", "$books 册 · 缓存 $cachedTotal 页") { onOpen(SPage.DATA) }
        }
        item {
            NavRow("缓存管理", if (cachedTotal > 0) "$cachedTotal 页可清理" else "暂无缓存") { onOpen(SPage.CACHE) }
        }

        item { SectionHeader("其它") }
        item {
            NavRow("更新", "v${BuildConfig.VERSION_NAME} · 自动更新${if (autoUpdate) "开" else "关"}") {
                onOpen(SPage.UPDATE)
            }
        }
        item {
            NavRow("关于", "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") { onOpen(SPage.ABOUT) }
        }
    }
}

// ══════════════ 小组件 ══════════════

@Composable
private fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(Glyph.BACK, onClick = onBack, size = 38.dp, tint = Glass.Text)
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Glass.Text
        )
    }
}

/** 一级分组行：标题 + 当前状态摘要 + 右箭头 */
@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Glass.Text)
            Spacer(Modifier.height(3.dp))
            Text(
                summary,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = Glass.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        // 用一个小三角当「进入」指示，避免再加图标资源
        Text("›", fontSize = 20.sp, color = Glass.TextTertiary)
    }
}

/** 开关行：右侧是真正的 Switch，状态一眼可见 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onChecked(!checked) }
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Glass.Text)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 10.5.sp, lineHeight = 14.sp, color = Glass.TextTertiary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF06222B),
                checkedTrackColor = Glass.Accent,
                uncheckedThumbColor = Glass.TextDim,
                uncheckedTrackColor = Glass.palette.inputField,
                uncheckedBorderColor = Glass.palette.outline
            )
        )
    }
}

/** 单选弹窗：选项一眼看全，点一下即生效 */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    GlassDialog(onDismiss) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, key) ->
                val on = key == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) Glass.Accent.copy(alpha = 0.14f) else Glass.palette.inputField)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onPick(key) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = if (on) Glass.Accent else Glass.Text,
                        modifier = Modifier.weight(1f)
                    )
                    if (on) Text("✓", fontSize = 13.sp, color = Glass.Accent)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassPill("取消", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onDismiss)
    }
}

/** 数据页顶部统计卡（纯信息，但样式上明确是「概览」而不是可点项） */
@Composable
private fun StatsCard(books: Int, pages: Int, links: Int, cached: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(Glass.R18)
            .background(Glass.palette.surfaceVariant)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatCell("册数", "$books")
        StatCell("总页数", "$pages")
        StatCell("链接", "$links")
        StatCell("已缓存", "$cached 页")
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = Glass.TextTertiary)
    }
}
