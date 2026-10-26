package app.fieldwatch.domain

import app.fieldwatch.data.MapKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MapKeyTest {
    private val token = "GoodKey1234"

    @Test
    fun aTokenIsDataAndAnythingElseIsRefused() {
        assertEquals(token, MapKey.accept("  $token  "))
        assertEquals(MapKey.Verdict.SHORT, MapKey.verdict("short"))
        assertNull(MapKey.accept("short"))
        for (bad in listOf(
            "key key",
            "key\nkey",
            "key\rkey",
            "key&admin=1",
            "key?x=1",
            "key#frag",
            "../etc/passwd",
            "<script>",
            "javascript:alert(1)",
            "key;rm",
            "key'or'1",
            "key\"q",
            "key/path",
            "key\\path",
            "key%2e%2e",
            "key\u0000key",
            "key\u202ekey",
        )) {
            assertEquals(bad, MapKey.Verdict.DENIED, MapKey.verdict(bad))
            assertNull(MapKey.accept(bad))
        }
    }

    @Test
    fun theKeyIsOnlyAQueryValueOnTheServiceHost() {
        assertEquals(
            "https://tile.openstreetmap.org/12/1140/1536.png",
            MapTileUrl.build(MapBasemap.OSM, 12, 1140, 1536, token),
        )
        assertEquals(
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/12/1536/1140",
            MapTileUrl.build(MapBasemap.USGS_IMAGERY, 12, 1140, 1536, token),
        )
        val esri = MapTileUrl.build(MapBasemap.ESRI_SATELLITE, 12, 1140, 1536, token)
        assertEquals(
            "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/" +
                "static-basemap-tiles-service/v1/arcgis/imagery-labels/static/tile/12/1536/1140?token=$token",
            esri,
        )
        assertTrue(esri!!.substringBefore('?').contains("/12/1536/1140"))
        assertFalse(esri.substringBefore('?').contains(token))
        assertEquals(
            "https://api.maptiler.com/maps/streets-v4/256/12/1140/1536.png?key=$token",
            MapTileUrl.build(MapBasemap.MAPTILER_STREETS, 12, 1140, 1536, token),
        )
        assertEquals(
            "https://tiles.stadiamaps.com/tiles/alidade_satellite/12/1140/1536.jpg?api_key=$token",
            MapTileUrl.build(MapBasemap.STADIA_SATELLITE, 12, 1140, 1536, token),
        )
        assertEquals(
            "https://api.thunderforest.com/outdoors/12/1140/1536.png?apikey=$token",
            MapTileUrl.build(MapBasemap.THUNDERFOREST_OUTDOORS, 12, 1140, 1536, token),
        )
        assertNull(MapTileUrl.build(MapBasemap.ESRI_STREETS, 12, 1140, 1536, null))
        assertNull(MapTileUrl.build(MapBasemap.ESRI_STREETS, 12, 1140, 1536, "bad key"))
        assertNull(MapTileUrl.build(MapBasemap.ESRI_STREETS, -1, 0, 0, token))
        assertNull(MapTileUrl.build(MapBasemap.ESRI_STREETS, 2, 9, 0, token))
        assertFalse(MapBasemap.OSM.needsKey)
        assertFalse(MapBasemap.USGS_TOPO.needsKey)
        assertTrue(MapBasemap.ESRI_OUTDOOR.needsKey)
        assertEquals(MapKeyService.ESRI, MapBasemap.ESRI_STREETS.service)
        assertEquals(MapBasemap.ESRI_SATELLITE.service, MapBasemap.ESRI_OUTDOOR.service)
    }

    @Test
    fun aRedirectCannotCarryTheKeyOffTheService() {
        assertTrue(
            MapTileUrl.allowsRedirect(
                MapBasemap.ESRI_SATELLITE,
                "https://cdn.arcgis.com/tile.png",
                token,
            ),
        )
        assertFalse(
            MapTileUrl.allowsRedirect(
                MapBasemap.ESRI_SATELLITE,
                "https://evil.example/tile.png?token=$token",
                token,
            ),
        )
        assertFalse(
            MapTileUrl.allowsRedirect(
                MapBasemap.STADIA_STREETS,
                "https://tiles.stadiamaps.com.evil.example/tile.png",
                token,
            ),
        )
        assertFalse(
            MapTileUrl.allowsRedirect(
                MapBasemap.MAPTILER_STREETS,
                "http://api.maptiler.com/tile.png",
                token,
            ),
        )
        assertFalse(
            MapTileUrl.allowsRedirect(
                MapBasemap.THUNDERFOREST_STREETS,
                "https://user:secret@api.thunderforest.com/tile.png",
                token,
            ),
        )
    }

    @Test
    fun aStoredKeyIsATokenAndDropsEverythingElse() {
        val dir = Files.createTempDirectory("map-keys").toFile()
        val file = dir.resolve("map-keys.json")
        file.writeText(
            """{"ESRI":"$token","MAPTILER":"bad key","EVIL":"$token","STADIA":"short"}""",
        )
        val store = MapKeyStore(file)
        assertEquals(token, store.read(MapKeyService.ESRI))
        assertNull(store.read(MapKeyService.MAPTILER))
        assertNull(store.read(MapKeyService.STADIA))
        store.save(MapKeyService.MAPTILER, token)
        val written = file.readText()
        assertFalse(written.contains("bad key"))
        assertFalse(written.contains("EVIL"))
        assertFalse(written.contains("short"))
        assertTrue(written.contains("\"MAPTILER\":\"$token\""))
        assertFalse(store.save(MapKeyService.THUNDERFOREST, "<script>alert(1)</script>"))
        assertNull(store.read(MapKeyService.THUNDERFOREST))
        store.clear(MapKeyService.ESRI)
        assertNull(store.read(MapKeyService.ESRI))
        assertFalse(file.readText().contains("ESRI"))
    }

    @Test
    fun settingsJsonDoesNotContainTheKey() {
        val secret = "SecretKey9876"
        val encoded = SettingsExchange.json.encodeToString(
            AppSettings.serializer(),
            AppSettings(mapBasemap = MapBasemap.ESRI_SATELLITE),
        )
        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains("token="))
        val round = SettingsExchange.json.decodeFromString(AppSettings.serializer(), encoded)
        assertEquals(MapBasemap.ESRI_SATELLITE, round.mapBasemap)
        val old = SettingsExchange.json.decodeFromString(
            AppSettings.serializer(),
            """{"mapBasemap":"USGS_TOPO"}""",
        )
        assertEquals(MapBasemap.USGS_TOPO, old.mapBasemap)
        val faa = SettingsExchange.json.decodeFromString(
            AppSettings.serializer(),
            """{"mapBasemap":"FAA_VFR_SECTIONAL"}""",
        )
        assertEquals(MapBasemap.FAA_VFR_SECTIONAL, faa.mapBasemap)
    }
}
