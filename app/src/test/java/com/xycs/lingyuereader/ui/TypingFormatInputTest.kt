package com.xycs.lingyuereader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.ui.text.TextRange
import com.xycs.lingyuereader.data.FormatSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入时自动排版的那一层：作者这一下编辑做完以后才换（单独一步，撤销先撤换的那一下），
 * 输入法还在组合（拼音没上屏）时不换，退格不换，开关关了不换。
 */
@OptIn(ExperimentalFoundationApi::class)
class TypingFormatInputTest {
    /** 换的那一下排在调度器里；测试里不跑调度器，直接调 [TypingFormatInput.apply]。 */
    private val scope = CoroutineScope(object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
    })

    private fun field(text: String, settings: FormatSettings? = FormatSettings()): Pair<TextFieldState, TypingFormatInput> {
        val state = TextFieldState(text).apply { edit { placeCursorAtEnd() } }
        return state to TypingFormatInput(state, scope) { settings }
    }

    /**
     * 像键盘那样打字：走文本框给输入法用的那条路（作者的编辑，过输入变换、记撤销、留着组合范围），
     * [composing] 给出输入法还在组合的范围。这些是内部接口（只有输入法会用），测试里借反射调。
     */
    private fun TextFieldState.type(input: TypingFormatInput, s: String, composing: IntRange? = null) = asUser(input) {
        append(s)
        if (composing != null) setComposition(this, composing.first, composing.last + 1)
    }

    private fun TextFieldState.asUser(input: TypingFormatInput, block: TextFieldBuffer.() -> Unit) {
        val behavior = Class.forName("androidx.compose.foundation.text.input.internal.undo.TextFieldEditUndoBehavior").enumConstants!!.first { (it as Enum<*>).name == "MergeIfPossible" }
        val m = TextFieldState::class.java.methods.first { it.name == "editAsUser\$foundation" && it.parameterCount == 4 }
        m.invoke(this, input, false, behavior, { b: TextFieldBuffer -> b.block() })
    }

    private fun setComposition(buffer: TextFieldBuffer, start: Int, end: Int) {
        val m = TextFieldBuffer::class.java.methods.first { it.name == "setComposition\$foundation" && it.parameterCount == 3 }
        m.invoke(buffer, start, end, emptyList<Any>())
    }

    @Test
    fun convertsAfterTheEditAsASeparateUndoStep() {
        val (state, input) = field("林月")
        state.type(input, ",")
        // 打的这一下原样进了正文，换的那一下还排着。
        assertEquals("林月,", state.text.toString())
        input.apply()
        assertEquals("林月，", state.text.toString())
        assertEquals(TextRange(3), state.selection)
        // 第一次撤销撤的是自动换的那一下。
        state.undoState.undo()
        assertEquals("林月,", state.text.toString())
        assertEquals(TextRange(3), state.selection)
        // 第二次才撤销打的字。
        state.undoState.undo()
        assertEquals("林月", state.text.toString())
    }

    @Test
    fun waitsWhileTheImeIsComposing() {
        val (state, input) = field("他说")
        state.type(input, "...", composing = 2 until 5)
        assertTrue(state.composition != null)
        input.apply()
        assertEquals("他说...", state.text.toString())
        // 上屏了（输入法用同样的字换掉组合的那一截、结束组合）：这时才换。
        state.asUser(input) {
            replace(2, 5, "...")
            TextFieldBuffer::class.java.methods.first { it.name == "commitComposition\$foundation" }.invoke(this)
        }
        assertEquals(null, state.composition)
        input.apply()
        assertEquals("他说……", state.text.toString())
    }

    @Test
    fun leavesDeletionsAndDisabledSettingsAlone() {
        val (state, input) = field("他说。")
        state.asUser(input) { replace(length - 1, length, "") }
        input.apply()
        assertEquals("他说", state.text.toString())

        val (off, offInput) = field("他说", FormatSettings(onInput = false))
        off.type(offInput, "...")
        offInput.apply()
        assertEquals("他说...", off.text.toString())

        // 定稿、只读时设置给空：不换。
        val (locked, lockedInput) = field("他说", null)
        locked.type(lockedInput, "...")
        lockedInput.apply()
        assertEquals("他说...", locked.text.toString())
    }

    @Test
    fun skipsWhenTheTextChangedInBetween() {
        val (state, input) = field("他说")
        state.type(input, ",")
        // 换之前正文又被别处改了（快捷标点、AI 采纳）：这一下作废。
        state.edit { append("好") }
        input.apply()
        assertEquals("他说,好", state.text.toString())
    }
}
