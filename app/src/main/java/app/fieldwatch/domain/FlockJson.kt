package app.fieldwatch.domain

import java.io.Reader

class FlockParseException(message: String) : Exception(message)

/**
 * Pulls roadside sensors out of a GeoJSON feature collection.
 * A plate-reader file with no type tags stays plate readers. A truncated file throws.
 */
fun parseFlock(reader: Reader): List<FlockCamera> {
    val pull = JsonPull(reader)
    pull.skipBom()
    pull.expect('{')
    var found = false
    val out = LinkedHashMap<Long, FlockCamera>()
    while (true) {
        val key = pull.nextName() ?: break
        if (key == "features") {
            found = true
            pull.readFeatures(out)
        } else {
            pull.skipValue()
        }
    }
    if (!found) throw FlockParseException("no features")
    return out.values.sortedWith(compareBy({ it.lat }, { it.lon }, { it.key }))
}

private fun JsonPull.readFeatures(out: LinkedHashMap<Long, FlockCamera>) {
    expect('[')
    while (true) {
        skipWs()
        when (peek()) {
            ']'.code -> {
                bump()
                return
            }
            ','.code -> bump()
            '{'.code -> {
                val cam = readFeature()
                if (cam != null) {
                    if (out.size >= MAX_KEPT && cam.key !in out) {
                        throw FlockParseException("too many cameras")
                    }
                    out[cam.key] = cam
                }
            }
            -1 -> throw FlockParseException("truncated")
            else -> skipValue()
        }
    }
}

private fun JsonPull.readFeature(): FlockCamera? {
    expect('{')
    var point = false
    var lat = Double.NaN
    var lon = Double.NaN
    var osmId = 0L
    var osmType = ""
    var brand = ""
    var operatorName = ""
    var direction = ""
    var cameraDirection = ""
    var directions = emptyList<Int>()
    var explicitKind = ""
    var highway = ""
    var enforcement = ""
    var surveillance = ""
    while (true) {
        val key = nextName() ?: break
        when (key) {
            "geometry" -> {
                val coords = readGeometry()
                if (coords != null) {
                    point = true
                    lon = coords.first
                    lat = coords.second
                }
            }
            "properties" -> {
                val props = readProperties()
                osmId = props.osmId
                osmType = props.osmType
                brand = props.brand
                operatorName = props.operatorName
                direction = props.direction
                cameraDirection = props.cameraDirection
                directions = props.directions
                explicitKind = props.explicitKind
                highway = props.highway
                enforcement = props.enforcement
                surveillance = props.surveillance
            }
            else -> skipValue()
        }
    }
    if (!point || !lat.isFinite() || !lon.isFinite()) return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val kind = classifySensor(explicitKind, highway, enforcement, surveillance) ?: return null
    val id = sensorKey(osmId, osmType)
    if (id == 0L) return null
    val facings = if (directions.isNotEmpty()) {
        directions
    } else {
        val merged = ArrayList<Int>(FlockApproach.MAX_FACINGS)
        for (deg in bearingsFrom(direction) + bearingsFrom(cameraDirection)) {
            if (deg !in merged) merged.add(deg)
            if (merged.size == FlockApproach.MAX_FACINGS) break
        }
        merged
    }
    return FlockCamera(
        id,
        lat,
        lon,
        facings,
        plateBrand(brand, operatorName),
        isFlock(brand, operatorName),
        kind,
    ).forStore()
}

/** A way is the negative id. A relation uses another negative range so the two do not collide. */
internal fun sensorKey(osmId: Long, osmType: String): Long {
    if (osmId == 0L) return 0L
    if (osmId > 0L && osmType.equals("way", ignoreCase = true)) return -osmId
    if (osmId > 0L && osmType.equals("relation", ignoreCase = true)) return -(1L shl 62) - osmId
    return osmId
}

/**
 * An explicit kind wins. No type tags means a plate reader, which is the downloaded list.
 * A tagged feature this app does not draw is dropped.
 */
internal fun classifySensor(
    explicit: String,
    highway: String,
    enforcement: String,
    surveillance: String,
): SensorKind? {
    when (explicit.trim().lowercase()) {
        "plate", "alpr" -> return SensorKind.PLATE
        "speed" -> return SensorKind.SPEED
        "red" -> return SensorKind.RED
        "gunshot" -> return SensorKind.GUNSHOT
        "camera" -> return SensorKind.CAMERA
        "" -> Unit
        else -> return null
    }
    val type = surveillance.lowercase()
    val hwy = highway.lowercase()
    val enf = enforcement.lowercase()
    if (type.isEmpty() && hwy.isEmpty() && enf.isEmpty()) return SensorKind.PLATE
    val parts = type.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.any { "gunshot" in it }) return SensorKind.GUNSHOT
    // A red-light camera is often also tagged highway=speed_camera. Enforcement wins.
    if (enf == "red_light_camera" || enf == "traffic_signals" || "red_light" in enf) return SensorKind.RED
    if ("radar" in parts || hwy == "speed_camera" || enf == "maxspeed" || enf == "average_speed") {
        return SensorKind.SPEED
    }
    if ("alpr" in parts) return SensorKind.PLATE
    if ("camera" in parts) return SensorKind.CAMERA
    return null
}

/** A compass word such as NW stays empty. A number, or a short list of numbers, is a facing. */
internal fun bearingsFrom(raw: String): List<Int> {
    if (raw.isBlank()) return emptyList()
    val out = ArrayList<Int>(FlockApproach.MAX_FACINGS)
    var start = 0
    for (i in 0..raw.length) {
        if (i != raw.length && raw[i] != ';' && raw[i] != ',') continue
        val part = raw.substring(start, i).trim()
        start = i + 1
        if (part.isEmpty()) continue
        val deg = FlockApproach.norm(part.toDoubleOrNull() ?: continue) ?: continue
        if (deg !in out) out.add(deg)
        if (out.size == FlockApproach.MAX_FACINGS) break
    }
    return out
}

private data class FlockProps(
    val osmId: Long,
    val osmType: String,
    val brand: String,
    val operatorName: String,
    val direction: String,
    val cameraDirection: String,
    val directions: List<Int>,
    val explicitKind: String,
    val highway: String,
    val enforcement: String,
    val surveillance: String,
)

private fun JsonPull.readGeometry(): Pair<Double, Double>? {
    skipWs()
    if (peek() == 'n'.code) {
        literal("null")
        return null
    }
    if (peek() != '{'.code) {
        skipValue()
        return null
    }
    expect('{')
    var point = false
    var coords: Pair<Double, Double>? = null
    while (true) {
        val key = nextName() ?: break
        when (key) {
            "type" -> point = optionalString() == "Point"
            "coordinates" -> coords = readLonLat()
            else -> skipValue()
        }
    }
    return if (point) coords else null
}

private fun JsonPull.readProperties(): FlockProps {
    skipWs()
    if (peek() == 'n'.code) {
        literal("null")
        return emptyProps()
    }
    if (peek() != '{'.code) {
        skipValue()
        return emptyProps()
    }
    expect('{')
    var osmId = 0L
    var osmType = ""
    var brand = ""
    var operatorName = ""
    var direction = ""
    var cameraDirection = ""
    var directions = emptyList<Int>()
    var explicitKind = ""
    var highway = ""
    var enforcement = ""
    var surveillance = ""
    while (true) {
        val key = nextName() ?: break
        when (key) {
            "osmId" -> osmId = optionalLong()
            "osmType" -> osmType = optionalString()
            "brand" -> brand = optionalString()
            "operator" -> operatorName = optionalString()
            "direction" -> direction = optionalRaw()
            "camera:direction" -> cameraDirection = optionalRaw()
            "directions" -> directions = readDirections()
            "kind" -> explicitKind = optionalString()
            "highway" -> highway = optionalString()
            "enforcement" -> enforcement = optionalString()
            "surveillance:type", "surveillance_type" -> surveillance = optionalString()
            else -> skipValue()
        }
    }
    return FlockProps(
        osmId,
        osmType,
        brand,
        operatorName,
        direction,
        cameraDirection,
        directions,
        explicitKind,
        highway,
        enforcement,
        surveillance,
    )
}

private fun emptyProps(): FlockProps = FlockProps(0L, "", "", "", "", "", emptyList(), "", "", "", "")

private fun isFlock(brand: String, operatorName: String): Boolean {
    return brand.contains("flock", ignoreCase = true) || operatorName.contains("flock", ignoreCase = true)
}

/** Company name for the alert. Brand wins. The name is clipped so a notice stays one line. */
internal fun plateBrand(brand: String, operatorName: String): String {
    val raw = if (brand.isNotBlank()) brand else operatorName
    val cleaned = buildString(raw.length) {
        var space = false
        for (ch in raw) {
            val use = if (ch == '\n' || ch == '\r' || ch == '\t' || ch == '{' || ch == '}') ' ' else ch
            if (use == ' ') {
                if (isNotEmpty() && !space) append(' ')
                space = true
            } else {
                append(use)
                space = false
            }
        }
    }.trim()
    var clipped = cleaned.take(48)
    while (clipped.encodeToByteArray().size > 48) clipped = clipped.dropLast(1)
    return clipped
}

private const val MAX_KEPT = 400_000

private class JsonPull(private val reader: Reader) {
    private var cur = reader.read()
    private val sb = StringBuilder()

    fun peek(): Int = cur

    fun bump(): Int {
        val seen = cur
        cur = reader.read()
        return seen
    }

    fun skipBom() {
        if (cur == 0xFEFF) bump()
    }

    fun skipWs() {
        while (cur == ' '.code || cur == '\n'.code || cur == '\r'.code || cur == '\t'.code) bump()
    }

    fun expect(c: Char) {
        skipWs()
        if (cur != c.code) throw FlockParseException("expected $c")
        bump()
    }

    fun nextName(): String? {
        while (true) {
            skipWs()
            when (cur) {
                '}'.code -> {
                    bump()
                    return null
                }
                ','.code -> bump()
                '"'.code -> {
                    val name = string()
                    skipWs()
                    if (cur != ':'.code) throw FlockParseException("expected colon")
                    bump()
                    return name
                }
                -1 -> throw FlockParseException("truncated")
                else -> throw FlockParseException("bad object")
            }
        }
    }

    fun skipValue() {
        skipWs()
        when (cur) {
            '{'.code -> skipObject()
            '['.code -> skipArray()
            '"'.code -> string()
            't'.code -> literal("true")
            'f'.code -> literal("false")
            'n'.code -> literal("null")
            '-'.code -> number()
            in '0'.code..'9'.code -> number()
            else -> throw FlockParseException("bad value")
        }
    }

    fun optionalString(): String {
        skipWs()
        if (cur == '"'.code) return string()
        skipValue()
        return ""
    }

    fun optionalRaw(): String {
        skipWs()
        if (cur == '"'.code) return string()
        if (cur == 'n'.code) {
            literal("null")
            return ""
        }
        if (cur == '-'.code || cur in '0'.code..'9'.code) return number()
        skipValue()
        return ""
    }

    fun optionalLong(): Long {
        skipWs()
        if (cur == '"'.code) {
            val text = string()
            return text.toLongOrNull() ?: 0L
        }
        if (cur == 'n'.code) {
            literal("null")
            return 0L
        }
        if (cur == '-'.code || cur in '0'.code..'9'.code) {
            val text = number()
            return if ('.' in text || 'e' in text || 'E' in text) text.toDouble().toLong() else text.toLong()
        }
        skipValue()
        return 0L
    }

    fun readDirections(): List<Int> {
        skipWs()
        if (cur != '['.code) {
            skipValue()
            return emptyList()
        }
        bump()
        val out = ArrayList<Int>(FlockApproach.MAX_FACINGS)
        while (true) {
            skipWs()
            when (cur) {
                ']'.code -> {
                    bump()
                    return out
                }
                ','.code -> bump()
                -1 -> throw FlockParseException("truncated")
                '-'.code, in '0'.code..'9'.code -> {
                    val deg = FlockApproach.norm(number().toDouble())
                    if (deg != null && deg !in out && out.size < FlockApproach.MAX_FACINGS) out.add(deg)
                }
                else -> skipValue()
            }
        }
    }

    fun readLonLat(): Pair<Double, Double>? {
        skipWs()
        if (cur != '['.code) {
            skipValue()
            return null
        }
        bump()
        skipWs()
        if (cur == '['.code || cur == ']'.code || cur == 'n'.code || cur == '"'.code) {
            finishNested(1)
            return null
        }
        if (cur != '-'.code && cur !in '0'.code..'9'.code) {
            finishNested(1)
            return null
        }
        val lon = number().toDouble()
        skipWs()
        if (cur == ','.code) bump()
        skipWs()
        if (cur != '-'.code && cur !in '0'.code..'9'.code) {
            finishNested(1)
            return null
        }
        val lat = number().toDouble()
        finishNested(1)
        return lon to lat
    }

    private fun skipObject() {
        expect('{')
        while (nextName() != null) skipValue()
    }

    private fun skipArray() {
        expect('[')
        while (true) {
            skipWs()
            when (cur) {
                ']'.code -> {
                    bump()
                    return
                }
                ','.code -> bump()
                -1 -> throw FlockParseException("truncated")
                else -> skipValue()
            }
        }
    }

    private fun finishNested(startDepth: Int) {
        var depth = startDepth
        while (depth > 0) {
            when (cur) {
                -1 -> throw FlockParseException("truncated")
                '"'.code -> string()
                '['.code -> {
                    bump()
                    depth += 1
                }
                ']'.code -> {
                    bump()
                    depth -= 1
                }
                '{'.code -> skipObject()
                else -> bump()
            }
        }
    }

    private fun string(): String {
        skipWs()
        if (cur != '"'.code) throw FlockParseException("expected string")
        bump()
        sb.setLength(0)
        while (cur >= 0) {
            when (cur) {
                '"'.code -> {
                    bump()
                    return sb.toString()
                }
                '\\'.code -> {
                    bump()
                    when (cur) {
                        '"'.code -> sb.append('"')
                        '\\'.code -> sb.append('\\')
                        '/'.code -> sb.append('/')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'n'.code -> sb.append('\n')
                        'r'.code -> sb.append('\r')
                        't'.code -> sb.append('\t')
                        'u'.code -> {
                            var hex = 0
                            repeat(4) {
                                bump()
                                hex = (hex shl 4) or hexDigit(cur)
                            }
                            sb.append(hex.toChar())
                        }
                        else -> throw FlockParseException("bad escape")
                    }
                    bump()
                }
                else -> {
                    if (cur < 0x20) throw FlockParseException("raw control")
                    sb.append(cur.toChar())
                    bump()
                }
            }
        }
        throw FlockParseException("unterminated string")
    }

    private fun number(): String {
        skipWs()
        sb.setLength(0)
        if (cur == '-'.code) {
            sb.append('-')
            bump()
        }
        if (cur !in '0'.code..'9'.code) throw FlockParseException("bad number")
        while (cur in '0'.code..'9'.code) {
            sb.append(cur.toChar())
            bump()
        }
        if (cur == '.'.code) {
            sb.append('.')
            bump()
            if (cur !in '0'.code..'9'.code) throw FlockParseException("bad number")
            while (cur in '0'.code..'9'.code) {
                sb.append(cur.toChar())
                bump()
            }
        }
        if (cur == 'e'.code || cur == 'E'.code) {
            sb.append(cur.toChar())
            bump()
            if (cur == '+'.code || cur == '-'.code) {
                sb.append(cur.toChar())
                bump()
            }
            if (cur !in '0'.code..'9'.code) throw FlockParseException("bad number")
            while (cur in '0'.code..'9'.code) {
                sb.append(cur.toChar())
                bump()
            }
        }
        return sb.toString()
    }

    fun literal(word: String) {
        for (ch in word) {
            if (cur != ch.code) throw FlockParseException("expected $word")
            bump()
        }
    }

    private fun hexDigit(c: Int): Int {
        return when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> throw FlockParseException("bad hex")
        }
    }
}
