package com.xycs.lingyuereader.data

import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 只有图片的书：漫画（CBZ）和 PDF。每页是一张图，按需从原文件里取，不另存一份。
 * - CBZ 里的图用“@zip/压缩包内路径”表示；
 * - PDF 的页用“@pdf/页号/高宽比”表示，高宽比在导入时量好，分页时不必先画出来。
 * 导入时这两种路径会换成带来源文件名的形式（见 Library），阅读页按它去压缩包或 PDF 里取图。
 */
object PageBooks {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    /** 一章放多少页：章是进度和目录的单位，太长了进度条不好用。 */
    const val PAGES_PER_CHAPTER = 20

    fun comic(source: BookParser.SourceBytes): BookContent {
        val names = (source.file?.let { f -> ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toList() } }
            ?: ZipInputStream(source.bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.map { it.name }.toList()
            })
            .filter { !it.endsWith('/') && it.substringAfterLast('.', "").lowercase() in imageExtensions }
            .filterNot { it.startsWith("__MACOSX/") || it.substringAfterLast('/').startsWith('.') }
            .sortedWith(BookParser.NaturalOrder)
        require(names.isNotEmpty()) { "压缩包里没有图片" }
        val folders = names.groupBy { it.substringBeforeLast('/', "") }
        val chapters = if (folders.size > 1) {
            // 分了文件夹（常见于一卷多话）：一个文件夹一章。
            folders.map { (dir, files) ->
                Chapter(dir.substringAfterLast('/').ifEmpty { "开头" }, files.map { Block.Image("@zip/$it") })
            }
        } else {
            pageChapters(names.map { Block.Image("@zip/$it") })
        }
        return BookContent(BookParser.titleOf(source.name), null, chapters, cover = "@zip/${names.first()}")
    }

    /** [aspects] 是每页的高宽比。 */
    fun pdf(source: BookParser.SourceBytes, aspects: List<Float>): BookContent {
        require(aspects.isNotEmpty()) { "这个 PDF 没有页面" }
        val pages = aspects.mapIndexed { i, a -> Block.Image("@pdf/$i/$a") }
        return BookContent(BookParser.titleOf(source.name), null, pageChapters(pages), cover = "@pdf/0/${aspects[0]}")
    }

    private fun pageChapters(pages: List<Block.Image>): List<Chapter> =
        pages.chunked(PAGES_PER_CHAPTER).mapIndexed { i, chunk ->
            val from = i * PAGES_PER_CHAPTER + 1
            Chapter(if (pages.size <= PAGES_PER_CHAPTER) "全部" else "第 $from–${from + chunk.size - 1} 页", chunk)
        }

    /** PDF 页的引用：来源文件名、页号、高宽比。 */
    data class PdfPage(val file: String, val page: Int, val aspect: Float)

    fun pdfRef(src: String): PdfPage? {
        if (!src.startsWith("pdf:")) return null
        val parts = src.removePrefix("pdf:").split(':')
        if (parts.size != 3) return null
        return PdfPage(parts[0], parts[1].toIntOrNull() ?: return null, parts[2].toFloatOrNull() ?: return null)
    }

    /** 压缩包里的图：来源文件名和包内路径。 */
    fun zipRef(src: String): Pair<String, String>? {
        if (!src.startsWith("zip:")) return null
        val rest = src.removePrefix("zip:")
        val file = rest.substringBefore(':')
        val path = rest.substringAfter(':', "")
        return if (file.isEmpty() || path.isEmpty()) null else file to path
    }

    /** 从图片文件头判断扩展名；认不出返回空。 */
    fun imageExtension(bytes: ByteArray): String? {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "png"
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "gif"
            at(0) == 'B'.code && at(1) == 'M'.code -> "bmp"
            at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code -> "webp"
            else -> null
        }
    }
}
