package com.feiyu.notes.settings

import android.content.Context
import com.feiyu.notes.app
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Only the first system language decides; secondary preferred languages do not change this rule. */
object AppLanguage {
    fun code(language: String): String = if (language.equals("zh", ignoreCase = true)) "zh" else "en"

    /** "zh" or "en" as currently applied to the app's own text. */
    fun current(base: Context): String = code(context(base).resources.configuration.locales[0].language)

    fun context(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        val language = when (base.app.prefs.display.value.language) {
            UiLanguage.SYSTEM -> code(config.locales[0].language)
            UiLanguage.ZH -> "zh"
            UiLanguage.EN -> "en"
        }
        config.setLocale(Locale.forLanguageTag(language))
        val localized = base.createConfigurationContext(config)
        // Wrap rather than replace: Compose finds the Activity (result registry, back dispatcher)
        // by walking ContextWrapper bases, so only resources may change.
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = localized.resources
            override fun getAssets(): AssetManager = localized.assets
        }
    }
}
