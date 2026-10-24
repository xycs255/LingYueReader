package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 设定包里的文档类型。[priority] 是默认优先级（数字小的优先：冲突时以它为准），
 * [always] 是默认是否“常用”（每次请 AI 都带上；“按需”的只在用到时带）。
 */
enum class PackKind(val label: String, val priority: Int, val always: Boolean, val hint: String) {
    OUTLINE("总纲", 1, true, "全书走向、主线、结局；冲突时最终以它为准"),
    INVARIANT("不变量", 2, true, "定下来就不能动的硬数字、生死身份、要回收的原话和物件"),
    VOLUME("卷纲细纲", 2, false, "每卷、每章的事实边界与钩子"),
    CANON("硬性设定", 3, false, "年代、制度、地理、势力、资源等事实"),
    CHARACTER("人物档案", 3, true, "人物卡：想要、害怕、怎么说话、标志物、怎么变"),
    AWARENESS("察觉表", 4, false, "谁从第几章起知道什么、消息从哪来"),
    STYLE("写作规范", 5, true, "怎么把事实写成小说：篇幅、对白、旁白、禁用词"),
    VOICE("称谓与语气", 5, false, "称谓边界、各人物说话的腔调"),
    RULES("检查规则", 5, false, "灵阅在本机逐章检查：篇幅、口头禅次数、禁用词和句式、段落长短"),
    TECHNIQUE("技法参考", 6, false, "学来的写法，只取方法不抄原文"),
    REFERENCE("资料", 7, false, "其他参考资料、体检报告、说明"),
}

/** 设定包里的一份文档。正文另存一个文件。 */
data class PackDoc(
    val id: String,
    val kind: PackKind,
    val title: String,
    val priority: Int = kind.priority,
    val always: Boolean = kind.always,
    val updatedAt: Long = 0,
    val words: Int = 0,
)

/** 导入时的一份文件：认成哪类、要不要导、是当设定还是当正文拆成章节。 */
data class PackImportItem(
    val fileName: String,
    val text: String,
    val kind: PackKind,
    val asChapters: Boolean = false,
    val include: Boolean = true,
    val priority: Int = kind.priority,
    val always: Boolean = kind.always,
    /** 导入后的标题：默认是去掉文件夹和扩展名的文件名。 */
    val title: String = fileName.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { fileName },
)

/** 导入的结果：新加几份、替换几份（旧版进历史）、导入几章正文。 */
data class PackImportResult(val added: Int, val replaced: Int, val chapters: Int)

object PackImport {
    /** 认得的文本文件。 */
    fun isText(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "txt", "text")

    private val rules: List<Pair<Regex, PackKind>> = listOf(
        Regex("检查规则|自查清单|口癖清单") to PackKind.RULES,
        Regex("不变量") to PackKind.INVARIANT,
        Regex("察觉|知情") to PackKind.AWARENESS,
        // 称谓、语气放在人物前面：“对白与人物语气”是语气规范，不是人物档案。
        Regex("称谓|对白|语气|口吻|腔调") to PackKind.VOICE,
        Regex("人物|角色|人设") to PackKind.CHARACTER,
        Regex("技法|样板|摘要") to PackKind.TECHNIQUE,
        Regex("约定|规范|写作要求|文风") to PackKind.STYLE,
        Regex("细纲|卷纲|分章|章组|因果") to PackKind.VOLUME,
        Regex("接续|索引") to PackKind.INVARIANT,
        Regex("总纲|大纲") to PackKind.OUTLINE,
        Regex("硬性|设定|世界观|战役|地图|制度|势力|年表") to PackKind.CANON,
    )

    /** 正文（已定正文、正文第几章）：导入成章节，不当设定。 */
    private val chapterFile = Regex("正文|第\\s*[0-9０-９一二三四五六七八九十百]+\\s*[-—–~～至到]\\s*[0-9０-９一二三四五六七八九十百]+\\s*章")

    /** 按文件名认类型。写手版、速查这类压缩版比原件低一级。 */
    fun classify(fileName: String, text: String = ""): PackImportItem {
        val name = fileName.substringAfterLast('/').substringBeforeLast('.')
        if (chapterFile.containsMatchIn(name) && !Regex("约定|规范|写法").containsMatchIn(name)) {
            return PackImportItem(fileName, text, PackKind.REFERENCE, asChapters = true)
        }
        val kind = rules.firstOrNull { (r, _) -> r.containsMatchIn(name) }?.second ?: PackKind.REFERENCE
        val lower = Regex("写手版|速查|简版|压缩").containsMatchIn(name)
        return PackImportItem(fileName, text, kind, priority = kind.priority + if (lower) 1 else 0)
    }

    /** 正文按章名拆开：Markdown 标题或“第X章”“楔子”这类单独成行的章名。没有章名时整份当一章。 */
    fun chapters(text: String, fallbackTitle: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            val t = title
            val b = body.toString().trim()
            if (t != null) out += t to b else if (b.isNotEmpty()) out += fallbackTitle to b
            body.setLength(0)
        }
        for (raw in text.removePrefix("\uFEFF").lines()) {
            val line = raw.trim().trim('　').trim()
            val heading = Regex("""^#{1,4}\s+(.+)$""").matchEntire(line)?.groupValues?.get(1)?.trim()
            val isTitle = heading?.let { ChapterTitles.isTxtChapterTitle(it) || it.startsWith("第") } ?: ChapterTitles.isTxtChapterTitle(line)
            if (isTitle) {
                flush()
                title = heading ?: line
                continue
            }
            // 文件头的一级标题（文件名、说明）不当正文。
            if (heading != null && title == null) continue
            if (line.isEmpty()) continue
            body.append(line.removePrefix("> ")).append('\n')
        }
        flush()
        return out.filter { it.second.isNotBlank() || it.first.isNotBlank() }
    }

    // ---------- 打包 ----------

    private const val MANIFEST = "pack.json"

    /** 设定包打成 zip：每份文档一个 .md，外加 pack.json 记类型、优先级、常用与否，导回来原样还原。 */
    fun writeZip(docs: List<Pair<PackDoc, String>>, out: OutputStream) {
        val used = HashSet<String>()
        val manifest = JSONArray()
        ZipOutputStream(out).use { zip ->
            docs.forEach { (doc, text) ->
                val base = doc.title.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "未命名" }
                var name = "$base.md"
                var n = 2
                while (!used.add(name)) name = "$base（${n++}）.md"
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
                manifest.put(JSONObject().put("file", name).put("title", doc.title).put("kind", doc.kind.name).put("priority", doc.priority).put("always", doc.always))
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(JSONObject().put("format", 1).put("docs", manifest).toString(2).toByteArray())
            zip.closeEntry()
        }
    }

    /** 读 zip 里的文本文件；有 pack.json 时按它还原类型和优先级。压缩包的文件名先按 UTF-8 读，读不出再按 GBK（Windows 上打的包）。 */
    fun readZip(open: () -> InputStream): List<PackImportItem> {
        val files = linkedMapOf<String, ByteArray>()
        var manifest: JSONObject? = null
        fun read(charset: Charset) {
            files.clear()
            ZipInputStream(open(), charset).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    val base = name.substringAfterLast('/')
                    if (base.startsWith(".") || name.startsWith("__MACOSX")) continue
                    when {
                        base == MANIFEST -> manifest = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                        isText(base) -> files[name] = zip.readBytes()
                    }
                }
            }
        }
        try {
            read(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            read(Charset.forName("GBK"))
        }
        val meta = manifest?.optJSONArray("docs")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("file") } }.orEmpty()
        return files.map { (name, bytes) ->
            val text = TextDecoder.decode(bytes)
            val m = meta[name.substringAfterLast('/')]
            if (m != null) {
                val kind = runCatching { PackKind.valueOf(m.optString("kind")) }.getOrDefault(PackKind.REFERENCE)
                val item = PackImportItem(name, text, kind, priority = m.optInt("priority", kind.priority), always = m.optBoolean("always", kind.always))
                m.optString("title").takeIf { it.isNotBlank() }?.let { item.copy(title = it) } ?: item
            } else {
                classify(name, text)
            }
        }.sortedBy { it.fileName }
    }

    /** 新建文档时的模板：人物档案给人物卡的五问，写作规范给常用的几条，检查规则给写法示例，其余空着。 */
    fun template(kind: PackKind): String = when (kind) {
        PackKind.CHARACTER -> "## 人物名\n想要：\n害怕：\n怎么说话：\n标志物：\n怎么变：\n"
        PackKind.STYLE -> "## 篇幅\n\n## 对白\n\n## 旁白\n\n## 禁用词与口癖\n"
        PackKind.INVARIANT -> "## 硬数字\n\n## 生死与身份\n\n## 要回收的原话与物件\n\n## 允许改动的范围\n"
        PackKind.AWARENESS -> "| 人物 | 知道什么 | 从第几章起 | 消息从哪来 |\n| --- | --- | --- | --- |\n"
        PackKind.OUTLINE -> "## 一句话\n\n## 主线\n\n## 结局\n"
        PackKind.RULES -> CheckRules.TEMPLATE
        else -> ""
    }
}

/** 请 AI 时带上的设定：常用的文档按优先级排好，超出字数就按份数平分、截断。 */
object PackContext {
    const val LIMIT = 8_000

    fun build(docs: List<Pair<PackDoc, String>>, limit: Int = LIMIT): String? {
        val picked = docs.filter { (d, t) -> d.always && t.isNotBlank() }.sortedWith(compareBy({ it.first.priority }, { it.first.kind.ordinal }, { it.first.title }))
        if (picked.isEmpty()) return null
        val out = StringBuilder("下面是作品的设定资料，按优先级排列（数字小的优先）；资料之间有冲突时，以优先级高的为准。\n")
        var left = limit
        picked.forEachIndexed { i, (doc, text) ->
            if (left <= 200) {
                out.append("（还有 ${picked.size - i} 份资料因为太长没有带上）\n")
                return out.toString().trim()
            }
            val quota = left / (picked.size - i)
            val body = text.trim()
            val cut = if (body.length > quota) body.take(quota) + "……（后面省略）" else body
            out.append("\n【").append(doc.kind.label).append("·").append(doc.title).append("（优先级 ").append(doc.priority).append("）】\n").append(cut).append('\n')
            left -= cut.length
        }
        return out.toString().trim()
    }
}
