#!/usr/bin/env python3
"""Generate the app-owned Material Symbols Rounded subset fonts."""

from __future__ import annotations

import argparse
import tempfile
import zipfile
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

from fontTools.ttLib import TTFont
import fontTools.subset
import fontTools.varLib.instancer

ICONS = (
    "arrow_back",
    "arrow_forward",
    "search",
    "close",
    "more_horiz",
    "more_vert",
    "refresh",
    "settings",
    "dashboard_customize",
    "home",
    "newsstand",
    "school",
    "local_library",
    "campaign",
    "calendar_today",
    "calendar_view_week",
    "bar_chart",
    "science",
    "system_update",
    "info",
    "brightness_medium",
    "light_mode",
    "palette",
    "dark_mode",
    "account_circle",
    "check",
    "person",
    "thumbs_up_down",
    "meeting_room",
    "lock",
    "lock_open",
    "fingerprint",
    "notifications",
    "notifications_active",
    "download",
    "arrow_outward",
    "keyboard_arrow_down",
    "keyboard_arrow_right",
    "keyboard_arrow_up",
    "menu",
    "code",
    "license",
)

AAR_GROUP_PATH = Path("dev.vicart/compose-material-symbols-android/1.0.2")
ROUNDED_FONT_ENTRY = (
    "assets/composeResources/dev.vicart.compose.material.symbols.resources/"
    "font/material_symbols_rounded.ttf"
)
OUTLINE_AXES = ("FILL=0", "wght=400", "GRAD=0", "opsz=24")
FILLED_AXES = ("FILL=1", "wght=400", "GRAD=0", "opsz=24")
SOURCE_FONT_URL = (
    "https://raw.githubusercontent.com/google/material-design-icons/master/variablefont/"
    "MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf"
)


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def default_output_dir() -> Path:
    return repo_root() / "android" / "app" / "src" / "main" / "res" / "font"


def aar_search_roots() -> list[Path]:
    return [
        Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / AAR_GROUP_PATH,
        repo_root()
        / "android"
        / ".gradle-user-home"
        / "caches"
        / "modules-2"
        / "files-2.1"
        / AAR_GROUP_PATH,
    ]


def find_cached_aar() -> Path | None:
    for root in aar_search_roots():
        if not root.exists():
            continue
        matches = sorted(root.rglob("compose-material-symbols.aar"))
        if matches:
            return matches[0]
    return None


def extract_rounded_font(aar_path: Path, destination: Path) -> Path:
    with zipfile.ZipFile(aar_path) as archive:
        try:
            data = archive.read(ROUNDED_FONT_ENTRY)
        except KeyError as exc:
            raise SystemExit(f"Rounded font not found in {aar_path}") from exc
    destination.write_bytes(data)
    return destination


def download_rounded_font(destination: Path) -> Path:
    print("Cached source font not found; downloading the official Material Symbols Rounded font.")
    request = Request(SOURCE_FONT_URL, headers={"User-Agent": "CLHS-Pocket-font-subsetter"})
    try:
        with urlopen(request, timeout=120) as response:
            destination.write_bytes(response.read())
    except (OSError, URLError) as exc:
        raise SystemExit(
            "Could not download the Material Symbols source font. "
            "Use --source-font for offline generation."
        ) from exc
    return destination


def resolve_source_font(source_font: str | None, work_dir: Path) -> Path:
    if source_font:
        path = Path(source_font).resolve()
        if not path.is_file():
            raise SystemExit(f"--source-font does not exist: {path}")
        return path

    aar_path = find_cached_aar()
    destination = work_dir / "material_symbols_rounded.ttf"
    if aar_path is not None:
        return extract_rounded_font(aar_path, destination)
    return download_rounded_font(destination)


def collect_ligature_glyphs(font_path: Path, icons: tuple[str, ...]) -> list[str]:
    font = TTFont(font_path)
    try:
        cmap = font.getBestCmap()
        reverse_cmap = {glyph_name: chr(codepoint) for codepoint, glyph_name in cmap.items()}
        ligature_map: dict[str, str] = {}
        gsub = font["GSUB"].table
        for lookup in gsub.LookupList.Lookup:
            for subtable in lookup.SubTable:
                subtable = getattr(subtable, "ExtSubTable", subtable)
                ligatures = getattr(subtable, "ligatures", None)
                if not ligatures:
                    continue
                for first_glyph, ligature_records in ligatures.items():
                    first_char = reverse_cmap.get(first_glyph)
                    if first_char is None:
                        continue
                    for ligature in ligature_records:
                        chars = [first_char]
                        for component in ligature.Component:
                            component_char = reverse_cmap.get(component)
                            if component_char is None:
                                break
                            chars.append(component_char)
                        else:
                            ligature_map["".join(chars)] = ligature.LigGlyph

        missing = [icon for icon in icons if icon not in ligature_map]
        if missing:
            raise SystemExit(f"Missing Material Symbols ligatures: {', '.join(missing)}")

        glyphs = {ligature_map[icon] for icon in icons}
        for icon in icons:
            for character in icon:
                glyph_name = cmap.get(ord(character))
                if glyph_name:
                    glyphs.add(glyph_name)
        return sorted(glyphs)
    finally:
        font.close()



def generate_subset(source_font: Path, output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="material-symbols-subset-") as temp_name:
        temp_dir = Path(temp_name)
        glyphs_file = temp_dir / "glyphs.txt"
        variable_subset = temp_dir / "material_symbols_rounded_variable_subset.ttf"
        glyphs_file.write_text("\n".join(collect_ligature_glyphs(source_font, ICONS)), encoding="utf-8")

        fontTools.subset.main(
            [
                str(source_font),
                f"--glyphs-file={glyphs_file}",
                "--layout-features=*",
                "--no-layout-closure",
                "--glyph-names",
                "--symbol-cmap",
                "--legacy-cmap",
                f"--output-file={variable_subset}",
            ]
        )

        outputs = (
            (
                output_dir / "material_symbols_rounded_outline_subset.ttf",
                OUTLINE_AXES,
            ),
            (
                output_dir / "material_symbols_rounded_filled_subset.ttf",
                FILLED_AXES,
            ),
        )
        for output_file, axes in outputs:
            fontTools.varLib.instancer.main(
                [
                    str(variable_subset),
                    *axes,
                    "--output",
                    str(output_file),
                ]
            )
            print(f"Wrote {output_file} ({output_file.stat().st_size} bytes)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source-font",
        help="Path to a local material_symbols_rounded.ttf. Otherwise uses a cached AAR or downloads Google's font.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=default_output_dir(),
        help="Directory for generated Android font resources.",
    )
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="material-symbols-source-") as temp_name:
        source_font = resolve_source_font(args.source_font, Path(temp_name))
        generate_subset(source_font, args.output_dir.resolve())


if __name__ == "__main__":
    main()
