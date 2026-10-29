package app.fieldwatch.data

import app.fieldwatch.domain.TrustedAlert
import app.fieldwatch.domain.TrustedBook
import app.fieldwatch.domain.TrustedExchange
import app.fieldwatch.domain.TrustedExportFile
import app.fieldwatch.domain.TrustedNet
import app.fieldwatch.domain.TrustedNets
import app.fieldwatch.domain.TrustedOpt
import app.fieldwatch.domain.TrustedSitLine
import app.fieldwatch.domain.TwinHear
import app.fieldwatch.domain.TwinPin
import app.fieldwatch.domain.TwinTomb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Trusted Wi-Fi names. One file, separate from settings and from the live list.
 * Places and pin coordinates stay in this file and out of the export.
 */
class TrustedNetworkStore(private val file: File, private val scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private var book = TrustedBook()
    private var tombs = emptyList<TwinTomb>()
    private var dirty = false
    private val _book = MutableStateFlow(TrustedBook())
    val bookFlow: StateFlow<TrustedBook> = _book.asStateFlow()
    private val _notice = MutableStateFlow<TrustedAlert?>(null)
    val notice: StateFlow<TrustedAlert?> = _notice.asStateFlow()
    private val _importProblem = MutableStateFlow<String?>(null)
    /** A language key. Null when the last import was kept or has not been tried. */
    val importProblem: StateFlow<String?> = _importProblem.asStateFlow()

    fun load() {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val parsed = runCatching { json.decodeFromString(TrustedFile.serializer(), text) }.getOrNull() ?: return
        synchronized(lock) {
            book = TrustedBook(parsed.nets)
            tombs = parsed.tombs
            _book.value = book
            dirty = false
        }
    }

    fun startFlusher() {
        scope.launch {
            while (true) {
                delay(FLUSH_MS)
                runCatching { flush() }
            }
        }
    }

    fun snapshot(): TrustedBook = synchronized(lock) { book }

    fun linesIn(start: Long, end: Long): List<TrustedSitLine> =
        synchronized(lock) { TrustedNets.linesIn(book, start, end) }

    fun hear(hear: TwinHear, opt: TrustedOpt, now: Long): TrustedAlert? = synchronized(lock) {
        val step = TrustedNets.hear(book, hear, opt, now)
        if (step.book != book) {
            book = step.book
            dirty = true
            _book.value = book
        }
        val alert = step.alert
        if (alert != null) _notice.value = alert
        alert
    }

    fun trust(ssid: String, picks: List<TwinHear>, now: Long) = edit {
        book = TrustedNets.trust(book, ssid, picks, now)
    }

    fun forget(ssid: String) {
        synchronized(lock) {
            val net = book.nets.find { it.ssid == ssid } ?: return
            queue(net.pins)
            book = TrustedNets.forget(book, ssid)
            if (_notice.value?.ssid == ssid) _notice.value = null
            dirty = true
            _book.value = book
            flush()
        }
    }

    fun rename(ssid: String, label: String) = edit { book = TrustedNets.rename(book, ssid, label) }

    fun setPaused(ssid: String, paused: Boolean) = edit { book = TrustedNets.setPaused(book, ssid, paused) }

    fun setTravels(ssid: String, travels: Boolean) = edit { book = TrustedNets.setTravels(book, ssid, travels) }

    fun removeAp(ssid: String, bssid: String) {
        synchronized(lock) {
            pinOf(ssid, bssid)?.let { queue(listOf(it)) }
            book = TrustedNets.removeAp(book, ssid, bssid)
            dropPin(ssid, bssid)
            dirty = true
            _book.value = book
            flush()
        }
    }

    fun removePlace(ssid: String, index: Int) = edit { book = TrustedNets.removePlace(book, ssid, index) }

    fun stopIgnore(ssid: String, bssid: String) = edit { book = TrustedNets.stopIgnore(book, ssid, bssid) }

    fun dismiss(ssid: String, bssid: String, now: Long) = synchronized(lock) {
        book = TrustedNets.dismiss(book, ssid, bssid, now)
        if (_notice.value?.ssid == ssid && _notice.value?.bssid.equals(bssid, true)) _notice.value = null
        dirty = true
        _book.value = book
    }

    fun learnAp(ssid: String, bssid: String, now: Long) {
        synchronized(lock) {
            pinOf(ssid, bssid)?.let { queue(listOf(it)) }
            book = TrustedNets.learnAp(book, ssid, bssid, now)
            dropPin(ssid, bssid)
            if (_notice.value?.ssid == ssid && _notice.value?.bssid.equals(bssid, true)) _notice.value = null
            dirty = true
            _book.value = book
            flush()
        }
    }

    fun ignore(ssid: String, bssid: String) {
        synchronized(lock) {
            pinOf(ssid, bssid)?.let { queue(listOf(it)) }
            book = TrustedNets.ignore(book, ssid, bssid)
            dropPin(ssid, bssid)
            if (_notice.value?.ssid == ssid && _notice.value?.bssid.equals(bssid, true)) _notice.value = null
            dirty = true
            _book.value = book
            flush()
        }
    }

    fun remember(ssid: String, bssid: String, now: Long) {
        synchronized(lock) {
            book = TrustedNets.remember(book, ssid, bssid, now)
            if (_notice.value?.ssid == ssid && _notice.value?.bssid.equals(bssid, true)) _notice.value = null
            dirty = true
            _book.value = book
            flush()
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun clearImportFailed() {
        _importProblem.value = null
    }

    /** The trusted page says why. The list already on the phone stays. */
    fun noteImportProblem(key: String) {
        _importProblem.value = key
    }

    fun exportText(): String = synchronized(lock) { TrustedExchange.encode(book) }

    /** False leaves the list on the phone as it is. [importProblem] says why. */
    fun importText(text: String): Boolean {
        if (text.isBlank()) {
            _importProblem.value = "language.err.empty"
            return false
        }
        val incoming = TrustedExchange.parse(text)
        if (incoming == null) {
            _importProblem.value = "trusted.import.bad"
            return false
        }
        val previous: TrustedBook
        synchronized(lock) {
            previous = book
            book = TrustedNets.mergeImport(book, incoming)
            dirty = true
            _book.value = book
        }
        if (!flush()) {
            synchronized(lock) {
                book = previous
                dirty = true
                _book.value = book
            }
            flush()
            _importProblem.value = "import.failed.save"
            return false
        }
        _importProblem.value = null
        return true
    }

    data class CotPlan(
        val book: TrustedBook,
        val tombs: List<TwinTomb>,
        val xml: List<String>,
    )

    /** Does not change the file. [commitCot] keeps the result after a send. */
    fun planCot(opt: TrustedOpt, takRunning: Boolean, now: Long): CotPlan = synchronized(lock) {
        if (!takRunning) return CotPlan(book, emptyList(), emptyList())
        val (next, pinXml) = TrustedNets.pinCalls(book, opt, true, now)
        val tombXml = tombs.map { TrustedNets.tombXml(it, now) }
        CotPlan(next, tombs, tombXml + pinXml)
    }

    fun commitCot(plan: CotPlan) = synchronized(lock) {
        book = TrustedNets.applyPins(book, plan.book)
        val sent = plan.tombs.map { it.uid }.toSet()
        tombs = tombs.filter { it.uid !in sent }
        dirty = true
        _book.value = book
    }

    private fun edit(block: () -> Unit) {
        synchronized(lock) {
            block()
            dirty = true
            _book.value = book
            flush()
        }
    }

    private fun pinOf(ssid: String, bssid: String): TwinPin? =
        book.nets.find { it.ssid == ssid }?.pins?.find { it.bssid.equals(bssid, true) }

    private fun dropPin(ssid: String, bssid: String) {
        book = book.copy(nets = book.nets.map { net ->
            if (net.ssid != ssid) net else net.copy(pins = net.pins.filterNot { it.bssid.equals(bssid, true) })
        })
    }

    private fun queue(pins: List<TwinPin>) {
        if (pins.isEmpty()) return
        val more = pins.map { TrustedNets.tombOf(it) }
        tombs = (tombs + more).distinctBy { it.uid }
    }

    private fun flush(): Boolean {
        val snap: TrustedFile
        synchronized(lock) {
            if (!dirty) return true
            snap = TrustedFile(nets = book.nets, tombs = tombs)
            dirty = false
        }
        val ok = runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(TrustedFile.serializer(), snap))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.isSuccess
        if (!ok) synchronized(lock) { dirty = true }
        return ok
    }

    @Serializable
    private data class TrustedFile(
        val format: String = TrustedExportFile.FORMAT,
        val nets: List<TrustedNet> = emptyList(),
        val tombs: List<TwinTomb> = emptyList(),
    )

    companion object {
        private const val FLUSH_MS = 10_000L
    }
}
