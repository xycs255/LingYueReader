package com.xycs.lingyuereader.dict

import android.content.Context
import com.xycs.lingyuereader.data.Library
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一本导入的词典。 */
data class DictInfo(
    val id: String,
    val title: String,
    val mdx: String,
    val mdds: List<String>,
    /** 随词典一起导入的 .css/.js 等文件。 */
    val loose: List<String>,
    val entries: Long,
    val enabled: Boolean,
)

data class DictSettings(val builtinEnabled: Boolean = true, val dicts: List<DictInfo> = emptyList())

/** 一次查词的全部结果。 */
data class LookupResult(
    val word: String,
    /** 内置字典：词里每个字。 */
    val chars: List<CharEntry>,
    /** 内置词典：整词的释义。 */
    val words: List<WordEntry>,
    /** 以这个词开头的相关词语。 */
    val related: List<String>,
    /** 各本导入词典查到的（按词典顺序，没查到的不列）。 */
    val imported: List<Pair<DictInfo, List<MdictEntry>>>,
)

/**
 * 词典：内置的 Unihan 字典与 CC-CEDICT 词典，加上用户导入的 MDict 词典。
 * 导入的词典存放在 files/dicts/<id>/，每份 .mdx/.mdd 旁边有一份我们自己的排序索引。
 */
class Dictionaries(private val context: Context) {
    private val root = File(context.filesDir, "dicts").apply { mkdirs() }
    private val settingsFile = File(root, "dicts.json")
    private val opened = HashMap<String, MdictDictionary>()

    // ---------- 内置 ----------

    private var cedict: Cedict? = null
    private var unihan: Unihan? = null

    /**
     * 内置词库从安装包里复制出来一次；应用更新后重新复制（数据可能变了）。
     * 仓库里存的是 .tsv.gz，打包时 Android Gradle 插件会把 .gz 资源解压、去掉扩展名，所以这里读 .tsv。
     */
    @Synchronized
    private fun builtinCedict(): Cedict {
        cedict?.let { return it }
        val dir = File(root, "builtin").apply { mkdirs() }
        val target = File(dir, "cedict.tsv")
        val marker = File(dir, "cedict.version")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!target.isFile || marker.readTextOrNull() != stamp) {
            extract(context.assets.open("dict/cedict.tsv"), target)
            marker.writeText(stamp)
        }
        return Cedict(target).also { cedict = it }
    }

    @Synchronized
    private fun builtinUnihan(): Unihan =
        unihan ?: context.assets.open("dict/unihan.tsv").use { Unihan(it) }.also { unihan = it }

    // ---------- 设置与列表 ----------

    @Synchronized
    fun settings(): DictSettings {
        if (!settingsFile.isFile) return DictSettings()
        return runCatching {
            val o = JSONObject(settingsFile.readText())
            val arr = o.optJSONArray("dicts") ?: JSONArray()
            DictSettings(
                builtinEnabled = o.optBoolean("builtin", true),
                dicts = (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    DictInfo(
                        id = d.getString("id"),
                        title = d.getString("title"),
                        mdx = d.getString("mdx"),
                        mdds = d.optJSONArray("mdds").strings(),
                        loose = d.optJSONArray("loose").strings(),
                        entries = d.optLong("entries"),
                        enabled = d.optBoolean("enabled", true),
                    )
                },
            )
        }.getOrDefault(DictSettings())
    }

    @Synchronized
    private fun save(s: DictSettings) {
        val o = JSONObject().put("builtin", s.builtinEnabled).put("dicts", JSONArray().apply {
            s.dicts.forEach { d ->
                put(JSONObject().put("id", d.id).put("title", d.title).put("mdx", d.mdx)
                    .put("mdds", JSONArray(d.mdds)).put("loose", JSONArray(d.loose))
                    .put("entries", d.entries).put("enabled", d.enabled))
            }
        })
        val tmp = File(root, "dicts.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(settingsFile)
    }

    fun setBuiltinEnabled(enabled: Boolean) = save(settings().copy(builtinEnabled = enabled))

    fun setEnabled(id: String, enabled: Boolean) =
        save(settings().let { s -> s.copy(dicts = s.dicts.map { if (it.id == id) it.copy(enabled = enabled) else it }) })

    /** 调整查词结果里的先后。 */
    fun move(id: String, up: Boolean) {
        val s = settings()
        val list = s.dicts.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        save(s.copy(dicts = list))
    }

    @Synchronized
    fun remove(id: String) {
        opened.remove(id)
        File(root, id).deleteRecursively()
        save(settings().let { s -> s.copy(dicts = s.dicts.filterNot { it.id == id }) })
    }

    data class ImportResult(val added: List<String>, val failed: List<String>)

    /**
     * 导入词典文件：每个 .mdx 成为一本词典，同名的 .mdd（含 xxx.1.mdd 这类分卷）和 .css/.js 跟着它；
     * 只选了一个 .mdx 时，同批的样式文件都归它。
     */
    fun import(files: List<Library.Picked>, read: (Library.Picked) -> java.io.InputStream): ImportResult {
        fun base(name: String) = name.substringBefore('.').lowercase()
        val mdxs = files.filter { it.name.endsWith(".mdx", ignoreCase = true) }
        val added = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (mdxs.isEmpty()) return ImportResult(added, files.map { "${it.name}（需要 .mdx 文件）" })
        for (mdx in mdxs) {
            val id = UUID.randomUUID().toString()
            val dir = File(root, id).apply { mkdirs() }
            try {
                val companions = files.filter { f ->
                    f !== mdx && !f.name.endsWith(".mdx", true) && (mdxs.size == 1 || base(f.name) == base(mdx.name))
                }
                fun copy(p: Library.Picked): File {
                    val safe = p.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    return File(dir, safe).also { out -> read(p).use { input -> out.outputStream().use { input.copyTo(it) } } }
                }
                val mdxFile = copy(mdx)
                val file = MdictFile(mdxFile)
                MdictDictionary.buildIndex(file, File(dir, mdxFile.name + ".idx"), isResource = false)
                val mdds = companions.filter { it.name.endsWith(".mdd", true) }.map { p ->
                    val f = copy(p)
                    MdictDictionary.buildIndex(MdictFile(f, isMdd = true), File(dir, f.name + ".idx"), isResource = true)
                    f.name
                }
                val loose = companions.filterNot { it.name.endsWith(".mdd", true) }.map { copy(it).name }
                val info = DictInfo(id, file.title, mdxFile.name, mdds, loose, file.entryCount, true)
                save(settings().let { it.copy(dicts = it.dicts + info) })
                added += file.title
            } catch (e: Throwable) {
                dir.deleteRecursively()
                failed += "${mdx.name}（${e.message ?: "无法读取"}）"
            }
        }
        return ImportResult(added, failed)
    }

    @Synchronized
    private fun open(info: DictInfo): MdictDictionary? = opened[info.id] ?: runCatching {
        val dir = File(root, info.id)
        MdictDictionary.open(
            File(dir, info.mdx),
            File(dir, info.mdx + ".idx"),
            info.mdds.map { File(dir, it) to File(dir, "$it.idx") },
            info.loose.map { File(dir, it) },
        )
    }.getOrNull()?.also { opened[info.id] = it }

    // ---------- 查询 ----------

    /** 选词用：内置词库或任一本启用的导入词典里有这个词。 */
    fun isWord(word: String): Boolean {
        val s = settings()
        if (s.builtinEnabled && runCatching { builtinCedict().contains(word) }.getOrDefault(false)) return true
        return s.dicts.any { it.enabled && open(it)?.contains(word) == true }
    }

    fun lookup(word: String): LookupResult {
        val s = settings()
        // 划选了一长段时不逐字列字典。
        val chars = if (s.builtinEnabled && word.codePointCount(0, word.length) <= 8) {
            word.codePoints().toArray().map { String(Character.toChars(it)) }.distinct()
                .filter { WordFinder.isHan(it[0]) }.mapNotNull { builtinUnihan()[it] }
        } else {
            emptyList()
        }
        val words = if (s.builtinEnabled) builtinCedict().lookup(word) else emptyList()
        val related = if (s.builtinEnabled && word.length <= 4) builtinCedict().withPrefix(word, 12) else emptyList()
        val imported = s.dicts.filter { it.enabled }.mapNotNull { d ->
            open(d)?.lookup(word)?.takeIf { it.isNotEmpty() }?.let { d to it }
        }
        return LookupResult(word, chars, words, related, imported)
    }

    /** 释义 HTML 里引用的图片、样式。 */
    fun resource(dictId: String, path: String): ByteArray? =
        settings().dicts.firstOrNull { it.id == dictId }?.let(::open)?.resource(path)

    fun looseStyles(dictId: String): List<String> =
        settings().dicts.firstOrNull { it.id == dictId }?.loose?.filter { it.endsWith(".css", true) }.orEmpty()

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
