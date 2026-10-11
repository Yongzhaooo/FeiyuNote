package com.feiyu.notes.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class CourseDocument(val id: Long, val notebookId: Long, val markdown: String, val revision: Long, val enabled: Boolean = true)
data class CourseSection(val id: Long, val documentId: Long, val notebookId: Long, val headingPath: String, val markdown: String, val ordinal: Int)
data class CourseBookmark(val id: Long, val sectionId: Long?, val headingPath: String)

/** Local Markdown source only. No HTML execution, network assets or external tokenizer. */
object CourseMarkdown {
    const val MAX_BYTES = 1_048_576
    private val latinPattern = Regex("[A-Za-z0-9]+")
    data class Section(val headingPath: String, val markdown: String, val readingMarkdown: String = markdown)

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "Document exceeds 1 MiB" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }

    fun sections(markdown: String): List<Section> {
        require(markdown.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val lines = markdown.replace("\r\n", "\n").split('\n')
        data class Heading(val start: Int, val level: Int, val path: String, val parents: List<Int>)
        val headings = mutableListOf<Heading>()
        val stack = mutableListOf<Int>()
        val path = mutableListOf<Pair<Int, String>>()
        var fence: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val marker = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
            if (marker != null) {
                if (fence == null) fence = marker
                else if (fence.first() == marker.first() && marker.length >= fence.length && line.substringAfter(marker).isBlank()) fence = null
                i++; continue
            }
            if (fence != null) { i++; continue }
            val atx = Regex("^ {0,3}(#{1,6}) +(.+?) *#* *$").matchEntire(line)
            val setext = if (line.isNotBlank() && i + 1 < lines.size) Regex("^ {0,3}(=+|-+) *$").matchEntire(lines[i + 1]) else null
            val level = atx?.groupValues?.get(1)?.length ?: setext?.groupValues?.get(1)?.let { if (it.first() == '=') 1 else 2 }
            if (level != null) {
                val title = atx?.groupValues?.get(2) ?: line.trim()
                while (path.isNotEmpty() && path.last().first >= level) path.removeAt(path.lastIndex)
                while (stack.isNotEmpty() && headings[stack.last()].level >= level) stack.removeAt(stack.lastIndex)
                path += level to title
                headings += Heading(i, level, path.joinToString(" / ") { it.second }, stack.toList())
                stack += headings.lastIndex
                if (setext != null) i++
            }
            i++
        }
        require(headings.size <= 1000) { "Too many sections" }
        if (headings.isEmpty()) return listOf(Section("", markdown))
        return buildList {
            if (headings.first().start > 0 && lines.take(headings.first().start).any { it.isNotBlank() }) {
                add(Section("", lines.take(headings.first().start).joinToString("\n")))
            }
            headings.forEachIndexed { index, h ->
                val end = headings.drop(index + 1).firstOrNull { it.level <= h.level }?.start ?: lines.size
                val readingEnd = headings.getOrNull(index + 1)?.start ?: lines.size
                val assumptions = h.parents.map { parentIndex ->
                    lines.subList(headings[parentIndex].start, headings[parentIndex + 1].start).joinToString("\n")
                }
                add(Section(h.path, (assumptions + lines.subList(h.start, end).joinToString("\n")).joinToString("\n\n"), lines.subList(h.start, readingEnd).joinToString("\n")))
            }
        }
    }

    // ponytail: CJK bigrams provide literal keyword retrieval, not semantic synonyms; upgrade only
    // if measured course queries require a language tokenizer. FTS still performs candidate lookup.
    fun terms(text: String): Set<String> = buildSet {
        var previous: Int? = null
        text.codePoints().forEach { point ->
            val han = point in 0x3400..0x4DBF || point in 0x4E00..0x9FFF || point in 0xF900..0xFAFF || point in 0x20000..0x323AF
            if (han) { previous?.let { add(String(intArrayOf(it, point), 0, 2)) }; previous = point }
            else previous = null
        }
        latinPattern.findAll(text.lowercase(Locale.ROOT)).forEach { if (it.value.length > 1) add(it.value) }
    }.minus(setOf("什么", "怎么", "如何", "为什么", "请问", "解释", "一下", "这个", "那个", "可以", "是否", "我们", "的是", "有什么"))
}
