package com.wakr.app.data

import com.wakr.app.core.WorldCity
import java.io.BufferedReader
import java.text.Normalizer
import java.time.ZoneId
import java.util.Locale

private const val CATALOG_ASSET = "world-cities.tsv"
private val suggestedCityIds = listOf(
    "2643743", "1850147", "5128581", "2988507", "1880252", "2147714", "5368361", "292223",
).map { "geonames:$it" }

private val combiningMarks = Regex("\\p{M}+")
private val separators = Regex("[^\\p{L}\\p{N}]+")
private val legacyZoneNames = mapOf(
    "Europe/Kyiv" to "Europe/Kiev", "America/Nuuk" to "America/Godthab",
    "Pacific/Kanton" to "Pacific/Enderbury", "Asia/Kolkata" to "Asia/Calcutta",
    "Asia/Kathmandu" to "Asia/Katmandu", "Asia/Yangon" to "Asia/Rangoon",
)

internal fun cityZone(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (error: java.time.DateTimeException) {
    ZoneId.of(legacyZoneNames[id] ?: throw error)
}

private fun searchText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
    .lowercase(Locale.ROOT)
    .replace(combiningMarks, "")
    .replace(separators, " ")
    .trim()

internal class WorldCityCatalog(private val entries: List<Entry>) {
    class Entry(val city: WorldCity, countryCode: String = "", aliases: String = "") {
        val name = searchText(city.city)
        val alternateNames = searchText(aliases)
        val searchable = searchText(
            "${city.city} ${city.region} ${city.zone.id} $countryCode $aliases " +
                when (countryCode) { "US" -> "USA"; "GB" -> "UK"; else -> "" },
        )
    }

    fun choices(saved: List<WorldCity>, query: String): List<WorldCity> {
        val savedIds = saved.mapTo(mutableSetOf()) { it.id }
        val legacy = saved.filter { it.id == it.zone.id }.map { searchText(it.city) to it.zone.rules }
        val available = entries.asSequence().filter { entry ->
            entry.city.id !in savedIds && legacy.none { (name, rules) ->
                (entry.name == name || " ${entry.alternateNames} ".contains(" $name ")) &&
                    entry.city.zone.rules == rules
            }
        }
        val normalized = searchText(query)
        if (normalized.isBlank()) {
            return available.sortedBy {
                suggestedCityIds.indexOf(it.city.id).let { rank -> if (rank < 0) Int.MAX_VALUE else rank }
            }.take(6).map { it.city }.toList()
        }
        val terms = normalized.split(' ')
        val queryPhrase = " $normalized "
        return available.filter { entry -> terms.all { it in entry.searchable } }
            .sortedBy { entry ->
                when {
                    entry.name == normalized -> 0
                    queryPhrase.contains(" ${entry.name} ") -> 1
                    entry.name.startsWith(normalized) -> 2
                    entry.alternateNames.startsWith(normalized) -> 3
                    else -> 4
                }
            }.map { it.city }.toList()
    }

    companion object {
        fun read(reader: BufferedReader): WorldCityCatalog = WorldCityCatalog(reader.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .mapNotNull { line ->
                val f = line.split('\t')
                runCatching {
                    val region = if (f[4].isBlank()) f[3] else "${f[4]}, ${f[3]}"
                    Entry(WorldCity(
                        zone = cityZone(f[5]), city = f[1], region = region, id = "geonames:${f[0]}",
                        latitude = f[6].toDouble(), longitude = f[7].toDouble(),
                    ), f[2], f[8])
                }.getOrNull()
            }.toList())

        fun load(assets: android.content.res.AssetManager): WorldCityCatalog =
            assets.open(CATALOG_ASSET).bufferedReader().use(::read)
    }
}
