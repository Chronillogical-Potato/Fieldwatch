package app.fieldwatch.domain

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The one lookup. Compose and the domain layer both call this.
 * An empty overlay is English and does not read a file.
 */
object LanguageText {
    @Volatile
    private var overlay: Map<String, String> = emptyMap()

    @Volatile
    private var locale: Locale = Locale.US

    /**
     * Compose only redraws a screen that read snapshot state.
     * [revision] on the root is not enough: a skippable screen stays as it was.
     */
    private val clock = mutableStateOf(0)

    private val revisions = MutableStateFlow(0)
    val revision: StateFlow<Int> = revisions.asStateFlow()

    fun apply(map: Map<String, String>, tag: String? = null) {
        locale = if (tag.isNullOrBlank() || tag.equals("en", ignoreCase = true)) {
            Locale.US
        } else {
            Locale.forLanguageTag(tag)
        }
        overlay = map
        clock.value = clock.value + 1
        revisions.value = revisions.value + 1
    }

    /** Sit names follow the active language. Numbers and share timestamps stay on Locale.US. */
    fun dateLocale(): Locale = locale

    fun clear() {
        apply(emptyMap())
    }

    fun text(key: String): String {
        observe()
        return resolve(key, overlay)
    }

    fun text(key: String, values: Map<String, String>): String {
        observe()
        val english = EnglishCopy.byKey[key]?.text ?: return key
        val needed = EnglishCopy.placeholders[key].orEmpty()
        if (needed.any { it !in values }) return english
        return LanguagePack.fill(resolve(key, overlay), values)
    }

    /** A read during composition subscribes that screen to the next language change. */
    private fun observe() {
        clock.value
    }

    private fun resolve(key: String, map: Map<String, String>): String {
        val line = EnglishCopy.byKey[key] ?: return key
        if (map.isEmpty()) return line.text
        val raw = map[key] ?: return line.text
        if (raw.any { it == '\n' || it == '\r' } && '\n' !in line.text) return line.text
        val cleaned = LanguagePack.clean(raw, allowNewline = '\n' in line.text).trim()
        if (cleaned.isEmpty() || cleaned.length > EnglishCopy.cap(line)) return line.text
        if (placeholderNames(cleaned) != EnglishCopy.placeholders.getValue(key)) return line.text
        return cleaned
    }
}
