package com.feiyu.notes.study

import com.feiyu.notes.ai.AiInput
import com.feiyu.notes.ai.AiMessage
import com.feiyu.notes.ai.AiRole
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.Template
import java.io.File

/**
 * Pure request assembly (spec §6). The saved user entry is the only authority for a turn:
 * action, parent, own photo, attached photos and template come from it. Callers pass the
 * already-validated reference note and template contents.
 */
object ContextBuilder {
    /**
     * Upper bound for prior messages in one request (about 60k tokens of Chinese text at worst), so a
     * very long chat never exceeds the model's context window. The oldest messages go first.
     */
    const val HISTORY_CHAR_BUDGET = 60_000

    fun buildTurn(
        target: Entry,
        lessonEntries: List<Entry>,
        referenceNote: Entry?,
        template: Template?,
        resolvePhoto: (String) -> File,
        language: String = "zh",
        base: String = StudyPrompts.of(language).system,
    ): AiInput {
        val set = StudyPrompts.of(language)
        require(target.kind == EntryKind.USER && target.action != null)
        val chain = ancestors(target, lessonEntries)
        val onChain = chain.associateBy { it.id }

        val history = recent(chain.mapNotNull { e ->
            when {
                e.kind == EntryKind.USER -> AiMessage(AiRole.USER, e.text.ifBlank { set.photoPlaceholder })
                e.kind == EntryKind.ASSISTANT && e.state == EntryState.COMPLETE -> AiMessage(AiRole.ASSISTANT, e.text)
                else -> null
            }
        })

        // Own photo first, then user-selected photos from this chain only.
        val images = buildList {
            addAll(target.imagePaths.map(resolvePhoto))
            target.attachedImageEntryIds.mapNotNull(onChain::get)
                .filter { it.kind == EntryKind.USER }
                .forEach { addAll(it.imagePaths.map(resolvePhoto)) }
        }

        val text = when (target.action) {
            EntryAction.ASK -> target.text.ifBlank { if (images.isNotEmpty()) set.identifyAndExplain else "" }
            EntryAction.EXPAND -> listOf(set.expand, target.text).filter { it.isNotBlank() }.joinToString("\n")
            EntryAction.MISTAKE -> "${set.mistake}\n${set.myAnswer}${target.text.ifBlank { set.photoPlaceholder }}"
        }

        val system = StudyPrompts.withTemplate(
            set,
            StudyPrompts.withReference(set, base, referenceNote?.text),
            template?.instruction,
        )
        return AiInput(system, history + AiMessage(AiRole.USER, text, images))
    }

    /**
     * Summary of a lesson's completed Q&A, text only (spec §6). [lessonEntries] must already
     * exclude archived threads. Returns null when there is nothing completed to summarize.
     */
    fun buildSummary(lessonEntries: List<Entry>, template: Template?, language: String = "zh", base: String = PromptKind.SUMMARY.default(language)): Pair<AiInput, List<Long>>? {
        val set = StudyPrompts.of(language)
        val answered = lessonEntries.filter { it.kind == EntryKind.ASSISTANT && it.state == EntryState.COMPLETE }
        if (answered.isEmpty()) return null
        val byId = lessonEntries.associateBy { it.id }
        val lines = mutableListOf<String>()
        val sources = mutableListOf<Long>()
        for (answer in answered) {
            val question = answer.parentEntryId?.let(byId::get) ?: continue
            val parentNote = question.parentEntryId?.let { "${set.followUpFrom}$it" }.orEmpty()
            lines += "[#${question.id} ${set.questionTag}$parentNote] ${question.text.ifBlank { set.photoPlaceholder }}"
            lines += "[#${answer.id} ${set.answerTag}] ${answer.text}"
            sources += listOf(question.id, answer.id)
        }
        if (sources.isEmpty()) return null
        val system = StudyPrompts.withTemplate(set, base, template?.instruction)
        val input = AiInput(system, listOf(AiMessage(AiRole.USER, set.lessonQa + "\n" + lines.joinToString("\n"))))
        return input to sources.distinct()
    }

    /** Entries from the thread root down to (excluding) [target]. */
    /** Newest messages whose text fits [budget]; never starts with an answer whose question was dropped. */
    internal fun recent(history: List<AiMessage>, budget: Int = HISTORY_CHAR_BUDGET): List<AiMessage> {
        var used = 0
        val kept = history.asReversed().takeWhile { used += it.text.length; used <= budget }.asReversed()
        return kept.dropWhile { it.role != AiRole.USER }
    }

    fun ancestors(target: Entry, lessonEntries: List<Entry>): List<Entry> {
        val byId = lessonEntries.associateBy { it.id }
        val chain = ArrayDeque<Entry>()
        var next = target.parentEntryId?.let(byId::get)
        while (next != null && chain.size < lessonEntries.size) {
            chain.addFirst(next)
            next = next.parentEntryId?.let(byId::get)
        }
        return chain.toList()
    }
}
