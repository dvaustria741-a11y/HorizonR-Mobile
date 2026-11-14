//! Loads a `libjvm.so` that the caller names and calls one Java method through JNI.
//!
//! Nothing here knows where a JVM lives on any device: the library path is an input, and "not given"
//! is a distinct state (`JvmConfig::libjvm == None`), never an empty path.
use std::{
    ffi::{c_char, c_int, c_void, CString},
    fmt,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

#[cfg(unix)]
pub mod android;

use jni::{
    objects::{JObject, JString, JValue},
    sys::{self, JavaVMInitArgs, JavaVMOption},
    JavaVM,
};

/// Smallest `-Xmx` this spike accepts. A guard against typos, not a tuned value.
pub const MIN_HEAP_MIB: u32 = 16;

#[derive(Clone, Debug, Default)]
pub struct JvmConfig {
    /// `None` = not provided. The spike refuses to guess where a runtime is installed.
    pub libjvm: Option<PathBuf>,
    pub heap_mib: u32,
    /// Extra `-X…`/`-D…` options, passed through unchanged after validation.
    pub extra_options: Vec<String>,
}

#[derive(Debug)]
pub enum ConfigError {
    LibJvmUnset,
    LibJvmMissing(PathBuf),
    LibJliMissing(PathBuf),
    HeapTooSmall(u32),
    BadOption(String),
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::LibJvmUnset => write!(f, "no libjvm path given"),
            Self::LibJvmMissing(p) => write!(f, "libjvm not found: {}", p.display()),
            Self::LibJliMissing(p) => write!(f, "libjli not found: {}", p.display()),
            Self::HeapTooSmall(n) => write!(f, "heap {n} MiB is below the minimum of {MIN_HEAP_MIB} MiB"),
            Self::BadOption(o) => write!(f, "unusable JVM option: {o:?}"),
        }
    }
}

#[derive(Debug)]
pub enum BootError {
    Config(ConfigError),
    Load(libloading::Error),
    Create(i32),
    Attach(String),
}

impl fmt::Display for BootError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Config(e) => write!(f, "config: {e}"),
            Self::Load(e) => write!(f, "loading libjvm or JNI_CreateJavaVM: {e}"),
            Self::Create(code) => write!(f, "JNI_CreateJavaVM returned {code}"),
            Self::Attach(e) => write!(f, "JNI: {e}"),
        }
    }
}

impl From<ConfigError> for BootError {
    fn from(e: ConfigError) -> Self {
        Self::Config(e)
    }
}

/// The option strings for `JavaVMInitArgs`. Pure: no process state.
pub fn jvm_options(heap_mib: u32, extra: &[String]) -> Result<Vec<String>, ConfigError> {
    if heap_mib < MIN_HEAP_MIB {
        return Err(ConfigError::HeapTooSmall(heap_mib));
    }
    let mut out = vec![format!("-Xmx{heap_mib}m")];
    for option in extra {
        if option.is_empty() || option.contains('\0') || !(option.starts_with("-X") || option.starts_with("-D")) {
            return Err(ConfigError::BadOption(option.clone()));
        }
        out.push(option.clone());
    }
    Ok(out)
}

/// `VmRSS` in KiB from the text of `/proc/self/status`; `None` when the line is absent or malformed.
pub fn parse_vm_rss_kib(status: &str) -> Option<u64> {
    let line = status.lines().find(|l| l.starts_with("VmRSS:"))?;
    let mut parts = line["VmRSS:".len()..].split_whitespace();
    let value: u64 = parts.next()?.parse().ok()?;
    (parts.next()? == "kB").then_some(value)
}

pub fn current_rss_kib() -> Option<u64> {
    parse_vm_rss_kib(&std::fs::read_to_string("/proc/self/status").ok()?)
}

/// Runtime libraries to load by absolute path before the JVM needs them. Android's linker does not look in the
/// directory of the library that names a dependency, so `libnio.so` failed with `library "libnet.so" not found`
/// (build 7, JLI path, user's report). Both names come from that error text. Add a name when an error names one.
pub const PRELOAD_LIBS: &[&str] = &["libnet.so", "libnio.so"];

/// How many nested dependencies `preload` follows for one library before it gives up.
const PRELOAD_DEPTH: usize = 8;

/// The library name in an Android `dlopen` error such as
/// `dlopen failed: library "libnet.so" not found: needed by /x/lib/libnio.so in namespace clns-4`.
/// `None` for any other message, or when the name is empty or a path.
pub fn missing_library(message: &str) -> Option<&str> {
    const OPEN: &str = "library \"";
    let rest = &message[message.find(OPEN)? + OPEN.len()..];
    let name = &rest[..rest.find('"')?];
    let found_missing = rest[name.len()..].starts_with("\" not found");
    (found_missing && !name.is_empty() && !name.contains('/')).then_some(name)
}

/// `dlopen` of `name` from the first of `dirs` that holds it, with RTLD_GLOBAL, so later libraries that name it as a
/// dependency resolve to the copy already loaded. When the linker reports a missing dependency that exists in
/// `dirs`, that one is loaded first (bounded depth). `trail` collects the names loaded, dependencies first.
fn load_global(dirs: &[&Path], name: &str, depth: usize, trail: &mut Vec<String>) -> Result<(), String> {
    use libloading::os::unix::{Library, RTLD_GLOBAL, RTLD_NOW};

    let Some(path) = dirs.iter().map(|dir| dir.join(name)).find(|candidate| candidate.is_file()) else {
        let searched: Vec<String> = dirs.iter().map(|dir| dir.display().to_string()).collect();
        return Err(format!("{name} is not in {}", searched.join(" or ")));
    };
    let mut attempts = 0;
    loop {
        // SAFETY: loading a runtime library runs its initialisers; the caller chose the directory.
        match unsafe { Library::open(Some(&path), RTLD_NOW | RTLD_GLOBAL) } {
            Ok(library) => {
                // Stays mapped for the life of the process, like the JVM itself.
                std::mem::forget(library);
                trail.push(name.to_string());
                return Ok(());
            }
            Err(e) => {
                let message = e.to_string();
                attempts += 1;
                match missing_library(&message).map(str::to_owned) {
                    Some(dependency) if dependency != name && depth < PRELOAD_DEPTH && attempts <= PRELOAD_DEPTH => {
                        load_global(dirs, &dependency, depth + 1, trail)
                            .map_err(|inner| format!("{message}; dependency {dependency}: {inner}"))?;
                    }
                    _ => return Err(message),
                }
            }
        }
    }
}

/// Loads each of `names` from `dirs` (see `load_global`). One entry per name: the libraries loaded for it in
/// order, or the linker's error text. A failure does not stop the others.
pub fn preload(dirs: &[&Path], names: &[&str]) -> Vec<(String, Result<Vec<String>, String>)> {
    names
        .iter()
        .map(|name| {
            let mut trail = Vec::new();
            let result = load_global(dirs, name, 0, &mut trail).map(|()| trail);
            (name.to_string(), result)
        })
        .collect()
}

pub fn validate(config: &JvmConfig) -> Result<(&Path, Vec<String>), ConfigError> {
    let path = config.libjvm.as_deref().ok_or(ConfigError::LibJvmUnset)?;
    if !path.is_file() {
        return Err(ConfigError::LibJvmMissing(path.to_path_buf()));
    }
    Ok((path, jvm_options(config.heap_mib, &config.extra_options)?))
}

/// A running JVM. A process can create one JVM only, and HotSpot cannot be unloaded afterwards, so
/// there is no teardown: the library handle is leaked on purpose.
pub struct Jvm {
    vm: JavaVM,
}

type CreateJavaVm = unsafe extern "system" fn(*mut *mut sys::JavaVM, *mut *mut c_void, *mut c_void) -> sys::jint;

impl Jvm {
    pub fn boot(config: &JvmConfig) -> Result<Self, BootError> {
        let (path, options) = validate(config)?;
        // SAFETY: loading a JVM runs its initialisers; the caller chose the library.
        let library = unsafe { libloading::Library::new(path) }.map_err(BootError::Load)?;
        // SAFETY: the symbol has the JNI invocation API's signature.
        let symbol = unsafe { library.get::<CreateJavaVm>(b"JNI_CreateJavaVM\0") }.map_err(BootError::Load)?;
        let create: CreateJavaVm = *symbol;
        // A JVM's library must stay mapped for the life of the process.
        std::mem::forget(library);

        let strings: Vec<CString> = options
            .iter()
            .map(|o| CString::new(o.as_str()).expect("validated: no NUL"))
            .collect();
        let mut raw: Vec<JavaVMOption> = strings
            .iter()
            .map(|s| JavaVMOption { optionString: s.as_ptr() as *mut c_char, extraInfo: std::ptr::null_mut() })
            .collect();
        let mut args = JavaVMInitArgs {
            version: sys::JNI_VERSION_1_8,
            nOptions: raw.len() as sys::jint,
            options: raw.as_mut_ptr(),
            ignoreUnrecognized: sys::JNI_FALSE,
        };
        let mut vm_ptr: *mut sys::JavaVM = std::ptr::null_mut();
        let mut env_ptr: *mut c_void = std::ptr::null_mut();
        // SAFETY: `args` and the option strings outlive the call.
        let code = unsafe { create(&mut vm_ptr, &mut env_ptr, &mut args as *mut _ as *mut c_void) };
        if code != sys::JNI_OK || vm_ptr.is_null() {
            return Err(BootError::Create(code));
        }
        // SAFETY: `vm_ptr` came from a successful JNI_CreateJavaVM.
        let vm = unsafe { JavaVM::from_raw(vm_ptr) }.map_err(|e| BootError::Attach(e.to_string()))?;
        Ok(Self { vm })
    }

    /// `System.getProperty(key)`: the one Java method this spike calls. `None` when the property is unset.
    pub fn system_property(&self, key: &str) -> Result<Option<String>, BootError> {
        let attach = |e: jni::errors::Error| BootError::Attach(e.to_string());
        let mut env = self.vm.attach_current_thread().map_err(attach)?;
        let key = env.new_string(key).map_err(attach)?;
        let result = env
            .call_static_method(
                "java/lang/System",
                "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&key)],
            )
            .and_then(|v| v.l());
        let object: JObject = match result {
            Ok(o) => o,
            Err(e) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
                return Err(attach(e));
            }
        };
        if object.is_null() {
            return Ok(None);
        }
        let text: String = env.get_string(&JString::from(object)).map_err(attach)?.into();
        Ok(Some(text))
    }
}

/// Where the JRE tarballs of ZalithLauncher2 keep the two libraries, relative to the runtime home
/// (derived from the notes in README.md; a different runtime layout is unknown here).
pub const LIBJVM_REL: &str = "lib/server/libjvm.so";
pub const LIBJLI_REL: &str = "lib/libjli.so";

/// Milliseconds since the Unix epoch; `None` if the clock is before 1970.
pub fn epoch_ms() -> Option<u128> {
    SystemTime::now().duration_since(UNIX_EPOCH).ok().map(|d| d.as_millis())
}

/// What the `java` launcher would receive on its command line, minus the JDK's own option parsing.
#[derive(Clone, Debug)]
pub struct LauncherArgs {
    pub heap_mib: u32,
    pub class_path: PathBuf,
    pub main_class: String,
    /// `-Dkey=value` pairs, in order.
    pub properties: Vec<(String, String)>,
}

/// `argv` for `JLI_Launch`: program name, heap, properties, class path, main class. Pure.
pub fn launcher_argv(args: &LauncherArgs) -> Result<Vec<String>, ConfigError> {
    let mut argv = vec!["java".to_string()];
    argv.extend(jvm_options(args.heap_mib, &[])?);
    for (key, value) in &args.properties {
        if key.is_empty() || key.contains(|c: char| c == '=' || c == '\0') || value.contains('\0') {
            return Err(ConfigError::BadOption(format!("{key}={value}")));
        }
        argv.push(format!("-D{key}={value}"));
    }
    let class_path = args
        .class_path
        .to_str()
        .filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| ConfigError::BadOption(format!("class path {:?}", args.class_path)))?;
    if args.main_class.is_empty() || args.main_class.contains(|c: char| c == '\0' || c.is_whitespace()) {
        return Err(ConfigError::BadOption(format!("main class {:?}", args.main_class)));
    }
    argv.extend(["-cp".to_string(), class_path.to_string(), args.main_class.clone()]);
    Ok(argv)
}

/// What the Fabric server launcher needs: `java <jvm options> -jar <jar> nogui`, run with the server directory as
/// the working directory (the caller changes it; Fabric writes `world`, `libraries` and `logs` there).
#[derive(Clone, Debug)]
pub struct ServerArgs {
    pub heap_mib: u32,
    pub jar: PathBuf,
    /// Extra `-X…`/`-D…` options, validated like `jvm_options`.
    pub options: Vec<String>,
}

/// `argv` for `JLI_Launch` running a server jar: program name, heap, options, `-jar <jar>`, `nogui`. Pure.
pub fn server_argv(args: &ServerArgs) -> Result<Vec<String>, ConfigError> {
    let mut argv = vec!["java".to_string()];
    argv.extend(jvm_options(args.heap_mib, &args.options)?);
    let jar = args
        .jar
        .to_str()
        .filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| ConfigError::BadOption(format!("server jar {:?}", args.jar)))?;
    argv.extend(["-jar".to_string(), jar.to_string(), "nogui".to_string()]);
    Ok(argv)
}

/// Prototype of `JLI_Launch` as I remember OpenJDK's `src/java.base/share/native/libjli/java.h`.
/// Not checked against the 25.0.5 build in the Zalith tarball: a wrong prototype would crash at the call.
type JliLaunch = unsafe extern "C" fn(
    argc: c_int,
    argv: *mut *mut c_char,
    jargc: c_int,
    jargv: *const *const c_char,
    appclassc: c_int,
    appclassv: *const *const c_char,
    fullversion: *const c_char,
    dotversion: *const c_char,
    pname: *const c_char,
    lname: *const c_char,
    javaargs: sys::jboolean,
    cpwildcard: sys::jboolean,
    javaw: sys::jboolean,
    ergo: sys::jint,
) -> c_int;

/// Runs the JDK launcher (`libjli.so`) in this process: it creates the JVM, runs `main`, destroys the JVM and
/// returns the exit code. The launcher may call `exit()` itself on errors, so run it in a process you can lose.
/// `fullversion`/`dotversion` are only shown by `-version`; "25" is my choice, not read from the runtime.
pub fn jli_launch(libjli: &Path, argv: &[String]) -> Result<i32, BootError> {
    if !libjli.is_file() {
        return Err(ConfigError::LibJliMissing(libjli.to_path_buf()).into());
    }
    if argv.is_empty() || argv.iter().any(|a| a.is_empty() || a.contains('\0')) {
        return Err(ConfigError::BadOption(format!("launcher argv {argv:?}")).into());
    }
    // SAFETY: loading a JDK library runs its initialisers; the caller chose the library.
    let library = unsafe { libloading::Library::new(libjli) }.map_err(BootError::Load)?;
    // SAFETY: the symbol has the prototype above (unverified for this build, see `JliLaunch`).
    let symbol = unsafe { library.get::<JliLaunch>(b"JLI_Launch\0") }.map_err(BootError::Load)?;
    let launch: JliLaunch = *symbol;
    std::mem::forget(library);

    let owned: Vec<CString> = argv.iter().map(|a| CString::new(a.as_str()).expect("validated: no NUL")).collect();
    let mut pointers: Vec<*mut c_char> = owned.iter().map(|s| s.as_ptr() as *mut c_char).collect();
    pointers.push(std::ptr::null_mut());
    let version = CString::new("25").expect("literal");
    let program = CString::new("java").expect("literal");
    let launcher = CString::new("openjdk").expect("literal");
    // SAFETY: every pointer outlives the call; argv is NULL-terminated and argc excludes the terminator.
    let code = unsafe {
        launch(
            argv.len() as c_int,
            pointers.as_mut_ptr(),
            0,
            std::ptr::null(),
            0,
            std::ptr::null(),
            version.as_ptr(),
            version.as_ptr(),
            program.as_ptr(),
            launcher.as_ptr(),
            sys::JNI_FALSE,
            sys::JNI_TRUE,
            sys::JNI_FALSE,
            0,
        )
    };
    Ok(code)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn options_have_heap_first_and_pass_extras() {
        let o = jvm_options(128, &["-Dx=1".into(), "-XX:+UseSerialGC".into()]).unwrap();
        assert_eq!(o, ["-Xmx128m", "-Dx=1", "-XX:+UseSerialGC"]);
    }

    #[test]
    fn options_reject_small_heap_and_odd_extras() {
        assert!(matches!(jvm_options(8, &[]), Err(ConfigError::HeapTooSmall(8))));
        for bad in ["", "-Xmx1\0", "classpath", "--add-opens=a/b=c"] {
            assert!(matches!(jvm_options(64, &[bad.to_string()]), Err(ConfigError::BadOption(_))), "{bad:?}");
        }
    }

    #[test]
    fn rss_parse() {
        assert_eq!(parse_vm_rss_kib("Name:\tx\nVmRSS:\t  12345 kB\nThreads:\t1\n"), Some(12345));
        assert_eq!(parse_vm_rss_kib("VmRSS:\t12 MB\n"), None);
        assert_eq!(parse_vm_rss_kib("Name:\tx\n"), None);
    }

    #[test]
    fn unset_library_is_an_error_not_a_default() {
        assert!(matches!(validate(&JvmConfig { heap_mib: 64, ..Default::default() }), Err(ConfigError::LibJvmUnset)));
        let missing = JvmConfig { libjvm: Some("/nonexistent/libjvm.so".into()), heap_mib: 64, extra_options: vec![] };
        assert!(matches!(validate(&missing), Err(ConfigError::LibJvmMissing(_))));
    }

    fn launcher_args() -> LauncherArgs {
        LauncherArgs {
            heap_mib: 128,
            class_path: "/data/probe.jar".into(),
            main_class: "dev.horizonr.jvmprobe.Probe".into(),
            properties: vec![("probe.out".into(), "/data/out.txt".into())],
        }
    }

    #[test]
    fn launcher_argv_order() {
        assert_eq!(
            launcher_argv(&launcher_args()).unwrap(),
            ["java", "-Xmx128m", "-Dprobe.out=/data/out.txt", "-cp", "/data/probe.jar", "dev.horizonr.jvmprobe.Probe"]
        );
    }

    #[test]
    fn launcher_argv_rejects_bad_input() {
        let mut a = launcher_args();
        a.main_class = "two words".into();
        assert!(matches!(launcher_argv(&a), Err(ConfigError::BadOption(_))));
        let mut a = launcher_args();
        a.properties = vec![("k=v".into(), "x".into())];
        assert!(matches!(launcher_argv(&a), Err(ConfigError::BadOption(_))));
        let mut a = launcher_args();
        a.heap_mib = 1;
        assert!(matches!(launcher_argv(&a), Err(ConfigError::HeapTooSmall(1))));
        let mut a = launcher_args();
        a.class_path = PathBuf::new();
        assert!(matches!(launcher_argv(&a), Err(ConfigError::BadOption(_))));
    }

    #[test]
    fn server_argv_order() {
        let args = ServerArgs {
            heap_mib: 1024,
            jar: "/data/server/fabric-server.jar".into(),
            options: vec!["-Djava.io.tmpdir=/data/server/tmp".into(), "-Xlog:gc=info:file=/data/gc.txt".into()],
        };
        assert_eq!(
            server_argv(&args).unwrap(),
            [
                "java",
                "-Xmx1024m",
                "-Djava.io.tmpdir=/data/server/tmp",
                "-Xlog:gc=info:file=/data/gc.txt",
                "-jar",
                "/data/server/fabric-server.jar",
                "nogui"
            ]
        );
    }

    #[test]
    fn server_argv_rejects_bad_input() {
        let ok = ServerArgs { heap_mib: 512, jar: "/s/a.jar".into(), options: vec![] };
        assert!(server_argv(&ok).is_ok());
        let mut a = ok.clone();
        a.jar = PathBuf::new();
        assert!(matches!(server_argv(&a), Err(ConfigError::BadOption(_))));
        let mut a = ok.clone();
        a.heap_mib = 1;
        assert!(matches!(server_argv(&a), Err(ConfigError::HeapTooSmall(1))));
        let mut a = ok.clone();
        a.options = vec!["-jar".into()];
        assert!(matches!(server_argv(&a), Err(ConfigError::BadOption(_))));
    }

    #[test]
    fn missing_library_parses_the_android_message() {
        let real = "/data/user/0/dev.horizonr.jvmprobe/files/jre25/lib/libnio.so: dlopen failed: library \"libnet.so\" \
                    not found: needed by /data/user/0/dev.horizonr.jvmprobe/files/jre25/lib/libnio.so in namespace clns-4";
        assert_eq!(missing_library(real), Some("libnet.so"));
        assert_eq!(missing_library("dlopen failed: library \"libnet.so\" not found"), Some("libnet.so"));
    }

    #[test]
    fn missing_library_rejects_other_messages() {
        for other in [
            "",
            "dlopen failed: cannot locate symbol \"foo\" referenced by \"/x/libnio.so\"",
            "dlopen failed: library \"\" not found",
            "dlopen failed: library \"/abs/libnet.so\" not found",
            "dlopen failed: library \"libnet.so\" has bad ELF magic",
            "library \"unterminated",
        ] {
            assert_eq!(missing_library(other), None, "{other:?}");
        }
    }

    #[test]
    fn preload_reports_a_missing_file_per_name() {
        let out = preload(&[Path::new("/nonexistent/lib"), Path::new("/nonexistent/lib/server")], &["libnet.so", "libnio.so"]);
        assert_eq!(out.len(), 2);
        assert!(out.iter().all(|(_, r)| r.as_ref().is_err_and(|e| e.contains("is not in"))));
    }

    #[test]
    fn missing_libjli_is_an_error() {
        assert!(matches!(
            jli_launch(Path::new("/nonexistent/libjli.so"), &["java".to_string()]),
            Err(BootError::Config(ConfigError::LibJliMissing(_)))
        ));
    }
}
