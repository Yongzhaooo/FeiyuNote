package com.feiyu.notes.study

import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TitleSuggesterTest {

    private val zone = ZoneId.of("UTC")
    // 2026-05-10T00:00:00Z = 1778371200000L
    private val createdAt = 1778371200000L

    @Test
    fun resolveDatePrefix_retainsValidExistingDate() {
        val result = TitleSuggester.resolveDatePrefix("2026-03-15 原标题", createdAt, zone)
        assertEquals("2026-03-15", result)
    }

    @Test
    fun resolveDatePrefix_fallsBackToCreatedAtWhenDateInvalid() {
        // 2026-02-30 is not a valid date
        val result = TitleSuggester.resolveDatePrefix("2026-02-30 错题集", createdAt, zone)
        assertEquals("2026-05-10", result)
    }

    @Test
    fun resolveDatePrefix_fallsBackToCreatedAtWhenNoDatePrefix() {
        val result = TitleSuggester.resolveDatePrefix("高等数学第一讲", createdAt, zone)
        assertEquals("2026-05-10", result)
    }

    @Test
    fun validateTopic_chineseValidLengths() {
        assertEquals("极限与连续", TitleSuggester.validateTopic("极限与连续", "zh")) // 5 chars
        assertEquals("微积分学", TitleSuggester.validateTopic("微积分学", "zh")) // 4 chars
        assertEquals("空间解析几何引论", TitleSuggester.validateTopic("空间解析几何引论", "zh")) // 8 chars
    }

    @Test
    fun validateTopic_chineseStripQuotesAndDatePrefix() {
        assertEquals("极限与连续", TitleSuggester.validateTopic("“极限与连续”", "zh"))
        assertEquals("极限与连续", TitleSuggester.validateTopic("「极限与连续」", "zh"))
        assertEquals("极限与连续", TitleSuggester.validateTopic("2026-10-12 极限与连续", "zh"))
    }

    @Test
    fun validateTopic_chineseRejectsInvalid() {
        // Too short (< 4)
        assertNull(TitleSuggester.validateTopic("积分", "zh"))
        assertNull(TitleSuggester.validateTopic("微积分", "zh"))

        // Exactly 9 chars (> 8)
        assertNull(TitleSuggester.validateTopic("多元函数微分学应用", "zh"))
        // Too long (> 8)
        assertNull(TitleSuggester.validateTopic("高等数学微积分学基础教程", "zh"))

        // Blank or whitespace
        assertNull(TitleSuggester.validateTopic("", "zh"))
        assertNull(TitleSuggester.validateTopic("   ", "zh"))

        // Multiline explanation
        assertNull(TitleSuggester.validateTopic("微积分学\n这一章主要讲解极限", "zh"))

        // Punctuation / non-CJK
        assertNull(TitleSuggester.validateTopic("极限，连续", "zh"))
        assertNull(TitleSuggester.validateTopic("极限。连续", "zh"))
        assertNull(TitleSuggester.validateTopic("极限！连续", "zh"))
        assertNull(TitleSuggester.validateTopic("极限与连续！", "zh"))
        assertNull(TitleSuggester.validateTopic("微积分1", "zh"))
        assertNull(TitleSuggester.validateTopic("极限A论", "zh"))
        assertNull(TitleSuggester.validateTopic("α极限理论", "zh"))
        assertNull(TitleSuggester.validateTopic("Calculus", "zh"))

        // Non-BMP Unicode (emoji: 📐 is supplementary code point U+1F4D0, 😀 is U+1F600)
        assertNull(TitleSuggester.validateTopic("几何证明📐", "zh"))
        assertNull(TitleSuggester.validateTopic("极限理论😀", "zh"))
    }

    @Test
    fun buildPrompt_constructsValidAiInput() {
        val excerpt = "Q: 什么是导数？\nA: 导数是瞬时变化率。"
        val inputZh = TitleSuggester.buildPrompt(excerpt, "zh")
        assertTrue(inputZh.systemText.contains("4至8个汉字"))
        assertEquals(1, inputZh.messages.size)
        assertEquals(com.feiyu.notes.ai.AiRole.USER, inputZh.messages[0].role)
        assertEquals(excerpt, inputZh.messages[0].text)

        val inputEn = TitleSuggester.buildPrompt(excerpt, "en")
        assertTrue(inputEn.systemText.contains("2 to 6 words"))
        assertEquals(1, inputEn.messages.size)
        assertEquals(com.feiyu.notes.ai.AiRole.USER, inputEn.messages[0].role)
        assertEquals(excerpt, inputEn.messages[0].text)
    }

    @Test
    fun validateTopic_englishValid() {
        assertEquals("Linear Algebra", TitleSuggester.validateTopic("Linear Algebra", "en"))
        assertEquals("Fourier Series and Transforms", TitleSuggester.validateTopic("Fourier Series and Transforms", "en"))
    }

    @Test
    fun validateTopic_englishRejectsInvalid() {
        // Single word (< 2 words)
        assertNull(TitleSuggester.validateTopic("Calculus", "en"))

        // Too many words (> 6)
        assertNull(TitleSuggester.validateTopic("Introduction to Modern Theory of Partial Differential Equations", "en"))

        // Blank
        assertNull(TitleSuggester.validateTopic("   ", "en"))

        // Multiline
        assertNull(TitleSuggester.validateTopic("Linear Algebra\nLesson 1", "en"))
    }

    @Test
    fun extractExcerpt_extractsUpToThreeCompletedPairs() {
        val entries = listOf(
            Entry(id = 1, lessonId = 1, kind = EntryKind.USER, text = "什么是极限？"),
            Entry(id = 2, lessonId = 1, kind = EntryKind.ASSISTANT, parentEntryId = 1, state = EntryState.COMPLETE, text = "极限是趋势。"),
            Entry(id = 3, lessonId = 1, kind = EntryKind.USER, text = "导数的几何意义？"),
            Entry(id = 4, lessonId = 1, kind = EntryKind.ASSISTANT, parentEntryId = 3, state = EntryState.COMPLETE, text = "切线斜率。"),
            Entry(id = 5, lessonId = 1, kind = EntryKind.USER, text = "未完成问题"),
            Entry(id = 6, lessonId = 1, kind = EntryKind.ASSISTANT, parentEntryId = 5, state = EntryState.PENDING, text = ""),
            Entry(id = 7, lessonId = 1, kind = EntryKind.USER, text = "积分的定义？"),
            Entry(id = 8, lessonId = 1, kind = EntryKind.ASSISTANT, parentEntryId = 7, state = EntryState.COMPLETE, text = "曲边梯形面积。"),
            Entry(id = 9, lessonId = 1, kind = EntryKind.USER, text = "第四个问题"),
            Entry(id = 10, lessonId = 1, kind = EntryKind.ASSISTANT, parentEntryId = 9, state = EntryState.COMPLETE, text = "第四个答案。")
        )

        val excerpt = TitleSuggester.extractExcerpt(entries)
        assertNotNull(excerpt)
        assertTrue(excerpt!!.contains("什么是极限？"))
        assertTrue(excerpt.contains("切线斜率。"))
        assertTrue(excerpt.contains("积分的定义？"))
        // Fourth pair should not be included (only up to 3)
        assertTrue(!excerpt.contains("第四个问题"))
    }

    @Test
    fun extractExcerpt_returnsNullWhenNoCompletedPairs() {
        val entries = listOf(
            Entry(id = 1, lessonId = 1, kind = EntryKind.USER, text = "新问题"),
            Entry(id = 2, lessonId = 1, kind = EntryKind.ASSISTANT, parentEntryId = 1, state = EntryState.PENDING, text = "")
        )
        assertNull(TitleSuggester.extractExcerpt(entries))
    }

    @Test
    fun formatTitle_combinesDateAndTopic() {
        val title = TitleSuggester.formatTitle("2026-10-12", "极限与连续")
        assertEquals("2026-10-12 极限与连续", title)
    }
}
