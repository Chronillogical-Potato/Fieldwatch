package app.fieldwatch.data

import app.fieldwatch.domain.LanguagePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ImportHandlingTest {
    @Test
    fun aCappedReadNamesEmptyLargeAndIo() {
        try {
            ImportFiles.read(ByteArrayInputStream(ByteArray(0)), 10)
            fail()
        } catch (e: ImportFileException) {
            assertEquals("language.err.empty", e.key)
        }
        try {
            ImportFiles.read(ByteArrayInputStream(ByteArray(11)), 10)
            fail()
        } catch (e: ImportFileException) {
            assertEquals("language.err.large", e.key)
        }
        val text = ImportFiles.read(ByteArrayInputStream("hi".toByteArray()), 10)
        assertEquals("hi", text.toString(Charsets.UTF_8))
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("disk")
        }
        try {
            ImportFiles.read(broken, 10)
            fail()
        } catch (e: ImportFileException) {
            assertEquals("ui.could.not.read.file", e.key)
        }
    }

    @Test
    fun aCatalogFailureNamesTheCause() {
        assertEquals(CatalogRemote.Reason.REACH, CatalogRemote.failReason(UnknownHostException("catalog")))
        assertEquals(CatalogRemote.Reason.REACH, CatalogRemote.failReason(SocketTimeoutException("timed out")))
        assertEquals(CatalogRemote.Reason.TRUST, CatalogRemote.failReason(SSLHandshakeException("bad cert")))
        assertEquals(CatalogRemote.Reason.OTHER, CatalogRemote.failReason(IllegalStateException("other")))
    }

    @Test
    fun aBadTrustedFileLeavesTheListAndSaysWhy() {
        val dir = File.createTempFile("fw-trust", "").apply {
            delete()
            mkdirs()
        }
        val store = TrustedNetworkStore(File(dir, "trusted-networks.json"), CoroutineScope(Dispatchers.Unconfined))
        assertFalse(store.importText(""))
        assertEquals("language.err.empty", store.importProblem.value)
        assertFalse(store.importText("nope"))
        assertEquals("trusted.import.bad", store.importProblem.value)
        assertTrue(store.snapshot().nets.isEmpty())
        val blocked = File(dir, "not-a-folder").apply { writeText("x") }
        val stuck = TrustedNetworkStore(File(blocked, "trusted-networks.json"), CoroutineScope(Dispatchers.Unconfined))
        assertFalse(stuck.importText("""{"format":"fieldwatch-trusted","nets":[{"ssid":"Home","trustedAt":1}]}"""))
        assertEquals("import.failed.save", stuck.importProblem.value)
        assertTrue(stuck.snapshot().nets.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun aLanguageSaveFailureKeepsThePreviousPack() {
        val dir = File.createTempFile("fw-lang", "").apply {
            delete()
            mkdirs()
        }
        val blocked = File(dir, "not-a-folder").apply { writeText("x") }
        val store = LanguageStore(File(blocked, "language.json")) { null }
        store.load(Locale.US)
        val preview = LanguagePreview(
            language = "Test",
            languageTag = "fr",
            writtenFor = "1.1.27",
            formatVersion = 1,
            accepted = mapOf("tab.live" to "Direct"),
            changed = 1,
            stayEnglish = 0,
            ignored = 0,
            newSince = null,
            formatNewer = false,
        )
        try {
            store.confirm(preview, Locale.US)
            fail()
        } catch (_: Exception) {
            assertFalse(store.current().imported)
        }
        dir.deleteRecursively()
    }
}
