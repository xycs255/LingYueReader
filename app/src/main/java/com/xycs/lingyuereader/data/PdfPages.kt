package com.xycs.lingyuereader.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File

/**
 * 用系统的 PdfRenderer 量页面、把页面画成图。PdfRenderer 同一时间只能开一页，所以全部串行；
 * 最近用的那个 PDF 保持打开，连续翻页时不必每次重开。
 */
object PdfPages {
    private var openFile: File? = null
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    @Synchronized
    private fun rendererFor(file: File): PdfRenderer {
        renderer?.takeIf { openFile == file }?.let { return it }
        close()
        val d = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(d).also {
                fd = d
                renderer = it
                openFile = file
            }
        } catch (e: Exception) {
            d.close()
            throw IllegalArgumentException(if (e is SecurityException) "这个 PDF 有密码，灵阅打不开" else "不是有效的 PDF 文件", e)
        }
    }

    @Synchronized
    fun close() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        renderer = null
        fd = null
        openFile = null
    }

    /** 每页的高宽比。 */
    @Synchronized
    fun aspects(file: File): List<Float> {
        val r = rendererFor(file)
        return (0 until r.pageCount).map { i ->
            r.openPage(i).use { p -> if (p.width > 0) p.height.toFloat() / p.width else 1.414f }
        }
    }

    /** 把第 [page] 页按 [widthPx] 宽画出来（白底）。 */
    @Synchronized
    fun render(file: File, page: Int, widthPx: Int): Bitmap? = runCatching {
        val r = rendererFor(file)
        if (page !in 0 until r.pageCount) return null
        r.openPage(page).use { p ->
            val width = widthPx.coerceIn(64, 2400)
            val height = (width.toFloat() * p.height / p.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            createBitmap(width, height).also { b ->
                b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }.getOrNull()

    /** 把某页存成 JPEG（书架封面用）。 */
    fun saveJpeg(file: File, page: Int, widthPx: Int, target: File): Boolean {
        val bitmap = render(file, page, widthPx) ?: return false
        return target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.also { bitmap.recycle() }
    }
}
