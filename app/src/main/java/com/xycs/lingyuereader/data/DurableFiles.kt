package com.xycs.lingyuereader.data

import java.io.File
import java.io.FileOutputStream

/** 小的 JSON 文件（书架索引、书签批注）：断电也不会写坏；读坏了先留一份，不被接下来的保存覆盖。 */
internal object DurableFiles {
    /** 写临时文件、刷到磁盘再改名：断电时要么是旧内容，要么是新内容，不会只剩半个文件。返回是否改名成功。 */
    fun write(target: File, bytes: ByteArray): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        return tmp.renameTo(target)
    }

    /** 读不出的文件先另存一份（文件名加 .damaged），之后当作空的用时，保存不会把原来的内容覆盖掉。 */
    fun keepDamaged(file: File) {
        val copy = File(file.parentFile, file.name + ".damaged")
        if (file.isFile && !copy.exists()) runCatching { file.copyTo(copy) }
    }

    /**
     * 读不出的内容 [bytes] 确实另存下来了才返回 true，之后才能用别的内容（如备份里的）覆盖原文件。
     * 已有的 .damaged 是更早另存的另一份时，再另存一份带时间的，不覆盖它。
     */
    fun keptDamaged(file: File, bytes: ByteArray): Boolean {
        keepDamaged(file)
        val copy = File(file.parentFile, file.name + ".damaged")
        if (copy.isFile && runCatching { copy.readBytes().contentEquals(bytes) }.getOrDefault(false)) return true
        val dated = File(file.parentFile, file.name + ".damaged-" + System.currentTimeMillis())
        return !dated.exists() && runCatching { dated.writeBytes(bytes) }.isSuccess && dated.length() == bytes.size.toLong()
    }
}
