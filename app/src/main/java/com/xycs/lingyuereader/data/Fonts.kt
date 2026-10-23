package com.xycs.lingyuereader.data

import java.io.File
import java.io.InputStream

/**
 * 用户导入的字体（TTF/OTF/TTC），存在 files/fonts/。设置里按文件名引用；
 * 文件不在了（比如从备份恢复到新手机，字体没打包）就退回内置的宋体。
 */
class FontStore(private val dir: File) {
    fun list(): List<String> =
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }?.map { it.name }?.sortedWith(BookParser.NaturalOrder).orEmpty()

    fun file(name: String): File? = File(dir, name).takeIf { name.isNotEmpty() && it.isFile && it.parentFile == dir }

    /** 导入一个字体文件；不是字体时抛出异常。返回保存后的文件名。 */
    fun import(name: String, input: InputStream): String {
        dir.mkdirs()
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "font.ttf" }
        val target = File(dir, safe)
        val tmp = File(dir, "$safe.tmp")
        input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        if (!isFont(tmp)) {
            tmp.delete()
            throw IllegalArgumentException("不是 TTF/OTF 字体文件")
        }
        target.delete()
        tmp.renameTo(target)
        return target.name
    }

    fun delete(name: String) {
        file(name)?.delete()
    }

    companion object {
        val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

        /** 显示用的名字：去掉扩展名。 */
        fun label(name: String): String = name.substringBeforeLast('.')

        /** 按文件头判断是不是字体：TrueType（00 01 00 00 / true）、OpenType（OTTO）、字体集合（ttcf）。 */
        fun isFont(file: File): Boolean {
            if (file.length() < 12) return false
            val head = ByteArray(4)
            file.inputStream().use { it.read(head) }
            val tag = String(head, Charsets.ISO_8859_1)
            return tag == "\u0000\u0001\u0000\u0000" || tag == "OTTO" || tag == "true" || tag == "ttcf"
        }
    }
}
