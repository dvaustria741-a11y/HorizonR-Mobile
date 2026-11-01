#!/usr/bin/env python3
"""Wire the Sodium Lite profile (scripts/sodium_lite/sodium_lite.rs) into the upstream engine.

Run from the build workspace root, after the upstream engine was synced and after
scripts/patch_horizon_direct_boot.py. Every step checks its anchor first: if the upstream
code moved, the patch prints a warning and leaves the engine untouched (it never fails the build).
"""
import re
import sys
from pathlib import Path

ROOT = Path(".")
MODULE_SRC = Path(__file__).resolve().parent / "sodium_lite" / "sodium_lite.rs"
GRAPHICS_RS = ROOT / "crates/horizon-client-render/src/graphics.rs"
MAIN_RS = ROOT / "crates/horizon-client/src/android_main.rs"
LAUNCHER_RS = ROOT / "crates/horizon-client/src/android/launcher.rs"
MODULE_DST = ROOT / "crates/horizon-client/src/android/sodium_lite.rs"
TAG = "[Sodium Lite]"

# Keys the profile writes; each must still be parsed by the engine's own settings reader.
KEYS = ["entityCulling", "caveCulling", "chunkUpdates", "uploadBudgetKiB", "preset"]


def skip(reason: str) -> None:
    print(f"{TAG} skipped: {reason}")
    sys.exit(0)


def main() -> None:
    for p in (MODULE_SRC, GRAPHICS_RS, MAIN_RS, LAUNCHER_RS):
        if not p.exists():
            skip(f"{p} not found")

    graphics = GRAPHICS_RS.read_text(encoding="utf-8")
    missing = [k for k in KEYS if f'"{k}" =>' not in graphics]
    if missing:
        skip(f"engine no longer parses these horizon-graphics.txt keys: {missing}")

    main_txt = MAIN_RS.read_text(encoding="utf-8")
    launcher_txt = LAUNCHER_RS.read_text(encoding="utf-8")

    decl_anchor = '#[path = "android/launcher.rs"]\nmod launcher;\n'
    call_anchor = '        crate::diagnostics::heartbeat(\n            "play_requested",'
    if "mod sodium_lite;" not in main_txt and main_txt.count(decl_anchor) != 1:
        skip("module declaration anchor not found in android_main.rs")
    if "sodium_lite::apply" not in launcher_txt and launcher_txt.count(call_anchor) != 1:
        skip("Launcher::action anchor not found in launcher.rs")

    MODULE_DST.write_text(MODULE_SRC.read_text(encoding="utf-8"), encoding="utf-8")

    if "mod sodium_lite;" not in main_txt:
        main_txt = main_txt.replace(
            decl_anchor,
            decl_anchor + '#[allow(dead_code)]\n#[path = "android/sodium_lite.rs"]\nmod sodium_lite;\n',
            1,
        )
        MAIN_RS.write_text(main_txt, encoding="utf-8")

    if "sodium_lite::apply" not in launcher_txt:
        call = (
            "        // Sodium Lite: bring horizon-graphics.txt in line with the mod's switch before the game reads it.\n"
            "        match crate::sodium_lite::apply(&self.catalog.config.game_home(&self.home)) {\n"
            "            Ok(outcome) => log::info!(\"sodium_lite: {outcome:?}\"),\n"
            "            Err(error) => log::warn!(\"sodium_lite: {error}\"),\n"
            "        }\n"
        )
        launcher_txt = launcher_txt.replace(call_anchor, call + call_anchor, 1)
        LAUNCHER_RS.write_text(launcher_txt, encoding="utf-8")

    print(f"{TAG} patched: module copied, declared in android_main.rs, applied in Launcher::action")


main()
