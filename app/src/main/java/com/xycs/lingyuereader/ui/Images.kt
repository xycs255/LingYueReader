package com.xycs.lingyuereader.ui

import android.graphics.BitmapFactory
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

/**
 * 书里的插图：按显示宽度降采样解码，内存里缓存最近用过的几张。
 * 分页时只需要宽高比，读文件头即可，不解码整张图。
 */
class BookImages(private val fileOf: (String) -> File) {
    private val aspects = ConcurrentHashMap<String, Float>()

    /** 高宽比；读不出时为空。 */
    fun aspect(src: String): Float? = aspects[src] ?: run {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fileOf(src).path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else (o.outHeight.toFloat() / o.outWidth).also { aspects[src] = it }
    }

    fun load(src: String, widthPx: Int): ImageBitmap? = decode(fileOf(src), widthPx)

    companion object {
        // 约 24MB：几页插图加书架封面足够。
        private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }

        fun decode(file: File, widthPx: Int): ImageBitmap? {
            if (!file.isFile) return null
            val key = "${file.path}@$widthPx:${file.lastModified()}"
            cache.get(key)?.let { return it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap() ?: return null
            cache.put(key, bitmap)
            return bitmap
        }
    }
}

/** 当前这本书的插图来源，由阅读页提供。 */
val LocalBookImages = staticCompositionLocalOf<BookImages?> { null }

/** 插图最高显示多高：翻页模式下不超过一页，滚动模式下不超过一屏。 */
val LocalImageMaxHeight = compositionLocalOf { Dp.Infinity }

@Composable
internal fun BookImage(src: String, placeholder: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val images = LocalBookImages.current ?: return
    val maxHeight = LocalImageMaxHeight.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val aspect = remember(src) { images.aspect(src) } ?: 0.75f
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
