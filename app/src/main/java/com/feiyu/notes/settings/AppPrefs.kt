package com.feiyu.notes.settings

import android.content.Context
import com.feiyu.notes.ai.ModelChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UiLanguage { SYSTEM, ZH, EN }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class TextSize(val scale: Float) { STANDARD(1f), LARGE(1.15f), EXTRA_LARGE(1.3f), LARGEST(1.5f) }
data class DisplayPreferences(
    val language: UiLanguage = UiLanguage.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textSize: TextSize = TextSize.STANDARD,
)

data class Features(val chat: Boolean = true, val study: Boolean = true) {
    fun normalized() = if (!chat && !study) Features() else this
}

/** Plain UI preferences; not part of the notebook database. */
class AppPrefs(context: Context, name: String = NAME) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val _display = MutableStateFlow(DisplayPreferences(
        language = UiLanguage.entries.firstOrNull { it.name == prefs.getString("language", null) } ?: UiLanguage.SYSTEM,
        theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM,
        textSize = TextSize.entries.firstOrNull { it.name == prefs.getString("text_size", null) } ?: TextSize.STANDARD,
    ))
    val display = _display.asStateFlow()

    fun setLanguage(value: UiLanguage) = updateDisplay(display.value.copy(language = value))
    fun setTheme(value: ThemeMode) = updateDisplay(display.value.copy(theme = value))
    fun setTextSize(value: TextSize) = updateDisplay(display.value.copy(textSize = value))

    private fun updateDisplay(value: DisplayPreferences) {
        prefs.edit().putString("language", value.language.name).putString("theme", value.theme.name)
            .putString("text_size", value.textSize.name).apply()
        _display.value = value
    }

    fun prompt(kind: com.feiyu.notes.study.PromptKind, language: String): String =
        prefs.getString("prompt_${kind.name}", null)?.takeIf { it.isNotBlank() } ?: kind.default(language)

    fun isPromptCustom(kind: com.feiyu.notes.study.PromptKind): Boolean = prefs.contains("prompt_${kind.name}")

    /** Blank text or either built-in language's default restores the built-in default, so it keeps following the language. */
    fun setPrompt(kind: com.feiyu.notes.study.PromptKind, text: String?) {
        val value = text?.trim().orEmpty()
        prefs.edit().apply {
            if (value.isEmpty() || value == kind.default("zh") || value == kind.default("en")) remove("prompt_${kind.name}")
            else putString("prompt_${kind.name}", value)
        }.apply()
    }

    /** Which halves of the app are on. At least one stays on; a stored "both off" falls back to both on. */
    private val _features = MutableStateFlow(
        Features(prefs.getBoolean("chat_enabled", true), prefs.getBoolean("study_enabled", true)).normalized(),
    )
    val features = _features.asStateFlow()

    fun setFeatures(value: Features) {
        val safe = value.normalized()
        prefs.edit().putBoolean("chat_enabled", safe.chat).putBoolean("study_enabled", safe.study).apply()
        _features.value = safe
    }

    private val _modelRevision = MutableStateFlow(0L)
    val modelRevision = _modelRevision.asStateFlow()

    fun sessionModel(lessonId: Long, default: ModelChoice): ModelChoice =
        prefs.getString("model_$lessonId", null)?.let { model ->
            ModelChoice(model, prefs.getString("effort_$lessonId", null) ?: default.effort)
        } ?: default

    fun setSessionModel(lessonId: Long, choice: ModelChoice?) {
        val edit = prefs.edit()
        if (choice == null) edit.remove("model_$lessonId").remove("effort_$lessonId")
        else edit.putString("model_$lessonId", choice.model.trim()).putString("effort_$lessonId", choice.effort.trim())
        edit.apply()
        _modelRevision.value++
    }

    val lastLesson: Pair<Long, Long>?
        get() {
            val notebook = prefs.getLong(NOTEBOOK, -1)
            val lesson = prefs.getLong(LESSON, -1)
            return if ((notebook > 0 && lesson > 0) || (notebook == -2L && lesson == -2L)) notebook to lesson else null
        }

    fun setLastLesson(notebookId: Long, lessonId: Long) {
        prefs.edit().putLong(NOTEBOOK, notebookId).putLong(LESSON, lessonId).apply()
    }

    fun clearLastLesson() {
        prefs.edit().remove(NOTEBOOK).remove(LESSON).apply()
    }

    /** Pick once per lesson. Resource IDs are never persisted because they change between builds. */
    @Synchronized
    fun avatarIndex(lessonId: Long, count: Int): Int {
        require(count > 0)
        val key = "avatar_$lessonId"
        val saved = prefs.getInt(key, -1)
        if (saved in 0 until count) return saved
        return kotlin.random.Random.nextInt(count).also { prefs.edit().putInt(key, it).apply() }
    }

    companion object {
        const val NAME = "app_prefs"
        private const val NOTEBOOK = "last_notebook_id"
        private const val LESSON = "last_lesson_id"
    }
}
