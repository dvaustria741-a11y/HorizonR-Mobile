//! Adaptive Render Distance: the draw distance that follows the frame's own cost.
//!
//! While frames take longer than the budget, terrain is drawn one chunk nearer, a step at a time
//! down to a floor; once frames have had room for a while, it grows back a chunk at a time, up to
//! the render distance setting. Only the drawn distance moves: chunks stay loaded and the
//! server's view distance is untouched, so growing back needs no new chunks from anyone. Fog and
//! the far plane follow the drawn distance, so the world closes in rather than being cut.
//!
//! A verdict is the 90th percentile of a window of frames (`WINDOW`), so a few slow frames (a
//! chunk load, a hitch) don't move it, and the thresholds are apart (`SHRINK_ABOVE` against
//! `GROW_BELOW`) so a step doesn't undo itself. Nothing here reads the GPU or draws: the caller
//! feeds it each frame's busy time and applies what `apply` answers.

/// Frames in one verdict.
pub const WINDOW: usize = 60;
/// A window whose 90th-percentile busy time is above this share of the budget shrinks the distance.
const SHRINK_ABOVE: f32 = 0.95;
/// A window below this share of the budget counts toward growing back.
const GROW_BELOW: f32 = 0.70;
/// Windows below `GROW_BELOW` in a row before the distance grows a chunk.
const GROW_AFTER: u8 = 3;
/// Windows left out after a step, while the new distance's frames settle.
const SETTLE: u8 = 1;
/// Fewest chunks the floor can be set to.
pub const MIN_FLOOR: i32 = 4;

/// The 90th percentile of `frames` in milliseconds, or `None` for none or a non-finite one.
pub fn percentile_90(frames: &[f32]) -> Option<f32> {
    if frames.is_empty() || frames.iter().any(|f| !f.is_finite()) {
        return None;
    }
    let mut sorted = frames.to_vec();
    sorted.sort_by(f32::total_cmp);
    let index = ((sorted.len() * 9).div_ceil(10)).saturating_sub(1);
    sorted.get(index).copied()
}

/// What a window's verdict asks of the distance.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Shrink,
    Grow,
    Hold,
}

/// The verdict of one window: its 90th-percentile busy time against the frame budget.
pub fn judge(p90_ms: f32, budget_ms: f32, calm_windows: u8) -> (Verdict, u8) {
    if !(budget_ms.is_finite() && budget_ms > 0.0) || !p90_ms.is_finite() {
        return (Verdict::Hold, 0);
    }
    if p90_ms > budget_ms * SHRINK_ABOVE {
        return (Verdict::Shrink, 0);
    }
    if p90_ms < budget_ms * GROW_BELOW {
        let calm = calm_windows.saturating_add(1);
        return if calm >= GROW_AFTER {
            (Verdict::Grow, 0)
        } else {
            (Verdict::Hold, calm)
        };
    }
    (Verdict::Hold, 0)
}

/// The drawn distance's governor.
#[derive(Clone, Debug)]
pub struct AdaptiveDistance {
    enabled: bool,
    budget_ms: f32,
    floor: i32,
    /// Chunks taken off the render distance setting right now.
    shrunk: i32,
    frames: Vec<f32>,
    calm: u8,
    settling: u8,
}

impl Default for AdaptiveDistance {
    fn default() -> Self {
        Self::new()
    }
}

impl AdaptiveDistance {
    pub fn new() -> Self {
        Self {
            enabled: false,
            budget_ms: 1000.0 / 60.0,
            floor: MIN_FLOOR,
            shrunk: 0,
            frames: Vec::with_capacity(WINDOW),
            calm: 0,
            settling: 0,
        }
    }

    /// Takes the settings. Turning it off, or a new budget or floor, starts the measuring over;
    /// off gives the whole distance back at once.
    pub fn configure(&mut self, enabled: bool, target_fps: f32, floor_chunks: i32) {
        let budget_ms = if target_fps.is_finite() && target_fps > 0.0 {
            1000.0 / target_fps.clamp(30.0, 60.0)
        } else {
            1000.0 / 60.0
        };
        let floor = floor_chunks.max(MIN_FLOOR);
        if enabled != self.enabled || budget_ms != self.budget_ms || floor != self.floor {
            self.enabled = enabled;
            self.budget_ms = budget_ms;
            self.floor = floor;
            self.reset();
        }
    }

    /// Forgets what it measured and gives the whole distance back.
    pub fn reset(&mut self) {
        self.shrunk = 0;
        self.frames.clear();
        self.calm = 0;
        self.settling = 0;
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// The distance to draw, in chunks, for a render distance of `setting`: never above it, and
    /// never below the floor unless the setting itself is lower.
    pub fn apply(&self, setting: i32) -> i32 {
        if !self.enabled {
            return setting;
        }
        let lowest = self.floor.min(setting);
        (setting - self.shrunk).clamp(lowest, setting)
    }

    /// Chunks taken off right now, for the debug state.
    pub fn shrunk(&self) -> i32 {
        self.shrunk
    }

    /// Feeds one frame's busy time in milliseconds (the slower of its CPU and GPU sides) and
    /// returns the change in drawn distance a finished window made: `-1`, `1`, or `None`.
    /// `setting` is the render distance setting the steps are taken against.
    pub fn observe(&mut self, busy_ms: f32, setting: i32) -> Option<i32> {
        if !self.enabled {
            return None;
        }
        if !busy_ms.is_finite() || busy_ms < 0.0 {
            return None;
        }
        self.frames.push(busy_ms);
        if self.frames.len() < WINDOW {
            return None;
        }
        let p90 = percentile_90(&self.frames);
        self.frames.clear();
        if self.settling > 0 {
            self.settling -= 1;
            return None;
        }
        let (verdict, calm) = judge(p90?, self.budget_ms, self.calm);
        self.calm = calm;
        let lowest = self.floor.min(setting);
        match verdict {
            Verdict::Shrink if setting - self.shrunk > lowest => {
                self.shrunk += 1;
                self.settling = SETTLE;
                Some(-1)
            }
            Verdict::Grow if self.shrunk > 0 => {
                self.shrunk -= 1;
                self.settling = SETTLE;
                Some(1)
            }
            _ => None,
        }
    }

    /// Drops the frames of a window that loading or a menu spoiled, so they don't count.
    pub fn skip_window(&mut self) {
        self.frames.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed(a: &mut AdaptiveDistance, ms: f32, frames: usize, setting: i32) -> Vec<i32> {
        (0..frames)
            .filter_map(|_| a.observe(ms, setting))
            .collect()
    }

    #[test]
    fn percentile_ignores_a_few_spikes_and_rejects_bad_input() {
        let mut frames = vec![5.0; 60];
        frames[3] = 80.0;
        frames[40] = 90.0;
        assert_eq!(percentile_90(&frames), Some(5.0));
        assert_eq!(percentile_90(&[]), None);
        assert_eq!(percentile_90(&[1.0, f32::NAN]), None);
    }

    #[test]
    fn judge_has_a_gap_between_shrinking_and_growing() {
        let budget = 16.0;
        assert_eq!(judge(17.0, budget, 0).0, Verdict::Shrink);
        assert_eq!(judge(14.0, budget, 2), (Verdict::Hold, 0));
        assert_eq!(judge(8.0, budget, 0), (Verdict::Hold, 1));
        assert_eq!(judge(8.0, budget, 2), (Verdict::Grow, 0));
        assert_eq!(judge(f32::NAN, budget, 0), (Verdict::Hold, 0));
        assert_eq!(judge(8.0, 0.0, 0), (Verdict::Hold, 0));
    }

    #[test]
    fn off_changes_nothing() {
        let mut a = AdaptiveDistance::new();
        a.configure(false, 60.0, 6);
        assert!(feed(&mut a, 40.0, WINDOW * 3, 12).is_empty());
        assert_eq!(a.apply(12), 12);
    }

    #[test]
    fn slow_frames_shrink_to_the_floor_and_no_further() {
        let mut a = AdaptiveDistance::new();
        a.configure(true, 60.0, 6);
        // Every second window is a settle window, so each step takes two.
        let steps = feed(&mut a, 30.0, WINDOW * 40, 12);
        assert!(steps.iter().all(|&s| s == -1));
        assert_eq!(a.apply(12), 6);
        assert_eq!(a.shrunk(), 6);
    }

    #[test]
    fn room_grows_it_back_one_chunk_at_a_time_up_to_the_setting() {
        let mut a = AdaptiveDistance::new();
        a.configure(true, 60.0, 6);
        feed(&mut a, 30.0, WINDOW * 40, 12);
        let steps = feed(&mut a, 4.0, WINDOW * 80, 12);
        assert!(steps.iter().all(|&s| s == 1));
        assert_eq!(a.apply(12), 12);
        assert_eq!(a.shrunk(), 0);
    }

    #[test]
    fn a_setting_below_the_floor_is_left_alone() {
        let mut a = AdaptiveDistance::new();
        a.configure(true, 60.0, 8);
        feed(&mut a, 30.0, WINDOW * 20, 5);
        assert_eq!(a.apply(5), 5);
    }

    #[test]
    fn a_changed_setting_starts_over_and_off_gives_everything_back() {
        let mut a = AdaptiveDistance::new();
        a.configure(true, 60.0, 6);
        feed(&mut a, 30.0, WINDOW * 6, 12);
        assert!(a.shrunk() > 0);
        a.configure(true, 60.0, 6);
        assert!(a.shrunk() > 0, "the same settings keep what was measured");
        a.configure(false, 60.0, 6);
        assert_eq!(a.apply(12), 12);
        assert_eq!(a.shrunk(), 0);
    }

    #[test]
    fn non_finite_frames_are_not_counted() {
        let mut a = AdaptiveDistance::new();
        a.configure(true, 60.0, 6);
        assert!(feed(&mut a, f32::NAN, WINDOW * 4, 12).is_empty());
        assert!(feed(&mut a, -1.0, WINDOW * 4, 12).is_empty());
        assert_eq!(a.shrunk(), 0);
    }
}
