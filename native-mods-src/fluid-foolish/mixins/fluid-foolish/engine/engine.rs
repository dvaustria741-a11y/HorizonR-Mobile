//! Fluid & Foolish movement animations for the Horizon player model.
//!
//! `clip` reads the pack's Bedrock-style animation clips and samples them, `locomotion` picks and
//! blends the clips for what the player is doing, and `rig` turns the blended bone poses into
//! additions to the player model's parts. Pure Rust: no Horizon types, so it is tested on its own.

pub mod actions;
pub mod clip;
pub mod locomotion;
pub mod rig;

pub use actions::{ActionInput, Actions, Compat};
pub use clip::{Clip, Clips, Pose};
pub use locomotion::{Input, Locomotion};
pub use rig::{PartAdd, parts};

/// The pack's item groups and defaults, compiled in (`data/compat.json`).
pub fn embedded_compat() -> Result<Compat, String> {
    Compat::parse(include_bytes!("data/compat.json"))
}

/// The pack's animation clips, compiled in (`data/clips.json`, built by `tools/build_native_mod.py`).
pub fn embedded() -> Result<Clips, String> {
    Clips::parse(include_bytes!("data/clips.json"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_embedded_pack_loads_and_every_sample_is_finite() {
        let clips = embedded().unwrap();
        assert!(clips.len() >= 260, "{} clips", clips.len());
        let walk = clips.get("normal_forward").unwrap();
        assert!((walk.length - 0.6389).abs() < 1.0e-3 && walk.looped);
        let bones = ["root", "waist", "body", "head", "left_arm", "right_arm", "left_leg", "right_leg"];
        for name in ["idle", "normal_forward", "sidestep_left", "jumping", "squash", "sneaking", "free_fall"] {
            let clip = clips.get(name).unwrap_or_else(|| panic!("missing {name}"));
            for step in 0..=40 {
                let t = clip.length * step as f32 / 40.0;
                for bone in bones {
                    let p = clip.sample(bone, t);
                    assert!(
                        p.position.iter().chain(&p.rotation).chain(&p.scale).all(|v| v.is_finite()),
                        "{name}/{bone} at {t}"
                    );
                }
            }
        }
    }

    #[test]
    fn looping_clips_wrap_and_hold_clips_hold() {
        let clips = embedded().unwrap();
        let walk = clips.get("normal_forward").unwrap();
        let a = walk.sample("right_leg", 0.1);
        let b = walk.sample("right_leg", 0.1 + walk.length * 3.0);
        for i in 0..3 {
            assert!((a.rotation[i] - b.rotation[i]).abs() < 1.0e-3);
        }
        let sneak = clips.get("sneaking").unwrap();
        assert!(sneak.hold_on_last_frame);
        assert_eq!(sneak.sample("body", sneak.length), sneak.sample("body", sneak.length + 5.0));
    }

    /// The axis conversion: crouching tips the torso forward and lowers the head forward and down
    /// (Java: forward is -Z, down is +Y, a forward lean is +X rotation).
    #[test]
    fn crouching_leans_the_body_forward_and_lowers_the_head() {
        let clips = embedded().unwrap();
        let sneak = clips.get("sneaking").unwrap();
        let mut rig = rig::Rig::new();
        for bone in ["root", "waist", "body", "head", "left_arm", "right_arm", "left_leg", "right_leg"] {
            rig.insert(bone.to_owned(), sneak.sample(bone, sneak.length));
        }
        let parts = parts(&rig);
        let body = parts.iter().find(|p| p.name == "body").unwrap();
        assert!(body.rotation[0] > 0.3, "body leans forward: {:?}", body.rotation);
        let head = parts.iter().find(|p| p.name == "head").unwrap();
        assert!(head.position[1] > 0.0, "head goes down: {:?}", head.position);
        assert!(head.position[2] < 0.0, "head goes forward: {:?}", head.position);
    }
}
