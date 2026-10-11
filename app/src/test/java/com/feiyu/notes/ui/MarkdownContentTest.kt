package com.feiyu.notes.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownContentTest {
    @Test fun blankLinesAndShorterFencesInsideCodeStayLiteral() {
        val blocks = markdownBlocks("# 标题\n\n````kotlin\nfirst\n\n```\n# literal\n````\n\nparagraph")
        assertEquals(3, blocks.size)
        assertFalse(blocks[0].first)
        assertTrue(blocks[1].first)
        assertTrue(blocks[1].second.contains("first\n\n```\n# literal"))
        assertEquals(false to "paragraph", blocks[2])
    }
}
