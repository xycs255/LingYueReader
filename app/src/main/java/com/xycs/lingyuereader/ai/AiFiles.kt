package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.DurableFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 存在书目录里的 AI 结果（整章译文、速读、伴读、讲书稿）：一个文件装着这本书以前的全部结果，写坏一次就全没了。
 * 写照 [AiCharacters.write]：临时文件、刷到磁盘再原子改名，写不成就抛出，旧文件原样留着。
 * 读不出的先另存一份（见 [DurableFiles.keepDamaged]），之后按空的用、再保存时不会把原来的内容冲掉。
 */
internal object AiFiles {
    /** 读出并解析；文件不存在返回 null。读不出或解析失败：另存一份后返回 null；连副本都存不下就抛出，不能当成空的再写回去。 */
    fun <T> load(file: File?, parse: (String) -> T): T? {
        val f = file ?: return null
        if (!f.exists()) return null
        return try {
            parse(f.readText())
        } catch (e: Exception) {
            DurableFiles.keepDamaged(f)
            if (!File(f.parentFile, f.name + ".damaged").isFile) throw IOException("读取失败，也无法另存一份：${e.message ?: e.javaClass.simpleName}", e)
            null
        }
    }

    /** 写临时文件、刷到磁盘再改名：断电或存储满时要么是旧内容，要么是新内容。失败时抛出。 */
    fun write(file: File?, text: String) {
        val f = file ?: throw IOException("找不到保存位置")
        val dir = f.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录")
        val tmp = File(dir, f.name + ".tmp")
        try {
            FileOutputStream(tmp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }
}
