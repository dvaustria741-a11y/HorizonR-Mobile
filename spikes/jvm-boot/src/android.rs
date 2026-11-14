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
    current_rss_kib, epoch_ms, jli_launch, launcher_argv, BootError, Jvm, JvmConfig, LauncherArgs, LIBJLI_REL,
    LIBJVM_REL,
};

/// `JNI_CreateJavaVM` on `lib/server/libjvm.so` (what `spikes/jvm-boot` does on desktop).
pub const MODE_JNI: i32 = 0;
/// `JLI_Launch` on `lib/libjli.so` running `dev.horizonr.jvmprobe.Probe` (how ZalithLauncher2 starts its JVM).
pub const MODE_JLI: i32 = 1;

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

fn boot_via_jni(report: &Report, jre_home: &Path, heap_mib: u32) -> i32 {
    report.line("boot_ms_def", "JNI_CreateJavaVM call only, no Java class loaded by us");
    let config = JvmConfig { libjvm: Some(jre_home.join(LIBJVM_REL)), heap_mib, extra_options: vec![] };
    let started = Instant::now();
    let jvm = match Jvm::boot(&config) {
        Ok(jvm) => jvm,
        Err(e) => {
            report.line("error", format!("boot: {e}"));
            return EXIT_BOOT_FAILED;
        }
    };
    report.line("boot_ms", started.elapsed().as_millis());
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
    match jli_launch(&jre_home.join(LIBJLI_REL), &argv) {
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

/// Boots a JVM in this process in the way `mode` names and logs to `<work_dir>/native.txt`.
/// A process can create one JVM only; the caller is expected to end the process afterwards.
pub fn run(mode: i32, heap_mib: i32, jre_home: &Path, probe_jar: &Path, work_dir: &Path) -> i32 {
    let report = Report { path: work_dir.join("native.txt") };
    if let Err(e) = redirect_stdio(&work_dir.join("jvm_stdio.log")) {
        report.line("stdio_redirect", format!("failed: {e}"));
    }
    report.line("mode", mode);
    report.line("heap_mib", heap_mib);
    report.line("jre_home", jre_home.display());
    let Ok(heap_mib) = u32::try_from(heap_mib) else {
        report.line("error", format!("negative heap {heap_mib}"));
        return EXIT_BAD_ARGUMENTS;
    };
    report.optional("rss_kib_before", current_rss_kib());
    match mode {
        MODE_JNI => boot_via_jni(&report, jre_home, heap_mib),
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
