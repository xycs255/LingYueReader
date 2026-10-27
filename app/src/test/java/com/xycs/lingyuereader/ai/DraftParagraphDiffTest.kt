package com.xycs.lingyuereader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftParagraphDiffTest {
    private fun rebuild(parts: List<DraftParagraphDiff.Piece>, exclude: TextDiff.Kind): String =
        parts.filter { it.kind != exclude }.flatMap { it.paragraphs }.joinToString("\n")

    @Test fun longChangedParagraphsDoNotConsumeCharacterEditBudget() {
        val initial = listOf("开场", "甲".repeat(3_000), "结尾").joinToString("\n")
        val current = listOf("开场", "乙".repeat(3_000), "结尾").joinToString("\n")
        val parts = requireNotNull(DraftParagraphDiff.diff(initial, current, maxEdits = 4))
        assertEquals(initial, rebuild(parts, TextDiff.Kind.INSERTED))
        assertEquals(current, rebuild(parts, TextDiff.Kind.DELETED))
        assertTrue(parts.any { it.kind == TextDiff.Kind.DELETED })
        assertTrue(parts.any { it.kind == TextDiff.Kind.INSERTED })
    }

    @Test fun repeatedParagraphsAndTrailingNewlineRebuildExactly() {
        val initial = "相同\n甲\n相同\n结尾\n"
        val current = "相同\n乙\n相同\n结尾\n"
        val parts = requireNotNull(DraftParagraphDiff.diff(initial, current))
        assertEquals(initial, rebuild(parts, TextDiff.Kind.INSERTED))
        assertEquals(current, rebuild(parts, TextDiff.Kind.DELETED))
    }

    @Test fun ambiguousLargeRewriteReturnsNoAlignment() {
        val initial = (1..20).joinToString("\n") { "旧段 $it" }
        val current = (1..20).joinToString("\n") { "新段 $it" }
        assertNull(DraftParagraphDiff.diff(initial, current, maxEdits = 2))
    }
}
