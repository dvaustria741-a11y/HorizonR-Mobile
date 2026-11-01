//! frame hook: runs once per client frame in/out of a world.
//! Renders the ClickGUI overlay and ESP boxes.

use liquid_horizon_engine as lh;

let elapsed_ms = elapsed.as_millis() as u64;
let out = lh::on_frame(elapsed_ms);

if !game.native_playing() && !out.gui_open {
    return;
}

// ── ClickGUI overlay ─────────────────────────────────────────────────────────
// The actual draw happens in hud.rs via native_hud.
// Here we just suppress escape key when the GUI is open so it closes LH first.
// (No direct key suppression available — handled in key.rs.)

// ── Notification toasts (drawn as quick HUD fills) ───────────────────────────
// Pop is done in hud.rs each frame; nothing extra needed here.
