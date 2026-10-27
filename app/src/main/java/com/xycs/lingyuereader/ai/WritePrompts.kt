package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.ChapterRefs
import com.xycs.lingyuereader.data.ChapterTitles
import com.xycs.lingyuereader.data.LEDGER_ASPECTS
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.ReviewEvidence
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.SyncGroup
import com.xycs.lingyuereader.data.SyncItem
import com.xycs.lingyuereader.data.SyncPlace
import com.xycs.lingyuereader.data.WorkAssist
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

/** 写作建议的几类：作者可以分别开关。 */
enum class SuggestKind(val label: String, val ask: String) {
    TYPO("错字", "错别字、用错的词、漏字多字、标点误用、明显的病句"),
    CANON("设定", "和设定包冲突的：人数、生死、日期、地点、官职、称呼、物件；人物知道了按察觉表这时还不该知道的事；提前写出后面才发生的事"),
    STYLE("规范", "违反作者写作约定、写作规范的：禁用词、对白写法、旁白腔、篇幅节奏这类明写了的规矩（只按作者写明的规矩，不按你自己的口味）"),
    VOICE("口吻", "人物说话不像他：和设定包里这个人的身份、称谓、说话方式不符"),
    ;

    companion object {
        fun of(name: String): SuggestKind = entries.firstOrNull { it.name == name } ?: TYPO
    }
}

/** 在编辑器里让 AI 接着写：写多少。 */
enum class InlineMode(val label: String) {
    SCENE("写下一场"),
    TO_END("写到本章结尾"),
    WORDS("写 500 字左右"),
    CUSTOM("按要求写…"),
}

/** 设定集里 AI 从正文整理出的一条：[existing] 为真时是更新已有的条目。 */
data class BibleProposal(val name: String, val text: String, val existing: Boolean)

/**
 * 写作助手发给模型的话。原则：保留作者的文风，不擅自改情节；改稿类只输出结果；
 * 设定集、作者的文风偏好、作者采纳过的改法都放进系统提示里。
 */
object WritePrompts {
    /** A complete chapter is composed from a bounded task, never from the review or repair template. */
    fun projectDraft(task: ChapterWritingTask, rules: String): String = buildString {
        append(task.render()).append("\n\n【当前阶段：创作】\n")
        append("请写出这一章完整、连贯、好读的小说正文，篇幅遵守本次章节任务。先抓本章主戏与关键选择，再组织场面。")
        append("只按当前材料和明确认可的前文写，不调用旧稿、旧版项目包或外部史料改写已定故事；未读取的不声称读过。\n")
        append("对白先想此人对谁说、双方关系、此刻所求和情绪。允许长说、答半句、改口、打断、重复和不接话，")
        append("保留普通生活话与自然语气，不让人人精准概括、轮流讲课或随时说明责任。\n")
        append("旁白可讲必要背景、有限心理、闲笔、机锋与温度；解释到读者理解当前选择为止。对白、行动和结果已经说明的意思，不换词再总结。")
        append("不把事实核验写成论文，不把细纲条目排成办事流水账。事实底稿供核对，不逐项表演；重要选择与交锋写足，重复手续概述。\n")
        append("消息须有亲见、传递或有根据的推断，核清人物知情、能力、资源和命令权限；跨地留足路程与时间。")
        append("不为凑字重复解释，不为固定场次或节奏模板改变章界与不可逆结果。\n")
        if (rules.isNotBlank()) append("\n作者的写作规范与检查规则：\n").append(rules.trim()).append('\n')
        append("\n完成后在内部连读对白，再连读完整章节，修掉机械模板、统一神态和口号收尾；保留有效的普通话、直接心理和生活细节。")
        append("正文中不放准备、提纲、审校说明、待核增量或通过记录。当前请求的输出格式优先：只输出完整正文，一行一段，不加Markdown或解释；章名由软件单独管理。")
    }

    /** Structured evidence is kept outside the manuscript; a missing source is not a proven error. */
    fun projectReview(task: ChapterWritingTask, text: String, rules: String, evidence: ReviewEvidence? = null): String = buildString {
        append(task.render()).append("\n\n【当前阶段：审读】\n")
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

    /** Repair is based on checked findings, not permission to invent new facts or replace every voice. */
    fun projectRevision(task: ChapterWritingTask, text: String, review: String, rules: String): String = buildString {
        append(task.render()).append("\n\n【当前阶段：修订】\n")
        append("当前完整章稿：\n").append(text).append("\n\n有可定位来源、仍须核对含义的待处理审读意见：\n").append(review).append("\n\n")
        if (rules.isNotBlank()) append("作者规范与规则：\n").append(rules.trim()).append("\n\n")
        append("逐条回原稿与目标章核实意见。修正有依据的事实、因果、视角、承接和明确写法违规；审读意见是线索，")
        append("不自动成为新设定。误读原稿、资料不足或违背细纲的意见不照办；审美建议按整章效果取舍。\n")
        append("局部问题在原处修，因果断裂才联动相关场面。保留有效交锋、对白、心理、旁白、生活细节和人物各自的目标；")
        append("不把所有对白改短，不句句加动作或语气词，不用漂亮总结替代人物当时说的话。")
        append("写足关键选择与实际代价，删并重复手续和解释，严守篇幅、章界、人物知情与权限。\n")
        append("两遍校稿：第一遍连读对白并核事实、章界与机械套句；第二遍连读全章，检查改动造成的接话、动作与情绪断裂。")
        append("修改后旧审核与待核状态增量均作废，必须针对新稿重新检查；不把修改过视为已通过或作者已定。\n")
        append("只交付修订后的完整正文，一行一段，不加Markdown、说明或意见处理表；章名由软件单独管理。")
    }

    /**
     * 哪些改稿要带文风范例：要写出新句子的（润色、更生动、扩写、缩写、按要求改、续写）。
     * 挑错只改错字，不学文风；写作建议、审稿、整理进设定包等也不带。
     */
    fun usesSamples(action: WriteAction): Boolean = action != WriteAction.PROOFREAD

    /** 改稿时带上前文多少字、后文多少字。 */
    const val BEFORE_CHARS = 1_500
    const val AFTER_CHARS = 300
    /** 整章点评、矛盾检查最多发多少字。 */
    const val CHAPTER_CHARS = 14_000

    /** 能查设定包时加在系统提示后面：要用就查，别凭印象编；查完直接作答。 */
    const val TOOL_GUIDE = "设定包里没整份给出的内容（某一章正文、细纲原文、某个人物、某个数字、谁什么时候知道了什么），" +
        "拿不准就先用工具查，不要凭印象编。查够了就直接作答：回答里不要复述查到的资料，也不要说你查了什么。"

    /**
     * [pack] 是拼好的设定包（见 [com.xycs.lingyuereader.data.PackContext]），为空时不带设定。
     * [samples] 是文风范例（[com.xycs.lingyuereader.data.StyleSamples.render]）：放在文风要求之后、本章资料之前——
     * 它在同一部作品里很少变，放在前面，连同知识库成为稳定的开头，服务商能缓存；本章资料每章不同，放在它后面。
     */
    fun system(title: String, assist: WorkAssist, pack: String? = null, examples: Boolean = false, samples: String? = null): String = buildString {
        append("你是写作软件“灵阅”里的编辑助手，在帮作者写长篇小说")
        if (title.isNotBlank()) append("《").append(title).append("》")
        append("。\n")
        append("- 用简体中文。尊重作者：保留作者的文风、人称、时态和用词习惯，不擅自改变情节和人物设定，不说教。\n")
        append("- 要你改写或续写时，只输出正文本身：不要解释，不要加标题、引号、序号或“修改后：”之类的话，不用 Markdown。\n")
        append("- 一行就是一段，段与段之间换行，不要空行，段首不要空格。\n")
        if (assist.style.isNotBlank()) append("\n作者对文风的要求（最优先，和下面的设定资料冲突时以这里为准）：").append(assist.style.trim()).append('\n')
        if (!samples.isNullOrBlank()) append("\n").append(samples.trim()).append('\n')
        if (!pack.isNullOrBlank()) append("\n").append(pack).append("\n写的内容不要和这些设定矛盾。\n")
        if (examples) {
            val picked = assist.accepted.filter { it.before.length <= 200 && it.after.length <= 260 }.takeLast(3)
            if (picked.isNotEmpty()) {
                append("\n作者以前采纳过的修改（照这个分寸来改）：\n")
                picked.forEach { e -> append("原文：").append(e.before).append("\n改为：").append(e.after).append('\n') }
            }
            val declined = assist.declined.filter { it.before.length <= 200 && it.after.length <= 260 }.takeLast(2)
            if (declined.isNotEmpty()) {
                append("\n作者没有采纳的改法（不要这样改）：\n")
                declined.forEach { e -> append("原文：").append(e.before).append("\n没采纳的改法：").append(e.after).append('\n') }
            }
        }
    }

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

    /** 改一段：[target] 是要改的文字（续写时是光标前的上文），[before]、[after] 是它前后的原文。 */
    fun edit(action: WriteAction, target: String, before: String, after: String, chapterTitle: String, instruction: String = ""): String = buildString {
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
    }

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

    /** 前后矛盾检查：设定集在系统提示里，这里给上一章结尾和本章。 */
    fun consistency(previous: String, chapter: String, chapterTitle: String): String = buildString {
        if (previous.isNotBlank()) append("上一章的结尾：\n").append(previous).append("\n\n")
        append("本章「").append(chapterTitle).append("」：\n").append(chapter.take(CHAPTER_CHARS)).append("\n\n")
        append("检查本章和作品设定、和上一章之间有没有前后矛盾：人名地名写法不一致、人物的年龄身份能力关系对不上、")
        append("已经死去或离开的人物又出现、时间线和季节天气冲突、物品下落不一致、伏笔被遗忘等。\n")
        append("逐条列出发现的问题，每条写明本章原文的哪句话、和什么矛盾。只列有把握的；没有发现就只回答“没有发现明显的前后矛盾”。")
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
        append("设定、规范、口吻这几类必须在上面给出的设定里有明确依据，拿不准就不提。\n")
        append("wrong 必须是原文里一字不差的一小段（尽量短，三五个字到一句话），right 是改好的这一小段，reason 用一句话说原因，")
        append("kind 是 ").append(kinds.joinToString("、") { it.name + "（" + it.label + "）" }).append(" 之一，source 写依据出自哪份文档哪一节（错字不用写）。\n")
        append("每段最多 3 处；没有问题的段不要列。只输出 JSON：{\"issues\":[{\"p\":1,\"wrong\":\"\",\"right\":\"\",\"reason\":\"\",\"kind\":\"\",\"source\":\"\"}]}，全都没问题就输出 {\"issues\":[]}。")
    }

    /**
     * 在编辑器里接着光标往下写：[before] 是光标前的正文（前面不够时接上一章结尾），[after] 是光标后面已经写了的。
     * [length] 是全章篇幅要求，[instruction] 是作者这次的要求。
     */
    fun continueAt(title: String, before: String, after: String, mode: InlineMode, length: String, instruction: String, rules: String): String = buildString {
        if (rules.isNotBlank()) append("作者的写作规范和检查规则（必须遵守）：\n").append(rules).append("\n\n")
        append("这一章「").append(title).append("」写到光标处的正文：\n").append(before).append("\n【光标在这里】\n")
        if (after.isNotBlank()) append("光标后面已经写了（只作语境，不要重复它，写的内容要能接上它）：\n").append(after).append('\n')
        append("\n请从光标处接着往下写正文。")
        append(
            when (mode) {
                InlineMode.SCENE -> "写完眼下这一场就停，停在场面自然收住的地方。"
                InlineMode.TO_END -> "按细纲卡完成本章剩余的关键选择与结果，在既定章末自然收束；全章篇幅$length，不强加悬念。"
                InlineMode.WORDS -> "写 500 字左右，停在一句话说完的地方。"
                InlineMode.CUSTOM -> ""
            },
        )
        if (instruction.isNotBlank()) append("\n作者的要求：").append(instruction.trim())
        append("\n- 接着上文最后一个字写：上文最后一段没写完就接着写这一段，要另起一段就换行。不要重复上文。")
        append("\n- 细纲卡里的事实、人物知道什么不知道什么，以设定资料为准；拿不准就先用工具查。")
        append("\n- 只输出接下去的正文：一行一段，段首不空格；不要章名、小标题、解释。")
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

    /** 本章摘要：给写后面的章当前情用，只写事实。 */
    fun summary(text: String, title: String): String = buildString {
        append("下面是小说的一章「").append(title).append("」：\n\n").append(text).append("\n\n")
        append("请用 150 到 300 字概括这一章：谁、在哪、做了什么、结果怎样；人数、伤亡、得失、谁知道了什么这类会影响后文的事实要写准；")
        append("章末留下的钩子写一句。只写事实，不评价写法，不猜后文。只输出摘要本身。")
    }

    /** 拟细纲时各项的标签（AI 照这个格式回，按它拆回细纲卡）。 */
    private val PLAN_FIELDS = listOf("本章事实与结果", "后文承接边界", "视角人物", "倒计时", "章末钩子", "推进的线")

    /**
     * 拟这一章的细纲卡：按系统提示里的本章资料（总纲、前后几章的细纲或摘要、人物、察觉、伏笔），接上一章结尾，
     * 不越过下一章已经定下的细纲。[written] 是这一章已经写了的正文（可空）。
     */
    fun plan(title: String, previous: String, next: ChapterCard?, nextTitle: String?, written: String): String = buildString {
        if (previous.isNotBlank()) append("上一章结尾：\n").append(previous).append("\n\n")
        if (next != null && !next.isEmpty) {
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

    /**
     * AI 写一章：按细纲卡（在系统提示里）写，接上一章结尾，照写作规范和检查规则。
     * [length] 是篇幅要求（“3000 到 6000 字”），[instruction] 是作者这次的补充要求（可空）。
     */
    fun draft(title: String, previous: String, rules: String, length: String, instruction: String): String = buildString {
        if (previous.isNotBlank()) append("上一章结尾：\n").append(previous).append("\n\n")
        if (rules.isNotBlank()) append("作者的写作规范和检查规则（必须遵守）：\n").append(rules).append("\n\n")
        append("请写出这一章「").append(title).append("」的正文。\n")
        append("- 按本章细纲卡写：保留事实成立的条件、关键选择和结果；事实底稿供核对，不逐项表演，不越过章界与后文承接边界，不提前写后面的事。\n")
        append("- 接上上一章的结尾和钩子，人物知道什么、不知道什么以设定资料为准。\n")
        append("- 篇幅").append(length).append("；故事写完就收，不为凑字数加插曲。\n")
        if (instruction.isNotBlank()) append("- 作者这次的要求：").append(instruction.trim()).append('\n')
        append("- 一行一段，段首不空格；不要章名、小标题，不要写“本章完”，也不要任何解释。")
    }

    /** 草稿没写完（被截断或作者要接着写）：从已写的结尾接下去。 */
    fun draftMore(title: String, written: String, rules: String, length: String): String = buildString {
        if (rules.isNotBlank()) append("作者的写作规范和检查规则（必须遵守）：\n").append(rules).append("\n\n")
        append("这一章「").append(title).append("」已经写了下面这些（只给出最后一部分）：\n\n").append(written.takeLast(4_000)).append("\n\n")
        append("请从最后一句接着往下写，完成细纲卡的关键选择与结果，在既定章末自然收束，不强加悬念。全章篇幅").append(length).append("。")
        append("不要重复已经写的内容，不要总结。一行一段，不要任何解释。")
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
    fun cleanDraft(answer: String): String {
        val lines = answer.trim().lines()
            .map { it.trim().trim('　').trim() }
            // Drop the entire fence line, including an optional `markdown` / `text` label.
            .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("~~~") }
            .filterIndexed { i, l -> !(i == 0 && (l.startsWith("#") || ChapterTitles.isTxtChapterTitle(l))) }
            .filterNot { Regex("^[（(]?(本章完|未完待续|全章完)[）)]?$").matches(it) }
            // 兜底：连接层给问答加的拒答、中途停止提示不是正文（整稿请求遇到拒答本该已经报错）。
            .filterNot { it == "模型拒绝回答这个问题。" || it == "（模型中途停止了回答。）" }
        return lines.joinToString("\n")
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
    fun sync(title: String, number: Int, text: String, part: Int = 1, parts: Int = 1, before: String = "", ledger: String = ""): String = buildString {
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
        append("\"time\":{\"when\":\"\",\"quote\":\"\"}}")
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

    /** 整理本章的解析结果：[dropped] 是正文里找不到原句、丢掉的条数。 */
    data class SyncParse(val items: List<SyncItem>, val dropped: Int)

    /** 正文里找原句：去掉两头的引号、书名号；末尾“……”省略的，按前面那截找。找不到返回空。 */
    fun findQuote(text: String, quote: String): String? {
        var q = quote.trim().trim('“', '”', '「', '」', '『', '』', '"', '\'').trim()
        if (q.length < 2) return null
        if (q in text) return q
        q = q.removeSuffix("……").removeSuffix("...").removeSuffix("…").trim()
        return q.takeIf { it.length >= 4 && it in text }
    }

    /**
     * 整理本章的回答：容忍 ``` 包裹、前后多余的话、字段缺失；一类都没有时当作没按格式回（报错，不当成“没什么要整理”）。
     * 只回了 new、conflicts、awareness 的（旧的回法）照样能用。
     * 依据（quote）在正文 [text] 里找不到的丢掉，防止模型编造。收伏笔的编号按 [codes]（[com.xycs.lingyuereader.data.SyncLedger.codes]）
     * 认回账本里那一条，认不出的丢掉。
     */
    fun parseSync(answer: String, text: String, chapterId: String, chapter: Int, codes: Map<String, LedgerEntry> = emptyMap()): SyncParse {
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
            val q = findQuote(text, o.s("quote", "依据", "原句"))
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
        return SyncParse(out.distinctBy { it.key }, dropped)
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
    fun remember(assist: WorkAssist, action: WriteAction, before: String, after: String, at: Long): WorkAssist {
        if (action == WriteAction.CONTINUE || before.isBlank()) return assist
        val e = StyleExample(action.label, before.take(300), after.take(400), at)
        return assist.copy(accepted = (assist.accepted + e).takeLast(WorkAssist.KEEP_EXAMPLES))
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
