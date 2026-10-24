package com.xycs.lingyuereader.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import com.xycs.lingyuereader.data.PageBooks
import com.xycs.lingyuereader.data.PdfPages

/**
 * 书里的插图：按显示宽度降采样解码，内存里缓存最近用过的几张。
 * 分页时只需要宽高比，读文件头即可，不解码整张图。
 * 漫画的页直接从压缩包里读（“zip:来源文件:包内路径”），PDF 的页现画（“pdf:来源文件:页号:高宽比”）。
 */
class BookImages(private val fileOf: (String) -> File) {
    private val aspects = ConcurrentHashMap<String, Float>()
    private val zips = HashMap<String, ZipFile>()

    @Synchronized
    private fun zip(name: String): ZipFile? = zips[name] ?: runCatching { com.xycs.lingyuereader.data.PageBooks.openZip(fileOf(name)) }.getOrNull()?.also { zips[name] = it }

    /** 已经量过的高宽比（不读文件）；没量过时为空。 */
    fun knownAspect(src: String): Float? = aspects[src] ?: PageBooks.pdfRef(src)?.aspect

    /** 高宽比；读不出时为空。要读文件，别在界面线程上调。 */
    fun aspect(src: String): Float? = aspects[src] ?: run {
        PageBooks.pdfRef(src)?.let { return it.aspect }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val zipped = PageBooks.zipRef(src)
        if (zipped != null) {
            val (name, path) = zipped
            val z = zip(name) ?: return null
            val entry = z.getEntry(path) ?: return null
            synchronized(z) { z.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, o) } }
        } else {
            BitmapFactory.decodeFile(fileOf(src).path, o)
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) null else (o.outHeight.toFloat() / o.outWidth).also { aspects[src] = it }
    }

    fun load(src: String, widthPx: Int): ImageBitmap? {
        PageBooks.pdfRef(src)?.let { page ->
            val key = "${fileOf(page.file).path}#${page.page}@$widthPx"
            cache.get(key)?.let { return it }
            return PdfPages.render(fileOf(page.file), page.page, widthPx)?.asImageBitmap()?.also { cache.put(key, it) }
        }
        PageBooks.zipRef(src)?.let { (name, path) ->
            val z = zip(name) ?: return null
            val entry = z.getEntry(path) ?: return null
            val key = "${fileOf(name).path}#$path@$widthPx"
            cache.get(key)?.let { return it }
            return synchronized(z) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                z.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, widthPx) }
                z.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
            }?.asImageBitmap()?.also { cache.put(key, it) }
        }
        return decode(fileOf(src), widthPx)
    }

    /** 关掉打开着的压缩包（离开阅读页时）。 */
    @Synchronized
    fun close() {
        zips.values.forEach { runCatching { it.close() } }
        zips.clear()
    }

    companion object {
        // 约 24MB：几页插图加书架封面足够。
        private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }

        private fun sampleFor(width: Int, widthPx: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= widthPx) sample *= 2
            return sample
        }

        fun decode(file: File, widthPx: Int): ImageBitmap? {
            if (!file.isFile) return null
            val key = "${file.path}@$widthPx:${file.lastModified()}"
            cache.get(key)?.let { return it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, widthPx) })
                ?.asImageBitmap() ?: return null
            cache.put(key, bitmap)
            return bitmap
        }
    }
}

/** 当前这本书的插图来源，由阅读页提供。 */
val LocalBookImages = staticCompositionLocalOf<BookImages?> { null }

/** 长按插图时打开大图，由阅读页提供。 */
val LocalImageZoom = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** 插图最高显示多高：翻页模式下不超过一页，滚动模式下不超过一屏。 */
val LocalImageMaxHeight = compositionLocalOf { Dp.Infinity }

@Composable
internal fun BookImage(src: String, placeholder: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val images = LocalBookImages.current ?: return
    val maxHeight = LocalImageMaxHeight.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 高宽比要打开压缩包读图片头：量过的直接用，没量过的到后台去量（量好前按 3:4 占位）。
        val measured by produceState(images.knownAspect(src), src) {
            if (value == null) value = withContext(Dispatchers.IO) { images.aspect(src) }
        }
        val aspect = measured ?: 0.75f
        val height = min(maxWidth * aspect, maxHeight)
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(null, src, widthPx) {
            value = withContext(Dispatchers.IO) { images.load(src, widthPx) }
        }
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(height))
        } else {
            Box(Modifier.fillMaxWidth().height(height).background(placeholder))
        }
    }
}

/** 书架封面图；调用方先确认 [file] 存在，没有时画生成的封面。 */
@Composable
internal fun CoverImage(file: File, modifier: Modifier = Modifier) {
    val widthPx = with(LocalDensity.current) { Dp(120f).roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BookImages.decode(file, widthPx) }
    }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

/** 全屏大图：双指缩放、拖动，双击放大或还原，单击或返回键关闭。 */
@Composable
internal fun ZoomedImage(src: String, images: BookImages, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val widthPx = (LocalWindowInfo.current.containerSize.width * 2).coerceAtMost(2400)
    val bitmap by produceState<ImageBitmap?>(null, src, widthPx) {
        value = withContext(Dispatchers.IO) { images.load(src, widthPx) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClose() },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    },
                )
            }
            .transformable(state)
            .semantics { contentDescription = "大图，双指缩放，单击关闭" },
    ) {
        bitmap?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
    }
}
