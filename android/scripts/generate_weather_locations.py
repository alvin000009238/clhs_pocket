"""Build the offline Taiwan weather catalogue from official public sources.

Uses only Python's standard library. Geometry: NLSC/TGOS dataset 7441, linked
from the requested WMS dataset 32157. County forecast identifiers: CWA OpenAPI
summaries. Township names are cross-checked against CWA's own website catalogue;
the OpenAPI LocationName parameter does not enumerate townships.

Run: python android/scripts/generate_weather_locations.py
Sources are cached below android/.artifacts/weather-catalog-source. --offline reuses
that cache. The resulting asset contains no credentials or API response cache.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import ssl
import struct
import urllib.request
import zipfile


ANDROID = Path(__file__).resolve().parents[1]
SOURCES = {
    "bounds.zip": "https://www.tgos.tw/tgos/VirtualDir/Product/3fe61d4a-ca23-4f45-8aca-4a536f40f290/%E9%84%89%28%E9%8E%AE%E3%80%81%E5%B8%82%E3%80%81%E5%8D%80%29%E7%95%8C%E7%B7%9A1140318.zip",
    "cwa.yaml": "https://opendata.cwa.gov.tw/apidoc/v1",
    "town.js": "https://www.cwa.gov.tw/Data/js/info/Info_Town.js",
    "county.js": "https://www.cwa.gov.tw/Data/js/info/Info_County.js",
}


def download(url):
    context = ssl.create_default_context()
    # Some government certificates lack Subject Key Identifier. Retain normal
    # certificate-chain and hostname verification, without Python 3.13 strict mode.
    context.verify_flags &= ~getattr(ssl, "VERIFY_X509_STRICT", 0)
    request = urllib.request.Request(url, headers={"User-Agent": "CLHS-Pocket-catalog/1"})
    with urllib.request.urlopen(request, context=context, timeout=90) as response:
        data = response.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024:
            raise ValueError("Source exceeds 64 MiB")
        return data


def dbf_records(data):
    count = struct.unpack_from("<I", data, 4)[0]
    header, length = struct.unpack_from("<HH", data, 8)
    fields = []
    for offset in range(32, header - 1, 32):
        descriptor = data[offset : offset + 32]
        fields.append((descriptor[:11].split(b"\0")[0].decode("ascii"), descriptor[16]))
    for number in range(count):
        raw = data[header + number * length : header + (number + 1) * length]
        if raw[0:1] != b" ":
            raise ValueError("Deleted DBF records require explicit reconciliation")
        record, offset = {}, 1
        for name, size in fields:
            record[name] = raw[offset : offset + size].decode("utf-8").strip()
            offset += size
        yield record


def shp_polygons(data):
    offset = 100
    while offset < len(data):
        _, words = struct.unpack_from(">II", data, offset)
        raw = data[offset + 8 : offset + 8 + words * 2]
        offset += 8 + words * 2
        if struct.unpack_from("<I", raw)[0] != 5:
            raise ValueError("Expected polygon shape")
        parts, points = struct.unpack_from("<II", raw, 36)
        starts = list(struct.unpack_from(f"<{parts}I", raw, 44)) + [points]
        vertices = [struct.unpack_from("<dd", raw, 44 + parts * 4 + i * 16) for i in range(points)]
        yield [vertices[starts[i] : starts[i + 1]] for i in range(parts)]


def ring_area(ring):
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:])) / 2


def interior_point(rings):
    """Select an interior point on the largest land component, avoiding holes.

    A horizontal scan through that component's midpoint gives intersections
    with every boundary. Odd/even spans provide points strictly inside land;
    the longest span avoids tiny offshore islands. This is a representative
    coordinate, not a claim about population centre or an observation station.
    """
    largest = max(rings, key=lambda ring: abs(ring_area(ring)))
    ys = [point[1] for point in largest]
    y = (min(ys) + max(ys)) / 2
    intersections = sorted(
        a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
        for ring in rings
        for a, b in zip(ring, ring[1:])
        if (a[1] <= y < b[1]) or (b[1] <= y < a[1])
    )
    if len(intersections) < 2 or len(intersections) % 2:
        raise ValueError("Invalid polygon intersections")
    start, end = max(zip(intersections[::2], intersections[1::2]), key=lambda pair: pair[1] - pair[0])
    return round((start + end) / 2, 6), round(y, 6)


def cwa_names(source):
    county_names = dict(re.findall(r"'ID':'([^']+)'.*?'Name':\{'C':'([^']+)'", source["county.js"].decode("utf-8")))
    township_names = set()
    town_text = source["town.js"].decode("utf-8")
    for code, block in re.findall(r"'([^']+)':\[(.*?)\]", town_text, re.S):
        for name in re.findall(r"'Name':\{'C':'([^']+)'", block):
            township_names.add((county_names[code], name))
    forecasts = {}
    api = source["cwa.yaml"].decode("utf-8")
    for identifier, block in re.findall(r"^  /v1/rest/datastore/(F-D0047-\d+):\n(.*?)(?=^  /|\Z)", api, re.M | re.S):
        summary = re.search(r"summary: 鄉鎮天氣預報-(.+?)未來3天天氣預報", block)
        if summary and summary[1] in county_names.values():
            forecasts[summary[1]] = identifier
    if len(forecasts) != 22 or len(township_names) != 368:
        raise ValueError(f"Unexpected official catalogue: {len(forecasts)} counties/{len(township_names)} townships")
    return forecasts, township_names


def build(source):
    forecasts, cwa_towns = cwa_names(source)
    archive = zipfile.ZipFile(__import__("io").BytesIO(source["bounds.zip"]))
    stem = "TOWN_MOI_1140318"
    records = list(dbf_records(archive.read(stem + ".dbf")))
    polygons = list(shp_polygons(archive.read(stem + ".shp")))
    if len(records) != len(polygons) or len(records) != 368:
        raise ValueError("Unexpected township geometry count")
    output = []
    for record, rings in zip(records, polygons):
        county, town = record["COUNTYNAME"], record["TOWNNAME"]
        if (county, town) not in cwa_towns:
            raise ValueError(f"Township not verified by CWA: {county}{town}")
        longitude, latitude = interior_point(rings)
        if not 118 <= longitude <= 123 or not 21 <= latitude <= 27:
            raise ValueError("Geometry is not Taiwan longitude/latitude")
        output.append({
            "id": "tw-" + record["TOWNCODE"], "name": town,
            "latitude": latitude, "longitude": longitude,
            "timezoneId": "Asia/Taipei", "countryCode": "TW", "county": county,
            "cwaDataset": forecasts[county], "cwaLocationName": town,
        })
    output.sort(key=lambda item: item["id"])
    if len({item["id"] for item in output}) != 368:
        raise ValueError("Duplicate official township code")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--cache-dir", type=Path, default=ANDROID / ".artifacts/weather-catalog-source")
    parser.add_argument("--output", type=Path, default=ANDROID / "app/src/main/assets/weather_locations.json")
    args = parser.parse_args()
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    source = {}
    for filename, url in SOURCES.items():
        file = args.cache_dir / filename
        if not args.offline:
            file.write_bytes(download(url))
        source[filename] = file.read_bytes()
        print(filename, hashlib.sha256(source[filename]).hexdigest())
    locations = build(source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(locations, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(locations)} verified township locations in {len({item['county'] for item in locations})} counties")


if __name__ == "__main__":
    main()
