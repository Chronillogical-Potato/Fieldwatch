package app.fieldwatch.domain

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Live hunt picture. [onGlobe] is false while the walk is still a local meter frame. */
data class HuntPose(
    val lat: Double,
    val lon: Double,
    val onGlobe: Boolean,
    val uncertaintyM: Double,
    val holdFlat: Boolean,
    val session: Int,
    val attachGen: Int,
    /** Hears for this session. The same update as the dot, so the cloud cannot lag behind it. */
    val marks: List<GpsSample> = emptyList(),
)

/**
 * Where this phone is during a hunt, in meters.
 * Steps move along the phone heading. GPS does not steer those steps.
 * Until two fresh fixes agree, the walk is a local picture: no streets and no place.
 * A short walk, about the first 20 m, is placed on those fixes. The steps already
 * taken stay behind the dot. A longer walk stays on that picture. It is not slid
 * onto a fix that arrives late. In the first 20 m after placement, one tight fix
 * that disagrees moves the dot onto that fix. After that, a fix only nudges the
 * dot when it already sits inside the circle.
 */
class HuntTrack {
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var placed = false
    private var haveCandidate = false
    private var candidateLat = 0.0
    private var candidateLon = 0.0
    private var candidateAt = Long.MIN_VALUE
    private var snapped = false
    private var attachAnchorLat = 0.0
    private var attachAnchorLon = 0.0
    private var attachGen = 0
    private var session = 0
    private var east = 0.0
    private var north = 0.0
    private var walkedM = 0.0
    private var strideM = STRIDE_M
    private var strideLearned = false
    private var headingDeg = 0.0
    private var haveHeading = false
    private var trustedDeg = 0.0
    private var haveTrusted = false
    private var gameDeg = 0.0
    private var haveGame = false
    private var lastGameSample = 0.0
    private var haveLastGameSample = false
    private var offsetDeg = 0.0
    private var haveOffset = false
    private var usingGame = false
    private var rateHeading = 0.0
    private var rateAt = Long.MIN_VALUE
    private var turnRate = 0.0
    private var stepHeading = 0.0
    private var haveStepHeading = false
    private var tiltSince = Long.MIN_VALUE
    private var holdFlat = false
    private var learnOpen = false
    private var learnSteps = 0
    private var learnWalked = 0.0
    private var learnLat = 0.0
    private var learnLon = 0.0
    private var hearGen = 0
    private val hears = ArrayList<Hear>()

    fun clear() {
        session += 1
        hearGen += 1
        placed = false
        haveCandidate = false
        candidateAt = Long.MIN_VALUE
        snapped = false
        haveLastGameSample = false
        attachGen = 0
        east = 0.0
        north = 0.0
        walkedM = 0.0
        haveHeading = false
        headingDeg = 0.0
        haveTrusted = false
        haveGame = false
        haveOffset = false
        usingGame = false
        rateAt = Long.MIN_VALUE
        turnRate = 0.0
        haveStepHeading = false
        stepHeading = 0.0
        tiltSince = Long.MIN_VALUE
        holdFlat = false
        learnOpen = false
        learnSteps = 0
        learnWalked = 0.0
        hears.clear()
    }

    fun session(): Int = session

    fun attachGen(): Int = attachGen

    /** Bumps when a hear is added, updated, or moved onto a fix. */
    fun hearGen(): Int = hearGen

    fun onGlobe(): Boolean = placed

    fun holdingFlat(): Boolean = holdFlat

    /**
     * The local picture, when this walk is not on a fix.
     * Null once the walk has been placed.
     */
    fun plainPicture(): HuntPlain? {
        if (placed) return null
        return HuntPlain(east, north, walkedM)
    }

    fun uncertaintyM(): Double = max(UNCERTAINTY_FLOOR_M, UNCERTAINTY_RATE * walkedM)

    fun steeredDeg(): Double = if (haveHeading) headingDeg else Double.NaN

    /** Saved stride from a clean walk. The default stays until one is learned. */
    fun setStride(meters: Double, learnedAlready: Boolean = false) {
        strideM = meters.coerceIn(STRIDE_MIN_M, STRIDE_MAX_M)
        if (learnedAlready) strideLearned = true
    }

    /**
     * [fresh] fixes can place the walk once two of them agree, if it is still short.
     * [commit] places immediately, for a hunt that is already on a fix.
     * A later fix returns a learned stride when a straight stretch was clean.
     * A fix does not draw the walk until it is placed. Network fixes stay out.
     */
    fun gps(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        atMs: Long = 0L,
        fresh: Boolean = true,
        commit: Boolean = false,
    ): Double? {
        if (!PayloadLocation.validCoord(lat, lon)) return null
        val reported = accuracyM.toDouble()
        if (!reported.isFinite()) return null
        val acc = if (reported <= 0.0) 1.0 else reported
        if (!placed) {
            if (localPicture(lat, lon)) return null
            if (commit) {
                lockTrail(lat, lon)
                snapped = true
                return null
            }
            if (walkedM > RECENTER_M) return null
            if (fresh && atMs > 0L) {
                if (haveCandidate && atMs > candidateAt && atMs - candidateAt in AGREE_MIN_MS..AGREE_MAX_MS) {
                    val (_, _, dist) = offset(candidateLat, candidateLon, lat, lon)
                    if (dist <= AGREE_M) {
                        placeShortWalk(lat, lon)
                        haveCandidate = false
                        return null
                    }
                }
                candidateLat = lat
                candidateLon = lon
                candidateAt = atMs
                haveCandidate = true
            }
            return null
        }
        val here = position()
        if (here != null && acc <= NUDGE_ACC_M) {
            val (_, _, dist) = offset(here.first, here.second, lat, lon)
            val room = uncertaintyM()
            if (!snapped && walkedM < RECENTER_M && dist > room && dist >= 0.4) {
                snapOnto(lat, lon)
            } else if (dist >= 0.4 && dist <= room) {
                anchorLat += (lat - here.first) * 0.5
                anchorLon += (lon - here.second) * 0.5
            }
        }
        if (acc > LEARN_ACC_M) {
            learnOpen = false
            return null
        }
        return considerLearn(lat, lon)
    }

    /**
     * Compass degrees clockwise from north, and the game-rotation yaw when the
     * phone has one. A bad magnetic field keeps the last good heading and follows
     * the game yaw from there. Coming back does not snap to a jumped compass.
     */
    fun heading(deg: Double, atMs: Long = 0L) {
        heading(deg, null, true, atMs)
    }

    fun heading(compassDeg: Double, gameDeg: Double?, magHealthy: Boolean, atMs: Long = 0L) {
        val compass = norm(compassDeg)
        val gameBefore = if (haveLastGameSample) lastGameSample else Double.NaN
        if (gameDeg != null && gameDeg.isFinite()) {
            this.gameDeg = norm(gameDeg)
            haveGame = true
            lastGameSample = this.gameDeg
            haveLastGameSample = true
        }
        val gameMoved = if (gameBefore.isFinite() && gameDeg != null && gameDeg.isFinite()) {
            angleDiff(this.gameDeg, gameBefore)
        } else {
            Double.NaN
        }
        val steer = chooseSteer(compass, magHealthy, gameMoved)
        if (!steer.isFinite()) return
        noteRate(steer, atMs)
        headingDeg = steer
        haveHeading = true
        trustedDeg = steer
        haveTrusted = true
    }

    /** Degrees off flat. Staying past [TILT_MAX_DEG] pauses steps. Flat resumes them. */
    fun tilt(degFromFlat: Double, atMs: Long = 0L) {
        if (degFromFlat > TILT_MAX_DEG) {
            if (tiltSince == Long.MIN_VALUE) tiltSince = atMs
            if (atMs - tiltSince >= TILT_HOLD_MS) holdFlat = true
        } else {
            tiltSince = Long.MIN_VALUE
            holdFlat = false
        }
    }

    /** One stride along the phone heading. The walk moves before GPS has placed it. */
    fun step(atMs: Long = 0L): Boolean {
        if (!haveHeading || holdFlat) return false
        if (turnRate > TURN_RATE_DPS) return false
        if (haveStepHeading && angleDiff(headingDeg, stepHeading) > TURN_DEG) {
            stepHeading = headingDeg
            haveStepHeading = true
            return false
        }
        stepHeading = headingDeg
        haveStepHeading = true
        val rad = Math.toRadians(headingDeg)
        val stride = strideM
        north += stride * cos(rad)
        east += stride * sin(rad)
        walkedM += stride
        if (learnOpen) {
            learnSteps += 1
            learnWalked += stride
        }
        return true
    }

    /** Globe position once the walk is placed. Null while it is still a local picture. */
    fun position(): Pair<Double, Double>? {
        if (!placed) return null
        return project(anchorLat, anchorLon, east, north)
    }

    /** Globe position, or a local picture with the same shape before the first fix. */
    fun display(): Pair<Double, Double> {
        val globe = position()
        if (globe != null) return globe
        return project(LOCAL_LAT, LOCAL_LON, east, north)
    }

    /**
     * Continue a plain walk that was left open. [samples] are hears from that picture.
     * A walk already longer than [RECENTER_M] is not placed by a later fix.
     */
    fun resumePlain(saved: HuntPlain, samples: List<GpsSample>) {
        if (placed) return
        east = saved.east
        north = saved.north
        walkedM = saved.walkedM.coerceAtLeast(0.0)
        for (sample in samples) {
            if (!PayloadLocation.validCoord(sample.lat, sample.lon)) continue
            if (!localPicture(sample.lat, sample.lon)) continue
            val (hearEast, hearNorth, _) = offset(LOCAL_LAT, LOCAL_LON, sample.lat, sample.lon)
            hears.add(Hear(hearEast, hearNorth, sample.rssi, sample.at, sample.lat, sample.lon, false))
        }
        if (hears.isNotEmpty()) hearGen += 1
    }

    /**
     * Where a pre-fix picture point sits after the walk was placed.
     * Uses the anchor from that first fix, so a later nudge does not move it.
     */
    fun fromLocal(lat: Double, lon: Double): Pair<Double, Double>? {
        if (attachGen == 0) return null
        return shift(attachAnchorLat, attachAnchorLon, lat, lon)
    }

    /**
     * Paint the dot's current place.
     * A louder reading while the dot is still here raises that place.
     * A weaker one leaves it. About 1.5 m of movement starts the next spot.
     */
    fun note(rssi: Int, at: Long) {
        if (HuntHeat.level(rssi) == null) return
        val (lat, lon) = display()
        val last = hears.lastOrNull()
        if (last != null && Geo.meters(last.lat, last.lon, lat, lon) < HuntHeat.STEP_M) {
            raiseLast(rssi, at)
            return
        }
        if (hears.size >= HuntHeat.CAP) hears.removeAt(0)
        hears.add(Hear(east, north, rssi, at, lat, lon, placed))
        hearGen += 1
    }

    /**
     * The place underfoot keeps the loudest reading heard there.
     * Does not add a spot. A packet calls this while the phone is standing still.
     */
    fun raiseHere(rssi: Int, at: Long): Boolean {
        if (HuntHeat.level(rssi) == null) return false
        val last = hears.lastOrNull() ?: return false
        val (lat, lon) = display()
        if (Geo.meters(last.lat, last.lon, lat, lon) >= HuntHeat.STEP_M) return false
        return raiseLast(rssi, at)
    }

    private fun raiseLast(rssi: Int, at: Long): Boolean {
        val last = hears.last()
        if (rssi < last.rssi + Hunt.STEP_DB) return false
        hears[hears.lastIndex] = last.copy(rssi = rssi, at = at)
        hearGen += 1
        return true
    }

    fun marks(): List<GpsSample> = hears.map { hear ->
        when {
            hear.frozen -> GpsSample(hear.at, hear.lat, hear.lon, hear.rssi)
            placed -> {
                val (lat, lon) = project(attachAnchorLat, attachAnchorLon, hear.east, hear.north)
                GpsSample(hear.at, lat, lon, hear.rssi)
            }
            else -> {
                val (lat, lon) = project(LOCAL_LAT, LOCAL_LON, hear.east, hear.north)
                GpsSample(hear.at, lat, lon, hear.rssi)
            }
        }
    }

    private fun chooseSteer(compass: Double, healthy: Boolean, gameMoved: Double): Double {
        if (!healthy) {
            usingGame = true
            if (haveGame && haveOffset) return norm(gameDeg + offsetDeg)
            return if (haveTrusted) trustedDeg else Double.NaN
        }
        val jump = haveTrusted && angleDiff(compass, trustedDeg) > REJOIN_DEG
        val still = gameMoved.isFinite() && gameMoved < GAME_STILL_DEG
        if (jump && still && haveGame && haveOffset) {
            usingGame = true
            return norm(gameDeg + offsetDeg)
        }
        if (usingGame && haveTrusted && angleDiff(compass, trustedDeg) > REJOIN_DEG) {
            if (haveGame && haveOffset) return norm(gameDeg + offsetDeg)
            return trustedDeg
        }
        usingGame = false
        if (haveGame) {
            offsetDeg = norm(compass - gameDeg)
            haveOffset = true
        }
        return compass
    }

    private fun noteRate(deg: Double, atMs: Long) {
        if (rateAt == Long.MIN_VALUE) {
            rateHeading = deg
            rateAt = atMs
        } else if (atMs - rateAt >= RATE_WINDOW_MS) {
            val dt = (atMs - rateAt) / 1000.0
            if (dt > 0.0) turnRate = angleDiff(deg, rateHeading) / dt
            rateHeading = deg
            rateAt = atMs
        }
    }

    /** A resumed hunt that was already on a fix. The plain walk is not used. */
    private fun lockTrail(lat: Double, lon: Double) {
        east = 0.0
        north = 0.0
        walkedM = 0.0
        anchorLat = lat
        anchorLon = lon
        attachAnchorLat = lat
        attachAnchorLon = lon
        placed = true
        hears.clear()
        hearGen += 1
        haveStepHeading = false
        stepHeading = 0.0
        attachGen += 1
        openLearn(lat, lon)
    }

    /**
     * Land the current dot on [lat], [lon]. Steps and hears already taken stay behind it.
     * The walk has to be short. A long one never calls this.
     */
    private fun placeShortWalk(lat: Double, lon: Double) {
        val originLat = lat - north / M_LAT
        val cos0 = cos(Math.toRadians(originLat)).coerceAtLeast(0.2)
        val originLon = lon - east / (M_LON * cos0)
        anchorLat = originLat
        anchorLon = originLon
        attachAnchorLat = originLat
        attachAnchorLon = originLon
        placed = true
        for (i in hears.indices) {
            val hear = hears[i]
            val (hearLat, hearLon) = project(originLat, originLon, hear.east, hear.north)
            hears[i] = hear.copy(lat = hearLat, lon = hearLon)
        }
        if (hears.isNotEmpty()) hearGen += 1
        attachGen += 1
        openLearn(lat, lon)
    }

    /** True for the blank-picture coordinates. A real fix is not this close to that origin. */
    private fun localPicture(lat: Double, lon: Double): Boolean {
        val (_, _, dist) = offset(LOCAL_LAT, LOCAL_LON, lat, lon)
        return dist < LOCAL_PICTURE_M
    }

    /** Moves the short trail with the dot. A later nudge leaves stored hears put. */
    private fun snapOnto(lat: Double, lon: Double) {
        val here = project(anchorLat, anchorLon, east, north)
        val dLat = lat - here.first
        val dLon = lon - here.second
        anchorLat += dLat
        anchorLon += dLon
        attachAnchorLat += dLat
        attachAnchorLon += dLon
        for (i in hears.indices) {
            val hear = hears[i]
            hears[i] = hear.copy(lat = hear.lat + dLat, lon = hear.lon + dLon)
        }
        if (hears.isNotEmpty()) hearGen += 1
        snapped = true
    }

    private fun openLearn(lat: Double, lon: Double) {
        learnOpen = true
        learnSteps = 0
        learnWalked = 0.0
        learnLat = lat
        learnLon = lon
    }

    private fun considerLearn(lat: Double, lon: Double): Double? {
        if (!learnOpen) {
            openLearn(lat, lon)
            return null
        }
        val (_, _, moved) = offset(learnLat, learnLon, lat, lon)
        val straight = learnWalked > 0.0 && moved / learnWalked >= LEARN_STRAIGHT
        if (learnSteps >= LEARN_STEPS && moved >= LEARN_MIN_M && straight) {
            val sample = (moved / learnSteps).coerceIn(STRIDE_MIN_M, STRIDE_MAX_M)
            strideM = if (!strideLearned) {
                sample
            } else {
                (0.7 * strideM + 0.3 * sample).coerceIn(STRIDE_MIN_M, STRIDE_MAX_M)
            }
            strideLearned = true
            openLearn(lat, lon)
            return strideM
        }
        if (learnWalked > 40.0 && !straight) openLearn(lat, lon)
        return null
    }

    private fun project(lat0: Double, lon0: Double, eastM: Double, northM: Double): Pair<Double, Double> {
        val cos0 = cos(Math.toRadians(lat0)).coerceAtLeast(0.2)
        val lat = lat0 + northM / M_LAT
        val lon = lon0 + eastM / (M_LON * cos0)
        return lat to lon
    }

    private fun offset(
        lat0: Double,
        lon0: Double,
        lat: Double,
        lon: Double,
    ): Triple<Double, Double, Double> {
        val cos0 = cos(Math.toRadians(lat0)).coerceAtLeast(0.2)
        val dNorth = (lat - lat0) * 110_540.0
        val dEast = (lon - lon0) * 111_320.0 * cos0
        return Triple(dEast, dNorth, hypot(dEast, dNorth))
    }

    private fun shift(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val (eastM, northM, _) = offset(LOCAL_LAT, LOCAL_LON, lat, lon)
        return project(lat0, lon0, eastM, northM)
    }

    private fun norm(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

    private fun angleDiff(a: Double, b: Double): Double {
        val d = norm(a - b)
        return if (d > 180.0) 360.0 - d else d
    }

    private data class Hear(
        val east: Double,
        val north: Double,
        val rssi: Int,
        val at: Long,
        val lat: Double,
        val lon: Double,
        val frozen: Boolean,
    )

    companion object {
        const val STRIDE_M = 0.72
        const val STRIDE_MIN_M = 0.5
        const val STRIDE_MAX_M = 0.9
        /** Two fresh fixes within this distance lock the trail. */
        const val AGREE_M = 8.0
        const val AGREE_MIN_MS = 400L
        const val AGREE_MAX_MS = 8_000L
        /** While the walk is shorter than this, two agreeing fixes place it, and one tight fix may snap the dot. */
        const val RECENTER_M = 20.0
        /** A later fix this tight may nudge the live dot. */
        const val NUDGE_ACC_M = 8.0
        /** The step sensor has gone quiet. The walking motion may count steps. */
        const val STEP_SILENT_MS = 2_000L
        /** Game yaw under this, with a jumped compass, is the phone sitting still. */
        const val GAME_STILL_DEG = 8.0
        const val UNCERTAINTY_FLOOR_M = 4.0
        const val UNCERTAINTY_RATE = 0.03
        const val TILT_MAX_DEG = 35.0
        const val TILT_HOLD_MS = 2_500L
        const val REJOIN_DEG = 20.0
        const val MAG_MIN_UT = 25.0
        const val MAG_MAX_UT = 65.0
        const val LEARN_ACC_M = 8.0
        const val LEARN_STEPS = 8
        const val LEARN_MIN_M = 8.0
        const val LEARN_STRAIGHT = 0.85
        /** A step whose heading jumped this far is a turn, not a stride. */
        const val TURN_DEG = 45.0
        /** Faster than this, the phone is turning and the step is dropped. */
        const val TURN_RATE_DPS = 25.0
        private const val RATE_WINDOW_MS = 250L
        private const val M_LAT = 110_540.0
        private const val M_LON = 111_320.0
        /** Local picture only. Not a place on the map, and not (0, 0). */
        private const val LOCAL_LAT = 1.0
        private const val LOCAL_LON = 1.0
        /** Farther than this from the local origin is a real fix, not the blank picture. */
        private const val LOCAL_PICTURE_M = 100_000.0
    }
}

/** A hunt walk that is not on a map yet. Leaving the page keeps this, and coming back continues it. */
data class HuntPlain(
    val east: Double,
    val north: Double,
    val walkedM: Double,
)

/**
 * A walking step from the accelerometer magnitude, gravity included.
 * The bounce above a slow baseline counts, about one to two strides a second.
 * The first sample sets the baseline. A quiet stretch has to come before the
 * first step, so opening Hunt does not walk the dot.
 */
class HuntStepDetector {
    private var baseline = Float.NaN
    private var armed = false
    private var above = false
    private var lastStep = 0L

    fun reset() {
        baseline = Float.NaN
        armed = false
        above = false
        lastStep = 0L
    }

    fun sample(magnitude: Float, atMs: Long): Boolean {
        if (baseline.isNaN()) {
            baseline = magnitude
            return false
        }
        val delta = magnitude - baseline
        val follow = if (delta < 0f) 0.2f else 0.02f
        baseline += delta * follow
        val dyn = magnitude - baseline
        if (dyn < LOW) {
            armed = true
            above = false
        }
        val gap = lastStep == 0L || atMs - lastStep >= MIN_GAP_MS
        if (dyn >= HIGH && armed && !above && gap) {
            above = true
            armed = false
            lastStep = atMs
            return true
        }
        if (dyn >= HIGH) above = true
        return false
    }

    companion object {
        /** Meters per second squared above the baseline. A held-out walk is a small bounce. */
        const val HIGH = 0.45f
        const val LOW = 0.2f
        /** Faster than this is a shake, not a stride. About two steps a second. */
        const val MIN_GAP_MS = 450L
    }
}

/**
 * The accelerometer may count a step when this phone has no step sensor,
 * or that sensor has not reported one step since [watchedAt].
 * A sensor that has already reported keeps the walk. Once [fellBack] is set,
 * the walking motion keeps counting for the rest of the hunt.
 */
internal fun huntAccelStepCounts(
    hardwarePresent: Boolean,
    fellBack: Boolean,
    hardwareHeard: Boolean,
    watchedAt: Long,
    now: Long,
): Boolean {
    if (!hardwarePresent || fellBack) return true
    if (hardwareHeard) return false
    return now - watchedAt >= HuntTrack.STEP_SILENT_MS
}
