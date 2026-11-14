# JVM server smoke (Step 2)

Boots a Fabric 26.3 server (Java 25) with mods pinned by hash in `mods.json`, in GitHub Actions, and reports the
result as annotations. The point: find out whether the mods start on a real JVM server, and give the later
Rust-client join test (protocol 777) a known-good server to connect to.

Start it by adding `spikes/jvm-server-smoke/run.json` on `jvm-mod-runtime` and pushing:

    {"config": "clumps", "accept_mojang_eula": true}

`accept_mojang_eula: true` writes `eula.txt`; it is your acceptance of https://aka.ms/MinecraftEULA, not ours.
Configs: `clumps`, `clumps-fabric-api-placeholder` (see `mods.json`).

Checked offline only (scripts' unit tests, YAML and shell syntax). Never run on a runner. Unverified:
- The Fabric launcher URL in `run_smoke.sh` (documented Fabric meta endpoint, built from the launcher jar's versions).
- That Modrinth knows the pinned hashes (a missing hash fails visibly).
- The log patterns in `summarize.py`: PASS needs vanilla's "Done (...)" line and no ERROR/Exception lines, so a
  benign exception in a log reads as FAIL.

Not covered: a Rust client joining, and any mod behaviour beyond starting.
