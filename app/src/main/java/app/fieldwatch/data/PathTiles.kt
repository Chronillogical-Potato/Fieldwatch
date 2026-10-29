package app.fieldwatch.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import app.fieldwatch.domain.GpsSample
import app.fieldwatch.domain.MapBasemap
import app.fieldwatch.domain.MapKey
import app.fieldwatch.domain.MapKeyProblem
import app.fieldwatch.domain.MapOverlay
import app.fieldwatch.domain.MapTileUrl
import app.fieldwatch.domain.SitPathPlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Optional raster under Reports → Path, Show live path, and the letter path figure.
 * Same switch as Online place names and maps. Privacy mode does not hide tiles.
 * Saved pictures stay in the app's files until cleared or until they pass [MAX_CACHE_BYTES].
 * With no connection, a saved picture is still drawn. A missing picture is left blank, and nothing is downloaded.
 * Never throws. A failed fetch leaves that spot as a plain trace.
 */
object PathTiles {
    data class Tile(
        val bitmap: Bitmap,
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double,
        val basemap: MapBasemap,
        /** Overlays this zoom can draw. The corner credit names these. */
        val overlays: List<MapOverlay> = emptyList(),
    )

    /** Tiles plus why a keyed map produced none. OpenStreetMap, USGS, and FAA stay [MapKeyProblem.NONE]. */
    data class Batch(
        val tiles: List<Tile> = emptyList(),
        val problem: MapKeyProblem = MapKeyProblem.NONE,
        /** The view is coarser than this map allows, so nothing was requested. */
        val zoomIn: Boolean = false,
    )

    suspend fun load(
        context: Context,
        samples: List<GpsSample>,
        onlineLookup: Boolean,
        forPrint: Boolean = false,
        basemap: MapBasemap = MapBasemap.OSM,
        mapKey: String? = null,
        overlays: Set<MapOverlay> = emptySet(),
    ): Batch = withContext(Dispatchers.IO) {
        try {
            if (!onlineLookup) return@withContext Batch()
            if (samples.isEmpty()) return@withContext Batch()
            val expanded = expandToPlot(
                samples.minOf { it.lat },
                samples.maxOf { it.lat },
                samples.minOf { it.lon },
                samples.maxOf { it.lon },
            )
            loadBox(
                context,
                expanded[0],
                expanded[1],
                expanded[2],
                expanded[3],
                forPrint,
                basemap,
                mapKey,
                overlays,
                allowNetwork = PlaceLookup.online(context),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Batch(problem = if (basemap.needsKey) MapKeyProblem.FAILED else MapKeyProblem.NONE)
        }
    }

    /**
     * Tiles for the area on screen. [south]/[north]/[west]/[east] are the padded view.
     * Each decoded picture is passed to [onTile] before the batch returns.
     * Cancelling the caller stops the remaining fetches.
     */
    suspend fun loadBounds(
        context: Context,
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        onlineLookup: Boolean,
        basemap: MapBasemap = MapBasemap.OSM,
        mapKey: String? = null,
        overlays: Set<MapOverlay> = emptySet(),
        onTile: (suspend (Tile) -> Unit)? = null,
    ): Batch = withContext(Dispatchers.IO) {
        try {
            if (!onlineLookup) return@withContext Batch()
            loadBox(
                context,
                south,
                north,
                west,
                east,
                basemap = basemap,
                mapKey = mapKey,
                overlays = overlays,
                onTile = onTile,
                allowNetwork = PlaceLookup.online(context),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Batch(problem = if (basemap.needsKey) MapKeyProblem.FAILED else MapKeyProblem.NONE)
        }
    }

    /**
     * Save the pictures for a window the phone has not reached yet.
     * Nothing is returned for the screen. A tile already on disk is left alone.
     */
    suspend fun prefetch(
        context: Context,
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        onlineLookup: Boolean,
        basemap: MapBasemap = MapBasemap.OSM,
        mapKey: String? = null,
        overlays: Set<MapOverlay> = emptySet(),
    ) {
        if (!onlineLookup) return
        withContext(Dispatchers.IO) {
            if (!PlaceLookup.online(context)) return@withContext
            if (blockedKey(basemap, mapKey) != null) return@withContext
            val plan = when (val planned = planFor(south, north, west, east, basemap = basemap)) {
                is Plan.Ready -> planned.plan
                else -> return@withContext
            }
            val layers = MapOverlay.ordered(overlays)
            for ((x, y) in plan.tiles) {
                ensureActive()
                runCatching { cacheTile(context, basemap.cacheName, basemap, plan.z, x, y, mapKey) }
                for (overlay in layers) {
                    ensureActive()
                    val source = overlaySource(
                        plan.z, x, y, overlay.basemap.maxZoom, overlay.basemap.minZoom,
                    ) ?: continue
                    runCatching {
                        cacheTile(
                            context,
                            overlay.basemap.cacheName,
                            overlay.basemap,
                            source.z,
                            source.x,
                            source.y,
                            null,
                        )
                    }
                }
            }
            runCatching { trimTileFiles(File(context.filesDir, TILE_ROOT)) }
        }
    }

    /** Download a missing tile and drop the bitmap. The file is what the next view reads. */
    private fun cacheTile(
        context: Context,
        cacheName: String,
        basemap: MapBasemap,
        z: Int,
        x: Int,
        y: Int,
        mapKey: String?,
    ) {
        val file = tileFile(context, cacheName, z, x, y)
        if (file.isFile && file.length() > 64) return
        val fetched = tileBitmap(context, cacheName, basemap, z, x, y, mapKey)
        if (fetched is Fetch.Picture) fetched.bitmap.recycle()
    }

    /**
     * Tiles of one zoom have the same width in longitude. The next zoom is twice as wide.
     * A tiny difference is the same picture.
     */
    internal fun sameTileZoom(spanA: Double, spanB: Double): Boolean {
        if (spanA <= 0.0 || spanB <= 0.0 || !spanA.isFinite() || !spanB.isFinite()) return false
        val scale = maxOf(spanA, spanB)
        return abs(spanA - spanB) <= scale * 0.01
    }

    /**
     * A picture already on screen stays when it is the zoom being loaded, or the zoom
     * just before that, inside the pad. A closer picture that sits inside a wider new
     * one is dropped. Anything else is dropped. The caller does not recycle the bitmap.
     */
    internal fun keepShownPicture(
        oldWest: Double,
        oldEast: Double,
        frontSpan: Double,
        underlaySpan: Double,
        inPad: Boolean,
        replaced: Boolean,
        coveredByWider: Boolean,
    ): Boolean {
        if (!inPad || replaced) return false
        val span = oldEast - oldWest
        if (sameTileZoom(span, frontSpan)) return true
        if (!sameTileZoom(span, underlaySpan)) return false
        return !(span < frontSpan && coveredByWider)
    }

    /** Same tile set means the same key. A move that leaves those tiles, or another map, changes it. */
    internal fun coverageKey(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        basemap: MapBasemap = MapBasemap.OSM,
    ): String {
        return when (val planned = planFor(south, north, west, east, basemap = basemap)) {
            Plan.None -> "off"
            Plan.ZoomIn -> "zoom"
            is Plan.Ready -> "${basemap.name}:${planned.plan.key}"
        }
    }

    private suspend fun loadBox(
        context: Context,
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        forPrint: Boolean = false,
        basemap: MapBasemap = MapBasemap.OSM,
        mapKey: String? = null,
        overlays: Set<MapOverlay> = emptySet(),
        onTile: (suspend (Tile) -> Unit)? = null,
        allowNetwork: Boolean = true,
    ): Batch {
        val blocked = blockedKey(basemap, mapKey)
        if (blocked != null) return Batch(problem = blocked)
        val plan = when (val planned = planFor(south, north, west, east, forPrint, basemap)) {
            Plan.None -> return Batch()
            Plan.ZoomIn -> return Batch(zoomIn = true)
            is Plan.Ready -> planned.plan
        }
        val active = MapOverlay.ordered(overlays).filter { overlay ->
            overlaySource(plan.z, 0, 0, overlay.basemap.maxZoom, overlay.basemap.minZoom) != null
        }
        val gate = Semaphore(4)
        val built = coroutineScope {
            plan.tiles.map { (x, y) ->
                async(Dispatchers.IO) {
                    val one = gate.withPermit {
                        ensureActive()
                        oneTile(context, basemap, mapKey, plan.z, x, y, active, allowNetwork)
                    }
                    ensureActive()
                    if (one is Built.Ready) onTile?.invoke(one.tile)
                    one
                }
            }.awaitAll()
        }
        val out = ArrayList<Tile>(built.size)
        var rejected = 0
        var missed = 0
        for (one in built) {
            when (one) {
                is Built.Ready -> out += one.tile
                Built.Rejected -> rejected++
                Built.Missed -> missed++
            }
        }
        val problem = when {
            !basemap.needsKey -> MapKeyProblem.NONE
            out.isEmpty() && rejected > 0 -> MapKeyProblem.REJECTED
            out.isEmpty() && missed > 0 -> MapKeyProblem.FAILED
            else -> MapKeyProblem.NONE
        }
        trimTileFiles(File(context.filesDir, TILE_ROOT))
        return Batch(out, problem)
    }

    private sealed class Built {
        data class Ready(val tile: Tile) : Built()
        data object Rejected : Built()
        data object Missed : Built()
    }

    /** One picture for the screen. [Built.Missed] is a gap. [Built.Rejected] is a refused key. */
    private fun oneTile(
        context: Context,
        basemap: MapBasemap,
        mapKey: String?,
        z: Int,
        x: Int,
        y: Int,
        active: List<MapOverlay>,
        allowNetwork: Boolean,
    ): Built {
        return when (val fetch = tileBitmap(context, basemap.cacheName, basemap, z, x, y, mapKey, allowNetwork)) {
            is Fetch.Picture -> {
                val layers = ArrayList<Pair<Bitmap, MapOverlay>>(active.size)
                for (overlay in active) {
                    val source = overlaySource(
                        z, x, y, overlay.basemap.maxZoom, overlay.basemap.minZoom,
                    ) ?: continue
                    val over = tileBitmap(
                        context,
                        overlay.basemap.cacheName,
                        overlay.basemap,
                        source.z,
                        source.x,
                        source.y,
                        null,
                        allowNetwork,
                    )
                    if (over is Fetch.Picture) {
                        layers += cropOverlay(over.bitmap, source) to overlay
                    }
                }
                Built.Ready(
                    Tile(
                        bitmap = if (layers.isEmpty()) fetch.bitmap else composite(fetch.bitmap, layers),
                        north = tileLat(y, z),
                        south = tileLat(y + 1, z),
                        west = tileLon(x, z),
                        east = tileLon(x + 1, z),
                        basemap = basemap,
                        overlays = active,
                    ),
                )
            }
            Fetch.Rejected -> Built.Rejected
            Fetch.Failed -> Built.Missed
        }
    }

    /** A keyed map with no usable token does not open a connection. */
    private fun blockedKey(basemap: MapBasemap, mapKey: String?): MapKeyProblem? {
        if (!basemap.needsKey) return null
        return when (MapKey.verdict(mapKey.orEmpty())) {
            MapKey.Verdict.OK -> null
            MapKey.Verdict.EMPTY, MapKey.Verdict.SHORT -> MapKeyProblem.MISSING
            MapKey.Verdict.DENIED -> MapKeyProblem.DENIED
        }
    }

    private data class TilePlan(val z: Int, val tiles: List<Pair<Int, Int>>, val key: String)

    /** Zoom the PDF actually uses after the print cap. Screen callers leave [forPrint] false. */
    internal fun plannedZoom(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        forPrint: Boolean = false,
        basemap: MapBasemap = MapBasemap.OSM,
    ): Int = when (val planned = planFor(south, north, west, east, forPrint, basemap)) {
        is Plan.Ready -> planned.plan.z
        else -> -1
    }

    private sealed class Plan {
        data class Ready(val plan: TilePlan) : Plan()
        data object ZoomIn : Plan()
        data object None : Plan()
    }

    private fun planFor(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        forPrint: Boolean = false,
        basemap: MapBasemap = MapBasemap.OSM,
    ): Plan {
        if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) return Plan.None
        if (north <= south || east <= west) return Plan.None
        val midLat = (south + north) / 2.0
        val spanM = app.fieldwatch.domain.Geo.meters(south, west, north, east).coerceAtLeast(40.0)
        val cap = if (forPrint) PRINT_TILE_CAP else SCREEN_TILE_CAP
        val maxZ = minOf(if (forPrint) PRINT_MAX_Z else SCREEN_MAX_Z, basemap.maxZoom)
        val ideal = idealZoom(spanM, midLat) + if (forPrint) 2 else 0
        if (ideal < basemap.minZoom) return Plan.ZoomIn
        val floor = maxOf(8, basemap.minZoom)
        if (maxZ < floor) return Plan.ZoomIn
        var z = ideal.coerceIn(floor, maxZ)
        var tiles = tileBox(south, north, west, east, z)
        while (tiles.size > cap && z > floor) {
            z--
            tiles = tileBox(south, north, west, east, z)
        }
        if (tiles.isEmpty()) return Plan.None
        val x0 = tiles.minOf { it.first }
        val x1 = tiles.maxOf { it.first }
        val y0 = tiles.minOf { it.second }
        val y1 = tiles.maxOf { it.second }
        return Plan.Ready(TilePlan(z, tiles, "$z:$x0:$x1:$y0:$y1"))
    }

    /** Grow the path bbox to the Path plot aspect so tiles fill the allotted box, not a skinny strip. */
    internal fun expandToPlot(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        targetAspect: Double = 1.7,
    ): DoubleArray {
        val midLat = (minLat + maxLat) / 2.0
        val midLon = (minLon + maxLon) / 2.0
        val cos = cos(Math.toRadians(midLat)).absoluteValue.coerceAtLeast(0.2)
        val minM = (SitPathPlot.MIN_HALF_SPAN_M * 2f * SitPathPlot.FIT_PAD).toDouble()
        var yM = ((maxLat - minLat) * 110_540.0).coerceAtLeast(minM)
        var xM = ((maxLon - minLon) * 111_320.0 * cos).coerceAtLeast(minM)
        if (xM / yM < targetAspect) {
            xM = yM * targetAspect
        } else {
            yM = xM / targetAspect
        }
        xM *= 1.35
        yM *= 1.35
        val dLat = yM / 2.0 / 110_540.0
        val dLon = xM / 2.0 / (111_320.0 * cos)
        return doubleArrayOf(midLat - dLat, midLat + dLat, midLon - dLon, midLon + dLon)
    }

    private const val SCREEN_TILE_CAP = 20
    private const val PRINT_TILE_CAP = 96
    private const val SCREEN_MAX_Z = 17
    private const val PRINT_MAX_Z = 19

    /** Zoom the span wants, including coarser than the tile floor of 8. */
    internal fun idealZoom(spanM: Double, lat: Double): Int {
        val earth = 40_075_016.686 * cos(Math.toRadians(lat)).absoluteValue.coerceAtLeast(0.2)
        for (z in 17 downTo 0) {
            val tileM = earth / (1 shl z)
            if (tileM * 2.2 >= spanM) return z
        }
        return 0
    }

    /** Screen zoom for a map with no minimum. Never below 8. */
    internal fun zoomFor(spanM: Double, lat: Double): Int = idealZoom(spanM, lat).coerceAtLeast(8)

    private fun tileBox(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, z: Int,
    ): List<Pair<Int, Int>> {
        val x0 = lon2tile(minLon, z)
        val x1 = lon2tile(maxLon, z)
        val y0 = lat2tile(maxLat, z)
        val y1 = lat2tile(minLat, z)
        val n = 1 shl z
        val xs = (minOf(x0, x1).coerceIn(0, n - 1)..maxOf(x0, x1).coerceIn(0, n - 1))
        val ys = (minOf(y0, y1).coerceIn(0, n - 1)..maxOf(y0, y1).coerceIn(0, n - 1))
        val out = ArrayList<Pair<Int, Int>>()
        for (x in xs) for (y in ys) out += x to y
        return out
    }

    private fun lon2tile(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun lat2tile(lat: Double, z: Int): Int {
        val n = 1 shl z
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
    }

    private fun tileLon(x: Int, z: Int): Double {
        val n = 1 shl z
        return x.toDouble() / n * 360.0 - 180.0
    }

    private fun tileLat(y: Int, z: Int): Double {
        val n = 1 shl z
        val t = PI - 2.0 * PI * y / n
        return Math.toDegrees(atan(sinh(t)))
    }

    private sealed class Fetch {
        data class Picture(val bitmap: Bitmap) : Fetch()
        data object Rejected : Fetch()
        data object Failed : Fetch()
    }

    /**
     * Overlay tile for a view tile. Above the overlay's max zoom, use the parent tile
     * and [OverlaySource.col]/[OverlaySource.row] pick the piece.
     */
    internal data class OverlaySource(
        val z: Int,
        val x: Int,
        val y: Int,
        val scale: Int,
        val col: Int,
        val row: Int,
    )

    internal fun overlaySource(viewZ: Int, x: Int, y: Int, maxZ: Int, minZ: Int): OverlaySource? {
        if (viewZ < minZ || maxZ < minZ) return null
        val srcZ = viewZ.coerceAtMost(maxZ)
        val shift = viewZ - srcZ
        val scale = 1 shl shift
        return OverlaySource(
            z = srcZ,
            x = x shr shift,
            y = y shr shift,
            scale = scale,
            col = x and (scale - 1),
            row = y and (scale - 1),
        )
    }

    /** The matching piece of a coarser overlay tile, blown back up to the view tile. */
    private fun cropOverlay(bitmap: Bitmap, source: OverlaySource): Bitmap {
        if (source.scale == 1) return bitmap
        val w = bitmap.width / source.scale
        val h = bitmap.height / source.scale
        if (w < 1 || h < 1) return bitmap
        val crop = Bitmap.createBitmap(bitmap, source.col * w, source.row * h, w, h)
        val scaled = Bitmap.createScaledBitmap(crop, bitmap.width, bitmap.height, true)
        if (crop !== scaled) crop.recycle()
        return scaled
    }

    /** Relief darkens the map. Hydrography draws on top with its own transparency. */
    private fun composite(base: Bitmap, layers: List<Pair<Bitmap, MapOverlay>>): Bitmap {
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, null)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((bmp, overlay) in layers) {
            paint.xfermode = if (overlay.multiply) {
                PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            } else {
                null
            }
            val src = if (bmp.width == out.width && bmp.height == out.height) {
                bmp
            } else {
                Bitmap.createScaledBitmap(bmp, out.width, out.height, true)
            }
            canvas.drawBitmap(src, 0f, 0f, paint)
            if (src !== bmp) src.recycle()
        }
        return out
    }

    private fun tileBitmap(
        context: Context,
        cacheName: String,
        basemap: MapBasemap,
        z: Int,
        x: Int,
        y: Int,
        mapKey: String?,
        allowNetwork: Boolean = true,
    ): Fetch {
        val file = tileFile(context, cacheName, z, x, y)
        if (file.isFile && file.length() > 64) {
            val cached = BitmapFactory.decodeFile(file.absolutePath)
            if (cached != null) return Fetch.Picture(cached)
            file.delete()
        }
        if (!allowNetwork) return Fetch.Failed
        val address = MapTileUrl.build(basemap, z, x, y, mapKey) ?: return Fetch.Failed
        val token = if (basemap.needsKey) MapKey.accept(mapKey).orEmpty() else ""
        return fetchTile(address, token, basemap, file, hopsLeft = 2)
    }

    private fun fetchTile(
        address: String,
        token: String,
        basemap: MapBasemap,
        file: File,
        hopsLeft: Int,
    ): Fetch {
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            instanceFollowRedirects = token.isEmpty()
            setRequestProperty(
                "User-Agent",
                "Fieldwatch/${app.fieldwatch.BuildConfig.VERSION_NAME} (https://github.com/OffGridPete/Fieldwatch)",
            )
        }
        return try {
            val code = conn.responseCode
            if (token.isNotEmpty() && code in 300..399) {
                if (hopsLeft <= 0) return Fetch.Failed
                val loc = conn.getHeaderField("Location") ?: return Fetch.Failed
                val next = runCatching { URL(URL(address), loc).toString() }.getOrNull() ?: return Fetch.Failed
                if (!MapTileUrl.allowsRedirect(basemap, next, token)) return Fetch.Failed
                conn.disconnect()
                return fetchTile(next, token, basemap, file, hopsLeft - 1)
            }
            if (code == 401 || code == 403 || code == 498 || code == 499) {
                return if (token.isNotEmpty()) Fetch.Rejected else Fetch.Failed
            }
            if (code != 200) return Fetch.Failed
            if (conn.contentLengthLong > MAX_TILE_BYTES) return Fetch.Failed
            val bytes = conn.inputStream.use { stream ->
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                while (true) {
                    val n = stream.read(tmp)
                    if (n < 0) break
                    if (buf.size() + n > MAX_TILE_BYTES) return Fetch.Failed
                    buf.write(tmp, 0, n)
                }
                buf.toByteArray()
            }
            if (bytes.size < 64) return Fetch.Failed
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return Fetch.Failed
            runCatching { file.writeBytes(bytes) }
            Fetch.Picture(bitmap)
        } catch (_: Exception) {
            Fetch.Failed
        } finally {
            conn.disconnect()
        }
    }

    private const val MAX_TILE_BYTES = 2_000_000

    /** Saved pictures for every map and overlay. Android cache cleaning does not remove this folder. */
    private const val TILE_ROOT = "map-tiles"

    /** Oldest pictures are deleted after this. 512 MB. */
    internal const val MAX_CACHE_BYTES = 512L * 1024L * 1024L

    private val trimLock = Any()

    /** Deletes saved map pictures, including copies left in the old cache folder. */
    fun clearSaved(context: Context) {
        synchronized(trimLock) {
            File(context.filesDir, TILE_ROOT).deleteRecursively()
            val names = MapBasemap.entries.map { it.cacheName }.toSet()
            for (name in names) File(context.cacheDir, name).deleteRecursively()
        }
    }

    private fun tileFile(context: Context, cacheName: String, z: Int, x: Int, y: Int): File {
        val folder = File(context.filesDir, "$TILE_ROOT/$cacheName").apply { mkdirs() }
        val kept = File(folder, "${z}_${x}_$y.png")
        if (!kept.isFile || kept.length() <= 64L) {
            val old = File(File(context.cacheDir, cacheName), "${z}_${x}_$y.png")
            if (old.isFile && old.length() > 64L) {
                if (!old.renameTo(kept)) {
                    runCatching {
                        old.copyTo(kept, overwrite = true)
                        old.delete()
                    }
                }
            }
        }
        if (kept.isFile) kept.setLastModified(System.currentTimeMillis())
        return kept
    }

    /** Drops the oldest files until [root] is within [maxBytes]. Recently viewed files stay. */
    internal fun trimTileFiles(root: File, maxBytes: Long = MAX_CACHE_BYTES) {
        if (!root.isDirectory) return
        synchronized(trimLock) {
            val files = root.walkTopDown().filter { it.isFile }.toMutableList()
            var total = 0L
            for (file in files) total += file.length()
            if (total <= maxBytes) return
            files.sortBy { it.lastModified() }
            for (file in files) {
                if (total <= maxBytes) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }
}
