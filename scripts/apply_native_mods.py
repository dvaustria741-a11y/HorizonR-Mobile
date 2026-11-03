#!/usr/bin/env python3
"""Apply the native mods in native-mods/*.hnmod to the engine source before it is built.

A native mod (.hnmod) is a zip of Rust mixin bundles (mixins/<id>/horizon-mixin.toml + helpers)
that horizon-mixins applies to the game's own source at build time. They cannot be loaded into a
compiled app, so the APK workflow runs this script before `cargo build`.

Usage:
    python3 scripts/apply_native_mods.py apply    # apply every native-mods/*.hnmod to crates/
    python3 scripts/apply_native_mods.py revert   # restore the files `apply` changed

`apply` is all-or-nothing: if the tool fails, the engine is left exactly as it was.
Exit code is 0 either way so the build can fall back to the plain engine; read the log.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODS_DIR = ROOT / "native-mods"
BACKUP = Path(os.environ.get("NATIVE_MODS_BACKUP", "/tmp/native-mods-backup"))
TOOL_DIR = Path(os.environ.get("HORIZON_MIXINS_DIR", "/tmp/horizon-mixins-tool"))
TOOL_REPO = "https://github.com/horizon-rust/horizon-mixins.git"
SKIP_DIRS = {".git", "target", ".local", "node_modules"}
# Files the staged workspace can change. Everything else is left alone.
TOUCHED_ROOT_FILES = ("Cargo.toml", "Cargo.lock")


def log(msg):
    print(f"[native-mods] {msg}", flush=True)


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def unpack_bundles(work: Path):
    """Unzip each .hnmod; return [(bundle_dir, horizon_revision)]."""
    bundles = []
    mods_in = work / "mods-in"
    mods_in.mkdir(parents=True)
    for hn in sorted(MODS_DIR.glob("*.hnmod")):
        out = work / "unz" / hn.stem
        with zipfile.ZipFile(hn) as z:
            z.extractall(out)
        found = sorted((out / "mixins").glob("*/horizon-mixin.toml"))
        if not found:
            log(f"{hn.name}: no mixins/<id>/horizon-mixin.toml inside, skipped")
            continue
        for toml_path in found:
            bdir = toml_path.parent
            dest = mods_in / bdir.name
            shutil.copytree(bdir, dest)
            data = tomllib.loads(toml_path.read_text(encoding="utf-8"))
            rev = (data.get("compatibility") or {}).get("horizon_revision")
            bundles.append((dest, rev, data.get("mod", {}).get("id", bdir.name)))
            log(f"{hn.name}: bundle {bdir.name} pinned to {rev}")
    return mods_in, bundles


def build_tool():
    exe = TOOL_DIR / "target/release/horizon-mixins"
    if exe.is_file():
        return exe
    shutil.rmtree(TOOL_DIR, ignore_errors=True)
    r = run(["git", "clone", "--depth", "1", TOOL_REPO, str(TOOL_DIR)])
    if r.returncode:
        log("could not clone horizon-mixins: " + r.stderr.strip()[-400:])
        return None
    # Build with the runner's stable cargo; the tool's own toolchain pin is not needed to run it.
    r = run(["cargo", "build", "--release", "-p", "horizon-mixins-cli",
             "--manifest-path", str(TOOL_DIR / "Cargo.toml")], cwd=TOOL_DIR)
    if r.returncode or not exe.is_file():
        log("could not build horizon-mixins:\n" + (r.stderr or r.stdout)[-1500:])
        return None
    return exe


def copy_workspace(dst: Path):
    """Copy the game workspace without symlinks (the tool rejects them) or build output."""
    def ignore(_d, names):
        return [n for n in names if n in SKIP_DIRS]
    shutil.copytree(ROOT, dst, ignore=ignore, symlinks=False, ignore_dangling_symlinks=True)
    for p in dst.rglob("*"):
        if p.is_symlink():
            p.unlink()


def add_android_deps(stage: Path, target_cargo: Path):
    """horizon-android compiles the client sources as its own crate, so every crate a mod adds to
    horizon-client must be a dependency of horizon-android too."""
    client = (stage / "crates/horizon-client/Cargo.toml").read_text(encoding="utf-8")
    orig = (ROOT / "crates/horizon-client/Cargo.toml").read_text(encoding="utf-8")
    added = [l for l in client.splitlines() if l not in orig.splitlines() and "native-crates" in l]
    if not added:
        return
    text = target_cargo.read_text(encoding="utf-8")
    fixed = []
    for line in added:
        # ../../native-crates/x -> ../../native-crates/x (same depth: crates/<name>/Cargo.toml)
        if line.split("=")[0].strip() not in text:
            fixed.append(line)
    if fixed:
        marker = "[dependencies]\n"
        text = text.replace(marker, marker + "\n".join(fixed) + "\n", 1)
        target_cargo.write_text(text, encoding="utf-8")
        log("added to horizon-android: " + ", ".join(l.split("=")[0].strip() for l in fixed))


def changed_files(before: Path, after: Path):
    out = []
    for p in after.rglob("*"):
        if not p.is_file() or p.name == ".horizon-mixins-stage.json":
            continue
        rel = p.relative_to(after)
        old = before / rel
        if rel.parts[0] == ".horizon-revision":
            continue
        if not old.is_file() or old.read_bytes() != p.read_bytes():
            out.append(rel)
    return out


def apply():
    if not list(MODS_DIR.glob("*.hnmod")):
        log("no native-mods/*.hnmod, nothing to do")
        return 0
    shutil.rmtree(BACKUP, ignore_errors=True)
    work = Path(tempfile.mkdtemp(prefix="native-mods-"))
    try:
        mods_in, bundles = unpack_bundles(work)
        if not bundles:
            return 0
        exe = build_tool()
        if exe is None:
            return 0
        ws = work / "ws"
        copy_workspace(ws)
        revs = {r for _, r, _ in bundles if r}
        if len(revs) > 1:
            log("bundles pin different Horizon revisions: " + ", ".join(sorted(revs)))
            return 0
        if revs:
            # The pin names the Horizon commit the mod was made for. This engine is a newer or
            # patched copy of it, so tell the tool the pin matches ("Build anyway").
            (ws / ".horizon-revision").write_text(next(iter(revs)) + "\n")
        stage = work / "stage"
        r = run([str(exe), "apply", "--workspace", str(ws), "--mods", str(mods_in), "--out", str(stage)])
        if r.returncode:
            log("apply failed (engine left unchanged):\n" + (r.stderr or r.stdout)[-2000:])
            return 0
        add_android_deps(stage, stage / "crates/horizon-android/Cargo.toml")
        files = changed_files(ws, stage)
        # Back up the originals so `revert` can undo this.
        BACKUP.mkdir(parents=True)
        manifest = {"changed": [], "added": []}
        for rel in files:
            src = ROOT / rel
            if src.is_file():
                (BACKUP / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, BACKUP / rel)
                manifest["changed"].append(str(rel))
            else:
                manifest["added"].append(str(rel))
        (BACKUP / "manifest.json").write_text(json.dumps(manifest))
        for rel in files:
            (ROOT / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(stage / rel, ROOT / rel)
        log(f"applied {len(bundles)} bundle(s): {len(manifest['changed'])} files changed, "
            f"{len(manifest['added'])} added")
        return 0
    except Exception as exc:  # never break the build over an optional step
        log(f"unexpected error, reverting: {exc}")
        revert()
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


def revert():
    mf = BACKUP / "manifest.json"
    if not mf.is_file():
        log("nothing to revert")
        return 0
    m = json.loads(mf.read_text())
    for rel in m["changed"]:
        shutil.copy2(BACKUP / rel, ROOT / rel)
    for rel in m["added"]:
        p = ROOT / rel
        if p.is_file():
            p.unlink()
    for rel in m["added"]:
        d = (ROOT / rel).parent
        while d != ROOT and d.is_dir() and not any(d.iterdir()):
            d.rmdir()
            d = d.parent
    shutil.rmtree(BACKUP, ignore_errors=True)
    log("reverted to the plain engine")
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "apply"
    sys.exit(apply() if cmd == "apply" else revert() if cmd == "revert" else 2)
