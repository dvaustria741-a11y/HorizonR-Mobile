//! horizon-shaderport: makes Iris/OptiFine shader packs run in Horizon.
//!
//! Horizon reads the `shaders/` pack format itself (crate `horizon-shaders`), but a program it
//! cannot translate is skipped, and a pack missing its programs draws wrong or not at all. This
//! crate runs that same loader offline (no GPU), lists what it would skip and why, rewrites the
//! GLSL it trips on (rules in `fix.rs`, each kept only when the program then loads), and writes
//! the result as a pack for Horizon's `shaderpacks` folder.
//!
//! Adapted from Horizon-Translator by merenotnoonereal
//! (https://github.com/merenotnoonereal/Horizon-Translator), shader-converter/.

mod bufsize;
mod cloudsoff;
mod compute;
mod files;
mod fix;
mod fragdepth;
mod glsl;
mod gpu;
mod gpu_fix;
mod load;
mod noise2d;
mod normals;
mod pbr;
mod postkeep;
mod skyblend;
mod tex2d;
mod tex3d;
mod uniforms;

use std::path::{Path, PathBuf};

/// What one conversion did.
#[derive(Debug, Clone)]
pub struct PortOutcome {
    pub name: String,
    pub before_loaded: usize,
    pub before_skipped: usize,
    pub after_loaded: usize,
    pub after_skipped: usize,
    /// How many rewrite rules changed at least one file.
    pub rules_kept: usize,
    pub zip: PathBuf,
    pub report: PathBuf,
}

/// Converts the pack at `path` (a `.zip` or a folder) and writes `<name>-horizon.zip` and
/// `<name>-horizon.md` into `out`. A loader panic (naga has a few known ones) is returned as an
/// error instead of unwinding into the caller.
pub fn port_pack(path: &Path, out: &Path) -> Result<PortOutcome, String> {
    std::panic::catch_unwind(|| port_inner(path, out)).unwrap_or_else(|_| Err("the shader loader crashed on this pack".to_owned()))
}

/// Silences the default panic message (a pack that panics the loader is reported, not printed).
pub fn silence_panics() {
    if std::env::var_os("HZ_TRACE").is_none() {
        std::panic::set_hook(Box::new(|_| {}));
    }
}

fn port_inner(path: &Path, out: &Path) -> Result<PortOutcome, String> {
    let mut files = files::Files::read(path)?;
    let before = load::evaluate(&files)?;
    let mut log = fix::fix(&mut files, &before);
    compute::compute_off(&mut files, &mut log);
    // Fixes for how Horizon draws (not what it loads): run on every pack.
    normals::fix(&mut files, &mut log);
    uniforms::fix(&mut files, &mut log);
    uniforms::matrix_order(&mut files, &mut log);
    pbr::fix(&mut files, &mut log);
    postkeep::fix(&mut files, &mut log);
    fragdepth::fix(&mut files, &mut log);
    tex3d::fix(&mut files, &mut log);
    noise2d::fix(&mut files, &mut log);
    tex2d::fix(&mut files, &mut log);
    bufsize::fix(&mut files, &mut log);
    skyblend::fix(&mut files, &mut log);
    cloudsoff::fix(&mut files, &mut log);
    let after = load::evaluate(&files)?;
    std::fs::create_dir_all(out).map_err(|e| format!("{}: {e}", out.display()))?;
    let stem = files.name.trim_end_matches(".zip").to_owned();
    let zip = out.join(format!("{stem}-horizon.zip"));
    files.write_zip(&zip)?;
    let report = out.join(format!("{stem}-horizon.md"));
    std::fs::write(&report, fix::report(&files.name, &before, &after, &log))
        .map_err(|e| format!("{}: {e}", report.display()))?;
    Ok(PortOutcome {
        name: files.name.clone(),
        before_loaded: before.loaded_count(),
        before_skipped: before.skipped_count(),
        after_loaded: after.loaded_count(),
        after_skipped: after.skipped_count(),
        rules_kept: log.iter().filter(|c| !c.files.is_empty()).count(),
        zip,
        report,
    })
}
