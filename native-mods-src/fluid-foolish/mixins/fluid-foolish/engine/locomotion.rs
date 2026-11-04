//! Picks and blends the pack's movement clips for what the player is doing: idle, walking,
//! sprinting, sidestepping, crouching, jumping, falling, landing, swimming, crawling, climbing,
//! flying, gliding, sitting, getting hurt, turning on the spot, idle events and emotes.
//! Ticks at 20 Hz.
//!
//! The numbers (stride lengths, cycle-rate caps, amplitude reference, fall distances) are the
//! pack's `compat/develop.json` defaults.

use crate::actions::{ActionInput, Actions, Compat};
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
// swimming / elytra / climbing / turn in place / idle events (`compat/develop.json`)
const SWIM_SPEED: f32 = 1.25;
const SWIM_BASELINE: f32 = 90.0;
const CRAWL_BASELINE: f32 = 85.0;
const CRAWL_STRIDE_BLOCKS: f32 = 0.8;
const ELYTRA_FAST: f32 = 0.9;
const BANK_DEGREES: f32 = 30.0;
const BANK_SENSITIVITY: f32 = 5.0;
const CLIMB_TIMER_SCALE: f32 = 8.0;
const TURN_DEGREES: f32 = 60.0;
const IDLE_EVENTS: [&str; 2] = ["idle_event_one_backup", "idle_event_two_backup"];
const IDLE_EVENT_MIN_TICKS: u32 = 160;
const IDLE_EVENT_SPAN_TICKS: u32 = 140;
const FADE_IN: f32 = 0.1;
const FADE_OUT: f32 = 0.15;

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
    /// Swimming in water.
    pub swimming: bool,
    /// The head is under water.
    pub underwater: bool,
    /// Crawling: the swimming pose on land.
    pub crawling: bool,
    /// Flying in creative or spectator-like flight.
    pub flying: bool,
    /// Gliding with an elytra.
    pub gliding: bool,
    /// Sitting in a boat or a minecart.
    pub seated: bool,
    /// Whole-velocity speed in blocks per tick (for the elytra's fast loop).
    pub speed: f32,
    /// Set on the tick the player got hurt: where it came from, in degrees relative to the
    /// facing (0: in front).
    pub hurt_from: Option<f32>,
    /// False while sleeping, dead or in spectator: every clip fades out.
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
/// A clip played once (or until cancelled when it loops) on top of the rest.
#[derive(Clone, Debug)]
struct Oneshot {
    clips: Vec<&'static str>,
    t: f32,
}
#[derive(Clone, Debug)]
struct Emote {
    clip: String,
    t: f32,
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
    // modes
    sit_w: f32,
    glide_w: f32,
    fly_w: f32,
    swim_w: f32,
    crawl_w: f32,
    climb_w: f32,
    swim_t: f32,
    swim_vertical: bool,
    swim_underwater: bool,
    crawl_phase: f32,
    glide_t: f32,
    glide_fast: bool,
    bank: f32,
    climb_t: f32,
    last_yaw: Option<f32>,
    vy_history: [f32; 3],
    // one-shots
    hurt: Option<Oneshot>,
    turn: Option<Oneshot>,
    turn_right: bool,
    yaw_acc: f32,
    idle_event: Option<Oneshot>,
    next_idle_event: u32,
    rng: u32,
    emote: Option<Emote>,
    emote_w: f32,
    actions: Actions,
}

fn approach(value: &mut f32, target: f32, step: f32) {
    *value += (target - *value).clamp(-step, step);
}
fn wrap_degrees(mut d: f32) -> f32 {
    d = d.rem_euclid(360.0);
    if d >= 180.0 { d - 360.0 } else { d }
}
/// Fade in at the start and out at the end of a clip of `len` seconds played for `t`.
fn envelope(t: f32, len: f32) -> f32 {
    ((t / FADE_IN).min((len - t) / FADE_OUT)).clamp(0.0, 1.0)
}

/// Adds `weight` times clip `name` at `time` to `rig`. `base` is a root pitch the clip carries to
/// lay the body down, which Horizon already does for swimming and crawling, so it is taken back out.
fn add(rig: &mut Rig, clips: &Clips, name: &str, time: f32, weight: f32, base: f32) {
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
        if bone == "root" {
            r.rotation[0] -= base * weight;
        }
    }
}

impl Locomotion {
    /// How much of the pack's movement animation is showing (0 off, 1 fully).
    pub fn strength(&self) -> f32 {
        self.master
    }

    /// Plays emote `clip` (a looping one runs until the player moves or plays it again).
    pub fn play_emote(&mut self, clip: &str) {
        if self.emote.as_ref().is_some_and(|e| e.clip == clip) {
            self.emote = None;
        } else {
            self.emote = Some(Emote { clip: clip.to_owned(), t: 0.0 });
        }
    }

    fn random(&mut self) -> u32 {
        self.rng = self.rng.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        self.rng >> 8
    }

    /// What the hands did this tick (attacks, mining, item switches, items in use).
    pub fn tick_actions(&mut self, clips: &Clips, compat: &Compat, input: &ActionInput) {
        if self.master > 0.0 {
            self.actions.tick(clips, compat, input);
        }
    }

    pub fn tick(&mut self, clips: &Clips, input: &Input) {
        approach(&mut self.master, if input.active { 1.0 } else { 0.0 }, 0.2);
        if !input.active {
            *self = Locomotion { master: self.master, rng: self.rng, ..Locomotion::default() };
            self.was_on_ground = true;
            return;
        }
        let d = input.dx.hypot(input.dz).min(MAX_DISPLACEMENT);
        let special = input.swimming || input.flying || input.gliding || input.seated;

        // Climbing: a steady vertical speed off the ground, as on a ladder or vines.
        self.vy_history = [self.vy_history[1], self.vy_history[2], input.vy];
        let steady = |lo: f32, hi: f32| self.vy_history.iter().all(|v| (lo..=hi).contains(v));
        let climbing = !input.on_ground
            && !special
            && !input.crawling
            && (steady(0.09, 0.14) || steady(-0.16, -0.13));
        let special = special || climbing;

        let moving = input.on_ground && !input.crawling && d >= MIN_DISPLACEMENT;
        if moving || (input.crawling && d >= MIN_DISPLACEMENT) {
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
        }
        if moving {
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

        // Modes fade in and out against each other and against the ground layers.
        approach(&mut self.sit_w, f32::from(input.seated), 0.2);
        approach(&mut self.glide_w, f32::from(input.gliding), 0.2);
        approach(&mut self.fly_w, f32::from(input.flying && !input.gliding && !input.seated), 0.2);
        approach(&mut self.swim_w, f32::from(input.swimming && !input.seated), 0.2);
        approach(&mut self.crawl_w, f32::from(input.crawling && !special), 0.2);
        approach(&mut self.climb_w, f32::from(climbing), 0.25);
        self.idle_time += DT;
        if input.seated {
            self.swim_w = 0.0;
        }
        if input.swimming {
            self.swim_t += DT * SWIM_SPEED;
            self.swim_underwater = input.underwater;
            self.swim_vertical = input.underwater && input.vy.abs() > d;
        }
        if input.crawling {
            let step = (d / CRAWL_STRIDE_BLOCKS).min(1.2 * DT);
            self.crawl_phase = (self.crawl_phase + step).rem_euclid(1.0);
        }
        if input.gliding {
            self.glide_t += DT;
            self.glide_fast = input.speed >= ELYTRA_FAST;
            let rate = self.last_yaw.map_or(0.0, |y| wrap_degrees(input.yaw - y));
            let target = (rate * BANK_SENSITIVITY).clamp(-BANK_DEGREES, BANK_DEGREES);
            self.bank += (target - self.bank) * 0.25;
        } else {
            self.bank *= 0.8;
        }
        if climbing {
            self.climb_t += input.vy.abs() * CLIMB_TIMER_SCALE * DT * input.vy.signum();
        }
        let yaw_rate = self.last_yaw.map_or(0.0, |y| wrap_degrees(input.yaw - y));
        self.last_yaw = Some(input.yaw);

        let ground = !special && !input.crawling;
        approach(&mut self.walk_w, if moving && ground { 1.0 } else { 0.0 }, 0.25);
        approach(&mut self.sprint_w, if moving && input.sprinting { 1.0 } else { 0.0 }, 0.2);
        let resting = input.on_ground && !moving && ground && self.air.is_none();
        approach(&mut self.idle_w, if resting { 1.0 } else { 0.0 }, 0.08);

        // Crouching eases the sneak clip in and out.
        let sneak_len = clips.get("sneaking").map_or(0.0, |c| c.length);
        let sneak_step = DT * CROUCH_TIMER_SCALE;
        self.crouch_t = (self.crouch_t + if input.crouching && ground { sneak_step } else { -sneak_step })
            .clamp(0.0, sneak_len);

        // Sprint to a stop plays the stop clip once.
        if self.was_sprinting && !input.sprinting && input.on_ground && self.stop_t.is_none() {
            self.stop_t = Some(0.0);
        }
        if input.sprinting || !input.on_ground || !ground {
            self.stop_t = None;
        }
        if let Some(t) = &mut self.stop_t {
            *t += DT;
            if *t >= clips.get("stop_running").map_or(0.0, |c| c.length) {
                self.stop_t = None;
            }
        }
        self.was_sprinting = input.sprinting && input.on_ground;

        // Jumping, falling and landing, off the special modes.
        let airborne = !input.on_ground && ground;
        if airborne {
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
            if !self.was_on_ground && input.on_ground && ground {
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
            }
            if input.on_ground || !ground {
                self.air = None;
            }
            approach(&mut self.air_w, 0.0, 0.25);
        }
        self.was_on_ground = input.on_ground || !ground;
        self.last_vy = input.vy;
        if let Some(l) = &mut self.landing {
            l.t += DT;
            if l.t >= clips.get(l.clip).map_or(0.0, |c| c.length) {
                self.landing = None;
            }
        }

        // Getting hurt: a one-shot recoil, by where the hit came from.
        if let Some(from) = input.hurt_from {
            let front = from.to_radians().cos() >= 0.0;
            self.hurt = Some(Oneshot { clips: vec![if front { "hurt_backward" } else { "hurt_forward" }], t: 0.0 });
        }
        Self::advance(&mut self.hurt, clips);

        // Turning on the spot steps the feet round once the head has turned far enough.
        if resting && self.emote.is_none() {
            self.yaw_acc += yaw_rate;
            if self.yaw_acc.abs() >= TURN_DEGREES && self.turn.is_none() {
                self.turn_right = self.yaw_acc > 0.0;
                self.turn = Some(Oneshot {
                    clips: if self.turn_right { vec!["step_right", "step_add_right"] } else { vec!["step_left", "step_add_left"] },
                    t: 0.0,
                });
                self.yaw_acc = 0.0;
            }
        } else {
            self.yaw_acc = 0.0;
        }
        Self::advance(&mut self.turn, clips);

        // An idle event now and then while standing still.
        let calm = resting && self.idle_w > 0.9 && self.turn.is_none() && self.emote.is_none();
        if calm && self.idle_event.is_none() {
            if self.next_idle_event == 0 {
                self.next_idle_event = IDLE_EVENT_MIN_TICKS + self.random() % IDLE_EVENT_SPAN_TICKS;
            }
            self.next_idle_event -= 1;
            if self.next_idle_event == 0 {
                let pick = IDLE_EVENTS[(self.random() as usize) % IDLE_EVENTS.len()];
                self.idle_event = Some(Oneshot { clips: vec![pick], t: 0.0 });
            }
        }
        if !calm {
            self.idle_event = None;
        }
        Self::advance(&mut self.idle_event, clips);

        // An emote plays until it ends, or until the player moves, jumps, crouches or is hurt.
        let cancel = moving || special || !input.on_ground || input.crouching || input.crawling || input.hurt_from.is_some();
        if let Some(e) = &mut self.emote {
            e.t += DT;
            let ended = clips.get(&e.clip).is_none_or(|c| !c.looped && e.t >= c.length);
            if cancel || ended {
                self.emote = None;
            }
        }
        approach(&mut self.emote_w, f32::from(self.emote.is_some()), 0.25);
    }

    fn advance(slot: &mut Option<Oneshot>, clips: &Clips) {
        if let Some(o) = slot {
            o.t += DT;
            let len = o.clips.iter().filter_map(|n| clips.get(n)).map(|c| c.length).fold(0.0, f32::max);
            if o.t >= len {
                *slot = None;
            }
        }
    }

    /// The blended bone poses without the hand actions.
    pub fn sample(&self, clips: &Clips, partial: f32) -> Rig {
        self.sample_with(clips, &Compat::default(), partial)
    }

    /// The blended bone poses, `partial` of the way to the next tick.
    pub fn sample_with(&self, clips: &Clips, compat: &Compat, partial: f32) -> Rig {
        let mut rig = Rig::new();
        for bone in BONES {
            rig.insert(bone.to_owned(), crate::clip::Pose::default());
        }
        let m = self.master;
        if m <= 0.0 {
            return rig;
        }
        let ahead = DT * partial.clamp(0.0, 1.0);
        // `base` is a root pitch the clip carries to lay the body down, which Horizon already
        // does for swimming and crawling, so it is taken back out.
        let special = self.sit_w.max(self.glide_w).max(self.fly_w).max(self.swim_w).max(self.crawl_w).max(self.climb_w);
        let ground = (1.0 - special).max(0.0) * (1.0 - self.emote_w);

        add(&mut rig, clips, "idle", self.idle_time + ahead, self.idle_w * m * (1.0 - self.emote_w), 0.0);

        if self.walk_w > 0.0 {
            let w = self.walk_w * self.amplitude.max(0.4) * m * ground;
            let phase = self.phase + self.cycles_per_second * ahead;
            let cycle = phase.rem_euclid(1.0);
            let length = |n: &str| clips.get(n).map_or(0.0, |c| c.length);
            match self.dir {
                Dir::Forward | Dir::Backward => {
                    let cycle = if self.dir == Dir::Backward { 1.0 - cycle } else { cycle };
                    add(&mut rig, clips, "normal_forward", cycle * length("normal_forward"), w, 0.0);
                    add(&mut rig, clips, "nf_add", cycle * length("nf_add"), w, 0.0);
                    add(&mut rig, clips, "sprint_add", cycle * length("sprint_add"), self.sprint_w * m * ground, 0.0);
                }
                Dir::Left => add(&mut rig, clips, "sidestep_left", cycle * length("sidestep_left"), w, 0.0),
                Dir::Right => add(&mut rig, clips, "sidestep_right", cycle * length("sidestep_right"), w, 0.0),
            }
        }

        if let Some(air) = &self.air {
            let jump = air.jump.and_then(|n| clips.get(n).map(|c| (n, c.length)));
            let w = self.air_w * m * ground;
            match jump {
                Some((name, len)) if air.t < len => add(&mut rig, clips, name, air.t + ahead, w, 0.0),
                _ if air.fall_t > 0.0 => {
                    let t = air.fall_t + ahead;
                    if self.peak_fall >= FREE_FALL_DISTANCE || self.last_vy <= -FREE_FALL_VELOCITY {
                        add(&mut rig, clips, "free_fall", t, w * (t / 2.0).clamp(0.0, 1.0), 0.0);
                    } else {
                        add(&mut rig, clips, "legacy_review_falling", t, w, 0.0);
                    }
                }
                _ => {}
            }
        }
        if let Some(l) = &self.landing {
            let len = clips.get(l.clip).map_or(1.0, |c| c.length);
            let fade = if l.clip == "jump-landing" { ((len - l.t) / 0.2).clamp(0.0, 1.0) } else { 1.0 };
            add(&mut rig, clips, l.clip, l.t + ahead, l.strength * fade * m * ground, 0.0);
        }
        if let Some(t) = self.stop_t {
            let len = clips.get("stop_running").map_or(1.0, |c| c.length);
            add(&mut rig, clips, "stop_running", t + ahead, ((len - t) / 0.3).clamp(0.0, 1.0) * m * ground, 0.0);
        }
        add(&mut rig, clips, "sneaking", self.crouch_t, m * (1.0 - self.sit_w), 0.0);

        // Modes.
        if self.sit_w > 0.0 {
            add(&mut rig, clips, "sit", 0.0, self.sit_w * m, 0.0);
            add(&mut rig, clips, "sit_idle", self.idle_time + ahead, self.sit_w * m, 0.0);
        }
        if self.fly_w > 0.0 {
            add(&mut rig, clips, "creative_fly_pose", 0.0, self.fly_w * m, 0.0);
            add(&mut rig, clips, "idle", self.idle_time + ahead, self.fly_w * m, 0.0);
        }
        if self.glide_w > 0.0 {
            let clip = if self.glide_fast { "elytra_intense_loop" } else { "elytra_loop" };
            add(&mut rig, clips, clip, self.glide_t + ahead, self.glide_w * m, 0.0);
            if let Some(root) = rig.get_mut("root") {
                root.rotation[2] -= self.bank * self.glide_w * m;
            }
        }
        if self.swim_w > 0.0 {
            let clip = match (self.swim_underwater, self.swim_vertical) {
                (false, _) => "swimming_overwater",
                (true, false) => "swimming_underwater",
                (true, true) => "swimming_vertical",
            };
            add(&mut rig, clips, clip, self.swim_t + ahead * SWIM_SPEED, self.swim_w * m, SWIM_BASELINE);
        }
        if self.crawl_w > 0.0 {
            let name = match self.dir {
                Dir::Forward => "crawling_forward",
                Dir::Backward => "crawling_backward",
                Dir::Left => "crawling_leftward",
                Dir::Right => "crawling_rightward",
            };
            let len = clips.get(name).map_or(0.0, |c| c.length);
            add(&mut rig, clips, name, self.crawl_phase * len, self.crawl_w * m, CRAWL_BASELINE);
        }
        if self.climb_w > 0.0 {
            add(&mut rig, clips, "climbing_up", self.climb_t, self.climb_w * m, 0.0);
        }

        // One-shots over the top.
        for slot in [&self.hurt, &self.turn, &self.idle_event] {
            if let Some(o) = slot {
                let len = o.clips.iter().filter_map(|n| clips.get(n)).map(|c| c.length).fold(0.0, f32::max);
                for name in &o.clips {
                    add(&mut rig, clips, name, o.t + ahead, envelope(o.t, len) * m, 0.0);
                }
            }
        }
        if let Some(e) = &self.emote {
            let looped = clips.get(&e.clip).is_some_and(|c| c.looped);
            let len = clips.get(&e.clip).map_or(0.0, |c| c.length);
            let w = if looped { (e.t / FADE_IN).clamp(0.0, 1.0) } else { envelope(e.t, len) };
            add(&mut rig, clips, &e.clip, e.t + ahead, w * m, 0.0);
        }

        // The hands: each action clip takes over the bones it animates, leaving as much of the
        // walking and idling there as the pack says.
        let layers = self.actions.layers(clips, compat, partial);
        for layer in &layers {
            let Some(clip) = clips.get(&layer.clip) else { continue };
            for bone in BONES {
                if !clip.has_bone(bone) {
                    continue;
                }
                let k = 1.0 - layer.weight * m * (1.0 - layer.retain_for(bone)).clamp(0.0, 1.0);
                let r = rig.get_mut(bone).expect("every bone is in the rig");
                for i in 0..3 {
                    r.position[i] *= k;
                    r.rotation[i] *= k;
                    r.scale[i] = 1.0 + (r.scale[i] - 1.0) * k;
                }
            }
        }
        for layer in &layers {
            add(&mut rig, clips, &layer.clip, layer.time, layer.weight * m, 0.0);
        }
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

    fn run(l: &mut Locomotion, c: &Clips, ticks: usize, input: Input) {
        for _ in 0..ticks {
            l.tick(c, &input);
        }
    }

    #[test]
    fn swimming_plays_the_swim_clip_without_laying_the_body_down_twice() {
        let c = clips();
        let mut l = Locomotion::default();
        run(&mut l, &c, 30, Input { swimming: true, underwater: true, dz: 0.1, active: true, ..Input::default() });
        let rig = l.sample(&c, 0.0);
        // The clip carries a 90 degree root pitch; Horizon already lays the body down.
        assert!(rig["root"].rotation[0].abs() < 20.0, "{:?}", rig["root"].rotation);
        assert!(swing(&rig) > 0.5 || rig["left_arm"].rotation != [0.0; 3], "the limbs move");
        assert!(!l.swim_vertical);
        run(&mut l, &c, 5, Input { swimming: true, underwater: true, vy: 0.2, active: true, ..Input::default() });
        assert!(l.swim_vertical, "mostly up or down is the vertical clip");
    }

    #[test]
    fn crawling_follows_the_distance_crawled() {
        let c = clips();
        let mut l = Locomotion::default();
        run(&mut l, &c, 20, Input { crawling: true, on_ground: true, active: true, ..Input::default() });
        assert_eq!(l.crawl_phase, 0.0, "still: the stride holds");
        run(&mut l, &c, 20, Input { crawling: true, on_ground: true, dz: 0.05, active: true, ..Input::default() });
        assert!(l.crawl_phase > 0.0);
        let rig = l.sample(&c, 0.0);
        assert!(rig["root"].rotation[0].abs() < 20.0, "{:?}", rig["root"].rotation);
    }

    #[test]
    fn a_steady_climb_speed_climbs_and_a_jump_does_not() {
        let c = clips();
        let mut l = Locomotion::default();
        run(&mut l, &c, 12, Input { vy: 0.1176, active: true, ..Input::default() });
        assert!(l.climb_w > 0.9 && l.climb_t > 0.0, "climbing up");
        let mut j = Locomotion::default();
        for vy in [0.42, 0.33, 0.25, 0.17, 0.1, 0.02, -0.07, -0.15, -0.23] {
            j.tick(&c, &Input { vy, active: true, ..Input::default() });
        }
        assert!(j.climb_w < 0.3, "a jump arc is not a climb: {}", j.climb_w);
    }

    #[test]
    fn gliding_banks_into_a_turn_and_goes_fast_over_the_threshold() {
        let c = clips();
        let mut l = Locomotion::default();
        for i in 0..20 {
            l.tick(&c, &Input { gliding: true, yaw: i as f32 * 4.0, speed: 1.2, active: true, ..Input::default() });
        }
        assert!(l.glide_fast);
        assert!(l.bank > 10.0, "turning right banks right: {}", l.bank);
        let rig = l.sample(&c, 0.0);
        assert!(rig["root"].rotation[2] < -5.0, "a right bank rolls to the right: {:?}", rig["root"].rotation);
    }

    #[test]
    fn sitting_lowers_the_body_and_flying_holds_the_fly_pose() {
        let c = clips();
        let mut l = Locomotion::default();
        run(&mut l, &c, 20, Input { seated: true, active: true, ..Input::default() });
        assert!(l.sample(&c, 0.0)["root"].position[1] < -5.0);
        let mut f = Locomotion::default();
        run(&mut f, &c, 20, Input { flying: true, active: true, ..Input::default() });
        assert!(f.sample(&c, 0.0)["root"].position[1] < -1.0);
    }

    #[test]
    fn a_hit_from_the_front_recoils_back_and_plays_once() {
        let c = clips();
        let mut l = Locomotion::default();
        l.tick(&c, &Input { hurt_from: Some(0.0), ..standing() });
        assert_eq!(l.hurt.as_ref().map(|h| h.clips[0]), Some("hurt_backward"));
        l.tick(&c, &Input { hurt_from: Some(180.0), ..standing() });
        assert_eq!(l.hurt.as_ref().map(|h| h.clips[0]), Some("hurt_forward"));
        run(&mut l, &c, 40, standing());
        assert!(l.hurt.is_none(), "it ends");
    }

    #[test]
    fn turning_far_on_the_spot_steps_the_feet_round() {
        let c = clips();
        let mut l = Locomotion::default();
        run(&mut l, &c, 10, standing());
        for i in 0..20 {
            l.tick(&c, &Input { yaw: i as f32 * 5.0, ..standing() });
        }
        assert_eq!(l.turn.as_ref().map(|t| t.clips[0]), Some("step_right"));
        let mut r = Locomotion::default();
        run(&mut r, &c, 10, standing());
        for i in 0..20 {
            r.tick(&c, &Input { yaw: -(i as f32) * 5.0, ..standing() });
        }
        assert_eq!(r.turn.as_ref().map(|t| t.clips[0]), Some("step_left"));
        // Walking while turning never steps.
        let mut w = Locomotion::default();
        for i in 0..40 {
            w.tick(&c, &Input { yaw: i as f32 * 5.0, dz: 0.2, ..standing() });
        }
        assert!(w.turn.is_none());
    }

    #[test]
    fn standing_idle_long_enough_plays_an_idle_event() {
        let c = clips();
        let mut l = Locomotion::default();
        let mut seen = false;
        for _ in 0..400 {
            l.tick(&c, &standing());
            seen |= l.idle_event.is_some();
        }
        assert!(seen, "one is due within 15 seconds");
        let mut m = Locomotion::default();
        for _ in 0..400 {
            m.tick(&c, &Input { dz: 0.2, ..standing() });
            assert!(m.idle_event.is_none(), "never while walking");
        }
    }

    #[test]
    fn an_emote_plays_until_the_player_moves() {
        let c = clips();
        let mut l = Locomotion::default();
        run(&mut l, &c, 10, standing());
        l.play_emote("hello_nleg");
        run(&mut l, &c, 5, standing());
        assert!(l.emote.is_some() && l.emote_w > 0.5);
        let still = l.sample(&c, 0.0);
        assert!(still.values().any(|p| p.rotation != [0.0; 3]));
        l.tick(&c, &Input { dz: 0.2, ..standing() });
        assert!(l.emote.is_none(), "walking away ends it");
        l.play_emote("hello_nleg");
        l.play_emote("hello_nleg");
        assert!(l.emote.is_none(), "the same emote again stops it");
    }
}
