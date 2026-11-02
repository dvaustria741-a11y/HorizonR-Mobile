//! LiquidHorizon engine — owns all module state.
//! Helpers (in horizon-client) call pub functions here.
//! Never imports horizon-client; all data flows via primitives.

pub mod gui;
pub mod modules;

use modules::ModuleManager;
use std::sync::{Mutex, OnceLock};

// ── global state ─────────────────────────────────────────────────────────────

static STATE: OnceLock<Mutex<LiquidHorizon>> = OnceLock::new();

fn state() -> std::sync::MutexGuard<'static, LiquidHorizon> {
    STATE
        .get_or_init(|| Mutex::new(LiquidHorizon::new()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

// ── player / entity snapshots ─────────────────────────────────────────────────

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
    pub delta_y: f64,
}

#[derive(Clone, Debug)]
pub struct EntitySnapshot {
    pub id: u32,
    pub kind: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub health: f32,
    pub is_player: bool,
}

// ── per-frame outputs sent to helpers ────────────────────────────────────────

#[derive(Clone, Debug, Default)]
pub struct FrameOutput {
    pub gui_open: bool,
    /// Gamma override for FullBright — None means no change.
    pub fullbright_gamma: Option<f64>,
    /// Scaffold: place a block at this relative offset from player (dx,dy,dz)
    /// using the item in hotbar slot `scaffold_slot`.
    pub scaffold_place: Option<([i32; 3], i32)>,
}

#[derive(Clone, Debug, Default)]
pub struct TickOutput {
    pub killaura_target: Option<u32>,
    pub aim_yaw:         Option<f32>,
    pub aim_pitch:       Option<f32>,
    pub force_sprint:    bool,
    pub no_fall:         bool,
    /// Velocity module: cancel or scale incoming knockback.
    pub zero_velocity:   bool,
    /// Triggerbot: attack entity currently under crosshair.
    pub trigger_entity:  Option<u32>,
}

#[derive(Clone, Debug, Default)]
pub struct AttackOutput {
    pub cancel: bool,
    pub crit:   bool,
}

/// A module's screen rect for click hit-testing.  Set by hud helper.
#[derive(Clone, Debug, Default)]
pub struct ModuleRect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    pub module_index: usize,
}

// ── main struct ───────────────────────────────────────────────────────────────

pub struct LiquidHorizon {
    pub modules:       ModuleManager,
    pub gui:           gui::ClickGui,
    pub player:        PlayerSnapshot,
    pub entities:      Vec<EntitySnapshot>,
    pub enabled:       bool,
    pub notifications: Vec<(String, bool)>,
    ka_last_tick:      u64,
    trigger_last_tick: u64,
    pub tick:          u64,
    /// Rects for each drawn module row (set by hud helper, read by key helper).
    pub module_rects:  Vec<ModuleRect>,
    /// Category panel rects [Combat, Movement, Render, Misc] — (x, y, w, h).
    pub panel_rects:   [[f32; 4]; 4],
}

impl LiquidHorizon {
    fn new() -> Self {
        Self {
            modules:       ModuleManager::new(),
            gui:           gui::ClickGui::new(),
            player:        PlayerSnapshot::default(),
            entities:      Vec::new(),
            enabled:       true,
            notifications: Vec::new(),
            ka_last_tick:  0,
            trigger_last_tick: 0,
            tick:          0,
            module_rects:  Vec::new(),
            panel_rects:   [[0.0; 4]; 4],
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Public API
// ═══════════════════════════════════════════════════════════════════════════════

/// Called from frame.rs each client frame.
pub fn on_frame(
    _elapsed_ms: u64,
    block_below_is_air: bool,
    block_in_hotbar_slot: Option<i32>,
    crosshair_entity: Option<u32>,
) -> FrameOutput {
    let mut s = state();
    let mut out = FrameOutput::default();
    out.gui_open = s.gui.open;

    if !s.enabled {
        return out;
    }

    // ── FullBright ────────────────────────────────────────────────────────────
    if s.modules.is_on("fullbright") {
        let gamma = s.modules.float_setting("fullbright", "gamma").unwrap_or(10.0);
        out.fullbright_gamma = Some(gamma as f64);
    }

    // ── Scaffold: client-side block placement ─────────────────────────────────
    if s.modules.is_on("scaffold") && block_below_is_air {
        if let Some(slot) = block_in_hotbar_slot {
            // Place directly below the player's feet
            out.scaffold_place = Some(([0, -1, 0], slot));
        }
    }

    // ── Triggerbot ────────────────────────────────────────────────────────────
    if s.modules.is_on("triggerbot") {
        let delay = s.modules.float_setting("triggerbot", "delay_ms").unwrap_or(50.0) as u64;
        let ticks_delay = (delay / 50).max(1);
        if let Some(eid) = crosshair_entity {
            if s.tick.wrapping_sub(s.trigger_last_tick) >= ticks_delay {
                out.scaffold_place = None; // don't scaffold+trigger same frame
                // Reuse killaura_target pattern — frame hook applies the attack
                s.trigger_last_tick = s.tick;
                // Stored so frame.rs can emit it
                drop(s);
                return FrameOutput {
                    gui_open:          out.gui_open,
                    fullbright_gamma:  out.fullbright_gamma,
                    scaffold_place:    None,
                };
            }
        }
    }

    out
}

/// HUD module list for hud.rs helper.
/// Returns (module_index, display_name, tag, enabled, category_index).
pub fn hud_modules() -> Vec<(usize, String, String, bool, usize)> {
    let s = state();
    if !s.enabled { return Vec::new(); }
    s.modules
        .modules
        .iter()
        .enumerate()
        .map(|(i, m)| {
            let cat = match m.category {
                modules::ModuleCategory::Combat   => 0,
                modules::ModuleCategory::Movement => 1,
                modules::ModuleCategory::Render   => 2,
                modules::ModuleCategory::Misc     => 3,
            };
            (i, m.name.clone(), m.tag(), m.enabled, cat)
        })
        .collect()
}

/// Whether the ClickGUI is open (fast path for key.rs).
pub fn gui_open() -> bool {
    state().gui.open
}

/// Called from key.rs.
pub fn on_key(key: &str, pressed: bool) {
    if !pressed { return; }
    let mut s = state();

    // Global GUI toggle — Right Shift
    if key == "key.keyboard.right.shift" {
        s.gui.open = !s.gui.open;
        return;
    }
    // Master kill switch — Insert
    if key == "key.keyboard.insert" {
        s.enabled = !s.enabled;
        s.gui.open = false;
        return;
    }
    // GUI close on Escape
    if key == "key.keyboard.escape" && s.gui.open {
        s.gui.open = false;
        return;
    }

    // Module keybinds — use index to avoid iterator borrow conflict
    // FIX E0505: find by index first, then mutate and push notification
    let idx = s.modules.modules
        .iter()
        .position(|m| m.keybind.as_deref() == Some(key));
    if let Some(i) = idx {
        s.modules.modules[i].enabled = !s.modules.modules[i].enabled;
        let note = (s.modules.modules[i].name.clone(), s.modules.modules[i].enabled);
        s.notifications.push(note);
    }
}

/// Called from key.rs when left mouse is clicked while GUI is open.
/// `cx`, `cy` are screen GUI-pixel coordinates from `game.native_cursor()`.
pub fn on_gui_click(cx: f32, cy: f32) {
    let mut s = state();
    if !s.gui.open { return; }

    // Hit-test each module rect
    let rects = s.module_rects.clone();
    for r in &rects {
        if cx >= r.x && cx < r.x + r.w && cy >= r.y && cy < r.y + r.h {
            let i = r.module_index;
            if i < s.modules.modules.len() {
                s.modules.modules[i].enabled = !s.modules.modules[i].enabled;
                let note = (s.modules.modules[i].name.clone(), s.modules.modules[i].enabled);
                s.notifications.push(note);
            }
            return;
        }
    }

    // Hit-test category panel headers to switch panels
    let panels = s.panel_rects;
    for (pi, rect) in panels.iter().enumerate() {
        let [px, py, pw, ph] = *rect;
        if cx >= px && cx < px + pw && cy >= py && cy < py + 14.0 {
            s.gui.active_panel = pi;
            return;
        }
    }
}

/// Store module rects (written by hud.rs after draw, read by on_gui_click).
pub fn set_module_rects(rects: Vec<ModuleRect>, panel_rects: [[f32; 4]; 4]) {
    let mut s = state();
    s.module_rects = rects;
    s.panel_rects  = panel_rects;
}

pub fn active_gui_panel() -> usize {
    state().gui.active_panel
}

/// Called from player_tick.rs each server tick.
pub fn on_player_tick(
    player: PlayerSnapshot,
    entities: Vec<EntitySnapshot>,
) -> TickOutput {
    let mut s = state();
    s.tick = s.tick.wrapping_add(1);
    s.player   = player.clone();
    s.entities = entities.clone();

    if !s.enabled { return TickOutput::default(); }

    let mut out = TickOutput::default();

    // ── KillAura ─────────────────────────────────────────────────────────────
    if s.modules.is_on("killaura") {
        let range      = s.modules.float_setting("killaura", "range").unwrap_or(4.5);
        let cps        = s.modules.float_setting("killaura", "cps").unwrap_or(10.0);
        let ticks_wait = (20.0 / cps.max(1.0)) as u64;
        if s.tick.wrapping_sub(s.ka_last_tick) >= ticks_wait {
            if let Some(tid) = nearest_attackable(&s.entities, &player, range as f64) {
                out.killaura_target = Some(tid);
                s.ka_last_tick = s.tick;
            }
        }
    }

    // ── AimAssist ────────────────────────────────────────────────────────────
    if s.modules.is_on("aimassist") {
        let range = s.modules.float_setting("aimassist", "range").unwrap_or(8.0);
        let speed = s.modules.float_setting("aimassist", "speed").unwrap_or(2.0);
        if let Some(tid) = nearest_attackable(&s.entities, &player, range as f64) {
            if let Some(e) = s.entities.iter().find(|e| e.id == tid) {
                let (ny, np) = look_at(&player, e, speed);
                out.aim_yaw   = Some(ny);
                out.aim_pitch = Some(np);
            }
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

    // ── Velocity (AntiKnockback) ──────────────────────────────────────────────
    if s.modules.is_on("velocity") {
        let h = s.modules.float_setting("velocity", "horizontal").unwrap_or(0.0);
        if h <= 5.0 {
            out.zero_velocity = true;
        }
    }

    out
}

/// Called from hurt_enemy.rs.
pub fn on_hurt_enemy(entity: u32, _kind: &str) -> AttackOutput {
    let s = state();
    if !s.enabled { return AttackOutput::default(); }
    let _ = entity;
    AttackOutput { cancel: false, crit: s.modules.is_on("criticals") }
}

/// Called from attack_entity.rs.
pub fn on_attack_entity(_entity: i32, _kind: &str) -> AttackOutput {
    let s = state();
    if !s.enabled { return AttackOutput::default(); }
    AttackOutput { cancel: false, crit: s.modules.is_on("criticals") }
}

/// Called from inventory_tick.rs.  Returns Some(slot) to eat from.
pub fn on_inventory_tick(
    slot: i32,
    selected: bool,
    _health: f32,
    food: i32,
) -> Option<i32> {
    let s = state();
    if !s.enabled || !s.modules.is_on("autoeat") { return None; }
    let thresh = s.modules.float_setting("autoeat", "food_threshold").unwrap_or(16.0) as i32;
    if food <= thresh && selected { Some(slot) } else { None }
}

/// Called from use_item.rs.
pub fn on_use_item(item: &str) -> bool {
    let s = state();
    if !s.enabled || !s.modules.is_on("fastuse") { return false; }
    item.contains("potion") || item.contains("milk_bucket")
}

/// Called from command.rs.  Returns Some(chat reply) if ours.
pub fn on_command(command: &str) -> Option<String> {
    if !command.starts_with("lb ") && command != "lb" {
        return None;
    }
    let rest = command.strip_prefix("lb ").unwrap_or("").trim();
    let mut s = state();

    if rest.is_empty() || rest == "help" {
        let list: Vec<String> = s.modules.modules.iter().map(|m| m.name.clone()).collect();
        return Some(format!("§aLiquidHorizon§f: {}", list.join(", ")));
    }

    // .lb <module>  → toggle
    let idx = s.modules.modules.iter().position(|m| m.name.eq_ignore_ascii_case(rest));
    if let Some(i) = idx {
        s.modules.modules[i].enabled = !s.modules.modules[i].enabled;
        let (name, on) = (s.modules.modules[i].name.clone(), s.modules.modules[i].enabled);
        return Some(format!(
            "{}{} {}",
            if on { "§a" } else { "§c" },
            name,
            if on { "ON" } else { "OFF" }
        ));
    }

    // .lb <module> <key> <value>
    let parts: Vec<&str> = rest.splitn(3, ' ').collect();
    if parts.len() == 3 {
        if let Ok(val) = parts[2].parse::<f32>() {
            if let Some(mi) = s.modules.modules.iter().position(|m| m.name.eq_ignore_ascii_case(parts[0])) {
                if s.modules.modules[mi].floats.contains_key(parts[1]) {
                    s.modules.modules[mi].floats.insert(parts[1].into(), val);
                    return Some(format!("§aSet §f{}.{} §a= §f{}", parts[0], parts[1], val));
                }
            }
        }
    }

    Some(format!("§cUnknown module: {}", rest))
}

pub fn command_names() -> &'static [&'static str] { &["lb"] }

pub fn mod_info() -> (&'static str, &'static str, &'static str) {
    (
        "liquid_horizon",
        "LiquidHorizon 1.0",
        "LiquidBounce ported to Horizon — KillAura, ESP, Scaffold, AimAssist and more.",
    )
}

/// Settings page button labels.
pub fn settings_buttons() -> Vec<String> {
    let s = state();
    s.modules.modules.iter().map(|m| {
        format!("{} — {}", m.name, if m.enabled { "§aON" } else { "§cOFF" })
    }).collect()
}

/// A settings button was clicked.  FIX E0597: use index, no iterator borrow.
pub fn on_settings_click(button: usize) {
    let mut s = state();
    if button < s.modules.modules.len() {
        s.modules.modules[button].enabled = !s.modules.modules[button].enabled;
    }
}

/// Pop next notification (module toggled).
pub fn pop_notification() -> Option<(String, bool)> {
    state().notifications.pop()
}

/// ESP data: (id, kind, x, y, z, health, is_player).
pub fn esp_entities() -> Vec<(u32, String, f64, f64, f64, f32, bool)> {
    let s = state();
    if !s.enabled || !s.modules.is_on("esp") { return Vec::new(); }
    s.entities.iter().map(|e| {
        (e.id, e.kind.clone(), e.x, e.y, e.z, e.health, e.is_player)
    }).collect()
}

/// Tooltip extra lines.
pub fn on_tooltip(item: &str) -> Vec<String> {
    let s = state();
    if !s.enabled || !s.modules.is_on("iteminfo") { return Vec::new(); }
    vec![format!("§7[LH] §fID: §e{}", item)]
}

/// Reach module — max attack range.  Returns None if reach module is off.
pub fn reach_range() -> Option<f32> {
    let s = state();
    if !s.enabled || !s.modules.is_on("reach") { return None; }
    Some(s.modules.float_setting("reach", "max").unwrap_or(3.5))
}

/// Whether a module is enabled (the client's tick hook reads this).
pub fn module_on(name: &str) -> bool {
    let s = state();
    s.enabled && s.modules.is_on(name)
}

/// A module's float setting, if it has one.
pub fn module_float(module: &str, key: &str) -> Option<f32> {
    state().modules.float_setting(module, key)
}

/// A module's bool setting, if it has one.
pub fn module_bool(module: &str, key: &str) -> Option<bool> {
    state()
        .modules
        .modules
        .iter()
        .find(|m| m.name.eq_ignore_ascii_case(module))?
        .bools
        .get(key)
        .copied()
}

/// Whether modules with server-side movement effects are on.
pub fn velocity_on() -> bool {
    let s = state();
    s.enabled && s.modules.is_on("velocity")
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
            (dx*dx + dy*dy + dz*dz).sqrt() <= range
                && e.health > 0.0
                && !e.kind.contains("item")
                && !e.kind.contains("experience_orb")
        })
        .min_by(|a, b| {
            dist2(a, player).partial_cmp(&dist2(b, player))
                .unwrap_or(std::cmp::Ordering::Equal)
        })
        .map(|e| e.id)
}

fn dist2(e: &EntitySnapshot, p: &PlayerSnapshot) -> f64 {
    let (dx, dy, dz) = (e.x - p.x, e.y - p.y, e.z - p.z);
    dx*dx + dy*dy + dz*dz
}

fn look_at(player: &PlayerSnapshot, entity: &EntitySnapshot, speed: f32) -> (f32, f32) {
    let dx = entity.x - player.x;
    let dy = (entity.y + 0.85) - (player.y + 1.62);
    let dz = entity.z - player.z;
    let target_yaw   = -(dx.atan2(dz) as f32).to_degrees();
    let horiz        = (dx*dx + dz*dz).sqrt() as f32;
    let target_pitch = -(dy.atan2(horiz as f64) as f32).to_degrees();
    let step         = speed * 2.0;
    (
        player.yaw   + angle_diff(target_yaw,   player.yaw).clamp(-step, step),
        player.pitch + angle_diff(target_pitch, player.pitch).clamp(-step, step),
    )
}

fn angle_diff(a: f32, b: f32) -> f32 {
    let mut d = a - b;
    while d >  180.0 { d -= 360.0; }
    while d < -180.0 { d += 360.0; }
    d
}
