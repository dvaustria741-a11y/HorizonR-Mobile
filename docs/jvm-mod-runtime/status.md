# JVM mod runtime: status (2026-11-14)

Goal (user): run Fabric mods built for Minecraft 26.3 without porting, keeping the Rust/wgpu engine.
Scope (user): Fabric loader only, 26.3 only. The runtime must stay general; it is not built around the sample mods.
Rule (user): do not watch or babysit CI after a push. Never put the token in memory files or the repo.

## Done
- Step 0: repo read, notes in `step0.md`.
- Step 1 (partial): `spikes/jvm-boot` boots a libjvm.so over JNI. Desktop only: Linux x86_64, OpenJDK 25,
  1.4 s boot, 36 MiB RSS at -Xmx128m. Not built for Android, never run on the phone.
- Step 2 (partial): `.github/workflows/jvm-server-smoke.yml` + `spikes/jvm-server-smoke`. Run #1 (commit 64a26c7,
  config `clumps`) passed: annotation said PASS, Done in 2.380 s, "Loading 5 mods" with clumps 26.3.2 and
  fabricloader 0.20.1 named (the other three were not captured). That is a GitHub Linux runner, not Android,
  and proves startup only, not mod behaviour. Not done: a Rust client joining on protocol 777.
- Step 3 (sample): `spikes/mod-api-scan`. AppleSkin 3.0.10 55 classes/68 methods/19 fields, Clumps 26.3.2
  13/21/2, Xaero's Minimap 26.6.2 353/566/155 (+ nested xaerolib 137/250/31). Union of the three: 385 classes,
  634 methods, 169 fields. Mixin shapes seen: Inject, ModifyVariable, ModifyArg, ModifyReturnValue, Accessor, Shadow.
  Fabric API 0.166.2 modules reference 841 distinct net/minecraft classes. All are lower bounds.

## Facts derived from files
- Engine = Minecraft 26.3, protocol 777. The Mojang 26.3 server jar needs Java 25 (its version.json); Clumps
  declares java >=25. Fabric API manifest says mapping namespace `official`.
- Android already has a Java 25 runtime in Zalith Launcher (user's phone: built-in 25.0.3 plus 21, 17, 8).
  ZalithLauncher2 (GPL-3.0) ships `runtimes/jre-25`: universal 32.8 MB + arm64 5.45 MB (xz), arm64 part has
  lib/server/libjvm.so and lib/libjli.so; release file says 25.0.5. Its JVM runs in its own Android process
  (":jvm"/":game") started through libjli.so JLI_Launch. Provenance of that JRE build is unknown.
- docs/mods.md and docs/android-0.7.6.md: Java Fabric/Forge/NeoForge mods are unsupported today; `.hnmod`
  compile on Android is unsupported; `.hmod` (wasmtime) runs.
- User's phone (screenshot): Redmi Note 11 Pro 5G, 7.26 GB total memory, about 2.5 GB free with Zalith open.

## Assumptions (unverified)
- HotSpot can run in an Android process next to ART when it lives in its own process (Zalith suggests yes).
- The Fabric launcher URL in `spikes/jvm-server-smoke/run_smoke.sh` is right (it worked in run #1).

## Missing
- Any on-device number: APK size with a runtime, RAM, JVM start time.
- Which Android Java 25 build Horizon should ship (own build vs download). Licence of the final choice.
- A facade design; whether it is realistic is the decision after Steps 1 to 3.

## Next step (decided)
Step 1 on the phone. Written, not built and not run: `.github/workflows/jvm-android-probe.yml` builds the test APK in
`spikes/jvm-android` (details and the unverified list in its README). It bundles `spikes/jvm-boot` (cdylib, aarch64) and
Zalith's arm64 jre-25 tarballs downloaded at build time (never committed). Two boot paths, `JNI_CreateJavaVM` and
`JLI_Launch`, each in a fresh `:jvm` process. The screen shows boot time, RSS before/after, java.version and APK size.
To report back: the numbers or the error text on screen.
