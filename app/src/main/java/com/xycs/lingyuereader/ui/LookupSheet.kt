package com.xycs.lingyuereader.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Lookup
import com.xycs.lingyuereader.dict.CharEntry
import com.xycs.lingyuereader.dict.DictInfo
import com.xycs.lingyuereader.dict.MdictEntry
import com.xycs.lingyuereader.dict.WordEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/**
 * 查词面板，照苹果的“查询”：标题栏右边“管理词典”；词头大字，两边微调选区；
 * 下面一排操作（生词本、复制、划线）；字典、词典、导入的词典各一组；最后是在线查询。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookupSheet(
    lookup: Lookup,
    palette: ReaderPalette,
    vm: AppViewModel,
    onAnnotate: () -> Unit,
    onManage: () -> Unit,
    tint: Color = palette.accent,
) {
    val context = LocalContext.current
    val vocab by vm.vocab.collectAsStateWithLifecycle()
    ProvideGroupedColors(dark = palette.background.luminance() < 0.5f, tint = tint, elevated = true) {
        val c = LocalGroupedColors.current
        // 先是一张小卡片：词、读音、头几条释义、原句；“完整释义”再展开成整页（各本词典、微调选区、相关词语）。
        // 微调选区时词会变，展开了就一直展开；换一段再查才回到小卡片。
        var full by remember(lookup.chapterIndex, lookup.block) { mutableStateOf(false) }
        if (!full) {
            CompactLookup(lookup, vocab.any { it.word == lookup.word }, vm, onAnnotate) { full = true }
            return@ProvideGroupedColors
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetNavBar("查词", back = null, action = "管理词典" to onManage) {}
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lookup.range != null) {
                    AdjustButton(LingYueIcons.ChevronBackward, "选区向前多一个字") { vm.adjustSelection(-1, 0) }
                    AdjustButton(LingYueIcons.ChevronForward, "选区开头少一个字") { vm.adjustSelection(1, 0) }
                }
                Text(
                    lookup.word,
                    color = c.label,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (lookup.range != null) {
                    AdjustButton(LingYueIcons.ChevronBackward, "选区结尾少一个字") { vm.adjustSelection(0, -1) }
                    AdjustButton(LingYueIcons.ChevronForward, "选区向后多一个字") { vm.adjustSelection(0, 1) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val inVocab = vocab.any { it.word == lookup.word }
                ActionTile(if (inVocab) "已在生词本" else "收入生词本", if (inVocab) LingYueIcons.StarFill else LingYueIcons.Star, Modifier.weight(1f)) {
                    if (inVocab) vm.removeFromVocabulary(lookup.word) else vm.addToVocabulary()
                }
                ActionTile("复制", LingYueIcons.Copy, Modifier.weight(1f)) { copyText(context, lookup.word) }
                if (lookup.range != null) ActionTile("划线·批注", LingYueIcons.Highlight, Modifier.weight(1f), onAnnotate)
            }

            val result = lookup.result
            if (result == null) {
                GroupedSection { row { ValueRow("正在查……", "") } }
            } else {
                // 查单字时先看字典（读音、部首），查词时先看词典。
                val chars: @Composable () -> Unit = {
                    if (result.chars.isNotEmpty()) {
                        GroupedSection(header = "字典", footer = "Unihan") { result.chars.forEach { row { CharCell(it) } } }
                    }
                }
                val words: @Composable () -> Unit = {
                    if (result.words.isNotEmpty()) {
                        GroupedSection(header = "词典", footer = "CC-CEDICT（英文释义）") { result.words.forEach { row { WordCell(it) } } }
                    }
                }
                if (lookup.word.codePointCount(0, lookup.word.length) == 1) {
                    chars()
                    words()
                } else {
                    words()
                    chars()
                }
                result.imported.forEach { (dict, entries) -> ImportedSection(dict, entries, vm) }
                if (result.words.isEmpty() && result.chars.isEmpty() && result.imported.isEmpty()) {
                    GroupedSection(footer = "可以微调选区，或用下面的在线查询、其他词典 App。") {
                        row { ValueRow("词典里没有查到“${lookup.word}”", "") }
                    }
                }
                if (result.related.isNotEmpty()) {
                    GroupedSection(header = "相关词语") {
                        row {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                result.related.forEach { w ->
                                    Text(
                                        w,
                                        color = c.tint,
                                        fontSize = 15.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(c.fill)
                                            .clickable(role = Role.Button) { vm.lookupWord(w) }
                                            .padding(horizontal = 14.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            GroupedSection(header = "在线查询") {
                row { NavRow("汉典") { openUrl(context, "https://www.zdic.net/hans/" + encode(lookup.word)) } }
                row { NavRow("百度汉语") { openUrl(context, "https://hanyu.baidu.com/s?wd=" + encode(lookup.word) + "&ptype=zici") } }
                row { NavRow("其他词典 App") { sendToApps(context, lookup.word) } }
            }
        }
    }
}

/** 查词小卡片：照样稿，词和读音大字，右上角划线、收入生词本，下面头几条释义、原句，最底下在线查询和“完整释义”。 */
@Composable
private fun CompactLookup(lookup: Lookup, inVocab: Boolean, vm: AppViewModel, onAnnotate: () -> Unit, onExpand: () -> Unit) {
    val c = LocalGroupedColors.current
    val context = LocalContext.current
    val result = lookup.result
    val word = result?.words?.firstOrNull()
    val ch = result?.chars?.firstOrNull()
    val reading = word?.pinyin ?: ch?.readings?.joinToString("  ")
    val definitions = word?.definitions?.take(3) ?: ch?.definition?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
    Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(lookup.word, color = c.label, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp)
            if (reading != null) Text(reading, color = c.tint, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp, bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            if (lookup.range != null) RoundIcon(LingYueIcons.Highlight, "划线·批注", active = false, onClick = onAnnotate)
            RoundIcon(if (inVocab) LingYueIcons.StarFill else LingYueIcons.Star, if (inVocab) "已在生词本" else "收入生词本", active = true) {
                if (inVocab) vm.removeFromVocabulary(lookup.word) else vm.addToVocabulary()
            }
        }
        when {
            result == null -> Text("正在查……", color = c.secondary, fontSize = 15.sp)
            definitions.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                definitions.forEachIndexed { i, d ->
                    Row {
                        Text("${i + 1}", color = c.tint, fontSize = 13.sp, modifier = Modifier.width(18.dp).padding(top = 2.dp))
                        Text(d, color = c.label, fontSize = 15.sp, lineHeight = 22.sp)
                    }
                }
            }
            result.imported.isNotEmpty() -> Text("在「${result.imported.first().first.title}」里查到了，点“完整释义”看。", color = c.secondary, fontSize = 14.sp)
            else -> Text("词典里没有查到“${lookup.word}”。可以用下面的在线查询，或在完整释义里微调选区。", color = c.secondary, fontSize = 14.sp)
        }
        if (lookup.context.isNotBlank()) {
            Text("原句：${lookup.context.trim()}", color = c.secondary, fontSize = 12.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("汉典") { openUrl(context, "https://www.zdic.net/hans/" + encode(lookup.word)) }
            Pill("百度汉语") { openUrl(context, "https://hanyu.baidu.com/s?wd=" + encode(lookup.word) + "&ptype=zici") }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onExpand).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("完整释义", color = c.tint, fontSize = 14.sp)
                Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 卡片右上角的圆钮：44dp，[active] 时铺一层淡强调色。 */
@Composable
private fun RoundIcon(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) c.tint.copy(alpha = 0.1f) else c.fill)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) c.tint else c.label, modifier = Modifier.size(20.dp))
    }
}

/** 灰底小胶囊按钮（在线查询）。 */
@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Text(
        label,
        color = c.label,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** 词头两边微调选区的小圆钮：看上去 34dp，能按到的 48dp。 */
@Composable
private fun AdjustButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Box(
        Modifier.size(40.dp, 48.dp).clickable(onClickLabel = description, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.label, modifier = Modifier.size(34.dp).clip(CircleShape).background(c.cell).padding(8.dp))
    }
}

/** 一排操作里的一格：圆角卡片，上面图标、下面字，像“照片”里的分享操作。 */
@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.cell)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = c.tint, modifier = Modifier.size(22.dp))
        Text(label, color = c.tint, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun WordCell(entry: WordEntry) {
    val c = LocalGroupedColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(entry.pinyin, color = c.tint, fontSize = 17.sp)
            if (entry.traditional != entry.simplified) {
                Text("繁 ${entry.traditional}", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        entry.definitions.forEachIndexed { i, d ->
            Text("${i + 1}. $d", color = c.label, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun CharCell(ch: CharEntry) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(ch.char, color = c.label, fontFamily = FontFamily.Serif, fontSize = 40.sp, modifier = Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.readings.joinToString("  "), color = c.tint, fontSize = 17.sp)
            val shape = listOfNotNull(
                ch.radical.takeIf { it.isNotEmpty() }?.let { "部首 $it" },
                ch.totalStrokes?.let { "共 $it 画" },
                ch.traditional.takeIf { it.isNotEmpty() }?.let { "繁体 $it" },
                ch.simplified.takeIf { it.isNotEmpty() }?.let { "简体 $it" },
            )
            if (shape.isNotEmpty()) Text(shape.joinToString(" · "), color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            if (ch.definition.isNotEmpty()) Text(ch.definition, color = c.label, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ImportedSection(dict: DictInfo, entries: List<MdictEntry>, vm: AppViewModel) {
    val c = LocalGroupedColors.current
    val styles = vm.dictStyles(dict.id).joinToString("") { """<link rel="stylesheet" href="$it">""" }
    val text = c.label.hex()
    val accent = c.tint.hex()
    val raw = entries.joinToString("""<hr style="border:none;border-top:1px solid $text;opacity:.15">""") { localResourceUrls(it.html) }
    // 图片先在后台从词典里取出来直接嵌进网页（data: 地址），不靠 WebView 再去请求——
    // 不同 WebView 对拦截自定义地址的处理不一，嵌进去最稳；找不到的记下来。
    val inlined by produceState<Pair<String, List<String>>?>(null, raw, dict.id) {
        value = withContext(Dispatchers.IO) { inlineImages(raw) { path -> vm.dictResource(dict.id, path) } }
    }
    val loaded = inlined
    if (loaded == null) {
        GroupedSection(header = dict.title) { row { ValueRow("正在载入释义……", "") } }
        return
    }
    val (body, notFound) = loaded
    val html = """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">$styles
        <style>body{margin:0;color:$text;background:transparent;font-size:15px;line-height:1.6;word-wrap:break-word}
        a{color:$accent}img{max-width:100%;height:auto}</style></head><body>$body</body></html>"""
    // 释义里引用、却在词典里找不到的文件（多半是图片）：列出来，好知道是没导入 .mdd 还是词典本身缺。
    val missing = remember(html) { mutableStateListOf<String>().apply { addAll(notFound) } }
    // 释义区最高占屏幕六成，长的在里面滚。整页撑开的话，几千像素高的网页画在玻璃面板里会闪，
    // 面板的高度也会跟着释义一变再变。
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.6f).toDp() }
    val footer = if (missing.isEmpty()) null else buildString {
        append("有 ${missing.size} 个文件没找到：")
        append(missing.take(3).joinToString("、") { it.substringAfterLast('/') })
        if (missing.size > 3) append(" 等")
        append("。")
        if (dict.mdds.isEmpty()) {
            append("这本词典导入时没有带 .mdd，图片、读音一般都在 .mdd 里——到“管理词典”删掉它，重新导入时把同名的 .mdd 一起选上。")
        } else {
            append("词典的 .mdd 里也没有这些文件。")
        }
    }
    GroupedSection(header = dict.title, footer = footer) {
        row {
            DictWebView(dict.id, html, vm, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 40.dp, max = maxHeight)) { path ->
                if (path !in missing) missing += path
            }
        }
    }
}

/**
 * 显示导入词典的 HTML 释义。图片、样式从词典的 .mdd 和同目录文件里取，其他网络请求一律拦下；
 * 点 entry:// 链接在面板里接着查，点普通网址交给浏览器。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DictWebView(dictId: String, html: String, vm: AppViewModel, modifier: Modifier, onMissing: (String) -> Unit = {}) {
    val base = "https://$HOST/$dictId/"
    val missing by rememberUpdatedState(onMissing)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0)
                // 释义比框高、能在里面滚时，手指按下就让外面的面板别抢这次拖动。
                setOnTouchListener { v, e ->
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && (v.canScrollVertically(1) || v.canScrollVertically(-1))) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptEnabled = false
                webViewClient = DictWebClient(
                    dictId = dictId,
                    resource = { path -> vm.dictResource(dictId, path) },
                    onEntry = vm::lookupWord,
                    onLink = { url -> openUrl(context, url) },
                    // 拦截在后台线程里调，回到主线程再记。
                    onMissing = { path -> post { missing(path) } },
                )
            }
        },
        // 只在释义变了时才重新载入。每次重组都载入的话：长释义让面板变高 → 面板重组 → 页面重载变矮 → 又变高……
        // 整个查词面板就一直闪。
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
            }
        },
    )
}

private const val HOST = "dict.lingyue.local"

/**
 * 把释义里的图片换成 data: 地址：按 src 从词典（.mdd 和同目录文件）里取出来，base64 嵌进去。
 * 网上的图片（http/https）先按路径在词典里找，找不到就不管（释义页不联网）。
 * 返回改好的 HTML 和没找到的文件。嵌入总量超过 [limit] 字节后剩下的留给拦截器按需去取。
 */
internal fun inlineImages(html: String, limit: Int = 6_000_000, resource: (String) -> ByteArray?): Pair<String, List<String>> {
    val missing = linkedSetOf<String>()
    var total = 0
    val out = Regex("""(<img\b[^>]*?\bsrc\s*=\s*)(["']?)([^"'\s>]+)\2""", RegexOption.IGNORE_CASE).replace(html) { m ->
        val src = m.groupValues[3]
        if (src.startsWith("data:", true) || total > limit) return@replace m.value
        val uri = Uri.parse(src)
        val path = when (uri.scheme?.lowercase()) {
            null -> src
            "http", "https" -> uri.path.orEmpty()
            else -> uri.host.orEmpty() + uri.path.orEmpty()
        }.let { Uri.decode(it) }.trim('/')
        val bytes = path.takeIf { it.isNotEmpty() }?.let { runCatching { resource(it) }.getOrNull() }
        if (bytes == null) {
            if (path.isNotEmpty()) missing += path
            return@replace m.value
        }
        total += bytes.size
        val data = "data:${mimeOf(path)};base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        m.groupValues[1] + "\"" + data + "\""
    }
    return out to missing.toList()
}

/**
 * 把释义里 file:// 开头的图片、样式地址改成相对地址（file://5357.gif → 5357.gif）。
 * 网页本身在 https 地址下，WebView 不许它加载 file:// 资源，请求在渲染进程里就被挡掉，
 * 根本到不了拦截器；改成相对地址后落到词典自己的地址下，由拦截器从 .mdd 里取。
 */
internal fun localResourceUrls(html: String): String =
    html.replace(Regex("""((?:src|href)\s*=\s*["']?)file:/*""", RegexOption.IGNORE_CASE), "$1")
        .replace(Regex("""(url\(\s*["']?)file:/*""", RegexOption.IGNORE_CASE), "$1")

/**
 * 词典释义页的 WebView 客户端：图片、样式从词典的 .mdd 和同目录文件里取，其他请求一律拦下（释义页不联网）；
 * 点 entry:// 链接在面板里接着查，点普通网址交给浏览器。
 */
internal class DictWebClient(
    private val dictId: String,
    private val resource: (String) -> ByteArray?,
    private val onEntry: (String) -> Unit,
    private val onLink: (String) -> Unit,
    /** 释义引用的文件在词典里找不到（路径）。 */
    private val onMissing: (String) -> Unit = {},
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? = intercept(request)

    internal fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        // 释义页本身（loadDataWithBaseURL 给的内容）要放行：较新的 WebView 也会把它交到这里，
        // 拦成 404 就只剩一张“网页无法使用”。它不经过网络。
        if (request.isForMainFrame || url.scheme == "data") return null
        // 词典里写的地址五花八门：相对路径（落到我们的地址下）、file://5357.gif、file:///img/5357.gif、
        // 甚至原网站的网址。一律按路径到词典里找，找到就给，找不到就 404——释义页不联网。
        val path = resourcePath(url)
        val bytes = path.trim('/').takeIf { it.isNotEmpty() }?.let { runCatching { resource(it) }.getOrNull() }
        if (bytes != null) return WebResourceResponse(mimeOf(path), null, ByteArrayInputStream(bytes))
        if (path.isNotEmpty() && !path.endsWith("favicon.ico")) onMissing(path.trim('/'))
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * 请求对应词典里的哪个文件。MDict 常写成 file://5357.gif（两道斜杠），
     * 按网址规则“5357.gif”会被当成主机名、路径为空，所以非 http 的地址把主机名也算进路径。
     */
    internal fun resourcePath(url: Uri): String {
        val path = url.path.orEmpty()
        return when {
            url.host == HOST -> path.removePrefix("/$dictId/")
            url.scheme == "http" || url.scheme == "https" -> path
            else -> url.host.orEmpty() + path
        }.trim('/')
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        when {
            url.startsWith("entry://") || url.startsWith("bword://") ->
                onEntry(Uri.decode(url.substringAfter("://")).trim('/').substringBefore('#'))
            url.startsWith("http://") || url.startsWith("https://") ->
                if (request.url.host != HOST) onLink(url)
        }
        return true
    }
}

private fun mimeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
    "css" -> "text/css"
    "js" -> "application/javascript"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ttf" -> "font/ttf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** 交给手机上的其他词典、翻译应用（它们一般会出现在系统“处理文字”的列表里）。 */
private fun sendToApps(context: Context, word: String) {
    val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, word)
    val chooser = Intent.createChooser(process, "用其他应用查“$word”")
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(send))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("灵阅", text))
}
