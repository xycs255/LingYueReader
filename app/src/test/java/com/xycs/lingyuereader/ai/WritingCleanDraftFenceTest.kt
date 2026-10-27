package com.xycs.lingyuereader.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class WritingCleanDraftFenceTest {
    @Test fun languageLabelsOnAnswerFencesDoNotBecomeProse() {
        assertEquals("她推开渡口的门。", WritePrompts.cleanDraft("```markdown\n她推开渡口的门。\n```"))
        assertEquals("船终于到了。", WritePrompts.cleanDraft("~~~text\n船终于到了。\n~~~"))
    }

    @Test fun refusalNotesFromTheClientAreNeverKeptAsProse() {
        assertEquals("", WritePrompts.cleanDraft("模型拒绝回答这个问题。"))
        assertEquals("林月把灯笼递给老周。", WritePrompts.cleanDraft("林月把灯笼递给老周。\n\n（模型中途停止了回答。）"))
        // 人物台词里提到的同样字眼不是整行提示，保留。
        assertEquals("杜娘笑道：“模型拒绝回答这个问题。”", WritePrompts.cleanDraft("杜娘笑道：“模型拒绝回答这个问题。”"))
    }

    @Test fun ordinaryProseIsNotRemovedAsAFormatLabel() {
        assertEquals("她在纸上写下 markdown 三个字。", WritePrompts.cleanDraft("她在纸上写下 markdown 三个字。"))
    }
}
