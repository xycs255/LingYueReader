package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A proposed scene is an author-editable writing route, never a confirmed story event. */
data class WritingScene(
    val goal: String,
    val conflict: String,
    val turn: String,
    val exit: String,
    val selected: Boolean = true,
)

/**
 * The approved scene route belongs to one exact chapter task. [draftHash] binds progress to the
 * saved candidate manuscript; a changed candidate must not silently inherit completed scenes.
 */
data class WritingScenePlan(
    val taskFingerprint: String,
    val scenes: List<WritingScene>,
    val completed: Int = 0,
    val draftHash: String = "",
    /** Exact author input used to compile [taskFingerprint]; needed after a process restart. */
    val instruction: String = "",
    val targetLength: String = "",
) {
    val selectedScenes: List<WritingScene> get() = scenes.filter { it.selected }

    fun validated(): WritingScenePlan {
        require(FINGERPRINT.matches(taskFingerprint)) { "场景安排的本章资料指纹无效" }
        require(instruction.length <= MAX_INSTRUCTION_CHARS) { "本次要求不能超过 $MAX_INSTRUCTION_CHARS 字" }
        require(targetLength.length <= MAX_TARGET_LENGTH_CHARS) { "目标篇幅不能超过 $MAX_TARGET_LENGTH_CHARS 字" }
        require(scenes.size in 1..MAX_SCENES) { "场景安排应有 1 至 $MAX_SCENES 场" }
        require(selectedScenes.isNotEmpty()) { "请至少选中一场" }
        scenes.forEachIndexed { index, scene ->
            listOf("目标" to scene.goal, "冲突" to scene.conflict, "转折" to scene.turn, "收束" to scene.exit).forEach { (label, value) ->
                require(value.isNotBlank() && value.length <= MAX_FIELD_CHARS) {
                    "第 ${index + 1} 场的${label}须为 1 至 $MAX_FIELD_CHARS 字"
                }
            }
        }
        require(completed in 0..selectedScenes.size) { "已写场数超出所选场景" }
        require(if (completed == 0) draftHash.isEmpty() else FINGERPRINT.matches(draftHash)) {
            "场景安排的已写进度与草稿指纹不一致"
        }
        return this
    }

    fun matchesDraft(text: String): Boolean = draftHash.isNotEmpty() && draftHash == hash(text)

    fun toJson(): JSONObject = validated().let {
        JSONObject().put("version", VERSION).put("taskFingerprint", taskFingerprint)
            .put("completed", completed).put("draftHash", draftHash)
            .put("instruction", instruction).put("targetLength", targetLength)
            .put("scenes", JSONArray().apply {
                scenes.forEach { scene ->
                    put(JSONObject().put("goal", scene.goal).put("conflict", scene.conflict)
                        .put("turn", scene.turn).put("exit", scene.exit).put("selected", scene.selected))
                }
            })
    }

    companion object {
        const val MAX_SCENES = 4
        const val MAX_FIELD_CHARS = 240
        const val MAX_INSTRUCTION_CHARS = 2_000
        const val MAX_TARGET_LENGTH_CHARS = 160
        private const val VERSION = 1
        private val FINGERPRINT = Regex("[0-9a-f]{64}")

        fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun scene(o: JSONObject, selectedRequired: Boolean): WritingScene {
            fun field(key: String): String {
                require(o.opt(key) is String) { "场景的 $key 缺失或格式有误" }
                return o.getString(key).trim()
            }
            require(!selectedRequired || o.opt("selected") is Boolean) { "场景选择状态格式有误" }
            return WritingScene(field("goal"), field("conflict"), field("turn"), field("exit"),
                if (selectedRequired) o.optBoolean("selected", true) else true)
        }

        /** Model output may have a code fence; prose or invented missing fields never become a card. */
        fun parseSuggestion(answer: String, taskFingerprint: String, instruction: String = "", targetLength: String = ""): WritingScenePlan {
            require(answer.length <= 32_000) { "场景建议过长，请重试" }
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            require(start >= 0 && end > start) { "场景建议不是要求的 JSON 格式" }
            val root = JSONObject(answer.substring(start, end + 1))
            val raw = root.optJSONArray("scenes") ?: throw IllegalArgumentException("场景建议缺少 scenes")
            require(raw.length() in 1..MAX_SCENES) { "场景建议应有 1 至 $MAX_SCENES 场" }
            return WritingScenePlan(taskFingerprint,
                (0 until raw.length()).map { scene(raw.getJSONObject(it), selectedRequired = false) },
                instruction = instruction, targetLength = targetLength).validated()
        }

        fun fromJson(o: JSONObject): WritingScenePlan {
            val version = o.opt("version")
            require(version is Number && version.toString() == VERSION.toString()) { "场景安排版本不受支持" }
            require(o.opt("taskFingerprint") is String) { "场景安排资料指纹格式有误" }
            require(o.opt("draftHash") is String) { "场景安排草稿指纹格式有误" }
            require(!o.has("instruction") || o.opt("instruction") is String) { "场景安排本次要求格式有误" }
            require(!o.has("targetLength") || o.opt("targetLength") is String) { "场景安排目标篇幅格式有误" }
            val rawCompleted = (o.opt("completed") as? Number)?.toString()?.toIntOrNull()
                ?: throw IllegalArgumentException("场景安排已写场数格式有误")
            val raw = o.optJSONArray("scenes") ?: throw IllegalArgumentException("场景安排缺少 scenes")
            require(raw.length() in 1..MAX_SCENES) { "场景安排应有 1 至 $MAX_SCENES 场" }
            return WritingScenePlan(o.getString("taskFingerprint"),
                (0 until raw.length()).map { scene(raw.getJSONObject(it), selectedRequired = true) },
                rawCompleted, o.getString("draftHash"), o.optString("instruction"), o.optString("targetLength")).validated()
        }
    }
}
