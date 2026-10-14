package com.clhs.score.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class WeatherLocationCatalogTest {
    private val locations: List<WeatherLocation> by lazy {
        SchoolJson.decodeFromString(catalogueFile().readText(Charsets.UTF_8))
    }

    @Test fun bundledAdministrativeCatalogueHas368UniqueTownshipsAcross22Counties() {
        assertEquals(368, locations.size)
        assertEquals(368, locations.map { it.id }.toSet().size)
        assertEquals(22, locations.map { it.county }.toSet().size)
        assertEquals(368, locations.map { it.county to it.name }.toSet().size)
        for (location in locations) {
            assertTrue(location.id.matches(Regex("tw-[0-9]{8}")))
            assertTrue(location.name.isNotBlank())
            assertTrue(location.county.isNotBlank())
            assertTrue(location.latitude.isFinite() && location.latitude in -90.0..90.0)
            assertTrue(location.longitude.isFinite() && location.longitude in -180.0..180.0)
            assertEquals("TW", location.countryCode)
            assertEquals(ZoneId.of("Asia/Taipei"), ZoneId.of(location.timezoneId))
            assertTrue(location.supportsCwa)
            assertEquals(location.name, location.cwaLocationName)
        }
    }

    @Test fun eachCountyKeepsItsOfficialThreeDayDatasetIdentity() {
        val expected = mapOf(
            "宜蘭縣" to "001", "桃園市" to "005", "新竹縣" to "009", "苗栗縣" to "013",
            "彰化縣" to "017", "南投縣" to "021", "雲林縣" to "025", "嘉義縣" to "029",
            "屏東縣" to "033", "臺東縣" to "037", "花蓮縣" to "041", "澎湖縣" to "045",
            "基隆市" to "049", "新竹市" to "053", "嘉義市" to "057", "臺北市" to "061",
            "高雄市" to "065", "新北市" to "069", "臺中市" to "073", "臺南市" to "077",
            "連江縣" to "081", "金門縣" to "085",
        ).mapValues { (_, number) -> setOf("F-D0047-$number") }
        val actual = locations.groupBy { it.county }.mapValues { (_, rows) -> rows.map { requireNotNull(it.cwaDataset) }.toSet() }
        assertEquals(expected, actual)
    }

    @Test fun localSearchNormalizesTaiAliasAndCombinesCountyTownTerms() {
        val original = matchLocalWeatherLocations(locations, "臺北市 中正區")
        assertEquals(1, original.size)
        assertEquals("臺北市", original.single().county)
        assertEquals("中正區", original.single().name)
        assertEquals(original, matchLocalWeatherLocations(locations, "  台北市, 中正區  "))
        assertEquals(original, matchLocalWeatherLocations(locations, "臺北市，中正區"))
        val zhongli = matchLocalWeatherLocations(locations, "桃園 中壢")
        assertEquals("中壢區", zhongli.single().name)
        assertEquals("F-D0047-005", zhongli.single().cwaDataset)
        assertEquals("西屯區", matchLocalWeatherLocations(locations, "台中 西屯").single().name)
    }

    @Test fun sameTownNameRetainsCountyIdentityAndNeverInfersOverseasLocation() {
        val sameName = matchLocalWeatherLocations(locations, "中正區")
        assertEquals(setOf("臺北市", "基隆市"), sameName.map { it.county }.toSet())
        assertEquals(2, sameName.map { it.id }.toSet().size)
        assertTrue(sameName.all { it.name == "中正區" && it.supportsCwa })
        assertTrue(matchLocalWeatherLocations(locations, "東京").isEmpty())
        assertTrue(matchLocalWeatherLocations(locations, "New York").isEmpty())
        assertTrue(matchLocalWeatherLocations(locations, "").isEmpty())
        assertTrue(matchLocalWeatherLocations(locations, "  ").isEmpty())
    }

    @Test fun broadQueriesAreBoundedStableAndDoNotModifyCatalogueEntries() {
        val result = matchLocalWeatherLocations(locations, "台")
        assertEquals(40, result.size)
        assertEquals(result, matchLocalWeatherLocations(locations, "臺"))
        assertEquals(result, matchLocalWeatherLocations(locations.reversed(), "台"))
        assertTrue(result.all { it in locations })
        assertEquals(368, locations.size)
    }

    private fun catalogueFile(): File {
        val relative = listOf("src/main/assets/weather_locations.json", "app/src/main/assets/weather_locations.json",
            "android/app/src/main/assets/weather_locations.json")
        return generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .flatMap { directory -> relative.asSequence().map { File(directory, it) } }
            .firstOrNull(File::isFile) ?: error("Cannot find bundled weather_locations.json from test working directory")
    }
}
