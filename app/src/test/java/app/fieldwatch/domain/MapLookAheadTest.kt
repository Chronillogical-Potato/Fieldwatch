package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLookAheadTest {
    private val south = 36.0
    private val north = 36.01
    private val west = -115.01
    private val east = -115.0

    @Test
    fun straightNorthPutsTheNextScreenOnTheFarEdge() {
        val ahead = MapLookAhead.boxes(south, north, west, east, courseDeg = 0.0, turnDeg = 0.0).single()
        assertEquals(north, ahead[0], 1e-6)
        assertEquals(north + (north - south), ahead[1], 1e-6)
        assertEquals(west, ahead[2], 1e-6)
        assertEquals(east, ahead[3], 1e-6)
    }

    @Test
    fun straightEastPutsTheNextScreenOnTheFarEdge() {
        val ahead = MapLookAhead.boxes(south, north, west, east, courseDeg = 90.0, turnDeg = 0.0).single()
        assertEquals(south, ahead[0], 1e-6)
        assertEquals(north, ahead[1], 1e-6)
        assertEquals(east, ahead[2], 1e-6)
        assertEquals(east + (east - west), ahead[3], 1e-6)
    }

    @Test
    fun aRightTurnAddsAWedgeOnThatSide() {
        val boxes = MapLookAhead.boxes(south, north, west, east, courseDeg = 0.0, turnDeg = 25.0)
        assertEquals(2, boxes.size)
        val ahead = boxes[0]
        val wedge = boxes[1]
        assertTrue(wedge[2] > ahead[2])
        assertTrue(wedge[3] > ahead[3])
        assertEquals(ahead[0], wedge[0], 1e-6)
    }

    @Test
    fun aLeftTurnAddsAWedgeOnThatSide() {
        val boxes = MapLookAhead.boxes(south, north, west, east, courseDeg = 0.0, turnDeg = -25.0)
        val ahead = boxes[0]
        val wedge = boxes[1]
        assertTrue(wedge[2] < ahead[2])
        assertTrue(wedge[3] < ahead[3])
    }

    @Test
    fun aSmallSwingDoesNotAskForAWedge() {
        assertEquals(1, MapLookAhead.boxes(south, north, west, east, 0.0, 10.0).size)
    }

    @Test
    fun noCourseAsksForNothing() {
        assertTrue(MapLookAhead.boxes(south, north, west, east, Double.NaN, 30.0).isEmpty())
        assertTrue(MapLookAhead.boxes(north, south, west, east, 0.0, 0.0).isEmpty())
    }

    @Test
    fun aNorthDragPointsNorthAndASmallNudgeDoesNot() {
        val here = doubleArrayOf(south, north, west, east)
        val moved = doubleArrayOf(south + 0.01, north + 0.01, west, east)
        assertEquals(0.0, MapLookAhead.panCourse(here, moved)!!, 0.5)
        val eastward = doubleArrayOf(south, north, west + 0.01, east + 0.01)
        assertEquals(90.0, MapLookAhead.panCourse(here, eastward)!!, 0.5)
        val nudge = doubleArrayOf(south + 0.00005, north + 0.00005, west, east)
        assertEquals(null, MapLookAhead.panCourse(here, nudge))
    }

    @Test
    fun turnDegreesTakesTheShortWayAround() {
        assertEquals(20.0, MapLookAhead.turnDegrees(350.0, 10.0), 0.001)
        assertEquals(-20.0, MapLookAhead.turnDegrees(10.0, 350.0), 0.001)
        assertEquals(0.0, MapLookAhead.turnDegrees(Double.NaN, 10.0), 0.0)
    }
}
