//! LiquidHorizon, built into the client: the hook points of `native_hooks.rs` call these.
//! The engine (`mods/liquidhorizon/engine`) owns every module's state.

use super::{Game, native_hooks::NativeMod};
use horizon_client::{extras_hud::Corners, hud::HudBatch, items::ItemStack, text::Text};
use liquid_horizon_engine as lh;
use std::{sync::atomic::{AtomicBool, Ordering}, time::Duration};

/// Flight was on last tick: turning it off hands the abilities back.
static FLIGHT_ON: AtomicBool = AtomicBool::new(false);

/// The mod's id: its settings folder and its keys' lines.
const ID: &str = "liquid_horizon";

impl Game<'_> {
    pub(super) fn lh_frame(&mut self, elapsed: Duration) {
        let _ = lh::on_frame(elapsed.as_millis() as u64, false, None, None);
    }
    pub(super) fn lh_key(&mut self, key: &str, pressed: bool) {
        let _ = lh::on_key(key, pressed);
    }
    pub(super) fn lh_command(&mut self, command: &str, ran: &mut bool) {
        if let Some(reply) = lh::on_command(command) {
            self.native_chat(&reply);
            *ran = true;
        }
    }
    pub(super) fn lh_commands(&mut self, commands: &mut Vec<&'static str>) {
        commands.extend_from_slice(lh::command_names());
    }
    pub(super) fn lh_mods(&mut self, mods: &mut Vec<NativeMod>) {
        let (_, title, description) = lh::mod_info();
        let key = |name: &str, label: &str, key: &str| [name.to_owned(), label.to_owned(), key.to_owned()];
        mods.push(NativeMod {
            id: ID.to_owned(),
            title: title.to_owned(),
            description: description.to_owned(),
            keys: vec![
                key("gui", "LiquidHorizon GUI", "key.keyboard.right.shift"),
                key("toggle", "Toggle All", "key.keyboard.insert"),
                key("killaura", "KillAura", "key.keyboard.r"),
                key("aimassist", "AimAssist", "key.keyboard.z"),
                key("esp", "ESP", "key.keyboard.x"),
                key("speed", "Speed", "key.keyboard.v"),
                key("flight", "Flight", "key.keyboard.f"),
                key("scaffold", "Scaffold", "key.keyboard.g"),
                key("nofall", "NoFall", "key.keyboard.n"),
                key("fullbright", "FullBright", "key.keyboard.b"),
                key("criticals", "Criticals", "key.keyboard.c"),
            ],
        });
    }
    pub(super) fn lh_settings(&mut self, mod_id: &str, buttons: &mut Vec<String>) {
        if mod_id == ID {
            buttons.extend(lh::settings_buttons());
        }
    }
    pub(super) fn lh_settings_click(&mut self, mod_id: &str, button: usize) {
        if mod_id == ID {
            lh::on_settings_click(button);
        }
    }
    pub(super) fn lh_tooltip(&self, stack: &ItemStack, lines: &mut Vec<(Text, u32)>) {
        for line in lh::on_tooltip(&stack.item) {
            lines.push((Text::literal(line), u32::MAX));
        }
    }
    pub(super) fn lh_use_item(&mut self, stack: &ItemStack, claimed: &mut bool) {
        if lh::on_use_item(&stack.item) {
            *claimed = true;
        }
    }
    pub(super) fn lh_attack_entity(&mut self, entity: i32, kind: &str, canceled: &mut bool) {
        if lh::on_attack_entity(entity, kind).cancel {
            *canceled = true;
        }
    }
    /// The module list (top right) and the toggle notifications (bottom left).
    pub(super) fn lh_hud(&mut self, batch: &mut HudBatch, corners: &mut Corners) {
        let _ = corners;
        const BG: u32 = 0xCC10_1028;
        const ACCENT: u32 = 0xFF0A_F0C8;
        const TEXT: u32 = 0x00E8_FFFF;
        if self.native_playing() {
            let mut y = 4.0_f32;
            for (_, name, tag, enabled, _) in lh::hud_modules() {
                if !enabled {
                    continue;
                }
                let label = if tag.is_empty() {
                    name
                } else {
                    format!("{name} \u{a7}7{tag}")
                };
                let box_w = self.native_text_width(&label) + 8.0;
                let x = batch.width - box_w - 2.0;
                self.native_fill(batch, [x, y, box_w, 10.0], BG);
                self.native_fill(batch, [x, y, 2.0, 10.0], ACCENT);
                self.native_text(batch, &label, [x + 4.0, y + 1.5], TEXT, true);
                y += 11.0;
            }
        }
        if let Some((name, enabled)) = lh::pop_notification() {
            let color = if enabled { "\u{a7}a" } else { "\u{a7}c" };
            let status = if enabled { "enabled" } else { "disabled" };
            let msg = format!("{color}{name} \u{a7}f{status}");
            let box_w = self.native_text_width(&msg) + 10.0;
            let y = batch.height - 16.0;
            self.native_fill(batch, [4.0, y, box_w, 12.0], BG);
            self.native_fill(batch, [4.0, y, 2.0, 12.0], ACCENT);
            self.native_text(batch, &msg, [8.0, y + 1.5], TEXT, true);
        }
    }
    /// Once a tick, in the window's game: the modules that act on the player.
    pub(super) fn lh_tick(&mut self) {
        if !self.native_window() || !self.native_playing() {
            return;
        }
        let Some(p) = self.player.as_ref() else {
            return;
        };
        let snapshot = lh::PlayerSnapshot {
            x: p.position.x,
            y: p.position.y,
            z: p.position.z,
            yaw: self.yaw,
            pitch: self.pitch,
            health: 20.0,
            food: 20,
            on_ground: p.ground.on_ground,
            delta_y: p.velocity.y,
        };
        let entities: Vec<lh::EntitySnapshot> = self
            .mobs
            .iter()
            .filter(|m| m.alive())
            .map(|m| lh::EntitySnapshot {
                id: m.id,
                kind: format!("minecraft:{}", m.kind.id.trim_start_matches("minecraft:")),
                x: m.position.x,
                y: m.position.y,
                z: m.position.z,
                health: m.health,
                is_player: false,
            })
            .collect();
        let out = lh::on_player_tick(snapshot, entities);

        // AimAssist: the view turns toward the nearest target.
        if let (Some(yaw), Some(pitch)) = (out.aim_yaw, out.aim_pitch) {
            self.yaw = yaw;
            self.pitch = pitch;
        }

        // KillAura: face the target, hit it, swing.
        if let Some(id) = out.killaura_target
            && let Some(index) = self.mobs.iter().position(|m| m.id == id)
        {
            if lh::module_bool("killaura", "rotate").unwrap_or(true)
                && let Some(p) = self.player.as_ref()
            {
                let mob = &self.mobs[index];
                let dx = mob.position.x - p.position.x;
                let dy = (mob.position.y + 0.85) - (p.position.y + 1.62);
                let dz = mob.position.z - p.position.z;
                let horizontal = (dx * dx + dz * dz).sqrt();
                self.yaw = -(dx.atan2(dz) as f32).to_degrees();
                self.pitch = -(dy.atan2(horizontal) as f32).to_degrees();
            }
            if lh::module_on("criticals")
                && let Some(p) = self.player.as_mut()
            {
                // Player.canCriticalAttack: falling, off the ground, not sprinting.
                p.ground.on_ground = false;
                p.sprinting = false;
                p.fall_distance = p.fall_distance.max(0.1);
            }
            self.attack_mob(index);
            if lh::module_bool("killaura", "swing").unwrap_or(true) {
                self.swing_arm();
            }
        }

        let creative = self.creative;
        let Some(p) = self.player.as_mut() else {
            return;
        };

        // AutoSprint: sprint while moving forward.
        if out.force_sprint && p.move_vector[1] > 0.0 && !p.mobility_restricted {
            p.sprinting = true;
        }
        // NoFall: no fall distance, no fall damage.
        if out.no_fall {
            p.fall_distance = 0.0;
        }
        // Flight: creative flight, at the module's speed.
        if lh::module_on("flight") {
            FLIGHT_ON.store(true, Ordering::Relaxed);
            p.may_fly = true;
            p.flying = true;
            p.flying_speed = 0.05 * lh::module_float("flight", "speed").unwrap_or(1.0);
        } else if FLIGHT_ON.swap(false, Ordering::Relaxed) {
            p.flying = false;
            p.may_fly = creative;
            p.flying_speed = 0.05;
        }
        // Speed: lift the walking speed toward `0.22 * multiplier` blocks a tick.
        if lh::module_on("speed") && !p.flying && (p.move_vector[0] != 0.0 || p.move_vector[1] != 0.0) {
            let target = 0.22 * f64::from(lh::module_float("speed", "multiplier").unwrap_or(1.3));
            let (vx, vz) = (p.velocity.x, p.velocity.z);
            let current = (vx * vx + vz * vz).sqrt();
            if current > 0.01 && current < target {
                let scale = target / current;
                p.velocity.x *= scale;
                p.velocity.z *= scale;
            }
        }
    }
}
