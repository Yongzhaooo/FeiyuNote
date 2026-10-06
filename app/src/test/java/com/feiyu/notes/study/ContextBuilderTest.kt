package com.feiyu.notes.study

import com.feiyu.notes.ai.AiRole
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lesson with two branches under the first answer:
 *   1 user(photo p1) -> 2 answer -> 3 follow-up A -> 4 answer
 *                               \-> 5 follow-up B(photo p5) -> 6 answer
 *   7 user(photo p7, other thread) -> 8 answer
 */
class ContextBuilderTest {
    private fun user(id: Long, text: String, parent: Long? = null, photo: String? = null) =
        Entry(id, 1, EntryKind.USER, EntryAction.ASK, text, parentEntryId = parent, imagePaths = listOfNotNull(photo))

    private fun answer(id: Long, parent: Long, text: String, state: EntryState = EntryState.COMPLETE) =
        Entry(id, 1, EntryKind.ASSISTANT, text = text, parentEntryId = parent, state = state)

    private val lesson = listOf(
        user(1, "原题", photo = "p1.jpg"), answer(2, 1, "讲解1"),
        user(3, "分支A", parent = 2), answer(4, 3, "讲解A"),
        user(5, "分支B", parent = 2, photo = "p5.jpg"), answer(6, 5, "讲解B"),
        user(7, "另一题", photo = "p7.jpg"), answer(8, 7, "讲解7"),
    )
    private val resolve: (String) -> File = { File(it) }

    @Test fun followUpSendsOnlySelectedChainTextByDefault() {
        val target = user(9, "继续问", parent = 4)
        val input = ContextBuilder.buildTurn(target, lesson + target, null, null, resolve)
        assertEquals(listOf("原题", "讲解1", "分支A", "讲解A", "继续问"), input.messages.map { it.text })
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT, AiRole.USER, AiRole.ASSISTANT, AiRole.USER), input.messages.map { it.role })
        assertTrue("no history photos without selection", input.messages.all { it.images.isEmpty() })
    }

    @Test fun attachedPhotosAreLimitedToTheChain() {
        // 1 is on the chain; 5 is a sibling branch; 7 is another thread.
        val target = user(9, "看原图", parent = 4).copy(attachedImageEntryIds = listOf(1, 5, 7))
        val input = ContextBuilder.buildTurn(target, lesson + target, null, null, resolve)
        assertEquals(listOf(File("p1.jpg")), input.messages.last().images)
    }

    @Test fun multipleOwnAndSelectedPhotosKeepTheirOrder() {
        val earlier = lesson.map { if (it.id == 1L) it.copy(imagePaths = listOf("a.jpg", "b.jpg")) else it }
        val target = user(9, "", parent = 4).copy(imagePaths = listOf("c.jpg", "d.jpg"), attachedImageEntryIds = listOf(1, 5))
        val input = ContextBuilder.buildTurn(target, earlier + target, null, null, resolve)
        assertEquals(listOf("c.jpg", "d.jpg", "a.jpg", "b.jpg"), input.messages.last().images.map { it.name })
        assertTrue(input.messages.dropLast(1).all { it.images.isEmpty() })
        assertEquals(StudyPrompts.IDENTIFY_AND_EXPLAIN, input.messages.last().text)
    }

    @Test fun photoOnlyAskUsesBuiltInInstruction() {
        val target = user(9, "", photo = "p9.jpg")
        val input = ContextBuilder.buildTurn(target, lesson + target, null, null, resolve)
        assertEquals(StudyPrompts.IDENTIFY_AND_EXPLAIN, input.messages.single().text)
        assertEquals(listOf(File("p9.jpg")), input.messages.single().images)
    }

    @Test fun expandMistakeReferenceAndTemplate() {
        val template = Template(1, "严格", "每步写出依据", 0)
        val note = Entry(20, 1, EntryKind.NOTE, text = "参考笔记正文")
        val expand = user(9, "", parent = 2).copy(action = EntryAction.EXPAND)
        val e = ContextBuilder.buildTurn(expand, lesson + expand, note, template, resolve)
        assertTrue(e.messages.last().text.startsWith(StudyPrompts.EXPAND))
        assertTrue(e.systemText.contains("参考笔记正文"))
        assertTrue(e.systemText.endsWith("每步写出依据"))

        val mistake = user(9, "我算得 x=3", parent = 2).copy(action = EntryAction.MISTAKE)
        val m = ContextBuilder.buildTurn(mistake, lesson + mistake, null, null, resolve)
        assertTrue(m.messages.last().text.startsWith(StudyPrompts.MISTAKE))
        assertTrue(m.messages.last().text.contains("我算得 x=3"))
        assertFalse(m.systemText.contains("附加讲解要求"))
    }

    @Test fun longHistoryKeepsNewestMessagesWithinBudget() {
        val chat = buildList {
            var parent: Long? = null
            for (i in 0 until 40) {
                add(user(2L * i + 1, "问$i" + "x".repeat(2_000), parent = parent))
                add(answer(2L * i + 2, 2L * i + 1, "答$i" + "y".repeat(2_000)))
                parent = 2L * i + 2
            }
        }
        val target = Entry(100, 1, EntryKind.USER, EntryAction.ASK, "最新", parentEntryId = 80)
        val messages = ContextBuilder.buildTurn(target, chat, null, null, resolve).messages
        val history = messages.dropLast(1)
        assertTrue(history.sumOf { it.text.length } <= ContextBuilder.HISTORY_CHAR_BUDGET)
        assertEquals(AiRole.USER, history.first().role)
        assertTrue(history.last().text.startsWith("答39"))
        assertEquals("最新", messages.last().text)
        assertFalse(history.any { it.text.startsWith("问0") })
    }

    @Test fun incompleteAnswersNeverEnterContext() {
        val failed = listOf(user(1, "q"), answer(2, 1, "网络错误", EntryState.FAILED), answer(3, 1, "ok"))
        val target = user(4, "再问", parent = 3)
        val input = ContextBuilder.buildTurn(target, failed + target, null, null, resolve)
        assertEquals(listOf("q", "ok", "再问"), input.messages.map { it.text })
    }

    @Test fun summaryIsTextOnlyWithSources() {
        val withPending = lesson + user(9, "未完成") + answer(10, 9, "", EntryState.PENDING)
        val (input, sources) = ContextBuilder.buildSummary(withPending, null)!!
        assertTrue(input.messages.all { it.images.isEmpty() })
        assertEquals(listOf(1L, 2, 3, 4, 5, 6, 7, 8), sources)
        assertFalse(input.messages.single().text.contains("未完成"))
        assertFalse(input.systemText.contains("附加讲解要求"))
        val templated = ContextBuilder.buildSummary(lesson, Template(1, "报告", "按报告格式", 0))!!.first
        assertTrue(templated.systemText.endsWith("按报告格式"))
        assertNull(ContextBuilder.buildSummary(listOf(user(1, "q"), answer(2, 1, "", EntryState.FAILED)), null))
    }

    @Test fun builtInPromptsFollowTheLanguage() {
        val target = user(9, "hi")
        val en = ContextBuilder.buildTurn(target, lesson + target, null, null, resolve, language = "en")
        assertTrue(en.systemText.startsWith("You are a patient"))
        assertTrue(ContextBuilder.buildTurn(target, lesson + target, null, null, resolve).systemText.startsWith("你是耐心"))
        // Chat is soft and uses the 鱼鱼 / 用户酱 nicknames in both languages.
        assertTrue(PromptKind.GENERAL.default("zh").contains("鱼鱼") && PromptKind.GENERAL.default("zh").contains("用户酱"))
        assertTrue(PromptKind.GENERAL.default("en").contains("用户酱"))
        // Only the untouched built-in template follows the language.
        assertEquals(StudyPrompts.of("en").guided, StudyPrompts.localizedTemplate(StudyPrompts.GUIDED, true, "en"))
        assertEquals("mine", StudyPrompts.localizedTemplate("mine", true, "en"))
        assertEquals(StudyPrompts.GUIDED, StudyPrompts.localizedTemplate(StudyPrompts.GUIDED, false, "en"))
    }
}
