//! Automatic render scale: lowers the world's resolution while frames run over
//! budget and raises it again once there is room, so a GPU-bound phone holds
//! its frame rate. The Resolution Scale setting stays the ceiling.

/// Frame budget the scale aims for (60 fps).
const TARGET_MS: f32 = 1000.0 / 60.0;
/// Frames averaged per decision.
const WINDOW: u32 = 30;
/// The scale steps by this much, so a change (which rebuilds the world's
/// render targets) happens at most once a window.
const STEP: f32 = 0.1;
/// Lowest share of the chosen scale the controller goes to.
const FLOOR: f32 = 0.6;
/// Lower the scale when the window's average is this far over budget.
const OVER: f32 = 1.12;
/// A window within this of the budget counts as headroom for raising.
const UNDER: f32 = 1.04;
/// Longest wait, in windows, before trying to raise the scale again.
const MAX_WAIT: u32 = 16;
/// A frame this long is a load or a pause, not a measure of the GPU.
const HITCH_MS: f32 = 250.0;

pub(super) struct DynScale {
    enabled: bool,
    factor: f32,
    sum_ms: f32,
    frames: u32,
    /// Windows at or under budget in a row.
    calm: u32,
    /// Calm windows needed before the next raise; doubles when a raise had to be undone.
    wait: u32,
    raised: bool,
}

impl DynScale {
    pub(super) fn new(enabled: bool) -> Self {
        Self {
            enabled,
            factor: 1.0,
            sum_ms: 0.0,
            frames: 0,
            calm: 0,
            wait: 2,
            raised: false,
        }
    }

    /// The share of the chosen resolution scale to render at (1.0 when off).
    pub(super) fn factor(&self) -> f32 {
        if self.enabled { self.factor } else { 1.0 }
    }

    /// Takes one frame's time; true when `factor` changed.
    pub(super) fn update(&mut self, frame_ms: f32) -> bool {
        if !self.enabled || !frame_ms.is_finite() || frame_ms <= 0.0 {
            return false;
        }
        if frame_ms > HITCH_MS {
            self.sum_ms = 0.0;
            self.frames = 0;
            return false;
        }
        self.sum_ms += frame_ms;
        self.frames += 1;
        if self.frames < WINDOW {
            return false;
        }
        let average = self.sum_ms / self.frames as f32;
        self.sum_ms = 0.0;
        self.frames = 0;
        let before = self.factor;
        if average > TARGET_MS * OVER {
            if self.raised {
                // The last raise did not hold: wait longer before the next.
                self.wait = (self.wait * 2).min(MAX_WAIT);
                self.raised = false;
            }
            self.calm = 0;
            self.factor = (self.factor - STEP).max(FLOOR);
        } else if average <= TARGET_MS * UNDER {
            self.calm += 1;
            if self.calm >= self.wait && self.factor < 1.0 {
                self.factor = (self.factor + STEP).min(1.0);
                self.calm = 0;
                self.raised = true;
            }
        } else {
            self.calm = 0;
        }
        (self.factor - before).abs() > f32::EPSILON
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(scale: &mut DynScale, ms: f32, frames: u32) -> bool {
        (0..frames).fold(false, |changed, _| scale.update(ms) | changed)
    }

    #[test]
    fn slow_frames_lower_the_scale_to_the_floor() {
        let mut scale = DynScale::new(true);
        assert!(run(&mut scale, 25.0, WINDOW));
        assert!((scale.factor() - 0.9).abs() < 1e-4);
        run(&mut scale, 25.0, WINDOW * 10);
        assert!((scale.factor() - FLOOR).abs() < 1e-4);
    }

    #[test]
    fn frames_on_budget_raise_it_back_after_a_wait() {
        let mut scale = DynScale::new(true);
        run(&mut scale, 25.0, WINDOW * 3);
        let lowered = scale.factor();
        assert!(lowered < 1.0);
        run(&mut scale, 10.0, WINDOW * 20);
        assert!((scale.factor() - 1.0).abs() < 1e-4);
    }

    #[test]
    fn a_raise_that_does_not_hold_waits_longer() {
        let mut scale = DynScale::new(true);
        run(&mut scale, 25.0, WINDOW);
        run(&mut scale, 10.0, WINDOW * 2);
        assert!((scale.factor() - 1.0).abs() < 1e-4);
        run(&mut scale, 25.0, WINDOW);
        assert_eq!(scale.wait, 4);
    }

    #[test]
    fn hitches_and_a_disabled_controller_change_nothing() {
        let mut scale = DynScale::new(true);
        assert!(!run(&mut scale, 400.0, WINDOW * 4));
        assert_eq!(scale.factor(), 1.0);
        let mut off = DynScale::new(false);
        assert!(!run(&mut off, 40.0, WINDOW * 4));
        assert_eq!(off.factor(), 1.0);
    }
}
