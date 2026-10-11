package com.feiyu.notes.study

import com.feiyu.notes.ai.AiInput
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

object TitleSuggester {
    private val DATE_PREFIX_REGEX = Regex("""^(\d{4}-\d{2}-\d{2})""")

    /**
     * Resolves date prefix: prefers a valid YYYY-MM-DD prefix already present in [currentTitle],
     * otherwise falls back to the device local date of [createdAt].
     */
    fun resolveDatePrefix(currentTitle: String, createdAt: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val match = DATE_PREFIX_REGEX.find(currentTitle.trim())
        if (match != null) {
            val dateStr = match.groupValues[1]
            try {
                LocalDate.parse(dateStr)
                return dateStr
            } catch (_: DateTimeParseException) {
                // Invalid calendar date, fall through
            }
        }
        return try {
            Instant.ofEpochMilli(createdAt).atZone(zoneId).toLocalDate().toString()
        } catch (_: Exception) {
            LocalDate.now(zoneId).toString()
        }
    }

    /**
     * Extracts an excerpt of the earliest up to 3 completed, unarchived Q&A pairs in the lesson,
     * limited to [maxChars]. Returns null if there are no completed Q&A pairs.
     */
    fun extractExcerpt(entries: List<Entry>, maxChars: Int = 6000): String? {
        val questions = entries.filter { it.kind == EntryKind.USER && !it.archived && it.text.isNotBlank() }
            .sortedBy { it.id }
        val repliesByParent = entries.filter { it.kind == EntryKind.ASSISTANT && it.state == EntryState.COMPLETE && it.text.isNotBlank() }
            .groupBy { it.parentEntryId }

        val pairs = mutableListOf<String>()
        for (q in questions) {
            val reply = repliesByParent[q.id]?.firstOrNull() ?: continue
            pairs += "Q: ${q.text.trim()}\nA: ${reply.text.trim()}"
            if (pairs.size >= 3) break
        }

        if (pairs.isEmpty()) return null

        val joined = pairs.joinToString("\n\n")
        return if (joined.length > maxChars) joined.take(maxChars) else joined
    }

    /**
     * Builds AI prompt to extract title topic from the excerpt.
     */
    fun buildPrompt(excerpt: String, language: String): AiInput {
        val systemPrompt = if (language == "zh") {
            "你是一个课程标题提炼助手。请根据以下课堂问答内容，提炼一个简短的主题概括。要求：仅输出4至8个汉字的主题，不要包含日期，不要包含任何标点符号、解释或多余文字。"
        } else {
            "You extract lesson topics from conversation excerpts. Output only a topic title of 2 to 6 words without dates, punctuation, quotation marks or explanations."
        }
        return AiInput(
            systemText = systemPrompt,
            messages = listOf(com.feiyu.notes.ai.AiMessage(role = com.feiyu.notes.ai.AiRole.USER, text = excerpt))
        )
    }

    /**
     * Validates and normalizes candidate topic from raw model response.
     * For Chinese: exactly 4 to 8 CJK ideograph code points (BMP only, no symbols/newlines).
     * For English: 2 to 6 words.
     * Returns null if invalid (do not truncate/fudge to valid length).
     */
    fun validateTopic(raw: String, language: String): String? {
        if (raw.isBlank()) return null
        // Reject multiline explanations
        if (raw.contains('\n') || raw.contains('\r')) return null

        var text = raw.trim()
        // Strip common quote marks
        text = text.removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .removePrefix("“").removeSuffix("”")
            .removePrefix("「").removeSuffix("」")
            .trim()

        // If the model prepended a date prefix matching YYYY-MM-DD, strip it
        val dateMatch = DATE_PREFIX_REGEX.find(text)
        if (dateMatch != null) {
            text = text.substring(dateMatch.range.last + 1).trim()
        }

        if (text.isBlank()) return null

        return if (language == "zh") {
            validateChineseTopic(text)
        } else {
            validateEnglishTopic(text)
        }
    }

    private fun validateChineseTopic(text: String): String? {
        val codePointCount = text.codePointCount(0, text.length)
        if (codePointCount !in 4..8) return null

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // Reject non-BMP (supplementary) code points (e.g. emojis)
            if (Character.isSupplementaryCodePoint(cp)) return null

            val block = Character.UnicodeBlock.of(cp)
            val isCjk = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A

            if (!isCjk) return null
            i += Character.charCount(cp)
        }

        return text
    }

    private fun validateEnglishTopic(text: String): String? {
        val words = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size !in 2..6) return null

        val wordPattern = Regex("""^[A-Za-z0-9'\-]+$""")
        for (w in words) {
            if (!w.matches(wordPattern)) return null
        }

        return words.joinToString(" ")
    }

    /**
     * Formats full title: "YYYY-MM-DD <topic>".
     */
    fun formatTitle(datePrefix: String, topic: String): String = "$datePrefix $topic"
}
