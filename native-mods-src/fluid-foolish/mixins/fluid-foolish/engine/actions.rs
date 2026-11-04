//! What the hands do: attacking, mining, switching items, holding a tool or lantern, eating and
//! drinking, blocking with a shield, drawing a bow and looking through a spyglass. The clips for
//! each item group come from the pack's `compat/develop.json` (`item_groups`, `empty_hand`).

use crate::clip::Clips;
use serde_json::Value;
use std::collections::BTreeMap;

const DT: f32 = 0.05;
const FADE_IN: f32 = 0.08;
const FADE_OUT: f32 = 0.15;
const DEFAULT_RETAIN: f32 = 0.35;

/// One animation slot of an item group.
#[derive(Clone, Debug, Default)]
pub struct Slot {
    /// The clip (or the clips to alternate between).
    pub clips: Vec<String>,
    /// Clips played on top (the pack's additive overlays).
    pub overlays: Vec<String>,
    /// How much of the walking and idling shows on the bones the clip animates.
    pub retain: f32,
    pub hold_last_frame: bool,
    /// `base_pose_retain`: per bone, how much locomotion stays (overrides `retain`).
    pub bones: BTreeMap<String, f32>,
}

#[derive(Clone, Debug, Default)]
pub struct Group {
    slots: BTreeMap<String, Slot>,
}

/// The pack's item groups and the empty hand.
#[derive(Clone, Debug, Default)]
pub struct Compat {
    groups: BTreeMap<String, Group>,
    empty: Group,
    defaults: BTreeMap<String, Slot>,
}

fn strings(v: Option<&Value>) -> Vec<String> {
    match v {
        Some(Value::String(s)) => vec![s.clone()],
        Some(Value::Array(a)) => a.iter().filter_map(|x| x.as_str().map(str::to_owned)).collect(),
        _ => Vec::new(),
    }
}

fn slot(name: &str, value: &Value) -> Option<(String, Slot)> {
    let obj = value.as_object()?;
    let mut clips = strings(obj.get("clip"));
    clips.extend(strings(obj.get("variants")));
    // Eating and drinking name their loop; bows name charge, hold and release by slot.
    for key in ["looping", "charge", "hold", "release", "idle", "use", "equip"] {
        if clips.is_empty() {
            clips = match obj.get(key) {
                Some(Value::String(s)) if name != "sequence" => vec![s.clone()],
                _ => Vec::new(),
            };
        }
    }
    let overlays = obj
        .get("overlays")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|o| o.get("clip").and_then(Value::as_str).map(str::to_owned)).collect())
        .unwrap_or_default();
    let retain = obj
        .get("locomotion_influence")
        .and_then(|l| l.get("walk"))
        .and_then(Value::as_f64)
        .map_or(DEFAULT_RETAIN, |v| (v as f32).clamp(0.0, 1.0));
    let hold_last_frame = obj.get("hold_last_frame").and_then(Value::as_bool).unwrap_or(false);
    let bones = obj
        .get("base_pose_retain")
        .and_then(Value::as_object)
        .map(|m| m.iter().filter_map(|(k, v)| v.as_f64().map(|v| (k.clone(), v as f32))).collect())
        .unwrap_or_default();
    if clips.is_empty() {
        return None;
    }
    Some((name.to_owned(), Slot { clips, overlays, retain, hold_last_frame, bones }))
}

fn group(value: &Value) -> Group {
    let mut slots = BTreeMap::new();
    if let Some(anims) = value.get("animations").and_then(Value::as_object) {
        for (name, v) in anims {
            if let Some((n, s)) = slot(name, v) {
                slots.insert(n, s);
            }
        }
    }
    Group { slots }
}

impl Compat {
    pub fn parse(bytes: &[u8]) -> Result<Compat, String> {
        let json: Value = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
        let groups = json
            .get("item_groups")
            .and_then(Value::as_object)
            .ok_or("no item_groups")?
            .iter()
            .map(|(k, v)| (k.clone(), group(v)))
            .collect();
        let empty = json.get("empty_hand").map(group).unwrap_or_default();
        let defaults = json
            .get("defaults")
            .and_then(Value::as_object)
            .map(|m| m.iter().filter_map(|(k, v)| slot(k, v)).collect())
            .unwrap_or_default();
        Ok(Compat { groups, empty, defaults })
    }
    fn slot(&self, group: Option<&str>, name: &str) -> Option<&Slot> {
        group.and_then(|g| self.groups.get(g)).and_then(|g| g.slots.get(name))
    }
    /// The slot for an action, falling back to the empty hand's (a punch, a mining swing).
    fn action(&self, group: Option<&str>, name: &str) -> Option<&Slot> {
        self.slot(group, name).or_else(|| self.empty.slots.get(name))
    }
}

/// The pack's item group of an item id.
pub fn group_of(id: &str) -> Option<&'static str> {
    let name = id.strip_prefix("minecraft:").unwrap_or(id);
    let ends = |s: &str| name.ends_with(s);
    Some(match name {
        "bow" => "BOW",
        "crossbow" => "CROSSBOW",
        "shield" => "SHIELD",
        "spyglass" => "SPYGLASS",
        "trident" => "TRIDENT",
        "mace" => "MACE",
        "fishing_rod" => "FISHING_ROD",
        "shears" => "SHEARS",
        "flint_and_steel" => "FLINT",
        "bucket" => "EMPTY_BUCKET",
        "brush" => "BRUSH",
        "filled_map" | "map" => "MAP",
        "lantern" | "soul_lantern" => "LANTERN",
        "torch" | "soul_torch" | "redstone_torch" => "TORCH",
        "snowball" | "egg" | "ender_pearl" | "splash_potion" | "lingering_potion" | "experience_bottle" => {
            "THROWABLE"
        }
        _ if ends("_sword") => "SWORD",
        _ if ends("_pickaxe") => "PICKAXE",
        _ if ends("_axe") => "AXE",
        _ if ends("_shovel") => "SHOVEL",
        _ if ends("_hoe") => "HOE",
        _ if ends("_spear") => "SPEAR",
        _ if ends("_bucket") => "FILLED_BUCKET",
        _ if ends("_horn") => "HORNS",
        _ if ends("bundle") => "BUNDLE",
        _ => return None,
    })
}

/// How an item in use is used.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Use {
    Eat,
    Drink,
    Block,
    Bow,
    Spyglass,
}

/// The use of an item in use, by id: `None` for items with no pose of their own.
pub fn use_of(id: &str) -> Option<Use> {
    let name = id.strip_prefix("minecraft:").unwrap_or(id);
    Some(match name {
        "shield" => Use::Block,
        "bow" => Use::Bow,
        "spyglass" => Use::Spyglass,
        "potion" | "milk_bucket" | "honey_bottle" | "ominous_bottle" => Use::Drink,
        "trident" | "crossbow" | "brush" | "goat_horn" | "mace" | "fishing_rod" | "shears" | "bundle" => {
            return None;
        }
        _ if name.contains("spear") => return None,
        _ => Use::Eat,
    })
}

/// What the hands did this tick.
#[derive(Clone, Debug, Default)]
pub struct ActionInput {
    /// The main hand's item id (empty: none).
    pub held: String,
    /// The item in use and for how many ticks, if any.
    pub using: Option<(String, u32)>,
    /// The swing counter: a rise is a swing started.
    pub swings: u32,
    pub mining: bool,
    /// Armor pieces by slot, to see one put on: boots, leggings, chestplate, helmet ids.
    pub armor: [String; 4],
}

#[derive(Clone, Debug)]
struct Shot {
    clip: String,
    overlays: Vec<String>,
    retain: f32,
    bones: BTreeMap<String, f32>,
    t: f32,
}

#[derive(Clone, Debug, Default)]
pub struct Actions {
    baseline: bool,
    held: String,
    armor: [String; 4],
    swings: u32,
    variant: usize,
    attack: Option<Shot>,
    switch: Option<Shot>,
    armor_in: Option<Shot>,
    mining_w: f32,
    mining_t: f32,
    pose_w: f32,
    using_w: f32,
    using: Option<(Use, u32)>,
    use_t: f32,
    group: Option<&'static str>,
}

/// An action clip to add: its name, time, weight and how much locomotion it leaves on its bones.
#[derive(Clone, Debug)]
pub struct Layer {
    pub clip: String,
    pub time: f32,
    pub weight: f32,
    pub retain: f32,
    pub bones: BTreeMap<String, f32>,
}

impl Layer {
    /// How much locomotion stays on `bone` while this plays.
    pub fn retain_for(&self, bone: &str) -> f32 {
        self.bones.get(bone).copied().unwrap_or(self.retain)
    }
}

fn envelope(t: f32, len: f32) -> f32 {
    ((t / FADE_IN).min((len - t) / FADE_OUT)).clamp(0.0, 1.0)
}
fn length(clips: &Clips, name: &str) -> f32 {
    clips.get(name).map_or(0.0, |c| c.length)
}

impl Actions {
    pub fn tick(&mut self, clips: &Clips, compat: &Compat, input: &ActionInput) {
        let group = group_of(&input.held);
        // The first tick only learns what is held, so joining a world plays nothing.
        if !self.baseline {
            self.baseline = true;
            self.held = input.held.clone();
            self.armor = input.armor.clone();
            self.swings = input.swings;
        }
        self.group = group;

        // Switching or changing the held item.
        if input.held != self.held {
            let slot = compat
                .slot(group, "item_change")
                .or_else(|| compat.slot(group, "hand_in"))
                .or_else(|| compat.default_slot("item_switch"));
            self.switch = slot.and_then(|s| {
                s.clips.first().map(|c| Shot {
                    clip: resolve(clips, c),
                    overlays: s.overlays.iter().map(|o| resolve(clips, o)).collect(),
                    retain: s.retain,
                    bones: s.bones.clone(),
                    t: 0.0,
                })
            });
            self.held = input.held.clone();
        }
        // Putting armour on.
        for (i, now) in input.armor.iter().enumerate() {
            if *now != self.armor[i] && !now.is_empty() {
                let name = if i == 3 { "armor_helmet" } else { "armor" };
                self.armor_in = compat.default_slot(name).and_then(|s| {
                    s.clips.first().map(|c| Shot {
                        clip: resolve(clips, c),
                        overlays: Vec::new(),
                        retain: s.retain,
                        bones: s.bones.clone(),
                        t: 0.0,
                    })
                });
            }
        }
        self.armor = input.armor.clone();

        // A swing that is not mining attacks.
        let swung = input.swings != self.swings;
        self.swings = input.swings;
        if swung && !input.mining {
            let attack = compat.action(group, "attack");
            if let Some(s) = attack {
                let pick = s.clips[self.variant % s.clips.len()].clone();
                self.variant += 1;
                let restart = self.attack.as_ref().is_none_or(|a| a.t >= length(clips, &a.clip) * 0.3);
                if restart {
                    self.attack = Some(Shot {
                        clip: resolve(clips, &pick),
                        overlays: s.overlays.iter().map(|o| resolve(clips, o)).collect(),
                        retain: s.retain,
                        bones: s.bones.clone(),
                        t: 0.0,
                    });
                }
            }
        }
        for shot in [&mut self.attack, &mut self.switch, &mut self.armor_in] {
            if let Some(s) = shot {
                s.t += DT;
                if s.t >= length(clips, &s.clip) {
                    *shot = None;
                }
            }
        }

        // Mining loops while the block is being broken.
        let mining = input.mining && compat.action(group, "mining").is_some();
        approach(&mut self.mining_w, f32::from(mining), 0.3);
        if mining {
            self.mining_t += DT;
        } else if self.mining_w <= 0.0 {
            self.mining_t = 0.0;
        }

        // An item in use.
        self.using = input
            .using
            .as_ref()
            .and_then(|(id, ticks)| use_of(id).map(|u| (u, *ticks)));
        approach(&mut self.using_w, f32::from(self.using.is_some()), 0.3);
        match &self.using {
            Some((_, ticks)) => self.use_t = *ticks as f32 * DT,
            None if self.using_w <= 0.0 => self.use_t = 0.0,
            None => {}
        }
        // Holding a tool or a lantern shows its pose when nothing else is going on.
        let pose = compat.slot(group, "pose").is_some() && self.attack.is_none() && !mining && self.using.is_none();
        approach(&mut self.pose_w, f32::from(pose), 0.2);
    }

    /// The clips to add, with the weight each has.
    pub fn layers(&self, clips: &Clips, compat: &Compat, partial: f32) -> Vec<Layer> {
        let ahead = DT * partial.clamp(0.0, 1.0);
        let mut out = Vec::new();
        let none: BTreeMap<String, f32> = BTreeMap::new();
        let mut push = |clip: &str, time: f32, weight: f32, retain: f32, bones: &BTreeMap<String, f32>| {
            let clip = resolve(clips, clip);
            if weight > 1.0e-4 && clips.get(&clip).is_some() {
                out.push(Layer { clip, time, weight, retain, bones: bones.clone() });
            }
        };
        let shot = |s: &Shot, out: &mut dyn FnMut(&str, f32, f32, f32, &BTreeMap<String, f32>)| {
            let len = length(clips, &s.clip);
            let w = envelope(s.t, len);
            out(&s.clip, s.t + ahead, w, s.retain, &s.bones);
            for o in &s.overlays {
                out(o, s.t + ahead, w, 1.0, &none);
            }
        };
        for s in [&self.attack, &self.switch, &self.armor_in].into_iter().flatten() {
            shot(s, &mut |c, t, w, r, b| push(c, t, w, r, b));
        }
        if self.mining_w > 0.0 {
            if let Some(s) = compat.action(self.group, "mining") {
                let t = self.mining_t + ahead;
                push(&s.clips[0], t, self.mining_w, s.retain, &s.bones);
                for o in &s.overlays {
                    push(o, t, self.mining_w, 1.0, &none);
                }
            }
        }
        if self.pose_w > 0.0 {
            if let Some(s) = compat.slot(self.group, "pose") {
                push(&s.clips[0], 0.0, self.pose_w, s.retain, &s.bones);
            }
        }
        if let Some((kind, _)) = self.using.as_ref().or(None) {
            let t = self.use_t + ahead;
            let w = self.using_w;
            match kind {
                Use::Eat => {
                    if let Some(s) = compat.slot(Some("CONSUMABLE"), "eating") {
                        push(&s.clips[0], t, w, s.retain, &s.bones);
                    }
                }
                Use::Drink => {
                    if let Some(s) = compat.slot(Some("CONSUMABLE"), "drinking") {
                        push(&s.clips[0], t, w, s.retain, &s.bones);
                        for o in &s.overlays {
                            push(o, t, w, 1.0, &none);
                        }
                    }
                }
                Use::Block => {
                    if let Some(s) = compat.slot(Some("SHIELD"), "use_item") {
                        let len = length(clips, &s.clips[0]);
                        push(&s.clips[0], t.min(len), w, s.retain, &s.bones);
                    }
                }
                Use::Bow => {
                    let charge = compat.slot(Some("BOW"), "charge").map(|s| s.clips[0].clone());
                    if let Some(c) = charge {
                        let len = length(clips, &c);
                        push(&c, t.min(len), w, 0.1, &none);
                    }
                }
                Use::Spyglass => {
                    if let Some(s) = compat.slot(Some("SPYGLASS"), "spyglass_use") {
                        push(&s.clips[0], t.min(length(clips, &s.clips[0])), w, s.retain, &s.bones);
                        for o in &s.overlays {
                            push(o, t, w, 1.0, &none);
                        }
                    }
                }
            }
        }
        out
    }
}

impl Compat {
    /// One of the pack's defaults (`item_switch`, `armor`, `nausea`, ...).
    pub fn default_slot(&self, name: &str) -> Option<&Slot> {
        self.defaults.get(name)
    }
}

/// A clip name as the pack writes it: hand clips come as `<name>_right` and `<name>_left`.
fn resolve(clips: &Clips, name: &str) -> String {
    if clips.get(name).is_some() {
        return name.to_owned();
    }
    let right = format!("{name}_right");
    if clips.get(&right).is_some() { right } else { name.to_owned() }
}

fn approach(value: &mut f32, target: f32, step: f32) {
    *value += (target - *value).clamp(-step, step);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Locomotion;

    fn data() -> (Clips, Compat) {
        (crate::embedded().unwrap(), crate::embedded_compat().unwrap())
    }
    fn held(id: &str) -> ActionInput {
        ActionInput { held: id.into(), ..ActionInput::default() }
    }

    #[test]
    fn items_map_to_the_packs_groups_and_uses() {
        assert_eq!(group_of("minecraft:diamond_sword"), Some("SWORD"));
        assert_eq!(group_of("minecraft:netherite_pickaxe"), Some("PICKAXE"));
        assert_eq!(group_of("minecraft:bow"), Some("BOW"));
        assert_eq!(group_of("minecraft:dirt"), None);
        assert_eq!(use_of("minecraft:shield"), Some(Use::Block));
        assert_eq!(use_of("minecraft:apple"), Some(Use::Eat));
        assert_eq!(use_of("minecraft:potion"), Some(Use::Drink));
        assert_eq!(use_of("minecraft:crossbow"), None);
    }

    #[test]
    fn the_pack_config_is_read() {
        let (clips, compat) = data();
        let sword = compat.slot(Some("SWORD"), "attack").expect("a sword attack");
        assert!(sword.clips.iter().all(|c| clips.get(c).is_some()), "{:?}", sword.clips);
        let punch = compat.action(None, "attack").unwrap();
        assert_eq!(punch.clips, ["punch"]);
        assert_eq!(punch.bones.get("right_arm"), Some(&0.0));
        assert!(compat.default_slot("item_switch").is_some());
    }

    #[test]
    fn a_swing_attacks_with_the_held_tools_clip_or_a_punch_and_ends() {
        let (clips, compat) = data();
        let mut a = Actions::default();
        a.tick(&clips, &compat, &held("minecraft:diamond_sword"));
        a.tick(&clips, &compat, &ActionInput { swings: 1, ..held("minecraft:diamond_sword") });
        a.tick(&clips, &compat, &ActionInput { swings: 1, ..held("minecraft:diamond_sword") });
        let layers = a.layers(&clips, &compat, 0.0);
        let sword = compat.slot(Some("SWORD"), "attack").unwrap();
        assert!(layers.iter().any(|l| sword.clips.contains(&l.clip) && l.weight > 0.0), "{layers:?}");
        let mut hand = Actions::default();
        hand.tick(&clips, &compat, &held(""));
        hand.tick(&clips, &compat, &ActionInput { swings: 1, ..held("") });
        let punch = hand.layers(&clips, &compat, 0.0);
        assert!(punch.iter().any(|l| l.clip.starts_with("punch")), "{punch:?} {:?}", clips.get("punch").map(|c| c.length));
        for _ in 0..60 {
            hand.tick(&clips, &compat, &ActionInput { swings: 1, ..held("") });
        }
        assert!(hand.layers(&clips, &compat, 0.0).is_empty(), "it ends");
    }

    #[test]
    fn mining_loops_while_mining_and_fades_after() {
        let (clips, compat) = data();
        let mut a = Actions::default();
        for _ in 0..10 {
            a.tick(&clips, &compat, &ActionInput { mining: true, swings: 5, ..held("minecraft:iron_pickaxe") });
        }
        let on = a.layers(&clips, &compat, 0.0);
        assert!(on.iter().any(|l| l.weight > 0.9), "{on:?}");
        for _ in 0..10 {
            a.tick(&clips, &compat, &ActionInput { swings: 5, ..held("minecraft:iron_pickaxe") });
        }
        let rest = a.layers(&clips, &compat, 0.0);
        assert!(rest.iter().all(|l| l.weight < 1.0e-3 || l.clip.contains("pose")), "{rest:?}");
    }

    #[test]
    fn changing_the_held_item_plays_the_switch_but_joining_does_not() {
        let (clips, compat) = data();
        let mut a = Actions::default();
        a.tick(&clips, &compat, &held("minecraft:stick"));
        assert!(a.layers(&clips, &compat, 0.0).iter().all(|l| l.weight < 1.0e-3 || l.clip.contains("pose")));
        a.tick(&clips, &compat, &held("minecraft:diamond_sword"));
        a.tick(&clips, &compat, &held("minecraft:diamond_sword"));
        assert!(!a.layers(&clips, &compat, 0.0).is_empty(), "the switch plays");
    }

    #[test]
    fn putting_armour_on_plays_the_armour_clip() {
        let (clips, compat) = data();
        let mut a = Actions::default();
        a.tick(&clips, &compat, &ActionInput::default());
        let mut worn = ActionInput::default();
        worn.armor[3] = "minecraft:iron_helmet".into();
        a.tick(&clips, &compat, &worn);
        a.tick(&clips, &compat, &worn);
        assert!(a.layers(&clips, &compat, 0.0).iter().any(|l| l.clip == "armor_in_helmet"));
    }

    #[test]
    fn items_in_use_show_their_pose() {
        let (clips, compat) = data();
        for (id, label) in [("minecraft:apple", "eat"), ("minecraft:potion", "drink"), ("minecraft:shield", "shield"), ("minecraft:bow", "bow")] {
            let mut a = Actions::default();
            for t in 0..8 {
                a.tick(&clips, &compat, &ActionInput { using: Some((id.into(), t)), ..held(id) });
            }
            assert!(a.layers(&clips, &compat, 0.0).iter().any(|l| l.weight > 0.5), "{label}: nothing plays");
        }
    }

    #[test]
    fn an_action_takes_over_the_bones_it_animates() {
        let (clips, compat) = data();
        let mut l = Locomotion::default();
        for _ in 0..30 {
            l.tick(&clips, &crate::locomotion::standing());
            l.tick_actions(&clips, &compat, &held(""));
        }
        let before = l.sample_with(&clips, &compat, 0.0);
        l.tick_actions(&clips, &compat, &ActionInput { swings: 1, ..held("") });
        l.tick_actions(&clips, &compat, &ActionInput { swings: 1, ..held("") });
        l.tick_actions(&clips, &compat, &ActionInput { swings: 1, ..held("") });
        let during = l.sample_with(&clips, &compat, 0.0);
        assert_ne!(before["right_arm"].rotation, during["right_arm"].rotation, "the punch moves the arm");
        assert!(during.values().all(|p| p.rotation.iter().chain(&p.position).all(|v| v.is_finite())));
    }
}
