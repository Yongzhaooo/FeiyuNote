package com.feiyu.notes.data

import org.junit.Assert.*
import org.junit.Test

class CourseMarkdownTest {
    @Test fun outlinePreservesCompleteHierarchyAndIgnoresCodeHeadings() {
        val source = "前言\n\n# 极限\n前提\n\n## 连续性\n公式 $$ f(x) $$\n\n```\n# fake\n```\n\n导数\n===\n内容"
        val sections = CourseMarkdown.sections(source)
        assertEquals(listOf("", "极限", "极限 / 连续性", "导数"), sections.map { it.headingPath })
        assertTrue(sections[1].markdown.contains("前提"))
        assertTrue(sections[1].markdown.contains("公式"))
        assertFalse(sections[1].readingMarkdown.contains("公式"))
        assertTrue(sections[2].markdown.contains("# fake"))
        assertTrue(sections[2].markdown.contains("前提"))
    }
    @Test fun strictUtf8AndChineseKeywords() {
        assertEquals("中文", CourseMarkdown.decode("\uFEFF中文".toByteArray(Charsets.UTF_8)))
        assertThrows(Exception::class.java) { CourseMarkdown.decode(byteArrayOf(0xff.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { CourseMarkdown.decode(ByteArray(CourseMarkdown.MAX_BYTES + 1)) }
        assertTrue(CourseMarkdown.terms("为什么条件概率公式成立 Bayes").containsAll(listOf("条件", "概率", "公式", "bayes")))
    }
}
