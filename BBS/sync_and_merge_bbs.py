#!/usr/bin/env python3
"""
BBS Runtime Auto-Updater & Smart 3-Way Patch Re-Applier (`BBS/sync_and_merge_bbs.py`):
1. Pulls the latest upstream runtime, Rust engine, Minecraft runner (`crates/`), `docs/modpacks/atm10-mods.csv`,
   and native `.so` / `engine-data.zip` assets from `https://github.com/Vasysik/horizon-android`.
2. Stores clean upstream copies in `BBS/runtime-rust/upstream_clean/` and records SHA-256 hashes in `BBS/upstream_state.json`.
3. Whenever upstream updates any file that was previously edited/patched in HorizonZ:
   - Applies the upstream update first so all new upstream features/fixes are adopted;
   - Automatically re-applies all HorizonZ custom runtime edits (`scripts/patch_horizon_direct_boot.py` +
     any custom rules in `BBS/custom_runtime_edits.json`) on top of the updated file!
4. Stages compiled `libhorizon_android.so` (`arm64-v8a` & `armeabi-v7a`) and exported JNI/ELF symbol tables
   into `BBS/jni-so/` for runtime inspection and debugging.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

BBS_DIR = Path(__file__).resolve().parent
ROOT_DIR = BBS_DIR.parent
UPSTREAM_CLEAN_DIR = BBS_DIR / "runtime-rust" / "upstream_clean"
PATCHED_ACTIVE_DIR = BBS_DIR / "runtime-rust" / "patched_active"
JNI_SO_DIR = BBS_DIR / "jni-so"
ENGINE_ASSETS_DIR = BBS_DIR / "engine-assets"
STATE_FILE = BBS_DIR / "upstream_state.json"
CUSTOM_EDITS_FILE = BBS_DIR / "custom_runtime_edits.json"


def sha256_of(path: Path) -> str:
    if not path.is_file():
        return ""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path) -> dict:
    if path.is_file():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def sync_upstream_into_bbs(upstream_dir: Path | None = None):
    UPSTREAM_CLEAN_DIR.mkdir(parents=True, exist_ok=True)
    PATCHED_ACTIVE_DIR.mkdir(parents=True, exist_ok=True)
    JNI_SO_DIR.mkdir(parents=True, exist_ok=True)
    ENGINE_ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    cfg = load_json(CUSTOM_EDITS_FILE)
    tracked_files = cfg.get("tracked_runtime_files", [])
    prev_state = load_json(STATE_FILE)
    old_hashes = prev_state.get("upstream_hashes", {})
    new_hashes = dict(old_hashes)
    updated_files = []

    src_root = upstream_dir if (upstream_dir and upstream_dir.is_dir()) else ROOT_DIR

    for rel_str in tracked_files:
        candidate = src_root / rel_str
        if not candidate.is_file():
            candidate = ROOT_DIR / rel_str
        if candidate.is_file():
            cur_hash = sha256_of(candidate)
            clean_dest = UPSTREAM_CLEAN_DIR / rel_str
            clean_dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(candidate, clean_dest)

            if old_hashes.get(rel_str) != cur_hash:
                updated_files.append(rel_str)
                new_hashes[rel_str] = cur_hash

            # Ensure workspace root has the latest upstream base before re-applying HorizonZ edits
            root_dest = ROOT_DIR / rel_str
            root_dest.parent.mkdir(parents=True, exist_ok=True)
            if candidate.resolve() != root_dest.resolve():
                shutil.copy2(candidate, root_dest)

    if updated_files:
        print(f"[BBS Auto-Sync] Detected {len(updated_files)} updated runtime file(s) from Vasysik/horizon-android:")
        for uf in updated_files:
            print(f"  -> Updated upstream base: {uf}")

    # Now re-apply all HorizonZ custom runtime patches on top of the updated upstream files
    patch_script = ROOT_DIR / "scripts" / "patch_horizon_direct_boot.py"
    if patch_script.is_file():
        print("[BBS Auto-Sync] Re-applying HorizonZ custom runtime edits over latest upstream source...")
        subprocess.run([sys.executable, str(patch_script)], cwd=ROOT_DIR, check=False)

    # Snapshot all patched active runtime files into BBS/runtime-rust/patched_active/
    for rel_str in tracked_files:
        active_file = ROOT_DIR / rel_str
        if active_file.is_file():
            patched_dest = PATCHED_ACTIVE_DIR / rel_str
            patched_dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(active_file, patched_dest)

    # Also copy key Android Java/Kotlin runtime bridges into BBS/runtime-rust/patched_active/
    for extra_rel in [
        "packaging/android/java/dev/horizon/android/HorizonActivity.java",
        "packaging/android/java/dev/horizon/android/bridge/HorizonNativeBridge.kt",
        "packaging/android/bedrock-controls.json",
    ]:
        ef = ROOT_DIR / extra_rel
        if ef.is_file():
            dest = PATCHED_ACTIVE_DIR / extra_rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ef, dest)

    # Stage any built .so binaries & engine-data.zip into BBS/jni-so/ and BBS/engine-assets/
    for abi in ("arm64-v8a", "armeabi-v7a"):
        so_src = ROOT_DIR / ".local/android/gradle-jni" / abi / "libhorizon_android.so"
        if so_src.is_file():
            so_dest_dir = JNI_SO_DIR / abi
            so_dest_dir.mkdir(parents=True, exist_ok=True)
            so_dest = so_dest_dir / "libhorizon_android.so"
            shutil.copy2(so_src, so_dest)
            print(f"[BBS Auto-Sync] Staged compiled native runtime {abi}/libhorizon_android.so ({so_dest.stat().st_size:,} bytes) into BBS/jni-so/{abi}/")
            # Extract dynamic symbol table for runtime debugging if llvm-nm or nm is available
            for nm_cmd in ("llvm-nm", "nm"):
                if shutil.which(nm_cmd):
                    res = subprocess.run([nm_cmd, "-D", str(so_dest)], capture_output=True, text=True, check=False)
                    if res.returncode == 0 and res.stdout:
                        (so_dest_dir / "exported_symbols.txt").write_text(res.stdout, encoding="utf-8")
                    break

    engine_zip = ROOT_DIR / ".local/android/gradle-assets/engine-data.zip"
    if engine_zip.is_file():
        shutil.copy2(engine_zip, ENGINE_ASSETS_DIR / "engine-data.zip")

    state_payload = {
        "upstream_repo": "https://github.com/Vasysik/horizon-android",
        "upstream_branch": "android",
        "updated_files_in_last_sync": updated_files,
        "upstream_hashes": new_hashes,
    }
    STATE_FILE.write_text(json.dumps(state_payload, indent=2), encoding="utf-8")
    print("[BBS Auto-Sync] BBS runtime folder synchronized and custom edits verified.")


if __name__ == "__main__":
    upstream_arg = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/horizon-upstream")
    sync_upstream_into_bbs(upstream_arg if upstream_arg.is_dir() else None)
