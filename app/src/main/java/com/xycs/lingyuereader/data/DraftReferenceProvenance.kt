package com.xycs.lingyuereader.data

import org.json.JSONObject

/** Provenance belongs to one exact candidate, including a stopped/partial one. */
data class DraftReferenceProvenance(
    val analysisId: String,
    val sourceFingerprint: String?,
    val chapterLength: Int?,
    val verified: Boolean,
)

data class DraftContent(
    val text: String,
    val reference: DraftReferenceProvenance? = null,
    val provenanceError: String? = null,
    val blocked: Boolean = false,
)

/** The hash prevents a torn text/metadata pair from silently becoming an unreferenced draft. */
internal object DraftReferenceCodec {
    private const val MAX_BYTES = 2_048
    private val HASH = Regex("[a-f0-9]{64}")

    fun encode(text: String, reference: DraftReferenceProvenance?, blocked: Boolean = false): JSONObject = JSONObject()
        .put("version", 1)
        .put("draftFingerprint", WritingReferences.fingerprint(text))
        .put("analysisId", reference?.analysisId.orEmpty())
        .put("sourceFingerprint", reference?.sourceFingerprint ?: JSONObject.NULL)
        .put("chapterLength", reference?.chapterLength ?: JSONObject.NULL)
        .put("verified", reference?.verified == true)
        .put("blocked", blocked)

    fun decode(raw: String, text: String): DraftContent {
        require(raw.toByteArray().size <= MAX_BYTES) { "草稿范本来源记录过长" }
        val value = JSONObject(raw)
        require(value.getInt("version") == 1) { "草稿范本来源记录版本无效" }
        val draftFingerprint = value.getString("draftFingerprint")
        require(HASH.matches(draftFingerprint) && draftFingerprint == WritingReferences.fingerprint(text)) {
            "草稿与范本来源记录不一致"
        }
        require(!value.has("blocked") || value.opt("blocked") is Boolean) { "草稿来源拦截标记无效" }
        val blocked = value.optBoolean("blocked", false)
        val id = value.getString("analysisId")
        if (id.isEmpty()) {
            require(value.opt("sourceFingerprint") == JSONObject.NULL && value.opt("chapterLength") == JSONObject.NULL &&
                !value.getBoolean("verified")) { "草稿范本来源记录无效" }
            return if (blocked) DraftContent(text, provenanceError = "旧草稿缺少可核验的来源记录，请重新生成后采纳", blocked = true)
                else DraftContent(text)
        }
        require(WritingReferences.ID.matches(id)) { "草稿范本分析编号无效" }
        val fingerprint = if (value.isNull("sourceFingerprint")) null else value.getString("sourceFingerprint")
        require(fingerprint == null || HASH.matches(fingerprint)) { "草稿范本指纹无效" }
        val chapterLength = if (value.isNull("chapterLength")) null else value.getInt("chapterLength")
        require(chapterLength == null || chapterLength in 1..WritingReferences.MAX_CHAPTER_CHARS) { "草稿范本范围无效" }
        val verified = value.getBoolean("verified")
        require(!verified || fingerprint != null && chapterLength != null) { "未核验的草稿不能标记为完成" }
        return DraftContent(text, DraftReferenceProvenance(id, fingerprint, chapterLength, verified),
            provenanceError = if (blocked) "草稿来源无法核验，请重新生成" else null, blocked = blocked)
    }
}
