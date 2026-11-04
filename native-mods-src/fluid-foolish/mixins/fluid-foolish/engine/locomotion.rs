//! Picks and blends the pack's movement clips for what the player is doing: idle, walking,
//! sprinting, sidestepping, crouching, jumping, falling and landing. Ticks at 20 Hz.
//!
//! The numbers (stride lengths, cycle-rate caps, amplitude reference, fall distances) are the
//! pack's `compat/develop.json` defaults.

use crate::clip::Clips;
use crate::rig::Rig;

const DT: f32 = 0.05;
const BONES: [&str; 8] =
    ["root", "waist", "body", "head", "left_arm", "right_arm", "left_leg", "right_leg"];
// `compat/develop.json` defaults.animations.walking
const STRIDE_BLOCKS: f32 = 1.67;
const CROUCH_STRIDE_BLOCKS: f32 = 1.15;
const MAX_CYCLES: f32 = 1.25;
const SPRINT_MAX_CYCLES: f32 = 1.583_333_4;
const CROUCH_MAX_CYCLES: f32 = 0.75;
const MIN_DISPLACEMENT: f32 = 0.0005;
const MAX_DISPLACEMENT: f32 = 0.35;
const REFERENCE_SPEED: f32 = 0.215;
const MAX_AMPLITUDE: f32 = 1.2;
// falling / landing / crouch
const FALL_TRIGGER: f32 = 5.0;
const FREE_FALL_DISTANCE: f32 = 7.0;
const FREE_FALL_VELOCITY: f32 = 0.72;
const LANDING_FULL_DISTANCE: f32 = 5.0;
const CROUCH_TIMER_SCALE: f32 = 1.45;

/// What the player did this tick.
#[derive(Clone, Copy, Debug, Default)]
pub struct Input {
    /// Horizontal displacement since the last tick, in blocks.
    pub dx: f32,
    pub dz: f32,
    /// Facing, in degrees (Minecraft yaw: 0 faces +Z, clockwise from above).
    pub yaw: f32,
    pub on_ground: bool,
    /// Vertical velocity in blocks per tick.
    pub vy: f32,
    pub fall_distance: f32,
    pub sprinting: bool,
    pub crouching: bool,
    /// False while riding, swimming, flying, gliding, sleeping or dead: the pack's other
    /// behaviours own the body then, so these clips fade out.
    pub active: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
enum Dir {
    #[default]
    Forward,
    Backward,
    Left,
    Right,
}

#[derive(Clone, Debug)]
struct Air {
    jump: Option<&'static str>,
    t: f32,
    fall_t: f32,
}
#[derive(Clone, Debug)]
struct Landing {
    clip: &'static str,
    t: f32,
    strength: f32,
}

#[derive(Clone, Debug, Default)]
pub struct Locomotion {
    master: f32,
    idle_time: f32,
    idle_w: f32,
    phase: f32,
    cycles_per_second: f32,
    walk_w: f32,
    amplitude: f32,
    sprint_w: f32,
    dir: Dir,
    crouch_t: f32,
    air: Option<Air>,
    air_w: f32,
    landing: Option<Landing>,
    stop_t: Option<f32>,
    hop_left: bool,
    was_on_ground: bool,
    was_sprinting: bool,
    peak_fall: f32,
    last_vy: f32,
}

fn approach(value: &mut f32, target: f32, step: f32) {
    *value += (target - *value).clamp(-step, step);
}
fn wrap_degrees(mut d: f32) -> f32 {
    d = d.rem_euclid(360.0);
    if d >= 180.0 { d - 360.0 } else { d }
}

impl Locomotion {
    /// How much of the pack's movement animation is showing (0 off, 1 fully).
    pub fn strength(&self) -> f32 {
        self.master
    }

    pub fn tick(&mut self, clips: &Clips, input: &Input) {
        approach(&mut self.master, if input.active { 1.0 } else { 0.0 }, 0.2);
        if !input.active {
            self.air = None;
            self.landing = None;
            self.stop_t = None;
            self.peak_fall = 0.0;
            self.was_on_ground = true;
            self.was_sprinting = false;
            self.walk_w = 0.0;
            self.idle_w = 0.0;
            return;
        }
        let d = input.dx.hypot(input.dz).min(MAX_DISPLACEMENT);
        let moving = input.on_ground && d >= MIN_DISPLACEMENT;
        if moving {
            let angle = (-input.dx).atan2(input.dz).to_degrees();
            let rel = wrap_degrees(angle - input.yaw);
            self.dir = if rel.abs() <= 60.0 {
                Dir::Forward
            } else if rel.abs() >= 120.0 {
                Dir::Backward
            } else if rel < 0.0 {
                Dir::Left
            } else {
                Dir::Right
            };
            let (stride, cap) = if input.crouching {
                (CROUCH_STRIDE_BLOCKS, CROUCH_MAX_CYCLES)
            } else if input.sprinting {
                (STRIDE_BLOCKS, SPRINT_MAX_CYCLES)
            } else {
                (STRIDE_BLOCKS, MAX_CYCLES)
            };
            let step = (d / stride).min(cap * DT);
            self.phase = (self.phase + step).rem_euclid(1.0);
            self.cycles_per_second = step / DT;
            self.amplitude = (d / REFERENCE_SPEED).min(MAX_AMPLITUDE);
        } else {
            self.cycles_per_second = 0.0;
        }
        approach(&mut self.walk_w, if moving { 1.0 } else { 0.0 }, 0.25);
        approach(&mut self.sprint_w, if moving && input.sprinting { 1.0 } else { 0.0 }, 0.2);
        let resting = input.on_ground && !moving && self.air.is_none();
        approach(&mut self.idle_w, if resting { 1.0 } else { 0.0 }, 0.08);
        self.idle_time += DT;

        // Crouching eases the sneak clip in and out.
        let sneak_len = clips.get("sneaking").map_or(0.0, |c| c.length);
        let sneak_step = DT * CROUCH_TIMER_SCALE;
        self.crouch_t = (self.crouch_t + if input.crouching { sneak_step } else { -sneak_step })
            .clamp(0.0, sneak_len);

        // Sprint to a stop plays the stop clip once.
        if self.was_sprinting && !input.sprinting && input.on_ground && self.stop_t.is_none() {
            self.stop_t = Some(0.0);
        }
        if input.sprinting || !input.on_ground {
            self.stop_t = None;
        }
        if let Some(t) = &mut self.stop_t {
            *t += DT;
            if *t >= clips.get("stop_running").map_or(0.0, |c| c.length) {
                self.stop_t = None;
            }
        }
        self.was_sprinting = input.sprinting && input.on_ground;

        // Jumping, falling and landing.
        if !input.on_ground {
            if self.was_on_ground {
                self.peak_fall = 0.0;
                let jump = (input.vy > 0.1).then(|| {
                    if input.sprinting && d > 0.05 {
                        self.hop_left = !self.hop_left;
                        if self.hop_left { "hoprunning_left" } else { "hoprunning_right" }
                    } else {
                        "jumping"
                    }
                });
                self.air = Some(Air { jump, t: 0.0, fall_t: 0.0 });
            }
            self.peak_fall = self.peak_fall.max(input.fall_distance);
            if let Some(air) = &mut self.air {
                air.t += DT;
                let jump_len = air.jump.and_then(|n| clips.get(n)).map_or(0.0, |c| c.length);
                let falling = self.peak_fall >= FALL_TRIGGER || input.vy <= -FREE_FALL_VELOCITY;
                if air.t >= jump_len && falling {
                    air.fall_t += DT;
                }
            }
            approach(&mut self.air_w, 1.0, 0.2);
        } else {
            if !self.was_on_ground {
                let jumped = self.air.as_ref().is_some_and(|a| a.jump.is_some());
                if self.peak_fall >= 3.0 {
                    self.landing = Some(Landing {
                        clip: "squash",
                        t: 0.0,
                        strength: (self.peak_fall / LANDING_FULL_DISTANCE).clamp(0.3, 1.0),
                    });
                } else if jumped {
                    self.landing = Some(Landing { clip: "jump-landing", t: 0.0, strength: 1.0 });
                }
                self.air = None;
            }
            approach(&mut self.air_w, 0.0, 0.25);
        }
        self.was_on_ground = input.on_ground;
        self.last_vy = input.vy;
        if let Some(l) = &mut self.landing {
            l.t += DT;
            if l.t >= clips.get(l.clip).map_or(0.0, |c| c.length) {
                self.landing = None;
            }
        }
    }

    /// The blended bone poses, `partial` of the way to the next tick.
    pub fn sample(&self, clips: &Clips, partial: f32) -> Rig {
        let mut rig = Rig::new();
        for bone in BONES {
            rig.insert(bone.to_owned(), crate::clip::Pose::default());
        }
        let m = self.master;
        if m <= 0.0 {
            return rig;
        }
        let ahead = DT * partial.clamp(0.0, 1.0);
        let mut add = |name: &str, time: f32, weight: f32| {
            let (Some(clip), true) = (clips.get(name), weight.abs() > 1.0e-4) else { return };
            for bone in BONES {
                if !clip.has_bone(bone) {
                    continue;
                }
                let s = clip.sample(bone, time);
                let r = rig.get_mut(bone).expect("every bone is in the rig");
                for i in 0..3 {
                    r.position[i] += s.position[i] * weight;
                    r.rotation[i] += s.rotation[i] * weight;
                    r.scale[i] += (s.scale[i] - 1.0) * weight;
                }
            }
        };

        add("idle", self.idle_time + ahead, self.idle_w * m);

        if self.walk_w > 0.0 {
            let w = self.walk_w * self.amplitude.max(0.4) * m;
            let phase = self.phase + self.cycles_per_second * ahead;
            let cycle = phase.rem_euclid(1.0);
            let length = |n: &str| clips.get(n).map_or(0.0, |c| c.length);
            match self.dir {
                Dir::Forward | Dir::Backward => {
                    let cycle = if self.dir == Dir::Backward { 1.0 - cycle } else { cycle };
                    add("normal_forward", cycle * length("normal_forward"), w);
                    add("nf_add", cycle * length("nf_add"), w);
                }
                Dir::Left => add("sidestep_left", cycle * length("sidestep_left"), w),
                Dir::Right => add("sidestep_right", cycle * length("sidestep_right"), w),
            }
            if matches!(self.dir, Dir::Forward | Dir::Backward) {
                add("sprint_add", cycle * length("sprint_add"), self.sprint_w * m);
            }
        }

        if let Some(air) = &self.air {
            let jump = air.jump.and_then(|n| clips.get(n).map(|c| (n, c.length)));
            match jump {
                Some((name, len)) if air.t < len => add(name, air.t + ahead, self.air_w * m),
                _ if air.fall_t > 0.0 => {
                    let t = air.fall_t + ahead;
                    if self.peak_fall >= FREE_FALL_DISTANCE || self.last_vy <= -FREE_FALL_VELOCITY {
                        add("free_fall", t, self.air_w * m * (t / 2.0).clamp(0.0, 1.0));
                    } else {
                        add("legacy_review_falling", t, self.air_w * m);
                    }
                }
                _ => {}
            }
        }
        if let Some(l) = &self.landing {
            let len = clips.get(l.clip).map_or(1.0, |c| c.length);
            let fade = if l.clip == "jump-landing" { ((len - l.t) / 0.2).clamp(0.0, 1.0) } else { 1.0 };
            add(l.clip, l.t + ahead, l.strength * fade * m);
        }
        if let Some(t) = self.stop_t {
            let len = clips.get("stop_running").map_or(1.0, |c| c.length);
            add("stop_running", t + ahead, ((len - t) / 0.3).clamp(0.0, 1.0) * m);
        }
        add("sneaking", self.crouch_t, m);
        rig
    }
}

/// Used by `Input::default()` readers: a player standing on the ground, doing nothing.
pub fn standing() -> Input {
    Input { on_ground: true, active: true, ..Input::default() }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn clips() -> Clips {
        crate::embedded().unwrap()
    }
    fn walking(dx: f32, dz: f32, sprint: bool) -> Input {
        Input { dx, dz, on_ground: true, active: true, sprinting: sprint, ..Input::default() }
    }
    fn swing(rig: &Rig) -> f32 {
        rig["left_leg"].rotation[0].abs().max(rig["right_leg"].rotation[0].abs())
    }

    #[test]
    fn walking_advances_the_stride_and_swings_the_legs() {
        let c = clips();
        let mut l = Locomotion::default();
        for _ in 0..30 {
            l.tick(&c, &walking(0.0, 0.2, false));
        }
        assert_eq!(l.dir, Dir::Forward);
        let a = l.sample(&c, 0.0);
        for _ in 0..3 {
            l.tick(&c, &walking(0.0, 0.2, false));
        }
        let b = l.sample(&c, 0.0);
        assert!(swing(&a) > 1.0 || swing(&b) > 1.0, "legs swing");
        assert_ne!(a["left_leg"].rotation, b["left_leg"].rotation, "the stride moves on");
    }

    #[test]
    fn the_stride_follows_distance_not_time() {
        let c = clips();
        let (mut slow, mut fast) = (Locomotion::default(), Locomotion::default());
        for _ in 0..20 {
            slow.tick(&c, &walking(0.0, 0.08, false));
            fast.tick(&c, &walking(0.0, 0.2, false));
        }
        assert!(slow.cycles_per_second < fast.cycles_per_second);
        assert!((slow.cycles_per_second - 0.08 / STRIDE_BLOCKS / DT).abs() < 1.0e-3);
        // The fastest walk is capped at the pack's cycles per second.
        let mut run = Locomotion::default();
        for _ in 0..20 {
            run.tick(&c, &walking(0.0, 0.34, false));
        }
        assert!((run.cycles_per_second - MAX_CYCLES).abs() < 1.0e-3);
    }

    #[test]
    fn the_walk_direction_follows_the_facing() {
        let c = clips();
        let mut l = Locomotion::default();
        l.tick(&c, &walking(0.2, 0.0, false)); // east, facing south (+Z): the player's left
        assert_eq!(l.dir, Dir::Left);
        l.tick(&c, &walking(-0.2, 0.0, false));
        assert_eq!(l.dir, Dir::Right);
        l.tick(&c, &walking(0.0, -0.2, false));
        assert_eq!(l.dir, Dir::Backward);
    }

    #[test]
    fn standing_still_idles_and_never_moves_the_stride() {
        let c = clips();
        let mut l = Locomotion::default();
        for _ in 0..60 {
            l.tick(&c, &standing());
        }
        assert!(l.idle_w > 0.99 && l.walk_w == 0.0 && l.phase == 0.0);
        let rig = l.sample(&c, 0.5);
        assert!(rig.values().all(|p| p.position.iter().chain(&p.rotation).all(|v| v.is_finite())));
        assert!(rig["left_leg"].rotation == [0.0; 3], "the idle clip leaves the legs alone");
    }

    #[test]
    fn a_jump_plays_its_clip_and_a_hard_landing_squashes() {
        let c = clips();
        let mut l = Locomotion::default();
        l.tick(&c, &standing());
        l.tick(&c, &Input { on_ground: false, vy: 0.42, active: true, ..Input::default() });
        assert_eq!(l.air.as_ref().and_then(|a| a.jump), Some("jumping"));
        assert!(l.sample(&c, 0.0)["root"].position[1].abs() > 0.0, "the jump clip moves the root");
        for _ in 0..10 {
            l.tick(&c, &Input { on_ground: false, vy: -0.8, fall_distance: 8.0, active: true, ..Input::default() });
        }
        l.tick(&c, &standing());
        assert_eq!(l.landing.as_ref().map(|x| x.clip), Some("squash"));
        assert!(l.air.is_none());
    }

    #[test]
    fn crouching_eases_in_and_out_and_inactive_fades_out() {
        let c = clips();
        let mut l = Locomotion::default();
        for _ in 0..40 {
            l.tick(&c, &Input { crouching: true, ..standing() });
        }
        assert!(l.sample(&c, 0.0)["body"].rotation[0] > 20.0);
        for _ in 0..40 {
            l.tick(&c, &standing());
        }
        assert!(l.sample(&c, 0.0)["body"].rotation[0].abs() < 1.0e-3);
        for _ in 0..10 {
            l.tick(&c, &Input { active: false, ..standing() });
        }
        assert_eq!(l.strength(), 0.0);
        assert!(l.sample(&c, 0.0).values().all(|p| p.rotation == [0.0; 3]));
    }
}
