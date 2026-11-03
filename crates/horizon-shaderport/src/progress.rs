//! What the converter is doing right now, for a UI to poll while `port_pack` runs on another
//! thread (the Android Store and Import show it as a progress bar), and a cancel switch.
//!
//! The expensive step is loading the whole pack through Horizon's loader, which cannot report
//! its own progress, so the percentage moves between steps (stages and the rewrite rules inside
//! them) and each stage's span is sized by how long it takes on a real pack.

use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

/// A snapshot of the conversion.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Progress {
    /// 0 to 100, never goes backwards during one conversion.
    pub percent: u32,
    /// What is happening ("Rewriting shader code", ...).
    pub stage: String,
    /// The step inside the stage ("normals", a rule name), may be empty.
    pub detail: String,
}

static STATE: Mutex<Progress> = Mutex::new(Progress { percent: 0, stage: String::new(), detail: String::new() });
static SPAN: Mutex<(u32, u32)> = Mutex::new((0, 0));
static START: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();
static CANCEL: AtomicBool = AtomicBool::new(false);

/// Panic payload used to unwind out of a cancelled conversion; `port_pack` turns it into an error.
pub(crate) struct Cancelled;

/// The latest snapshot (cheap: poll a few times a second).
pub fn current() -> Progress {
    STATE.lock().map(|s| s.clone()).unwrap_or_default()
}

/// Asks the running conversion to stop at its next step. The pack on disk is not touched.
pub fn cancel() {
    CANCEL.store(true, Ordering::SeqCst);
}

/// Starts a conversion: zero progress, no pending cancel.
pub(crate) fn reset() {
    CANCEL.store(false, Ordering::SeqCst);
    if let Ok(mut s) = STATE.lock() {
        *s = Progress { percent: 0, stage: "Starting".into(), detail: String::new() };
    }
    if let Ok(mut r) = SPAN.lock() {
        *r = (0, 0);
    }
}

/// Starts a stage that fills `from..to` percent. Unwinds if the conversion was cancelled.
pub(crate) fn stage(name: &str, from: u32, to: u32) {
    checkpoint();
    if let Ok(mut r) = SPAN.lock() {
        *r = (from, to);
    }
    set(from, name, "");
}

/// Moves inside the current stage: `done` of `total` steps, `detail` names the one starting.
pub(crate) fn step(done: usize, total: usize, detail: &str) {
    checkpoint();
    let (from, to) = SPAN.lock().map(|r| *r).unwrap_or((0, 0));
    let frac = if total == 0 { 0.0 } else { (done as f64 / total as f64).clamp(0.0, 1.0) };
    let percent = from + ((to.saturating_sub(from)) as f64 * frac) as u32;
    let stage = STATE.lock().map(|s| s.stage.clone()).unwrap_or_default();
    set(percent, &stage, detail);
}

fn set(percent: u32, stage: &str, detail: &str) {
    if std::env::var_os("HZ_PROGRESS").is_some() {
        eprintln!("[progress {:?}] {percent}% {stage} {detail}", START.get_or_init(std::time::Instant::now).elapsed());
    }
    if let Ok(mut s) = STATE.lock() {
        s.percent = s.percent.max(percent.min(100));
        s.stage = stage.to_owned();
        s.detail = detail.to_owned();
    }
}

/// Unwinds with [`Cancelled`] if a cancel was asked for.
pub(crate) fn checkpoint() {
    if CANCEL.load(Ordering::SeqCst) {
        std::panic::resume_unwind(Box::new(Cancelled));
    }
}

/// True if a panic payload is a cancel rather than a crash.
pub(crate) fn is_cancel(payload: &(dyn std::any::Any + Send)) -> bool {
    payload.is::<Cancelled>()
}
