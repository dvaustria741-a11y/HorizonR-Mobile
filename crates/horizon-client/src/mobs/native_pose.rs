//! A native mod's pose for the own player's body (`Game::native_player_pose`).
//!
//! The pose is added to the parts after the vanilla animation (`anim::setup`), for the player
//! model and the armour layers that copy it, while the own body is drawn.

use std::cell::RefCell;
use std::collections::HashMap;

/// What a mod adds to one part: position in model pixels, rotation in radians (X, Y, Z).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PartPose {
    pub position: [f32; 3],
    pub rotation: [f32; 3],
}

/// Per-part additions, keyed by part name (`head`, `body`, `right_arm`, `left_arm`, `right_leg`,
/// `left_leg`; the outer layers follow their parts).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NativePose {
    parts: HashMap<String, PartPose>,
}

impl NativePose {
    /// Adds `position` (pixels) and `rotation` (radians) to part `name`; repeated calls add up.
    pub fn add(&mut self, name: &str, position: [f32; 3], rotation: [f32; 3]) {
        let p = self.parts.entry(name.to_owned()).or_default();
        for i in 0..3 {
            p.position[i] += position[i];
            p.rotation[i] += rotation[i];
        }
    }
    pub fn is_empty(&self) -> bool {
        self.parts.is_empty()
    }
    pub fn get(&self, name: &str) -> Option<&PartPose> {
        self.parts.get(name)
    }
}

thread_local! {
    static CURRENT: RefCell<Option<NativePose>> = const { RefCell::new(None) };
}

/// Makes `pose` the one `apply` adds, until `clear`. An empty pose is not kept.
pub fn set(pose: NativePose) {
    CURRENT.with(|c| *c.borrow_mut() = (!pose.is_empty()).then_some(pose));
}
/// Whether a pose is set.
pub fn active() -> bool {
    CURRENT.with(|c| c.borrow().is_some())
}
/// Runs `f` as if no pose were set.
pub fn suspended<R>(f: impl FnOnce() -> R) -> R {
    let saved = CURRENT.with(|c| c.borrow_mut().take());
    let out = f();
    CURRENT.with(|c| *c.borrow_mut() = saved);
    out
}
pub fn clear() {
    CURRENT.with(|c| *c.borrow_mut() = None);
}

/// Keeps a pose set for as long as the guard lives, so everything drawn for the player in that time
/// (the body, armour, cape, elytra, held items and shield, head item, parrots) follows it.
#[must_use = "the pose is cleared when the guard is dropped"]
pub struct Scope(());
impl Scope {
    pub fn set(pose: NativePose) -> Scope {
        set(pose);
        Scope(())
    }
}
impl Drop for Scope {
    fn drop(&mut self) {
        clear();
    }
}

/// Adds the current pose to `poses`: position at 0..3, rotation at 3..6 of each part's pose.
pub fn apply(poses: &mut super::anim::Poses) {
    CURRENT.with(|c| {
        let c = c.borrow();
        let Some(pose) = c.as_ref() else { return };
        for (name, add) in &pose.parts {
            if !poses.has(name) {
                continue;
            }
            let p = poses.get(name);
            for i in 0..3 {
                p[i] += add.position[i];
                p[3 + i] += add.rotation[i];
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn repeated_additions_add_up_and_an_empty_pose_is_not_kept() {
        let mut pose = NativePose::default();
        pose.add("head", [0.0, 1.0, 0.0], [0.5, 0.0, 0.0]);
        pose.add("head", [0.0, 1.0, 0.0], [0.25, 0.0, 0.0]);
        let head = pose.get("head").unwrap();
        assert_eq!(head.position, [0.0, 2.0, 0.0]);
        assert_eq!(head.rotation, [0.75, 0.0, 0.0]);
        set(NativePose::default());
        CURRENT.with(|c| assert!(c.borrow().is_none()));
        set(pose.clone());
        CURRENT.with(|c| assert!(c.borrow().is_some()));
        clear();
        {
            let _scope = Scope::set(pose);
            CURRENT.with(|c| assert!(c.borrow().is_some()));
        }
        CURRENT.with(|c| assert!(c.borrow().is_none()));
    }
}
