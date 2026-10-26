package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.fieldwatch.domain.Geo
import app.fieldwatch.domain.GpsSample
import kotlin.math.cos
import kotlin.math.hypot

class HuntTest {
    private val now = 100_000L

    @Test
    fun aWeakerPassDoesNotWashOutAHotCell() {
        val hot = HuntHeat.mixWeight(HuntHeat.shadeWeight(0.0), 1f)
        val weak = HuntHeat.mixWeight(HuntHeat.shadeWeight(1.5), 0.35f)
        var weight = hot
        var sum = hot * 1f
        repeat(8) {
            weight += weak
            sum += weak * 0.35f
        }
        assertTrue("washed to ${sum / weight}", sum / weight > 0.85f)
    }

    @Test
    fun neighboringColorsBlendInsteadOfAHardEdge() {
        val here = HuntHeat.mixWeight(HuntHeat.shadeWeight(0.0), 0.70f)
        val beside = HuntHeat.mixWeight(HuntHeat.shadeWeight(0.6), 0.40f)
        val mixed = (here * 0.70f + beside * 0.40f) / (here + beside)
        assertTrue("hard edge at $mixed", mixed < 0.70f - 0.02f)
        assertTrue("lost the local shade at $mixed", mixed > 0.40f + 0.05f)
    }

    @Test
    fun aNearlyGoneSpotStaysBlueBesideALouderPass() {
        val local = HuntHeat.mixWeight(HuntHeat.shadeWeight(0.0), 0.07f)
        val far = HuntHeat.mixWeight(HuntHeat.shadeWeight(HuntHeat.SHADE_M), 1f)
        assertEquals(0f, far, 0.001f)
        val mixed = local * 0.07f / local
        assertTrue("weak spot painted warm at $mixed", mixed < 0.12f)
    }

    @Test
    fun veryCloseBeatsCloserWhenScreamingLoud() {
        val samples = listOf(
            RssiSample(now - 6_000, -70),
            RssiSample(now - 5_000, -68),
            RssiSample(now - 1_200, -38),
            RssiSample(now - 400, -36),
        )
        assertEquals(HuntCue.VERY_CLOSE, Hunt.cue(samples, now, now - 400, missing = false))
    }

    @Test
    fun belowVeryCloseStillUsesRelativeCue() {
        val samples = listOf(
            RssiSample(now - 6_000, -72),
            RssiSample(now - 5_000, -70),
            RssiSample(now - 1_200, -60),
            RssiSample(now - 400, -58),
        )
        assertEquals(HuntCue.CLOSER, Hunt.cue(samples, now, now - 400, missing = false))
    }

    @Test
    fun quietWinsOverALoudLastPacket() {
        val samples = listOf(RssiSample(now - 9_000, -30))
        assertEquals(HuntCue.QUIET, Hunt.cue(samples, now, now - 9_000, missing = false))
    }

    @Test
    fun goneWins() {
        assertEquals(HuntCue.GONE, Hunt.cue(emptyList(), now, now, missing = true))
    }

    @Test
    fun tickSilentWhenQuietGoneOrNoRssi() {
        assertEquals(null, Hunt.tickIntervalMs(-40, HuntCue.QUIET))
        assertEquals(null, Hunt.tickIntervalMs(-40, HuntCue.GONE))
        assertEquals(null, Hunt.tickIntervalMs(null, HuntCue.CLOSER))
    }

    @Test
    fun tickFasterWhenLouder() {
        val slow = Hunt.tickIntervalMs(-90, HuntCue.SAME)!!
        val mid = Hunt.tickIntervalMs(-65, HuntCue.CLOSER)!!
        val fast = Hunt.tickIntervalMs(-40, HuntCue.VERY_CLOSE)!!
        assertEquals(Hunt.TICK_SLOW_MS, slow)
        assertEquals(Hunt.TICK_FAST_MS, fast)
        assertTrue(mid in (fast + 1) until slow)
        val veryClose = Hunt.tickIntervalMs(-45, HuntCue.VERY_CLOSE)!!
        assertTrue(veryClose < mid)
        assertTrue(veryClose > fast)
    }

    @Test
    fun heatRampRunsFromPoorToPowerful() {
        assertEquals(null, HuntHeat.level(127))
        assertEquals(0f, HuntHeat.level(HuntHeat.QUIET_DBM)!!, 0.001f)
        assertEquals(0f, HuntHeat.level(-120)!!, 0.001f)
        assertTrue("−90 should stay blue, was ${HuntHeat.level(-90)}", HuntHeat.level(-90)!! < 0.12f)
        assertEquals(1f, HuntHeat.level(HuntHeat.POWER_DBM)!!, 0.001f)
        assertEquals(1f, HuntHeat.level(-30)!!, 0.001f)
        assertEquals(1f, HuntHeat.level(-23)!!, 0.001f)
        val steps = intArrayOf(-80, -70, -60, -50)
        var prev = HuntHeat.level(-90)!!
        for (rssi in steps) {
            val next = HuntHeat.level(rssi)!!
            assertTrue("$rssi should be a fuller shade ($prev → $next)", next > prev + 0.08f)
            prev = next
        }
        val mid = HuntHeat.level(-75)!!
        assertTrue(mid > HuntHeat.level(-80)!!)
        assertTrue(mid < HuntHeat.level(-70)!!)
        assertEquals(1f, HuntHeat.shadeWeight(0.0), 0.001f)
        assertEquals(0f, HuntHeat.shadeWeight(HuntHeat.SHADE_M), 0.001f)
        val half = HuntHeat.shadeWeight(HuntHeat.SHADE_M / 2.0)
        assertTrue(half > 0.2f && half < 0.8f)
        assertEquals(0xFF1E4BFF.toInt(), HuntHeat.colorArgb(0f))
        assertEquals(0xFF00C8FF.toInt(), HuntHeat.colorArgb(0.2f))
        assertEquals(0xFF2DFF6A.toInt(), HuntHeat.colorArgb(0.4f))
        assertEquals(0xFFFFE033.toInt(), HuntHeat.colorArgb(0.6f))
        assertEquals(0xFFFF7A00.toInt(), HuntHeat.colorArgb(0.8f))
        assertEquals(0xFFFF1E1E.toInt(), HuntHeat.colorArgb(1f))
        val between = HuntHeat.colorArgb(0.1f)
        assertTrue(between != HuntHeat.colorArgb(0f))
        assertTrue(between != HuntHeat.colorArgb(0.2f))
    }

    @Test
    fun heatConnectsTheWalkAndChangesColorAlongIt() {
        assertTrue(HuntHeat.connects(HuntHeat.STEP_M))
        assertTrue(HuntHeat.connects(20.0))
        assertTrue(!HuntHeat.connects(HuntHeat.BRIDGE_M + 1.0))
        assertEquals(1f, HuntHeat.ribbonWeight(0.0), 0.001f)
        assertEquals(1f, HuntHeat.ribbonWeight(HuntHeat.COVER_M * 0.5), 0.001f)
        assertEquals(0f, HuntHeat.ribbonWeight(HuntHeat.RIBBON_M), 0.001f)
        assertTrue(HuntHeat.bridges(8.0))
        assertTrue(!HuntHeat.bridges(HuntHeat.CLOSE_M + 1.0))
        assertTrue(!HuntHeat.bridges(0.2))
        assertEquals(0.2f, HuntHeat.alongLevel(0.2f, 0.8f, 0.0), 0.001f)
        assertEquals(0.5f, HuntHeat.alongLevel(0.2f, 0.8f, 0.5), 0.001f)
        assertEquals(0.8f, HuntHeat.alongLevel(0.2f, 0.8f, 1.0), 0.001f)
    }

    @Test
    fun heatLeavesAPaintedSpotAndPaintsTheNextPlace() {
        val lat = 36.2361322
        val lon = -115.0820944
        val near = lat + 0.4 / 110_540.0
        assertTrue(Geo.meters(lat, lon, near, lon) < HuntHeat.STEP_M)
        var trail = HuntHeat.note(emptyList(), lat, lon, -70, 1_000L)
        trail = HuntHeat.note(trail, near, lon, -40, 2_000L)
        assertEquals(1, trail.size)
        assertEquals(-40, trail[0].rssi)
        assertEquals(lat, trail[0].lat, 1e-9)
        assertEquals(lon, trail[0].lon, 1e-9)
        trail = HuntHeat.note(trail, near, lon, -90, 3_000L)
        assertEquals(1, trail.size)
        assertEquals(-40, trail[0].rssi)
        val far = lat + 3.0 / 110_540.0
        trail = HuntHeat.note(trail, far, lon, -55, 4_000L)
        assertEquals(2, trail.size)
        assertEquals(-40, trail[0].rssi)
        assertEquals(lat, trail[0].lat, 1e-9)
        assertEquals(lon, trail[0].lon, 1e-9)
        assertEquals(-55, trail[1].rssi)
        val left = HuntHeat.level(trail[0].rssi)!!
        val right = HuntHeat.level(trail[1].rssi)!!
        val mid = HuntHeat.alongLevel(left, right, 0.5)
        val lo = minOf(left, right)
        val hi = maxOf(left, right)
        assertTrue(mid > lo && mid < hi)
        assertTrue(HuntHeat.colorArgb(mid) != HuntHeat.colorArgb(left))
        assertTrue(HuntHeat.colorArgb(mid) != HuntHeat.colorArgb(right))
        assertTrue(HuntHeat.note(trail, lat, lon, 127, 5_000L) === trail)
        assertTrue(HuntHeat.note(trail, 0.0, 0.0, -40, 6_000L) === trail)
    }

    @Test
    fun heatMarkSitsInTheHottestPatch() {
        val cols = 4
        val sumW = FloatArray(8) { 1f }
        val sumL = floatArrayOf(0.4f, 0.4f, 0.4f, 0.4f, 1f, 1f, 1f, 1f)
        val fade = FloatArray(8) { 1f }
        val spot = HuntHeat.hotSpot(sumW, sumL, fade, cols)
        assertNotNull(spot)
        assertEquals(2f, spot!!.first, 0.01f)
        assertEquals(1.5f, spot.second, 0.01f)
        fade[0] = 0.1f
        sumL[0] = 1f
        val still = HuntHeat.hotSpot(sumW, sumL, fade, cols)
        assertNotNull(still)
        assertEquals(2f, still!!.first, 0.01f)
        assertNull(HuntHeat.hotSpot(sumW, sumL, FloatArray(8) { 0.1f }, cols))
    }

    @Test
    fun heatStepsWhenThePhoneMovesAndDropsTheOldest() {
        val lat = 36.2361322
        val lon = -115.0820944
        val far = lat + 3.0 / 110_540.0
        assertTrue(Geo.meters(lat, lon, far, lon) >= HuntHeat.STEP_M)
        val two = HuntHeat.note(
            HuntHeat.note(emptyList(), lat, lon, -80, 1_000L),
            far, lon, -50, 2_000L,
        )
        assertEquals(2, two.size)
        assertEquals(-80, two[0].rssi)
        assertEquals(-50, two[1].rssi)
        assertEquals(-50, HuntHeat.loudest(two)!!.rssi)
        var trail = emptyList<GpsSample>()
        repeat(HuntHeat.CAP + 3) { i ->
            trail = HuntHeat.note(trail, lat + i * 20.0 / 110_540.0, lon, -70, i.toLong())
        }
        assertEquals(HuntHeat.CAP, trail.size)
        assertEquals(3L, trail.first().at)
    }

    @Test
    fun huntOpensCloseAndPinchStopsAtAFewMeters() {
        val lat = 36.2361322
        val lon = -115.0820944
        val model = SitPathPlot.Model(
            samples = listOf(GpsSample(1L, lat, lon, -60)),
            dots = emptyList(),
            lengthM = 0.0,
            spanM = 0.0,
            title = "",
            frameSamples = listOf(GpsSample(0L, lat, lon)),
            minHalfSpanM = HuntHeat.OPEN_HALF_M,
        )
        val lay = SitPathPlot.layout(model, 1080f, 1800f)!!
        val half = lay.metersPerPx * (lay.plotRight - lay.plotLeft) / 2f
        assertTrue(half > 10f)
        assertTrue(half < 22f)
        val tight = SitPathPlot.Viewport(lat, lon, 12f, 12f)
            .zoomAbout(4f, lat, lon, minHalfM = HuntHeat.PINCH_HALF_M)
        assertEquals(HuntHeat.PINCH_HALF_M, tight.halfEastM, 0.05f)
        assertEquals(HuntHeat.PINCH_HALF_M, tight.halfNorthM, 0.05f)
    }

    @Test
    fun aPlainWalkMovesBeforeGpsAndAShortWalkLandsOnTwoFixes() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        assertNull(track.position())
        assertTrue(!track.onGlobe())
        assertNull(track.gps(0.0, 0.0, 5f, 1_000L))
        track.heading(0.0, 1_000L)
        track.note(-60, 1_000L)
        repeat(4) { assertTrue(track.step(1_100L + it * 300L)) }
        assertTrue(!track.onGlobe())
        assertNull(track.position())
        assertEquals(HuntTrack.UNCERTAINTY_FLOOR_M, track.uncertaintyM(), 0.01)
        val shown = track.display()
        assertTrue(track.marks().isNotEmpty())
        track.gps(lat, lon, 40f, 3_000L, fresh = true)
        assertTrue(!track.onGlobe())
        assertNull(track.position())
        assertEquals(shown.first, track.display().first, 1e-9)
        assertEquals(shown.second, track.display().second, 1e-9)
        val apart = lat + 30.0 / 110_540.0
        track.gps(apart, lon, 12f, 4_000L, fresh = true)
        assertTrue(!track.onGlobe())
        track.gps(apart, lon, 12f, 5_000L, fresh = true)
        assertTrue(track.onGlobe())
        val here = track.position()!!
        assertTrue(Geo.meters(apart, lon, here.first, here.second) < 0.5)
        val first = track.marks().first()
        assertEquals(4 * HuntTrack.STRIDE_M, Geo.meters(here.first, here.second, first.lat, first.lon), 0.05)
        assertTrue(first.lat < here.first)
    }

    @Test
    fun aLongWalkStaysOffTheMapWhenTheFixArrivesLate() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.heading(0.0, 1_000L)
        val steps = (HuntTrack.RECENTER_M / HuntTrack.STRIDE_M).toInt() + 3
        repeat(steps) { assertTrue(track.step(1_100L + it * 300L)) }
        track.note(-55, 20_000L)
        val before = track.display()
        track.gps(lat, lon, 6f, 30_000L, fresh = true)
        track.gps(lat, lon, 6f, 31_000L, fresh = true)
        assertTrue(!track.onGlobe())
        assertNull(track.position())
        assertEquals(before.first, track.display().first, 1e-9)
        assertEquals(before.second, track.display().second, 1e-9)
        assertTrue(Geo.meters(lat, lon, track.display().first, track.display().second) > 1_000.0)
        track.gps(lat, lon, 5f, 40_000L, fresh = true)
        track.gps(lat, lon, 5f, 41_000L, fresh = true)
        assertTrue(!track.onGlobe())
        assertEquals(before.first, track.display().first, 1e-9)
    }

    @Test
    fun aResumedPlainWalkContinuesAndALongOneDoesNotJumpOntoAFix() {
        val lat = 36.2361322
        val lon = -115.0820944
        val short = HuntTrack()
        short.heading(0.0, 1_000L)
        repeat(6) { assertTrue(short.step(1_100L + it * 300L)) }
        short.note(-50, 4_000L)
        val saved = short.plainPicture()!!
        val resumed = HuntTrack()
        resumed.resumePlain(saved, short.marks())
        assertTrue(!resumed.onGlobe())
        assertEquals(short.display().first, resumed.display().first, 1e-9)
        assertEquals(short.display().second, resumed.display().second, 1e-9)
        resumed.gps(lat, lon, 5f, 5_000L, fresh = true)
        resumed.gps(lat, lon, 5f, 6_000L, fresh = true)
        assertTrue(resumed.onGlobe())
        assertTrue(Geo.meters(lat, lon, resumed.position()!!.first, resumed.position()!!.second) < 0.5)
        assertTrue(resumed.marks().isNotEmpty())

        val long = HuntTrack()
        long.heading(0.0, 1_000L)
        val steps = (HuntTrack.RECENTER_M / HuntTrack.STRIDE_M).toInt() + 3
        repeat(steps) { assertTrue(long.step(1_100L + it * 300L)) }
        val kept = long.plainPicture()!!
        val again = HuntTrack()
        again.resumePlain(kept, long.marks())
        val dot = again.display()
        again.gps(lat, lon, 5f, 30_000L, fresh = true)
        again.gps(lat, lon, 5f, 31_000L, fresh = true)
        assertTrue(!again.onGlobe())
        assertEquals(dot.first, again.display().first, 1e-9)
        assertEquals(dot.second, again.display().second, 1e-9)
    }

    @Test
    fun aCommittedFixPlacesTheWalkAndTheLocalPictureDoesNot() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        assertNull(track.gps(1.0, 1.0, 8f, 1_000L, fresh = true, commit = true))
        assertTrue(!track.onGlobe())
        track.gps(lat, lon, 8f, 2_000L, fresh = true, commit = true)
        assertTrue(track.onGlobe())
        assertEquals(lat, track.position()!!.first, 1e-6)
        assertEquals(lon, track.position()!!.second, 1e-6)
        assertNull(track.plainPicture())
    }

    @Test
    fun theFirstTwentyMetersSnapOnceAndALaterNudgeStaysInsideTheCircle() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.lock(lat, lon, 4_000L)
        track.heading(0.0, 4_100L)
        assertTrue(track.step(4_200L))
        val far = lat + 20.0 / 110_540.0
        track.gps(far, lon, 5f, 5_000L)
        assertEquals(far, track.position()!!.first, 1e-5)
        val held = track.position()!!
        track.gps(far + 25.0 / 110_540.0, lon, 5f, 6_000L)
        assertEquals(held.first, track.position()!!.first, 1e-7)
        track.gps(far + 2.0 / 110_540.0, lon, 15f, 7_000L)
        assertEquals(held.first, track.position()!!.first, 1e-7)
        val nearer = far + 2.0 / 110_540.0
        track.gps(nearer, lon, 5f, 8_000L)
        assertEquals((far + nearer) / 2.0, track.position()!!.first, 1e-5)
    }

    @Test
    fun huntStaysCloseOnTheDotWhenTheWalkIsLong() {
        val lat = 36.2361322
        val lon = -115.0820944
        val far = lat + 200.0 / 110_540.0
        val trail = listOf(
            GpsSample(1L, lat, lon, -80),
            GpsSample(2L, far, lon, -50),
        )
        val model = HuntHeat.followModel(lat, lon, trail)
        val lay = SitPathPlot.layout(model, 1080f, 1800f)!!
        val half = lay.metersPerPx * (lay.plotRight - lay.plotLeft) / 2f
        assertTrue(half > 10f)
        assertTrue(half < 22f)
        val dot = lay.project(lat, lon)
        val cx = (lay.plotLeft + lay.plotRight) / 2f
        val cy = (lay.plotTop + lay.plotBottom) / 2f
        assertTrue(hypot(dot.x - cx, dot.y - cy) < 2.0)
        val away = lay.project(far, lon)
        assertTrue(away.y < lay.plotTop || away.y > lay.plotBottom)
    }

    @Test
    fun leavingHuntKeepsTheSameRadio() {
        assertTrue(!HuntHeat.resumes(null, "a", fresh = false))
        assertTrue(HuntHeat.resumes("a", "a", fresh = false))
        assertTrue(!HuntHeat.resumes("a", "a", fresh = true))
        assertTrue(!HuntHeat.resumes("a", "b", fresh = false))
    }

    @Test
    fun aTurnInPlaceDoesNotMoveTheDot() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.lock(lat, lon, 1_000L)
        track.heading(0.0, 3_000L)
        assertTrue(track.step(3_000L))
        val after = track.position()!!
        track.heading(70.0, 3_400L)
        assertTrue(!track.step(3_400L))
        val held = track.position()!!
        assertEquals(after.first, held.first, 1e-9)
        assertEquals(after.second, held.second, 1e-9)
        track.heading(70.0, 3_800L)
        assertTrue(!track.step(3_800L))
        track.heading(70.0, 4_200L)
        assertTrue(track.step(4_200L))
        val walked = track.position()!!
        assertTrue(walked.first > after.first)
    }

    @Test
    fun stepsFollowThePhoneHeadingWhenGpsMoves() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        val cos0 = cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val eastLon = lon + 6.0 / (111_320.0 * cos0)
        track.lock(lat, lon, 10_000L)
        track.heading(0.0, 12_000L)
        track.gps(lat, eastLon, 5f, 12_200L)
        val snapped = track.position()!!
        assertEquals(eastLon, snapped.second, 1e-5)
        track.heading(0.0, 12_400L)
        assertTrue(track.step(12_400L))
        val moved = track.position()!!
        val northM = (moved.first - snapped.first) * 110_540.0
        val eastM = (moved.second - snapped.second) * 111_320.0 * cos0
        assertEquals(HuntTrack.STRIDE_M, northM, 0.05)
        assertEquals(0.0, eastM, 0.05)
    }

    @Test
    fun aShortWalkBeforeTheFixIsKeptAndALaterNudgeLeavesTheMarks() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.heading(90.0, 1_000L)
        track.note(-70, 1L)
        repeat(10) { assertTrue(track.step(1_200L + it * 300L)) }
        track.lock(lat, lon, 6_000L)
        val here = track.position()!!
        assertTrue(Geo.meters(lat, lon, here.first, here.second) < 0.5)
        val marks = track.marks()
        assertEquals(1, marks.size)
        assertEquals(10 * HuntTrack.STRIDE_M, Geo.meters(here.first, here.second, marks[0].lat, marks[0].lon), 0.05)
        assertTrue(marks[0].lon < here.second)
        val frozenLat = marks[0].lat
        val frozenLon = marks[0].lon
        track.gps(lat + 2.0 / 110_540.0, lon, 5f, 8_000L)
        val after = track.marks()
        assertEquals(frozenLat, after[0].lat, 1e-6)
        assertEquals(frozenLon, after[0].lon, 1e-6)
        assertTrue(kotlin.math.abs(track.position()!!.first - lat) > 1e-7)
    }

    @Test
    fun aLouderReadingWhereYouStandRaisesThatPlace() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.lock(lat, lon)
        track.heading(0.0)
        track.note(-70, 1L)
        val saved = track.marks().single()
        assertTrue(track.step(2L))
        track.note(-40, 3L)
        track.note(-90, 4L)
        val marks = track.marks()
        assertEquals(1, marks.size)
        assertEquals(-40, marks[0].rssi)
        assertEquals(saved.lat, marks[0].lat, 1e-9)
        assertEquals(saved.lon, marks[0].lon, 1e-9)
        assertTrue(track.raiseHere(-30, 5L))
        assertEquals(-30, track.marks().single().rssi)
        assertEquals(saved.lat, track.marks().single().lat, 1e-9)
        assertFalse(track.raiseHere(-55, 6L))
        assertEquals(-30, track.marks().single().rssi)
        assertFalse(track.raiseHere(-28, 7L))
        assertEquals(-30, track.marks().single().rssi)
    }

    @Test
    fun aWeakerReadingLeavesTheEarlierSpotAndPaintsTheNewOne() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.lock(lat, lon)
        track.heading(0.0)
        track.note(-70, 1L)
        val saved = track.marks().single()
        var at = 2L
        repeat(4) {
            assertTrue(track.step(at))
            track.note(-90, at)
            at += 1
        }
        val marks = track.marks()
        assertTrue(marks.size >= 2)
        assertEquals(-70, marks[0].rssi)
        assertEquals(saved.lat, marks[0].lat, 1e-9)
        assertEquals(saved.lon, marks[0].lon, 1e-9)
        assertEquals(-90, marks[1].rssi)
        val dot = track.position()!!
        assertTrue(Geo.meters(dot.first, dot.second, marks.last().lat, marks.last().lon) < HuntHeat.STEP_M)
        val gap = Geo.meters(marks[0].lat, marks[0].lon, marks[1].lat, marks[1].lon)
        assertTrue(gap >= HuntHeat.STEP_M)
        assertTrue(HuntHeat.connects(gap))
        val left = HuntHeat.level(marks[0].rssi)!!
        val right = HuntHeat.level(marks[1].rssi)!!
        val mid = HuntHeat.alongLevel(left, right, 0.5)
        val lo = minOf(left, right)
        val hi = maxOf(left, right)
        assertTrue(mid > lo && mid < hi)
        assertTrue(HuntHeat.colorArgb(mid) != HuntHeat.colorArgb(left))
        assertTrue(HuntHeat.colorArgb(mid) != HuntHeat.colorArgb(right))
    }

    @Test
    fun aWalkPaintsHeatBesideTheDotAndBlendsBetweenSamples() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.lock(lat, lon)
        track.heading(0.0)
        track.note(-88, 1L)
        var at = 2L
        repeat(12) { step ->
            assertTrue(track.step(at))
            track.note(-88 + step, at)
            at += 1
        }
        val here = track.position()!!
        val marks = track.marks()
        assertTrue(marks.size >= 5)
        val last = marks.last()
        assertTrue(Geo.meters(here.first, here.second, last.lat, last.lon) < HuntHeat.STEP_M)
        for (i in 1 until marks.size) {
            val gap = Geo.meters(marks[i - 1].lat, marks[i - 1].lon, marks[i].lat, marks[i].lon)
            assertTrue(HuntHeat.connects(gap))
            assertTrue(gap >= HuntHeat.STEP_M)
        }
        val weak = HuntHeat.level(marks.first().rssi)!!
        val strong = HuntHeat.level(marks.last().rssi)!!
        val mid = HuntHeat.alongLevel(weak, strong, 0.5)
        assertTrue(mid > weak && mid < strong)
        val painted = HuntHeat.colorArgb(mid)
        assertTrue(painted != HuntHeat.colorArgb(weak))
        assertTrue(painted != HuntHeat.colorArgb(strong))
        val kept = HuntHeat.shown(marks.take(2), marks.drop(2))
        assertEquals(marks.size, kept.size)
        assertEquals(marks.first().at, kept.first().at)
        assertEquals(marks.last().at, kept.last().at)
    }

    @Test
    fun aBadCompassFollowsTheGameYawAndDoesNotSnapBack() {
        val track = HuntTrack()
        track.heading(0.0, 0.0, true, 1_000L)
        assertEquals(0.0, track.steeredDeg(), 0.01)
        track.heading(80.0, 30.0, false, 1_200L)
        assertEquals(30.0, track.steeredDeg(), 0.01)
        track.heading(120.0, 30.0, true, 1_400L)
        assertEquals(30.0, track.steeredDeg(), 0.01)
        track.heading(40.0, 30.0, true, 1_600L)
        assertEquals(40.0, track.steeredDeg(), 0.01)
    }

    @Test
    fun anUntrustedCompassWithoutAnOffsetHoldsTheLastHeading() {
        val track = HuntTrack()
        track.heading(20.0, null, true, 1_000L)
        track.heading(90.0, null, false, 1_200L)
        assertEquals(20.0, track.steeredDeg(), 0.01)
    }

    @Test
    fun aTiltedPhonePausesStepsUntilItIsFlat() {
        val track = HuntTrack()
        track.lock(36.2, -115.08, 1_000L)
        track.heading(0.0, 0L)
        track.tilt(50.0, 1_000L)
        assertTrue(!track.holdingFlat())
        assertTrue(track.step(1_000L))
        track.tilt(50.0, 3_500L)
        assertTrue(track.holdingFlat())
        val held = track.position()!!
        assertTrue(!track.step(3_500L))
        assertEquals(held.first, track.position()!!.first, 1e-9)
        track.heading(10.0, 3_600L)
        track.tilt(10.0, 3_600L)
        assertTrue(!track.holdingFlat())
        assertTrue(track.step(3_600L))
    }

    @Test
    fun strideLearnsFromAStraightWalkAndStaysInRange() {
        val slow = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        slow.setStride(0.5)
        slow.lock(lat, lon)
        slow.heading(0.0)
        repeat(18) { assertTrue(slow.step()) }
        assertEquals(0.5, slow.gps(lat + 8.0 / 110_540.0, lon, 5f)!!, 0.001)

        val fast = HuntTrack()
        fast.lock(lat, lon)
        fast.heading(0.0)
        repeat(8) { assertTrue(fast.step()) }
        assertEquals(0.9, fast.gps(lat + 10.0 / 110_540.0, lon, 5f)!!, 0.001)
        repeat(12) { assertTrue(fast.step()) }
        // 12 steps at 0.9 m is 10.8 m walked. GPS moves 9.6 m, so the sample is 0.8.
        // A later learn blends: 0.7 of 0.9 plus 0.3 of 0.8.
        assertEquals(0.87, fast.gps(lat + 19.6 / 110_540.0, lon, 5f)!!, 0.01)
    }

    @Test
    fun uncertaintyStartsAtFourMetersAndGrowsWithTheWalk() {
        val track = HuntTrack()
        assertEquals(HuntTrack.UNCERTAINTY_FLOOR_M, track.uncertaintyM(), 0.001)
        track.lock(36.2, -115.08)
        track.heading(0.0)
        repeat(200) { assertTrue(track.step()) }
        assertEquals(0.03 * 200 * HuntTrack.STRIDE_M, track.uncertaintyM(), 0.02)
    }

    @Test
    fun aCompassJumpWhileThePhoneIsStillDoesNotDropTheStep() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.lock(lat, lon, 1_000L)
        track.heading(0.0, 10.0, true, 3_000L)
        assertTrue(track.step(3_000L))
        val after = track.position()!!
        track.heading(80.0, 12.0, true, 3_400L)
        assertEquals(2.0, track.steeredDeg(), 0.1)
        assertTrue(track.step(3_400L))
        val walked = track.position()!!
        assertTrue(walked.first > after.first)
        val east = (walked.second - after.second) * 111_320.0 * cos(Math.toRadians(lat))
        assertEquals(0.0, east, 0.08)
    }

    @Test
    fun aRealTurnDropsTheStep() {
        val track = HuntTrack()
        val lat = 36.2361322
        val lon = -115.0820944
        track.lock(lat, lon, 1_000L)
        track.heading(0.0, 0.0, true, 3_000L)
        assertTrue(track.step(3_000L))
        val after = track.position()!!
        track.heading(70.0, 70.0, true, 3_400L)
        assertTrue(!track.step(3_400L))
        assertEquals(after.first, track.position()!!.first, 1e-9)
        assertEquals(after.second, track.position()!!.second, 1e-9)
    }

    @Test
    fun aSilentStepSensorYieldsAndOneThatHasReportedKeepsTheWalk() {
        assertTrue(huntAccelStepCounts(false, false, false, 0L, 5_000L))
        assertTrue(!huntAccelStepCounts(true, false, false, 1_000L, 2_000L))
        assertTrue(huntAccelStepCounts(true, false, false, 1_000L, 1_000L + HuntTrack.STEP_SILENT_MS))
        assertTrue(!huntAccelStepCounts(true, false, true, 1_000L, 9_000L))
        assertTrue(huntAccelStepCounts(true, true, true, 1_000L, 1_500L))
    }

    @Test
    fun aWalkingBounceCountsAndTheOpeningJoltDoesNot() {
        val steps = HuntStepDetector()
        var at = 0L
        repeat(8) {
            assertTrue(!steps.sample(13.0f, at))
            at += 20L
        }
        repeat(20) {
            assertTrue(!steps.sample(9.8f, at))
            at += 20L
        }
        assertTrue(rise(steps, at).first)
        at = rise(steps, at).second + 500L
        repeat(10) {
            steps.sample(9.8f, at)
            at += 20L
        }
        val second = rise(steps, at)
        assertTrue(second.first)
        val early = rise(steps, second.second + 100L)
        assertTrue(!early.first)
    }

    private fun rise(steps: HuntStepDetector, start: Long): Pair<Boolean, Long> {
        var at = start
        var hit = false
        for (mag in floatArrayOf(10.1f, 10.5f, 11.0f, 10.4f, 9.85f)) {
            if (steps.sample(mag, at)) hit = true
            at += 20L
        }
        return hit to at
    }
}

private fun HuntTrack.lock(lat: Double, lon: Double, at: Long = 5_000L) {
    gps(lat, lon, 5f, at, fresh = true)
    gps(lat, lon, 5f, at + 1_000L, fresh = true)
}
