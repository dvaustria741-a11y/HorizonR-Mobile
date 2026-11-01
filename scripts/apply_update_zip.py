#!/usr/bin/env python3
"""
Auto-Extract Update System (`scripts/apply_update_zip.py`):
Scans the `Update/` directory for any `*.zip` archive(s).
If found:
1. Extracts all files from each `.zip` archive (automatically unwrapping a single top-level directory if present)
   and overwrites the main repository source tree (`./`).
2. Preserves the existing `README.md` if it contains `<!-- HORIZONZ_README_PRESERVE -->`, updating ONLY
   the targeted version badges (`<!-- VERSION_BADGE_START -->` and `<!-- LATEST_VERSION_START -->`)
   so the README layout and documentation are never overwritten on subsequent updates!
3. Deletes the processed `.zip` file(s) from `Update/` while keeping `Update/.gitkeep` and `Update/README.md`.
"""

import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
UPDATE_DIR = ROOT_DIR / "Update"


def sync_readme_version_only(existing_readme: Path, incoming_readme: Path):
    """Updates only the version badge & latest version callout in existing_readme."""
    try:
        cur_txt = existing_readme.read_text(encoding="utf-8")
        inc_txt = incoming_readme.read_text(encoding="utf-8")
        if "<!-- HORIZONZ_README_PRESERVE -->" not in cur_txt or "<!-- HORIZONZ_README_MOBILE_V3 -->" not in cur_txt:
            shutil.copy2(incoming_readme, existing_readme)
            return

        for start_tag, end_tag in [
            ("<!-- VERSION_BADGE_START -->", "<!-- VERSION_BADGE_END -->"),
            ("<!-- LATEST_VERSION_START -->", "<!-- LATEST_VERSION_END -->"),
        ]:
            if start_tag in cur_txt and end_tag in cur_txt and start_tag in inc_txt and end_tag in inc_txt:
                inc_block = inc_txt[inc_txt.index(start_tag):inc_txt.index(end_tag) + len(end_tag)]
                cur_start = cur_txt.index(start_tag)
                cur_end = cur_txt.index(end_tag) + len(end_tag)
                cur_txt = cur_txt[:cur_start] + inc_block + cur_txt[cur_end:]

        # Also extract version number from incoming zip/readme if present (e.g. v1.21.0)
        ver_match = re.search(r"HorizonZ_Launcher-v(\d+\.\d+(?:\.\d+)?)", inc_txt)
        if ver_match:
            new_ver = ver_match.group(1)
            cur_txt = re.sub(r"HorizonZ_Launcher-v\d+\.\d+(?:\.\d+)?", f"HorizonZ_Launcher-v{new_ver}", cur_txt)
            cur_txt = re.sub(r"HorizonZ v\d+\.\d+(?:\.\d+)?", f"HorizonZ v{new_ver}", cur_txt)

        existing_readme.write_text(cur_txt, encoding="utf-8")
        print("[Auto-Update] Preserved README.md and updated only version badges.")
    except Exception as e:
        print(f"[Auto-Update] Notice while updating README.md version section: {e}")


def extract_zip_to_root(zip_path: Path, root_dir: Path) -> int:
    print(f"[Auto-Update] Extracting {zip_path.name} into repository root...")
    extracted_count = 0
    with tempfile.TemporaryDirectory() as tmp_dir_str:
        tmp_dir = Path(tmp_dir_str)
        with zipfile.ZipFile(zip_path, "r") as zf:
            zf.extractall(tmp_dir)

        entries = [p for p in tmp_dir.iterdir() if p.name != "__MACOSX"]
        source_base = tmp_dir
        if len(entries) == 1 and entries[0].is_dir():
            if entries[0].name not in ("crates", "packaging", "scripts", "tools", ".github", "Update", "BBS"):
                source_base = entries[0]
                print(f"[Auto-Update] Detected wrapper folder '{source_base.name}/', unwrapping to root...")

        for item in sorted(source_base.rglob("*")):
            if item.is_file():
                rel_path = item.relative_to(source_base)
                if rel_path.parts[0] == "Update" and rel_path.suffix.lower() == ".zip":
                    continue
                dest_path = root_dir / rel_path
                if rel_path.as_posix() == "README.md" and dest_path.exists():
                    sync_readme_version_only(dest_path, item)
                    extracted_count += 1
                    continue
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item, dest_path)
                if dest_path.suffix in (".py", ".sh"):
                    dest_path.chmod(dest_path.stat().st_mode | 0o755)
                extracted_count += 1

    return extracted_count


def main() -> int:
    if not UPDATE_DIR.exists():
        UPDATE_DIR.mkdir(parents=True, exist_ok=True)
        (UPDATE_DIR / ".gitkeep").write_text("# Keep Update/ directory tracked in Git.\n", encoding="utf-8")
        print("[Auto-Update] No Update/ zip files found.")
        return 0

    zip_files = sorted(UPDATE_DIR.glob("*.zip"))
    if not zip_files:
        print("[Auto-Update] No .zip files in Update/ directory. Continuing with current main source.")
        return 0

    applied_names = []
    for zpath in zip_files:
        if not zipfile.is_zipfile(zpath):
            print(f"[Auto-Update] Skipping invalid zip file: {zpath.name}")
            continue
        count = extract_zip_to_root(zpath, ROOT_DIR)
        print(f"[Auto-Update] Overwrote {count} files from {zpath.name} into main source.")
        zpath.unlink()
        applied_names.append(zpath.name)

    UPDATE_DIR.mkdir(parents=True, exist_ok=True)
    gitkeep = UPDATE_DIR / ".gitkeep"
    if not gitkeep.exists():
        gitkeep.write_text("# Keep Update/ directory tracked in Git.\n", encoding="utf-8")

    if applied_names:
        summary_file = ROOT_DIR / ".auto_update_applied.txt"
        summary_file.write_text(", ".join(applied_names) + "\n", encoding="utf-8")
        print(f"[Auto-Update] Successfully applied and cleaned up: {', '.join(applied_names)}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
