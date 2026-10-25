package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** 书摘卡片的配色。 */
enum class CardStyle(val label: String, val background: Int, val text: Int, val secondary: Int, val accent: Int) {
    PAPER("纸白", 0xFFF4F2EC.toInt(), 0xFF2A2724.toInt(), 0xFF857D73.toInt(), 0xFFA23B2A.toInt()),
    PARCHMENT("宣黄", 0xFFF2E6CC.toInt(), 0xFF3E3222.toInt(), 0xFF8C7A5E.toInt(), 0xFF9A3A22.toInt()),
    BAMBOO("竹青", 0xFFDCEBD2.toInt(), 0xFF26331F.toInt(), 0xFF6C7D62.toInt(), 0xFF3E6B2E.toInt()),
    NIGHT("墨夜", 0xFF1C1B1A.toInt(), 0xFFE6DCC4.toInt(), 0xFF8C8475.toInt(), 0xFFD49A57.toInt()),
}

/** 卡片上的内容。 */
data class QuoteCardContent(val quote: String, val bookTitle: String, val author: String?, val chapterTitle: String?)

/**
 * 把一段书摘画成图片：宽 [width] 像素，高随文字多少而定。用 Android 自己的 Canvas 和 StaticLayout 画，
 * 和屏幕大小无关，分享出去的图片每次都一样。[typeface] 为空时用系统宋体（衬线）。
 */
fun renderQuoteCard(content: QuoteCardContent, style: CardStyle, typeface: Typeface? = null, width: Int = 1080): Bitmap {
    val serif = typeface ?: Typeface.SERIF
    val pad = width * 0.09f
    val textWidth = (width - pad * 2).toInt()
    val quote = content.quote.trim().let { if (it.length > MAX_QUOTE) it.take(MAX_QUOTE) + "……" else it }

    fun paint(size: Float, color: Int, face: Typeface = serif) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        this.typeface = face
    }

    fun layout(text: String, p: TextPaint, spacing: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, textWidth)
            .setAlignment(align)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    val markPaint = paint(width * 0.15f, style.accent)
    val quoteLayout = layout(quote, paint(width * 0.048f, style.text), 1.55f)
    val source = "——《${content.bookTitle}》"
    val sourceLayout = layout(source, paint(width * 0.034f, style.text), 1.3f, Layout.Alignment.ALIGN_OPPOSITE)
    val detail = listOfNotNull(content.author?.takeIf { it.isNotBlank() }, content.chapterTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
    val detailLayout = detail.takeIf { it.isNotEmpty() }?.let { layout(it, paint(width * 0.028f, style.secondary), 1.3f, Layout.Alignment.ALIGN_OPPOSITE) }
    val brandPaint = paint(width * 0.024f, style.secondary, Typeface.DEFAULT)

    val markHeight = width * 0.12f
    val gap = width * 0.05f
    val height = (pad + markHeight + quoteLayout.height + gap * 1.4f + sourceLayout.height +
        (detailLayout?.height?.plus(gap * 0.3f) ?: 0f) + gap * 1.6f + brandPaint.textSize + pad * 0.7f).toInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(style.background)
    // 左上角一个大号引号。
    canvas.drawText("“", pad - width * 0.01f, pad + markHeight * 0.95f, markPaint)
    var y = pad + markHeight
    canvas.save()
    canvas.translate(pad, y)
    quoteLayout.draw(canvas)
    canvas.restore()
    y += quoteLayout.height + gap
    // 一道短横线隔开出处。
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = style.accent
        strokeWidth = width * 0.004f
    }
    canvas.drawLine(width - pad - width * 0.08f, y, width - pad, y, line)
    y += gap * 0.4f
    canvas.save()
    canvas.translate(pad, y)
    sourceLayout.draw(canvas)
    canvas.restore()
    y += sourceLayout.height
    detailLayout?.let {
        y += gap * 0.3f
        canvas.save()
        canvas.translate(pad, y)
        it.draw(canvas)
        canvas.restore()
    }
    // 底部小字落款。
    canvas.drawText("灵阅", pad, height - pad * 0.7f, brandPaint)
    return bitmap
}

/** 书摘最多取这么多字，太长的卡片没法看。 */
private const val MAX_QUOTE = 600

/** 存成 PNG 文件（分享用）。要写文件，别在界面线程上调；写不出来时抛异常。 */
fun Bitmap.writePng(file: File): File {
    file.parentFile?.mkdirs()
    file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) { "写入出错" } }
    return file
}

/** 书摘卡片面板：选配色、预览、分享或保存成图片。[onMessage] 用来提示分享、保存失败。 */
@Composable
internal fun QuoteCardSheet(
    content: QuoteCardContent,
    typeface: Typeface?,
    palette: ReaderPalette,
    tint: Color = palette.accent,
    onMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by rememberSaveable { mutableStateOf(CardStyle.PAPER) }
    val bitmap = remember(content, style, typeface) { renderQuoteCard(content, style, typeface) }
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // 编码 PNG、写文件都在后台做；存储满、没有权限时提示，不闪退。
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri) ?: error("打不开文件")
                    out.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "写入出错" } }
                }
            }
            saved.onFailure { onMessage("保存图片失败：${it.message ?: "写入出错"}") }
        }
    }
    // 标题栏右边“分享”；预览下面选配色，再下面“保存图片”。
    ProvideReaderColors(palette, tint) {
        val c = LocalGroupedColors.current
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetNavBar("书摘卡片", back = null, action = "分享" to { scope.launch { shareCard(context, bitmap, onMessage) } }) {}
            // 换配色时旧卡淡出、新卡淡入（原地换内容），不一下跳色；减少动效时直接换。
            AnimatedContent(bitmap, transitionSpec = { Motion.fadeThrough() }, label = "quoteCardStyle") { card ->
                Image(
                    card.asImageBitmap(),
                    contentDescription = "书摘卡片预览",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .padding(horizontal = 32.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .border(0.5.dp, c.separator, RoundedCornerShape(12.dp)),
                )
            }
            SegmentedControl(CardStyle.entries.map { it to it.label }, style, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { style = it }
            GroupedSection {
                row { ActionRow("保存图片", centered = true) { save.launch("书摘-${content.bookTitle}-${System.currentTimeMillis() / 1000}.png") } }
            }
        }
    }
}

/** 先在后台把卡片写成 PNG，再交给系统分享；写不出来（存储满等）时提示。 */
private suspend fun shareCard(context: Context, bitmap: Bitmap, onMessage: (String) -> Unit) {
    val written = withContext(Dispatchers.IO) {
        runCatching { bitmap.writePng(File(File(context.cacheDir, "cards"), "书摘.png")) }
    }
    val file = written.getOrElse {
        onMessage("分享失败：${it.message ?: "写入出错"}")
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, "分享书摘").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
