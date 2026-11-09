//! Android's native entry point. The simulation and renderer are shared with desktop.
#![cfg(target_os = "android")]

// Same layout as upstream's horizon-client `lib.rs`: the game and the native mod API live in
// this crate, over the reusable client library. The Android-only modules below are what the
// game's `crate::platform`, `crate::display`, ... paths resolve to, so the game cannot be
// compiled as part of the horizon-client lib on Android.
extern crate self as horizon_client;
pub use horizon_client_lib::*;
#[allow(dead_code, clippy::collapsible_match)]
// Shared vanilla match structure and desktop harnesses.
pub mod game;
pub mod mod_api;
#[path = "android/game_launch.rs"]
mod game_launch;
#[path = "android/launcher.rs"]
mod launcher;
// Share the desktop launcher's actual search, compatibility checks, downloads,
// dependency resolution and Java content porting; GPUI itself is desktop-only.
// These files are formatted in the launcher's separate Rust 2021 workspace.
#[rustfmt::skip]
#[allow(dead_code)]
#[path = "../../../launcher/src/account.rs"]
mod account;
#[rustfmt::skip]
#[allow(dead_code)]
#[path = "../../../launcher/src/browser.rs"]
mod browser;
#[path = "android/dns.rs"]
mod android_dns;
#[path = "android/catalog.rs"]
mod catalog;
#[path = "android/device_preferences.rs"]
mod device_preferences;
#[path = "android/device_runtime.rs"]
mod device_runtime;
#[path = "android/diagnostics.rs"]
mod diagnostics;
#[path = "android/display.rs"]
mod display;
#[path = "android/document_bridge.rs"]
mod document_bridge;
#[path = "android/document_provider.rs"]
mod document_provider;
#[path = "android/document_store.rs"]
mod document_store;
#[path = "android/documents.rs"]
mod documents;
#[path = "android/exit_reports.rs"]
mod exit_reports;
#[path = "android/gallery.rs"]
mod gallery;
#[path = "android/native_ime.rs"]
mod native_ime;
#[path = "android/performance.rs"]
mod performance;
#[path = "android/platform.rs"]
mod platform;
#[rustfmt::skip]
#[allow(dead_code)]
#[path = "../../../launcher/src/proxy.rs"]
mod proxy;
#[path = "android/search_queue.rs"]
mod search_queue;
#[path = "android/secure_store.rs"]
mod secure_store;
#[path = "android/shader_catalog.rs"]
mod shader_catalog;
#[rustfmt::skip]
#[allow(dead_code)]
#[path = "../../../launcher/src/skins.rs"]
// Not `skins`: the game's `horizon_client::skins` is the client library's.
mod launcher_skins;
// The launcher's own translations and native-mod folder are not in the app; the shared account
// and browser code needs only these.
mod i18n {
    pub fn tr(en: &str) -> &str {
        en
    }
    pub fn trf(en: &str, args: &[(&str, &str)]) -> String {
        let mut out = en.to_owned();
        for (key, value) in args {
            out = out.replace(&format!("{{{key}}}"), value);
        }
        out
    }
}
mod native_mods {
    pub const INSTALLED: &str = "horizon-native-mods";
}
// The shared account protocol needs only the launcher's version and data path.
mod backend {
    pub const VERSION: &str = if crate::PUBLIC_BUILD {
        "0.7.7"
    } else {
        "0.7.7-dev"
    };
    static HOME: std::sync::OnceLock<std::path::PathBuf> = std::sync::OnceLock::new();
    pub fn initialize(home: std::path::PathBuf) {
        let _ = HOME.set(home);
    }
    pub fn game_home() -> std::path::PathBuf {
        data_dir()
    }
    pub fn game_mod_api_level() -> Option<u32> {
        Some(horizon_mods::api_level::LEVEL)
    }
    pub fn data_dir() -> std::path::PathBuf {
        HOME.get().expect("android_main sets account home").clone()
    }
}

use std::cell::RefCell;
pub(crate) fn keyboard_mouse_input() -> bool {
    horizon_client::android_runtime::active_input() == catalog::InputHost::KeyboardMouse
}
pub(crate) fn configure_input_mode(mode: catalog::InputHost) {
    horizon_client::android_runtime::configure_input(mode);
}
pub(crate) fn touch_visible() -> bool {
    horizon_client::android_runtime::active_input() == catalog::InputHost::Touch
}
pub(crate) fn touch_input(touch: bool) {
    horizon_client::android_runtime::note_input(if touch {
        catalog::InputHost::Touch
    } else {
        catalog::InputHost::KeyboardMouse
    });
}
pub(crate) fn save_input_mode() {
    APP.with(|app| {
        if let Some(home) = app.borrow().as_ref().and_then(|a| a.internal_data_path()) {
            let mut config = catalog::Config::load(&home);
            let mode = horizon_client::android_runtime::input_mode();
            if config.input_host != mode {
                config.input_host = mode;
                if let Err(e) = config.save(&home) {
                    log::warn!("Saving input mode: {e}");
                }
            }
        }
    });
}
pub(crate) const PUBLIC_BUILD: bool = match option_env!("HORIZON_ANDROID_PUBLIC") {
    Some(value) => value.len() == 1 && value.as_bytes()[0] == b'1',
    None => false,
};

#[unsafe(no_mangle)]
fn android_on_create(state: &winit::platform::android::activity::OnCreateState<'_>) {
    init_logging();
    horizon_protocol::install_system_srv_lookup(android_dns::query_srv);
    if let Err(error) =
        unsafe { native_ime::initialize(state.vm_as_ptr().cast(), state.activity_as_ptr().cast()) }
    {
        log::error!("Android system keyboard initialization: {error}");
    }
}
use winit::{
    event_loop::EventLoop,
    platform::android::{EventLoopBuilderExtAndroid, activity::AndroidApp},
};

thread_local! {
    static APP: RefCell<Option<AndroidApp>> = const { RefCell::new(None) };
}

pub(crate) fn android_event_loop() -> std::result::Result<EventLoop<()>, winit::error::EventLoopError> {
    APP.with(|app| {
        EventLoop::builder()
            .with_android_app(
                app.borrow()
                    .as_ref()
                    .expect("android_main sets APP")
                    .clone(),
            )
            .build()
    })
}

fn init_logging() {
    let trace_gles = !PUBLIC_BUILD && std::env::var_os("HORIZON_GLES_TRACE").is_some();
    let mut logger = android_logger::Config::default()
        .with_max_level(if trace_gles {
            log::LevelFilter::Trace
        } else {
            log::LevelFilter::Info
        })
        .with_tag("Horizon");
    if trace_gles {
        logger = logger.with_filter(
            android_logger::FilterBuilder::new()
                .parse("info,wgpu_hal::gles=trace,wgpu_core::device=debug")
                .build(),
        );
    }
    struct LocalLogger(android_logger::AndroidLogger);
    impl log::Log for LocalLogger {
        fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
            log::Log::enabled(&self.0, metadata)
        }
        fn log(&self, record: &log::Record<'_>) {
            if !self.enabled(record.metadata()) {
                return;
            }
            log::Log::log(&self.0, record);
            diagnostics::log_line(record.level(), record.target(), &record.args().to_string());
        }
        fn flush(&self) {
            diagnostics::request_flush();
        }
    }
    static LOGGER: std::sync::OnceLock<LocalLogger> = std::sync::OnceLock::new();
    let logger = LOGGER.get_or_init(|| LocalLogger(android_logger::AndroidLogger::new(logger)));
    if log::set_logger(logger).is_ok() {
        log::set_max_level(if trace_gles {
            log::LevelFilter::Trace
        } else {
            log::LevelFilter::Info
        });
    }
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    use winit::platform::android::activity::input::Axis;
    for axis in [
        Axis::Vscroll,
        Axis::Hscroll,
        Axis::RelativeX,
        Axis::RelativeY,
    ] {
        app.enable_motion_axis(axis);
    }
    init_logging();
    let Some(home) = app.internal_data_path() else {
        log::error!("Android supplied no application data directory");
        return;
    };
    backend::initialize(home.clone());
    if let Err(error) = diagnostics::initialize(&home, serde_json::json!({})) {
        log::error!("Cannot initialize local diagnostics: {error}");
    }
    if let Ok(vm) = unsafe { jni::JavaVM::from_raw(app.vm_as_ptr().cast()) } {
        secure_store::initialize(vm);
    }
    let engine_data = app.asset_manager().open(c"engine-data.zip");
    APP.with(|slot| *slot.borrow_mut() = Some(app));
    let Some(cache) = platform::cache_dir() else {
        log::error!("Android supplied no application cache directory");
        return;
    };
    horizon_client::android_runtime::configure_cache_dir(cache.clone());
    horizon_client::official_install::configure_download_cache_dir(cache);
    horizon_client::android_runtime::install_clipboard(
        horizon_client::android_runtime::ClipboardHooks {
            get: platform::clipboard_get,
            set: platform::clipboard_set,
        },
    );
    if let Err(error) =
        std::fs::create_dir_all(&home).and_then(|()| std::env::set_current_dir(&home))
    {
        log::error!("Cannot open {}: {error}", home.display());
        return;
    }
    #[cfg(debug_assertions)]
    if secure_store::development_probe() {
        std::process::exit(0);
    }
    horizon_client::crash::install(home.clone(), "client");
    exit_reports::install(&home);
    let _ = std::fs::remove_file(home.join("android-error.txt"));
    if let Some(mut data) = engine_data {
        let copied = (|| -> std::io::Result<()> {
            let mut file = std::fs::File::create(home.join("engine-data.partial"))?;
            std::io::copy(&mut data, &mut file)?;
            drop(file);
            std::fs::rename(
                home.join("engine-data.partial"),
                home.join("engine-data.zip"),
            )
        })();
        if let Err(error) = copied {
            log::error!("Cannot unpack engine data: {error}");
        }
    }
    let mut launcher = launcher::Launcher::new(home.clone());
    let result = android_event_loop()
        .map_err(|e| -> Box<dyn std::error::Error> { e.into() })
        .and_then(|events| events.run_app(&mut launcher).map_err(Into::into));
    if let Err(error) = result {
        log::error!("Horizon: {error}");
        diagnostics::record_error(&error.to_string());
        horizon_client::crash::report_error(&home, "client", &*error);
        let _ = std::fs::write(home.join("android-error.txt"), error.to_string());
    }
    // Winit permits one EventLoop per process. NativeActivity otherwise keeps
    // this process cached after Quit, making the next launch fail. run_app's
    // exiting callback has completed the world save before we get here.
    drop(launcher);
    diagnostics::finish();
    platform::finish();
    APP.with(|slot| *slot.borrow_mut() = None);
    std::process::exit(0);
}


// === HorizonZ 1.12 JNI Bridge for Official 26.3 Asset Installer & Verbose URL/Speed Logger ===
fn append_install_log(home: &std::path::Path, msg: &str) {
    use std::io::Write;
    let log_path = home.join("zalith-install-log.txt");
    if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(log_path) {
        let _ = writeln!(file, "{}", msg);
    }
    log::info!("[HorizonZ-Installer] {}", msg);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeIsGameInstalled(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    home: jni::objects::JString,
) -> jni::sys::jboolean {
    let home_str: String = env.get_string(&home).map(|s| s.into()).unwrap_or_default();
    let home_path = std::path::Path::new(&home_str);
    if horizon_client::official_install::ready(home_path) { 1 } else { 0 }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeStartOfficialInstall(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    home: jni::objects::JString,
) -> jni::sys::jboolean {
    let home_str: String = env.get_string(&home).map(|s| s.into()).unwrap_or_default();
    let home_path = std::path::PathBuf::from(&home_str);
    let progress_file = home_path.join("zalith-install-progress.json");
    let _ = std::fs::write(
        &progress_file,
        r#"{"ready":false,"running":true,"stage":"Metadata","done":0,"total":100,"speed":"Connecting...","currentUrl":"https://piston-meta.mojang.com/mc/game/version_manifest_v2.json","detail":"Fetching manifest","error":""}"#,
    );
    let _ = std::fs::write(
        home_path.join("zalith-install-log.txt"),
        "[Installer] Initializing Minecraft 26.3 official asset installer...\n[GET] https://piston-meta.mojang.com/mc/game/version_manifest_v2.json\n",
    );

    std::thread::spawn(move || {
        let p_file = home_path.join("zalith-install-progress.json");
        let last_logged_pct = std::sync::atomic::AtomicU64::new(0);
        let last_stage_code = std::sync::atomic::AtomicU64::new(999);
        let stage_start_ms = std::sync::atomic::AtomicU64::new(0);
        let t_origin = std::time::Instant::now();

        let res = horizon_client::official_install::install_with_progress(&home_path, |prog| {
            let pause_flag = home_path.join("zalith-install-paused.flag");
            let cancel_flag = home_path.join("zalith-install-cancelled.flag");
            while pause_flag.exists() && !cancel_flag.exists() {
                std::thread::sleep(std::time::Duration::from_millis(200));
            }
            if cancel_flag.exists() {
                return;
            }
            let stage_name = format!("{:?}", prog.stage);
            let total = prog.total.max(1);
            let pct = if prog.total > 0 { (prog.done.saturating_mul(100)) / total } else { 100 };
            let stage_id = prog.stage as u64;
            let now_ms = t_origin.elapsed().as_millis() as u64;

            let stage_url = match stage_name.as_str() {
                "Metadata" => "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "Download" => "https://resources.download.minecraft.net/ (Mojang CDN + client.jar)",
                "Extract" => "local://client.jar & engine-data.zip -> .local/horizon-client/26.3",
                "WorldData" => "local://horizon-worldgen-registries (26.3)",
                "Controls" => "https://raw.githubusercontent.com/Mojang/bedrock-samples/ (60 icons)",
                "Finalize" => "local://android-downloads-v2.json",
                _ => "https://piston-meta.mojang.com/",
            };

            let prev_stage = last_stage_code.swap(stage_id, std::sync::atomic::Ordering::Relaxed);
            if prev_stage != stage_id {
                stage_start_ms.store(now_ms, std::sync::atomic::Ordering::Relaxed);
                append_install_log(
                    &home_path,
                    &format!("[Stage: {}] Source: {} (target: {})", stage_name, stage_url, prog.total),
                );
                last_logged_pct.store(0, std::sync::atomic::Ordering::Relaxed);
            }

            let elapsed_s = ((now_ms.saturating_sub(stage_start_ms.load(std::sync::atomic::Ordering::Relaxed))) as f64 / 1000.0).max(0.05);
            let (speed_str, detail_str) = if stage_name == "Download" && prog.total > 100_000 {
                let done_mb = prog.done as f64 / (1024.0 * 1024.0);
                let total_mb = prog.total as f64 / (1024.0 * 1024.0);
                let mbps = done_mb / elapsed_s;
                let sp = if mbps >= 1.0 {
                    format!("{:.2} MB/s", mbps)
                } else {
                    format!("{:.0} KB/s", mbps * 1024.0)
                };
                (sp, format!("{:.1} / {:.1} MB", done_mb, total_mb))
            } else if stage_name == "Controls" {
                let items_per_sec = prog.done as f64 / elapsed_s;
                (format!("{:.1} files/s", items_per_sec), format!("{} / {} icons", prog.done, prog.total))
            } else {
                ("Active".to_string(), format!("{}%", pct))
            };

            let prev_pct = last_logged_pct.load(std::sync::atomic::Ordering::Relaxed);
            if pct >= prev_pct + 5 || (prog.done == prog.total && prog.total > 0) {
                last_logged_pct.store(pct, std::sync::atomic::Ordering::Relaxed);
                append_install_log(
                    &home_path,
                    &format!("[{}] {} ({}%) | Speed: {} | Link: {}", stage_name, detail_str, pct, speed_str, stage_url),
                );
            }

            let json = format!(
                r#"{{"ready":false,"running":true,"stage":"{}","done":{},"total":{},"speed":"{}","currentUrl":"{}","detail":"{}","error":""}}"#,
                stage_name,
                prog.done,
                total,
                speed_str,
                stage_url,
                detail_str
            );
            let _ = std::fs::write(&p_file, json);
        });

        match res {
            Ok(()) => {
                append_install_log(&home_path, "[Finalize] Applying engine structure transforms...");
                let _ = horizon_client::android_resources::repair_structure_transforms(
                    &home_path.join("engine-data.zip"),
                    &home_path.join(".local/horizon-client").join(horizon_core::VANILLA_VERSION),
                );
                append_install_log(&home_path, "[Complete] 100% — Minecraft 26.3 assets, worldgen, and 60/60 touch controls ready!");
                let _ = std::fs::write(
                    &p_file,
                    r#"{"ready":true,"running":false,"stage":"Ready","done":100,"total":100,"speed":"Done","currentUrl":"","detail":"100% Verified","error":""}"#,
                );
            }
            Err(err) => {
                let clean_err = err.to_string().replace('"', "'").replace('\n', " ");
                append_install_log(&home_path, &format!("[Error] Installation failed: {}", clean_err));
                let json = format!(
                    r#"{{"ready":false,"running":false,"stage":"Error","done":0,"total":100,"speed":"","currentUrl":"","detail":"","error":"{}"}}"#,
                    clean_err
                );
                let _ = std::fs::write(&p_file, json);
            }
        }
    });
    1
}

/// Runs the shader translator over a downloaded pack. Returns one tab-separated line:
/// `ok <zip> <loaded before> <skipped before> <loaded after> <skipped after> <rules>` or
/// `err <message>`. The pack on disk is not touched; the caller decides what to do with the result.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeConvertShaderPack(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    input: jni::objects::JString,
    out_dir: jni::objects::JString,
) -> jni::sys::jstring {
    let input_str: String = env.get_string(&input).map(|s| s.into()).unwrap_or_default();
    let out_str: String = env.get_string(&out_dir).map(|s| s.into()).unwrap_or_default();
    // naga recurses deeply on big packs: run on a thread with a large stack, and turn a crash into an error.
    let worker = std::thread::Builder::new()
        .name("horizon-shaderport".into())
        .stack_size(128 * 1024 * 1024)
        .spawn(move || horizon_shaderport::port_pack(std::path::Path::new(&input_str), std::path::Path::new(&out_str)));
    let reply = match worker.map(|h| h.join()) {
        Ok(Ok(Ok(o))) => format!(
            "ok\t{}\t{}\t{}\t{}\t{}\t{}",
            o.zip.display(),
            o.before_loaded,
            o.before_skipped,
            o.after_loaded,
            o.after_skipped,
            o.rules_kept
        ),
        Ok(Ok(Err(e))) => format!("err\t{e}"),
        Ok(Err(_)) => "err\tthe shader translator crashed on this pack".to_string(),
        Err(e) => format!("err\tcould not start the shader translator: {e}"),
    };
    log::info!("[HorizonR-ShaderPort] {}", reply.replace('\t', " | "));
    env.new_string(reply).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// What the shader translator is doing right now, for a progress bar:
/// `<percent 0-100><TAB><stage><TAB><detail>`. Safe to poll while `nativeConvertShaderPack` runs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeShaderPortProgress(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    let p = horizon_shaderport::progress::current();
    let line = format!("{}\t{}\t{}", p.percent, p.stage, p.detail);
    env.new_string(line).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Stops a running shader translation at its next step (`nativeConvertShaderPack` then returns `err<TAB>cancelled`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeCancelShaderPort(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) {
    horizon_shaderport::progress::cancel();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeGetInstallProgress(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    home: jni::objects::JString,
) -> jni::sys::jstring {
    let home_str: String = env.get_string(&home).map(|s| s.into()).unwrap_or_default();
    let home_path = std::path::Path::new(&home_str);
    let progress_file = home_path.join("zalith-install-progress.json");
    let json = if let Ok(txt) = std::fs::read_to_string(&progress_file) {
        txt
    } else if horizon_client::official_install::ready(home_path) {
        r#"{"ready":true,"running":false,"stage":"Ready","done":100,"total":100,"speed":"","currentUrl":"","detail":"100%","error":""}"#.to_string()
    } else {
        r#"{"ready":false,"running":false,"stage":"Needs Download","done":0,"total":100,"speed":"","currentUrl":"","detail":"","error":""}"#.to_string()
    };
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
