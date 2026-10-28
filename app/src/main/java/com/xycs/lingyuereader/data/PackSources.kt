package com.xycs.lingyuereader.data

import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 读出来的一份设定包，连同解析的结果：按阶段筛过的资料（[forPhase]）、版本差异提示（[warnings]）、是不是项目包（[project]）、
 * 审读指纹用的资料（[fingerprintSources]）、细纲段落（[outlineEntries]）、挑好的本章资料（[brief]）。
 *
 * 为什么要记：编一次本章任务以前要把整个设定包读一遍、合订本拆行逐行过正则拆四遍、按阶段筛四遍，再挑一遍本章资料。
 * 作者一百多万字的设定包编一次要分配八百多 MB，草稿页打一个字又会编两三次，几次叠在一起把 256 MB 的堆撑爆。
 * 设定包没变（[version] 一样）就用这一份，变了整份重算。每部作品只记当前这一份（见 [PackSourceCache]）。
 */
class PackSources internal constructor(
    val workId: String,
    internal val version: List<Any>,
    /** 设定包里的文档和正文，顺序同 [WorkStore.pack]。 */
    val raw: List<Pair<PackDoc, String>>,
) {
    /** 每份文档拆开合订本的结果，只拆一次。 */
    private val split: List<List<Pair<PackDoc, String>>> by lazy { raw.map { (d, t) -> ProjectSources.pieces(d, t) } }
    private val expanded: List<Pair<PackDoc, String>> by lazy { ProjectSources.expand(raw, split) }
    private val phases = HashMap<WritingPhase, List<Pair<PackDoc, String>>>()

    fun forPhase(phase: WritingPhase): List<Pair<PackDoc, String>> =
        synchronized(phases) { phases[phase] } ?: ProjectSources.select(expanded, phase).let { fresh ->
            synchronized(phases) { phases.getOrPut(phase) { fresh } }
        }

    val warnings: List<String> by lazy { ProjectSources.warnings(split.flatten()) }
    val project: Boolean by lazy { ProjectSources.hasProject(raw) }
    val fingerprintSources: List<Pair<PackDoc, String>> by lazy { ProjectSources.fingerprintOf(::forPhase) }
    val outlineEntries: List<OutlineEntry> by lazy { outlineEntriesOf(workId, forPhase(WritingPhase.CREATE)) }

    /** 挑本章资料用的切块、二元组、人物表：跟着这一版设定包走，每次挑资料不再整包重算。 */
    val prep = BriefPrep()

    /** 挑好的本章资料：同样的参数（[key] 里是挑资料用到的全部输入）只挑一次。最多记几份，旧的丢掉。 */
    private val briefs = object : LinkedHashMap<List<Any?>, ChapterBrief>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, ChapterBrief>?) = size > BRIEFS
    }

    fun brief(key: List<Any?>, build: () -> ChapterBrief): ChapterBrief =
        synchronized(briefs) { briefs[key] } ?: build().also { fresh -> synchronized(briefs) { briefs[key] = fresh } }

    companion object {
        private const val BRIEFS = 6

        /** 最近几份（弱引用）：传进来的列表就是某份的 [raw] 时，[ProjectSources] 直接用它算好的结果。 */
        private val recent = ArrayDeque<WeakReference<PackSources>>()

        internal fun remember(sources: PackSources) = synchronized(recent) {
            recent.removeAll { it.get().let { s -> s == null || s === sources } }
            recent.addFirst(WeakReference(sources))
            while (recent.size > 4) recent.removeLast()
        }

        /** 只认同一个列表对象（不比内容）：内容一样的另一份列表照常现算。 */
        internal fun of(docs: List<Pair<PackDoc, String>>): PackSources? = synchronized(recent) {
            recent.firstNotNullOfOrNull { ref -> ref.get()?.takeIf { it.raw === docs } }
        }
    }
}

/**
 * 每个 [WorkStore] 一个：只记当前作品的一份设定包（换了作品或设定包变了就换掉旧的），不会越攒越多。
 * [compiles] 数本章任务编了几次、[reads] 数设定包整份读了几次，测试拿来看有没有重复编译。
 */
internal class PackSourceCache {
    @Volatile private var current: PackSources? = null
    val compiles = AtomicInteger()
    val reads = AtomicInteger()

    fun cached(workId: String, version: List<Any>): PackSources? = current?.takeIf { it.workId == workId && it.version == version }

    /** 每部作品一把锁：同一部作品的设定包同时只读一份（打开草稿页时判断项目包和编本章任务几乎同时，以前各读一整份）。 */
    private val reading = HashMap<String, Any>()

    fun readLock(workId: String): Any = synchronized(reading) { reading.getOrPut(workId) { Any() } }

    /** 读文档放在锁外（读的时候要拿 [WorkStore] 的锁），读完再放进来；两边同时读了同一版，留先放进来的那份。 */
    fun put(fresh: PackSources): PackSources = synchronized(this) {
        cached(fresh.workId, fresh.version) ?: fresh.also { current = it; PackSources.remember(it) }
    }

    /** 放掉记着的设定包（系统内存紧张、应用退到后台时）：下次用到再读。 */
    fun clear() { current = null }
}

/**
 * 字符串用 [sep] 连起来以后的 UTF-8 SHA-256，和 `parts.joinToString(sep).toByteArray()` 再算的一字不差，
 * 但不拼出整串、不整串转字节（设定包一百多万字时，拼一次就是十几 MB）。孤立的代理字符照 [String.toByteArray] 换成“?”。
 */
internal fun sha256Joined(parts: Sequence<String>, sep: String = "\u0000"): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    val chars = CharBuffer.allocate(8_192)
    val bytes = ByteBuffer.allocate(8_192 * 3 + 16)
    fun drain(end: Boolean) {
        chars.flip()
        while (true) {
            val result = encoder.encode(chars, bytes, end)
            bytes.flip(); digest.update(bytes); bytes.clear()
            if (result.isUnderflow) break
        }
        // 没编完的（一个高代理等它的另一半）留到下一段。
        chars.compact()
    }
    fun feed(s: String) {
        var at = 0
        while (at < s.length) {
            val n = minOf(chars.remaining(), s.length - at)
            chars.put(s, at, at + n)
            at += n
            if (!chars.hasRemaining()) drain(false)
        }
    }
    var first = true
    for (part in parts) {
        if (!first) feed(sep)
        first = false
        feed(part)
    }
    drain(true)
    while (!encoder.flush(bytes).isUnderflow) { bytes.flip(); digest.update(bytes); bytes.clear() }
    bytes.flip(); digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}
