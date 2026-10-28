package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.BeatTask
import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.ChapterRefs
import com.xycs.lingyuereader.data.ChapterTitles
import com.xycs.lingyuereader.data.LEDGER_ASPECTS
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ProseLengths
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.ReviewEvidence
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.SyncGroup
import com.xycs.lingyuereader.data.SyncItem
import com.xycs.lingyuereader.data.SyncPlace
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.beats
import org.json.JSONArray
import org.json.JSONObject

/** 改一段文字的几种做法。[instruction] 是发给模型的要求。 */
enum class WriteAction(val label: String, val instruction: String) {
    POLISH("润色", "润色这段文字：让句子更通顺、用词更准确、节奏更好，改掉啰嗦和重复。不改变意思、情节和人称，保留作者的口吻和风格，能不改的地方就不改。"),
    VIVID("更生动", "把这段写得更生动：多用具体的动作、感官细节和画面，少用概括和形容词堆砌。不改变情节，篇幅可以略长一些。"),
    EXPAND("扩写", "扩写这段：补充动作、神态、环境和心理细节，篇幅扩到原来的一倍半到两倍。不添加新的情节转折，不改变人物性格。"),
    CONDENSE("缩写", "精简这段：删去重复、拖沓和可有可无的话，篇幅减到原来的一半到三分之二，保留关键情节和对话。"),
    PROOFREAD("挑错", "只改错：错别字、用错的词、病句、标点错误。没有错的地方一个字也不要动，不要做文风上的修改。"),
    CONTINUE("续写", "接着上文往下写大约 300 字。顺着已有的情节和人物性格，不要跳到大结局，不要另起炉灶，也不要重复上文。只写续写的部分。"),
    CUSTOM("按要求改", ""),
}

/** 灵感面板里的几种问法。 */
enum class IdeaKind(val label: String) {
    NEXT("接下来怎么写"),
    DETAIL("细节和描写"),
    NAMES("起名"),
    STUCK("卡文了"),
    ASK("随便问"),
}

/** 写作建议找出的一处问题：在哪段（段落原文）、哪几个字、改成什么、为什么。 */
data class WriteIssue(
    val paragraph: String,
    val wrong: String,
    val right: String,
    val reason: String,
    /** 哪类建议（[SuggestKind] 的名字）。 */
    val kind: String = SuggestKind.TYPO.name,
    /** 依据：出自设定包哪份文档、哪一节（AI 写的，可能为空）。 */
    val source: String = "",
)

/**
 * 写作建议的几类：[realtime] 的几类作者可以分别开关，停笔时自动查；
 * [CLICHE] 是作者点“去 AI 腔”才查的，结果和写作建议一样一条条改或忽略，不在设置里开关。
 * [CANON]（查设定）交给三道审稿的事实关，不再能选、也不再要 AI 查；留着这一类，是为了以前的建议、
 * 旧设置里存着它时照常显示（颜色、划线）而不报错。
 */
enum class SuggestKind(val label: String, val ask: String, val realtime: Boolean = true) {
    TYPO("错字", "错别字、用错的词、漏字多字、标点误用、明显的病句"),
    CANON("设定", "和设定包冲突的：人数、生死、日期、地点、官职、称呼、物件；人物知道了按察觉表这时还不该知道的事；提前写出后面才发生的事", realtime = false),
    STYLE("规范", "违反作者写作约定、写作规范的：禁用词、对白写法、旁白腔、篇幅节奏这类明写了的规矩（只按作者写明的规矩，不按你自己的口味）"),
    VOICE("口吻", "人物说话不像他：和设定包里这个人的身份、称谓、说话方式不符"),
    CLICHE("AI 腔", "套话、神态模板、替读者解释和点题的句子", realtime = false),
    ;

    companion object {
        fun of(name: String): SuggestKind = entries.firstOrNull { it.name == name } ?: TYPO
    }
}

/** 在编辑器里让 AI 在光标处写（“AI 写”面板）：写多少。 */
enum class InlineMode(val label: String) {
    SCENE("写下一场"),
    TO_END("写到本章结尾"),
    WORDS("写 500 字左右"),
    CUSTOM("按要求写…"),
    /** 写细纲卡里的某一拍：“AI 写”面板上面列的节拍，或智能推荐的“写第 N 拍”。 */
    BEAT("写这一拍…"),
}

/**
 * 一拍出几个版本时的方向：同样的资料、节拍、细纲和写法要求，各按一个方向写一版，作者挑。
 * [rule] 写进提示词，放在节拍、模式要求之后、写法要求之前。
 */
enum class WriteDirection(val label: String, val rule: String) {
    CONFLICT("冲突升级", "让这一段里的矛盾更尖锐：人物更主动地顶上去，把话说重、把事做绝一点，冲突在这一段里往上走一级。"),
    RESTRAIN("按下不发", "写得克制：情绪和信息压着不说破，用动作、细节和留白让读者自己读出来，不替人物把心里话说出来。"),
    SURPRISE("意外插入", "插入一个合理的小意外（一个人、一件东西或一个消息），改变这一段的走向；意外要在细纲卡的事实边界之内，不提前写后面的事。"),
}

/** 写一次正文写多少：任务卡按它写“写到哪里停”。 */
enum class ProseScope {
    /** 细纲卡里的某一拍（逐拍写、编辑器里写这一拍）。 */
    BEAT,
    /** 整章（没有节拍时写整章草稿）。 */
    CHAPTER,
    /** 写到本章结尾（编辑器里写到本章结尾、草稿接着写完）。 */
    TO_END,
    SCENE,
    WORDS,
    CUSTOM,
    /** 项目包流程里按审读意见修订整章。 */
    REVISE,
}

/**
 * 写一次正文的任务，[WritePrompts.taskCard] 照它出任务卡。[beat]：[ProseScope.BEAT] 时是这次写第几拍，
 * [ProseScope.TO_END] 时是从第几拍写起（前面的算已写，空是看细纲卡里勾的）。[length] 是这一次唯一的篇幅（算好的，
 * 比如“约 800 字”），空的不写。[opening] 为真时从本章开头写，[previous] 为真时前面给了上一章结尾，
 * [after] 为真时光标后面还有已写的；[previousHook] 是上一章的章末钩子。
 */
data class ProseTask(
    val chapter: Int?,
    val title: String,
    val card: ChapterCard,
    val scope: ProseScope,
    val beat: Int? = null,
    val length: String = "",
    val opening: Boolean = false,
    val previous: Boolean = false,
    val after: Boolean = false,
    val previousHook: String = "",
    val instruction: String = "",
    val direction: WriteDirection? = null,
)

/** 设定集里 AI 从正文整理出的一条：[existing] 为真时是更新已有的条目。 */
data class BibleProposal(val name: String, val text: String, val existing: Boolean)

/**
 * 写作助手发给模型的话。原则：保留作者的文风，不擅自改情节；改稿类只输出结果。
 * 写正文（编辑器里写、写整章草稿、项目包的创作和修订）的请求以任务卡收尾（[taskCard]），前面紧挨着写法要求（[proseTail]）；
 * 改稿类的请求最后接上写法要求（[voiceTail]：文风、范例、采纳过的改法、去 AI 腔、黑名单）。
 */
object WritePrompts {
    /** Structured evidence is kept outside the manuscript; a missing source is not a proven error. */
    fun projectReview(task: ChapterWritingTask, text: String, rules: String, evidence: ReviewEvidence? = null): String = buildString {
        // 本章任务在系统提示里（只给一次）。
        append("【当前阶段：审读】\n")
        append("待审完整章稿：\n").append(text).append("\n\n")
        if (rules.isNotBlank()) append("作者规范与规则：\n").append(rules.trim()).append("\n\n")
        append("先抓最影响这一章的两三处问题，再区分事实必改与审美建议。核时地、在场与知情、资源权限、关键选择、")
        append("因果、伤亡损失、结果和承接。每项问题给正文一字不差的原句、具体资料依据和影响；资料不足列为missing，不能把推测当错误。\n")
        if (evidence != null) {
            append("所有必改意见，以及事实类建议，都必须给 source_ref（来源编号）和 source_quote（该来源中连续、原样的至少 ${ReviewEvidence.MIN_QUOTE_CHARS} 字原文，不用省略号）。")
            append("本章任务来源编号是 ").append(evidence.taskRef)
            append("：FACT 可引本次明确要求、目标章细纲卡及已定前章摘录；CRAFT 必改只可引本次明确要求。任务中的承接推断和资料摘要不能冒充已定事实。设定文档和已定前文的来源编号见材料标题或查阅结果。")
            append("FACT 只能引目标章约束、已定前文或事实设定；CRAFT 必改只能引明确要求、写作说明、写作规范、称谓语气或检查规则，不能把事实卡或人物档案改称写法依据，也不能把一般审美偏好写成必改。")
            val craftSources = evidence.sources.filter { it.supportsCraft && !it.supportsFact }
            if (craftSources.isNotEmpty()) append(craftSources.joinToString("；", prefix = "写法与规则来源编号：", postfix = "。") { "${it.label} ${it.ref}" })
            append("若找不到可定位原文，把问题放进 missing 等作者核对，不把猜测列为必改。来源定位只能证明引文存在，仍须判断其含义。\n")
        }
        append("单独连读对白，检查关系、欲望和情绪是否成立，是否人人准确概括、借角色讲课；检查旁白是否重复对白与结果、是否写成论文。")
        append("不因生活闲话、直接心理或背景没有推进主线就删，不强制短句、逐句动作、统一场次、微伏笔或快慢配额。")
        append("保留有效内容，不为评分凑错，不将审美偏好写成硬性规则。\n")
        append("本阶段只审读，不重写正文，不新增故事事实，不声称已运行脚本或作者已定。输出结构用于软件展示，取代模板中的排版要求。")
        append("只输出JSON，固定顶层结构：{\"summary\":[],\"missing\":[],\"issues\":[]}。summary放两三项主要判断，missing放确实缺少的硬资料；没有则留空数组。")
        append("每个issues对象格式为{\"kind\":\"FACT\",\"quote\":\"正文原句\",\"problem\":\"问题与影响\",\"basis\":\"具体资料依据或明确创作要求\",\"source_ref\":\"来源编号\",\"source_quote\":\"来源原句\",\"fix\":\"在既定事实内的改法\",\"required\":true}。kind只能是FACT或CRAFT。")
        append("事实错误及明确违反作者要求的写法才required=true；可取舍的审美建议required=false。没有问题如实返回空数组，不伪填通过记录。")
    }

    /**
     * 哪些改稿要带文风范例和写法要求（[voiceTail]）：要写出新句子的（润色、更生动、扩写、缩写、按要求改、续写）。
     * 挑错只改错字，不学文风；写作建议、审稿、整理进设定包等也不带。
     */
    fun usesSamples(action: WriteAction): Boolean = action != WriteAction.PROOFREAD

    /** 改稿时带上前文多少字、后文多少字。 */
    const val BEFORE_CHARS = 1_500
    const val AFTER_CHARS = 300
    /** 整章点评最多发多少字。 */
    const val CHAPTER_CHARS = 14_000

    /** 能查设定包时加在系统提示后面：要用就查，别凭印象编；查完直接作答。 */
    const val TOOL_GUIDE = "设定包里没整份给出的内容（某一章正文、细纲原文、某个人物、某个数字、谁什么时候知道了什么），" +
        "拿不准就先用工具查，不要凭印象编。查够了就直接作答：回答里不要复述查到的资料，也不要说你查了什么。"

    /**
     * [pack] 是拼好的设定包（见 [com.xycs.lingyuereader.data.PackContext]），为空时不带设定。
     * 文风范例、作者采纳过和没采纳的改法不在这里：生成正文时放在请求的最后（[voiceTail]），离“请写”近才管用。
     */
    fun system(title: String, assist: WorkAssist, pack: String? = null, writer: Boolean = false): String = buildString {
        if (writer) {
            // 写正文：是一起写书的人，不是审稿的。“编辑助手”会把模型往说明、核对的口气上推。
            append("你是和作者一起写这部小说")
            if (title.isNotBlank()) append("《").append(title).append("》")
            append("的合写搭档，负责先写出好看的初稿，作者再改。\n")
            append("- 用简体中文。照作者定下的人物、设定、人称和用词习惯写，不擅自改情节和人物设定。\n")
            append("- 只输出正文本身：不要解释，不要加标题、引号、序号或“好的”之类的话，不用 Markdown。\n")
        } else {
            append("你是写作软件“灵阅”里的编辑助手，在帮作者写长篇小说")
            if (title.isNotBlank()) append("《").append(title).append("》")
            append("。\n")
            append("- 用简体中文。尊重作者：保留作者的文风、人称、时态和用词习惯，不擅自改变情节和人物设定，不说教。\n")
            append("- 要你改写或续写时，只输出正文本身：不要解释，不要加标题、引号、序号或“修改后：”之类的话，不用 Markdown。\n")
        }
        append("- 一行就是一段，段与段之间换行，不要空行，段首不要空格。\n")
        // 写正文时文风要求在请求最后（[proseTail]），这里不重复。
        if (assist.style.isNotBlank() && !writer) append("\n作者对文风的要求（最优先，和下面的设定资料冲突时以这里为准）：").append(assist.style.trim()).append('\n')
        if (!pack.isNullOrBlank()) append("\n").append(pack).append("\n写的内容不要和这些设定矛盾。\n")
    }

    /**
     * 改稿（润色、扩写这类）和去 AI 腔时带的写法要求（作品开了“查 AI 腔”时带）。写正文用的是 [VOICE_RULES]：
     * 这几条压情绪，改一段时求稳还行，写正文会把人物写成木头。
     */
    val EDIT_RULES = listOf(
        "人物的反应用动作、对白和具体细节写出来，不写“嘴角勾起”“眼中闪过一丝”“瞳孔骤缩”这类神态套话。",
        "不替读者解释人物心理和言外之意；段尾不总结、不点题、不升华，不写“他知道，……”“或许这就是……”。",
        "少用“仿佛、宛如、一丝、一抹、不禁、莫名”；比喻一章不超过两三处，要贴合人物的身份和眼界。",
        "对白要像人说话：短句、口语，可以答半句、打岔，不用书面语和成语堆砌。",
        "不用排比收尾，不写“这一刻……”式的抒情句，不写“空气仿佛凝固”“死一般的寂静”这类氛围套话。",
        "句子长短交错，不要每段都是“动作＋神态＋心理”三件套。",
        "能删的形容词、副词就删：写一个准确的细节，不写三个泛泛的。",
    )

    /** 写法要求的标题：测试、发送内容里认它。 */
    const val VOICE_HEADER = "【写法要求】（落笔时照这些写，和上面的事实、细纲不冲突）"

    /**
     * 改稿的写法要求：接在改稿请求的最末尾，什么都没有时是空串。依次是：
     * 1. 作者对文风的要求 [WorkAssist.style]（系统提示里那份也留着，这里再说一遍）；
     * 2. 文风范例 [samples]（[com.xycs.lingyuereader.data.StyleSamples.render] 的结果，可空）；
     * 3. [examples] 为真时，作者以前采纳过的修改、没采纳的改法（续写这类没有原文可比的传假）；
     * 4. [WorkAssist.aiCliche] 开着时，去 AI 腔的写法要求（[EDIT_RULES]）；
     * 5. 我的黑名单 [WorkAssist.banned]：一个都不要用。
     *
     * 用在润色扩写这类改稿和去 AI 腔；写正文（编辑器里写、写整章草稿、项目包的创作和修订）用 [proseTail]，
     * 审稿、拟细纲、摘要、整理、写作建议、挑错都不用。
     */
    fun voiceTail(assist: WorkAssist, samples: String?, examples: Boolean = true): String = buildString {
        if (assist.style.isNotBlank()) append("作者对文风的要求：").append(assist.style.trim()).append("\n\n")
        if (!samples.isNullOrBlank()) append(samples.trim()).append("\n\n")
        if (examples) append(examples(assist))
        if (assist.aiCliche) {
            append("去掉 AI 腔：\n")
            EDIT_RULES.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        if (assist.banned.isNotEmpty()) append("下面这些说法一个都不要用：").append(assist.banned.joinToString("、") { "「$it」" }).append('\n')
    }.trim().let { if (it.isEmpty()) it else "$VOICE_HEADER\n$it" }

    /** 作者以前采纳过的修改、没采纳的改法（最近几条，太长的不要），各一段；都没有为空。 */
    private fun examples(assist: WorkAssist): String = buildString {
        val picked = assist.accepted.filter { it.before.length <= 200 && it.after.length <= 260 }.takeLast(3)
        if (picked.isNotEmpty()) {
            append("作者以前采纳过的修改（照这个分寸来改）：\n")
            picked.forEach { e -> append("原文：").append(e.before).append("\n改为：").append(e.after).append('\n') }
            append('\n')
        }
        val declined = assist.declined.filter { it.before.length <= 200 && it.after.length <= 260 }.takeLast(2)
        if (declined.isNotEmpty()) {
            append("作者没有采纳的改法（不要这样改）：\n")
            declined.forEach { e -> append("原文：").append(e.before).append("\n没采纳的改法：").append(e.after).append('\n') }
            append('\n')
        }
    }

    /** 请求最后接上写法要求 [voice]（空的不接）。 */
    fun withVoice(prompt: String, voice: String): String = if (voice.isBlank()) prompt else prompt.trimEnd() + "\n\n" + voice.trim()

    // ---------- 写正文（编辑器里写、AI 写整章草稿、项目包的创作和修订） ----------
    //
    // 一次写正文的请求：系统提示是“合写搭档”的身份、作者的说明和写作规范、本章用得上的资料（[proseSystem]）；
    // 请求里依次是前文（上一章结尾、已写的部分或光标前后）、范本的要求、写法要求（[proseTail]：口味、文风范例、
    // 写法要求和黑名单），最后一段永远是任务卡（[taskCard]）：这次写什么、写到哪里停、写多少，只在这里说一次。

    /** 任务卡的标题：测试、找位置用。 */
    const val CARD_HEADER = "【这次要写的】"

    /** 写正文时写法要求的标题（改稿用 [VOICE_HEADER]）。 */
    const val PROSE_HEADER = "【写法要求】"

    /** 口味那一行的开头。 */
    const val FLAVOR_HEADER = "这部书的口味："

    /** 上一章结尾最多、最少带多少字。 */
    const val ENDING_MAX = 1_500
    const val ENDING_MIN = 800

    /** 接着写草稿时带已写的最后多少字（逐拍写的接口处要的是语气和场面，少带些）。 */
    const val MORE_CHARS = 4_000
    const val MORE_BEAT_CHARS = 3_000

    /**
     * 上一章结尾：只留最后 [max] 字，从完整的一段（找不到就从完整的一句）起，至少留 [min] 字。
     * 要接上的只是最后两三段，整章给出去只会离指令更远。
     */
    fun ending(text: String, max: Int = ENDING_MAX, min: Int = ENDING_MIN): String {
        val t = text.trim()
        if (t.length <= max) return t
        val window = t.takeLast(max)
        val room = window.length - min
        val paragraph = window.indexOf('\n')
        if (paragraph in 0 until room) return window.substring(paragraph + 1).trim()
        val end = window.indexOfFirst { it in SENTENCE_END }
        if (end in 0 until room) {
            var j = end + 1
            while (j < window.length && (window[j] in SENTENCE_END || window[j] in "”」』")) j++
            if (j <= room) return window.substring(j).trim()
        }
        return window.trim()
    }

    private const val SENTENCE_END = "。！？!?…"

    /** 章名里的“第3章”去掉，只留名字（任务卡里章号另写）。只有章号时原样。 */
    private fun chapterName(title: String): String =
        title.replace(Regex("^\\s*第[0-9０-９零〇一二两三四五六七八九十百千]+章[\\s:：、.．]*"), "").trim().ifEmpty { title.trim() }

    /** 卡片里的一项写成一行。 */
    private fun line(text: String): String = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** 这一拍的篇幅（细纲卡里写了就照它，没写按全章平均分）；分不出为空。 */
    fun beatLength(beat: BeatTask): String = beat.length?.let { "约 $it 字" }.orEmpty()

    /** 全章篇幅：写了数字的照写，没有数字（“依本章细纲……”）为空。 */
    fun chapterLength(length: String): String = length.trim().takeIf { t -> t.any { it.isDigit() } }.orEmpty()

    /** 写到本章结尾时还剩多少字：全章篇幅（范围取中间）减去已写的；算不出来、所剩无几为空。 */
    fun remaining(chapterLength: String, written: String): String {
        val total = Beats.share(chapterLength, 1) ?: return ""
        val left = total - WordCount.of(written)
        return if (left < 300) "" else "约 ${left / 100 * 100} 字"
    }

    /**
     * 本次写作任务卡：所有写正文的请求都以它收尾。用大白话写这一章是第几章、视角、全章节拍（标出已写、这次写哪一拍、
     * 哪几拍后面再写、在哪个钩子上收）、从哪里接、写到哪里停、篇幅（整个请求里唯一的篇幅数字）、本章必须成立的事。
     * 没有节拍时用细纲卡的事实、视角、钩子拼出同样的卡。
     */
    fun taskCard(t: ProseTask): String = buildString {
        val list = beats(t.card)
        val hook = line(t.card.hook)
        val n = t.beat?.takeIf { it in 1..list.size }
        val beatScope = t.scope == ProseScope.BEAT && n != null
        append(CARD_HEADER).append('\n')
        append(t.chapter?.let { "第 $it 章" } ?: "这一章")
        chapterName(t.title).takeIf { it.isNotEmpty() }?.let { append("《").append(it).append("》") }
        if (t.card.pov.isNotBlank()) append("，视角：").append(line(t.card.pov))
        append('\n')
        if (t.opening && t.previousHook.isNotBlank()) append("上一章停在：").append(line(t.previousHook)).append('\n')
        val done: Set<Int> = when {
            beatScope || (t.scope == ProseScope.TO_END && n != null) -> (1 until n!!).toSet()
            t.scope == ProseScope.REVISE -> emptySet()
            else -> t.card.beatsDone.toSet()
        }
        val end = if (hook.isNotEmpty()) "本章结尾，停在钩子上：$hook" else "本章结尾"
        if (list.isNotEmpty()) {
            // 写到本章结尾、又不知道写到哪一拍了：让模型从上文停下的地方接，别从第一拍重写。
            append(if (t.scope == ProseScope.TO_END && done.isEmpty() && !t.opening) "全章节拍（从上文停下的地方接着写，没写到的拍都要写到）：\n" else "全章节拍：\n")
            list.forEach { b ->
                append("  ").append(b.index).append(". ").append(line(b.text))
                val last = b.index == list.size
                when {
                    beatScope && b.index == n -> append("  ← 这次写这一拍").append(if (last) "（$end）" else "")
                    b.index in done -> append("（已写）")
                    beatScope && b.index > n!! -> append(if (last) "（后面再写；$end）" else "（后面再写，这次别写到）")
                    last -> append("（").append(end).append("）")
                }
                append('\n')
            }
        } else if (hook.isNotEmpty() && t.scope in setOf(ProseScope.CHAPTER, ProseScope.TO_END, ProseScope.REVISE)) {
            append("章末停在：").append(hook).append('\n')
        }
        append("从哪里接：").append(
            when {
                t.scope == ProseScope.REVISE -> "在上面这一版稿子上改"
                t.opening && t.previous -> "本章开头，紧接上一章结尾（见前面）"
                t.opening -> "本章开头"
                t.after -> "紧接光标前最后一句；光标后面已经写了的（见前面）要能接上"
                else -> "紧接上文最后一句（上文见前面）"
            },
        ).append('\n')
        val atHook = if (hook.isNotEmpty()) "，停在章末钩子上" else ""
        append("写到哪里停：").append(
            when (t.scope) {
                ProseScope.BEAT -> when {
                    !beatScope -> "写完这一段就停"
                    n!! < list.size -> "这次只写第 $n 拍，写完它的事就停，别开始第 ${n + 1} 拍"
                    hook.isNotEmpty() -> "这次只写第 $n 拍，它是本章最后一拍，写完停在章末钩子上"
                    else -> "这次只写第 $n 拍，它是本章最后一拍，写完本章就结束"
                }
                ProseScope.CHAPTER -> "写完整章$atHook"
                ProseScope.TO_END -> (if (list.isNotEmpty()) "把剩下的各拍都写完" else "把这一章写完") + atHook
                ProseScope.SCENE -> "写完眼下这一场就停，停在场面自然收住的地方"
                ProseScope.WORDS -> "写够篇幅，在一句话说完的地方停"
                ProseScope.CUSTOM -> "照作者这次的要求写，写完就停"
                ProseScope.REVISE -> "改完整章，交出修订后的完整正文"
            },
        ).append('\n')
        if (t.length.isNotBlank()) append("篇幅：").append(t.length.trim()).append('\n')
        if (t.instruction.isNotBlank()) append("作者这次的要求：").append(line(t.instruction)).append('\n')
        t.direction?.let { append(directionRule(it)).append('\n') }
        val facts = line(ProseLengths.strip(t.card.facts))
        if (facts.isNotEmpty()) {
            append("本章必须成立的事：").append(facts)
            if (beatScope && list.size > 1) append("（分在各拍里写，这次只写第 $n 拍的部分）")
            append('\n')
        }
        if (t.card.boundary.isNotBlank()) append("这一章不写到：").append(line(t.card.boundary)).append('\n')
        if (t.card.countdown.isNotBlank()) append("时间：").append(line(t.card.countdown)).append('\n')
        if (facts.isNotEmpty()) append("这些事要写出来，但写成场面和对话，不要列清单式交代。")
    }.trim()

    /** 一拍出几个版本时这一版的方向（写进任务卡）：方向只管这一版怎么写，写什么、写到哪里照卡上的。 */
    fun directionRule(direction: WriteDirection): String = "【这一版的方向：${direction.label}】${direction.rule}"

    /** 拼一次写正文的请求：前文、范本的要求、写法要求、任务卡（最后），空的不拼。 */
    private fun prose(head: String, reference: String, voice: String, card: String): String =
        listOf(head, reference, voice, card).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /**
     * 从本章开头写（整章、或逐拍写的第一拍）：[previous] 是上一章结尾（[ending] 截好的），[voice] 是 [proseTail]，
     * [reference] 是范本的要求（可空）。
     */
    fun draft(previous: String, task: ProseTask, voice: String = "", reference: String = ""): String =
        prose(if (previous.isBlank()) "" else "上一章结尾：\n" + previous.trim(), reference, voice,
            taskCard(task.copy(opening = true, previous = previous.isNotBlank())))

    /** 接着已写的往下写（草稿没写完、逐拍写的后面几拍）：[written] 是这一章已经写了的，只带最后一部分。 */
    fun draftMore(written: String, task: ProseTask, voice: String = "", reference: String = ""): String {
        val all = written.trim()
        val shown = all.takeLast(if (task.scope == ProseScope.BEAT) MORE_BEAT_CHARS else MORE_CHARS)
        val head = (if (shown.length < all.length) "这一章已经写到这里（只给最后一部分）：\n" else "这一章已经写到这里：\n") + shown
        return prose(head, reference, voice, taskCard(task.copy(opening = false)))
    }

    /** 编辑器里从光标处接着写：[before] 是光标前的正文（不够长时前面接上一章结尾），[after] 是光标后面已经写了的。 */
    fun continueAt(before: String, after: String, task: ProseTask, voice: String = "", reference: String = ""): String {
        val head = buildString {
            append("这一章写到光标处的正文：\n").append(before).append("\n【光标在这里】")
            if (after.isNotBlank()) append("\n\n光标后面已经写了（只作语境，不要重复它）：\n").append(after)
        }
        return prose(head, reference, voice, taskCard(task.copy(after = after.isNotBlank())))
    }

    /**
     * 项目包流程里按审读意见修订整章：和写正文一样以写法要求、任务卡收尾，修订稿才不会改回 AI 腔。
     * 审读意见只是线索：说得对的才改，不当成新设定。
     */
    fun projectRevision(text: String, review: String, task: ProseTask, voice: String = "", reference: String = ""): String {
        val head = buildString {
            append("这一章现在的稿子：\n").append(text.trim()).append("\n\n")
            append("审读意见：\n").append(review.trim()).append("\n\n")
            append("按这些意见改这一章。意见说得对的就改；误读了原稿、和细纲对不上的不照办，审读意见不自动成为新设定。")
            append("只在有问题的地方改，别处的句子、对白、心理和生活细节都留着；不要把所有对白改短，不要加总结。")
        }
        return prose(head, reference, voice, taskCard(task.copy(scope = ProseScope.REVISE, opening = false)))
    }

    /**
     * 写正文的系统提示：合写搭档的身份，作者写给 AI 的说明（[author]，去掉了整章字数），作者的写作规范和检查规则
     * （[norms]，只放这一份），本章用得上的资料（[material]）。前面三样同一部作品每次一样，服务商能按相同开头缓存；
     * [material] 为空时就是那段稳定的开头。
     */
    fun proseSystem(title: String, assist: WorkAssist, author: String, norms: String, material: String): String = buildString {
        append(system(title, assist, writer = true).trim())
        if (author.isNotBlank()) append("\n\n【作者写给 AI 的说明】\n").append(author.trim())
        if (norms.isNotBlank()) append("\n\n【作者的写作规范】（照这个写；和后面的写法要求说得不一样时，以这里为准）\n").append(norms.trim())
        if (material.isNotBlank()) append("\n\n").append(material.trim())
    }

    /** 写正文时的查阅说明：一两句，需要核对时再查，查完直接写。 */
    fun proseToolGuide(catalog: List<String>): String = buildString {
        append("细节拿不准（人物、地名、前面写过的事）时再查：read_doc 读资料，search 搜关键词，read_chapter 读前面某一章的正文。")
        if (catalog.isNotEmpty()) append("能查的资料：").append(catalog.joinToString("") { "《$it》" }).append("。")
        append("查完直接写正文。")
    }

    /**
     * 写正文的一条写法要求：[text] 是要求；作者的规范里写了同类规矩时（[topic] 在作者规范里找得到）换成 [yielded]，
     * 空的就不发。作者自己写的规范优先：同一件事不给模型两个说法。
     */
    class VoiceRule(val text: String, val topic: Regex? = null, val yielded: String = "", val cue: Regex? = null, val cued: String = "")

    /** 写正文的写法要求：说“要什么”，短、具体、能照着做。和作者规范冲突时见 [VoiceRule]。 */
    val VOICE_RULES = listOf(
        VoiceRule(
            "人物有情绪、有心思：会急、会怕、会得意、会犹豫、会嘴硬。用心里想的、嘴上说的、手上做的一起写出来，可以直接写他在想什么。",
            Regex("心理|心里|内心|想法"),
            "人物有情绪、有心思：会急、会怕、会得意、会犹豫、会嘴硬。用嘴上说的、手上做的写出来，心里想的写多少照作者的规范。",
        ),
        VoiceRule("对话要像活人说话：有来有回，带着情绪和没说出口的意思；可以抢话、顶嘴、打岔、开玩笑。每个人的口气都不一样。"),
        VoiceRule("每一场都往前走：主角想要什么、碰上什么阻碍、做了什么、结果怎样，读者要看得明白。关键的信息一出现，就让读者明白它为什么要紧。"),
        // 穿越、重生的书才提“历史知识”：别的书提了，模型会以为要往这上面靠。
        VoiceRule(
            "主角要主动：会盘算，会拿自己知道的东西想办法，不光是看着事情发生。",
            cue = Regex("穿越|重生|穿书"),
            cued = "主角要主动：会盘算，会拿自己知道的东西想办法（穿越过来的就用上历史知识），不光是看着事情发生。",
        ),
        VoiceRule("节奏有快有慢：紧的地方短句快推，松的地方可以有闲笔和吐槽。", Regex("句子|长句|短句|句长"), "节奏有快有慢：紧的地方快推，松的地方可以有闲笔和吐槽。"),
        VoiceRule("叙述可以有态度：叙述者偶尔吐槽、玩个梗都行，但别打断正在进行的场面。", Regex("吐槽|玩梗|调侃")),
        VoiceRule("写具体的东西：具体的动作、物件、声音、气味，少用空泛的形容词。"),
    )

    /**
     * 按作者的规范 [norms] 挑出这次要发的写法要求：作者写了同类规矩的那条换成让步的说法，或者不发；
     * [context]（口味）或作者规范里有 [VoiceRule.cue] 的换成 [VoiceRule.cued]。
     */
    fun voiceRules(norms: String, context: String = ""): List<String> = VOICE_RULES.mapNotNull { r ->
        when {
            r.topic != null && r.topic.containsMatchIn(norms) -> r.yielded.ifEmpty { null }
            r.cue != null && r.cue.containsMatchIn(norms + "\n" + context) -> r.cued
            else -> r.text
        }
    }

    /**
     * 写正文的请求里接在任务卡前面的一段，依次是：这部书的口味（[WorkAssist.flavor]）、作者对文风的要求、文风范例 [samples]、
     * 作者采纳过和没采纳的改法、写法要求（[voiceRules]，按作者的规范 [norms] 让步）、去套话的一句（开着“查 AI 腔”时）和黑名单。
     */
    fun proseTail(assist: WorkAssist, samples: String?, norms: String = ""): String = buildString {
        if (assist.flavor.isNotBlank()) append(FLAVOR_HEADER).append(line(assist.flavor)).append("\n\n")
        if (assist.style.isNotBlank()) append("作者对文风的要求：").append(assist.style.trim()).append("\n\n")
        if (!samples.isNullOrBlank()) append(samples.trim()).append("\n\n")
        append(examples(assist))
        append(PROSE_HEADER).append('\n')
        voiceRules(norms + "\n" + assist.style, assist.flavor).forEach { append("- ").append(it).append('\n') }
        if (assist.aiCliche) append("- 避开“嘴角勾起”“眼中闪过”这类套话。\n")
        if (assist.banned.isNotEmpty()) append("- 下面这些说法一个都不要用：").append(assist.banned.joinToString("、") { "「$it」" }).append('\n')
    }.trim()


    /**
     * 细纲卡带给 AI：本章的事实边界，加上一章留下的钩子和承接边界。都空着时为空。
     * 细纲只管“发生了什么”，不管怎么写；写的内容不能和它冲突。
     */
    fun card(card: ChapterCard, previousTitle: String?, previous: ChapterCard?): String? = buildString {
        val lines = listOf(
            "本章事实与结果" to card.facts, "后文承接边界" to card.boundary, "视角人物" to card.pov,
            "倒计时" to card.countdown, "章末钩子" to card.hook, "推进的线" to card.threads,
        ).filter { it.second.isNotBlank() }
        if (lines.isNotEmpty()) {
            append("本章细纲卡（事实边界：写的内容不能和它冲突；场面怎么写可以自由发挥）：\n")
            lines.forEach { (k, v) -> append("- ").append(k).append("：").append(v.trim()).append('\n') }
        }
        if (previous != null && (previous.hook.isNotBlank() || previous.boundary.isNotBlank())) {
            append("上一章").append(previousTitle?.let { "「$it」" }.orEmpty()).append("留下的：\n")
            if (previous.hook.isNotBlank()) append("- 钩子：").append(previous.hook.trim()).append('\n')
            if (previous.boundary.isNotBlank()) append("- 承接边界：").append(previous.boundary.trim()).append('\n')
        }
    }.trim().ifEmpty { null }

    /**
     * 改一段：[target] 是要改的文字（续写时是光标前的上文），[before]、[after] 是它前后的原文。
     * [voice] 是写法要求（[voiceTail]），接在最后；挑错只改错字，不带（见 [usesSamples]）。
     */
    fun edit(action: WriteAction, target: String, before: String, after: String, chapterTitle: String, instruction: String = "", voice: String = ""): String = withVoice(buildString {
        if (chapterTitle.isNotBlank()) append("章节：").append(chapterTitle).append('\n')
        if (action == WriteAction.CONTINUE) {
            append("上文：\n").append((before + target).takeLast(BEFORE_CHARS * 2)).append("\n\n")
            if (after.isNotBlank()) append("（光标后面还有：").append(after.take(AFTER_CHARS)).append("）\n\n")
            append(action.instruction)
            if (instruction.isNotBlank()) append("\n作者的要求：").append(instruction.trim())
            return@buildString
        }
        if (before.isNotBlank()) append("前文（只作语境，不要改）：\n").append(before.takeLast(BEFORE_CHARS)).append("\n\n")
        append("要改的文字：\n").append(target).append("\n\n")
        if (after.isNotBlank()) append("后文（只作语境，不要改）：\n").append(after.take(AFTER_CHARS)).append("\n\n")
        append(if (action == WriteAction.CUSTOM) "按作者的要求改这段文字：" + instruction.trim() else action.instruction)
        append("\n只输出改好的“要改的文字”这一部分。")
    }, if (usesSamples(action)) voice else "")

    /** 灵感：[recent] 是写到这里的原文（本章已写的部分，前面接上一章的结尾）。 */
    fun idea(kind: IdeaKind, recent: String, chapterTitle: String, extra: String = ""): String = buildString {
        if (chapterTitle.isNotBlank()) append("正在写的章节：").append(chapterTitle).append('\n')
        append("写到这里的原文：\n").append(recent.ifBlank { "（这一章还没开始写）" }).append("\n\n")
        when (kind) {
            IdeaKind.NEXT -> append("给出接下来剧情的 3 个不同走向，每个两三句话，写清楚冲突在哪、能带来什么看点；再用一句话说你更推荐哪个、为什么。")
            IdeaKind.DETAIL -> append("针对最后一段场景，给出 5 条可以加进去的具体细节（动作、神态、环境、声音气味、心理），每条一句话，作者可以直接拿去用。")
            IdeaKind.NAMES -> append("帮作者起名：").append(extra.ifBlank { "给接下来可能出场的人物" }).append("。给 8 个候选，贴合作品的时代和风格，每个后面用几个字说寓意或感觉。")
            IdeaKind.STUCK -> append("作者卡文了。不要替他写，而是像编辑一样问 3 到 5 个能帮他理清思路的问题（比如这一章要完成什么、人物此刻想要什么、什么在阻碍他），每个问题后面给一个可能的方向。")
            IdeaKind.ASK -> append("作者的问题：").append(extra.trim())
        }
        append("\n用纯文本分条回答，简洁，不要客套话。")
    }

    /** 读者视角点评一章。 */
    fun review(chapter: String, chapterTitle: String): String = buildString {
        append("下面是作者新写的一章「").append(chapterTitle).append("」：\n\n").append(chapter.take(CHAPTER_CHARS)).append("\n\n")
        append("请你以网文老读者的身份读完这一章，直说感受：\n")
        append("1. 节奏：哪里拖、哪里赶；\n2. 看点：最吸引人的地方，有没有让人想往下翻的钩子；\n")
        append("3. 弃书风险：哪些地方可能让读者失去耐心或出戏；\n4. 三条最值得改的具体建议（指出是哪一段）。\n")
        append("说真话，不要客套，也不要全盘否定。纯文本分条，总共不超过 500 字。")
    }

    /** 没有设定包时的写作建议：几段刚写的文字，让模型挑出明显的错字、用词和标点错误，回 JSON。 */
    /**
     * 写作建议：作者刚写的几段，按开着的几类挑问题。设定、规范、口吻都要有依据（设定包里写明的），
     * 拿不准的不提；只挑问题，不改文风。
     */
    fun suggest(paragraphs: List<String>, kinds: Set<SuggestKind>): String = buildString {
        append("下面是作者刚写的几段小说正文，每段前面是编号：\n\n")
        paragraphs.forEachIndexed { i, p -> append(i + 1).append(". ").append(p).append('\n') }
        append("\n只找下面这几类问题，别的不要管（不要改文风、不要提“可以写得更好”的建议、不要改人物故意的说法）：\n")
        kinds.forEach { append("- ").append(it.label).append("：").append(it.ask).append('\n') }
        // 写作建议发得勤，不给工具（一次请求就答）：只按上面整份给出的设定判断。
        if (kinds.any { it == SuggestKind.STYLE || it == SuggestKind.VOICE }) append("规范、口吻这两类必须在上面给出的设定里有明确依据，拿不准就不提。\n")
        append("wrong 必须是原文里一字不差的一小段（尽量短，三五个字到一句话），right 是改好的这一小段，reason 用一句话说原因，")
        append("kind 是 ").append(kinds.joinToString("、") { it.name + "（" + it.label + "）" }).append(" 之一，source 写依据出自哪份文档哪一节（错字不用写）。\n")
        append("每段最多 3 处；没有问题的段不要列。只输出 JSON：{\"issues\":[{\"p\":1,\"wrong\":\"\",\"right\":\"\",\"reason\":\"\",\"kind\":\"\",\"source\":\"\"}]}，全都没问题就输出 {\"issues\":[]}。")
    }

    fun proofread(paragraphs: List<String>): String = buildString {
        append("下面是作者刚写的几段小说正文，每段前面是编号：\n\n")
        paragraphs.forEachIndexed { i, p -> append(i + 1).append(". ").append(p).append('\n') }
        append("\n只找明显的错误：错别字、用错的词、漏字多字、标点误用、明显的病句。不要管文风、不要提修改建议、不要改对话里人物故意的说法。\n")
        append("wrong 必须是原文里一字不差的一小段（尽量短，三五个字到一句话），right 是改好的这一小段，reason 用几个字说原因。\n")
        append("每段最多 3 处；没有问题的段不要列。只输出 JSON：{\"issues\":[{\"p\":1,\"wrong\":\"\",\"right\":\"\",\"reason\":\"\"}]}，全都没问题就输出 {\"issues\":[]}。")
    }

    /** 从正文整理人物到设定集。 */
    fun extract(text: String, names: List<String>): String = buildString {
        append("下面是作者写的小说正文：\n\n").append(text).append("\n\n")
        append("请整理其中的人物，给作者的设定集用。")
        if (names.isNotEmpty()) append("设定集里已有：").append(names.joinToString("、")).append("。已有的人物如果正文里有了新信息，也列出来（用同样的名字）。")
        append("\n只收有名字的、有戏份的人物，最多 20 个。text 一两句话写身份、性格、外貌特征和与他人的关系，只依据正文，不要编。\n")
        append("只输出 JSON：{\"entries\":[{\"name\":\"\",\"text\":\"\"}]}")
    }

    /** 事实关：对照设定资料（系统提示里）、细纲卡、上一章结尾，只挑事实、因果、视角、承接上的问题。 */
    fun factReview(text: String, title: String, previous: String): String = buildString {
        if (previous.isNotBlank()) append("上一章结尾：\n").append(previous).append("\n\n")
        append("下面是这一章「").append(title).append("」的正文：\n\n").append(text).append("\n\n")
        append("请做事实审稿，对照上面的设定资料（包括到这一章为止人物知道什么、还不知道什么、没回收的伏笔）和本章细纲卡，找出：\n")
        append("1. 和设定、不变量、细纲卡的事实边界矛盾的；\n")
        append("2. 人物知道了这时还不该知道的事，或者说破了不该说破的；\n")
        append("3. 视角越界，写进了不该进的人物心里；\n")
        append("4. 和上一章结尾、钩子接不上的；\n")
        append("5. 因果断裂、前后说法不一的。\n")
        append("只挑确实有问题的，没有就返回空列表；不要提文笔和写法。")
        append(REVIEW_JSON)
    }

    /** 写法关：只按作者自己的写作规范审，不用模型自己的口味。 */
    fun craftReview(text: String, title: String, rules: String): String = buildString {
        append("下面是作者的写作规范：\n\n").append(rules).append("\n\n")
        append("下面是这一章「").append(title).append("」的正文：\n\n").append(text).append("\n\n")
        append("请只按上面的写作规范审这一章的写法：对白像不像人说话、旁白、场面展开和概述的取舍、重复和套话、节奏。")
        append("规范里没写的不提，不要用你自己的审美标准；不要改动事实。只挑最值得改的，至多 12 条。")
        append(REVIEW_JSON)
    }

    private const val REVIEW_JSON = "\n只输出 JSON：{\"issues\":[{\"quote\":\"原文里有问题的那一句，原样摘出，不超过 40 字\",\"problem\":\"问题在哪、依据哪一条\",\"fix\":\"怎么改，一句话\"}]}"

    /**
     * 审稿结果：容忍多余的话和 ``` 包裹；没写问题的条目丢掉。
     * 回答里找不到 {"issues":[…]} 时返回 null（不能当成“没发现问题”）。
     */
    fun parseReview(answer: String): List<ReviewIssue>? {
        val issues = runCatching { json(answer).optJSONArray("issues") }.getOrNull() ?: return null
        return (0 until issues.length()).mapNotNull { i ->
            val o = issues.optJSONObject(i) ?: return@mapNotNull null
            val problem = o.optString("problem").trim()
            if (problem.isEmpty()) null else ReviewIssue(o.optString("quote").trim().trim('“', '”', '「', '」', '"'), problem, o.optString("fix").trim())
        }
    }

    /** 本章摘要的要求：单独写摘要（[summary]）和整理时顺带写（[sync] 的 summary 字段）用同一句，两边写出来的一个样。 */
    private const val SUMMARY_RULE = "用 150 到 300 字概括这一章：谁、在哪、做了什么、结果怎样；人数、伤亡、得失、谁知道了什么这类会影响后文的事实要写准；" +
        "章末留下的钩子写一句。只写事实，不评价写法，不猜后文。"

    /** 本章摘要：给写后面的章当前情用，只写事实。 */
    fun summary(text: String, title: String): String = buildString {
        append("下面是小说的一章「").append(title).append("」：\n\n").append(text).append("\n\n")
        append("请").append(SUMMARY_RULE).append("只输出摘要本身。")
    }

    /**
     * AI 拆节拍：按细纲卡的其他几项和本章资料（在系统提示里）把这一章拆成 3 到 8 拍，每拍一句话写“发生什么”，
     * 不写文笔；[length] 是这一章的目标篇幅，按它给每拍分篇幅。[written] 是这一章已经写了的正文（可空）。
     */
    fun beatsSplit(title: String, card: ChapterCard, length: String, written: String): String = buildString {
        val lines = listOf(
            "本章事实与结果" to card.facts, "后文承接边界" to card.boundary, "视角人物" to card.pov,
            "倒计时" to card.countdown, "章末钩子" to card.hook, "推进的线" to card.threads,
        ).filter { it.second.isNotBlank() }
        if (lines.isNotEmpty()) {
            append("这一章「").append(title).append("」的细纲卡：\n")
            lines.forEach { (k, v) -> append("- ").append(k).append("：").append(v.trim()).append('\n') }
            append('\n')
        }
        if (written.isNotBlank()) append("这一章已经写了的开头：\n").append(written.take(3_000)).append("\n\n")
        append("请把这一章「").append(title).append("」拆成 3 到 8 拍（作者按拍让 AI 一段段写）。\n")
        append("- 每拍一句话，写这一步“发生什么”（谁、做了什么、结果），不写文笔、不写对白、不写景物描写。\n")
        append("- 按事情发生的先后排，覆盖细纲卡里的事实与结果，最后一拍落在章末钩子上；不加细纲卡和资料里没有的大事件。\n")
        append("- 全章篇幅").append(length).append("：按每拍的分量分篇幅，写在行尾，比如“（800字）”；分不出就不写。\n")
        append("每拍一行，行首写序号，比如：\n1. 沈默赶到渡口，发现船不见了（800字）\n只输出节拍，不要别的话。")
    }

    /** 把拆好的节拍整理成细纲卡里的写法：一行一拍，去掉序号，篇幅写成“（800字）”；最多 8 拍。没拆出来时为空。 */
    fun parseBeats(answer: String): String {
        val lines = answer.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("#") }
            // 开场白和小标题（“好的，拆成六拍：”“节拍：”）不是拍。
            .filterNot { it.endsWith("：") || it.endsWith(":") || it.startsWith("好的") || it.startsWith("以下") }
        return Beats.parse(lines.joinToString("\n")).take(8)
            .joinToString("\n") { b -> b.text + (b.length?.let { "（${it}字）" } ?: "") }
    }

    /** 拟细纲时各项的标签（AI 照这个格式回，按它拆回细纲卡）。 */
    private val PLAN_FIELDS = listOf("本章事实与结果", "后文承接边界", "视角人物", "倒计时", "章末钩子", "推进的线")

    /**
     * 拟这一章的细纲卡：按系统提示里的本章资料（总纲、前后几章的细纲或摘要、人物、察觉、伏笔），接上一章结尾，
     * 不越过下一章已经定下的细纲。[written] 是这一章已经写了的正文（可空）。
     */
    fun plan(title: String, previous: String, next: ChapterCard?, nextTitle: String?, written: String): String = buildString {
        if (previous.isNotBlank()) append("上一章结尾：\n").append(previous).append("\n\n")
        if (next != null && !next.outlineEmpty) {
            append("下一章「").append(nextTitle.orEmpty()).append("」已经定下的细纲（这一章不能写到它的事，要能接上它）：\n")
            append(listOf(next.facts, next.boundary, next.hook).filter { it.isNotBlank() }.joinToString("\n")).append("\n\n")
        }
        if (written.isNotBlank()) append("这一章已经写了的开头：\n").append(written.take(3_000)).append("\n\n")
        append("请为这一章「").append(title).append("」拟细纲卡：只划事实边界，不写文笔。\n")
        append("- 从设定资料里找这一章该发生的事（总纲、分卷速查、接续索引里写到这一章或这一段的），接上上一章结尾和前情；资料里没写到的，按总纲的走向补最小的一步，不要发明大事件。\n")
        append("- 人数、伤亡、日期、谁知道了什么要和资料一致；不写资料里后面才发生的事。\n")
        append("按下面的格式输出，每项一行（没有的项写“无”），不要别的话：\n")
        PLAN_FIELDS.forEach { append(it).append("：\n") }
    }

    /** 把拟好的细纲拆回细纲卡；“无”当空。 */
    fun parsePlan(answer: String): ChapterCard {
        val values = LinkedHashMap<String, StringBuilder>()
        var current: String? = null
        answer.lines().forEach { raw ->
            val line = raw.trim().removePrefix("- ").replace("**", "").trim()
            val field = PLAN_FIELDS.firstOrNull { line.startsWith("$it：") || line.startsWith("$it:") }
            if (field != null) {
                current = field
                values.getOrPut(field) { StringBuilder() }.append(line.substring(field.length + 1).trim())
            } else if (line.isNotEmpty()) {
                current?.let { values.getValue(it).append(if (values.getValue(it).isEmpty()) "" else "\n").append(line) }
            }
        }
        fun get(i: Int) = values[PLAN_FIELDS[i]]?.toString()?.trim()?.takeIf { it != "无" && it != "（无）" }.orEmpty()
        return ChapterCard(facts = get(0), boundary = get(1), pov = get(2), countdown = get(3), hook = get(4), threads = get(5))
    }

    /** 从写作规范里挑出能逐章数出来的硬规则，改写成灵阅的检查规则格式（见 [com.xycs.lingyuereader.data.CheckRules]）。 */
    fun rules(docs: String): String = buildString {
        append("下面是作者的写作规范：\n\n").append(docs).append("\n\n")
        append("请从中挑出能逐章机械检查的硬性规则：篇幅字数、某些说法每章至多或至少几次、禁用的词、称谓和句式、短段长段、对白占比。")
        append("按下面的格式改写，每行一条：\n")
        append("字数：3000-6000（只数汉字时写“汉字：3500-6000”）\n")
        append("至多 1：词甲、词乙（每个词各自计数）\n")
        append("合计至多 1：词甲、词乙（几个词加起来计数）\n")
        append("至少 1：词甲\n")
        append("禁用：词甲、词乙\n")
        append("句式：不是……而是……\n")
        append("短段：12 字以内至多 5 处\n")
        append("长段：300 字以上至多 2 处\n")
        append("对白占比：20%-40%\n\n")
        append("写法：词里的“……”代表一句之内中间的几个字，“某人”代表一个称呼，例如“看了某人一眼”；同一个词的几种写法用“／”连起来，例如“顿了顿／一顿”；")
        append("有不算的情况写在词后的括号里，例如“大概（除外：大概在今天）”；只限对白或只限叙述的规则，在前面加“对白”或“叙述”，例如“对白禁用：词甲”。\n")
        append("规范前后冲突时，以后写的、写明以它为准的为准。只收数得出来的规则，审美建议和写法要求不收；不要编规范里没有的规则。")
        append("可以用“#”开头的行简短注明出处。只输出规则文本，不要别的解释。")
    }

    /** 写好的草稿：去掉 ``` 包裹、章名、“本章完”，一行一段、段首不空格。 */
    fun cleanDraft(answer: String): String =
        answer.trim().lines().mapNotNull(::draftLine).filterIndexed { i, l -> keepDraftLine(l, first = i == 0) }.joinToString("\n")

    /** 一行去掉前后空白（全角空格也算）；空行、``` 围栏那一行（连同 `markdown`、`text` 标记）不要，返回空。 */
    private fun draftLine(raw: String): String? =
        raw.trim().trim('　').trim().takeIf { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("~~~") }

    /**
     * 这一行留不留：第一行（[first]）是章名的不要；“本章完”这类不要；
     * 兜底：连接层给问答加的拒答、中途停止提示不是正文（整稿请求遇到拒答本该已经报错）。
     */
    private fun keepDraftLine(line: String, first: Boolean): Boolean =
        !(first && (line.startsWith("#") || ChapterTitles.isTxtChapterTitle(line))) && !DRAFT_END.matches(line) && line !in CLIENT_NOTES

    private val DRAFT_END = Regex("^[（(]?(本章完|未完待续|全章完)[）)]?$")
    private val CLIENT_NOTES = setOf("模型拒绝回答这个问题。", "（模型中途停止了回答。）")

    /**
     * 流式写正文时边收边清理，任何时候 [text] 都和对收到的全部字做 [cleanDraft] 一样。
     * 写完的行清理一次就记下，每来一块只看还没写完的那一行：以前每来一块都把全文重新清理一遍，
     * 一章几千块、每块几个字，越写越慢（平方级），还不停地造垃圾、逼着回收内存，界面跟着卡。
     */
    class DraftStream {
        /** 清理好的整行，用换行连起来。 */
        private val done = StringBuilder()
        /** 过了第一道筛（不空、不是围栏）的行数：认第一行（章名）用。 */
        private var counted = 0
        /** 还没写完的那一行（原样）。 */
        private val tail = StringBuilder()

        fun append(piece: String) {
            var from = 0
            for (i in piece.indices) {
                val ch = piece[i]
                if (ch == '\n' || ch == '\r') {
                    tail.append(piece, from, i)
                    commit(tail.toString())
                    tail.setLength(0)
                    from = i + 1
                }
            }
            tail.append(piece, from, piece.length)
        }

        private fun commit(raw: String) {
            val line = draftLine(raw) ?: return
            val first = counted == 0
            counted++
            if (!keepDraftLine(line, first)) return
            if (done.isNotEmpty()) done.append('\n')
            done.append(line)
        }

        /** 还没写完的那一行清理后留下的；不留为空。 */
        private fun last(): String? = draftLine(tail.toString())?.takeIf { keepDraftLine(it, first = counted == 0) }

        /** 写出正文了没有（只看最后一行，每来一块都能问）。 */
        val hasText: Boolean get() = done.isNotEmpty() || last() != null

        /** 现在清理好的全文（要拼全文，节流着叫）。 */
        fun text(): String {
            val last = last() ?: return done.toString()
            return if (done.isEmpty()) last else "$done\n$last"
        }

        /** 模型去查资料了：之前说的不是回答，从头来。 */
        fun reset() {
            done.setLength(0)
            tail.setLength(0)
            counted = 0
        }
    }

    /** 去掉模型回答里的 ``` 包裹和前后空行。 */
    fun cleanRules(answer: String): String =
        answer.trim().removePrefix("```text").removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim() + "\n"

    /** 人物档案里已有的人名：整行只有名字的二级以下标题（“## 林月”；一级标题是文档名）、“- 林月：”这样的条目，或表格的第一列。 */
    fun characterNames(text: String): List<String> {
        val name = """([\p{IsHan}A-Za-z·]{1,8})"""
        val patterns = listOf(Regex("""^#{2,4}\s*$name\s*$"""), Regex("""^[-*]\s*$name[：:]"""), Regex("""^\|\s*$name\s*\|"""))
        return text.lines().mapNotNull { l -> patterns.firstNotNullOfOrNull { it.find(l.trim())?.groupValues?.get(1) } }
            .filter { it !in setOf("人物名", "人物", "姓名", "名字") }.distinct()
    }

    /** 采纳的整理结果写成人物档案的几节：已有的人物在后面补一段“（AI 整理，日期）”。 */
    fun appendCharacters(text: String, picked: List<BibleProposal>, date: String): String {
        if (picked.isEmpty()) return text
        val add = picked.joinToString("\n\n") { p -> "## ${p.name}\n${p.text}" + if (p.existing) "\n（AI 整理补充，$date）" else "" }
        return if (text.isBlank()) add else text.trimEnd() + "\n\n" + add
    }

    /**
     * 人物档案里已有这个人：把他那一节（“## 林月”标题下面、到下一个标题之前的文字）或那一条（“- 林月：……”）
     * 换成 [body]，后面注明“（AI 整理，日期）”。没有这样的一节（找不到，或只在表格里）返回空。
     */
    fun replaceCharacter(text: String, name: String, body: String, date: String): String? {
        val lines = text.lines()
        val mark = "（AI 整理，$date）"
        val newBody = body.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val heading = Regex("""^#{2,4}\s*${Regex.escape(name)}\s*$""")
        val h = lines.indexOfFirst { heading.matches(it.trim()) }
        if (h >= 0) {
            var end = h + 1
            while (end < lines.size && !lines[end].trim().startsWith("#")) end++
            val tail = lines.subList(end, lines.size)
            return (lines.subList(0, h + 1) + newBody + mark + (if (tail.isEmpty()) emptyList() else listOf("") + tail)).joinToString("\n")
        }
        val item = Regex("""^([-*]\s*)${Regex.escape(name)}\s*[：:]""")
        val i = lines.indexOfFirst { item.containsMatchIn(it.trim()) }
        if (i >= 0) {
            val indent = lines[i].takeWhile { it == ' ' || it == '\t' }
            val bullet = item.find(lines[i].trim())!!.groupValues[1]
            return lines.toMutableList().apply { this[i] = indent + bullet + name + "：" + newBody.joinToString(" ") + mark }.joinToString("\n")
        }
        return null
    }

    /** 整理本章时，提议能写进的几类文档（AI 在 kind 里写其中一个）。 */
    private val SYNC_KINDS = listOf(PackKind.CHARACTER, PackKind.CANON, PackKind.VOICE, PackKind.INVARIANT, PackKind.FORESHADOW, PackKind.AWARENESS, PackKind.REFERENCE)

    /**
     * 写完一章，整理进设定包：设定包整份在系统提示里（可以查），这里给这一章的正文和到上一章为止的故事账本（[ledger]，
     * 见 [com.xycs.lingyuereader.data.ChapterSyncs.ledgerView]，空的不给）。
     * 只要正文里明写了的：新出现的设定、和设定包对不上的写进设定包；人物状态的变化、发生的事、谁知道了什么、
     * 新埋和收掉的伏笔、故事里的时间记进账本。每条带正文原句，解析时核对。
     */
    fun sync(title: String, number: Int, text: String, part: Int = 1, parts: Int = 1, before: String = "", ledger: String = "", summary: Boolean = false): String = buildString {
        if (parts > 1) {
            // 太长的章分几段整理：每段一次请求，带上前一段的末尾接上文。
            append("这一章「").append(title).append("」（按设定算第 ").append(number).append(" 章）太长，分 ").append(parts).append(" 段整理")
            append("，下面是第 ").append(part).append(" 段。")
            if (before.isNotBlank()) append("上一段的末尾（只用来接上文，不用整理）：\n\n").append(before.trim()).append("\n\n这一段的正文：")
            append("\n\n")
        } else {
            append("下面是这一章「").append(title).append("」（按设定算第 ").append(number).append(" 章）的正文：\n\n")
        }
        append(text.take(CHAPTER_CHARS)).append("\n\n")
        if (ledger.isNotBlank()) append(ledger.trim()).append("\n\n")
        append("作者写完了这一章，请整理它：新设定和冲突写进设定包（设定包在上面），随章变化的记进故事账本。")
        append(if (parts > 1) "只整理这一段里写到的，" else "").append("只找这几类：\n")
        append("1. new（新设定）：正文里新出现、设定包里还没有的人物（称呼、身份、和谁什么关系、本章做了什么）、地名、物件、组织、规矩或设定。\n")
        append("2. conflicts（冲突）：正文和设定包写法对不上的（人数、生死、身份、称呼、年代、地点、物件下落、谁什么时候知道了什么）。")
        append("pack 摘设定包里的原话（一字不差，便于找到它），doc、section 写它在哪份文档哪一节，chapter 写正文怎么写的；")
        append("判断该改设定包（fix 写 pack，text 写改成什么）还是正文写错了（fix 写 text）。\n")
        append("3. states（人物状态）：这一章里人物的状态有了变化的。who 写谁；aspect 从这几项里选一个：").append(LEDGER_ASPECTS.joinToString("、"))
        append("；now 写现在怎样（一句话，比如“左臂受了伤，还没好”）。没变的不提。\n")
        append("4. events（发生的事）：已经发生、会影响后文的事，或世界的变化（谁死了、哪座城破了、东西到了谁手里）。what 写一句话。日常琐事不提。\n")
        append("5. awareness（察觉）：这一章里谁知道了什么、从第几章起、消息从哪来；谁对谁隐瞒、误会了什么（type 写 隐瞒 或 误会，what 写清楚瞒着谁、误会成什么）。")
        append("察觉表里已有的不要再提；和察觉表对不上的（比如表上写第 30 章才知道，这一章就说破了）放进 conflicts。\n")
        append("6. threads_opened（新伏笔）：这一章新埋下的伏笔、悬念、约定。what 写埋了什么；正文里写明了何时兑现、能确定是第几章的，payoff 写章号，否则空着。\n")
        append("7. threads_closed（收伏笔）：这一章收掉的伏笔。id 只能写上面账本“开着的伏笔”里列出的编号（比如 P1）；")
        append("没列出的不要提，账本里没有列出伏笔时给空列表。\n")
        append("8. time（时间）：这一章在故事里是什么时候，或者离上一章过了多久。when 写一句话；正文没写就空着。\n")
        // “这章写完了”：整理和写摘要合成一次请求，省一次把整章正文再发一遍。
        if (summary) append("9. summary（本章摘要）：给写后面的章当前情用。").append(SUMMARY_RULE).append("写成一段话，不分条。\n")
        append("规矩：\n")
        append("- 只提正文里明确写到的，不推测、不补全、不替作者发明设定；拿不准就不提。\n")
        append("- 设定包里已经有的不要重复提。")
        append(if (ledger.isNotBlank()) "账本里已有的（上面列出的状态、知情、开着的伏笔）也不要重复提。\n" else "\n")
        append("- 人名、称呼和设定包统一，照《称谓与语气》《人物档案》的写法；正文里用了别的称呼的写进 alias。\n")
        append("- 防剧透：只按这一章和之前的正文判断，不要用后面章节的内容。要查前文可以用工具，但最多查一两次，查不到就按已有的资料答。\n")
        append("- 每条都要带 quote：正文里一字不差的原句（一句话以内，不超过 60 字）；找不到原句的不要提。\n")
        append("- kind 从这几类里选：").append(SYNC_KINDS.joinToString("、") { it.label }).append("；人物写人物档案，地名、物件、组织、规矩写硬性设定，称呼写称谓与语气。")
        append("doc 写要写进的文档标题（设定包里有的照抄标题，没有就空着），section 写追加到哪一节（照文档里已有的小标题，没有就空着）。\n")
        append("- text 写要写进设定包的文字：一两句，只写事实，照设定包的口吻。\n")
        append("- now、what、when 都只写一句事实，人名照设定包。\n")
        append("只输出 JSON，没有的类给空列表（time 没有就给空对象）：\n")
        append("{\"new\":[{\"type\":\"人物\",\"name\":\"\",\"kind\":\"人物档案\",\"doc\":\"\",\"section\":\"\",\"alias\":\"\",\"identity\":\"\",\"relation\":\"\",\"did\":\"\",\"text\":\"\",\"quote\":\"\"}],")
        append("\"conflicts\":[{\"title\":\"\",\"kind\":\"\",\"doc\":\"\",\"section\":\"\",\"pack\":\"\",\"chapter\":\"\",\"fix\":\"pack\",\"text\":\"\",\"quote\":\"\"}],")
        append("\"states\":[{\"who\":\"\",\"aspect\":\"位置\",\"now\":\"\",\"quote\":\"\"}],")
        append("\"events\":[{\"what\":\"\",\"quote\":\"\"}],")
        append("\"awareness\":[{\"who\":\"\",\"what\":\"\",\"type\":\"知道\",\"since\":\"第").append(number).append("章\",\"source\":\"\",\"quote\":\"\"}],")
        append("\"threads_opened\":[{\"what\":\"\",\"payoff\":null,\"quote\":\"\"}],")
        append("\"threads_closed\":[{\"id\":\"P1\",\"quote\":\"\"}],")
        append("\"time\":{\"when\":\"\",\"quote\":\"\"}")
        append(if (summary) ",\"summary\":\"\"}" else "}")
    }

    /** 分段整理时带上前一段末尾多少字（接上文用）。 */
    const val SYNC_OVERLAP = 400

    /**
     * 整理本章要分几段发：不超过 [limit] 字的一段就是整章；更长的在段落（换行）处切成每段不超过 [limit] 字，
     * 一段本身就超长的才从中间硬切。
     */
    fun syncParts(text: String, limit: Int = CHAPTER_CHARS): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= limit) {
                out += text.substring(start)
                break
            }
            val cut = text.lastIndexOf('\n', start + limit - 1).takeIf { it > start + limit / 2 }?.plus(1) ?: (start + limit)
            out += text.substring(start, cut)
            start = cut
        }
        return out.filter { it.isNotBlank() }
    }

    /** 整理本章的解析结果：[dropped] 是正文里找不到原句、丢掉的条数；[summary] 是顺带写的本章摘要（没要、没回是空的）。 */
    data class SyncParse(val items: List<SyncItem>, val dropped: Int, val summary: String = "")

    /** 原句至少要有几个字（不算标点、空白）；不到这么长的，得是正文里完整的一句。 */
    const val QUOTE_CHARS = 6

    /** 短句最少几个字：“渡口起了雾。”这样完整的一句可以，“他说”“林月”不行。 */
    private const val QUOTE_SENTENCE_CHARS = 4

    /** 一句开头前面、结尾后面的字（或者在段首、段尾）。 */
    private const val SENTENCE_OPEN = "。！？!?…；;\n“「『：:"
    private const val SENTENCE_CLOSE = "。！？!?…；;\n”」』"

    /**
     * 正文里找原句：去掉两头的引号、书名号；末尾“……”省略的，按前面那截找。找不到返回空。
     * 依据要够长：去掉标点、空白至少 [QUOTE_CHARS] 字，或者是正文里完整的一句（至少 [QUOTE_SENTENCE_CHARS] 字）；
     * 去掉人名（[names]）以后不能只剩一个字——只引一个名字、一句“他说”，正文里一定找得到，证明不了什么。
     */
    fun findQuote(text: String, quote: String, names: Collection<String> = emptyList()): String? {
        val q = quote.trim().trim('“', '”', '「', '」', '『', '』', '"', '\'').trim()
        val cut = q.removeSuffix("……").removeSuffix("...").removeSuffix("…").trim()
        return listOf(q, cut).distinct().firstOrNull { enough(text, it, names) }
    }

    private fun enough(text: String, q: String, names: Collection<String>): Boolean {
        val content = q.replace(Regex("[\\p{P}\\p{S}\\s]"), "")
        if (content.length < QUOTE_SENTENCE_CHARS || q !in text) return false
        val rest = names.filter { it.length >= 2 }.sortedByDescending { it.length }.fold(content) { s, n -> s.replace(n, "") }
        if (rest.length < 2) return false
        if (content.length >= QUOTE_CHARS) return true
        // 短的：正文里有一处前后都是句子的边界（标点、段首段尾）。
        var at = text.indexOf(q)
        while (at >= 0) {
            val opens = at == 0 || text[at - 1] in SENTENCE_OPEN
            val end = at + q.length
            val closes = q.last() in SENTENCE_CLOSE || end == text.length || text[end] in SENTENCE_CLOSE
            if (opens && closes) return true
            at = text.indexOf(q, at + 1)
        }
        return false
    }

    /**
     * 整理本章的回答：容忍 ``` 包裹、前后多余的话、字段缺失；一类都没有时当作没按格式回（报错，不当成“没什么要整理”）。
     * 只回了 new、conflicts、awareness 的（旧的回法）照样能用。
     * 依据（quote）在正文 [text] 里找不到、太短或只是人名（[names]）的丢掉（[findQuote]），防止模型编造。收伏笔的编号按 [codes]（[com.xycs.lingyuereader.data.SyncLedger.codes]）
     * 认回账本里那一条，认不出的丢掉。
     */
    fun parseSync(answer: String, text: String, chapterId: String, chapter: Int, codes: Map<String, LedgerEntry> = emptyMap(), names: Collection<String> = emptyList()): SyncParse {
        val o = json(answer)
        fun arr(vararg keys: String): JSONArray? = keys.firstNotNullOfOrNull { o.optJSONArray(it) }
        val news = arr("new", "news", "新设定")
        val conflicts = arr("conflicts", "conflict", "冲突")
        val aware = arr("awareness", "aware", "察觉")
        val states = arr("states", "state", "人物状态")
        val events = arr("events", "event", "发生的事")
        val opened = arr("threads_opened", "opened", "新伏笔")
        val closed = arr("threads_closed", "closed", "收伏笔")
        // 时间：一个对象，也认只有一个对象的列表。
        val time = listOf("time", "时间").firstNotNullOfOrNull { k -> o.optJSONObject(k) ?: o.optJSONArray(k)?.optJSONObject(0) }
        val any = listOf(news, conflicts, aware, states, events, opened, closed).any { it != null } || time != null || o.has("time")
        if (!any) throw AiException("模型没有按格式回答")
        fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
        fun JSONObject.s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } }.orEmpty()
        fun kindOf(raw: String, fallback: PackKind): PackKind {
            val k = raw.trim().trim('《', '》')
            return PackKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) || it.label == k }
                ?: PackKind.entries.firstOrNull { k.isNotEmpty() && (it.label in k || k in it.label) } ?: fallback
        }
        var dropped = 0
        val out = mutableListOf<SyncItem>()
        fun add(o: JSONObject, make: (String) -> SyncItem?) {
            val q = findQuote(text, o.s("quote", "依据", "原句"), names)
            if (q == null) {
                dropped++
                return
            }
            make(q)?.let { out += it }
        }
        objects(news).forEach { n ->
            add(n) { q ->
                val type = n.s("type", "类型")
                val name = n.s("name", "名字", "名称")
                val person = type.contains("人") || kindOf(n.s("kind"), PackKind.CANON) == PackKind.CHARACTER
                val kind = if (person) PackKind.CHARACTER else kindOf(n.s("kind"), if ("称" in type) PackKind.VOICE else PackKind.CANON)
                val fields = if (person) listOf("称呼" to n.s("alias", "称呼"), "身份" to n.s("identity", "身份"), "关系" to n.s("relation", "关系"), "本章" to n.s("did", "本章", "text"))
                else emptyList()
                val body = n.s("text", "说明", "did")
                if (name.isEmpty() && body.isEmpty()) return@add null
                val label = if (person) "新人物" else type.ifEmpty { kind.label }.let { if (it.startsWith("新")) it else "新$it" }
                SyncItem(SyncGroup.NEW, "$label：${name.ifEmpty { body.take(12) }}", q, kind, n.s("doc"), n.s("section"), name, fields, body, chapterId = chapterId, chapter = chapter)
            }
        }
        objects(conflicts).forEach { c ->
            add(c) { q ->
                val fix = c.s("fix", "建议").lowercase()
                val fixText = fix == "text" || "正文" in fix
                val title = c.s("title", "标题").ifEmpty { c.s("chapter").take(20) }.ifEmpty { return@add null }
                SyncItem(
                    SyncGroup.CONFLICT, title, q, kindOf(c.s("kind"), PackKind.CANON), c.s("doc"), c.s("section"),
                    text = if (fixText) "" else c.s("text", "改成"), packSays = c.s("pack", "设定包"), textSays = c.s("chapter", "正文"),
                    fixText = fixText, chapterId = chapterId, chapter = chapter,
                )
            }
        }
        objects(aware).forEach { a ->
            add(a) { q ->
                val who = a.s("who", "人物", "谁")
                val what = a.s("what", "知道什么", "内容")
                if (who.isEmpty() || what.isEmpty()) return@add null
                val type = a.s("type", "类型")
                val since = a.s("since", "从第几章起").let { s -> s.toIntOrNull()?.let { "第${it}章" } ?: s }.ifEmpty { "第${chapter}章" }
                val shown = if (type.contains("瞒") || type.contains("误会")) "（$type）$what" else what
                val source = a.s("source", "消息从哪来", "来源")
                // 记进账本的知情：从第几章起按 since 的章号，认不出就是这一章。
                val from = ChapterRefs.number(since) ?: chapter
                SyncItem(
                    SyncGroup.AWARENESS, "$who：$shown", q, PackKind.AWARENESS, name = who,
                    fields = listOf("人物" to who, "知道什么" to shown, "从第几章起" to since, "消息从哪来" to source),
                    text = shown, chapterId = chapterId, chapter = chapter, place = SyncPlace.LEDGER,
                    entry = ledgerEntry(LedgerKind.KNOW, shown, q, chapterId, from).copy(subject = who, via = source),
                )
            }
        }
        objects(states).forEach { st ->
            add(st) { q ->
                val who = st.s("who", "人物", "谁")
                val now = st.s("now", "现在", "状态", "what")
                if (who.isEmpty() || now.isEmpty()) return@add null
                val raw = st.s("aspect", "方面")
                // 方面照 LEDGER_ASPECTS 写；写成“所在位置”“身体状况”的认成那一项，别的照写。
                val aspect = LEDGER_ASPECTS.firstOrNull { it == raw } ?: LEDGER_ASPECTS.firstOrNull { raw.isNotEmpty() && (it in raw || raw in it) } ?: raw
                SyncItem(
                    SyncGroup.STATE, "$who" + (if (aspect.isNotEmpty()) "·$aspect" else "") + "：$now", q, PackKind.CHARACTER, name = who,
                    text = now, chapterId = chapterId, chapter = chapter, place = SyncPlace.LEDGER,
                    entry = ledgerEntry(LedgerKind.STATE, now, q, chapterId, chapter).copy(subject = who, aspect = aspect),
                )
            }
        }
        objects(events).forEach { e ->
            add(e) { q ->
                val what = e.s("what", "事", "内容", "text").ifEmpty { return@add null }
                SyncItem(
                    SyncGroup.EVENT, what, q, PackKind.CANON, text = what, chapterId = chapterId, chapter = chapter, place = SyncPlace.LEDGER,
                    entry = ledgerEntry(LedgerKind.EVENT, what, q, chapterId, chapter),
                )
            }
        }
        objects(opened).forEach { p ->
            add(p) { q ->
                val what = p.s("what", "内容", "text").ifEmpty { return@add null }
                val raw = p.s("payoff", "计划第几章收")
                val payoff = (raw.toIntOrNull() ?: ChapterRefs.number(raw) ?: ChapterRefs.number("第${raw}章"))?.takeIf { it > 0 }
                SyncItem(
                    SyncGroup.PROMISE, "埋下：$what", q, PackKind.FORESHADOW, text = what, chapterId = chapterId, chapter = chapter, place = SyncPlace.LEDGER,
                    entry = ledgerEntry(LedgerKind.PROMISE, what, q, chapterId, chapter).copy(payoff = payoff),
                )
            }
        }
        objects(closed).forEach { c ->
            // 编号认不出（编的、没列出的）丢掉，不算“找不到原句”。
            val code = c.s("id", "编号").uppercase().let { if (it.firstOrNull()?.isDigit() == true) "P$it" else it }
            val promise = codes[code] ?: return@forEach
            add(c) { q ->
                SyncItem(
                    SyncGroup.PROMISE, "收起：${promise.text}", q, PackKind.FORESHADOW, text = promise.text,
                    chapterId = chapterId, chapter = chapter, place = SyncPlace.LEDGER, closes = promise,
                )
            }
        }
        time?.let { t ->
            val at = t.s("when", "time", "时间", "text")
            if (at.isEmpty()) return@let
            add(t) { q ->
                SyncItem(
                    SyncGroup.TIME, "时间：$at", q, PackKind.CANON, text = at, chapterId = chapterId, chapter = chapter, place = SyncPlace.LEDGER,
                    entry = ledgerEntry(LedgerKind.TIME, at, q, chapterId, chapter),
                )
            }
        }
        // 摘要不用核对原句：它是概括，不是摘抄；作者在收尾页上看过、改过才存。
        val summary = listOf("summary", "摘要", "本章摘要").firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } }.orEmpty()
        return SyncParse(out.distinctBy { it.key }, dropped, summary)
    }

    /** 整理出来、要记进账本的一条：第 [from] 章起，依据是正文原句 [quote]。 */
    private fun ledgerEntry(kind: LedgerKind, text: String, quote: String, chapterId: String, from: Int) =
        LedgerEntry(kind = kind, text = text, from = from.takeIf { it > 0 }, quote = quote, chapterId = chapterId, origin = LedgerOrigin.SYNC)

    /** 从模型回答里取出 JSON 对象：容忍前后多余的话和 ```json 包裹。 */
    private fun json(answer: String): JSONObject {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("模型没有按格式回答")
        return runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrElse { throw AiException("模型没有按格式回答") }
    }

    /** 写作建议的回答：只留原文里真找得到、确实有改动的。 */
    fun parseIssues(answer: String, paragraphs: List<String>): List<WriteIssue> {
        val a = json(answer).optJSONArray("issues") ?: throw AiException("写作建议回答缺少 issues 数组，尚未完成检查")
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: throw AiException("写作建议回答的 issues 条目格式有误，尚未完成检查")
            val index = when (val value = o.opt("p")) {
                is Int -> value
                is Long -> value.takeIf { it in 1L..paragraphs.size.toLong() }?.toInt()
                else -> null
            }?.takeIf { it in 1..paragraphs.size }
                ?: throw AiException("写作建议回答的段落编号格式有误，尚未完成检查")
            val wrong = (o.opt("wrong") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw AiException("写作建议回答缺少要修改的原文，尚未完成检查")
            // Empty replacement is a valid deletion; the field itself must still be a string.
            val right = (o.opt("right") as? String)?.trim()
                ?: throw AiException("写作建议回答缺少修改内容，尚未完成检查")
            val p = paragraphs[index - 1]
            if (wrong == right || wrong !in p) return@mapNotNull null
            WriteIssue(p, wrong, right, o.optString("reason").trim(), SuggestKind.of(o.optString("kind").trim().uppercase()).name, o.optString("source").trim())
        }.distinctBy { it.paragraph to it.wrong }
    }

    fun parseProposals(answer: String, existing: List<String>): List<BibleProposal> {
        val a = json(answer).optJSONArray("entries") ?: JSONArray()
        val names = existing.map { it.trim() }.toSet()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            val text = o.optString("text").trim()
            if (name.isEmpty() || text.isEmpty()) null else BibleProposal(name, text, name in names)
        }.distinctBy { it.name }.take(20)
    }

    /** 模型有时还是会加“修改后：”或把整段包进引号：去掉。 */
    fun clean(answer: String, original: String): String {
        var t = answer.trim()
        t = t.removePrefix("```").removeSuffix("```").trim()
        t = Regex("""^(修改后|改写后|润色后|改后|续写|结果)[：:]\s*""").replace(t, "")
        val quoted = (t.startsWith("「") && t.endsWith("」")) || (t.startsWith("“") && t.endsWith("”")) || (t.startsWith("\"") && t.endsWith("\""))
        val originallyQuoted = original.trim().let { it.isNotEmpty() && it.first() == t.first() && it.last() == t.last() }
        if (quoted && !originallyQuoted && t.length > 2) t = t.substring(1, t.length - 1).trim()
        // 一行一段：去掉空行和段首空格。
        return t.lines().map { it.trim().trim('　').trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** 记一条采纳的修改：只留最近 [WorkAssist.KEEP_EXAMPLES] 条，太长的截断。 */
    fun remember(assist: WorkAssist, action: WriteAction, before: String, after: String, at: Long): WorkAssist =
        if (action == WriteAction.CONTINUE) assist else remember(assist, action.label, before, after, at)

    /** 记一条采纳的修改，[label] 是哪种改法（“润色”“去 AI 腔”）。 */
    fun remember(assist: WorkAssist, label: String, before: String, after: String, at: Long): WorkAssist {
        if (before.isBlank()) return assist
        val e = StyleExample(label, before.take(300), after.take(400), at)
        return assist.copy(accepted = (assist.accepted + e).takeLast(WorkAssist.KEEP_EXAMPLES))
    }

    /**
     * 去 AI 腔：[paragraphs] 是含标出说法的段落（段落原文、标出的那几处）。只改标出的说法和它所在的那一句，
     * 最小改动；回答和写作建议一样的 JSON，按 [parseIssues] 读。
     */
    fun decliche(paragraphs: List<Pair<String, List<String>>>): String = buildString {
        append("下面是作者小说里的几段正文，每段前面是编号，后面方括号里是本机标出的 AI 腔说法：\n\n")
        paragraphs.forEachIndexed { i, (p, marks) ->
            append(i + 1).append(". ").append(p).append('\n')
            append("   [标出：").append(marks.joinToString("、") { "「$it」" }).append("]\n")
        }
        append("\n请去掉这些 AI 腔：只改标出的说法和它所在的那一句，能少改就少改。")
        append("换成具体的动作、细节或直接删掉；不改情节，不改对白的内容，不动没标出的句子，不加新的比喻和形容词。\n")
        append("wrong 必须是原文里一字不差的一小段（包含标出的说法，最长一句），right 是改好的这一小段（删掉就写空串），reason 用几个字说怎么改的。\n")
        append("每处标出的说法最多一条；改不好、改了反而别扭的就不列。")
        append("只输出 JSON：{\"issues\":[{\"p\":1,\"wrong\":\"\",\"right\":\"\",\"reason\":\"\"}]}，都不用改就输出 {\"issues\":[]}。")
    }
}

/** 文字对比：按字比，标出删掉的和加上的，给“采纳前看看改了哪里”用。 */
object TextDiff {
    enum class Kind { SAME, DELETED, INSERTED }
    data class Piece(val kind: Kind, val text: String)

    /**
     * 改动太多（编辑距离超过 [maxEdits]）时返回空，界面就只显示改后的全文。
     * 用 Myers 的“中间蛇”二分（线性空间）：内存只和 [maxEdits] 成正比；超过上限时在找的途中就放弃，不先算完。
     */
    fun diff(a: String, b: String, maxEdits: Int = 3_000): List<Piece>? {
        val out = Out()
        return if (compare(a, 0, a.length, b, 0, b.length, maxEdits, out)) out.done() else null
    }

    /** 收集结果：相邻同类的合成一段。 */
    private class Out {
        private val pieces = mutableListOf<Piece>()
        private var kind: Kind? = null
        private val buf = StringBuilder()

        fun add(k: Kind, s: CharSequence) {
            if (s.isEmpty()) return
            if (k != kind) flush()
            kind = k
            buf.append(s)
        }

        fun done(): List<Piece> {
            flush()
            return pieces
        }

        private fun flush() {
            kind?.let { if (buf.isNotEmpty()) pieces += Piece(it, buf.toString()) }
            buf.setLength(0)
            kind = null
        }
    }

    /** 比 a[a0, a1) 和 b[b0, b1)，结果按次序放进 [out]。改动超过 [maxEdits] 时返回假。 */
    private fun compare(a: String, a0: Int, a1: Int, b: String, b0: Int, b1: Int, maxEdits: Int, out: Out): Boolean {
        // 去掉相同的头尾，只比中间。
        var aS = a0
        var bS = b0
        while (aS < a1 && bS < b1 && a[aS] == b[bS]) { aS++; bS++ }
        var aE = a1
        var bE = b1
        while (aE > aS && bE > bS && a[aE - 1] == b[bE - 1]) { aE--; bE-- }
        out.add(Kind.SAME, a.subSequence(a0, aS))
        val n = aE - aS
        val m = bE - bS
        when {
            n == 0 -> out.add(Kind.INSERTED, b.subSequence(bS, bE))
            m == 0 -> out.add(Kind.DELETED, a.subSequence(aS, aE))
            else -> {
                val split = bisect(a, aS, n, b, bS, m, maxEdits)
                when {
                    split != null -> {
                        val (x, y) = split
                        if (!compare(a, aS, aS + x, b, bS, bS + y, maxEdits, out)) return false
                        if (!compare(a, aS + x, aE, b, bS + y, bE, maxEdits, out)) return false
                    }
                    // 一个字都不一样：全删全加。
                    n + m <= maxEdits -> {
                        out.add(Kind.DELETED, a.subSequence(aS, aE))
                        out.add(Kind.INSERTED, b.subSequence(bS, bE))
                    }
                    else -> return false
                }
            }
        }
        out.add(Kind.SAME, a.subSequence(aE, a1))
        return true
    }

    /**
     * 找 a[aS, aS+n) 和 b[bS, bS+m) 最短编辑路径的中点（从两头同时往中间找），返回中点在两段里的位置。
     * 两段没有一个字相同，或者改动超过 [maxEdits]（找到一半还没碰头）时返回空。
     */
    private fun bisect(a: String, aS: Int, n: Int, b: String, bS: Int, m: Int, maxEdits: Int): Pair<Int, Int>? {
        // 前后各走 d 步碰头时，编辑距离约为 2d：超过上限的一半就不用再找了。
        val dMax = minOf((n + m + 1) / 2, maxEdits / 2 + 2)
        val offset = dMax
        val size = 2 * dMax + 2
        val v1 = IntArray(size) { -1 }
        val v2 = IntArray(size) { -1 }
        v1[offset + 1] = 0
        v2[offset + 1] = 0
        val delta = n - m
        // 差的长度是奇数时，从前往后走的那一头先碰上。
        val front = delta % 2 != 0
        var k1start = 0
        var k1end = 0
        var k2start = 0
        var k2end = 0
        for (d in 0 until dMax) {
            // 从前往后。
            var k1 = -d + k1start
            while (k1 <= d - k1end) {
                val k1o = offset + k1
                var x1 = if (k1 == -d || (k1 != d && v1[k1o - 1] < v1[k1o + 1])) v1[k1o + 1] else v1[k1o - 1] + 1
                var y1 = x1 - k1
                while (x1 < n && y1 < m && a[aS + x1] == b[bS + y1]) { x1++; y1++ }
                v1[k1o] = x1
                if (x1 > n) {
                    k1end += 2
                } else if (y1 > m) {
                    k1start += 2
                } else if (front) {
                    val k2o = offset + delta - k1
                    if (k2o in 0 until size && v2[k2o] != -1 && x1 >= n - v2[k2o]) return x1 to y1
                }
                k1 += 2
            }
            // 从后往前。
            var k2 = -d + k2start
            while (k2 <= d - k2end) {
                val k2o = offset + k2
                var x2 = if (k2 == -d || (k2 != d && v2[k2o - 1] < v2[k2o + 1])) v2[k2o + 1] else v2[k2o - 1] + 1
                var y2 = x2 - k2
                while (x2 < n && y2 < m && a[aS + n - x2 - 1] == b[bS + m - y2 - 1]) { x2++; y2++ }
                v2[k2o] = x2
                if (x2 > n) {
                    k2end += 2
                } else if (y2 > m) {
                    k2start += 2
                } else if (!front) {
                    val k1o = offset + delta - k2
                    if (k1o in 0 until size && v1[k1o] != -1) {
                        val x1 = v1[k1o]
                        val y1 = x1 - (k1o - offset)
                        if (x1 >= n - x2) return x1 to y1
                    }
                }
                k2 += 2
            }
        }
        return null
    }
}
