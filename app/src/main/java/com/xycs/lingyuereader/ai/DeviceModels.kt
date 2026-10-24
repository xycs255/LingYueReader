package com.xycs.lingyuereader.ai

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本机（离线）模型：在手机上跑的小模型，写作 AI 和阅读 AI 都能用，不联网（下载模型时除外）。
 * 只收许可证允许随意使用的模型（Qwen2.5 是 Apache-2.0）；模型文件是 MediaPipe 的 .task 格式。
 * [maxTokens] 是模型能处理的上下文（输入加输出）。
 */
data class DeviceModel(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val maxTokens: Int,
    val note: String,
)

object DeviceModels {
    val ALL = listOf(
        DeviceModel(
            "qwen2.5-1.5b",
            "Qwen2.5 1.5B",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            1_598_556_720,
            "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3",
            4096,
            "约 1.6 GB。能润色、续写、概括、挑错，写整章也行但不如联网的大模型。建议 8 GB 内存以上的手机。",
        ),
        DeviceModel(
            "qwen2.5-0.5b",
            "Qwen2.5 0.5B",
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            546_660_344,
            "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
            1280,
            "约 550 MB，快但上下文很短：只适合挑错别字、概括一小段这类短活。",
        ),
    )

    /** 读者自己导入的模型：id 以这个开头，后面是文件名。 */
    const val FILE = "file:"

    fun of(id: String): DeviceModel? = ALL.firstOrNull { it.id == id }

    /** 导入的模型不知道上下文多长：按 4096 算（大多数 .task 是这么转的）。 */
    fun maxTokens(id: String): Int = of(id)?.maxTokens ?: 4096

    fun label(id: String): String = of(id)?.label ?: id.removePrefix(FILE).substringBeforeLast('.')

    /** 本机模型要 64 位的手机（ARM）：32 位的手机跑不动，也没带引擎。 */
    val supported: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
}

/** 下载、导入的阶段，给进度条用。 */
enum class DevicePhase(val label: String) { DOWNLOAD("下载中"), VERIFY("校验中"), IMPORT("导入中") }

class DeviceModelException(message: String) : Exception(message)

/**
 * 本机模型的文件：放在 [root] 下，一个模型一个文件。下载可断点续传、核对 SHA-256；也可以从读者选的 .task 文件导入。
 * 只在读者点了下载时联网。
 */
class DeviceModelFiles(
    private val root: File,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    /** 下载地址：测试时换成本机的假服务器。 */
    private val urlOf: (DeviceModel) -> String = { it.url },
) {
    fun file(id: String): File = File(root, of(id))

    private fun of(id: String): String = DeviceModels.of(id)?.fileName ?: id.removePrefix(DeviceModels.FILE)

    fun installed(id: String): Boolean = file(id).isFile && (DeviceModels.of(id)?.let { file(id).length() == it.bytes } ?: true)

    /** 装好的模型：目录里的预设模型和导入的 .task。 */
    fun installedIds(): List<String> {
        val presets = DeviceModels.ALL.filter { installed(it.id) }.map { it.id }
        val imported = root.listFiles()?.filter { f -> f.isFile && f.name.endsWith(".task") && DeviceModels.ALL.none { it.fileName == f.name } }
            ?.map { DeviceModels.FILE + it.name }.orEmpty()
        return presets + imported.sorted()
    }

    fun size(id: String): Long = file(id).length()

    fun delete(id: String) {
        file(id).delete()
        DeviceModels.of(id)?.let { part(it).delete() }
    }

    private fun part(m: DeviceModel) = File(root, m.fileName + ".part")

    /** 下载并装好。下到一半中断的，下次接着下。 */
    suspend fun download(m: DeviceModel, onProgress: (DevicePhase, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val need = m.bytes + 100_000_000
        if (root.usableSpace in 1 until need) throw DeviceModelException("手机空间不够：需要约 ${need / 1_000_000} MB")
        val part = part(m)
        if (part.length() > m.bytes) part.delete()
        if (part.length() < m.bytes) fetch(m, part, onProgress)
        onProgress(DevicePhase.VERIFY, 0, m.bytes)
        if (sha256(part) != m.sha256) {
            part.delete()
            throw DeviceModelException("下载的文件校验不对（可能下坏了），请重试")
        }
        if (!part.renameTo(file(m.id))) throw DeviceModelException("保存模型失败")
    }

    private suspend fun fetch(m: DeviceModel, part: File, onProgress: (DevicePhase, Long, Long) -> Unit) {
        val have = part.length()
        val req = Request.Builder().url(urlOf(m)).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val call = http.newCall(req)
        // 读网络会卡在 read() 里：另起一个等取消的子协程，一取消就断开连接。
        coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    throw DeviceModelException("下载失败：连不上 Hugging Face（${e.javaClass.simpleName}）。也可以用电脑下好后从文件导入")
                }
                response.use { r ->
                    if (!r.isSuccessful) throw DeviceModelException("下载失败：服务器返回 ${r.code}")
                    var done = if (r.code == 206) have else 0L
                    val body = r.body ?: throw DeviceModelException("下载失败：没有内容")
                    FileOutputStream(part, r.code == 206).use { out ->
                        body.byteStream().use { input -> copy(input, out, done, m.bytes) { done = it; onProgress(DevicePhase.DOWNLOAD, it, m.bytes) } }
                    }
                    if (done < m.bytes) throw DeviceModelException("下载中断，再点一次会接着下")
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    /** 从读者选的文件导入（要是 .task）。返回模型的 id。 */
    suspend fun import(name: String, open: () -> InputStream, size: Long, onProgress: (DevicePhase, Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val clean = name.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!clean.endsWith(".task", ignoreCase = true)) throw DeviceModelException("要选 .task 格式的模型文件（MediaPipe 的格式）")
        root.mkdirs()
        if (size > 0 && root.usableSpace in 1 until size + 50_000_000) throw DeviceModelException("手机空间不够")
        val preset = DeviceModels.ALL.firstOrNull { it.fileName == clean }
        val target = File(root, clean)
        val tmp = File(root, "$clean.import")
        try {
            open().use { input -> FileOutputStream(tmp).use { out -> copy(input, out, 0, size) { onProgress(DevicePhase.IMPORT, it, size) } } }
            if (!tmp.renameTo(target)) throw DeviceModelException("保存模型失败")
        } finally {
            tmp.delete()
        }
        preset?.id ?: (DeviceModels.FILE + clean)
    }

    private suspend fun copy(input: InputStream, out: FileOutputStream, start: Long, total: Long, progress: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = start
        var last = start
        while (true) {
            coroutineContext.ensureActive()
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw DeviceModelException("读取中断（${e.javaClass.simpleName}），再点一次会接着来")
            }
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done - last > 1_000_000) {
                last = done
                progress(done)
            }
        }
        progress(done)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 在手机上跑模型。测试时换成假的。 */
interface DeviceLlm {
    /** 生成回答：[onText] 收到一段就回调一段；返回全文。上下文放不下时，系统提示和请求的前半截会被截短。 */
    suspend fun generate(modelId: String, system: String, messages: List<AiMessage>, onText: (String) -> Unit): String

    /** 放掉模型占的内存（换了服务商、离开 AI 功能时）。 */
    fun release()
}

/** 把系统提示和对话拼成一段：上下文放不下时，先截系统提示（资料），再截请求的前面（正文前半截），要求留着。 */
internal object DevicePrompt {
    /** 一个汉字大约算几个 token（Qwen 的分词，中文大约 0.7 个字一个 token，按 1 个字 1 个 token 估，宁多勿少）。 */
    fun fit(system: String, messages: List<AiMessage>, maxTokens: Int, count: (String) -> Int = { it.length }): String {
        val reserve = maxOf(256, maxTokens / 3)
        val budget = maxTokens - reserve
        val turns = messages.joinToString("\n\n") { m -> (if (m.role == "assistant") "（之前的回答）" else "") + m.content }
        var sys = system
        var body = turns
        fun total() = count(sys) + count(body) + 8
        if (total() > budget) {
            val room = maxOf(0, budget - count(body) - 8)
            sys = if (room < 50) "" else sys.take(room) + "……（资料太长，后面省略）"
        }
        if (total() > budget) {
            val room = maxOf(100, budget - count(sys) - 8)
            body = "……（前面省略）" + body.takeLast(room)
        }
        return if (sys.isBlank()) body else sys + "\n\n" + body
    }
}

/** MediaPipe 的 LLM 推理（Apache-2.0）。一次只跑一个请求；模型第一次用时载入，换模型时换掉。 */
class MediaPipeLlm(private val context: Context, private val files: DeviceModelFiles) : DeviceLlm {
    private val lock = Mutex()
    private var engine: com.google.mediapipe.tasks.genai.llminference.LlmInference? = null
    private var loaded: String? = null

    private fun engine(modelId: String): com.google.mediapipe.tasks.genai.llminference.LlmInference {
        if (loaded == modelId) engine?.let { return it }
        release()
        val file = files.file(modelId)
        if (!files.installed(modelId)) throw AiException("本机模型还没下载：到“我的 → AI 助手 → 本机模型”里下载")
        val options = com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(DeviceModels.maxTokens(modelId))
            .setMaxTopK(40)
            .build()
        return try {
            com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(context, options).also {
                engine = it
                loaded = modelId
            }
        } catch (e: Throwable) {
            throw AiException("本机模型载入失败（手机内存可能不够）：${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun generate(modelId: String, system: String, messages: List<AiMessage>, onText: (String) -> Unit): String = lock.withLock {
        withContext(Dispatchers.Default) {
            if (!DeviceModels.supported) throw AiException("本机模型要 64 位的手机")
            val llm = engine(modelId)
            val prompt = DevicePrompt.fit(system, messages, DeviceModels.maxTokens(modelId)) { runCatching { llm.sizeInTokens(it) }.getOrDefault(it.length) }
            val session = com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.createFromOptions(
                llm,
                com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(40).setTemperature(0.8f).build(),
            )
            try {
                session.addQueryChunk(prompt)
                val out = StringBuilder()
                suspendCancellableCoroutine { cont ->
                    val future = session.generateResponseAsync { partial, done ->
                        if (!partial.isNullOrEmpty()) {
                            out.append(partial)
                            onText(partial)
                        }
                        if (done && cont.isActive) cont.resume(out.toString())
                    }
                    future.addListener({
                        val error = runCatching { future.get() }.exceptionOrNull()
                        if (error != null && cont.isActive) cont.resumeWithException(AiException("本机模型出错：${error.cause?.message ?: error.message}"))
                    }, Runnable::run)
                    cont.invokeOnCancellation { runCatching { session.cancelGenerateResponseAsync() } }
                }
            } finally {
                runCatching { session.close() }
            }
        }
    }

    override fun release() {
        runCatching { engine?.close() }
        engine = null
        loaded = null
    }
}
