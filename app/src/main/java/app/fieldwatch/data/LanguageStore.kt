package app.fieldwatch.data

import app.fieldwatch.domain.BundledLanguages
import app.fieldwatch.domain.EnglishCopy
import app.fieldwatch.domain.LanguageFile
import app.fieldwatch.domain.LanguagePack
import app.fieldwatch.domain.LanguageParse
import app.fieldwatch.domain.LanguagePreview
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException

class AssetLanguageFiles(
    private val assets: android.content.res.AssetManager,
) : LanguageAssets {
    override fun read(tag: String): ByteArray? {
        if (!BundledLanguages.contains(tag)) return null
        return runCatching { assets.open("lang/$tag.json").use { it.readBytes() } }.getOrNull()
    }
}

fun interface LanguageAssets {
    /** Read one bundled language. Other files stay unread. */
    fun read(tag: String): ByteArray?
}

data class LanguageSnapshot(
    val choice: String = CHOICE_FOLLOW,
    val doorDismissed: Boolean = false,
    val aiSeen: Set<String> = emptySet(),
    val importedName: String? = null,
    val activeTag: String? = null,
    val activeName: String = "English",
    val bundledActive: Boolean = false,
    val overlay: Map<String, String> = emptyMap(),
    val showDoor: Boolean = false,
    val showAi: Boolean = false,
) {
    val imported: Boolean get() = importedName != null

    companion object {
        const val CHOICE_FOLLOW = "follow"
        const val CHOICE_ENGLISH = "en"
    }
}

/**
 * Language choice and the cleaned import. Not part of [app.fieldwatch.domain.PersistedConfig].
 * Export settings does not read this file.
 */
class LanguageStore(
    private val file: File,
    private val assets: LanguageAssets,
) {
    private var disk = LanguageDisk()
    private var snapshot = LanguageSnapshot()

    fun load(phone: Locale): LanguageSnapshot {
        disk = readDisk()
        snapshot = resolve(phone)
        return snapshot
    }

    fun current(): LanguageSnapshot = snapshot

    fun choose(choice: String, phone: Locale): LanguageSnapshot {
        if (snapshot.imported) return snapshot
        val next = when {
            choice == LanguageSnapshot.CHOICE_FOLLOW -> choice
            choice == LanguageSnapshot.CHOICE_ENGLISH -> choice
            BundledLanguages.contains(choice) -> choice
            else -> return snapshot
        }
        disk = disk.copy(choice = next)
        save()
        snapshot = resolve(phone)
        return snapshot
    }

    fun confirm(preview: LanguagePreview, phone: Locale): LanguageSnapshot {
        val previous = disk
        disk = disk.copy(
            pack = LanguageFile(
                format = EnglishCopy.FORMAT,
                formatVersion = EnglishCopy.FORMAT_VERSION,
                language = preview.language,
                languageTag = preview.languageTag,
                writtenFor = preview.writtenFor,
                strings = preview.accepted,
            ),
        )
        try {
            save()
        } catch (e: CancellationException) {
            disk = previous
            throw e
        } catch (e: Exception) {
            disk = previous
            throw e
        }
        snapshot = resolve(phone)
        return snapshot
    }

    fun remove(phone: Locale): LanguageSnapshot {
        disk = disk.copy(choice = LanguageSnapshot.CHOICE_FOLLOW, pack = null)
        save()
        snapshot = resolve(phone)
        return snapshot
    }

    fun dismissDoor(phone: Locale): LanguageSnapshot {
        disk = disk.copy(doorDismissed = true)
        save()
        snapshot = resolve(phone)
        return snapshot
    }

    fun markAiSeen(tag: String, phone: Locale): LanguageSnapshot {
        if (tag.isBlank() || tag in disk.aiSeen) return snapshot
        disk = disk.copy(aiSeen = disk.aiSeen + tag)
        save()
        snapshot = resolve(phone)
        return snapshot
    }

    private fun resolve(phone: Locale): LanguageSnapshot {
        val imported = disk.pack?.let { stored ->
            val checked = LanguagePack.recheck(stored) ?: return englishFallback(phone)
            stored to checked
        }
        if (imported != null) {
            val (stored, strings) = imported
            if (strings.isEmpty()) return englishFallback(phone)
            return LanguageSnapshot(
                choice = disk.choice,
                doorDismissed = disk.doorDismissed,
                aiSeen = disk.aiSeen.toSet(),
                importedName = LanguagePack.clean(stored.language, allowNewline = false).trim(),
                activeTag = null,
                activeName = LanguagePack.clean(stored.language, allowNewline = false).trim(),
                bundledActive = false,
                overlay = strings,
                showDoor = false,
                showAi = false,
            )
        }
        val tag = when (disk.choice) {
            LanguageSnapshot.CHOICE_ENGLISH -> null
            LanguageSnapshot.CHOICE_FOLLOW -> LanguagePack.bundledTag(phone)
            else -> disk.choice.takeIf { BundledLanguages.contains(it) }
        }
        val bundled = tag?.let { readBundled(it) }
        val active = bundled != null
        return LanguageSnapshot(
            choice = disk.choice,
            doorDismissed = disk.doorDismissed,
            aiSeen = disk.aiSeen.toSet(),
            importedName = null,
            activeTag = if (active) tag else null,
            activeName = if (active) BundledLanguages.english(tag!!) else "English",
            bundledActive = active,
            overlay = bundled?.accepted ?: emptyMap(),
            showDoor = LanguagePack.showImportDoor(phone, disk.doorDismissed, packLoaded = false),
            showAi = active && tag != null && tag !in disk.aiSeen,
        )
    }

    private fun englishFallback(phone: Locale): LanguageSnapshot {
        disk = disk.copy(pack = null, choice = LanguageSnapshot.CHOICE_ENGLISH)
        save()
        return resolve(phone)
    }

    private fun readBundled(tag: String): LanguagePreview? {
        val bytes = assets.read(tag) ?: return null
        val parsed = LanguagePack.parse(bytes)
        return (parsed as? LanguageParse.Ok)?.preview
    }

    private fun readDisk(): LanguageDisk {
        if (!file.isFile) return LanguageDisk()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return LanguageDisk(choice = LanguageSnapshot.CHOICE_ENGLISH)
        return runCatching {
            LanguagePack.json.decodeFromString(LanguageDisk.serializer(), text)
        }.getOrElse { LanguageDisk(choice = LanguageSnapshot.CHOICE_ENGLISH) }
    }

    private fun save() {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(LanguagePack.json.encodeToString(LanguageDisk.serializer(), disk))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

@Serializable
data class LanguageDisk(
    val choice: String = LanguageSnapshot.CHOICE_FOLLOW,
    val doorDismissed: Boolean = false,
    val aiSeen: List<String> = emptyList(),
    val pack: LanguageFile? = null,
)

object LanguageFiles {
    const val MAX_BYTES = LanguagePack.MAX_BYTES

    fun readCapped(input: InputStream): LanguageRead {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return LanguageRead.TooBig
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return LanguageRead.Empty
        return LanguageRead.Bytes(bytes)
    }
}

sealed class LanguageRead {
    data class Bytes(val bytes: ByteArray) : LanguageRead()
    data object TooBig : LanguageRead()
    data object Empty : LanguageRead()
}
