package com.xycs.lingyuereader.data

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 用户导入的字体（TTF/OTF/TTC），存在 files/fonts/。设置里按文件名引用；
 * 文件不在了（比如从备份恢复到新手机，字体没打包）就退回内置的宋体。
 */
class FontStore internal constructor(
    private val dir: File,
    private val replace: (File, File) -> Unit,
) {
    constructor(dir: File) : this(dir, { from, to ->
        // 同目录的原子替换：覆盖失败时旧字体仍在，绝不先删掉它。
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    })

    fun list(): List<String> =
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }?.map { it.name }?.sortedWith(BookParser.NaturalOrder).orEmpty()

    fun file(name: String): File? = File(dir, name).takeIf { name.isNotEmpty() && it.isFile && it.parentFile == dir }

    /** 导入一个字体文件；不是字体时抛出异常。返回保存后的文件名。 */
    fun import(name: String, input: InputStream): String {
        check(dir.isDirectory || dir.mkdirs()) { "无法准备字体目录" }
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "font.ttf" }
        val target = File(dir, safe)
        val tmp = File.createTempFile("font-", ".tmp", dir)
        try {
            input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            require(isFont(tmp)) { "不是 TTF/OTF 字体文件" }
            replace(tmp, target)
            check(!tmp.exists() && target.isFile) { "无法安装字体文件" }
            return target.name
        } finally {
            tmp.delete()
        }
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
