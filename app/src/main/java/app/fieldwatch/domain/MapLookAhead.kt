package app.fieldwatch.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The next screen of a follow map, along the direction of travel.
 * A turn also asks for a wedge on that side. The boxes are not drawn.
 * They are only so the pictures can be saved before the phone gets there.
 */
object MapLookAhead {
    /** A smaller swing is GPS noise. The wedge starts here. */
    const val TURN_DEG = 20.0

    /**
     * Same-sized windows. The first sits one screen ahead. When [turnDeg] is
     * clockwise-positive and large, the second sits ahead and off that side.
     * An unknown course returns nothing.
     */
    fun boxes(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        courseDeg: Double,
        turnDeg: Double,
    ): List<DoubleArray> {
        if (!courseDeg.isFinite()) return emptyList()
        if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) return emptyList()
        if (north <= south || east <= west) return emptyList()
        val ahead = shift(south, north, west, east, courseDeg, along = 1.0, across = 0.0)
        if (abs(turnDeg) < TURN_DEG) return listOf(ahead)
        val side = if (turnDeg > 0.0) 0.5 else -0.5
        val wedge = shift(south, north, west, east, courseDeg, along = 1.0, across = side)
        return listOf(ahead, wedge)
    }

    /**
     * Compass degrees of a finger drag from one window to the next.
     * A nudge under about 20 m is not a scroll. Unknown stays null.
     */
    fun panCourse(from: DoubleArray?, to: DoubleArray?): Double? {
        if (from == null || to == null || from.size < 4 || to.size < 4) return null
        val dLat = (to[0] + to[1] - from[0] - from[1]) / 2.0
        val dLon = (to[2] + to[3] - from[2] - from[3]) / 2.0
        val midLat = (to[0] + to[1]) / 2.0
        val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        val northM = dLat * 110_540.0
        val eastM = dLon * 111_320.0 * cosLat
        if (hypot(northM, eastM) < 20.0) return null
        var deg = Math.toDegrees(atan2(eastM, northM))
        if (deg < 0.0) deg += 360.0
        return deg
    }

    /** Shortest swing from [from] to [to], clockwise positive. Unknown stays zero. */
    fun turnDegrees(from: Double, to: Double): Double {
        if (!from.isFinite() || !to.isFinite()) return 0.0
        var delta = (to - from) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    private fun shift(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        courseDeg: Double,
        along: Double,
        across: Double,
    ): DoubleArray {
        val midLat = (south + north) / 2.0
        val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        val halfNorthM = (north - south) / 2.0 * 110_540.0
        val halfEastM = (east - west) / 2.0 * 111_320.0 * cosLat
        val rad = Math.toRadians(courseDeg)
        val northward = cos(rad)
        val eastward = sin(rad)
        val alongM = along * 2.0 * (abs(northward) * halfNorthM + abs(eastward) * halfEastM)
        val rightNorth = -eastward
        val rightEast = northward
        val acrossM = across * 2.0 * (abs(rightNorth) * halfNorthM + abs(rightEast) * halfEastM)
        val dNorth = northward * alongM + rightNorth * acrossM
        val dEast = eastward * alongM + rightEast * acrossM
        val dLat = dNorth / 110_540.0
        val dLon = dEast / (111_320.0 * cosLat)
        return doubleArrayOf(south + dLat, north + dLat, west + dLon, east + dLon)
    }
}
