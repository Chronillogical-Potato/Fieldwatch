package app.fieldwatch.data

import app.fieldwatch.domain.AppSettings
import app.fieldwatch.domain.MapBasemap
import app.fieldwatch.domain.MapOverlay
import app.fieldwatch.domain.withOverlayBasemaps
import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTilesTest {
    @Test
    fun oldestTilesAreDroppedWhenTheCacheIsFull() {
        val root = File(System.getProperty("java.io.tmpdir"), "fw-tiles-" + System.nanoTime())
        root.mkdirs()
        try {
            val older = File(root, "osm/old.png")
            val newer = File(root, "usgs/new.png")
            older.parentFile!!.mkdirs()
            newer.parentFile!!.mkdirs()
            older.writeBytes(ByteArray(1000))
            newer.writeBytes(ByteArray(1000))
            older.setLastModified(1_000L)
            newer.setLastModified(5_000L)
            PathTiles.trimTileFiles(root, maxBytes = 1000L)
            assertFalse(older.exists())
            assertTrue(newer.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aCloserPictureInsideAWiderOneIsDropped() {
        val wide = 360.0 / (1 shl 10)
        val close = 360.0 / (1 shl 14)
        assertTrue(PathTiles.sameTileZoom(close, close))
        assertFalse(PathTiles.sameTileZoom(close, wide))
        assertTrue(
            PathTiles.keepShownPicture(
                oldWest = -81.0, oldEast = -81.0 + close,
                frontSpan = close, underlaySpan = Double.NaN,
                inPad = true, replaced = false, coveredByWider = false,
            ),
        )
        assertFalse(
            PathTiles.keepShownPicture(
                oldWest = -81.0, oldEast = -81.0 + close,
                frontSpan = wide, underlaySpan = close,
                inPad = true, replaced = false, coveredByWider = true,
            ),
        )
        assertTrue(
            PathTiles.keepShownPicture(
                oldWest = -81.0, oldEast = -81.0 + wide,
                frontSpan = close, underlaySpan = wide,
                inPad = true, replaced = false, coveredByWider = true,
            ),
        )
        assertFalse(
            PathTiles.keepShownPicture(
                oldWest = -81.0, oldEast = -81.0 + close,
                frontSpan = wide, underlaySpan = wide / 2.0,
                inPad = true, replaced = false, coveredByWider = false,
            ),
        )
        assertFalse(
            PathTiles.keepShownPicture(
                oldWest = -81.0, oldEast = -81.0 + close,
                frontSpan = close, underlaySpan = Double.NaN,
                inPad = false, replaced = false, coveredByWider = false,
            ),
        )
    }

    @Test
    fun zoomTightensForAShortSpanAndOpensForADrive() {
        assertTrue(PathTiles.zoomFor(80.0, 28.5) >= 15)
        assertTrue(PathTiles.zoomFor(12_000.0, 28.5) in 8..14)
    }

    @Test
    fun expandToPlotFillsAWidePlotForATallPath() {
        val box = PathTiles.expandToPlot(28.780, 28.790, -81.370, -81.3695)
        val latSpan = box[1] - box[0]
        val lonSpan = box[3] - box[2]
        val yM = latSpan * 110_540.0
        val xM = lonSpan * 111_320.0 * kotlin.math.cos(Math.toRadians(28.785))
        assertTrue(xM / yM in 1.5..1.9)
    }

    @Test
    fun coverageKeyChangesWhenTheViewLeavesItsTiles() {
        val here = PathTiles.coverageKey(28.78, 28.79, -81.38, -81.37)
        val away = PathTiles.coverageKey(29.10, 29.11, -81.10, -81.09)
        assertNotEquals("off", here)
        assertNotEquals(here, away)
        assertEquals(here, PathTiles.coverageKey(28.78, 28.79, -81.38, -81.37))
    }

    @Test
    fun aPrintedWalkUsesTheNextZoom() {
        val south = 28.780
        val north = 28.784
        val west = -81.370
        val east = -81.366
        val screen = PathTiles.plannedZoom(south, north, west, east)
        val print = PathTiles.plannedZoom(south, north, west, east, forPrint = true)
        assertEquals((screen + 2).coerceAtMost(19), print)
        assertTrue(print <= 19)
    }

    @Test
    fun coverageKeyNamesTheMap() {
        val osm = PathTiles.coverageKey(28.78, 28.79, -81.38, -81.37, MapBasemap.OSM)
        val imagery = PathTiles.coverageKey(28.78, 28.79, -81.38, -81.37, MapBasemap.USGS_IMAGERY)
        assertTrue(osm.startsWith("OSM:"))
        assertTrue(imagery.startsWith("USGS_IMAGERY:"))
        assertNotEquals(osm, imagery)
    }

    @Test
    fun usgsPrintStopsAtTheLastRealZoom() {
        val south = 28.780
        val north = 28.784
        val west = -81.370
        val east = -81.366
        val imagery = PathTiles.plannedZoom(
            south, north, west, east, forPrint = true, basemap = MapBasemap.USGS_IMAGERY,
        )
        val topo = PathTiles.plannedZoom(
            south, north, west, east, forPrint = true, basemap = MapBasemap.USGS_TOPO,
        )
        assertTrue(imagery in 8..16)
        assertTrue(topo in 8..16)
    }

    @Test
    fun tileUrlsUseEachServicesOrder() {
        assertEquals(
            "https://tile.openstreetmap.org/12/1140/1536.png",
            MapBasemap.OSM.tileUrl(12, 1140, 1536),
        )
        assertEquals(
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/12/1536/1140",
            MapBasemap.USGS_IMAGERY.tileUrl(12, 1140, 1536),
        )
        assertEquals(
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/12/1536/1140",
            MapBasemap.USGS_TOPO.tileUrl(12, 1140, 1536),
        )
        assertEquals(
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/12/1536/1140",
            MapBasemap.USGS_IMAGERY_TOPO.tileUrl(12, 1140, 1536),
        )
        assertEquals(
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSShadedReliefOnly/MapServer/tile/12/1536/1140",
            MapBasemap.USGS_SHADED_RELIEF.tileUrl(12, 1140, 1536),
        )
        assertEquals(
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSHydroCached/MapServer/tile/12/1536/1140",
            MapBasemap.USGS_HYDRO.tileUrl(12, 1140, 1536),
        )
        assertEquals(
            "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer/tile/12/1536/1140",
            MapBasemap.FAA_VFR_SECTIONAL.tileUrl(12, 1140, 1536),
        )
    }

    @Test
    fun faaStaysInsideItsZoomAndAWideViewDoesNotAsk() {
        val wideSouth = 24.0
        val wideNorth = 49.0
        val wideWest = -125.0
        val wideEast = -66.0
        assertEquals(
            -1,
            PathTiles.plannedZoom(wideSouth, wideNorth, wideWest, wideEast, basemap = MapBasemap.FAA_VFR_SECTIONAL),
        )
        assertEquals(
            -1,
            PathTiles.plannedZoom(
                wideSouth, wideNorth, wideWest, wideEast,
                forPrint = true,
                basemap = MapBasemap.FAA_VFR_SECTIONAL,
            ),
        )
        assertEquals("zoom", PathTiles.coverageKey(wideSouth, wideNorth, wideWest, wideEast, MapBasemap.FAA_VFR_SECTIONAL))
        assertTrue(PathTiles.plannedZoom(wideSouth, wideNorth, wideWest, wideEast) >= 8)
        assertTrue(PathTiles.idealZoom(5_000_000.0, 38.0) < 8)
        assertEquals(8, PathTiles.zoomFor(5_000_000.0, 38.0))

        val south = 38.89
        val north = 38.91
        val west = -77.04
        val east = -77.02
        val screen = PathTiles.plannedZoom(south, north, west, east, basemap = MapBasemap.FAA_VFR_SECTIONAL)
        val print = PathTiles.plannedZoom(
            south, north, west, east, forPrint = true, basemap = MapBasemap.FAA_VFR_SECTIONAL,
        )
        assertTrue(screen in 8..12)
        assertTrue(print in 8..12)
        val close = PathTiles.plannedZoom(38.900, 38.901, -77.040, -77.039, basemap = MapBasemap.FAA_VFR_SECTIONAL)
        assertTrue(close in 8..12)
    }

    @Test
    fun newUsMapsNameTheirLimits() {
        assertTrue(MapBasemap.USGS_IMAGERY.usOnly)
        assertTrue(MapBasemap.USGS_TOPO.usOnly)
        assertTrue(MapBasemap.USGS_IMAGERY_TOPO.usOnly)
        assertTrue(MapBasemap.USGS_SHADED_RELIEF.usOnly)
        assertTrue(MapBasemap.USGS_HYDRO.usOnly)
        assertTrue(MapBasemap.FAA_VFR_SECTIONAL.usOnly)
        assertTrue(!MapBasemap.OSM.usOnly)
        assertEquals(0, MapBasemap.USGS_HYDRO.minZoom)
        assertEquals(16, MapBasemap.USGS_HYDRO.maxZoom)
        assertEquals(8, MapBasemap.FAA_VFR_SECTIONAL.minZoom)
        assertEquals(12, MapBasemap.FAA_VFR_SECTIONAL.maxZoom)
        assertEquals(
            "U.S. Geological Survey (NHD)",
            MapBasemap.USGS_HYDRO.creditPrint,
        )
        assertEquals(
            "U.S. Geological Survey (3DEP)",
            MapBasemap.USGS_SHADED_RELIEF.credit,
        )
        assertTrue(MapBasemap.FAA_VFR_SECTIONAL.creditPrint.contains("Not for navigation."))
        assertEquals(
            "Federal Aviation Administration. Not for navigation.",
            MapBasemap.FAA_VFR_SECTIONAL.screenCredit("Not for navigation."),
        )
        assertEquals(
            "U.S. Geological Survey",
            MapBasemap.USGS_TOPO.screenCredit("Not for navigation."),
        )
        assertTrue(!MapBasemap.FAA_VFR_SECTIONAL.needsKey)
        assertTrue(!MapBasemap.USGS_IMAGERY_TOPO.needsKey)
        assertFalse(MapBasemap.USGS_SHADED_RELIEF.inPicker)
        assertFalse(MapBasemap.USGS_HYDRO.inPicker)
        assertTrue(MapBasemap.USGS_IMAGERY_TOPO.inPicker)
    }

    @Test
    fun reliefAndHydroSitOnTheChosenMap() {
        assertEquals(
            listOf(MapOverlay.USGS_SHADED_RELIEF, MapOverlay.USGS_HYDRO),
            MapOverlay.ordered(setOf(MapOverlay.USGS_HYDRO, MapOverlay.USGS_SHADED_RELIEF)),
        )
        assertEquals("USGS_SHADED_RELIEF,USGS_HYDRO", MapOverlay.key(MapOverlay.entries))
        assertEquals("none", MapOverlay.key(emptySet()))
        val line = MapBasemap.OSM.creditLine(MapOverlay.entries)
        assertTrue(line.contains("U.S. Geological Survey (3DEP)"))
        assertTrue(line.contains("U.S. Geological Survey (NHD)"))
        assertEquals(
            "Federal Aviation Administration. Not for navigation.",
            MapBasemap.FAA_VFR_SECTIONAL.screenCredit("Not for navigation.", listOf(MapOverlay.USGS_HYDRO))
                .substringBefore(" · "),
        )
        assertEquals(
            listOf(
                "© OpenStreetMap contributors",
                "U.S. Geological Survey (3DEP)",
                "U.S. Geological Survey (NHD)",
            ),
            MapBasemap.OSM.screenCreditLines(null, MapOverlay.entries),
        )
        assertEquals(
            listOf("© OpenStreetMap contributors"),
            MapBasemap.OSM.screenCreditLines(null, emptyList()),
        )
        val moved = AppSettings(mapBasemap = MapBasemap.USGS_SHADED_RELIEF).withOverlayBasemaps()
        assertEquals(MapBasemap.OSM, moved.mapBasemap)
        assertEquals(setOf(MapOverlay.USGS_SHADED_RELIEF), moved.mapOverlays)
        val kept = AppSettings(
            mapBasemap = MapBasemap.USGS_TOPO,
            mapOverlays = setOf(MapOverlay.USGS_HYDRO),
        ).withOverlayBasemaps()
        assertEquals(MapBasemap.USGS_TOPO, kept.mapBasemap)
        assertEquals(setOf(MapOverlay.USGS_HYDRO), kept.mapOverlays)
        val close = PathTiles.overlaySource(17, 2281, 3073, 16, 0)
        assertEquals(16, close!!.z)
        assertEquals(1140, close.x)
        assertEquals(1536, close.y)
        assertEquals(2, close.scale)
        assertEquals(1, close.col)
        assertEquals(1, close.row)
        val same = PathTiles.overlaySource(12, 1140, 1536, 16, 0)
        assertEquals(12, same!!.z)
        assertEquals(1140, same.x)
        assertEquals(1, same.scale)
        assertNull(PathTiles.overlaySource(7, 10, 10, 12, 8))
    }

    @Test
    fun aSingleFixStillRequestsANeighborhoodOfTiles() {
        val box = PathTiles.expandToPlot(28.780, 28.780, -81.370, -81.370)
        val yM = (box[1] - box[0]) * 110_540.0
        assertTrue(yM >= 400.0)
    }
}
