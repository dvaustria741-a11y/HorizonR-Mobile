//! Automatic render scale: lowers the world's resolution while frames run over
//! budget and raises it again once there is room, so a GPU-bound phone holds
//! its frame rate. The Resolution Scale setting stays the ceiling.

/// The frame rate the scale aims for unless a lower cap is set (60 fps).
const TARGET_FPS: f32 = 60.0;
/// Frames averaged per decision.
const WINDOW: u32 = 30;
/// The scale steps by this much, so a change (which rebuilds the world's
/// render targets) happens at most once a window.
const STEP: f32 = 0.1;
/// Lowest share of the chosen scale the controller goes to unless `set_floor` says otherwise.
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
    /// Frame budget in milliseconds.
    target_ms: f32,
    factor: f32,
    /// Lowest `factor` the controller goes to.
    floor: f32,
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
            target_ms: 1000.0 / TARGET_FPS,
            factor: 1.0,
            floor: FLOOR,
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

    /// Switches the controller on or off and sets the frame rate it aims for
    /// (the frame cap, within 20..=60: a capped frame is not a slow one);
    /// true when `factor` changed.
    pub(super) fn configure(&mut self, enabled: bool, target_fps: f32) -> bool {
        let target_ms = 1000.0 / target_fps.clamp(20.0, TARGET_FPS);
        if self.enabled == enabled && (self.target_ms - target_ms).abs() < 1e-3 {
            return false;
        }
        let before = self.factor();
        self.enabled = enabled;
        self.target_ms = target_ms;
        self.sum_ms = 0.0;
        self.frames = 0;
        self.calm = 0;
        self.raised = false;
        if !enabled {
            self.factor = 1.0;
        }
        (self.factor() - before).abs() > f32::EPSILON
    }

    /// Sets the lowest share of the chosen resolution scale to go to
    /// (0.25..=1.0); true when `factor` changed.
    pub(super) fn set_floor(&mut self, floor: f32) -> bool {
        let floor = floor.clamp(0.25, 1.0);
        if (self.floor - floor).abs() < 1e-4 {
            return false;
        }
        self.floor = floor;
        let before = self.factor();
        self.factor = self.factor.max(floor);
        (self.factor() - before).abs() > f32::EPSILON
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
        if average > self.target_ms * OVER {
            if self.raised {
                // The last raise did not hold: wait longer before the next.
                self.wait = (self.wait * 2).min(MAX_WAIT);
                self.raised = false;
            }
            self.calm = 0;
            self.factor = (self.factor - STEP).max(self.floor);
        } else if average <= self.target_ms * UNDER {
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
    fn a_frame_cap_is_the_budget_not_a_slow_gpu() {
        let mut scale = DynScale::new(true);
        assert!(!scale.configure(true, 30.0));
        // 33 ms frames are on budget at a 30 fps cap.
        assert!(!run(&mut scale, 33.0, WINDOW * 4));
        assert_eq!(scale.factor(), 1.0);
        // Over a 60 fps cap's budget, the same frames are slow.
        scale.configure(true, 60.0);
        assert!(run(&mut scale, 33.0, WINDOW));
    }

    #[test]
    fn the_floor_sets_how_low_the_scale_goes() {
        let mut scale = DynScale::new(true);
        assert!(!scale.set_floor(FLOOR));
        scale.set_floor(0.3);
        run(&mut scale, 25.0, WINDOW * 20);
        assert!((scale.factor() - 0.3).abs() < 1e-4);
        // Raising the floor lifts a scale that is under it.
        assert!(scale.set_floor(0.8));
        assert!((scale.factor() - 0.8).abs() < 1e-4);
    }

    #[test]
    fn turning_it_off_restores_the_full_scale() {
        let mut scale = DynScale::new(true);
        run(&mut scale, 25.0, WINDOW * 3);
        assert!(scale.factor() < 1.0);
        assert!(scale.configure(false, 60.0));
        assert_eq!(scale.factor(), 1.0);
        scale.configure(true, 60.0);
        assert_eq!(scale.factor(), 1.0);
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
