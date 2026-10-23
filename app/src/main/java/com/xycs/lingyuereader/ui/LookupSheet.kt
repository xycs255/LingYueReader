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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/** 查词面板：所选的词、调整选区、内置字典与词典、导入的词典、在线与其他应用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookupSheet(
    lookup: Lookup,
    palette: ReaderPalette,
    vm: AppViewModel,
    onAnnotate: () -> Unit,
    onManage: () -> Unit,
) {
    val context = LocalContext.current
    val vocab by vm.vocab.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (lookup.range != null) {
                Chip("‹", "选区向前多一个字", palette) { vm.adjustSelection(-1, 0) }
                Chip("›", "选区开头少一个字", palette) { vm.adjustSelection(1, 0) }
            }
            Text(
                lookup.word,
                color = palette.text,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            if (lookup.range != null) {
                Chip("‹", "选区结尾少一个字", palette) { vm.adjustSelection(0, -1) }
                Chip("›", "选区向后多一个字", palette) { vm.adjustSelection(0, 1) }
            }
        }
        FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val inVocab = vocab.any { it.word == lookup.word }
            Chip(if (inVocab) "★ 已在生词本" else "☆ 收入生词本", null, palette) {
                if (inVocab) vm.removeFromVocabulary(lookup.word) else vm.addToVocabulary()
            }
            Chip("复制", null, palette) { copyText(context, lookup.word) }
            if (lookup.range != null) Chip("划线·批注", null, palette) { onAnnotate() }
            Chip("汉典", null, palette) { openUrl(context, "https://www.zdic.net/hans/" + encode(lookup.word)) }
            Chip("百度汉语", null, palette) { openUrl(context, "https://hanyu.baidu.com/s?wd=" + encode(lookup.word) + "&ptype=zici") }
            Chip("其他词典 App", null, palette) { sendToApps(context, lookup.word) }
        }

        val result = lookup.result
        if (result == null) {
            Text("正在查……", color = palette.secondary, fontSize = 14.sp)
            return@Column
        }
        // 查单字时先看字典（读音、部首），查词时先看词典。
        if (lookup.word.codePointCount(0, lookup.word.length) == 1) {
            result.chars.forEach { CharCard(it, palette) }
            result.words.forEach { WordCard(it, palette) }
        } else {
            result.words.forEach { WordCard(it, palette) }
            result.chars.forEach { CharCard(it, palette) }
        }
        if (result.related.isNotEmpty()) {
            Text("相关词语", color = palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.related.forEach { w -> Chip(w, null, palette) { vm.lookupWord(w) } }
            }
        }
        result.imported.forEach { (dict, entries) -> ImportedSection(dict, entries, palette, vm) }
        if (result.words.isEmpty() && result.chars.isEmpty() && result.imported.isEmpty()) {
            Text(
                "词典里没有查到“${lookup.word}”。可以调整选区，或用上面的在线查询、其他词典 App。",
                color = palette.secondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        Text(
            "管理词典 ›",
            color = palette.accent,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 18.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onManage).padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun Chip(label: String, description: String?, palette: ReaderPalette, onClick: () -> Unit) {
    Text(
        label,
        color = palette.text,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.text.copy(alpha = 0.07f))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}

@Composable
private fun WordCard(entry: WordEntry, palette: ReaderPalette) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(palette.text.copy(alpha = 0.05f)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(entry.pinyin, color = palette.accent, fontSize = 16.sp)
            if (entry.traditional != entry.simplified) {
                Text("繁 ${entry.traditional}", color = palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        entry.definitions.forEachIndexed { i, d ->
            Text("${i + 1}. $d", color = palette.text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text("CC-CEDICT（英文释义）", color = palette.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CharCard(c: CharEntry, palette: ReaderPalette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(palette.text.copy(alpha = 0.05f)).padding(14.dp),
    ) {
        Text(c.char, color = palette.text, fontFamily = FontFamily.Serif, fontSize = 40.sp, modifier = Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.readings.joinToString("  "), color = palette.accent, fontSize = 16.sp)
            val shape = listOfNotNull(
                c.radical.takeIf { it.isNotEmpty() }?.let { "部首 $it" },
                c.totalStrokes?.let { "共 $it 画" },
                c.traditional.takeIf { it.isNotEmpty() }?.let { "繁体 $it" },
                c.simplified.takeIf { it.isNotEmpty() }?.let { "简体 $it" },
            )
            if (shape.isNotEmpty()) Text(shape.joinToString(" · "), color = palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            if (c.definition.isNotEmpty()) Text(c.definition, color = palette.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Unihan", color = palette.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ImportedSection(dict: DictInfo, entries: List<MdictEntry>, palette: ReaderPalette, vm: AppViewModel) {
    Text(dict.title, color = palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    val styles = vm.dictStyles(dict.id).joinToString("") { """<link rel="stylesheet" href="$it">""" }
    val text = palette.text.hex()
    val accent = palette.accent.hex()
    val raw = entries.joinToString("""<hr style="border:none;border-top:1px solid $text;opacity:.15">""") { localResourceUrls(it.html) }
    // 图片先在后台从词典里取出来直接嵌进网页（data: 地址），不靠 WebView 再去请求——
    // 不同 WebView 对拦截自定义地址的处理不一，嵌进去最稳；找不到的记下来。
    val inlined by produceState<Pair<String, List<String>>?>(null, raw, dict.id) {
        value = withContext(Dispatchers.IO) { inlineImages(raw) { path -> vm.dictResource(dict.id, path) } }
    }
    val (body, notFound) = inlined ?: run {
        Text("正在载入释义……", color = palette.secondary, fontSize = 13.sp)
        return
    }
    val html = """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">$styles
        <style>body{margin:0;color:$text;background:transparent;font-size:15px;line-height:1.6;word-wrap:break-word}
        a{color:$accent}img{max-width:100%;height:auto}</style></head><body>$body</body></html>"""
    // 释义里引用、却在词典里找不到的文件（多半是图片）：列出来，好知道是没导入 .mdd 还是词典本身缺。
    val missing = remember(html) { mutableStateListOf<String>().apply { addAll(notFound) } }
    // 释义区最高占屏幕六成，长的在里面滚。整页撑开的话，几千像素高的网页画在玻璃面板里会闪，
    // 面板的高度也会跟着释义一变再变。
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.6f).toDp() }
    DictWebView(dict.id, html, vm, Modifier.fillMaxWidth().heightIn(min = 40.dp, max = maxHeight)) { path -> if (path !in missing) missing += path }
    if (missing.isNotEmpty()) {
        Text(
            buildString {
                append("有 ${missing.size} 个文件没找到：")
                append(missing.take(3).joinToString("、") { it.substringAfterLast('/') })
                if (missing.size > 3) append(" 等")
                append("。")
                if (dict.mdds.isEmpty()) {
                    append("这本词典导入时没有带 .mdd，图片、读音一般都在 .mdd 里——到“管理词典”删掉它，重新导入时把同名的 .mdd 一起选上。")
                } else {
                    append("词典的 .mdd 里也没有这些文件。")
                }
            },
            color = palette.secondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
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
