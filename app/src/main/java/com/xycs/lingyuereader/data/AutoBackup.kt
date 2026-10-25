package com.xycs.lingyuereader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 放备份的文件夹。正式用系统文件选择器选的文件夹（[SafFolder]），测试用普通目录（[FileFolder]）。 */
interface BackupFolder {
    fun list(): List<String>
    fun write(name: String, block: (OutputStream) -> Unit)
    fun rename(from: String, to: String)
    fun delete(name: String)
}

/**
 * 自动备份：每天把备份 zip 写进用户选的文件夹（可以是云盘、Syncthing 等同步目录），只留最近几份。
 * 灵阅没有网络权限，换手机、多台设备之间靠这个文件夹带过去，在另一台上“从备份恢复”。
 *
 * 文件夹的地址只存在本机（单独的设置文件），不随备份走：换到别的手机上那个授权是无效的。
 */
class AutoBackup(
    context: Context,
    private val folderOf: (String) -> BackupFolder = { SafFolder(context, it.toUri()) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sp = context.getSharedPreferences("auto-backup", Context.MODE_PRIVATE)

    data class State(
        /** 文件夹地址；为空表示没开自动备份。 */
        val folder: String? = null,
        /** 文件夹的名字，显示用。 */
        val label: String = "",
        val lastAt: Long = 0,
        val lastFile: String = "",
        val lastError: String = "",
    ) {
        val enabled: Boolean get() = folder != null
    }

    fun state(): State = State(
        folder = sp.getString("folder", null),
        label = sp.getString("label", "").orEmpty(),
        lastAt = sp.getLong("lastAt", 0),
        lastFile = sp.getString("lastFile", "").orEmpty(),
        lastError = sp.getString("lastError", "").orEmpty(),
    )

    /** 选了新文件夹（或传空关闭）。换文件夹后下次立刻备份。 */
    fun setFolder(folder: String?, label: String) = sp.edit {
        if (folder == null) remove("folder") else putString("folder", folder)
        putString("label", label)
        putLong("lastAt", 0)
        remove("lastError")
    }

    /** 开着、且离上次成功备份满一天。 */
    fun due(): Boolean {
        val s = state()
        return s.enabled && now() - s.lastAt >= DAY
    }

    /**
     * 备份一次：先写入本次独有的“.part”，完成后改名；失败时只清理本次临时文件。
     * 同一分钟再备份时使用递增编号，避免先删除上一份可用备份；最后只保留最近几份。
     * 文件名用自动备份独有的前缀（[PREFIX]），清理只删这类：同一文件夹里的手动备份默认叫“灵阅备份-…”，不能被当成旧的自动备份删掉。
     * 旧版自动备份也叫“灵阅备份-…”，和手动备份分不开，不再自动删，由用户自己清理。
     * 返回写好的文件名。
     */
    fun run(write: (OutputStream) -> Unit): Result<String> {
        val folderUri = state().folder ?: return Result.failure(IllegalStateException("没有选备份文件夹"))
        return runCatching {
            val folder = folderOf(folderUri)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(now()))
            val stem = "$PREFIX$stamp"
            val latestCopy = folder.list().mapNotNull { file ->
                BACKUP_NAME.matchEntire(file)?.let { match ->
                    if (match.groupValues[1] == stamp) match.groupValues[2].toLongOrNull() ?: 1L else null
                }
            }.maxOrNull() ?: 0L
            val copy = latestCopy + 1
            val name = if (copy == 1L) "$stem.zip" else "$stem-${copy.toString().padStart(4, '0')}.zip"
            val part = "$name.${UUID.randomUUID()}.part"
            try {
                folder.write(part, write)
                folder.rename(part, name)
            } catch (e: Throwable) {
                runCatching { folder.delete(part) }
                throw e
            }
            // 上次备份到一半应用被系统停掉留下的“.part”：这次备份成功了就清掉，不然会一直堆在同步文件夹里。
            folder.list().filter { it != part && STALE_PART.matches(it) }.forEach { runCatching { folder.delete(it) } }
            folder.list().mapNotNull { file -> BACKUP_NAME.matchEntire(file)?.let { file to it } }
                .sortedWith(compareByDescending<Pair<String, MatchResult>> { it.second.groupValues[1] }
                    .thenByDescending { it.second.groupValues[2].toLongOrNull() ?: 1L })
                .drop(KEEP)
                .forEach { runCatching { folder.delete(it.first) } }
            name
        }.onSuccess { name ->
            sp.edit {
                putLong("lastAt", now())
                putString("lastFile", name)
                remove("lastError")
            }
        }.onFailure { e ->
            sp.edit { putString("lastError", e.message ?: "写不进去") }
        }
    }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L

        /** 留最近几份。 */
        const val KEEP = 7

        /** 自动备份的文件名前缀，和手动备份（默认“灵阅备份-”）分开。 */
        const val PREFIX = "灵阅自动备份-"
        val BACKUP_NAME = Regex("""$PREFIX(\d{8}-\d{4})(?:-(\d{4,}))?\.zip""")
        /** 备份到一半留下的临时文件；带本次独有的 UUID，不会是手动备份。旧版前缀的也一起清掉。 */
        private val STALE_PART = Regex("""(?:$PREFIX|灵阅备份-)\d{8}-\d{4}(?:-\d{4,})?\.zip\.[0-9a-fA-F-]{36}\.part""")
    }
}

/** 系统文件选择器（OPEN_DOCUMENT_TREE）选的文件夹。 */
class SafFolder(private val context: Context, private val tree: Uri) : BackupFolder {
    private val resolver get() = context.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    private fun children(): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val result = LinkedHashMap<String, String>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) result[c.getString(1)] = c.getString(0)
        }
        return result
    }

    private fun docUri(name: String): Uri? = children()[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }

    override fun list(): List<String> = children().keys.toList()

    override fun write(name: String, block: (OutputStream) -> Unit) {
        val uri = DocumentsContract.createDocument(resolver, root, "application/octet-stream", name) ?: error("文件夹里建不了新文件")
        requireNotNull(resolver.openOutputStream(uri)) { "文件夹写不进去" }.use(block)
    }

    override fun rename(from: String, to: String) {
        val uri = docUri(from) ?: error("找不到 $from")
        DocumentsContract.renameDocument(resolver, uri, to) ?: error("改不了文件名")
    }

    override fun delete(name: String) {
        docUri(name)?.let { DocumentsContract.deleteDocument(resolver, it) }
    }

    companion object {
        /** 记住授权，重启后仍能写；返回文件夹的名字。 */
        fun remember(context: Context, tree: Uri): String {
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return runCatching {
                context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':').orEmpty()
        }

        fun forget(context: Context, tree: Uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }
}

/** 普通目录，测试用。 */
class FileFolder(private val dir: File) : BackupFolder {
    override fun list(): List<String> = dir.list()?.toList().orEmpty()
    override fun write(name: String, block: (OutputStream) -> Unit) = File(dir, name).outputStream().use(block)
    override fun rename(from: String, to: String) {
        check(File(dir, from).renameTo(File(dir, to))) { "改不了文件名" }
    }
    override fun delete(name: String) {
        File(dir, name).delete()
    }
}

/**
 * 读者选的文件夹里的文本和压缩包（导入设定包用）：往下找几层子文件夹，返回（相对路径，文档地址）。
 * 只看 .md、.txt、.zip，别的文件不读。
 */
object SafTree {
    fun files(context: Context, tree: Uri, maxDepth: Int = 4): List<Pair<String, Uri>> {
        val out = mutableListOf<Pair<String, Uri>>()
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        fun walk(docId: String, path: String, depth: Int) {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val rel = if (path.isEmpty()) name else "$path/$name"
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < maxDepth) walk(id, rel, depth + 1)
                    } else if (PackImport.isText(name) || name.endsWith(".zip", ignoreCase = true)) {
                        out += rel to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out
    }
}
