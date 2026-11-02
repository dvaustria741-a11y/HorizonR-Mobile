//! key hook — module keybinds + ClickGUI mouse click.

use liquid_horizon_engine as lh;

lh::on_key(key, pressed);

// ── ClickGUI mouse click ──────────────────────────────────────────────────────
if pressed && key == "key.mouse.left" && lh::gui_open() {
    let [cx, cy] = game.native_cursor();
    lh::on_gui_click(cx, cy);
}
