# JVM mod runtime: status (2026-11-14)

Goal (user): run Fabric mods built for Minecraft 26.3 without porting, keeping the Rust/wgpu engine.
Scope (user): Fabric loader only, 26.3 only. The runtime must stay general; it is not built around the sample mods.
Rule (user): do not watch or babysit CI after a push. Never put the token in memory files or the repo.

## Done
- Step 0: repo read, notes in `step0.md`.
- Step 1 (partial): `spikes/jvm-boot` boots a libjvm.so over JNI. Desktop only: Linux x86_64, OpenJDK 25,
  1.4 s boot, 36 MiB RSS at -Xmx128m. Not built for Android, never run on the phone.
- Step 1 on the phone (user-reported, Redmi Note 11 Pro 5G, Android 13, builds 7 to 9; not rerun by me): HotSpot 25.0.5
  boots next to ART in a `:jvm` process through both paths. JNI_CreateJavaVM: 402 ms for the call alone, RSS 79.0 to 96.6 MiB
  at -Xmx128m. JLI_Launch with `probe.jar`: 489 ms to `Probe.main`, RSS 70.8 to 101.9 MiB, 3 available processors. The
  runtime's libraries need preloading by absolute path (libjvm, libjava, libnet, libnio) because Android's linker does not
  search the loading library's directory. The foreground-service button and how long Android keeps a busy `:jvm` alive are
  untested. These numbers are a floor: no Fabric, Mixin or Minecraft class had loaded.
- Step 2 (partial): `.github/workflows/jvm-server-smoke.yml` + `spikes/jvm-server-smoke`. Run #1 (commit 64a26c7,
  config `clumps`) passed: annotation said PASS, Done in 2.380 s, "Loading 5 mods" with clumps 26.3.2 and
  fabricloader 0.20.1 named (the other three were not captured). That is a GitHub Linux runner, not Android,
  and proves startup only, not mod behaviour. Not done: a Rust client joining on protocol 777.
- Step 2 on the phone (Redmi Note 11 Pro 5G, Android 13), user-reported builds 10 to 12: the Fabric 26.3 server with
  Clumps boots through JLI_Launch in the `:jvm` process, G1, -Xmx1024m. Server log Done 14.443 s and 16.051 s in two
  runs (12.641 s in the first, cold); about 82 to 85 s from service start to Done when warm. RSS 10 s after Done
  574.7 to 596.8 MiB, peak 578.1 to 609.1 MiB, 63 to 65 threads. Available processors vary between runs (3, 5, 7 of 8;
  cause unknown). A status ping to 127.0.0.1:25565 returns version name 26.3 and protocol 777 (155 ms), which matches the
  engine's protocol. MixinExtras 0.5.5 initialises; Mixin read `clumps.mixins.json` (refmap warning); whether any mixin
  applied is not shown. Known failure: Minecraft's `SystemReport` (oshi, JNA) cannot load the glibc `libjnidispatch`
  (`libc.so.6` not found); non-fatal, the server still reaches Done. SIGTERM ends the JVM with status 143, but no
  "Stopping server" line appears in `latest.log`: a clean stop is not shown yet (build 13 sends `stop` on stdin instead).
  Not done: a Horizon client joining this server; how long Android keeps a busy `:jvm` alive in the background.
- Step 2 on the phone, build 14 run (user-uploaded probe files, 2026-11-14 21:25 device time; I read them, did not run
  them): APK 63.0 MiB (66,045,221 bytes); runtime copy 291 files, 158.8 MiB in 840 ms; device RAM 7432.1 MiB total,
  3081.5 MiB available; UI process RSS 49.0 MiB. Server `Done` 14.709 s in the log, 79.7 s from service start. RSS 10 s
  after Done 657.3 MiB, peak 663.4 MiB, 58 threads, G1, -Xmx1024m, 5 CPUs available. Status ping 901 ms: version 26.3,
  protocol 777. Mod list in the log: clumps 26.3.2, fabricloader 0.20.1 (mixinextras 0.5.5), java 25, minecraft 26.3.
  The `stop` console command now ends cleanly: "Stopping server" and the saves are in the log, JLI_Launch returned
  exit code 0 after 89.1 s. The app's verdict line printed "FAIL or not finished" even though Done was reached; I infer
  it is the ERROR line from the JNA failure above (the app source was not checked). Same JNA/`libc.so.6` error as before.
- Second build 14 run (user-uploaded report, 21:37 device time): Done 14.947 s in the log, 59.6 s from service start
  with the launcher and mod cached; RSS 10 s after Done 600.2 MiB, peak 606.6 MiB, 59 threads; ping 50 ms, protocol 777.
  The probe stops the server 10 s after Done (`SETTLE_SECONDS`, by design for measuring), so the HorizonR entry "probe"
  read "Offline or unreachable" and a join was impossible. Android also killed the probe's UI process for low memory at
  21:32:58 (exit reason LOW_MEMORY, importance 400) while HorizonR was in use; the foreground `:jvm` process was not
  killed by it. Build 15 adds "Run server and keep it running (join test)" and a "Stop the server" button; unbuilt.
- Join attempt (user, 21:52 and 21:53 device time, build 15, exports 21:56): HorizonR's Multiplayer entry "probe"
  (127.0.0.1:25565) reached our Fabric 26.3 server. The server log shows player ZensoQP1 from 127.0.0.1 disconnected
  with "You are not white-listed on this server!" twice (and a "handleDisconnection() called twice" warning). So the
  client's handshake and login hello on protocol 777 worked up to the whitelist check; the cause of the whitelist is
  unknown (the probe writes no whitelist setting; build 16 sets `white-list=false` and `enforce-whitelist=false`).
  Also in that run: the JVM process's last heartbeat showed RSS 82 MiB (from about 600 MiB) and the report says it died
  without a result; Android's exit record for it was not in the export yet, so the cause is unconfirmed. Earlier exit
  records: probe UI process LOW_MEMORY at 21:52:10, an earlier `:jvm` SIGKILL at 21:50:10.
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
- A Horizon client joining the phone's server (Multiplayer screen or the `connect` agent command, per docs/agent-control.md).
  Memory with the game running next to a ~660 MiB server on a 7.4 GiB phone (about 3 GiB available) is unmeasured.
- Whether any Clumps mixin applied (the log does not say), and mods that use JNA or other glibc natives.
- How long Android keeps a busy `:jvm` process alive in the background.
- Which Android Java 25 build Horizon should ship (own build vs download) and its licence. The probe still uses
  Zalith's tarballs downloaded at build time.
- A facade design for client-side mods. Server-side Fabric mods need none: the real Mojang server runs on the JVM.

## Next step
Join test (needs build 15): start the server with the keep-running button, open Horizon on the same phone, add 127.0.0.1:25565 in Multiplayer, join,
and report what happens (success, kick text, crash). That closes the protocol-777 question of Step 2.
