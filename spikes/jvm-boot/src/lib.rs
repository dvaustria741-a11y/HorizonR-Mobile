//! Loads a `libjvm.so` that the caller names and calls one Java method through JNI.
//!
//! Nothing here knows where a JVM lives on any device: the library path is an input, and "not given"
//! is a distinct state (`JvmConfig::libjvm == None`), never an empty path.
use std::{
    ffi::{c_char, c_void, CString},
    fmt,
    path::{Path, PathBuf},
};

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
    HeapTooSmall(u32),
    BadOption(String),
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::LibJvmUnset => write!(f, "no libjvm path given"),
            Self::LibJvmMissing(p) => write!(f, "libjvm not found: {}", p.display()),
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
}
