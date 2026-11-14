//! JNI entry point for the Android test app (`spikes/jvm-android`), called in its `:jvm` process as
//! `dev.horizonr.jvmprobe.NativeBoot.run`. Compiles on any Unix host so `cargo check` covers it; only the
//! app calls it.
//!
//! Every fact is appended to `<work_dir>/native.txt` the moment it is known, because the JDK launcher can
//! `exit()` or the process can die inside the JVM, and a return value would never arrive.
use std::{
    fmt::Display,
    fs::OpenOptions,
    io::Write,
    os::unix::io::AsRawFd,
    panic::{catch_unwind, AssertUnwindSafe},
    path::{Path, PathBuf},
    time::Instant,
};

use jni::{
    objects::{JObject, JString},
    sys::jint,
    JNIEnv,
};

use crate::{
    current_rss_kib, epoch_ms, jli_launch, launcher_argv, preload, server_argv, BootError, Jvm, JvmConfig,
    LauncherArgs, ServerArgs, LIBJLI_REL, LIBJVM_REL, PRELOAD_LIBS,
};

/// `JNI_CreateJavaVM` on `lib/server/libjvm.so` (what `spikes/jvm-boot` does on desktop).
pub const MODE_JNI: i32 = 0;
/// `JLI_Launch` on `lib/libjli.so` running `dev.horizonr.jvmprobe.Probe` (how ZalithLauncher2 starts its JVM).
pub const MODE_JLI: i32 = 1;
/// `JLI_Launch` running the Fabric server jar (`run_server`); not a mode of `run`.
pub const MODE_SERVER: i32 = 2;

pub const EXIT_OK: i32 = 0;
pub const EXIT_BOOT_FAILED: i32 = 1;
pub const EXIT_BAD_ARGUMENTS: i32 = 2;

/// Append-only `key=value` log. A failed write is ignored: the report is best effort and must not stop the boot.
struct Report {
    path: PathBuf,
}

impl Report {
    fn line(&self, key: &str, value: impl Display) {
        if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(&self.path) {
            let _ = writeln!(file, "{key}={value}");
        }
    }

    /// "unknown" for a value that could not be read, never 0.
    fn optional(&self, key: &str, value: Option<impl Display>) {
        match value {
            Some(v) => self.line(key, v),
            None => self.line(key, "unknown"),
        }
    }
}

/// Points stdout and stderr (fds 1 and 2) at `path`; an app process has them on /dev/null, and the JVM prints
/// its startup errors there.
fn redirect_stdio(path: &Path) -> std::io::Result<()> {
    let file = OpenOptions::new().create(true).append(true).open(path)?;
    let fd = file.as_raw_fd();
    // SAFETY: `fd` is open for the whole block; dup2 copies it onto 1 and 2, which stay valid after `file` drops.
    unsafe {
        if libc::dup2(fd, 1) < 0 || libc::dup2(fd, 2) < 0 {
            return Err(std::io::Error::last_os_error());
        }
    }
    Ok(())
}

fn properties_to_report(report: &Report, jvm: &Jvm) -> bool {
    let mut ok = true;
    for key in ["java.version", "java.vm.name", "java.vm.version", "java.home", "os.arch"] {
        match jvm.system_property(key) {
            Ok(Some(value)) => report.line(key, value),
            Ok(None) => report.line(key, "unset"),
            Err(e) => {
                report.line("error", format!("property {key}: {e}"));
                ok = false;
            }
        }
    }
    ok
}

/// Diagnostic-only options: where HotSpot writes a crash report (it defaults to the working directory, which an
/// app process cannot write) and a unified log. They change no tuning. The paths come from the app's own files dir.
fn diagnostic_options(work_dir: &Path) -> Vec<String> {
    vec![
        format!("-XX:ErrorFile={}", work_dir.join("hs_err_%p.log").display()),
        format!("-Xlog:all=info:file={}", work_dir.join("jvm_xlog.txt").display()),
    ]
}

/// Loads `PRELOAD_LIBS` by absolute path before either boot path, so runtime libraries that name each other as
/// dependencies resolve on Android. A failure is logged and the boot goes on: the JNI path booted without this.
fn preload_runtime_libs(report: &Report, jre_home: &Path) {
    let started = Instant::now();
    // libjava.so names libjvm.so as a dependency, and libjvm.so lives in lib/server (build 8 report).
    let dirs = [jre_home.join("lib"), jre_home.join("lib/server")];
    let dir_refs: Vec<&Path> = dirs.iter().map(PathBuf::as_path).collect();
    for (name, result) in preload(&dir_refs, PRELOAD_LIBS) {
        match result {
            Ok(trail) => report.line(&format!("preload_{name}"), format!("ok, loaded in order: {}", trail.join(" "))),
            Err(e) => report.line(&format!("preload_{name}"), format!("failed: {e}")),
        }
    }
    report.line("preload_ms", started.elapsed().as_millis());
    report.optional("rss_kib_after_preload", current_rss_kib());
}

fn boot_via_jni(report: &Report, jre_home: &Path, work_dir: &Path, heap_mib: u32) -> i32 {
    report.line("boot_ms_def", "JNI_CreateJavaVM call only, no Java class loaded by us");
    let config = JvmConfig {
        libjvm: Some(jre_home.join(LIBJVM_REL)),
        heap_mib,
        extra_options: diagnostic_options(work_dir),
    };
    report.line("jni_call", "entering JNI_CreateJavaVM");
    let started = Instant::now();
    let jvm = match Jvm::boot(&config) {
        Ok(jvm) => jvm,
        Err(e) => {
            report.line("error", format!("boot: {e}"));
            return EXIT_BOOT_FAILED;
        }
    };
    report.line("boot_ms", started.elapsed().as_millis());
    report.line("jni_call", "returned");
    report.optional("rss_kib_after", current_rss_kib());
    if properties_to_report(report, &jvm) {
        EXIT_OK
    } else {
        EXIT_BOOT_FAILED
    }
}

fn boot_via_jli(report: &Report, jre_home: &Path, probe_jar: &Path, work_dir: &Path, heap_mib: u32) -> i32 {
    report.line("boot_ms_def", "start of JLI_Launch until Probe.main begins (JVM create + class load), measured by Probe");
    let Some(start_ms) = epoch_ms() else {
        report.line("error", "system clock is before 1970");
        return EXIT_BOOT_FAILED;
    };
    let args = LauncherArgs {
        heap_mib,
        class_path: probe_jar.to_path_buf(),
        main_class: "dev.horizonr.jvmprobe.Probe".to_string(),
        properties: vec![
            ("probe.out".to_string(), work_dir.join("probe_out.txt").to_string_lossy().into_owned()),
            ("probe.start_ms".to_string(), start_ms.to_string()),
        ],
    };
    let argv = match launcher_argv(&args) {
        Ok(argv) => argv,
        Err(e) => {
            report.line("error", format!("argv: {e}"));
            return EXIT_BAD_ARGUMENTS;
        }
    };
    report.line("jli_argv", argv.join(" "));
    report.line("jli_call", "entering JLI_Launch");
    let started = Instant::now();
    report_jli_result(report, started, jli_launch(&jre_home.join(LIBJLI_REL), &argv))
}

/// Logs what `JLI_Launch` returned (if it returned) and maps it to an `EXIT_*` code.
fn report_jli_result(report: &Report, started: Instant, result: Result<i32, BootError>) -> i32 {
    match result {
        Ok(code) => {
            report.line("jli_return_ms", started.elapsed().as_millis());
            report.line("jli_exit_code", code);
            if code == 0 {
                EXIT_OK
            } else {
                EXIT_BOOT_FAILED
            }
        }
        Err(e @ BootError::Config(_)) => {
            report.line("error", format!("jli: {e}"));
            EXIT_BAD_ARGUMENTS
        }
        Err(e) => {
            report.line("error", format!("jli: {e}"));
            EXIT_BOOT_FAILED
        }
    }
}

/// The report file plus stdout/stderr redirected next to it. Every entry point starts with this.
fn open_report(work_dir: &Path) -> Report {
    let report = Report { path: work_dir.join("native.txt") };
    if let Err(e) = redirect_stdio(&work_dir.join("jvm_stdio.log")) {
        report.line("stdio_redirect", format!("failed: {e}"));
    }
    report
}

/// Runs the Fabric server jar through `JLI_Launch` with `server_dir` as the working directory. It returns only when
/// the server's JVM exits on its own; a SIGTERM or a kill ends the process instead. The server's console output
/// goes to `<work_dir>/jvm_stdio.log`. `options` are extra `-X…`/`-D…` options, already validated by the caller's
/// contract and again by `server_argv`.
pub fn run_server(heap_mib: i32, jre_home: &Path, work_dir: &Path, server_dir: &Path, jar: &Path, options: Vec<String>) -> i32 {
    let report = open_report(work_dir);
    report.line("mode", MODE_SERVER);
    report.line("heap_mib", heap_mib);
    report.line("jre_home", jre_home.display());
    report.line("server_dir", server_dir.display());
    let Ok(heap_mib) = u32::try_from(heap_mib) else {
        report.line("error", format!("negative heap {heap_mib}"));
        return EXIT_BAD_ARGUMENTS;
    };
    report.optional("rss_kib_before", current_rss_kib());
    preload_runtime_libs(&report, jre_home);
    if let Err(e) = std::env::set_current_dir(server_dir) {
        report.line("error", format!("chdir {}: {e}", server_dir.display()));
        return EXIT_BOOT_FAILED;
    }
    let argv = match server_argv(&ServerArgs { heap_mib, jar: jar.to_path_buf(), options }) {
        Ok(argv) => argv,
        Err(e) => {
            report.line("error", format!("argv: {e}"));
            return EXIT_BAD_ARGUMENTS;
        }
    };
    report.line("jli_argv", argv.join(" "));
    report.line("jli_call", "entering JLI_Launch (server)");
    let started = Instant::now();
    report_jli_result(&report, started, jli_launch(&jre_home.join(LIBJLI_REL), &argv))
}

/// Boots a JVM in this process in the way `mode` names and logs to `<work_dir>/native.txt`.
/// A process can create one JVM only; the caller is expected to end the process afterwards.
pub fn run(mode: i32, heap_mib: i32, jre_home: &Path, probe_jar: &Path, work_dir: &Path) -> i32 {
    let report = open_report(work_dir);
    report.line("mode", mode);
    report.line("heap_mib", heap_mib);
    report.line("jre_home", jre_home.display());
    let Ok(heap_mib) = u32::try_from(heap_mib) else {
        report.line("error", format!("negative heap {heap_mib}"));
        return EXIT_BAD_ARGUMENTS;
    };
    report.optional("rss_kib_before", current_rss_kib());
    preload_runtime_libs(&report, jre_home);
    match mode {
        MODE_JNI => boot_via_jni(&report, jre_home, work_dir, heap_mib),
        MODE_JLI => boot_via_jli(&report, jre_home, probe_jar, work_dir, heap_mib),
        other => {
            report.line("error", format!("unknown mode {other}"));
            EXIT_BAD_ARGUMENTS
        }
    }
}

fn text(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(String::from)
}

/// `NativeBoot.run(mode, heapMib, jreHome, probeJar, workDir): Int` (static). Returns one of the `EXIT_*` codes,
/// or -2 for unreadable arguments and -3 for a Rust panic.
#[no_mangle]
pub extern "system" fn Java_dev_horizonr_jvmprobe_NativeBoot_run<'local>(
    mut env: JNIEnv<'local>,
    _class: JObject<'local>,
    mode: jint,
    heap_mib: jint,
    jre_home: JString<'local>,
    probe_jar: JString<'local>,
    work_dir: JString<'local>,
) -> jint {
    let (Some(home), Some(jar), Some(dir)) =
        (text(&mut env, &jre_home), text(&mut env, &probe_jar), text(&mut env, &work_dir))
    else {
        return -2;
    };
    catch_unwind(AssertUnwindSafe(|| run(mode, heap_mib, Path::new(&home), Path::new(&jar), Path::new(&dir))))
        .unwrap_or(-3)
}

/// `NativeBoot.runServer(heapMib, jreHome, workDir, serverDir, serverJar, options): Int` (static). `options` is one
/// string with one JVM option per line. Returns an `EXIT_*` code, -2 for unreadable arguments, -3 for a Rust panic.
#[no_mangle]
pub extern "system" fn Java_dev_horizonr_jvmprobe_NativeBoot_runServer<'local>(
    mut env: JNIEnv<'local>,
    _class: JObject<'local>,
    heap_mib: jint,
    jre_home: JString<'local>,
    work_dir: JString<'local>,
    server_dir: JString<'local>,
    server_jar: JString<'local>,
    options: JString<'local>,
) -> jint {
    let (Some(home), Some(dir), Some(server), Some(jar), Some(options)) = (
        text(&mut env, &jre_home),
        text(&mut env, &work_dir),
        text(&mut env, &server_dir),
        text(&mut env, &server_jar),
        text(&mut env, &options),
    ) else {
        return -2;
    };
    let options = split_options(&options);
    catch_unwind(AssertUnwindSafe(|| {
        run_server(heap_mib, Path::new(&home), Path::new(&dir), Path::new(&server), Path::new(&jar), options)
    }))
    .unwrap_or(-3)
}

/// One option per line; blank lines are dropped. Pure.
pub fn split_options(text: &str) -> Vec<String> {
    text.lines().map(str::trim).filter(|l| !l.is_empty()).map(str::to_owned).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_options_drops_blank_lines_and_trims() {
        assert_eq!(split_options("-Da=1\n\n  -Xlog:gc=info:file=/x  \n"), ["-Da=1", "-Xlog:gc=info:file=/x"]);
        assert!(split_options("").is_empty());
    }
}
