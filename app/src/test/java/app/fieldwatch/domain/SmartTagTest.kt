package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTagTest {
    @Test
    fun statesAndFieldsMatchTheTable() {
        val separated = parse(state = 1, aging = 0x0102, id = "AABBCCDDEEFF0011", flags = 0x07)
        assertEquals("Separated from owner", separated.state)
        assertTrue(separated.separated)
        assertEquals(0x0102, separated.aging)
        assertEquals("AABBCCDDEEFF0011", separated.privacyId)
        assertEquals("Full", separated.battery)
        assertEquals("UWB", separated.uwb)

        assertEquals("Separated from owner", parse(state = 2).state)
        assertEquals("Separated from owner, away more than 24 h", parse(state = 3).state)
        assertTrue(parse(state = 3).separated)
        assertEquals("With owner", parse(state = 4).state)
        assertEquals("With owner", parse(state = 5).state)
        assertEquals("With owner", parse(state = 6).state)
        assertFalse(parse(state = 4).separated)
        assertEquals("Low", parse(state = 1, flags = 0x01).battery)
        assertEquals("Medium", parse(state = 1, flags = 0x02).battery)
        assertEquals("Very low", parse(state = 1, flags = 0x00).battery)
        assertEquals("No UWB", parse(state = 1, flags = 0x03).uwb)
    }

    @Test
    fun unlabeledFramesKeepAgingBatteryAndUwb() {
        val zero = parse(state = 0, aging = 9, flags = 0x06)
        val seven = parse(state = 7, aging = 9, flags = 0x06)
        assertNull(zero.state)
        assertNull(seven.state)
        assertFalse(zero.separated)
        assertFalse(seven.separated)
        assertEquals(9, zero.aging)
        assertEquals(9, seven.aging)
        assertEquals("Medium", zero.battery)
        assertEquals("UWB", zero.uwb)
    }

    @Test
    fun rejectedFramesProduceNoFields() {
        assertNull(SmartTag.parse("10"))
        assertNull(SmartTag.parse(frame(state = 1).dropLast(2)))
        assertNull(SmartTag.parse(frame(state = 1, version = 0x00)))
        val longer = parse(state = 2, aging = 7, extra = "FF")
        assertEquals(7, longer.aging)
        assertEquals("AABBCCDDEEFF0011", longer.privacyId)
    }

    @Test
    fun nameOrCompanyWithoutFd5aHasNoState() {
        val named = radio(name = "SmartTag")
        assertNull(SmartTag.read(named))
        assertNull(SmartTag.separatedId(named))
        val company = radio(facts = RadioFacts(mfgRecords = listOf(MfgRecord(0x0075, "0102"))))
        assertNull(SmartTag.read(company))
        val labeled = company.withLiveDecode(emptyList())
        assertTrue(labeled.liveDecode.isEmpty())
        assertNull(labeled.smartTagId)
    }

    @Test
    fun liveChipAndStoredIdFollowTheLatestFrame() {
        val separated = radio(facts = fd5a(1)).withLiveDecode(emptyList())
        assertEquals("Separated from owner", separated.liveDecode.single().text)
        assertTrue(separated.liveDecode.single().emphasis)
        assertEquals("AABBCCDDEEFF0011", separated.smartTagId)

        val away = radio(facts = fd5a(3)).withLiveDecode(emptyList())
        assertEquals("Separated from owner, away more than 24 h", away.liveDecode.single().text)
        assertTrue(away.liveDecode.single().emphasis)

        val owner = radio(facts = fd5a(4)).withLiveDecode(emptyList())
        assertEquals("With owner", owner.liveDecode.single().text)
        assertFalse(owner.liveDecode.single().emphasis)
        assertNull(owner.smartTagId)

        val unlabeled = radio(facts = fd5a(0)).withLiveDecode(emptyList())
        assertTrue(unlabeled.liveDecode.isEmpty())
        assertNull(unlabeled.smartTagId)
    }

    @Test
    fun privacyModeMasksTheIdAndKeepsTheWords() {
        val device = radio(facts = fd5a(1))
        val open = DeviceDetailText.build(device, emptyList(), now = 1L, demo = false)
        val hidden = DeviceDetailText.build(device, emptyList(), now = 1L, demo = true)
        assertTrue(open.contains("AA BB CC DD EE FF 00 11"))
        assertTrue(open.contains("Not a serial number."))
        assertTrue(open.contains("Separated from owner"))
        assertTrue(hidden.contains("Privacy ID: masked"))
        assertTrue(hidden.contains("Not a serial number."))
        assertFalse(hidden.contains("AA BB"))
    }

    @Test
    fun separatedAddressesWithOneIdBecomeOneSubject() {
        val a = radio(mac = "02:00:00:00:00:01", first = 1_000L, last = 2_000L, rssiMin = -80, rssiMax = -40)
            .copy(smartTagId = "AABBCCDDEEFF0011", gpsTrail = listOf(GpsSample(1_000L, 1.0, 2.0, -40)))
        val b = radio(mac = "02:00:00:00:00:02", first = 3_000L, last = 9_000L, rssiMin = -90, rssiMax = -30)
            .copy(smartTagId = "AABBCCDDEEFF0011", gpsTrail = listOf(GpsSample(9_000L, 1.1, 2.1, -30)))
        val pooled = SmartTag.poolForFollow(listOf(a, b))
        assertEquals(1, pooled.size)
        val one = pooled.single()
        assertEquals(1_000L, one.firstSeen)
        assertEquals(9_000L, one.lastSeen)
        assertEquals(-90, one.rssiMin)
        assertEquals(-30, one.rssiMax)
        assertEquals(-30, one.rssi)
        assertEquals(2, one.gpsTrail.size)
        assertEquals(a.key, one.key)
    }

    @Test
    fun withOwnerAddressesStaySeparate() {
        val a = radio(mac = "02:00:00:00:00:01")
        val b = radio(mac = "02:00:00:00:00:02")
        assertNull(a.smartTagId)
        assertEquals(2, SmartTag.poolForFollow(listOf(a, b)).size)
    }

    private fun parse(
        state: Int,
        aging: Int = 1,
        id: String = "AABBCCDDEEFF0011",
        flags: Int = 0x07,
        extra: String = "",
        version: Int = 0x10,
    ): SmartTag.Read = SmartTag.parse(frame(state, aging, id, flags, extra, version))!!

    private fun frame(
        state: Int,
        aging: Int = 1,
        id: String = "AABBCCDDEEFF0011",
        flags: Int = 0x07,
        extra: String = "",
        version: Int = 0x10,
    ): String {
        val b0 = (version and 0xF8) or (state and 0x07)
        val b1 = aging and 0xFF
        val b2 = (aging shr 8) and 0xFF
        val b3 = (aging shr 16) and 0xFF
        return "%02X%02X%02X%02X%s%02X%s".format(b0, b1, b2, b3, id, flags, extra)
    }

    private fun fd5a(state: Int) = RadioFacts(
        serviceData = listOf(ServiceDataRecord("FD5A", frame(state))),
    )

    private fun radio(
        mac: String = "02:00:00:00:00:01",
        name: String = "",
        facts: RadioFacts = RadioFacts.Empty,
        first: Long = 1L,
        last: Long = 1L,
        rssiMin: Int = -50,
        rssiMax: Int = -40,
    ) = Sighting(
        key = "BLE:$mac",
        kind = RadioKind.BLE,
        mac = mac,
        name = name,
        rssi = rssiMax,
        rssiMin = rssiMin,
        rssiMax = rssiMax,
        channel = 0,
        frequencyMhz = 2402,
        vendor = null,
        randomized = true,
        hiddenSsid = false,
        serviceUuids = emptyList(),
        manufacturerId = null,
        manufacturerDataHex = "",
        rawHex = "",
        extras = "",
        firstSeen = first,
        lastSeen = last,
        hitCount = 1,
        fleetIds = emptySet(),
        rssiHistory = emptyList(),
        presence = emptyList(),
        facts = facts,
    )
}
