//! LiquidHorizon engine — owns all module state.
//!
//! Helpers (compiled into horizon-client) call the pub functions here.
//! This crate never imports horizon-client; data flows via primitive types only.

pub mod gui;
pub mod modules;

use modules::{Module, ModuleCategory, ModuleManager};
use std::sync::{Mutex, OnceLock};

// ── global state ─────────────────────────────────────────────────────────────

static STATE: OnceLock<Mutex<LiquidHorizon>> = OnceLock::new();

fn state() -> std::sync::MutexGuard<'static, LiquidHorizon> {
    STATE
        .get_or_init(|| Mutex::new(LiquidHorizon::new()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

// ── top-level game state snapshot passed by helpers ──────────────────────────

#[derive(Clone, Debug, Default)]
pub struct PlayerSnapshot {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub yaw: f32,
    pub pitch: f32,
    pub health: f32,
    pub food: i32,
    pub on_ground: bool,
}

/// A nearby entity as the frame hook can see from `game.mobs`.
#[derive(Clone, Debug)]
pub struct EntitySnapshot {
    pub id: u32,
    pub kind: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub health: f32,
}

// ── decisions the engine sends back to helpers ───────────────────────────────

/// What the frame hook should inject for KillAura.
#[derive(Clone, Debug, Default)]
pub struct FrameOutput {
    pub gui_open: bool,
}

/// What the player_tick hook should act on.
#[derive(Clone, Debug, Default)]
pub struct TickOutput {
    /// If Some, attack this entity id (helper calls game attack logic).
    pub killaura_target: Option<u32>,
    /// If Some, override yaw/pitch for AimAssist.
    pub aim_yaw: Option<f32>,
    pub aim_pitch: Option<f32>,
    /// Sprint if true.
    pub force_sprint: bool,
    /// Cancel fall damage.
    pub no_fall: bool,
}

/// Attack hook output.
#[derive(Clone, Debug, Default)]
pub struct AttackOutput {
    /// Cancel the hit (AntiKnockback = let through but track).
    pub cancel: bool,
    /// Boost crit: true if we should be in crit phase.
    pub crit: bool,
}

// ── main struct ──────────────────────────────────────────────────────────────

pub struct LiquidHorizon {
    pub modules: ModuleManager,
    pub gui: gui::ClickGui,
    /// Latest player snapshot updated each frame.
    pub player: PlayerSnapshot,
    /// Nearby entities updated each frame.
    pub entities: Vec<EntitySnapshot>,
    /// Whether LiquidHorizon is globally enabled.
    pub enabled: bool,
    /// Notifier queue: (module_name, enabled).
    pub notifications: Vec<(String, bool)>,
    /// KillAura CPS counter state.
    pub ka_last_attack_tick: u64,
    pub tick_counter: u64,
}

impl LiquidHorizon {
    fn new() -> Self {
        Self {
            modules: ModuleManager::new(),
            gui: gui::ClickGui::new(),
            player: PlayerSnapshot::default(),
            entities: Vec::new(),
            enabled: true,
            notifications: Vec::new(),
            ka_last_attack_tick: 0,
            tick_counter: 0,
        }
    }
}

// ── public API called by helpers ─────────────────────────────────────────────

/// Called from `frame.rs` helper every frame.
/// Returns whether the ClickGUI is open (helper should suppress game input).
pub fn on_frame(elapsed_ms: u64) -> FrameOutput {
    let s = state();
    let _ = elapsed_ms;
    FrameOutput { gui_open: s.gui.open }
}

/// Called from `hud.rs` with current HUD dimensions.
/// Returns the list of enabled modules to draw.
pub fn hud_modules() -> Vec<(String, String)> {
    let s = state();
    if !s.enabled {
        return Vec::new();
    }
    s.modules
        .all()
        .filter(|m| m.enabled)
        .map(|m| (m.name.clone(), m.tag()))
        .collect()
}

/// Called from `key.rs` on every key press/release.
pub fn on_key(key: &str, pressed: bool) -> KeyAction {
    let mut s = state();
    if !pressed {
        return KeyAction::None;
    }
    // Toggle GUI with Right Shift
    if key == "key.keyboard.right.shift" {
        s.gui.open = !s.gui.open;
        return KeyAction::None;
    }
    // Disable all on Insert
    if key == "key.keyboard.insert" {
        s.enabled = !s.enabled;
        return KeyAction::None;
    }
    // Module keybinds
    for m in s.modules.all_mut() {
        if m.keybind.as_deref() == Some(key) {
            m.enabled = !m.enabled;
            let note = (m.name.clone(), m.enabled);
            drop(s);
            state().notifications.push(note);
            return KeyAction::None;
        }
    }
    KeyAction::None
}

#[derive(Debug)]
pub enum KeyAction {
    None,
}

/// Called from `player_tick.rs` each server tick.
/// Returns what the helper should do.
pub fn on_player_tick(
    player: PlayerSnapshot,
    entities: Vec<EntitySnapshot>,
) -> TickOutput {
    let mut s = state();
    s.tick_counter += 1;
    s.player = player.clone();
    s.entities = entities.clone();

    if !s.enabled {
        return TickOutput::default();
    }

    let mut out = TickOutput::default();

    // ── KillAura ─────────────────────────────────────────────────────────────
    if s.modules.is_on("killaura") {
        let range = s.modules.float_setting("killaura", "range").unwrap_or(4.5);
        let cps   = s.modules.float_setting("killaura", "cps").unwrap_or(10.0);
        let ticks_per_hit = (20.0 / cps).max(1.0) as u64;

        if s.tick_counter.wrapping_sub(s.ka_last_attack_tick) >= ticks_per_hit {
            if let Some(target) = nearest_attackable(&s.entities, &player, range as f64) {
                out.killaura_target = Some(target);
                s.ka_last_attack_tick = s.tick_counter;
            }
        }
    }

    // ── AimAssist ────────────────────────────────────────────────────────────
    if s.modules.is_on("aimassist") {
        let range = s.modules.float_setting("aimassist", "range").unwrap_or(8.0);
        let speed = s.modules.float_setting("aimassist", "speed").unwrap_or(2.0);
        if let Some(target) = nearest_attackable(&s.entities, &player, range as f64) {
            let e = s.entities.iter().find(|e| e.id == target).unwrap();
            let (new_yaw, new_pitch) = look_at(&player, e, speed);
            out.aim_yaw   = Some(new_yaw);
            out.aim_pitch = Some(new_pitch);
        }
    }

    // ── AutoSprint ────────────────────────────────────────────────────────────
    if s.modules.is_on("autosprint") {
        out.force_sprint = true;
    }

    // ── NoFall ────────────────────────────────────────────────────────────────
    if s.modules.is_on("nofall") {
        out.no_fall = true;
    }

    out
}

/// Called from `hurt_enemy.rs` each time our hit lands.
pub fn on_hurt_enemy(entity: u32, kind: &str) -> AttackOutput {
    let s = state();
    if !s.enabled {
        return AttackOutput::default();
    }
    let crit = s.modules.is_on("criticals");
    let _ = (entity, kind);
    AttackOutput { cancel: false, crit }
}

/// Called from `attack_entity.rs`.
pub fn on_attack_entity(entity: i32, kind: &str) -> AttackOutput {
    let s = state();
    if !s.enabled {
        return AttackOutput::default();
    }
    let _ = (entity, kind);
    // AntiKnockback: don't cancel, just track
    AttackOutput { cancel: false, crit: s.modules.is_on("criticals") }
}

/// Called from `inventory_tick.rs`.  Returns Some(slot) when AutoEat should
/// equip a food item in that slot and trigger use.
pub fn on_inventory_tick(
    slot: i32,
    selected: bool,
    player_health: f32,
    player_food: i32,
) -> Option<i32> {
    let s = state();
    if !s.enabled || !s.modules.is_on("autoeat") {
        return None;
    }
    let threshold = s.modules.float_setting("autoeat", "food_threshold").unwrap_or(16.0) as i32;
    if player_food <= threshold && selected {
        return Some(slot);
    }
    let _ = (player_health,);
    None
}

/// Called from `use_item.rs`.  Returns true when FastUse should claim the use.
pub fn on_use_item(item: &str) -> bool {
    let s = state();
    if !s.enabled {
        return false;
    }
    if s.modules.is_on("fastuse") {
        return item.contains("potion") || item.contains("milk_bucket");
    }
    false
}

/// Called from `command.rs`.  Returns true when the command was ours.
pub fn on_command(command: &str) -> Option<String> {
    if !command.starts_with("lb ") && command != "lb" {
        return None;
    }
    let rest = command.strip_prefix("lb ").unwrap_or("").trim();
    let mut s = state();

    if rest.is_empty() || rest == "help" {
        let list: Vec<String> = s.modules.all().map(|m| m.name.clone()).collect();
        return Some(format!("§aLiquidHorizon modules: §f{}", list.join(", ")));
    }

    // `.lb <module>` → toggle
    if let Some(m) = s.modules.find_mut(rest) {
        m.enabled = !m.enabled;
        let (name, on) = (m.name.clone(), m.enabled);
        let color = if on { "§a" } else { "§c" };
        return Some(format!("{}{} {}", color, name, if on { "ON" } else { "OFF" }));
    }

    // `.lb <module> <setting> <value>`
    let parts: Vec<&str> = rest.splitn(3, ' ').collect();
    if parts.len() == 3 {
        if let Ok(val) = parts[2].parse::<f32>() {
            if s.modules.set_float(parts[0], parts[1], val) {
                return Some(format!("§aSet {}.{} = {}", parts[0], parts[1], val));
            }
        }
    }

    Some(format!("§cUnknown module: {}", rest))
}

/// Returns all command names this mod owns (for autocomplete).
pub fn command_names() -> &'static [&'static str] {
    &["lb"]
}

/// Returns the mod info for Options > Mods.
pub fn mod_info() -> (String, String, String) {
    (
        "LiquidHorizon".into(),
        "LiquidHorizon 1.0".into(),
        "LiquidBounce ported to Horizon — KillAura, AimAssist, ESP and more.".into(),
    )
}

/// Returns settings page buttons for this mod.
pub fn settings_buttons() -> Vec<String> {
    let s = state();
    s.modules
        .all()
        .map(|m| {
            let flag = if m.enabled { "§aON" } else { "§cOFF" };
            format!("{} — {}", m.name, flag)
        })
        .collect()
}

/// A settings button was clicked.
pub fn on_settings_click(button: usize) {
    let mut s = state();
    if let Some(m) = s.modules.all_mut().nth(button) {
        m.enabled = !m.enabled;
    }
}

/// Pop the next notification (module toggled).
pub fn pop_notification() -> Option<(String, bool)> {
    state().notifications.pop()
}

/// Returns ESP targets: (id, x, y, z, kind) for the HUD/frame helper.
pub fn esp_targets() -> Vec<(u32, f64, f64, f64, String)> {
    let s = state();
    if !s.enabled || !s.modules.is_on("esp") {
        return Vec::new();
    }
    s.entities
        .iter()
        .map(|e| (e.id, e.x, e.y, e.z, e.kind.clone()))
        .collect()
}

/// Called from `tooltip.rs` — returns extra lines.
pub fn on_tooltip(item: &str) -> Vec<String> {
    let s = state();
    if !s.enabled || !s.modules.is_on("iteminfo") {
        return Vec::new();
    }
    vec![format!("§7[LH] Item ID: §f{}", item)]
}

// ── geometry helpers ──────────────────────────────────────────────────────────

fn nearest_attackable(
    entities: &[EntitySnapshot],
    player: &PlayerSnapshot,
    range: f64,
) -> Option<u32> {
    entities
        .iter()
        .filter(|e| {
            let dx = e.x - player.x;
            let dy = e.y - (player.y + 1.62);
            let dz = e.z - player.z;
            let dist = (dx * dx + dy * dy + dz * dz).sqrt();
            dist <= range && e.health > 0.0 && !e.kind.contains("item")
        })
        .min_by(|a, b| {
            let da = dist2(a, player);
            let db = dist2(b, player);
            da.partial_cmp(&db).unwrap_or(std::cmp::Ordering::Equal)
        })
        .map(|e| e.id)
}

fn dist2(e: &EntitySnapshot, p: &PlayerSnapshot) -> f64 {
    let dx = e.x - p.x;
    let dy = e.y - p.y;
    let dz = e.z - p.z;
    dx * dx + dy * dy + dz * dz
}

/// Returns (yaw, pitch) smoothly rotated toward entity.
fn look_at(player: &PlayerSnapshot, entity: &EntitySnapshot, speed: f32) -> (f32, f32) {
    let dx = entity.x - player.x;
    let dy = (entity.y + 0.85) - (player.y + 1.62);
    let dz = entity.z - player.z;
    let target_yaw   = -(dx.atan2(dz) as f32).to_degrees();
    let horiz        = (dx * dx + dz * dz).sqrt() as f32;
    let target_pitch = -(dy.atan2(horiz as f64) as f32).to_degrees();

    let delta_yaw   = angle_diff(target_yaw,   player.yaw);
    let delta_pitch = angle_diff(target_pitch,  player.pitch);
    let step = speed * 2.0;
    (
        player.yaw   + delta_yaw.clamp(-step, step),
        player.pitch + delta_pitch.clamp(-step, step),
    )
}

fn angle_diff(a: f32, b: f32) -> f32 {
    let mut d = a - b;
    while d > 180.0 { d -= 360.0; }
    while d < -180.0 { d += 360.0; }
    d
}
