package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class PathCamerasTest {
    private val lat = 28.78
    private val lon = -81.37

    @Test
    fun aDirectionTowardThePathCountsAndOneThatPointsAwayDoesNot() {
        val path = listOf(
            GpsSample(1_000L, lat, lon),
            GpsSample(5_000L, north(lat, 80.0), lon),
        )
        val flock = camera(1L, north(lat, 40.0), east(lat, lon, 15.0), listOf(270), flock = true)
        val plain = camera(2L, north(lat, 40.0), east(lat, lon, -12.0), emptyList())
        val away = camera(3L, north(lat, 40.0), east(lat, lon, 12.0), listOf(0))
        val gun = camera(4L, lat, lon, emptyList(), kind = SensorKind.GUNSHOT)
        val far = camera(5L, north(lat, 400.0), lon, listOf(180), flock = true)
        val speed = camera(6L, north(lat, 40.0), east(lat, lon, -20.0), listOf(90), kind = SensorKind.SPEED)
        val cameras = listOf(flock, plain, away, gun, far, speed)
        val tally = PathCameras.tally(path, plates = true, others = true, near = nearby(cameras))
        val text = PathCameras.text(tally)
        assertEquals(
            "Within about 150 m\n" +
                "\n" +
                "Flock\n" +
                "  1 facing the path\n" +
                "  0 on the path with no direction\n" +
                "\n" +
                "Other plate readers\n" +
                "  0 facing the path\n" +
                "  1 on the path with no direction\n" +
                "\n" +
                "Speed cameras\n" +
                "  1 facing the path\n" +
                "  0 on the path with no direction",
            text,
        )
        assertEquals(listOf(1L, 2L, 6L), tally.cameras.map { it.key })
    }

    @Test
    fun aCameraBesideTheMiddleOfAStepStillCounts() {
        val path = listOf(
            GpsSample(1_000L, lat, lon),
            GpsSample(3_000L, north(lat, 40.0), lon),
        )
        val beside = camera(7L, north(lat, 20.0), east(lat, lon, 149.0), emptyList(), flock = true)
        val tally = PathCameras.tally(path, plates = true, others = false, near = nearby(listOf(beside)))
        assertEquals(1, tally.counts.single().near)
        assertEquals(listOf(7L), tally.cameras.map { it.key })
        val text = PathCameras.text(tally)
        assertTrue(text, text.contains("The other-camera list is not on this phone."))
        assertTrue(text, text.contains("Flock\n  0 facing the path\n  1 on the path with no direction"))
        assertFalse(text, text.contains("pointed toward"))
    }

    @Test
    fun noPathAndNoListsSaySo() {
        val empty = PathCameras.tally(emptyList(), plates = true, others = true) { _, _, _ -> emptyList() }
        assertTrue(empty.cameras.isEmpty())
        assertTrue(PathCameras.text(empty).contains("This sit has no path"))
        val missing = PathCameras.text(
            PathCameras.tally(listOf(GpsSample(1L, lat, lon)), plates = false, others = false) { _, _, _ ->
                emptyList()
            },
        )
        assertEquals("The camera lists are not on this phone.", missing)
    }

    @Test
    fun theSitReportLeavesTheSectionOffUnlessTheListsArePassed() {
        val now = 1_700_000_000_000L
        val plain = DebriefReport.document(
            devices = emptyList(),
            fleets = emptyList(),
            settings = layersOn(),
            operatorPath = listOf(GpsSample(now - 1_000L, lat, lon)),
            now = now,
            window = DebriefWindow(now - 60_000L, now, "drive"),
        )
        assertTrue(plain.sections.none { it.title == "Known cameras on path" })
        val withCameras = DebriefReport.document(
            devices = emptyList(),
            fleets = emptyList(),
            settings = layersOn(),
            operatorPath = listOf(GpsSample(now - 1_000L, lat, lon)),
            now = now,
            window = DebriefWindow(now - 60_000L, now, "drive"),
            cameras = PathCameras.Report(plates = true, others = true) { _, _, _ -> emptyList() },
        )
        val section = withCameras.sections.single { it.title == "Known cameras on path" }
        assertEquals("Where you were", withCameras.sections.map { it.title }[1])
        assertEquals("Known cameras on path", withCameras.sections.map { it.title }[2])
        assertTrue(section.body.contains("Within about 150 m"))
        assertTrue(section.body.contains("No listed camera was along this path."))
        assertFalse(section.body.contains("pointed toward"))
        assertEquals(null, withCameras.pathFigure)
    }

    @Test
    fun thePrintedPathKeepsOnlyCamerasAlongIt() {
        val now = 1_700_000_000_000L
        val path = listOf(
            GpsSample(now - 2_000L, lat, lon),
            GpsSample(now - 1_000L, north(lat, 80.0), lon),
        )
        val flock = camera(1L, north(lat, 40.0), east(lat, lon, 15.0), listOf(270), flock = true)
        val away = camera(3L, north(lat, 40.0), east(lat, lon, 12.0), listOf(0))
        val doc = DebriefReport.document(
            devices = emptyList(),
            fleets = emptyList(),
            settings = layersOn(),
            operatorPath = path,
            now = now,
            window = DebriefWindow(now - 60_000L, now, "drive"),
            cameras = PathCameras.Report(plates = true, others = true, near = nearby(listOf(flock, away))),
        )
        assertEquals(listOf(1L), doc.pathFigure?.cameras?.map { it.key })
    }

    @Test
    fun theSitReportLeavesOffAListWhoseSwitchIsOff() {
        val now = 1_700_000_000_000L
        val path = listOf(
            GpsSample(now - 2_000L, lat, lon),
            GpsSample(now - 1_000L, north(lat, 80.0), lon),
        )
        val flock = camera(1L, north(lat, 40.0), east(lat, lon, 15.0), listOf(270), flock = true)
        val speed = camera(6L, north(lat, 40.0), east(lat, lon, -20.0), listOf(90), kind = SensorKind.SPEED)
        val saved = PathCameras.Report(plates = true, others = true, near = nearby(listOf(flock, speed)))
        val hidden = DebriefReport.document(
            devices = emptyList(),
            fleets = emptyList(),
            settings = AppSettings(tagLocation = true),
            operatorPath = path,
            now = now,
            window = DebriefWindow(now - 60_000L, now, "drive"),
            cameras = saved,
        )
        assertTrue(hidden.sections.none { it.title == "Known cameras on path" })
        assertTrue(hidden.pathFigure?.cameras.orEmpty().isEmpty())
        val platesOnly = DebriefReport.document(
            devices = emptyList(),
            fleets = emptyList(),
            settings = AppSettings(tagLocation = true, flockLayer = true),
            operatorPath = path,
            now = now,
            window = DebriefWindow(now - 60_000L, now, "drive"),
            cameras = saved,
        )
        val section = platesOnly.sections.single { it.title == "Known cameras on path" }
        assertTrue(section.body.contains("Flock"))
        assertFalse(section.body.contains("Speed cameras"))
        assertFalse(section.body.contains("not on this phone"))
        assertEquals(listOf(1L), platesOnly.pathFigure?.cameras?.map { it.key })
        val missingPlates = DebriefReport.document(
            devices = emptyList(),
            fleets = emptyList(),
            settings = AppSettings(tagLocation = true, flockLayer = true),
            operatorPath = path,
            now = now,
            window = DebriefWindow(now - 60_000L, now, "drive"),
            cameras = PathCameras.Report(plates = false, others = true, near = nearby(listOf(speed))),
        )
        val named = missingPlates.sections.single { it.title == "Known cameras on path" }
        assertTrue(named.body.contains("The plate-reader list is not on this phone."))
        assertFalse(named.body.contains("The other-camera list is not on this phone."))
        assertFalse(named.body.contains("Speed cameras"))
        assertTrue(missingPlates.pathFigure?.cameras.orEmpty().isEmpty())
    }

    @Test
    fun thePathPageLeavesOffAListThatIsMissingOrSwitchedOff() {
        val flock = camera(1L, lat, lon, listOf(270), flock = true)
        val speed = camera(6L, lat, lon, listOf(90), kind = SensorKind.SPEED)
        val tally = PathCameras.Tally(
            plates = true,
            others = true,
            pathReady = true,
            counts = listOf(
                PathCameras.Count("ui.cameras.kind.flock", 1, 0),
                PathCameras.Count("ui.cameras.kind.speed", 1, 0),
            ),
            cameras = listOf(flock, speed),
        )
        assertEquals(null, PathCameras.shownOnPath(tally, platesOn = false, othersOn = false))
        val plates = PathCameras.shownOnPath(tally, platesOn = true, othersOn = false)
        assertEquals(listOf("ui.cameras.kind.flock"), plates?.counts?.map { it.labelKey })
        assertEquals(listOf(1L), plates?.cameras?.map { it.key })
        val noPlateFile = PathCameras.shownOnPath(tally.copy(plates = false), platesOn = true, othersOn = true)
        assertFalse(noPlateFile!!.plates)
        assertEquals(listOf("ui.cameras.kind.speed"), noPlateFile.counts.map { it.labelKey })
        assertEquals(listOf(6L), noPlateFile.cameras.map { it.key })
    }

    private fun layersOn() = AppSettings(tagLocation = true, flockLayer = true, sensorLayer = true)

    private fun nearby(cameras: List<FlockCamera>): (Double, Double, Double) -> List<FlockCamera> =
        { lat, lon, radius ->
            cameras.filter { Geo.meters(lat, lon, it.lat, it.lon) <= radius }
        }

    private fun camera(
        key: Long,
        lat: Double,
        lon: Double,
        facings: List<Int>,
        flock: Boolean = false,
        kind: SensorKind = SensorKind.PLATE,
    ) = FlockCamera(key, lat, lon, facings, flock = flock, kind = kind)

    private fun north(lat: Double, meters: Double) = lat + meters / 111_320.0

    private fun east(lat: Double, lon: Double, meters: Double): Double {
        val scale = 111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        return lon + meters / scale
    }
}
