//! LiquidHorizon, built into the client: the hook points of `native_hooks.rs` call these.
//! The engine (`mods/liquidhorizon/engine`) owns every module's state.

use super::{Game, native_hooks::NativeMod};
use horizon_client::{extras_hud::Corners, hud::HudBatch, items::ItemStack, text::Text};
use liquid_horizon_engine as lh;
use std::time::Duration;

/// The mod's id: its settings folder and its keys' lines.
const ID: &str = "liquid_horizon";

impl Game<'_> {
    pub(super) fn lh_frame(&mut self, elapsed: Duration) {
        let _ = lh::on_frame(elapsed.as_millis() as u64);
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
            title,
            description,
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
            for (name, tag) in lh::hud_modules() {
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
}
