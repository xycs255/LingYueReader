package com.xycs.lingyuereader.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class WritingCleanDraftFenceTest {
    @Test fun languageLabelsOnAnswerFencesDoNotBecomeProse() {
        assertEquals("她推开渡口的门。", WritePrompts.cleanDraft("```markdown\n她推开渡口的门。\n```"))
        assertEquals("船终于到了。", WritePrompts.cleanDraft("~~~text\n船终于到了。\n~~~"))
    }

    @Test fun ordinaryProseIsNotRemovedAsAFormatLabel() {
        assertEquals("她在纸上写下 markdown 三个字。", WritePrompts.cleanDraft("她在纸上写下 markdown 三个字。"))
    }
}
