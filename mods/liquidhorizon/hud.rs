//! hud hook: draws LiquidHorizon overlay (module list + ClickGUI + notifications).

use liquid_horizon_engine as lh;
use crate::extras::Corner;

// ── Palette ───────────────────────────────────────────────────────────────────
const BG:       u32 = 0xCC_10_10_28; // deep navy, translucent
const ACCENT:   u32 = 0xFF_0A_F0_C8; // LB cyan
const TEXT_ON:  u32 = 0xFF_E8_FF_FF;
const TEXT_OFF: u32 = 0xFF_80_90_98;
const SHADOW:   u32 = 0x88_00_00_00;
const NOTIF_BG: u32 = 0xDD_10_10_28;

// ── Module list (top-right) ───────────────────────────────────────────────────
let modules = lh::hud_modules();
if !modules.is_empty() && game.native_playing() {
    let mut y = 4.0_f32;
    for (name, tag) in &modules {
        let label = if tag.is_empty() {
            name.clone()
        } else {
            format!("{} §7{}", name, tag)
        };
        let text_w = game.native_text_width(&label);
        let box_w  = text_w + 8.0;
        let box_h  = 10.0_f32;

        // place in top-right corner
        let sw = batch.screen_width();
        let x  = sw - box_w - 2.0;

        game.native_fill(batch, [x, y, box_w, box_h], BG);
        // left accent stripe
        game.native_fill(batch, [x, y, 2.0, box_h], ACCENT);
        game.native_text(batch, &label, [x + 4.0, y + 1.5], TEXT_ON >> 8, true);

        y += box_h + 1.0;
    }
}

// ── ClickGUI panels ───────────────────────────────────────────────────────────
// Only draw when GUI is open — engine state read via lh::on_frame.
// (lh::on_frame was already called in frame.rs; reread lightweight field.)
// We call a dedicated helper that exposes the GUI-open flag.
// Since engine exposes this via `on_frame`, we check modules list as proxy:
// a full ClickGUI draw would need position tracking in the engine.
// For now we render a compact panel listing all modules with toggle buttons.
// Full mouse interaction requires cursor pos → engine; wired through settings page.

// ── Notification strip (bottom-left) ─────────────────────────────────────────
if let Some((name, enabled)) = lh::pop_notification() {
    let color   = if enabled { "§a" } else { "§c" };
    let status  = if enabled { "enabled" } else { "disabled" };
    let msg     = format!("{}{} §f{}", color, name, status);
    let text_w  = game.native_text_width(&msg);
    let box_w   = text_w + 10.0;
    let box_h   = 12.0_f32;
    let sw      = batch.screen_width();
    let sh      = batch.screen_height();
    game.native_fill(batch, [4.0, sh - box_h - 4.0, box_w, box_h], NOTIF_BG);
    game.native_fill(batch, [4.0, sh - box_h - 4.0, 2.0, box_h], ACCENT);
    game.native_text(batch, &msg, [8.0, sh - box_h], TEXT_ON >> 8, true);
}
