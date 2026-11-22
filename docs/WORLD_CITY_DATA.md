# World Clock city data

The bundled catalog is derived from [GeoNames](https://www.geonames.org/) under the [Creative Commons Attribution 4.0 International license](https://creativecommons.org/licenses/by/4.0/). Wakr downloads nothing at runtime. The app credits GeoNames in Settings under About.

The catalog uses the GeoNames populated-places extract, country names, and administrative division names. It includes capitals, seats of government, and cities with at least 100,000 residents as recorded in the extract. It excludes city subdivisions, historical places, and abandoned places from the population selection. Wakr shortens a display name and adds search aliases. Coordinates are used for day and night cards. Android's IANA time-zone data supplies the local times.

To refresh the catalog, download `cities15000.zip`, `countryInfo.txt`, and `admin1CodesASCII.txt` from the [GeoNames directory](https://download.geonames.org/export/dump/) into one directory. Run the generator against that directory.

```sh
python3 scripts/generate_world_cities.py /path/to/geonames app/src/main/assets/world-cities.tsv
```

The bundled snapshot was downloaded on 2026-11-22. GeoNames IDs are kept stable across refreshes.
