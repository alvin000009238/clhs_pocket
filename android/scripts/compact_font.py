#!/usr/bin/env python3
"""Remove optional TrueType glyph names without changing rendering tables.

Usage: python android/scripts/compact_font.py source.ttf output.ttf
Requires fonttools (also used by generate_material_symbol_subset.py).
"""

import sys
from pathlib import Path

from fontTools.ttLib import TTFont


def compact(source: Path, destination: Path) -> None:
    with TTFont(source, recalcTimestamp=False) as font:
        tables = {tag: font.getTableData(tag) for tag in font.reader.keys()}
        # Android shapes by glyph ID; PostScript glyph names are editor metadata.
        font["post"].formatType = 3.0
        font.save(destination)
    with TTFont(destination, recalcTimestamp=False) as result:
        assert set(result.reader.keys()) == set(tables), "Font tables changed"
        for tag, data in tables.items():
            if tag not in {"head", "post"}:
                assert result.getTableData(tag) == data, f"Rendering table changed: {tag}"
        # Only the whole-font checksum may change in the rendering header.
        header = result.getTableData("head")
        assert header[:8] + header[12:] == tables["head"][:8] + tables["head"][12:]
        assert result["post"].formatType == 3.0
        assert result.getTableData("post")[4:] == tables["post"][4:32]
    print(f"{source.stat().st_size:,} -> {destination.stat().st_size:,} bytes")


if __name__ == "__main__":
    compact(Path(sys.argv[1]), Path(sys.argv[2]))
