package com.wakr.app.ui.sheets

import com.wakr.app.data.WorldCityCatalog
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LocationChoicesTest {
    private val catalog = sequenceOf(File("src/main/assets/world-cities.tsv"), File("app/src/main/assets/world-cities.tsv"))
        .first(File::isFile).bufferedReader().use(WorldCityCatalog::read)

    @Test fun suggestionsExcludeSavedCitiesAndStayShort() {
        val choices = catalog.choices(listOf(prettify("Europe/London")), "")
        assertEquals(6, choices.size)
        assertEquals("Tokyo", choices.first().city)
        assertFalse(choices.any { it.city == "London" && it.zone.id == "Europe/London" })
    }

    @Test fun searchMatchesCountriesAndAccentedNames() {
        val saoPaulo = catalog.choices(emptyList(), "sao paulo brazil").first()
        assertEquals("São Paulo", saoPaulo.city)
        assertEquals("Brazil", saoPaulo.region)
        assertEquals(saoPaulo, catalog.choices(emptyList(), "SÃO PAULO").first())
        assertTrue(catalog.choices(emptyList(), "united arab emirates").any { it.city == "Dubai" })
        assertTrue(catalog.choices(emptyList(), "not a city").isEmpty())
    }

    @Test fun searchIncludesSmallCapitalsAndOldCityAliases() {
        assertTrue(catalog.choices(emptyList(), "ngerulmud palau").any { it.city == "Ngerulmud" })
        assertEquals("Kolkata", catalog.choices(emptyList(), "calcutta").first().city)
        assertEquals("New York", catalog.choices(emptyList(), "new york city").first().city)
    }

    @Test fun sameNameAndTimeZoneLocationsHaveDifferentIdentities() {
        val choices = catalog.choices(emptyList(), "springfield united states")
        val illinois = choices.first { it.region.startsWith("Illinois") }
        val missouri = choices.first { it.region.startsWith("Missouri") }
        assertEquals(illinois.zone, missouri.zone)
        assertNotEquals(illinois.id, missouri.id)
        val remaining = catalog.choices(listOf(illinois), "springfield united states")
        assertFalse(illinois in remaining)
        assertTrue(missouri in remaining)
    }

    @Test fun legacyRegionNamesPreserveMultiWordCities() {
        val city = prettify("America/Argentina/Buenos_Aires")
        assertEquals("Buenos Aires", city.city)
        assertEquals("Argentina", city.region)
    }
}
