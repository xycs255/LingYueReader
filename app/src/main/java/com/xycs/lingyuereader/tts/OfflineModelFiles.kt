package com.xycs.lingyuereader.tts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 装模型的几个阶段，给进度条用。 */
enum class OfflinePhase(val label: String) { DOWNLOAD("下载中"), VERIFY("校验中"), EXTRACT("解压中") }

/**
 * 离线语音模型的文件：下载（可断点续传、核对 SHA-256）、从读者选的文件导入、解压、删除。
 * 模型装在 [root]/模型名/ 下，装好后写一个标记文件；没装完的不算数。只在读者点了下载或导入时联网。
 */
class OfflineModelFiles(
    private val root: File,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    /** 下载地址：测试时换成本机的假服务器。 */
    private val urlOf: (OfflineModel) -> String = { it.url },
) {
    fun dir(m: OfflineModel): File = File(root, m.id)

    fun installed(m: OfflineModel): Boolean = File(dir(m), READY).isFile

    fun installedIds(): Set<String> = OfflineModels.ALL.filter(::installed).map { it.id }.toSet()

    /** 装好的模型占多大地方。 */
    fun sizeOnDisk(m: OfflineModel): Long = dir(m).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun delete(m: OfflineModel) {
        dir(m).deleteRecursively()
        File(root, m.id + TMP).deleteRecursively()
        part(m).delete()
    }

    private fun part(m: OfflineModel) = File(root, "${m.id}.tar.bz2.part")

    /** 下载并装好。下到一半中断的，下次接着下。[onProgress] 在后台线程回调（已完成、总数）。 */
    suspend fun download(m: OfflineModel, onProgress: (OfflinePhase, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val need = m.bytes * 3
        if (root.usableSpace in 1 until need) throw OfflineVoiceException("手机空间不够：需要约 ${need / 1_000_000} MB")
        val part = part(m)
        if (part.length() > m.bytes) part.delete()
        if (part.length() < m.bytes) fetch(m, part, onProgress)
        onProgress(OfflinePhase.VERIFY, 0, m.bytes)
        if (sha256(part) != m.sha256) {
            part.delete()
            throw OfflineVoiceException("下载的文件校验不对（可能下坏了），请重试")
        }
        part.inputStream().use { install(m, it, m.bytes, onProgress) }
        part.delete()
    }

    private suspend fun fetch(m: OfflineModel, part: File, onProgress: (OfflinePhase, Long, Long) -> Unit) {
        val have = part.length()
        val req = Request.Builder().url(urlOf(m)).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val call = http.newCall(req)
        // 读网络会卡在 read() 里，协程取消管不到它：另起一个等取消的子协程，一取消就断开连接，read() 马上出错返回。
        coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                fetchBody(call, m, part, have, onProgress)
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun fetchBody(call: okhttp3.Call, m: OfflineModel, part: File, have: Long, onProgress: (OfflinePhase, Long, Long) -> Unit) {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw OfflineVoiceException("下载失败：连不上 GitHub（${e.javaClass.simpleName}）。也可以用电脑下好后从文件导入")
        }
        response.use { r ->
            val append = r.code == 206
            if (!r.isSuccessful) throw OfflineVoiceException("下载失败：服务器返回 ${r.code}")
            var done = if (append) have else 0L
            val body = r.body ?: throw OfflineVoiceException("下载失败：没有内容")
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = try {
                            input.read(buf)
                        } catch (e: IOException) {
                            // 是读者取消的（连接被断开），就按取消处理，不报“下载中断”。
                            coroutineContext.ensureActive()
                            throw OfflineVoiceException("下载中断（${e.javaClass.simpleName}），再点一次会接着下")
                        }
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) {
                            last = done
                            onProgress(OfflinePhase.DOWNLOAD, done, m.bytes)
                        }
                    }
                }
            }
            if (done < m.bytes) throw OfflineVoiceException("下载中断，再点一次会接着下")
        }
    }

    /**
     * 从读者选的文件导入：看模型包里的目录名认出是哪个模型。[open] 要能打开两次（先认、再装）。
     * 返回装好的模型。
     */
    suspend fun import(open: () -> InputStream, size: Long, onProgress: (OfflinePhase, Long, Long) -> Unit): OfflineModel = withContext(Dispatchers.IO) {
        val top = try {
            open().use { input -> tar(input).use { t -> t.nextEntry?.name?.trimStart('.', '/')?.substringBefore('/') } }
        } catch (e: IOException) {
            null
        }
        val m = OfflineModels.of(top) ?: throw OfflineVoiceException(
            "不认识这个文件。请选 sherpa-onnx 的模型包：${OfflineModels.ALL.joinToString("、") { it.id + ".tar.bz2" }}",
        )
        root.mkdirs()
        open().use { install(m, it, size.takeIf { s -> s > 0 } ?: m.bytes, onProgress) }
        m
    }

    /** 解压到临时目录，检查齐全后换上。只解模型目录下的普通文件，挡掉“../”之类跑出目录的路径。 */
    private suspend fun install(m: OfflineModel, raw: InputStream, total: Long, onProgress: (OfflinePhase, Long, Long) -> Unit) {
        val tmp = File(root, m.id + TMP)
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            extract(m, raw, tmp, total, onProgress)
        } finally {
            // 出错、被取消时不留下解了一半的文件（可能有两百 MB）。
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    private suspend fun extract(m: OfflineModel, raw: InputStream, tmp: File, total: Long, onProgress: (OfflinePhase, Long, Long) -> Unit) {
        val base = tmp.canonicalPath + File.separator
        val counted = Counting(raw)
        try {
            tar(counted).use { t ->
                var last = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val e = t.nextEntry ?: break
                    val rel = e.name.trimStart('.', '/').removePrefix(m.id + "/")
                    if (rel == e.name.trimStart('.', '/') || rel.isEmpty()) continue
                    if (m.skip.any { s -> rel == s.trimEnd('/') || (s.endsWith("/") && rel.startsWith(s)) }) continue
                    val target = File(tmp, rel)
                    if (!target.canonicalPath.startsWith(base)) continue
                    when {
                        e.isDirectory -> target.mkdirs()
                        e.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { t.copyTo(it, 64 * 1024) }
                        }
                    }
                    if (counted.count - last > 512 * 1024) {
                        last = counted.count
                        onProgress(OfflinePhase.EXTRACT, counted.count, total)
                    }
                }
            }
        } catch (e: IOException) {
            tmp.deleteRecursively()
            throw OfflineVoiceException("解压失败：文件不完整或不是 .tar.bz2 模型包")
        }
        val missing = m.required.filterNot { File(tmp, it.trimEnd('/')).exists() }
        if (missing.isNotEmpty()) {
            tmp.deleteRecursively()
            throw OfflineVoiceException("模型包不完整，缺少 ${missing.joinToString("、")}")
        }
        File(tmp, READY).writeText(m.sha256)
        val dir = dir(m)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) {
            tmp.deleteRecursively()
            throw OfflineVoiceException("安装失败：无法写入模型目录")
        }
    }

    private fun tar(input: InputStream) = TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input, 64 * 1024)))

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 数读了多少压缩字节，解压进度按它算。 */
    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object {
        const val READY = ".ready"
        private const val TMP = ".installing"
    }
}
