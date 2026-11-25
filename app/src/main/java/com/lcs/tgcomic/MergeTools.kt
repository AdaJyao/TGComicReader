package com.lcs.tgcomic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 书架页上的「整理 / 合并 / 链接记录」——
 * 这些是书架自己的事，不放设置里。
 */

private fun linkStatusLabel(s: String): String = when (s) {
    "ok" -> "已导入"
    "merged" -> "已合并"
    "dup" -> "重复"
    "fail" -> "失败"
    else -> s
}

/* ---------------- 整理（整库合并 + 清重，可预览、可撤销） ---------------- */

@Composable
fun OrganizeSheet(lib: Library, onClose: () -> Unit, onToast: (String) -> Unit) {
    var mergeSame by remember { mutableStateOf(true) }
    var removeDups by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<Library.OrganizeReport?>(null) }

    val plan = remember(mergeSame, removeDups, lib.tick.value) {
        lib.planOrganize(mergeSame, removeDups)
    }

    GlassDialog(onDismiss = onClose) {
        Text("整理书架", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(3.dp))
        Text("先在下面预览会怎么并，再决定要不要动手", fontSize = 11.sp, color = Glass.TextDim)

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ToggleRow("合并同一系列", "同名的多话按标题序号（第1话、第2话…）升序拼成一册", mergeSame) {
                mergeSame = it
                report = null
            }
            ToggleRow("清理重复册", "标题相同就算重复，只留页数最多的那册（页数相同留早的）", removeDups) {
                removeDups = it
                report = null
            }

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Glass.palette.inputField)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "预览：${plan.before} 册 → ${plan.after} 册",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.Accent
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "合并 ${plan.seriesGroups.size} 组同系列 · 清理 ${plan.duplicatePairs.size} 册重复",
                        fontSize = 10.5.sp,
                        color = Glass.TextDim
                    )
                    plan.seriesGroups.take(4).forEach { g ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "• ${g.title}：${g.chapters.size} 册 → ${g.totalPages} 页",
                            fontSize = 10.5.sp,
                            color = Glass.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "   按序：" + g.chapters.joinToString(" → ") { it.first },
                            fontSize = 9.5.sp,
                            color = Glass.TextTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    plan.duplicatePairs.take(3).forEach { (keep, drop) ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "◦ 去掉「${drop.title}」（保留 ${keep.pages.size} 页那本）",
                            fontSize = 10.sp,
                            color = Glass.TextTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (plan.seriesGroups.isEmpty() && plan.duplicatePairs.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("书架已经很干净，没有需要整理的内容", fontSize = 10.5.sp, color = Glass.TextTertiary)
                    }
                }
            }

            report?.let { r ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "已整理：${r.before} 册 → ${r.after} 册（合并 ${r.seriesMerged}、清重 ${r.dupsRemoved}）",
                    fontSize = 11.sp,
                    color = Glass.Good
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GlassPill("关闭", modifier = Modifier.weight(1f).height(42.dp), onClick = onClose)
            if (report == null) {
                GlassPill(
                    "开始整理",
                    selected = true,
                    enabled = plan.seriesGroups.isNotEmpty() || plan.duplicatePairs.isNotEmpty(),
                    modifier = Modifier.weight(1.3f).height(42.dp),
                    onClick = {
                        val r = runCatching { lib.organizeWith(mergeSame, removeDups) }
                        r.fold(
                            onSuccess = { rep ->
                                report = rep
                                onToast("已整理：${rep.before} 册 → ${rep.after} 册")
                            },
                            onFailure = { e -> onToast(e.message ?: "整理失败") }
                        )
                    }
                )
            } else {
                GlassPill(
                    "撤销这次整理",
                    modifier = Modifier.weight(1.3f).height(42.dp),
                    onClick = {
                        if (lib.undo()) {
                            report = null
                            onToast("已还原到整理前")
                        } else onToast("没有可撤销的操作")
                    }
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, color = Glass.Text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 10.sp, color = Glass.TextTertiary, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.size(width = 42.dp, height = 26.dp)
        )
    }
}

/* ---------------- 排序（点开就是列表，不再一下一下循环点） ---------------- */

private fun sortHint(m: SortMode): String = when (m) {
    SortMode.RECENT -> "读过的排前面，按最近一次翻页时间"
    SortMode.ADDED -> "按导入到书架的时间，新导入的在前"
    SortMode.TITLE -> "标题 A-Z，数字按大小比（第2话 在 第10话 前面）"
    SortMode.PAGES -> "页数多的在前，页数相同按标题"
}

@Composable
fun SortSheet(current: SortMode, onPick: (SortMode) -> Unit, onClose: () -> Unit) {
    GlassDialog(onDismiss = onClose) {
        Text("排序方式", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
        Spacer(Modifier.height(3.dp))
        Text("书架和收藏页共用这一套；收藏页固定按最近阅读", fontSize = 10.5.sp, color = Glass.TextDim)
        Spacer(Modifier.height(8.dp))
        SortMode.entries.forEach { m ->
            val on = m == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) Glass.Accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onPick(m) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        m.label,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Glass.Accent else Glass.Text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(sortHint(m), fontSize = 10.sp, color = Glass.TextTertiary, lineHeight = 14.sp)
                }
                if (on) {
                    Spacer(Modifier.width(8.dp))
                    Text("✓", fontSize = 13.sp, color = Glass.Accent, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        GlassPill("关闭", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onClose)
    }
}

/* ---------------- 手动合并（勾选几册 → 预览 → 合并 → 可撤销） ---------------- */

@Composable
fun ManualMergeSheet(lib: Library, ids: Set<String>, onClose: () -> Unit, onToast: (String) -> Unit) {
    val plan = remember(ids) { lib.previewMerge(ids) }
    if (plan == null) {
        GlassDialog(onDismiss = onClose) {
            Text("至少选两册", fontSize = 14.sp, color = Glass.Text)
            Spacer(Modifier.height(6.dp))
            Text("长按封面进入多选，勾两册以上再点「合并」", fontSize = 11.sp, color = Glass.TextDim)
            Spacer(Modifier.height(12.dp))
            GlassPill("知道了", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onClose)
        }
        return
    }

    var title by remember(ids) { mutableStateOf(plan.title) }
    var merged by remember(ids) { mutableStateOf<Comic?>(null) }

    GlassDialog(onDismiss = onClose) {
        val done = merged
        Text(
            if (done != null) "已合并成一册" else "合并 ${plan.chapters.size} 册",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Glass.Text
        )
        Spacer(Modifier.height(3.dp))
        Text(
            if (done != null) "不对的话可以一键还原，书架会回到合并前"
            else "按标题里的序号升序拼成章节（有页码区间的按起始页排），下面就是合并后的样子",
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = Glass.TextDim
        )

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 286.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                if (done != null) "${done.chapterMarks.size} 章 · ${done.pages.size} 页"
                else "${plan.chapters.size} 章 · ${plan.totalPages} 页",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Glass.Accent
            )
            Spacer(Modifier.height(6.dp))
            plan.chapters.forEachIndexed { i, (name, pages) ->
                val range = Titles.pageRange(name)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        "${i + 1}.",
                        fontSize = 10.5.sp,
                        color = Glass.TextTertiary,
                        modifier = Modifier.width(22.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            fontSize = 11.sp,
                            color = Glass.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (range != null) {
                            Spacer(Modifier.height(1.dp))
                            Text("第 ${range.first}-${range.last} 页", fontSize = 9.5.sp, color = Glass.TextTertiary)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("$pages 页", fontSize = 10.sp, color = Glass.TextDim)
                }
            }
        }

        if (done == null) {
            Spacer(Modifier.height(12.dp))
            Text("合并后的书名", fontSize = 10.5.sp, color = Glass.TextTertiary)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Glass.palette.inputField)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Glass.Text, fontSize = 12.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Glass.Accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (done == null) {
                GlassPill("取消", modifier = Modifier.weight(1f).height(42.dp), onClick = onClose)
                GlassPill(
                    "合并",
                    selected = true,
                    modifier = Modifier.weight(1.2f).height(42.dp),
                    onClick = {
                        val c = lib.mergeComics(ids, title)
                        if (c != null) {
                            merged = c
                            onToast("已合并成「${c.title}」：${c.chapterMarks.size} 章 · ${c.pages.size} 页")
                        } else onToast("合并失败")
                    }
                )
            } else {
                GlassPill(
                    "撤销这次合并",
                    accent = Glass.Warn,
                    modifier = Modifier.weight(1.3f).height(42.dp),
                    onClick = {
                        if (lib.undo()) {
                            onToast("已还原到合并前")
                            onClose()
                        } else onToast("没有可撤销的操作")
                    }
                )
                GlassPill(
                    "完成",
                    selected = true,
                    modifier = Modifier.weight(1f).height(42.dp),
                    onClick = onClose
                )
            }
        }
    }
}

/* ---------------- 链接记录（书架页入口，不藏设置里） ---------------- */

@Composable
fun LinksSheet(
    lib: Library,
    onImport: (List<String>) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit
) {
    val links = lib.links
    GlassDialog(onDismiss = onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("链接记录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Glass.Text)
                Text("${links.size} 条 · 书架 ${lib.comics.size} 册", fontSize = 10.5.sp, color = Glass.TextDim)
            }
            GlassPill(
                "全部导入",
                selected = true,
                enabled = links.isNotEmpty(),
                onClick = { onImport(links.map { it.url }) }
            )
        }

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (links.isEmpty()) {
                Text(
                    "还没有导入过的链接。粘贴过的每一条都会记在这里，书架清空后可以一键恢复。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Glass.TextTertiary
                )
            }
            links.forEach { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onRetry(l.url) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            l.title.ifBlank { l.url },
                            fontSize = 11.5.sp,
                            color = Glass.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            l.url,
                            fontSize = 9.sp,
                            color = Glass.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        linkStatusLabel(l.status),
                        fontSize = 9.5.sp,
                        color = when (l.status) {
                            "ok" -> Glass.Good
                            "fail" -> Glass.Warn
                            else -> Glass.TextDim
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "删除",
                        fontSize = 9.5.sp,
                        color = Glass.TextTertiary,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onRemove(l.url) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPill("关闭", modifier = Modifier.fillMaxWidth().height(42.dp), onClick = onClose)
    }
}
