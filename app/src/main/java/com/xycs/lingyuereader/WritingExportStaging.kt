package com.xycs.lingyuereader

import java.io.File
import java.io.OutputStream

/** Finish and close a writing export before opening a user-selected document in truncate mode. */
internal suspend fun stagedWritingExport(
    cacheDir: File,
    openTarget: () -> OutputStream?,
    produce: suspend (OutputStream) -> Unit,
) {
    val staged = File.createTempFile("writing-export-", ".tmp", cacheDir)
    try {
        staged.outputStream().use { produce(it) }
        val target = openTarget() ?: throw IllegalArgumentException("无法写入文件")
        target.use { out -> staged.inputStream().use { it.copyTo(out) } }
    } finally {
        staged.delete()
    }
}
