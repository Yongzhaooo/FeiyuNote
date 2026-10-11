package com.feiyu.notes.study

import com.feiyu.notes.ai.AiRole
import com.feiyu.notes.data.*
import org.junit.Assert.*
import org.junit.Test

class TurnContextTest {
    @Test fun englishMaterialsAndNotesOnlySummaryUseEnglishInstructions() {
        val target = Entry(1, 1, EntryKind.USER, EntryAction.ASK, "Explain continuity",
            requestContext = TurnContext(scratchpad = "老师说 x < 3", knowledge = listOf(KnowledgeContext(1, 2, 3, 4, "Continuity", "Assume x < 3"))))
        val input = ContextBuilder.buildTurn(target, emptyList(), null, null, { error("no photos") }, language = "en")
        assertTrue(input.systemText.contains("<course-knowledge>"))
        assertTrue(input.systemText.contains("Assume x &lt; 3"))
        assertTrue(input.messages.single().text.contains("<class-notes>\n老师说 x &lt; 3"))
        assertFalse(input.systemText.any { it in '\u4e00'..'\u9fff' })
        val (summary, sources) = ContextBuilder.buildSummary(emptyList(), null, language = "en", scratchpad = "Assume continuity")!!
        assertTrue(summary.messages.single().text.startsWith("<class-notes>"))
        assertTrue(summary.systemText.contains("class notes"))
        assertFalse(summary.systemText.any { it in '\u4e00'..'\u9fff' })
        assertTrue(sources.isEmpty())
    }

    @Test fun roundTripAndUnsupportedVersions() {
        assertEquals(TurnContext.EMPTY, TurnContext.fromJson("{}"))
        val context = TurnContext(scratchpad = "老师强调连续性", knowledge = listOf(KnowledgeContext(1, 2, 3, 4, "极限", "假设连续\n$$ x $$")))
        assertEquals(context, TurnContext.fromJson(context.toJson()))
        assertThrows(IllegalArgumentException::class.java) { TurnContext.fromJson("{\"version\":2}") }
        assertThrows(Exception::class.java) { TurnContext.fromJson("broken") }
    }

    @Test fun savedSnapshotIsOnlyUsedOnCurrentUserMessageAndKnowledgeInSystem() {
        val old = Entry(1, 1, EntryKind.USER, EntryAction.ASK, "previous", requestContext = TurnContext(scratchpad = "OLD PRIVATE SCRATCHPAD"))
        val target = Entry(3, 1, EntryKind.USER, EntryAction.ASK, "question", parentEntryId = 2,
            requestContext = TurnContext(scratchpad = "latest", knowledge = listOf(KnowledgeContext(1, 2, 1, 3, "连续性", "完整条件 $ x $"))))
        val answer = Entry(2, 1, EntryKind.ASSISTANT, text = "answer", parentEntryId = 1, state = EntryState.COMPLETE)
        val input = ContextBuilder.buildTurn(target, listOf(old, answer, target), null, null, { error("no photos") })
        assertTrue(input.systemText.contains("<课程知识>"))
        assertTrue(input.messages.last().text.contains("latest"))
        assertEquals(AiRole.USER, input.messages.last().role)
        assertFalse(input.messages.any { it.text.contains("OLD PRIVATE SCRATCHPAD") })
    }

    @Test fun summaryAcceptsScratchpadAloneAndExcludesKnowledgeAndUnfinishedPairs() {
        val pending = Entry(1, 1, EntryKind.ASSISTANT, text = "pending secret", state = EntryState.PENDING)
        val result = ContextBuilder.buildSummary(listOf(pending), null, scratchpad = "课堂重点")!!
        assertTrue(result.first.messages.single().text.contains("课堂重点"))
        assertFalse(result.first.messages.single().text.contains("pending secret"))
        assertTrue(result.second.isEmpty())
        assertFalse(result.first.systemText.contains("<课程知识>"))
    }

    @Test fun oversizeExplicitTextIsRejectedWithoutTruncation() {
        val target = Entry(0, 1, EntryKind.USER, EntryAction.ASK, "q", requestContext = TurnContext(scratchpad = "记".repeat(12001)))
        assertThrows(IllegalArgumentException::class.java) { ContextBuilder.buildTurn(target, emptyList(), null, null, { error("no photos") }) }
        assertThrows(IllegalArgumentException::class.java) { ContextBuilder.buildSummary(emptyList(), null, scratchpad = "记".repeat(60000)) }
    }
}
