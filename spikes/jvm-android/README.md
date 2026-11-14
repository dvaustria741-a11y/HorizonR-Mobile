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
- `targetSdk 28` was chosen to avoid the exec restrictions of newer targets; Zalith's own target is not checked.
