# jvm-boot spike (Step 1)

Loads a `libjvm.so` you name, creates a JVM in-process through `JNI_CreateJavaVM`, calls
`System.getProperty`, and prints boot time and resident memory before and after.

    cargo build --release
    target/release/jvm-boot-spike <path to libjvm.so> [--heap-mib N]

Checked on a Linux x86_64 desktop with OpenJDK 25 only (host toolchain Rust 1.85, not the repo's pinned 1.95).
Not built for Android and not run on a device. Open items:

- Which runtime supplies `libjvm.so` on Android, its licence and its size: unknown.
- Whether HotSpot can start inside the ART process (signal handlers, bionic): unverified. The alternative is
  to run the runtime's `java` as a child process and talk to it over a socket.
- A process can create one JVM only; there is no teardown.
