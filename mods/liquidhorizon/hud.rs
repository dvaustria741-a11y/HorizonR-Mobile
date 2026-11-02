//! hud hook — full LiquidHorizon overlay:
//!   • Right-side enabled-module waterfall
//!   • ClickGUI panels (open with RShift)
//!   • ESP radar (2-D top-down mini-map)
//!   • Notification toast (bottom-left)

use liquid_horizon_engine as lh;
use crate::extras::Corner;

// ── palette ───────────────────────────────────────────────────────────────────
const C_BG:       u32 = 0xCC_0C_0E_18;   // dark translucent panel
const C_HDR:      u32 = 0xFF_06_1A_2C;   // panel header
const C_ACCENT:   u32 = 0xFF_00_E5_C8;   // LB teal
const C_ON:       u32 = 0xFF_D8_FF_F8;
const C_OFF:      u32 = 0xFF_6A_7A_7D;
const C_SHADOW:   u32 = 0x99_00_00_00;
const C_NOTIF:    u32 = 0xEE_06_10_1E;
const C_ESP_MOB:  u32 = 0xFF_FF_55_55;   // red for mobs
const C_ESP_PLR:  u32 = 0xFF_55_FF_55;   // green for players
const C_ROW_ON:   u32 = 0x44_00_E5_C8;   // tinted row bg when enabled
const C_ROW_HVRL: u32 = 0x55_FF_FF_FF;   // hover tint (click zone)

// panel dims (GUI pixels)
const PANEL_W:  f32 = 110.0;
const PANEL_PAD: f32 = 4.0;
const HDR_H:    f32 = 13.0;
const ROW_H:    f32 = 11.0;
const GAP:      f32 = 3.0;
const PANEL_X0: f32 = 4.0;

// category names
const CATS: [&str; 4] = ["Combat", "Movement", "Render", "Misc"];

let sw = batch.screen_width();
let sh = batch.screen_height();

// ── 1. Enabled-module waterfall (top-right, always visible while playing) ─────
if game.native_playing() {
    let mods = lh::hud_modules();
    let enabled: Vec<_> = mods.iter().filter(|(_, _, _, en, _)| *en).collect();
    if !enabled.is_empty() {
        let mut y = 4.0_f32;
        for (_, name, tag, _, _) in &enabled {
            let label = if tag.is_empty() {
                name.clone()
            } else {
                format!("{} §7{}", name, tag)
            };
            let tw   = game.native_text_width(&label);
            let bw   = tw + 8.0;
            let x    = sw - bw - 2.0;
            // shadow + bg
            game.native_fill(batch, [x + 1.0, y + 1.0, bw, ROW_H], C_SHADOW);
            game.native_fill(batch, [x, y, bw, ROW_H], C_BG);
            game.native_fill(batch, [x, y, 2.0, ROW_H], C_ACCENT);
            game.native_text(batch, &label, [x + 4.0, y + 1.5], C_ON >> 8, false);
            y += ROW_H + 1.0;
        }
    }
}

// ── 2. ClickGUI ───────────────────────────────────────────────────────────────
let gui_open = lh::gui_open();
if gui_open {
    let all_mods = lh::hud_modules();
    let active   = lh::active_gui_panel();

    let mut new_rects: Vec<lh::ModuleRect> = Vec::new();
    let mut panel_rects_out: [[f32; 4]; 4] = [[0.0; 4]; 4];

    // Overall dark backdrop
    game.native_fill(batch, [0.0, 0.0, sw, sh], 0xAA_00_00_00);

    // Tab bar across the top
    let tab_w = PANEL_W;
    let tab_h = 16.0_f32;
    let total_w = tab_w * 4.0 + GAP * 3.0;
    let tab_x0 = (sw - total_w) / 2.0;
    let tab_y  = 20.0_f32;

    for (ci, cat) in CATS.iter().enumerate() {
        let tx = tab_x0 + ci as f32 * (tab_w + GAP);
        let is_active = ci == active;
        let bg = if is_active { C_ACCENT } else { C_HDR };
        let fg = if is_active { C_BG } else { C_OFF };
        game.native_fill(batch, [tx, tab_y, tab_w, tab_h], bg);
        let tw = game.native_text_width(cat);
        game.native_text(batch, cat, [tx + (tab_w - tw) / 2.0, tab_y + 3.0], fg >> 8, false);
        panel_rects_out[ci] = [tx, tab_y, tab_w, tab_h];
    }

    // Active panel body
    let panel_y = tab_y + tab_h + 2.0;
    let panel_x = tab_x0 + active as f32 * (tab_w + GAP);
    let mods_in_cat: Vec<_> = all_mods.iter()
        .filter(|(_, _, _, _, cat)| *cat == active)
        .collect();
    let panel_h = mods_in_cat.len() as f32 * (ROW_H + 1.0) + 4.0;

    game.native_fill(batch, [panel_x, panel_y, tab_w, panel_h], C_BG);
    game.native_fill(batch, [panel_x, panel_y, tab_w, 1.0], C_ACCENT);

    let mut ry = panel_y + 3.0;
    for (idx, name, tag, enabled, _) in &mods_in_cat {
        let row_bg = if *enabled { C_ROW_ON } else { 0x00_00_00_00 };
        game.native_fill(batch, [panel_x + 1.0, ry, tab_w - 2.0, ROW_H], row_bg);

        // enabled indicator dot
        let dot_col = if *enabled { C_ACCENT } else { C_OFF };
        game.native_fill(batch, [panel_x + 3.0, ry + 3.5, 4.0, 4.0], dot_col);

        let label = if tag.is_empty() {
            name.clone()
        } else {
            format!("{} §7{}", name, tag)
        };
        let fc = if *enabled { C_ON } else { C_OFF };
        game.native_text(batch, &label, [panel_x + 10.0, ry + 1.5], fc >> 8, false);

        new_rects.push(lh::ModuleRect {
            x: panel_x + 1.0,
            y: ry,
            w: tab_w - 2.0,
            h: ROW_H,
            module_index: *idx,
        });

        ry += ROW_H + 1.0;
    }

    // Keybind hint at bottom
    let hint = "§7RShift: close  |  lb <module> in chat";
    let hw   = game.native_text_width(hint);
    game.native_text(batch, hint, [(sw - hw) / 2.0, panel_y + panel_h + 4.0], C_OFF >> 8, false);

    // Commit rects to engine for click hit-testing
    lh::set_module_rects(new_rects, panel_rects_out);
}

// ── 3. ESP radar — 2-D top-down (bottom-right corner) ─────────────────────────
let esp = lh::esp_entities();
if !esp.is_empty() && game.native_playing() {
    let r_size = 70.0_f32;
    let r_scale = 4.0_f32;         // 1 block = 4 pixels
    let cx = sw - r_size / 2.0 - 4.0;
    let cy = sh - r_size / 2.0 - 4.0;

    // background circle-ish (square for now)
    game.native_fill(batch, [cx - r_size/2.0, cy - r_size/2.0, r_size, r_size], 0x99_00_00_00);
    game.native_fill(batch, [cx - r_size/2.0, cy - r_size/2.0, r_size, 1.0], C_ACCENT);
    game.native_fill(batch, [cx - r_size/2.0, cy + r_size/2.0 - 1.0, r_size, 1.0], C_ACCENT);
    game.native_fill(batch, [cx - r_size/2.0, cy - r_size/2.0, 1.0, r_size], C_ACCENT);
    game.native_fill(batch, [cx + r_size/2.0 - 1.0, cy - r_size/2.0, 1.0, r_size], C_ACCENT);

    // player dot (centre)
    game.native_fill(batch, [cx - 1.5, cy - 1.5, 3.0, 3.0], 0xFF_FF_FF_00);

    // entity dots
    if let Some(p) = game.native_player() {
        for (_, kind, ex, ey, ez, hp, is_plr) in &esp {
            let _ = (ey, hp);
            let dx = (ex - p.x) as f32 * r_scale;
            let dz = (ez - p.z) as f32 * r_scale;
            // rotate by player yaw
            let yaw_r = p.yaw.to_radians();
            let rx = dx * yaw_r.cos() - dz * yaw_r.sin();
            let rz = dx * yaw_r.sin() + dz * yaw_r.cos();
            let sx = cx + rx;
            let sz = cy + rz;
            // clip to radar
            if sx < cx - r_size/2.0 + 2.0 || sx > cx + r_size/2.0 - 2.0 { continue; }
            if sz < cy - r_size/2.0 + 2.0 || sz > cy + r_size/2.0 - 2.0 { continue; }
            let dot_col = if *is_plr { C_ESP_PLR } else { C_ESP_MOB };
            game.native_fill(batch, [sx - 1.5, sz - 1.5, 3.0, 3.0], dot_col);
        }
    }
}

// ── 4. Notification toast ─────────────────────────────────────────────────────
if let Some((name, enabled)) = lh::pop_notification() {
    let color  = if enabled { "§a" } else { "§c" };
    let status = if enabled { "enabled" } else { "disabled" };
    let msg    = format!("{}{} {}", color, name, status);
    let tw     = game.native_text_width(&msg);
    let bw     = tw + 12.0;
    let bh     = 13.0_f32;
    game.native_fill(batch, [3.0, sh - bh - 4.0, bw, bh], C_NOTIF);
    game.native_fill(batch, [3.0, sh - bh - 4.0, 2.0, bh], C_ACCENT);
    game.native_text(batch, &msg, [7.0, sh - bh + 1.0], C_ON >> 8, false);
}
