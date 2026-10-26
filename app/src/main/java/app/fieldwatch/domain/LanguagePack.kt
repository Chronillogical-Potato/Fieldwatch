package app.fieldwatch.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** One language file after the line rules. Nothing is stored until the user confirms. */
data class LanguagePreview(
    val language: String,
    val languageTag: String?,
    val writtenFor: String?,
    val formatVersion: Int,
    val accepted: Map<String, String>,
    val changed: Int,
    val stayEnglish: Int,
    val ignored: Int,
    /** Null when writtenFor is missing or not a version the app can compare. */
    val newSince: Int?,
    val formatNewer: Boolean,
)

sealed class LanguageParse {
    data class Ok(val preview: LanguagePreview) : LanguageParse()
    data class Reject(val messageKey: String) : LanguageParse()
}

@Serializable
data class LanguageFile(
    val format: String = "",
    val formatVersion: Int = 0,
    val language: String = "",
    val languageTag: String? = null,
    val writtenFor: String? = null,
    val strings: Map<String, String> = emptyMap(),
)

@Serializable
private data class LanguageHeader(val format: String = "")

@Serializable
private data class LanguageTemplateFile(
    val format: String,
    val formatVersion: Int,
    val language: String,
    val languageTag: String,
    val writtenFor: String,
    val slots: Map<String, String>,
    val strings: Map<String, String>,
)

object LanguagePack {
    const val MAX_BYTES = 264 * 1024
    const val MAX_KEYS = 2000
    const val MAX_NAME = 40
    const val MAX_WRITTEN_FOR = 32

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val token = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
    private val tagRe = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,2}$")

    fun parse(bytes: ByteArray): LanguageParse {
        if (bytes.isEmpty()) return LanguageParse.Reject(LanguageErrors.EMPTY)
        if (bytes.size > MAX_BYTES) return LanguageParse.Reject(LanguageErrors.LARGE)
        val text = decodeUtf8(bytes) ?: return LanguageParse.Reject(LanguageErrors.NOT_PACK)
        return parse(text)
    }

    fun parse(text: String): LanguageParse {
        val body = text.trim().trimStart('\uFEFF')
        if (body.isEmpty()) return LanguageParse.Reject(LanguageErrors.EMPTY)
        val header = try {
            json.decodeFromString(LanguageHeader.serializer(), body)
        } catch (_: Exception) {
            return LanguageParse.Reject(LanguageErrors.NOT_PACK)
        }
        when (header.format) {
            SettingsPack.FORMAT -> return LanguageParse.Reject(LanguageErrors.SETTINGS)
            SignaturePack.FORMAT, SignaturePack.LEGACY_FORMAT ->
                return LanguageParse.Reject(LanguageErrors.SIGNATURES)
        }
        if (header.format != EnglishCopy.FORMAT) return LanguageParse.Reject(LanguageErrors.NOT_PACK)
        val file = try {
            json.decodeFromString(LanguageFile.serializer(), body)
        } catch (_: Exception) {
            return LanguageParse.Reject(LanguageErrors.NOT_PACK)
        }
        if (file.formatVersion < 1) return LanguageParse.Reject(LanguageErrors.NOT_PACK)
        if (file.strings.size > MAX_KEYS) return LanguageParse.Reject(LanguageErrors.NOT_PACK)
        val name = clean(file.language, allowNewline = false).trim()
        if (name.isEmpty() || name.length > MAX_NAME) return LanguageParse.Reject(LanguageErrors.NOT_PACK)
        val tag = file.languageTag?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_WRITTEN_FOR && tagRe.matches(it) }
        val written = file.writtenFor?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_WRITTEN_FOR }
        val (accepted, ignored) = acceptLines(file.strings)
        if (accepted.isEmpty()) return LanguageParse.Reject(LanguageErrors.NONE)
        val writtenVersion = written?.let { parseVersion(it) }
        val newSince = writtenVersion?.let { version ->
            EnglishCopy.lines.count { line ->
                line.key !in accepted && versionOf(line.addedIn)?.let { compareVersion(it, version) > 0 } == true
            }
        }
        return LanguageParse.Ok(
            LanguagePreview(
                language = name,
                languageTag = tag,
                writtenFor = written,
                formatVersion = file.formatVersion,
                accepted = accepted,
                changed = accepted.size,
                stayEnglish = EnglishCopy.lines.size - accepted.size,
                ignored = ignored,
                newSince = newSince,
                formatNewer = file.formatVersion > EnglishCopy.FORMAT_VERSION,
            ),
        )
    }

    /** Line rules for a map the app already stored. A bad line is dropped. The rest stay. */
    fun recheck(file: LanguageFile): Map<String, String>? {
        if (file.format != EnglishCopy.FORMAT || file.formatVersion < 1) return null
        if (file.strings.size > MAX_KEYS) return null
        val name = clean(file.language, allowNewline = false).trim()
        if (name.isEmpty() || name.length > MAX_NAME) return null
        return acceptLines(file.strings).first
    }

    fun template(versionName: String): String {
        val slots = LinkedHashMap<String, String>(EnglishCopy.lines.size)
        val strings = LinkedHashMap<String, String>(EnglishCopy.lines.size)
        for (line in EnglishCopy.lines) {
            slots[line.key] = EnglishCopy.slotName(line.slot)
            strings[line.key] = line.text
        }
        val file = LanguageTemplateFile(
            format = EnglishCopy.FORMAT,
            formatVersion = EnglishCopy.FORMAT_VERSION,
            language = "English",
            languageTag = "en",
            writtenFor = versionName,
            slots = slots,
            strings = strings,
        )
        return json.encodeToString(LanguageTemplateFile.serializer(), file)
    }

    /**
     * Bundled file for this phone language, or null when the phone stays on the compiled English.
     * Does not change the Android locale.
     */
    fun bundledTag(locale: Locale): String? {
        val lang = locale.language.lowercase(Locale.US)
        val country = locale.country.uppercase(Locale.US)
        if (lang == "sr" && country == "ME") return "hr"
        if (lang == "cnr") return "hr"
        if (lang == "sr" || lang == "lb" || lang == "en" || lang.isEmpty()) return null
        return when (lang) {
            "sq", "bg", "hr", "cs", "da", "nl", "et", "fi", "fr", "de", "el", "hu", "is", "it",
            "lv", "lt", "mk", "pl", "pt", "ro", "sk", "sl", "es", "sv", "tr",
            -> lang
            "nb", "nn", "no" -> "nb"
            else -> null
        }
    }

    fun showImportDoor(locale: Locale, dismissed: Boolean, packLoaded: Boolean): Boolean {
        if (dismissed || packLoaded) return false
        if (locale.language.equals("en", ignoreCase = true)) return false
        return bundledTag(locale) == null
    }

    fun fill(template: String, values: Map<String, String>): String {
        val needed = placeholderNames(template)
        if (needed.any { it !in values }) return template
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val start = template.indexOf('{', i)
            if (start < 0) {
                out.append(template, i, template.length)
                break
            }
            out.append(template, i, start)
            val match = token.find(template, start)
            if (match == null || match.range.first != start) {
                out.append(template[start])
                i = start + 1
                continue
            }
            val name = match.groupValues[1]
            if (name in values) out.append(values.getValue(name)) else out.append(match.value)
            i = match.range.last + 1
        }
        return out.toString()
    }

    fun clean(raw: String, allowNewline: Boolean): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\r' -> Unit
                ch == '\n' -> if (allowNewline) out.append('\n')
                ch == '\uFEFF' || ch == '\u200E' || ch == '\u200F' ||
                    ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' -> Unit
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                else -> {
                    val type = Character.getType(ch)
                    if (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()) {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }

    fun parseVersion(raw: String): List<Int>? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it != '.' && !it.isDigit() }) return null
        if (text.startsWith('.') || text.endsWith('.') || ".." in text) return null
        return text.split('.').map { part -> part.toIntOrNull() ?: return null }
    }

    fun compareVersion(left: List<Int>, right: List<Int>): Int {
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val delta = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (delta != 0) return delta
        }
        return 0
    }

    private fun versionOf(raw: String): List<Int>? = parseVersion(raw)

    private fun acceptLines(strings: Map<String, String>): Pair<Map<String, String>, Int> {
        val accepted = LinkedHashMap<String, String>()
        var ignored = 0
        for ((key, value) in strings) {
            val line = EnglishCopy.byKey[key]
            if (line == null) {
                ignored++
                continue
            }
            if (value.any { it == '\n' || it == '\r' } && '\n' !in line.text) {
                ignored++
                continue
            }
            val cleaned = clean(value, allowNewline = '\n' in line.text).trim()
            if (cleaned.isEmpty() || cleaned.length > EnglishCopy.cap(line)) {
                ignored++
                continue
            }
            if (placeholderNames(cleaned) != EnglishCopy.placeholders.getValue(key)) {
                ignored++
                continue
            }
            accepted[key] = cleaned
        }
        return accepted to ignored
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

fun placeholderNames(text: String): Set<String> {
    val names = LinkedHashSet<String>()
    val re = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
    for (match in re.findAll(text)) names.add(match.groupValues[1])
    return names
}
