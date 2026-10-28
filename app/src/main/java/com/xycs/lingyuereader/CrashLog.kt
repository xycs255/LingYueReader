package com.xycs.lingyuereader

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.xycs.lingyuereader.data.DurableFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闪退记录：灵阅意外退出时，把出错的位置（堆栈）、时间、版本、当时在哪个界面记进 app 私有目录的一个文件，
 * 以后好查；作者可以在“我的 → 闪退记录”里看、复制了发给开发者。
 *
 * - 闪退（没接住的异常）留最近 [KEEP] 次；下次打开时问一次要不要复制（[unseen]）。
 * - 写作 AI 这类地方接住了、没让 app 闪退的意外（内存不够、栈溢出、下标越界这类程序的毛病）也记，另留最近 [KEEP] 条，
 *   同样的错只记一条、数次数：真机上复现不了的毛病，靠它查。
 * - 记录里不带正文：只记异常的类名和出错的代码位置；错误说明里的汉字（可能是正文、设定）一律隐去，只留字数。
 */
object CrashLog {
    /** 闪退、接住的意外，各留最近几条。 */
    const val KEEP = 3

    /** 一条记录：[at] 是时间，[fatal] 为真是闪退（假是接住了的意外），[count] 是同样的意外接住了几次，[seen] 是问过作者了。 */
    data class Entry(
        val at: Long,
        val version: String,
        val screen: String,
        val thread: String,
        val fatal: Boolean,
        val trace: String,
        val count: Int = 1,
        val seen: Boolean = false,
    )

    @Volatile private var store: CrashLogFile? = null

    /** 版本号（[install] 时读一次）。 */
    @Volatile var version: String = ""
        private set

    /** 当时在哪个界面：界面每换一次记一下，只记界面的名字（不带书名、章名、正文）。 */
    @Volatile var screen: String = ""

    /** 当时在忙什么（AI 在写正文这类）：出事时问一句，只要几个字的说明。 */
    @Volatile var busy: () -> String = { "" }

    /**
     * 在 Application 里装：没接住的异常先记下来，再交给系统原来的处理器（照常闪退、照常弹系统的提示）。
     * 记录写在 filesDir/crash/ 下，不进系统备份。
     */
    fun install(context: Context) {
        use(File(context.filesDir, "crash/$FILE"))
        version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val installed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(info.lastUpdateTime))
            "${info.versionName}（${PackageInfoCompat.getLongVersionCode(info)}，安装于 $installed）"
        }.getOrDefault("")
        // 一个进程只接一次（测试里每个用例都新建 Application，不能一层层套下去）；记到哪个文件按最近一次装的。
        synchronized(this) {
            if (handler != null) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            handler = Thread.UncaughtExceptionHandler { thread, e ->
                runCatching { store?.add(entry(e, thread, fatal = true)) }
                if (previous != null) previous.uncaughtException(thread, e)
            }
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }

    @Volatile private var handler: Thread.UncaughtExceptionHandler? = null

    /** 记到哪个文件（[install] 用；测试里直接指一个临时文件，为空是不记）。 */
    internal fun use(file: File?) {
        store = file?.let(::CrashLogFile)
    }

    /** 记一条接住了的意外（不会闪退的那种）。没装（单元测试里）就不记。 */
    fun caught(e: Throwable) {
        val s = store ?: return
        runCatching { s.add(entry(e, Thread.currentThread(), fatal = false)) }
    }

    /** 全部记录，新的在前。 */
    fun entries(): List<Entry> = store?.read().orEmpty()

    /** 上次闪退了、还没问过作者的那一条（最新的）；没有为空。 */
    fun unseen(): Entry? = entries().firstOrNull { it.fatal && !it.seen }

    /** 问过了：以后不再弹。 */
    fun markSeen() {
        runCatching { store?.markSeen() }
    }

    /** 清空记录。 */
    fun clear() {
        runCatching { store?.clear() }
    }

    /**
     * 是不是程序本身的毛病（不是网络、服务商、写盘、资料不全这类平常的出错）：
     * 下标越界、空指针、类型不对、并发改了集合、内存不够、栈溢出……这些要记下来查。
     */
    fun isBug(e: Throwable): Boolean = e !is Exception || e is IndexOutOfBoundsException || e is NullPointerException ||
        e is ClassCastException || e is ConcurrentModificationException || e is NoSuchElementException ||
        e is ArithmeticException || e is UnsupportedOperationException || e is NumberFormatException

    /** 复制给开发者的文字：时间、版本、界面、线程、堆栈。 */
    fun report(e: Entry): String = buildString {
        append(if (e.fatal) "灵阅闪退记录" else "灵阅接住的意外").append('\n')
        append("时间：").append(time(e.at)).append('\n')
        append("版本：").append(e.version.ifEmpty { "未知" }).append('\n')
        append("界面：").append(e.screen.ifEmpty { "未知" }).append('\n')
        append("线程：").append(e.thread).append('\n')
        if (e.count > 1) append("次数：").append(e.count).append('\n')
        append('\n').append(e.trace)
    }

    fun time(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(at))

    internal fun entry(e: Throwable, thread: Thread, fatal: Boolean, at: Long = System.currentTimeMillis()): Entry {
        val doing = runCatching { busy() }.getOrDefault("")
        return Entry(at, version, screen + if (doing.isEmpty()) "" else "（$doing）", thread.name, fatal, trace(e))
    }

    /** 堆栈：异常的类名、隐去汉字的说明、代码位置；连同起因（最多 [CAUSES] 层），每层最多 [FRAMES] 行。 */
    internal fun trace(e: Throwable): String = buildString {
        var t: Throwable? = e
        var depth = 0
        val seen = HashSet<Throwable>()
        while (t != null && depth <= CAUSES && seen.add(t)) {
            if (depth > 0) append("Caused by: ")
            append(t.javaClass.name)
            t.message?.let { append(": ").append(mask(it)) }
            append('\n')
            val frames = t.stackTrace
            frames.take(FRAMES).forEach { append("\tat ").append(it).append('\n') }
            if (frames.size > FRAMES) append("\t… 还有 ").append(frames.size - FRAMES).append(" 行\n")
            t = t.cause
            depth++
        }
    }.trimEnd()

    /**
     * 错误说明里可能夹着正文、设定（比如解析失败时带上的原文）：连着的汉字和全角标点换成“〔N字〕”，
     * 英文和数字（下标、长度、类名）留着，太长的截断。
     */
    internal fun mask(message: String): String {
        val out = StringBuilder()
        var run = 0
        fun flush() {
            if (run > 0) out.append("〔").append(run).append("字〕")
            run = 0
        }
        for (ch in message) {
            if (wide(ch)) run++ else {
                flush()
                out.append(ch)
            }
        }
        flush()
        return if (out.length > MESSAGE) out.substring(0, MESSAGE) + "…" else out.toString()
    }

    /** 汉字、全角符号、中日韩标点。 */
    private fun wide(ch: Char): Boolean = Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN ||
        ch in '　'..'〿' || ch in '＀'..'￯' || ch in '‐'..'⁯' || ch.code > 0x2E7F && ch.code < 0xA000

    const val FILE = "crash-log.json"
    private const val FRAMES = 40
    private const val CAUSES = 5
    private const val MESSAGE = 300
}

/** 闪退记录的那个文件：JSON 数组，新的在前。读写都在锁里（闪退的那一下可能和别的线程同时记）。 */
class CrashLogFile(private val file: File) {
    @Synchronized
    fun read(): List<CrashLog.Entry> = runCatching {
        if (!file.isFile) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            CrashLog.Entry(
                at = o.optLong("at"), version = o.optString("version"), screen = o.optString("screen"), thread = o.optString("thread"),
                fatal = o.optBoolean("fatal"), trace = o.optString("trace"), count = o.optInt("count", 1).coerceAtLeast(1),
                seen = o.optBoolean("seen"),
            )
        }
    }.getOrDefault(emptyList())

    /** 记一条：闪退、接住的意外各留最近 [CrashLog.KEEP] 条；接住的意外和最近一条一样（同一处、同一种）就只加次数。 */
    @Synchronized
    fun add(e: CrashLog.Entry) {
        val old = read()
        val same = if (e.fatal) null else old.firstOrNull { !it.fatal && it.trace == e.trace }
        val next = if (same != null) listOf(same.copy(at = e.at, count = same.count + 1, version = e.version, screen = e.screen, thread = e.thread)) + (old - same)
            else listOf(e) + old
        write(next.filter { it.fatal }.take(CrashLog.KEEP) + next.filter { !it.fatal }.take(CrashLog.KEEP))
    }

    @Synchronized
    fun markSeen() {
        val old = read()
        if (old.any { it.fatal && !it.seen }) write(old.map { if (it.fatal) it.copy(seen = true) else it })
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(list: List<CrashLog.Entry>) {
        val json = JSONArray().apply {
            list.sortedByDescending { it.at }.forEach { e ->
                put(JSONObject().put("at", e.at).put("version", e.version).put("screen", e.screen).put("thread", e.thread)
                    .put("fatal", e.fatal).put("trace", e.trace).put("count", e.count).put("seen", e.seen))
            }
        }.toString()
        file.parentFile?.mkdirs()
        DurableFiles.write(file, json.toByteArray())
    }
}
