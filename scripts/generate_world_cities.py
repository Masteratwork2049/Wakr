#!/usr/bin/env python3
"""Build Wakr's offline catalog from the GeoNames files in the input directory."""
import argparse
from collections import Counter
from pathlib import Path
from zipfile import ZipFile


DISPLAY_NAMES = {"5128581": "New York"}
EXTRA_SEATS = {"282239", "7303419"}  # Ramallah and East Jerusalem
EXCLUDED_TYPES = {"PPLX", "PPLH", "PPLQ"}  # Sections, historical, and abandoned places
ALIASES = {
    "1275004": "Calcutta", "1275339": "Bombay", "1273874": "Madras",
    "703448": "Kiev", "2462881": "El Aaiun", "1850147": "Tōkyō",
    "5128581": "New York City NYC", "5391959": "SF", "5368361": "LA",
    "1816670": "Peking", "1609348": "Krung Thep", "1701668": "City of Manila",
}


def tsv(path):
    with path.open(encoding="utf-8") as file:
        for line in file:
            if not line.startswith("#") and line.strip():
                yield line.rstrip("\n").split("\t")


def generate(source, output):
    countries = {row[0]: row[4] for row in tsv(source / "countryInfo.txt")}
    admin_names = {row[0]: row[1] for row in tsv(source / "admin1CodesASCII.txt")}
    cities = []
    with ZipFile(source / "cities15000.zip") as archive:
        for line in archive.read("cities15000.txt").decode("utf-8").splitlines():
            fields = line.split("\t")
            city_id, name, ascii_name = fields[:3]
            lat, lon, category, kind, country = fields[4:9]
            zone = fields[17]
            if category != "P" or not zone or country not in countries:
                continue
            if kind not in {"PPLC", "PPLG"} and city_id not in EXTRA_SEATS:
                if kind in EXCLUDED_TYPES or int(fields[14]) < 100_000:
                    continue
            area = admin_names.get(f"{country}.{fields[10]}", "")
            cities.append((city_id, DISPLAY_NAMES.get(city_id, name), ascii_name,
                           country, area, zone, lat, lon))
    duplicates = Counter((name.casefold(), country) for _, name, _, country, *_ in cities)
    with output.open("w", encoding="utf-8", newline="\n") as file:
        file.write("# GeoNames (CC BY 4.0) · https://www.geonames.org/\n")
        for city_id, name, ascii_name, country, area, zone, lat, lon in sorted(
            cities, key=lambda row: (row[1].casefold(), row[3], row[4], int(row[0]))
        ):
            area = area if duplicates[(name.casefold(), country)] > 1 else ""
            aliases = ALIASES.get(city_id, "")
            if ascii_name.casefold() != name.casefold():
                aliases = f"{ascii_name} {aliases}".strip()
            file.write("\t".join((city_id, name, country, countries[country], area, zone, lat, lon, aliases)) + "\n")
    print(f"Wrote {len(cities)} places")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.source, args.output)
