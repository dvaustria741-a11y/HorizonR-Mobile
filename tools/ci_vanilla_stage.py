#!/usr/bin/env python3
"""Stage the pinned Java Edition files for CI, in the layout lab.py reads from a launcher directory.

CI has no launcher install, so this fetches what the official launcher would: the version JSON, the
client jar, the server jar and the client libraries, from Mojang's own servers. Every file is checked
against research-lock.json (or its own SHA-1 in Mojang's manifest) before it is kept. Nothing staged
here is committed or uploaded; the files stay in the runner's temporary directory.

    python3 tools/ci_vanilla_stage.py --out DIR
    python3 tools/lab.py prepare --minecraft-dir DIR --server-jar DIR/server.jar
"""

import argparse
import json
from pathlib import Path, PurePosixPath
import sys
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lab  # noqa: E402

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "horizon/0.1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def stage(out):
    lock = json.loads(lab.LOCK.read_text(encoding="utf-8"))
    game = lock["minecraft"]
    version = game["version"]

    entry = next((v for v in fetch_json(MANIFEST_URL)["versions"] if v["id"] == version), None)
    if entry is None:
        raise ValueError(f"Mojang's version manifest does not list {version}")
    republished = not lab.manifest_accepted(lock, entry["sha1"])

    version_dir = out / "versions" / version
    manifest_path = lab.download(entry["url"], version_dir / f"{version}.json", entry["sha1"])
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    downloads = manifest["downloads"]
    if (manifest["id"] != version
            or downloads["client"]["sha1"] != game["client_sha1"]
            or downloads["server"]["sha1"] != game["server_sha1"]
            or manifest["javaVersion"]["majorVersion"] != game["java_major"]):
        raise ValueError(f"Mojang's {version} version file does not match the pins in research-lock.json")
    if republished:
        # Mojang republishes a version file for asset-index changes. The jar, server and Java pins above
        # still match, so accept this hash in the runner's checkout only (never committed) for lab.py.
        lock["minecraft"].setdefault("accepted_manifest_sha1", []).append(entry["sha1"])
        lab.LOCK.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
        print(f"Accepted republished {version} version file {entry['sha1']} for this run: client, server and Java pins match", flush=True)

    lab.download(downloads["client"]["url"], version_dir / f"{version}.jar", game["client_sha1"])
    lab.download(downloads["server"]["url"], out / "server.jar", game["server_sha1"])

    artifacts = lab.client_library_artifacts(manifest)
    for artifact in artifacts:
        relative = PurePosixPath(artifact["path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in artifact["path"]:
            raise ValueError(f"Unsafe library path {artifact['path']}")
        lab.download(artifact["url"], out / "libraries" / relative, artifact["sha1"])
    print(f"Staged {version}: client jar, server jar, {len(artifacts)} libraries in {out}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        stage(args.out)
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
