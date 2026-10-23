package com.xycs.lingyuereader.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
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
    val body = entries.joinToString("""<hr style="border:none;border-top:1px solid $text;opacity:.15">""") { it.html }
    val html = """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">$styles
        <style>body{margin:0;color:$text;background:transparent;font-size:15px;line-height:1.6;word-wrap:break-word}
        a{color:$accent}img{max-width:100%;height:auto}</style></head><body>$body</body></html>"""
    DictWebView(dict.id, html, vm, Modifier.fillMaxWidth().heightIn(min = 40.dp))
}

/**
 * 显示导入词典的 HTML 释义。图片、样式从词典的 .mdd 和同目录文件里取，其他网络请求一律拦下；
 * 点 entry:// 链接在面板里接着查，点普通网址交给浏览器。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DictWebView(dictId: String, html: String, vm: AppViewModel, modifier: Modifier) {
    val base = "https://$HOST/$dictId/"
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val url = request.url
                        if (url.host == HOST) {
                            val path = url.path.orEmpty().removePrefix("/$dictId/")
                            val bytes = vm.dictResource(dictId, Uri.decode(path))
                            if (bytes != null) return WebResourceResponse(mimeOf(path), null, ByteArrayInputStream(bytes))
                        }
                        // 不让释义页联网。
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        when {
                            url.startsWith("entry://") || url.startsWith("bword://") ->
                                vm.lookupWord(Uri.decode(url.substringAfter("://")).trim('/').substringBefore('#'))
                            url.startsWith("http://") || url.startsWith("https://") ->
                                if (request.url.host != HOST) openUrl(view.context, url)
                        }
                        return true
                    }
                }
            }
        },
        update = { it.loadDataWithBaseURL(base, html, "text/html", "utf-8", null) },
    )
}

private const val HOST = "dict.lingyue.local"

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
