# jvm-boot spike (Step 1)

Loads a `libjvm.so` you name, creates a JVM in-process through `JNI_CreateJavaVM`, calls
`System.getProperty`, and prints boot time and resident memory before and after.

    cargo build --release
    target/release/jvm-boot-spike <path to libjvm.so> [--heap-mib N]

Checked on a Linux x86_64 desktop with OpenJDK 25 only (host toolchain Rust 1.85, not the repo's pinned 1.95).
Not built for Android and not run on a device. Open items:

- Runtime source: ZalithLauncher2 (GPL-3.0, clone at 6dee5eb, 2026-11-13) bundles `runtimes/jre-25` as assets:
  `universal.tar.xz` 32.8 MB + `bin-arm64.tar.xz` 5.45 MB (xz, as stored in its git tree). The arm64 part holds
  `lib/server/libjvm.so` (ELF aarch64) and `lib/libjli.so`; its `release` file says 25.0.5, built 2026-10-20 on an
  "internal-adhoc.runner" build. Who built it and from what sources is not stated in the repo: unknown.
  The user's phone showed Zalith's built-in "Internal 25 (25.0.3)" in its runtime list.
- Process model in Zalith (from its AndroidManifest and `jre_launcher.c`): the JVM runs in its own Android process
  (`android:process=":jvm"` / `":game"`) and starts through `dlopen("libjli.so")` plus `JLI_Launch`, with a SIGABRT
  handler installed around it. This spike calls `JNI_CreateJavaVM` on `libjvm.so` directly instead: not tried on
  Android. Do not copy Zalith code into this MIT repo; its licence is GPL-3.0.
- A process can create one JVM only; there is no teardown.
- Not measured: APK size with the runtime bundled, RAM on the device, JVM start time on the device.
