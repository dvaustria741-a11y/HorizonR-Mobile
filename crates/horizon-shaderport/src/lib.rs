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

mod alphacut;
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
pub mod progress;
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
    progress::reset();
    let result = std::panic::catch_unwind(|| port_inner(path, out)).unwrap_or_else(|payload| {
        Err(if progress::is_cancel(payload.as_ref()) {
            "cancelled".to_owned()
        } else {
            "the shader loader crashed on this pack".to_owned()
        })
    });
    if result.is_ok() {
        progress::stage("Done", 100, 100);
    }
    result
}

/// Silences the default panic message (a pack that panics the loader is reported, not printed).
pub fn silence_panics() {
    if std::env::var_os("HZ_TRACE").is_none() {
        std::panic::set_hook(Box::new(|_| {}));
    }
}

/// One of the fixes for how Horizon draws a pack (as opposed to what it loads).
/// (name, fix, relative cost for the progress bar: tenths of a percent of a typical pack's time).
type Pass = (&'static str, fn(&mut files::Files, &mut Vec<fix::Change>), usize);

/// Run on every pack, in this order (later ones read what earlier ones wrote).
const DRAW_PASSES: &[Pass] = &[
    ("normals", normals::fix, 120),
    ("uniforms", uniforms::fix, 40),
    ("matrix-order", uniforms::matrix_order, 5),
    ("pbr", pbr::fix, 40),
    ("alphacut", alphacut::fix, 5),
    ("postkeep", postkeep::fix, 340),
    ("fragdepth", fragdepth::fix, 35),
    ("tex3d", tex3d::fix, 20),
    ("noise2d", noise2d::fix, 40),
    ("tex2d", tex2d::fix, 5),
    ("bufsize", bufsize::fix, 25),
    ("skyblend", skyblend::fix, 27),
    ("cloudsoff", cloudsoff::fix, 27),
];

/// Runs one pass on the pack. A pass that panics (a rewrite rule meeting GLSL it did not expect)
/// is undone and noted in the report; the rest of the conversion goes on without it.
fn run_pass(files: &mut files::Files, log: &mut Vec<fix::Change>, pass: &Pass) -> bool {
    let (name, run, _) = *pass;
    let saved = files.clone();
    let log_len = log.len();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| run(files, log)));
    if let Err(payload) = &result {
        if progress::is_cancel(payload.as_ref()) {
            std::panic::resume_unwind(result.unwrap_err());
        }
    }
    let ok = result.is_ok();
    if !ok {
        *files = saved;
        log.truncate(log_len);
        log.push(fix::Change {
            rule: name,
            files: Vec::new(),
            outcome: "skipped: this fix crashed on this pack, so it was left out".into(),
        });
    }
    ok
}

/// The draw fixes only change how Horizon draws, so they must not stop a program loading. Each is
/// checked on its own against the pack as it was before them, and undone if it did.
fn replay_checked(base: &files::Files, base_eval: &load::Eval, log: &mut Vec<fix::Change>) -> Result<files::Files, String> {
    let mut files = base.clone();
    let mut current = base_eval.clone();
    for pass in DRAW_PASSES {
        let before_files = files.clone();
        let log_len = log.len();
        if !run_pass(&mut files, log, pass) || files.entries == before_files.entries {
            continue;
        }
        let eval = load::evaluate(&files)?;
        if eval.score() > current.score() {
            files = before_files;
            log.truncate(log_len);
            log.push(fix::Change {
                rule: pass.0,
                files: Vec::new(),
                outcome: "not kept: made a loading program fail".into(),
            });
        } else {
            current = eval;
        }
    }
    Ok(files)
}

fn port_inner(path: &Path, out: &Path) -> Result<PortOutcome, String> {
    progress::stage("Reading the shader pack", 0, 1);
    let mut files = files::Files::read(path)?;
    progress::stage("Checking which programs Horizon would skip", 1, 3);
    let before = load::evaluate(&files)?;
    progress::stage("Rewriting shader code so Horizon can load it", 3, 28);
    let (mut log, fixed) = fix::fix(&mut files, &before);
    progress::stage("Replacing compute programs", 28, 29);
    compute::compute_off(&mut files, &mut log);
    // The pack as the load fixes left it: where the draw fixes below fall back to if they hurt.
    let base = files.clone();
    let base_log_len = log.len();
    progress::stage("Fixing how Horizon draws the pack", 29, 96);
    let total_cost: usize = DRAW_PASSES.iter().map(|p| p.2).sum();
    let mut done_cost = 0;
    for pass in DRAW_PASSES {
        progress::step(done_cost, total_cost, pass.0);
        run_pass(&mut files, &mut log, pass);
        done_cost += pass.2;
    }
    progress::stage("Checking the converted pack", 96, 98);
    let mut after = load::evaluate(&files)?;
    // Normally the draw fixes lose nothing and this costs nothing. If the pack now loads worse than
    // it did, find the fix that did it (one more load per fix, only on this path).
    if after.score() > fixed.score() || after.panicked() {
        progress::stage("Undoing a fix that made programs fail", 98, 99);
        let base_eval = load::evaluate(&base)?;
        if after.score() > base_eval.score() || after.panicked() {
            log.truncate(base_log_len);
            files = replay_checked(&base, &base_eval, &mut log)?;
            after = load::evaluate(&files)?;
        }
    }
    if after.score() > before.score() {
        return Err("the converted pack loads fewer programs than the original, so it was not used".to_owned());
    }
    progress::stage("Saving the converted pack", 98, 99);
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    /// Progress and cancel are process-wide, so the tests that touch them take turns.
    static PROGRESS_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn sample() -> files::Files {
        let mut entries = BTreeMap::new();
        entries.insert("shaders/composite.fsh".to_owned(), b"void main() {}".to_vec());
        files::Files { name: "t".into(), entries, layout_notes: Vec::new() }
    }

    fn crashing(f: &mut files::Files, log: &mut Vec<fix::Change>) {
        f.set_text("composite.fsh", "half written".into());
        log.push(fix::Change { rule: "crashing", files: vec!["x".into()], outcome: String::new() });
        panic!("rule met GLSL it did not expect");
    }

    #[test]
    fn a_crashing_pass_is_undone_and_noted() {
        std::panic::set_hook(Box::new(|_| {}));
        let mut f = sample();
        let mut log = Vec::new();
        assert!(!run_pass(&mut f, &mut log, &("crashing", crashing, 1)));
        assert_eq!(f.text("composite.fsh").as_deref(), Some("void main() {}"));
        assert_eq!(log.len(), 1);
        assert!(log[0].files.is_empty() && log[0].outcome.starts_with("skipped"));
    }

    #[test]
    fn a_pass_that_does_not_crash_keeps_its_work() {
        fn ok(f: &mut files::Files, log: &mut Vec<fix::Change>) {
            f.set_text("composite.fsh", "void main() { }".into());
            log.push(fix::Change { rule: "ok", files: vec!["shaders/composite.fsh".into()], outcome: "done".into() });
        }
        let mut f = sample();
        let mut log = Vec::new();
        assert!(run_pass(&mut f, &mut log, &("ok", ok, 1)));
        assert_eq!(f.text("composite.fsh").as_deref(), Some("void main() { }"));
        assert_eq!(log.len(), 1);
    }

    #[test]
    fn sample_packs_convert_without_loading_less() {
        let _turn = PROGRESS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../horizon-shaders/tests/packs");
        for pack in ["post-test", "terrain-test"] {
            let out = std::env::temp_dir().join(format!("hz-shaderport-test-{pack}-{}", std::process::id()));
            let o = port_pack(&root.join(pack), &out).unwrap_or_else(|e| panic!("{pack}: {e}"));
            assert!(o.after_skipped <= o.before_skipped, "{pack}: {o:?}");
            assert!(o.zip.is_file() && o.report.is_file());
            assert_eq!(progress::current().percent, 100, "{pack}: a finished conversion reads 100%");
            let _ = std::fs::remove_dir_all(&out);
        }
    }

    #[test]
    fn progress_never_goes_backwards_and_cancel_unwinds() {
        let _turn = PROGRESS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        std::panic::set_hook(Box::new(|_| {}));
        progress::reset();
        progress::stage("a", 10, 20);
        progress::step(1, 2, "half");
        assert_eq!(progress::current().percent, 15);
        progress::stage("b", 5, 8); // a smaller span must not pull the bar back
        assert_eq!(progress::current().percent, 15);
        assert_eq!(progress::current().stage, "b");
        progress::cancel();
        let r = std::panic::catch_unwind(|| progress::step(0, 1, "x"));
        assert!(r.is_err_and(|p| progress::is_cancel(p.as_ref())));
        // a cancel is not mistaken for a crashing pass
        let mut f = sample();
        let mut log = Vec::new();
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            fn noop(_: &mut files::Files, _: &mut Vec<fix::Change>) {}
            run_pass(&mut f, &mut log, &("noop", noop, 1));
            progress::checkpoint();
        }));
        assert!(r.is_err_and(|p| progress::is_cancel(p.as_ref())));
        progress::reset();
    }
}
