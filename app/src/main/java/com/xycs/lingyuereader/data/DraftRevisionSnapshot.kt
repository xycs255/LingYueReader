package com.xycs.lingyuereader.data

import org.json.JSONObject

/** The first complete candidate before an automatic revision replaces the current draft. */
data class DraftRevisionSnapshot(val initialText: String, val alternateText: String? = null)

/**
 * The snapshot is valid only beside the exact current draft it was saved with. WorkStore must
 * commit this sidecar and the draft/provenance pair in the same recovery transaction.
 */
internal object DraftRevisionCodec {
    // A very long manuscript must stop before automatic revision rather than silently lose its
    // first version. Ordinary chapter drafts are far smaller than this bound.
    const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(currentText: String, initialText: String, alternateText: String? = null): JSONObject {
        require(currentText.isNotBlank() && initialText.isNotBlank()) { "候选稿或创作初稿为空" }
        require(alternateText == null || alternateText.isNotBlank()) { "对照版本为空" }
        val value = JSONObject()
            .put("version", 1)
            .put("currentFingerprint", WritingReferences.fingerprint(currentText))
            .put("initialFingerprint", WritingReferences.fingerprint(initialText))
            .put("initialText", initialText)
        if (alternateText != null) value.put("alternateFingerprint", WritingReferences.fingerprint(alternateText))
            .put("alternateText", alternateText)
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "创作初稿过长，无法安全保留修订前版本" }
        return value
    }

    fun decode(raw: String, currentText: String): DraftRevisionSnapshot {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "创作初稿快照过长" }
        require(currentText.isNotBlank()) { "创作初稿快照对应的候选稿为空" }
        val value = JSONObject(raw)
        require(value.opt("currentFingerprint") is String && value.opt("initialFingerprint") is String &&
            value.opt("initialText") is String &&
            (!value.has("alternateText") || value.opt("alternateText") is String) &&
            (!value.has("alternateFingerprint") || value.opt("alternateFingerprint") is String)) {
            "创作初稿快照结构无效"
        }
        require(value.getInt("version") == 1) { "创作初稿快照版本无效" }
        require(value.getString("currentFingerprint") == WritingReferences.fingerprint(currentText)) {
            "创作初稿快照与当前候选稿不一致"
        }
        val initialText = value.getString("initialText")
        require(initialText.isNotBlank() &&
            value.getString("initialFingerprint") == WritingReferences.fingerprint(initialText)) {
            "创作初稿快照损坏"
        }
        val alternateText = if (value.has("alternateText")) value.getString("alternateText") else null
        require((alternateText == null && !value.has("alternateFingerprint")) ||
            (alternateText != null && alternateText.isNotBlank() &&
                value.getString("alternateFingerprint") == WritingReferences.fingerprint(alternateText))) {
            "草稿对照版本损坏"
        }
        return DraftRevisionSnapshot(initialText, alternateText)
    }
}
