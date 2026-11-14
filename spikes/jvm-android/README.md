# jvm-android (Step 1 on the phone)

A private test APK that boots a Java 25 runtime in its own Android process and shows the numbers on screen.
Built only by `.github/workflows/jvm-android-probe.yml`; not part of the game, not published.

What it contains, and where each part comes from:

| Part | Source |
|---|---|
| `src/main/kotlin` | this repo: one screen, the `:jvm` service, the runtime copier |
| `libjvm_boot_spike.so` | `spikes/jvm-boot` built as a cdylib for aarch64 (`src/android.rs` is the JNI entry) |
| `assets/jre25/**` | ZalithLauncher2 `runtimes/jre-25` (`universal.tar.xz` + `bin-arm64.tar.xz`), downloaded in CI at commit `6dee5eb`, unpacked by `ci/stage_runtime.py`. GPL-3.0 licensed runtime of unknown provenance; never committed, no Zalith source copied |
| `assets/probe.jar` | `jvmprobe/Probe.java.in`, compiled in CI (the public-tree check rejects tracked `.java`) |

Two ways to boot, one button each. A process can hold one JVM, so each tap ends the old `:jvm` process and starts a new one.

- **JNI_CreateJavaVM**: dlopen `lib/server/libjvm.so`, as the desktop spike does. Untried on Android.
- **JLI_Launch**: dlopen `lib/libjli.so`, run `dev.horizonr.jvmprobe.Probe`, as ZalithLauncher2 starts its JVM.

A third button runs the Fabric 26.3 server (config `clumps` of `spikes/jvm-server-smoke`) in the `:jvm` process, so the
desktop smoke test and the phone give comparable results:

- It asks once before writing `eula.txt` (your acceptance of https://aka.ms/MinecraftEULA) and runs as a foreground service.
- It downloads the Fabric server launcher and the pinned Clumps jar (SHA-512 checked) into `filesDir/mcserver`; the Fabric
  launcher downloads Mojang's server files there itself. Nothing is bundled or committed.
- `JLI_Launch` runs `java -Xmx1024m ... -jar fabric-server.jar nogui` with `mcserver` as the working directory.
- A monitor thread waits for vanilla's `Done (...s)!` line, idles 10 s, records RSS and peak RSS, then sends SIGTERM so
  HotSpot runs Minecraft's shutdown hook. The screen reads the log with the same rules as `summarize.py`.
- Server settings are `run_smoke.sh`'s, plus `server-ip=127.0.0.1` so the server is not reachable from the network.

Start a build by bumping the number in `run.json` and pushing to `jvm-mod-runtime`. The APK is the Actions artifact
`jvm-android-probe-apk` (1 day retention).

## Not verified (nothing here was built or run when this was written)

- That any of it compiles: Kotlin, Rust (JNI module, `JLI_Launch` prototype recalled from OpenJDK's `java.h`), Gradle.
- That the Zalith tarballs sit at `runtimes/jre-25/{universal,bin-arm64}.tar.xz` at that commit and hold `lib/modules`,
  `lib/libjli.so`, `lib/server/libjvm.so` (the workflow fails with an annotation if not).
- That HotSpot starts next to ART in an app process, that `dlopen` from `filesDir` is allowed, that `libjvm.so` finds its
  sibling libraries without `LD_LIBRARY_PATH`, and that `JLI_Launch` finds the runtime home from `libjli.so`'s location.
  Each is a possible failure on the first tap; the screen then shows the error, the JVM's stdout/stderr and logcat lines
  (whether logcat is readable by a normal app on this phone is also unverified).
- Heap 128 MiB, an 8 MiB creating thread and default GC flags are untuned choices.
- `targetSdk` was 28 in builds 7 to 13, chosen to avoid the exec restrictions of newer targets; build 14 targets 35 (like the launcher) to see whether the JVM still boots from filesDir. Zalith's own target is not checked.

## Not verified for the server run (written after builds 7 to 9 were run on the phone)

- That any of the server-run Kotlin compiles: it was written without an Android SDK. The Rust side builds and its unit tests
  pass on the host (Linux x86_64), not for the Android target.
- That the JRE has a usable `lib/security/cacerts`. If it has none, the Android CA store is exported to a PKCS12 file and passed
  as the trust store; whether that works with this JRE is untested. The screen shows which one was used.
- That HotSpot handles SIGTERM next to ART. If it does not, the process is killed after 30 s and the screen says so.
- Heap 1024 MiB (the CI runner's value), the 900 s start timeout, the 10 s idle time and the 30 s stop timeout are guesses.
- Whether Clumps applies any mixin on the server: the screen counts log lines that mention Mixin, which is a hint only.
