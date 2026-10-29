package app.fieldwatch.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/** A picked file failed before anything on the phone changed. [key] is a language key. */
class ImportFileException(val key: String) : Exception()

/**
 * Reads an import into memory with a hard stop.
 * A huge file becomes [language.err.large] instead of running the phone out of memory.
 */
object ImportFiles {
    /** A signature pack or the stock catalog. The published catalog is about 2 MB. */
    const val SIGNATURE_MAX = 8 * 1024 * 1024

    /** Settings and a trusted-network list are small JSON files. */
    const val SETTINGS_MAX = 2 * 1024 * 1024
    const val TRUSTED_MAX = 1024 * 1024

    fun read(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw ImportFileException("ui.could.not.read.file")
            }
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ImportFileException("language.err.large")
            out.write(buf, 0, n)
        }
        if (out.size() == 0) throw ImportFileException("language.err.empty")
        return out.toByteArray()
    }
}
