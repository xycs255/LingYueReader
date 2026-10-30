package com.xycs.lingyuereader

import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.LearnCandidate
import com.xycs.lingyuereader.data.SyncGroup

/**
 * “这章写完了”收尾到哪一步了：
 * [SETUP] 勾选要做的；[RUNNING] 在审稿、整理、写摘要；[REVIEW_FOUND] 审出问题停下，等作者选“回去改”还是“继续收尾”；
 * [AI_FAILED] 有一步没成，可以重试；[CONFIRM] 在同一页确认摘要和整理结果；[APPLYING] 正在写入、定稿；[DONE] 收完了。
 */
enum class FinishStage { SETUP, RUNNING, REVIEW_FOUND, AI_FAILED, CONFIRM, APPLYING, DONE }

/** 收尾里的一步现在怎样：等着、在做、好了、出错，或者这次不做（没勾）。 */
enum class StepState(val label: String) { WAITING("等着"), RUNNING("在做"), DONE("好了"), FAILED("出错"), SKIPPED("不做") }

/** 点“完成收尾”后依次写的几样；写成了的记在 [FinishRun.wrote] 里，重试时跳过，不重复写。 */
enum class FinishWrite(val label: String) { SYNC("写入整理条目"), SUMMARY("把摘要写进细纲卡"), LEARN("记下学到的"), FINAL("定稿") }

/**
 * 一章的收尾（存在 [WritingAi] 里：中途离开页面再回来还在；进程被杀就从头来）。
 * [options] 是这次实际要做的（AI 没配置时整理、摘要、审稿都不做）；[session] 是整理结果，勾选、改过的照整理页的样子存；
 * [draft] 是作者可以改的摘要，[oldSummary] 是进确认时细纲卡里已有的摘要，[keepOld] 为真就留旧的、不写新的。
 */
data class FinishRun(
    val workId: String,
    val chapterId: String,
    val options: FinishOptions = FinishOptions(),
    val stage: FinishStage = FinishStage.SETUP,
    val review: StepState = StepState.WAITING,
    val sync: StepState = StepState.WAITING,
    val summary: StepState = StepState.WAITING,
    /** 审出来还没处理的问题条数。 */
    val reviewIssues: Int = 0,
    val reviewError: String? = null,
    val syncError: String? = null,
    val summaryError: String? = null,
    val session: SyncSession? = null,
    val draft: String = "",
    val oldSummary: String = "",
    val keepOld: Boolean = false,
    val wrote: Set<FinishWrite> = emptySet(),
    /** 写入时没成的那一步和原因（“把摘要写进细纲卡没成：……”）；重试成功后清掉。 */
    val failedWrite: FinishWrite? = null,
    val writeError: String? = null,
    /** 这章有没有采纳进正文的 AI 原稿：没有就不显示“从这章的改稿里学”（打开收尾页时在后台读）。 */
    val learnable: Boolean = false,
    val learn: StepState = StepState.WAITING,
    /** 改稿学习没做全的原因（AI 归纳没成、段落对不齐）：照实给作者看，本机比对出的照样能勾。 */
    val learnError: String? = null,
    /** 提给作者的候选和勾了的（[LearnCandidate.key]）。 */
    val candidates: List<LearnCandidate> = emptyList(),
    val learnPicked: Set<String> = emptySet(),
    /** 作者明确不要的（取消过勾选、点了“不要”）：完成收尾时只有这些和没勾的本机候选记进“不再提”。 */
    val learnDeclined: Set<String> = emptySet(),
    /** 整理交接卡（给下一章接着写）：整理好就存下，没成不拦收尾，原因照实给作者看。 */
    val handoff: StepState = StepState.WAITING,
    val handoffError: String? = null,
    /** 交接卡这一步的说明（比如保留了作者改过的那一份）。 */
    val handoffNote: String? = null,
    /** 整理前情（故事至今、卷摘要）：跟着写本章摘要走，整理好就存下，没成不拦收尾。 */
    val digest: StepState = StepState.WAITING,
    val digestError: String? = null,
    val digestNote: String? = null,
) {
    /**
     * 这一章的收尾手上有东西：在跑、在写入、审出问题等着选、没成等着重试、等着确认。这时换一章收尾会把它丢掉
     * （写入阶段被打断还会写进去一半），所以同一时间只收一章，别的章要等它完成或先停下。
     */
    val busy: Boolean get() = stage != FinishStage.SETUP && stage != FinishStage.DONE

    /** 整理结果里新设定、记进账本的、冲突各几条（条目多时确认页折叠成这一行）。 */
    val syncCounts: Triple<Int, Int, Int>
        get() {
            val items = session?.items.orEmpty()
            return Triple(
                items.count { it.group == SyncGroup.NEW },
                items.count { it.group.ledger },
                items.count { it.group == SyncGroup.CONFLICT },
            )
        }
}
