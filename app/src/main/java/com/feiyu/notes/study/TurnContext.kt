package com.feiyu.notes.study

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KnowledgeContext(
    val notebookId: Long,
    val documentId: Long,
    val documentRevision: Long,
    val sectionId: Long,
    val headingPath: String,
    val markdown: String,
)

@Serializable
data class TurnContext(
    val version: Int = 1,
    val scratchpad: String? = null,
    val knowledge: List<KnowledgeContext> = emptyList(),
) {
    val isEmpty: Boolean get() = scratchpad.isNullOrBlank() && knowledge.isEmpty()

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val EMPTY = TurnContext()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(raw: String?): TurnContext {
            if (raw.isNullOrBlank() || raw == "{}") return EMPTY
            val ctx = json.decodeFromString(serializer(), raw)
            if (ctx.version != 1) {
                throw IllegalArgumentException("Unsupported TurnContext version: ${ctx.version}")
            }
            return ctx
        }
    }
}
