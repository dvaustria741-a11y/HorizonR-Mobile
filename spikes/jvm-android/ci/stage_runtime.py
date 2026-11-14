#!/usr/bin/env python3
"""Stage the ZalithLauncher2 Java 25 runtime for the probe APK (CI only, nothing here is committed).

    stage_runtime.py <universal.tar.xz> <bin-arm64.tar.xz> <assets/jre25 dir>

Both tarballs are unpacked over each other into one tree. Symlinks are replaced by copies of their target
(APK assets cannot hold links). A manifest.txt with `<size><TAB><relative path>` per file is written for the
app's installer. Facts about the layout go to stdout as GitHub annotations so they are readable without the log.
Fails when lib/server/libjvm.so, lib/libjli.so or lib/modules is missing: the probe cannot boot without them.
"""
import hashlib
import os
import posixpath
import shutil
import sys
import tarfile

REQUIRED = ("lib/server/libjvm.so", "lib/libjli.so", "lib/modules")
# Markers used to find the runtime root inside a tarball whether or not it has a top-level folder.
ROOT_MARKERS = ("lib/libjli.so", "lib/modules", "release", "lib/server/libjvm.so")


def find_prefix(names):
    """Prefix to strip from every member name so that the runtime root is the tree root; None if no marker found.

    The shortest prefix wins, so a stray nested copy cannot shift the root.
    """
    best = None
    for name in names:
        clean = name[2:] if name.startswith("./") else name
        for marker in ROOT_MARKERS:
            if clean == marker:
                prefix = ""
            elif clean.endswith("/" + marker):
                prefix = clean[: -len(marker)]
            else:
                continue
            if best is None or len(prefix) < len(best):
                best = prefix
    return best


def safe_relative(path):
    """Normalised relative POSIX path, or None when it is empty, absolute or climbs out of the tree."""
    clean = posixpath.normpath(path)
    if clean in ("", ".") or clean.startswith("/") or clean == ".." or clean.startswith("../"):
        return None
    return clean


def manifest_lines(files):
    """`<size>\t<path>` lines for (path, size) pairs, sorted by path."""
    return [f"{size}\t{path}" for path, size in sorted(files)]


def extract(tar_path, out_dir):
    """Unpack one tarball under out_dir with the runtime root as root. Returns [(relative path, link target)]."""
    links = []
    with tarfile.open(tar_path, "r:xz") as tar:
        members = tar.getmembers()
        prefix = find_prefix([m.name for m in members])
        if prefix is None:
            raise SystemExit(f"::error::{os.path.basename(tar_path)}: no runtime root marker {ROOT_MARKERS} found")
        for member in members:
            name = member.name[2:] if member.name.startswith("./") else member.name
            if not name.startswith(prefix):
                continue
            rel = safe_relative(name[len(prefix):])
            if rel is None:
                continue
            target = os.path.join(out_dir, *rel.split("/"))
            if member.isdir():
                os.makedirs(target, exist_ok=True)
            elif member.isreg():
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with tar.extractfile(member) as source, open(target, "wb") as dest:
                    shutil.copyfileobj(source, dest)
            elif member.issym():
                links.append((rel, member.linkname))
            elif member.islnk():
                # A hard link names its target from the archive root; "/" marks "from the runtime root" below.
                hard = member.linkname[2:] if member.linkname.startswith("./") else member.linkname
                if hard.startswith(prefix):
                    links.append((rel, "/" + hard[len(prefix):]))
            # devices, fifos: not expected in a JRE, skipped on purpose
    return links


def resolve_links(out_dir, links):
    """Replace links by copies of the file they point to. Returns the links that point nowhere."""
    dangling = []
    for rel, linkname in links:
        if linkname.startswith("/"):
            candidate = safe_relative(linkname.lstrip("/"))
        else:
            candidate = safe_relative(posixpath.join(posixpath.dirname(rel), linkname))
        source = os.path.join(out_dir, *candidate.split("/")) if candidate else None
        if source and os.path.isfile(source):
            dest = os.path.join(out_dir, *rel.split("/"))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copyfile(source, dest)
        else:
            dangling.append((rel, linkname))
    return dangling


def walk_files(out_dir):
    for root, _dirs, names in os.walk(out_dir):
        for name in names:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, out_dir).replace(os.sep, "/")
            if rel != "manifest.txt":
                yield rel, os.path.getsize(full)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def notice(title, lines):
    # %0A is how a GitHub annotation carries a newline.
    print(f"::notice title={title}::" + "%0A".join(lines))


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2
    universal, arm64, out_dir = argv[1:]
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)
    notice("Zalith tarballs", [f"{os.path.basename(p)} {os.path.getsize(p)} bytes sha256 {sha256(p)}" for p in (universal, arm64)])
    links = extract(universal, out_dir) + extract(arm64, out_dir)
    dangling = resolve_links(out_dir, links)
    files = list(walk_files(out_dir))
    top = {}
    for rel, size in files:
        key = rel.split("/")[0] if "/" in rel else "(root files)"
        count, total = top.get(key, (0, 0))
        top[key] = (count + 1, total + size)
    layout = [f"{key}: {count} files, {total} bytes" for key, (count, total) in sorted(top.items())]
    layout.append(f"links replaced by copies: {len(links) - len(dangling)}; dangling links dropped: {len(dangling)}")
    layout.extend(f"dangling {rel} -> {target}" for rel, target in dangling[:10])
    libs = sorted(rel for rel, _ in files if rel.startswith("lib/") and rel.count("/") == 1 and rel.endswith(".so"))
    layout.append("lib/*.so: " + " ".join(os.path.basename(p) for p in libs))
    notice("Runtime layout", layout)
    present = {rel for rel, _ in files}
    missing = [rel for rel in REQUIRED if rel not in present]
    if missing:
        print(f"::error title=Runtime incomplete::missing {', '.join(missing)}")
        return 1
    with open(os.path.join(out_dir, "manifest.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(manifest_lines(files)) + "\n")
    print(f"staged {len(files)} files, {sum(size for _, size in files)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
